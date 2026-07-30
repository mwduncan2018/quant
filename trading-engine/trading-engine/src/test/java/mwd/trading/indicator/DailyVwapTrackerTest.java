package mwd.trading.indicator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ib.client.Bar;
import com.ib.client.Decimal;

import mwd.trading.broker.ibkr.IdManager;
import mwd.trading.broker.ibkr.RequestRegistry;
import mwd.trading.broker.ibkr.TimeManager;
import mwd.trading.execution.OrderRegistry;
import mwd.trading.marketdata.MarketDataInput;
import mwd.trading.marketdata.MarketDataInputStore;
import mwd.trading.state.Blackboard;
import mwd.trading.support.TestConfig;

/**
 * Covers the session VWAP the one-sigma strategies use as a take-profit target
 * and the two-sigma strategy uses as a reward reference.
 *
 * <p>
 * Before this tracker owned the figure, VWAP was read from a price tick field
 * (459 live, 533 delayed) that IBKR does not define — its tick types run 0 to
 * 104 — so {@code DAILY_VWAP} was never recorded and no strategy could ever
 * reach its entry gate against a real feed. These tests drive the same callback
 * path IBKR uses rather than writing the field directly, which is what makes
 * them able to catch that class of defect.
 */
class DailyVwapTrackerTest {
	private static final String TICKER = "AAPL";
	private static final int REQUEST_ID = 11;
	private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
	private static final LocalDate SESSION = LocalDate.of(2026, 7, 27);
	private static final LocalDate PREVIOUS_SESSION = LocalDate.of(2026, 7, 24);
	private static final double TOLERANCE = 1e-6;

	private Blackboard blackboard;
	private MarketDataInputStore inputStore;
	private DailyVwapTracker tracker;

	@BeforeEach
	void setUp() {
		blackboard = new Blackboard(
				new IdManager(), new TimeManager(), new OrderRegistry(),
				new TestConfig(3, 10_000L, Set.of(TICKER)));
		RequestRegistry registry = new RequestRegistry();
		registry.register(REQUEST_ID, TICKER, RequestRegistry.DataConsumer.VWAP);
		inputStore = new MarketDataInputStore(30_000L);
		tracker = new DailyVwapTracker(blackboard, registry, inputStore);
	}

	private static Bar bar(LocalDate session, int hour, int minute, double wap, long volume) {
		String time = String.format("%s  %02d:%02d:00",
				session.format(DateTimeFormatter.BASIC_ISO_DATE), hour, minute);
		return new Bar(time, wap, wap, wap, wap, Decimal.get(volume), 10, Decimal.get(wap));
	}

	/** A bar whose weighted average price is absent, as IBKR marks a missing value. */
	private static Bar barWithoutWap(LocalDate session, int hour, int minute, double close, long volume) {
		String time = String.format("%s  %02d:%02d:00",
				session.format(DateTimeFormatter.BASIC_ISO_DATE), hour, minute);
		return new Bar(time, close, close, close, close, Decimal.get(volume), 10, Decimal.INVALID);
	}

	private double vwap() {
		return blackboard.getStock(TICKER).getDailyVWAP();
	}

	private boolean vwapIsReady() {
		return inputStore.isFresh(TICKER, MarketDataInput.DAILY_VWAP);
	}

	@Test
	void aSingleBarProducesItsOwnWeightedAveragePrice() {
		tracker.onHistoricalData(REQUEST_ID, bar(SESSION, 9, 30, 100.0, 1_000));

		assertEquals(100.0, vwap(), TOLERANCE);
		assertTrue(vwapIsReady(), "one traded bar is enough to publish a session VWAP");
	}

	@Test
	void barsAreWeightedByVolumeRatherThanCountedEqually() {
		// 100 on 1,000 shares and 110 on 9,000 shares averages to 109, not 105.
		tracker.onHistoricalData(REQUEST_ID, bar(SESSION, 9, 30, 100.0, 1_000));
		tracker.onHistoricalData(REQUEST_ID, bar(SESSION, 9, 31, 110.0, 9_000));

		assertEquals(109.0, vwap(), TOLERANCE);
	}

	@Test
	void theFormingMinuteIsIncludedSoTheValueDoesNotStall() {
		tracker.onHistoricalData(REQUEST_ID, bar(SESSION, 9, 30, 100.0, 1_000));
		// 09:31 is still forming; its volume accumulates through the minute.
		tracker.onHistoricalDataUpdate(REQUEST_ID, bar(SESSION, 9, 31, 120.0, 1_000));

		assertEquals(110.0, vwap(), TOLERANCE);
	}

	@Test
	void aReDeliveredFormingMinuteReplacesItsPredecessorRatherThanAddingToIt() {
		tracker.onHistoricalData(REQUEST_ID, bar(SESSION, 9, 30, 100.0, 1_000));
		tracker.onHistoricalDataUpdate(REQUEST_ID, bar(SESSION, 9, 31, 120.0, 1_000));
		// The same minute again with more volume. Counting it twice would drag the
		// average toward 120 without any new shares having traded.
		tracker.onHistoricalDataUpdate(REQUEST_ID, bar(SESSION, 9, 31, 120.0, 3_000));

		assertEquals(115.0, vwap(), TOLERANCE);
	}

	@Test
	void aCompletedMinuteStaysInTheSessionOnceALaterMinuteProvesItClosed() {
		tracker.onHistoricalDataUpdate(REQUEST_ID, bar(SESSION, 9, 30, 100.0, 1_000));
		tracker.onHistoricalDataUpdate(REQUEST_ID, bar(SESSION, 9, 31, 200.0, 1_000));
		tracker.onHistoricalDataUpdate(REQUEST_ID, bar(SESSION, 9, 32, 300.0, 1_000));

		assertEquals(200.0, vwap(), TOLERANCE);
	}

	@Test
	void aNewSessionStartsItsOwnAverage() {
		// The previous day's closing ramp carries far more volume than a typical
		// minute; letting it survive would anchor the morning VWAP to yesterday.
		tracker.onHistoricalData(REQUEST_ID, bar(PREVIOUS_SESSION, 15, 58, 50.0, 500_000));
		tracker.onHistoricalData(REQUEST_ID, bar(PREVIOUS_SESSION, 15, 59, 50.0, 500_000));
		tracker.onHistoricalData(REQUEST_ID, bar(SESSION, 9, 30, 100.0, 1_000));
		tracker.onHistoricalData(REQUEST_ID, bar(SESSION, 9, 31, 100.0, 1_000));

		assertEquals(100.0, vwap(), TOLERANCE);
	}

	@Test
	void aReplayedHistoryDumpDoesNotRebuildTheSessionBackwards() {
		tracker.onHistoricalDataUpdate(REQUEST_ID, bar(SESSION, 9, 30, 100.0, 1_000));
		tracker.onHistoricalDataUpdate(REQUEST_ID, bar(SESSION, 9, 31, 100.0, 1_000));
		double established = vwap();

		// A resubscribe replays the dump from the start of the window.
		tracker.onHistoricalData(REQUEST_ID, bar(PREVIOUS_SESSION, 10, 0, 500.0, 1_000_000));

		assertEquals(established, vwap(), TOLERANCE);
	}

	@Test
	void aZeroVolumeMinuteLeavesTheAverageWhereItWas() {
		tracker.onHistoricalData(REQUEST_ID, bar(SESSION, 9, 30, 100.0, 1_000));
		tracker.onHistoricalData(REQUEST_ID, bar(SESSION, 9, 31, 0.0, 0));

		assertEquals(100.0, vwap(), TOLERANCE);
	}

	@Test
	void aBarWithNoWeightedAveragePriceFallsBackToItsClose() {
		// Dropping a minute that really traded would bias the session figure more
		// than pricing it at the close.
		tracker.onHistoricalData(REQUEST_ID, barWithoutWap(SESSION, 9, 30, 100.0, 1_000));

		assertEquals(100.0, vwap(), TOLERANCE);
		assertTrue(vwapIsReady());
	}

	@Test
	void anUntradedSessionPublishesNothingRatherThanAZero() {
		tracker.onHistoricalData(REQUEST_ID, bar(SESSION, 9, 30, 0.0, 0));

		assertEquals(0.0, vwap(), TOLERANCE);
		assertFalse(vwapIsReady(), "a zero VWAP must never read as a usable price");
	}

	@Test
	void barsCarryingUnixEpochSecondsAreAccepted() {
		// historicalDataUpdate can deliver epoch seconds instead of a formatted time.
		long open = ZonedDateTime.of(SESSION, java.time.LocalTime.of(9, 30), NEW_YORK).toEpochSecond();
		long next = open + 60;
		tracker.onHistoricalDataUpdate(REQUEST_ID,
				new Bar(String.valueOf(open), 100.0, 100.0, 100.0, 100.0,
						Decimal.get(1_000), 10, Decimal.get(100.0)));
		tracker.onHistoricalDataUpdate(REQUEST_ID,
				new Bar(String.valueOf(next), 110.0, 110.0, 110.0, 110.0,
						Decimal.get(1_000), 10, Decimal.get(110.0)));

		assertEquals(105.0, vwap(), TOLERANCE);
	}

	@Test
	void anUnknownRequestIdIsIgnored() {
		tracker.onHistoricalData(REQUEST_ID + 99, bar(SESSION, 9, 30, 100.0, 1_000));

		assertFalse(vwapIsReady());
	}

	@Test
	void anUnparseableBarTimeIsIgnoredRatherThanCorruptingTheSession() {
		tracker.onHistoricalData(REQUEST_ID, bar(SESSION, 9, 30, 100.0, 1_000));
		tracker.onHistoricalData(REQUEST_ID,
				new Bar("not-a-time", 200.0, 200.0, 200.0, 200.0,
						Decimal.get(1_000), 10, Decimal.get(200.0)));

		assertEquals(100.0, vwap(), TOLERANCE);
	}
}
