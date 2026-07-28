package mwd.trading.optionsproxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.OptionalDouble;
import java.util.Set;

import org.junit.jupiter.api.Test;

import mwd.trading.optionsproxy.proto.IndicatorFrame;

class OptionsIndicatorStoreTest {
    private static final LocalDate TRADING_DATE = LocalDate.of(2026, 7, 27);
    private static final LocalDate PREVIOUS_TRADING_DATE = LocalDate.of(2026, 7, 24);
    private static final long NOW = 1_784_000_000_000L;
    private static final long MAX_AGE_MS = 5000L;

    private static OptionsIndicatorStore store() {
        return new OptionsIndicatorStore(Set.of("AAPL", "MSFT", "SPY"), MAX_AGE_MS);
    }

    private static IndicatorFrame.Builder frame(long sequence) {
        return IndicatorFrame.newBuilder()
                .setSequence(sequence)
                .setEmittedAtUnixMs(NOW)
                .setTicker("AAPL")
                .setTradingDate(TRADING_DATE.toString())
                .setStaticDailyImpliedMove(6.272)
                .setStaticDailyImpliedMoveValid(true)
                .setSpyGammaFlip(601.25)
                .setSpyGammaFlipValid(true);
    }

    @Test
    void aValidFrameBecomesReadableAsBothIndicators() {
        OptionsIndicatorStore store = store();

        assertTrue(store.accept(frame(1).build(), NOW));

        assertEquals(OptionalDouble.of(6.272),
                store.impliedMoveForNewEntry("AAPL", TRADING_DATE, NOW));
        assertEquals(OptionalDouble.of(601.25),
                store.gammaFlipForNewEntry(TRADING_DATE, NOW));
        assertEquals(1L, store.getAcceptedFrameCount());
        assertEquals(0L, store.getRejectedFrameCount());
    }

    @Test
    void aLowercaseTickerIsNormalizedRatherThanRejected() {
        OptionsIndicatorStore store = store();

        assertTrue(store.accept(frame(1).setTicker("aapl").build(), NOW));

        assertEquals(OptionalDouble.of(6.272),
                store.impliedMoveForNewEntry("AAPL", TRADING_DATE, NOW));
    }

    @Test
    void aFrameForATickerOutsideTheUniverseIsIgnored() {
        OptionsIndicatorStore store = store();

        assertFalse(store.accept(frame(1).setTicker("TSLA").build(), NOW));

        assertTrue(store.impliedMoveForNewEntry("TSLA", TRADING_DATE, NOW).isEmpty());
        assertTrue(store.gammaFlipForNewEntry(TRADING_DATE, NOW).isEmpty());
        assertEquals(1L, store.getRejectedFrameCount());
    }

    @Test
    void anUnparseableTradingDateIsIgnored() {
        OptionsIndicatorStore store = store();

        assertFalse(store.accept(frame(1).setTradingDate("07/27/2026").build(), NOW));
        assertFalse(store.accept(frame(2).setTradingDate("").build(), NOW));
        assertFalse(store.accept(frame(3).setTradingDate("2026-13-40").build(), NOW));

        assertTrue(store.gammaFlipForNewEntry(TRADING_DATE, NOW).isEmpty());
        assertEquals(3L, store.getRejectedFrameCount());
    }

    @Test
    void aValidFlagOverANonfiniteOrNonpositiveNumberIsIgnored() {
        OptionsIndicatorStore store = store();

        assertFalse(store.accept(frame(1).setStaticDailyImpliedMove(Double.NaN).build(), NOW));
        assertFalse(store.accept(frame(2).setStaticDailyImpliedMove(0.0).build(), NOW));
        assertFalse(store.accept(frame(3).setStaticDailyImpliedMove(-1.5).build(), NOW));
        assertFalse(store.accept(
                frame(4).setSpyGammaFlip(Double.POSITIVE_INFINITY).build(), NOW));
        assertFalse(store.accept(frame(5).setSpyGammaFlip(0.0).build(), NOW));

        assertEquals(0L, store.getAcceptedFrameCount());
        assertEquals(5L, store.getRejectedFrameCount());
    }

    @Test
    void aZeroValueIsAcceptedWhenItsValidityFlagIsFalse() {
        OptionsIndicatorStore store = store();

        assertTrue(store.accept(frame(1)
                .setStaticDailyImpliedMove(0.0)
                .setStaticDailyImpliedMoveValid(false)
                .build(), NOW));

        // The flag, not the number, decides. Zero must never look like a move.
        assertTrue(store.impliedMoveForNewEntry("AAPL", TRADING_DATE, NOW).isEmpty());
        assertEquals(OptionalDouble.of(601.25),
                store.gammaFlipForNewEntry(TRADING_DATE, NOW));
    }

    @Test
    void aFrameEmittedFarInTheFutureIsIgnored() {
        OptionsIndicatorStore store = store();

        assertFalse(store.accept(
                frame(1).setEmittedAtUnixMs(NOW + MAX_AGE_MS + 1).build(), NOW));
        assertFalse(store.accept(frame(2).setEmittedAtUnixMs(0L).build(), NOW));

        assertTrue(store.gammaFlipForNewEntry(TRADING_DATE, NOW).isEmpty());
    }

    @Test
    void aReplayedOrReorderedFrameIsIgnored() {
        OptionsIndicatorStore store = store();
        assertTrue(store.accept(frame(7).setStaticDailyImpliedMove(6.272).build(), NOW));

        assertFalse(store.accept(frame(6).setStaticDailyImpliedMove(9.99).build(), NOW + 1));
        assertFalse(store.accept(frame(7).setStaticDailyImpliedMove(9.99).build(), NOW + 2));

        assertEquals(OptionalDouble.of(6.272),
                store.impliedMoveForNewEntry("AAPL", TRADING_DATE, NOW + 2));
    }

    @Test
    void sequencesAreTrackedPerTickerSoOneTickerCannotStarveAnother() {
        OptionsIndicatorStore store = store();

        assertTrue(store.accept(frame(10).setTicker("AAPL").build(), NOW));
        assertTrue(store.accept(frame(11)
                .setTicker("MSFT")
                .setStaticDailyImpliedMove(3.5)
                .build(), NOW));

        assertEquals(OptionalDouble.of(6.272),
                store.impliedMoveForNewEntry("AAPL", TRADING_DATE, NOW));
        assertEquals(OptionalDouble.of(3.5),
                store.impliedMoveForNewEntry("MSFT", TRADING_DATE, NOW));
    }

    @Test
    void aRestartedProxyResynchronizesAfterTheStreamGoesSilent() {
        OptionsIndicatorStore store = store();
        assertTrue(store.accept(frame(5000).build(), NOW));

        long afterSilence = NOW + MAX_AGE_MS + 1;
        assertTrue(store.accept(frame(1)
                .setEmittedAtUnixMs(afterSilence)
                .setStaticDailyImpliedMove(7.5)
                .build(), afterSilence));

        assertEquals(OptionalDouble.of(7.5),
                store.impliedMoveForNewEntry("AAPL", TRADING_DATE, afterSilence));
    }

    @Test
    void yesterdaysSessionNeverSatisfiesTodaysEntry() {
        OptionsIndicatorStore store = store();

        assertTrue(store.accept(
                frame(1).setTradingDate(PREVIOUS_TRADING_DATE.toString()).build(), NOW));

        assertTrue(store.impliedMoveForNewEntry("AAPL", TRADING_DATE, NOW).isEmpty());
        assertTrue(store.gammaFlipForNewEntry(TRADING_DATE, NOW).isEmpty());
        assertEquals(OptionalDouble.of(6.272),
                store.impliedMoveForNewEntry("AAPL", PREVIOUS_TRADING_DATE, NOW));
    }

    @Test
    void aStaleFrameBlocksNewEntriesButKeepsTheLastKnownMove() {
        OptionsIndicatorStore store = store();
        assertTrue(store.accept(frame(1).build(), NOW));

        long tooLate = NOW + MAX_AGE_MS + 1;
        assertFalse(store.hasFreshFrame("AAPL", tooLate));
        assertTrue(store.impliedMoveForNewEntry("AAPL", TRADING_DATE, tooLate).isEmpty());
        assertTrue(store.gammaFlipForNewEntry(TRADING_DATE, tooLate).isEmpty());

        // Position management must survive the proxy going silent.
        assertEquals(OptionalDouble.of(6.272), store.lastKnownImpliedMove("AAPL"));
    }

    @Test
    void aFrameAtExactlyTheMaximumAgeIsStillFresh() {
        OptionsIndicatorStore store = store();
        assertTrue(store.accept(frame(1).build(), NOW));

        assertTrue(store.hasFreshFrame("AAPL", NOW + MAX_AGE_MS));
        assertEquals(OptionalDouble.of(6.272),
                store.impliedMoveForNewEntry("AAPL", TRADING_DATE, NOW + MAX_AGE_MS));
    }

    @Test
    void anInvalidatedMoveBlocksEntriesWithoutDiscardingTheRetainedValue() {
        OptionsIndicatorStore store = store();
        assertTrue(store.accept(frame(1).build(), NOW));

        assertTrue(store.accept(frame(2)
                .setStaticDailyImpliedMove(0.0)
                .setStaticDailyImpliedMoveValid(false)
                .build(), NOW));

        assertTrue(store.impliedMoveForNewEntry("AAPL", TRADING_DATE, NOW).isEmpty());
        assertEquals(OptionalDouble.of(6.272), store.lastKnownImpliedMove("AAPL"));
    }

    @Test
    void gammaFlipBecomingValidAfterTheOpenEnablesEntriesWithoutARestart() {
        OptionsIndicatorStore store = store();
        assertTrue(store.accept(frame(1)
                .setSpyGammaFlip(0.0)
                .setSpyGammaFlipValid(false)
                .build(), NOW));
        assertTrue(store.gammaFlipForNewEntry(TRADING_DATE, NOW).isEmpty());

        assertTrue(store.accept(frame(2).build(), NOW + 1000));

        assertEquals(OptionalDouble.of(601.25),
                store.gammaFlipForNewEntry(TRADING_DATE, NOW + 1000));
    }

    @Test
    void aGammaFlipThatGoesInvalidStopsSatisfyingEntries() {
        OptionsIndicatorStore store = store();
        assertTrue(store.accept(frame(1).build(), NOW));

        assertTrue(store.accept(frame(2)
                .setSpyGammaFlip(0.0)
                .setSpyGammaFlipValid(false)
                .build(), NOW + 1000));

        assertTrue(store.gammaFlipForNewEntry(TRADING_DATE, NOW + 1000).isEmpty());
    }

    @Test
    void anUnknownTickerHasNoRetainedMove() {
        OptionsIndicatorStore store = store();

        assertTrue(store.lastKnownImpliedMove("TSLA").isEmpty());
        assertTrue(store.impliedMove("AAPL").isEmpty());
        assertTrue(store.gammaFlip().isEmpty());
        assertFalse(store.hasFreshFrame("AAPL", NOW));
    }

    @Test
    void theStoreRefusesAnUnusableConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new OptionsIndicatorStore(Set.of(), MAX_AGE_MS));
        assertThrows(IllegalArgumentException.class,
                () -> new OptionsIndicatorStore(Set.of("  "), MAX_AGE_MS));
        assertThrows(IllegalArgumentException.class,
                () -> new OptionsIndicatorStore(Set.of("AAPL"), 0L));
    }

    @Test
    void aNullFrameIsCountedAsRejectedRatherThanThrowing() {
        OptionsIndicatorStore store = store();

        assertFalse(store.accept(null, NOW));

        assertEquals(1L, store.getRejectedFrameCount());
    }
}
