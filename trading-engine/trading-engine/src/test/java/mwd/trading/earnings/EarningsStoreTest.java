package mwd.trading.earnings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import mwd.trading.earnings.EarningsSnapshot.EarningsDate;

/**
 * Covers the session-scoped validity rules. Earnings dates need no age limit:
 * they are static within a session, and what must expire is the session itself,
 * so yesterday's answer cannot survive into today.
 */
class EarningsStoreTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 27);
    private static final LocalDate YESTERDAY = LocalDate.of(2026, 7, 24);
    private static final Set<String> UNIVERSE = Set.of("AAPL", "MSFT", "NVDA");

    private static EarningsSnapshot snapshot(LocalDate tradingDate, Object... pairs) {
        Map<String, EarningsDate> byTicker = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            String ticker = (String) pairs[i];
            LocalDate date = (LocalDate) pairs[i + 1];
            byTicker.put(ticker, new EarningsDate(ticker, date, null));
        }
        return new EarningsSnapshot(tradingDate, byTicker);
    }

    @Test
    void anAcceptedSnapshotIsReadableForItsSession() {
        EarningsStore store = new EarningsStore(UNIVERSE);

        assertTrue(store.accept(
                snapshot(TODAY, "AAPL", LocalDate.of(2026, 7, 30)), TODAY, 1L));

        assertTrue(store.isReadyFor(TODAY));
        assertEquals(LocalDate.of(2026, 7, 30),
                store.earningsDate("AAPL", TODAY).orElseThrow().nextEarningsDate());
        assertEquals(3L, store.daysUntilEarnings("AAPL", TODAY).orElseThrow());
    }

    @Test
    void aSnapshotForAnotherSessionIsRejectedOutright() {
        EarningsStore store = new EarningsStore(UNIVERSE);

        assertFalse(store.accept(
                snapshot(YESTERDAY, "AAPL", LocalDate.of(2026, 7, 30)), TODAY, 1L));

        assertFalse(store.isReadyFor(TODAY));
        assertTrue(store.earningsDate("AAPL", TODAY).isEmpty());
        assertEquals(1L, store.getRejectedSnapshotCount());
        assertTrue(store.getLastRejectionReason().contains("2026-07-24"));
    }

    @Test
    void yesterdaysSnapshotStopsBeingUsableWhenTheSessionRollsOver() {
        // The failure this design exists to prevent: a stale earnings date
        // surviving into a new session is how a strategy trades into a report
        // it believed was days away.
        EarningsStore store = new EarningsStore(UNIVERSE);
        store.accept(snapshot(YESTERDAY, "AAPL", LocalDate.of(2026, 7, 30)), YESTERDAY, 1L);
        assertTrue(store.isReadyFor(YESTERDAY));

        assertFalse(store.isReadyFor(TODAY));
        assertTrue(store.earningsDate("AAPL", TODAY).isEmpty());
        assertTrue(store.daysUntilEarnings("AAPL", TODAY).isEmpty());
    }

    @Test
    void tickersOutsideTheEngineUniverseAreDroppedNotRejected() {
        // The proxy may legitimately cover a wider universe than this engine
        // trades; that is not a malformed response.
        EarningsStore store = new EarningsStore(Set.of("AAPL"));

        assertTrue(store.accept(snapshot(TODAY,
                "AAPL", LocalDate.of(2026, 7, 30),
                "TSLA", LocalDate.of(2026, 8, 5)), TODAY, 1L));

        assertEquals(Set.of("AAPL"), store.coveredTickers());
        assertTrue(store.earningsDate("TSLA", TODAY).isEmpty());
    }

    @Test
    void tickerCasingAndWhitespaceDoNotMatter() {
        EarningsStore store = new EarningsStore(UNIVERSE);
        store.accept(snapshot(TODAY, "aapl", LocalDate.of(2026, 7, 30)), TODAY, 1L);

        assertTrue(store.earningsDate("  AAPL ", TODAY).isPresent());
    }

    @Test
    void aNewSnapshotReplacesTheWholePreviousOne() {
        // Merging per ticker would let two sessions' answers coexist with no
        // single date to check them against.
        EarningsStore store = new EarningsStore(UNIVERSE);
        store.accept(snapshot(TODAY,
                "AAPL", LocalDate.of(2026, 7, 30),
                "MSFT", LocalDate.of(2026, 8, 4)), TODAY, 1L);

        store.accept(snapshot(TODAY, "AAPL", LocalDate.of(2026, 7, 31)), TODAY, 2L);

        assertEquals(Set.of("AAPL"), store.coveredTickers());
        assertEquals(LocalDate.of(2026, 7, 31),
                store.earningsDate("AAPL", TODAY).orElseThrow().nextEarningsDate());
    }

    @Test
    void aPassedEarningsDateReportsNegativeDays() {
        EarningsStore store = new EarningsStore(UNIVERSE);
        store.accept(snapshot(TODAY, "AAPL", LocalDate.of(2026, 7, 24)), TODAY, 1L);

        assertEquals(-3L, store.daysUntilEarnings("AAPL", TODAY).orElseThrow());
    }

    @Test
    void reportingTodayIsZeroDaysAway() {
        EarningsStore store = new EarningsStore(UNIVERSE);
        store.accept(snapshot(TODAY, "AAPL", TODAY), TODAY, 1L);

        assertEquals(0L, store.daysUntilEarnings("AAPL", TODAY).orElseThrow());
    }

    @Test
    void unreadyIsDescribedWithAReason() {
        EarningsStore store = new EarningsStore(UNIVERSE);
        assertTrue(store.describeUnready(Set.of("AAPL"), TODAY).orElseThrow()
                .contains("no earnings snapshot"));

        store.accept(snapshot(TODAY, "AAPL", LocalDate.of(2026, 7, 30)), TODAY, 1L);

        assertTrue(store.describeUnready(Set.of("AAPL"), TODAY).isEmpty());
        assertTrue(store.describeUnready(Set.of("AAPL", "MSFT"), TODAY).orElseThrow()
                .contains("MSFT"));
        assertTrue(store.describeUnready(Set.of("AAPL"), YESTERDAY).orElseThrow()
                .contains("rather than 2026-07-24"));
    }

    @Test
    void aNullSnapshotIsRejected() {
        EarningsStore store = new EarningsStore(UNIVERSE);

        assertFalse(store.accept(null, TODAY, 1L));
        assertEquals(1L, store.getRejectedSnapshotCount());
    }

    @Test
    void anEmptyUniverseIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new EarningsStore(Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new EarningsStore(Set.of("  ")));
    }
}
