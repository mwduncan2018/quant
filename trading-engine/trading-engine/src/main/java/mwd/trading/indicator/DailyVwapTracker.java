package mwd.trading.indicator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.ib.client.Bar;
import com.ib.client.Decimal;

import mwd.trading.broker.ibkr.RequestRegistry;
import mwd.trading.domain.Stock;
import mwd.trading.marketdata.MarketDataInput;
import mwd.trading.marketdata.MarketDataInputStore;
import mwd.trading.state.Blackboard;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Publishes the session volume-weighted average price from the one-minute bar
 * stream.
 *
 * <p>
 * IBKR does not deliver VWAP as a price tick. It carries it inside
 * {@code RT_VOLUME} (tick type 48), a <em>string</em> tick enabled by generic
 * tick {@code 233}, and it publishes no delayed equivalent at all — the delayed
 * family stops at {@code DELAYED_OPEN} (76). Deriving the figure from bars
 * instead means one implementation serves live and delayed data alike, which is
 * what lets the PAPER engine run on delayed market data.
 *
 * <p>
 * Each bar carries {@link Bar#wap()}, its own volume-weighted average price, so
 * the session figure is the volume-weighted mean of those:
 *
 * <pre>
 *     VWAP = Σ(bar.wap × bar.volume) / Σ(bar.volume)
 * </pre>
 *
 * <p>
 * The minute-history subscription requests {@code TRADES} with
 * {@code useRTH = 1}, so the sums cover regular trading hours only, which is the
 * basis a session VWAP is normally quoted on. The value will not agree with the
 * TWS display to the cent — TWS applies its own trade-condition filtering — but
 * it tracks the same curve.
 *
 * <p>
 * Unlike the volume baseline, the <b>forming</b> minute is deliberately
 * included. Excluding it would freeze VWAP for up to a minute at a time, and the
 * one-sigma strategies use it as a live take-profit target. The forming bar is
 * held aside and replaced as IBKR re-delivers it with more volume, so it
 * contributes once, not once per update.
 */
public class DailyVwapTracker {
	private static final Logger logger = LogManager.getLogger(DailyVwapTracker.class);

	private static final ZoneId NEW_YORK_ZONE = ZoneId.of("America/New_York");
	private static final DateTimeFormatter BAR_DATE = DateTimeFormatter.BASIC_ISO_DATE;
	private static final int VWAP_SCALE = 6;

	private final Blackboard blackboard;
	private final RequestRegistry registry;
	private final MarketDataInputStore inputStore;
	private final Map<String, VwapSession> sessions = new ConcurrentHashMap<>();

	public DailyVwapTracker(
			Blackboard blackboard, RequestRegistry registry, MarketDataInputStore inputStore) {
		this.blackboard = blackboard;
		this.registry = registry;
		this.inputStore = inputStore;
	}

	public void onHistoricalData(int reqId, Bar bar) {
		updateVwap(reqId, bar);
	}

	public void onHistoricalDataUpdate(int reqId, Bar bar) {
		updateVwap(reqId, bar);
	}

	public void onHistoricalDataEnd(int reqId, String start, String end) {
		String ticker = registry.getTickerFor(reqId);
		if (ticker == null) {
			return;
		}

		VwapSession session = sessions.get(ticker);
		logger.info("[{}] DailyVwapTracker.onHistoricalDataEnd - session {}, VWAP {}. Range: {} to {}",
				ticker,
				session == null ? "none" : session.sessionDate(),
				session == null ? "unavailable" : session.vwap(),
				start, end);
	}

	private void updateVwap(int reqId, Bar bar) {
		String ticker = registry.getTickerFor(reqId);
		if (ticker == null || bar == null) {
			return;
		}

		VwapBar observed = describe(ticker, bar);
		if (observed == null) {
			return;
		}

		VwapSession session = sessions.computeIfAbsent(ticker, key -> new VwapSession());
		BigDecimal vwap;
		synchronized (session) {
			session.observe(observed);
			vwap = session.vwap();
		}

		if (vwap == null) {
			// Nothing has traded yet this session. Leave the previous value rather
			// than publishing a zero that reads as a real price.
			return;
		}

		Stock stock = blackboard.getStock(ticker);
		stock.setDailyVWAP(vwap.doubleValue());
		inputStore.record(ticker, MarketDataInput.DAILY_VWAP);
	}

	/**
	 * Reduces a bar to the session date, its minute, and its contribution to the
	 * two running sums. Returns null when the bar carries nothing usable.
	 *
	 * <p>
	 * IBKR marks absent values with {@link Decimal#INVALID} and {@link Decimal#NaN},
	 * which hold {@link Long#MIN_VALUE} rather than a quantity. A bar whose
	 * weighted average price is missing falls back to its close, because dropping a
	 * minute that really traded would bias the session figure more than pricing it
	 * one tick differently.
	 */
	private static VwapBar describe(String ticker, Bar bar) {
		String time = bar.time() == null ? "" : bar.time().trim();
		if (time.isEmpty()) {
			return null;
		}

		BigDecimal volume = usableVolume(bar);
		if (volume == null) {
			return null;
		}

		BigDecimal price = usablePrice(bar);
		if (price == null && volume.signum() > 0) {
			// Real volume with no usable price cannot be weighted.
			logger.warn("[{}] Bar at {} has volume {} but no usable price; it cannot join the VWAP",
					ticker, time, volume);
			return null;
		}

		BigDecimal notional = price == null ? BigDecimal.ZERO : price.multiply(volume);

		try {
			if (isAllDigits(time)) {
				// An 8-digit value is a yyyyMMdd daily bar, which has no minute to
				// place in the session. Epoch seconds are 10 digits until 2286.
				if (time.length() < 10) {
					return null;
				}
				ZonedDateTime newYork = Instant.ofEpochSecond(Long.parseLong(time)).atZone(NEW_YORK_ZONE);
				return new VwapBar(newYork.toLocalDate(), newYork.toEpochSecond(), notional, volume);
			}

			String[] parts = time.split("\\s+");
			if (parts.length < 2) {
				return null;
			}
			LocalDate date = LocalDate.parse(parts[0], BAR_DATE);
			LocalTime localTime = LocalTime.parse(parts[1]);
			ZoneId zone = parts.length >= 3 ? ZoneId.of(parts[2]) : NEW_YORK_ZONE;
			return new VwapBar(
					date, ZonedDateTime.of(date, localTime, zone).toEpochSecond(), notional, volume);
		} catch (RuntimeException e) {
			logger.warn("[{}] Unrecognized bar time '{}'; it cannot join the VWAP: {}",
					ticker, time, e.toString());
			return null;
		}
	}

	private static BigDecimal usableVolume(Bar bar) {
		Decimal volume = bar.volume();
		if (!Decimal.isValid(volume)) {
			return null;
		}
		BigDecimal value = volume.value();
		return value.signum() < 0 ? null : value;
	}

	private static BigDecimal usablePrice(Bar bar) {
		Decimal wap = bar.wap();
		if (Decimal.isValid(wap) && wap.value().signum() > 0) {
			return wap.value();
		}
		double close = bar.close();
		if (close > 0 && Double.isFinite(close)) {
			return BigDecimal.valueOf(close);
		}
		return null;
	}

	private static boolean isAllDigits(String value) {
		for (int i = 0; i < value.length(); i++) {
			if (!Character.isDigit(value.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	/** One bar reduced to what the session sums need. */
	private record VwapBar(
			LocalDate sessionDate, long epochSecond, BigDecimal notional, BigDecimal volume) {
	}

	/**
	 * One ticker's running session sums. Every member is guarded by the instance
	 * monitor: IBKR callbacks arrive on the reader thread, but a resubscribe can
	 * interleave a fresh history dump with live updates.
	 */
	private static final class VwapSession {
		private BigDecimal completedNotional = BigDecimal.ZERO;
		private BigDecimal completedVolume = BigDecimal.ZERO;
		private LocalDate sessionDate;
		private VwapBar pending;

		synchronized LocalDate sessionDate() {
			return sessionDate;
		}

		synchronized void observe(VwapBar bar) {
			if (pending != null && bar.epochSecond() < pending.epochSecond()) {
				// A replayed history dump after a resubscribe. Ignore it until it
				// passes the newest bar already held, so the running sums keep
				// working instead of being rebuilt backwards.
				return;
			}

			if (sessionDate == null || !bar.sessionDate().equals(sessionDate)) {
				// A new session starts its own sums. The previous day's closing ramp
				// would otherwise anchor the morning VWAP to yesterday's prices.
				completedNotional = BigDecimal.ZERO;
				completedVolume = BigDecimal.ZERO;
				sessionDate = bar.sessionDate();
				pending = null;
			}

			if (pending == null || bar.epochSecond() == pending.epochSecond()) {
				// Either the first bar of the session, or the same minute
				// re-delivered with more volume. Replacing rather than adding is
				// what keeps a forming bar from being counted once per update.
				pending = bar;
				return;
			}

			// A later minute began, so the pending bar is now complete.
			completedNotional = completedNotional.add(pending.notional());
			completedVolume = completedVolume.add(pending.volume());
			pending = bar;
		}

		/** The session VWAP including the forming minute, or null when nothing has traded. */
		synchronized BigDecimal vwap() {
			BigDecimal notional = completedNotional;
			BigDecimal volume = completedVolume;
			if (pending != null) {
				notional = notional.add(pending.notional());
				volume = volume.add(pending.volume());
			}
			if (volume.signum() <= 0) {
				return null;
			}
			return notional.divide(volume, VWAP_SCALE, RoundingMode.HALF_UP);
		}
	}
}
