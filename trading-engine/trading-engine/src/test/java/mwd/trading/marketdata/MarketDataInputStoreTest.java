package mwd.trading.marketdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * Covers per-input readiness. The rule this replaces marked a whole symbol fresh
 * on any arriving tick and never expired with time, so a symbol could be "ready"
 * having delivered only a bid size, and a frozen stream stayed ready forever.
 */
class MarketDataInputStoreTest {
    private static final String TICKER = "AAPL";
    private static final long MAX_AGE_MS = 30_000L;
    // 10:00 in New York, inside a regular session.
    private static final Instant NOW = Instant.parse("2026-07-27T14:00:00Z");

    private static MarketDataInputStore storeAt(Instant instant) {
        return new MarketDataInputStore(MAX_AGE_MS, Clock.fixed(instant, ZoneOffset.UTC));
    }

    @Test
    void anInputIsUnreadyUntilItIsRecorded() {
        MarketDataInputStore store = storeAt(NOW);

        assertFalse(store.isFresh(TICKER, MarketDataInput.LAST_PRICE));
        assertTrue(store.describeUnready(TICKER, Set.of(MarketDataInput.LAST_PRICE))
                .orElseThrow().contains("never received"));
    }

    @Test
    void oneRecordedInputDoesNotMakeAnotherReady() {
        // The defect this store exists to remove: any tick marking everything ready.
        MarketDataInputStore store = storeAt(NOW);
        store.record(TICKER, MarketDataInput.LAST_PRICE);

        assertTrue(store.isFresh(TICKER, MarketDataInput.LAST_PRICE));
        assertFalse(store.isFresh(TICKER, MarketDataInput.DAILY_VWAP));
        assertFalse(store.areAllFresh(TICKER,
                Set.of(MarketDataInput.LAST_PRICE, MarketDataInput.DAILY_VWAP)));
    }

    @Test
    void everyRequiredInputMustBeRecorded() {
        MarketDataInputStore store = storeAt(NOW);
        Set<MarketDataInput> required = Set.of(
                MarketDataInput.LAST_PRICE,
                MarketDataInput.PREVIOUS_CLOSE,
                MarketDataInput.DAILY_VWAP);

        store.record(TICKER, MarketDataInput.LAST_PRICE);
        store.record(TICKER, MarketDataInput.PREVIOUS_CLOSE);
        assertFalse(store.areAllFresh(TICKER, required));

        store.record(TICKER, MarketDataInput.DAILY_VWAP);
        assertTrue(store.areAllFresh(TICKER, required));
        assertEquals(Optional.empty(), store.describeUnready(TICKER, required));
    }

    @Test
    void aFrozenStreamStopsBeingFreshWithoutAnyDisconnectSignal() {
        MutableClock clock = new MutableClock(NOW);
        MarketDataInputStore store = new MarketDataInputStore(MAX_AGE_MS, clock);
        store.record(TICKER, MarketDataInput.DAILY_VWAP);
        assertTrue(store.isFresh(TICKER, MarketDataInput.DAILY_VWAP));

        clock.advance(Duration.ofMillis(MAX_AGE_MS));
        assertTrue(store.isFresh(TICKER, MarketDataInput.DAILY_VWAP),
                "the limit itself is still usable");

        clock.advance(Duration.ofMillis(1));
        assertFalse(store.isFresh(TICKER, MarketDataInput.DAILY_VWAP));
        assertTrue(store.describeUnready(TICKER, Set.of(MarketDataInput.DAILY_VWAP))
                .orElseThrow().contains("beyond the"));
    }

    @Test
    void thePreviousCloseSurvivesTheAgeLimitBecauseIbkrSendsItOnce() {
        MutableClock clock = new MutableClock(NOW);
        MarketDataInputStore store = new MarketDataInputStore(MAX_AGE_MS, clock);
        store.record(TICKER, MarketDataInput.PREVIOUS_CLOSE);

        clock.advance(Duration.ofHours(5));

        assertTrue(store.isFresh(TICKER, MarketDataInput.PREVIOUS_CLOSE),
                "an age limit would discard a value that is still correct all session");
    }

    @Test
    void thePreviousCloseLapsesWhenTheSessionChanges() {
        MutableClock clock = new MutableClock(NOW);
        MarketDataInputStore store = new MarketDataInputStore(MAX_AGE_MS, clock);
        store.record(TICKER, MarketDataInput.PREVIOUS_CLOSE);

        clock.advance(Duration.ofDays(1));

        assertFalse(store.isFresh(TICKER, MarketDataInput.PREVIOUS_CLOSE));
        assertTrue(store.describeUnready(TICKER, Set.of(MarketDataInput.PREVIOUS_CLOSE))
                .orElseThrow().contains("2026-07-27"));
    }

    @Test
    void markAllStaleDropsEverySymbolAndInput() {
        MarketDataInputStore store = storeAt(NOW);
        store.record(TICKER, MarketDataInput.LAST_PRICE);
        store.record("MSFT", MarketDataInput.PREVIOUS_CLOSE);
        assertEquals(2, store.coveredSymbolCount());

        store.markAllStale();

        assertEquals(0, store.coveredSymbolCount());
        assertFalse(store.isFresh(TICKER, MarketDataInput.LAST_PRICE));
        assertFalse(store.isFresh("MSFT", MarketDataInput.PREVIOUS_CLOSE),
                "a session-scoped input must not survive a resubscribe either");
    }

    @Test
    void symbolsAreTrackedIndependently() {
        MarketDataInputStore store = storeAt(NOW);
        store.record(TICKER, MarketDataInput.LAST_PRICE);

        assertTrue(store.isFresh(TICKER, MarketDataInput.LAST_PRICE));
        assertFalse(store.isFresh("MSFT", MarketDataInput.LAST_PRICE));
    }

    @Test
    void tickerCasingAndWhitespaceDoNotMatter() {
        MarketDataInputStore store = storeAt(NOW);
        store.record("  aapl ", MarketDataInput.LAST_PRICE);

        assertTrue(store.isFresh(TICKER, MarketDataInput.LAST_PRICE));
    }

    @Test
    void anEmptyRequirementSetIsNeverReady() {
        // A strategy requiring nothing would be trading on no verified data at all.
        MarketDataInputStore store = storeAt(NOW);

        assertFalse(store.areAllFresh(TICKER, Set.of()));
        assertTrue(store.describeUnready(TICKER, Set.of()).isPresent());
    }

    @Test
    void aBlankTickerIsNeverReady() {
        MarketDataInputStore store = storeAt(NOW);

        assertFalse(store.isFresh(null, MarketDataInput.LAST_PRICE));
        assertFalse(store.isFresh("   ", MarketDataInput.LAST_PRICE));
    }

    @Test
    void everyMissingInputIsNamedInOneReason() {
        MarketDataInputStore store = storeAt(NOW);
        store.record(TICKER, MarketDataInput.LAST_PRICE);

        String reason = store.describeUnready(TICKER, Set.of(
                MarketDataInput.LAST_PRICE,
                MarketDataInput.DAILY_VWAP,
                MarketDataInput.MINUTE_BAR)).orElseThrow();

        assertTrue(reason.contains(MarketDataInput.DAILY_VWAP.description()));
        assertTrue(reason.contains(MarketDataInput.MINUTE_BAR.description()));
        assertFalse(reason.contains(MarketDataInput.LAST_PRICE.description()));
    }

    @Test
    void aNonPositiveMaximumAgeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MarketDataInputStore(0));
        assertThrows(IllegalArgumentException.class, () -> new MarketDataInputStore(-1));
    }

    /**
     * A clock the test moves forward explicitly. {@code withZone} keeps sharing
     * the same instant, so the store's New York view advances with the test.
     */
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        private final ZoneId zone;

        private MutableClock(Instant instant) {
            this(new AtomicReference<>(instant), ZoneOffset.UTC);
        }

        private MutableClock(AtomicReference<Instant> instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advance(Duration duration) {
            instant.updateAndGet(current -> current.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
