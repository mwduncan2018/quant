package mwd.trading.indicator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ib.client.Bar;
import com.ib.client.Decimal;

import mwd.trading.broker.ibkr.IdManager;
import mwd.trading.broker.ibkr.RequestRegistry;
import mwd.trading.broker.ibkr.TimeManager;
import mwd.trading.domain.Stock;
import mwd.trading.execution.OrderRegistry;
import mwd.trading.marketdata.MarketDataInput;
import mwd.trading.marketdata.MarketDataInputStore;
import mwd.trading.state.Blackboard;
import mwd.trading.support.TestConfig;

/**
 * Covers the rolling volume baseline the capitulation filter compares against.
 * Before this tracker owned the figure, the baseline came from IBKR tick 21, the
 * 90-day average <em>daily</em> volume, which no single minute can ever exceed
 * threefold.
 */
class MinuteVolumeTrackerTest {
    private static final String TICKER = "AAPL";
    private static final int REQUEST_ID = 7;
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final LocalDate SESSION = LocalDate.of(2026, 7, 27);
    private static final LocalDate PREVIOUS_SESSION = LocalDate.of(2026, 7, 24);

    private Blackboard blackboard;
    private MarketDataInputStore inputStore;
    private MinuteVolumeTracker tracker;

    @BeforeEach
    void setUp() {
        blackboard = new Blackboard(
                new IdManager(), new TimeManager(), new OrderRegistry(),
                new TestConfig(5, 10_000L, Set.of(TICKER)));
        RequestRegistry registry = new RequestRegistry();
        registry.register(REQUEST_ID, TICKER, RequestRegistry.DataConsumer.VOLUME);
        inputStore = new MarketDataInputStore(30_000L);
        tracker = new MinuteVolumeTracker(blackboard, registry, inputStore);
    }

    private static Bar minuteBar(LocalDate session, int hour, int minute, long volume) {
        String time = String.format("%s  %02d:%02d:00",
                session.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE), hour, minute);
        return new Bar(time, 100.0, 100.5, 99.5, 100.2, Decimal.get(volume), 10, Decimal.get(100));
    }

    private double baseline() {
        return blackboard.getStock(TICKER).getAverageLast15MinuteVolume().value().doubleValue();
    }

    private double lastMinute() {
        return blackboard.getStock(TICKER).getLastMinuteVolume().value().doubleValue();
    }

    /** Feeds completed bars from 09:30, each carrying the same volume. */
    private void feedCompletedBars(int count, long volume) {
        for (int i = 0; i < count; i++) {
            int totalMinutes = 9 * 60 + 30 + i;
            tracker.onHistoricalData(REQUEST_ID,
                    minuteBar(SESSION, totalMinutes / 60, totalMinutes % 60, volume));
        }
    }

    @Test
    void baselineStaysUnusableUntilFifteenBarsHaveCompleted() {
        // Fifteen bars delivered means only fourteen have provably closed; the
        // fifteenth is still the pending bar until a later one arrives.
        feedCompletedBars(15, 1_000L);

        assertTrue(blackboard.getStock(TICKER).getAverageLast15MinuteVolume().isZero(),
                "a partial window must publish zero rather than an approximation");
        assertFalse(inputStore.isFresh(TICKER, MarketDataInput.MINUTE_VOLUME_BASELINE),
                "a partial window must not report the baseline as a ready input");
    }

    @Test
    void theBaselineBecomesAReadyInputOnlyOnceItsWindowIsFull() {
        // Fifteen delivered bars leave fourteen committed; the fifteenth is pending.
        feedCompletedBars(15, 1_000L);
        assertFalse(inputStore.isFresh(TICKER, MarketDataInput.MINUTE_VOLUME_BASELINE));

        // 09:45 closes 09:44, completing the window.
        tracker.onHistoricalData(REQUEST_ID, minuteBar(SESSION, 9, 45, 1_000L));

        assertTrue(inputStore.isFresh(TICKER, MarketDataInput.MINUTE_VOLUME_BASELINE));
    }

    @Test
    void baselineIsTheMeanOfTheFifteenCompletedBars() {
        feedCompletedBars(16, 1_000L);

        assertEquals(1_000.0, baseline(), 1e-9);
    }

    @Test
    void baselineExcludesTheBarStillForming() {
        feedCompletedBars(16, 1_000L);
        // A climax minute begins. It must not dilute the baseline it is measured
        // against, otherwise the effective threshold silently rises above 3.0x.
        tracker.onHistoricalDataUpdate(REQUEST_ID, minuteBar(SESSION, 9, 46, 9_000L));

        assertEquals(1_000.0, baseline(), 1e-9);
        assertEquals(9_000.0, lastMinute(), 1e-9);
    }

    @Test
    void theFormingBarIsRepublishedAsItsVolumeAccumulates() {
        feedCompletedBars(16, 1_000L);

        // keepUpToDate re-sends the same minute repeatedly with a growing volume.
        tracker.onHistoricalDataUpdate(REQUEST_ID, minuteBar(SESSION, 9, 46, 400L));
        tracker.onHistoricalDataUpdate(REQUEST_ID, minuteBar(SESSION, 9, 46, 2_500L));
        tracker.onHistoricalDataUpdate(REQUEST_ID, minuteBar(SESSION, 9, 46, 5_100L));

        assertEquals(5_100.0, lastMinute(), 1e-9);
        assertEquals(1_000.0, baseline(), 1e-9,
                "re-delivery of one forming minute must not commit it fifteen times");
    }

    @Test
    void theWindowSlidesAsMinutesComplete() {
        feedCompletedBars(16, 1_000L);
        assertEquals(1_000.0, baseline(), 1e-9);

        // Fifteen heavier completed bars, plus one more to close the last of them.
        for (int i = 0; i < 16; i++) {
            int totalMinutes = 9 * 60 + 46 + i;
            tracker.onHistoricalDataUpdate(REQUEST_ID,
                    minuteBar(SESSION, totalMinutes / 60, totalMinutes % 60, 3_000L));
        }

        assertEquals(3_000.0, baseline(), 1e-9, "the original quiet bars must have aged out");
    }

    @Test
    void aNewSessionDiscardsThePreviousDaysWindow() {
        // The prior day's closing ramp carries far more volume than a typical
        // minute. Carrying it into the open would suppress entries all morning.
        for (int i = 0; i < 16; i++) {
            int totalMinutes = 15 * 60 + 44 + i;
            tracker.onHistoricalData(REQUEST_ID,
                    minuteBar(PREVIOUS_SESSION, totalMinutes / 60, totalMinutes % 60, 80_000L));
        }
        assertEquals(80_000.0, baseline(), 1e-9);

        // Today opens. The first committed bar of the new session clears the window.
        tracker.onHistoricalData(REQUEST_ID, minuteBar(SESSION, 9, 30, 1_000L));
        tracker.onHistoricalData(REQUEST_ID, minuteBar(SESSION, 9, 31, 1_000L));

        assertTrue(blackboard.getStock(TICKER).getAverageLast15MinuteVolume().isZero(),
                "the opening baseline must be rebuilt from today's bars alone");
    }

    @Test
    void aReplayedHistoryDumpDoesNotRebuildTheWindowBackwards() {
        feedCompletedBars(16, 1_000L);
        tracker.onHistoricalDataUpdate(REQUEST_ID, minuteBar(SESSION, 9, 46, 4_000L));
        assertEquals(1_000.0, baseline(), 1e-9);

        // A resubscribe after data loss replays two days of history on a new
        // request id. The window must survive rather than be rewound.
        for (int i = 0; i < 16; i++) {
            int totalMinutes = 15 * 60 + 44 + i;
            tracker.onHistoricalData(REQUEST_ID,
                    minuteBar(PREVIOUS_SESSION, totalMinutes / 60, totalMinutes % 60, 80_000L));
        }

        assertEquals(1_000.0, baseline(), 1e-9);
    }

    @Test
    void aReplayedDumpResumesOnceItPassesTheNewestBarHeld() {
        feedCompletedBars(16, 1_000L);
        tracker.onHistoricalDataUpdate(REQUEST_ID, minuteBar(SESSION, 9, 46, 4_000L));

        // Replay from the open, then continue past 09:46 with heavier bars.
        for (int i = 0; i < 32; i++) {
            int totalMinutes = 9 * 60 + 30 + i;
            tracker.onHistoricalData(REQUEST_ID,
                    minuteBar(SESSION, totalMinutes / 60, totalMinutes % 60, 6_000L));
        }

        assertEquals(6_000.0, baseline(), 1e-9);
    }

    @Test
    void barTimesArrivingAsUnixEpochSecondsAreAccepted() {
        // historicalDataUpdate can deliver epoch seconds rather than yyyyMMdd.
        for (int i = 0; i < 16; i++) {
            long epochSecond = ZonedDateTime
                    .of(SESSION, LocalTime.of(9, 30).plusMinutes(i), NEW_YORK).toEpochSecond();
            tracker.onHistoricalDataUpdate(REQUEST_ID, new Bar(
                    Long.toString(epochSecond), 100.0, 100.5, 99.5, 100.2,
                    Decimal.get(2_000L), 10, Decimal.get(100)));
        }

        assertEquals(2_000.0, baseline(), 1e-9);
    }

    @Test
    void barTimesCarryingAnExplicitZoneAreAccepted() {
        for (int i = 0; i < 16; i++) {
            int totalMinutes = 9 * 60 + 30 + i;
            String time = String.format("20260727  %02d:%02d:00 US/Eastern",
                    totalMinutes / 60, totalMinutes % 60);
            tracker.onHistoricalDataUpdate(REQUEST_ID, new Bar(
                    time, 100.0, 100.5, 99.5, 100.2, Decimal.get(2_500L), 10, Decimal.get(100)));
        }

        assertEquals(2_500.0, baseline(), 1e-9);
    }

    @Test
    void anUnparseableBarTimeIsIgnoredWithoutDisturbingTheWindow() {
        feedCompletedBars(16, 1_000L);

        tracker.onHistoricalDataUpdate(REQUEST_ID, new Bar(
                "not-a-timestamp", 100.0, 100.5, 99.5, 100.2,
                Decimal.get(9_999L), 10, Decimal.get(100)));

        assertEquals(1_000.0, baseline(), 1e-9);
    }

    @Test
    void anInvalidVolumeLeavesTheLastReadingIntact() {
        feedCompletedBars(16, 1_000L);
        tracker.onHistoricalDataUpdate(REQUEST_ID, minuteBar(SESSION, 9, 46, 7_000L));
        assertEquals(7_000.0, lastMinute(), 1e-9);

        // IBKR marks an absent size with Decimal.INVALID, which holds Long.MIN_VALUE.
        tracker.onHistoricalDataUpdate(REQUEST_ID, new Bar(
                "20260727  09:47:00", 100.0, 100.5, 99.5, 100.2,
                Decimal.INVALID, 10, Decimal.get(100)));

        assertEquals(7_000.0, lastMinute(), 1e-9,
                "an unusable volume must not be published as a quiet minute");
        assertEquals(1_000.0, baseline(), 1e-9);
    }

    @Test
    void perTickerWindowsDoNotShareState() {
        RequestRegistry registry = new RequestRegistry();
        registry.register(1, "AAPL", RequestRegistry.DataConsumer.VOLUME);
        registry.register(2, "MSFT", RequestRegistry.DataConsumer.VOLUME);
        Blackboard board = new Blackboard(
                new IdManager(), new TimeManager(), new OrderRegistry(),
                new TestConfig(5, 10_000L, Set.of("AAPL", "MSFT")));
        MinuteVolumeTracker shared = new MinuteVolumeTracker(
                board, registry, new MarketDataInputStore(30_000L));

        for (int i = 0; i < 16; i++) {
            int totalMinutes = 9 * 60 + 30 + i;
            shared.onHistoricalData(1, minuteBar(SESSION, totalMinutes / 60, totalMinutes % 60, 1_000L));
            shared.onHistoricalData(2, minuteBar(SESSION, totalMinutes / 60, totalMinutes % 60, 4_000L));
        }

        assertEquals(1_000.0,
                board.getStock("AAPL").getAverageLast15MinuteVolume().value().doubleValue(), 1e-9);
        assertEquals(4_000.0,
                board.getStock("MSFT").getAverageLast15MinuteVolume().value().doubleValue(), 1e-9);
    }

    @Test
    void aRealisticClimaxProducesAThreeTimesRatio() {
        // The gate the strategy applies: one minute against the mean of the
        // fifteen before it. This is the comparison that was impossible while the
        // baseline held a 90-day average daily volume.
        feedCompletedBars(16, 20_000L);
        tracker.onHistoricalDataUpdate(REQUEST_ID, minuteBar(SESSION, 9, 46, 61_000L));

        Stock stock = blackboard.getStock(TICKER);
        double relativeVolume = stock.getLastMinuteVolume().value().doubleValue()
                / stock.getAverageLast15MinuteVolume().value().doubleValue();

        assertTrue(relativeVolume >= 3.0, "expected a climax ratio, got " + relativeVolume);
    }
}
