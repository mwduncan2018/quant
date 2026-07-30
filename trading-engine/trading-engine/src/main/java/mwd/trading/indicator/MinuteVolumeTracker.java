package mwd.trading.indicator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.ib.client.Bar;
import com.ib.client.Decimal;

import mwd.trading.broker.ibkr.RequestRegistry;
import mwd.trading.domain.Stock;
import mwd.trading.marketdata.MarketDataInput;
import mwd.trading.marketdata.MarketDataInputStore;
import mwd.trading.state.StockLookup;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Publishes the two minute-volume figures the capitulation filter compares.
 *
 * <ul>
 * <li><b>Last minute volume</b> is the volume of the newest bar. During live
 * trading that is the minute still forming, so the figure grows through the
 * minute. This is deliberate: a volume climax is detected while it happens
 * rather than a minute after it ends.
 * <li><b>Average last 15 minute volume</b> is the mean volume of the 15
 * <em>completed</em> minute bars preceding that one, from the current session
 * only.
 * </ul>
 *
 * <p>
 * Excluding the forming minute from its own baseline keeps the relative-volume
 * ratio meaning what it says: a reading of 3.0 is a minute trading three times
 * the recent norm. Including it would silently raise the effective threshold,
 * because the spike would inflate the average it is measured against.
 *
 * <p>
 * The window covers the current session only. The previous day's closing ramp
 * carries far more volume than a typical minute, so letting it survive into the
 * next morning would inflate the opening baseline and suppress entries during
 * the most active period of the day.
 *
 * <p>
 * Until 15 completed same-session bars exist the baseline is published as
 * {@link Decimal#ZERO}, which callers already treat as unusable. With regular
 * trading hours starting at 09:30 the baseline therefore becomes available at
 * about 09:45. An incomplete window blocks rather than approximates.
 *
 * <p>
 * IBKR delivers bars from two callbacks. The initial historical dump arrives on
 * {@code historicalData}, then {@code keepUpToDate} sends the forming bar
 * repeatedly on {@code historicalDataUpdate} as its volume accumulates. A bar
 * is only committed to the window once a bar with a later timestamp proves it
 * closed.
 */
public class MinuteVolumeTracker {
	private static final Logger logger = LogManager.getLogger(MinuteVolumeTracker.class);

	/** Bars of history the baseline averages, excluding the bar under test. */
	static final int BASELINE_BARS = 15;

	private static final ZoneId NEW_YORK_ZONE = ZoneId.of("America/New_York");
	private static final DateTimeFormatter BAR_DATE = DateTimeFormatter.BASIC_ISO_DATE;
	private static final BigDecimal BASELINE_DIVISOR = BigDecimal.valueOf(BASELINE_BARS);

	private final StockLookup stocks;
	private final RequestRegistry registry;
	private final MarketDataInputStore inputStore;
	private final Map<String, VolumeWindow> windows = new ConcurrentHashMap<>();

	public MinuteVolumeTracker(
			StockLookup stocks, RequestRegistry registry, MarketDataInputStore inputStore) {
		this.stocks = stocks;
		this.registry = registry;
		this.inputStore = inputStore;
	}

	public void onHistoricalData(int reqId, Bar bar) {
		updateVolume(reqId, bar);
	}

	public void onHistoricalDataEnd(int reqId, String start, String end) {
		String ticker = registry.getTickerFor(reqId);
		if (ticker == null) {
			return;
		}

		VolumeWindow window = windows.get(ticker);
		int completedBars = window == null ? 0 : window.size();
		logger.info(
				"[{}] MinuteVolumeTracker.onHistoricalDataEnd - {} of {} baseline bars for session {}. "
						+ "Range: {} to {}",
				ticker, completedBars, BASELINE_BARS,
				window == null ? "none" : window.sessionDate(), start, end);
	}

	public void onHistoricalDataUpdate(int reqId, Bar bar) {
		updateVolume(reqId, bar);
	}

	private void updateVolume(int reqId, Bar bar) {
		String ticker = registry.getTickerFor(reqId);
		if (ticker == null || bar == null) {
			return;
		}

		Stock stock = stocks.getStock(ticker);
		BigDecimal volume = usableVolume(bar);
		if (volume == null) {
			// A bar with no usable volume tells us nothing. Leave the previous
			// reading rather than publishing a zero that reads as a quiet minute.
			return;
		}

		// The newest bar is the one the strategy tests, forming or not.
		stock.setLastMinuteVolume(Decimal.get(volume));

		MinuteBar observed = describe(ticker, bar, volume);
		if (observed == null) {
			return;
		}

		VolumeWindow window = windows.computeIfAbsent(ticker, key -> new VolumeWindow());
		synchronized (window) {
			MinuteBar pending = window.pending();
			if (pending == null) {
				window.setPending(observed);
				return;
			}
			if (observed.epochSecond() == pending.epochSecond()) {
				// The same minute re-delivered with more volume; still forming.
				window.setPending(observed);
				return;
			}
			if (observed.epochSecond() < pending.epochSecond()) {
				// A replayed history dump after a resubscribe. Ignore it until it
				// passes the newest bar already held, so the existing window keeps
				// working instead of being rebuilt backwards.
				return;
			}

			// A later minute began, so the pending bar is now complete.
			window.commit(pending);
			window.setPending(observed);
			publish(ticker, stock, window);
		}
	}

	private void publish(String ticker, Stock stock, VolumeWindow window) {
		Decimal baseline = window.baseline();
		stock.setAverageLast15MinuteVolume(baseline);
		if (!baseline.isZero()) {
			// Recorded only once the window is full, so a strategy asking whether
			// the baseline is ready is never told yes about a partial one.
			inputStore.record(ticker, MarketDataInput.MINUTE_VOLUME_BASELINE);
		}
	}

	/**
	 * Returns the bar volume, or null when it carries nothing usable. IBKR marks
	 * absent sizes with {@link Decimal#INVALID} and {@link Decimal#NaN}, and those
	 * hold {@link Long#MIN_VALUE} rather than a quantity.
	 */
	private static BigDecimal usableVolume(Bar bar) {
		Decimal volume = bar.volume();
		if (!Decimal.isValid(volume)) {
			return null;
		}
		BigDecimal value = volume.value();
		return value.signum() < 0 ? null : value;
	}

	/**
	 * Resolves the session date and instant a bar belongs to. IBKR formats bar
	 * times as {@code yyyyMMdd HH:mm:ss} with an optional trailing zone, but
	 * {@code historicalDataUpdate} can deliver Unix epoch seconds instead, so both
	 * are accepted.
	 */
	private static MinuteBar describe(String ticker, Bar bar, BigDecimal volume) {
		String time = bar.time() == null ? "" : bar.time().trim();
		if (time.isEmpty()) {
			return null;
		}

		try {
			if (isAllDigits(time)) {
				// An 8-digit value is a yyyyMMdd daily bar, which has no minute to
				// place in the window. Epoch seconds are 10 digits until 2286.
				if (time.length() < 10) {
					return null;
				}
				ZonedDateTime newYork = Instant.ofEpochSecond(Long.parseLong(time)).atZone(NEW_YORK_ZONE);
				return new MinuteBar(newYork.toLocalDate(), newYork.toEpochSecond(), volume);
			}

			String[] parts = time.split("\\s+");
			if (parts.length < 2) {
				return null;
			}
			LocalDate date = LocalDate.parse(parts[0], BAR_DATE);
			LocalTime localTime = LocalTime.parse(parts[1]);
			ZoneId zone = parts.length >= 3 ? ZoneId.of(parts[2]) : NEW_YORK_ZONE;
			return new MinuteBar(date, ZonedDateTime.of(date, localTime, zone).toEpochSecond(), volume);
		} catch (RuntimeException e) {
			logger.warn("[{}] Unrecognized bar time '{}'; it cannot join the volume baseline: {}",
					ticker, time, e.toString());
			return null;
		}
	}

	private static boolean isAllDigits(String value) {
		for (int i = 0; i < value.length(); i++) {
			if (!Character.isDigit(value.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	/** One completed or forming minute bar reduced to what the baseline needs. */
	private record MinuteBar(LocalDate sessionDate, long epochSecond, BigDecimal volume) {
	}

	/**
	 * The completed bars of a single ticker's current session. Every member is
	 * guarded by the instance monitor. IBKR callbacks arrive on the reader thread,
	 * but a resubscribe can interleave a fresh history dump with live updates, and
	 * the diagnostic accessors are read from elsewhere.
	 */
	private static final class VolumeWindow {
		private final Deque<BigDecimal> completed = new ArrayDeque<>(BASELINE_BARS);
		private BigDecimal sum = BigDecimal.ZERO;
		private LocalDate sessionDate;
		private MinuteBar pending;

		synchronized MinuteBar pending() {
			return pending;
		}

		synchronized void setPending(MinuteBar pending) {
			this.pending = pending;
		}

		synchronized int size() {
			return completed.size();
		}

		synchronized LocalDate sessionDate() {
			return sessionDate;
		}

		synchronized void commit(MinuteBar bar) {
			if (!bar.sessionDate().equals(sessionDate)) {
				// A new session starts its own baseline.
				completed.clear();
				sum = BigDecimal.ZERO;
				sessionDate = bar.sessionDate();
			}
			completed.addLast(bar.volume());
			sum = sum.add(bar.volume());
			while (completed.size() > BASELINE_BARS) {
				sum = sum.subtract(completed.removeFirst());
			}
		}

		synchronized Decimal baseline() {
			if (completed.size() < BASELINE_BARS) {
				return Decimal.ZERO;
			}
			return Decimal.get(sum.divide(BASELINE_DIVISOR, 4, RoundingMode.HALF_UP));
		}
	}
}
