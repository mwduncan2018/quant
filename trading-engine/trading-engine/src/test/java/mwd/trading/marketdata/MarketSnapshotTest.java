package mwd.trading.marketdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.ib.client.Decimal;

import mwd.trading.domain.Stock;

/**
 * The point of the snapshot is what it does <em>not</em> do: keep tracking the
 * fields it came from. Every test here is a variation on that.
 */
class MarketSnapshotTest {
    private static final long TAKEN_AT = 1_800_000_000_000L;

    private static Stock primed() {
        Stock stock = new Stock("AAPL");
        stock.setLastPrice(96.0);
        stock.setPreviousClose(100.0);
        stock.setDailyVWAP(99.0);
        stock.setLastMinuteVolume(Decimal.get(1_000));
        stock.setAverageLast15MinuteVolume(Decimal.get(800));
        stock.setLongMarginRate(0.25);
        stock.setLongMarginRateVerified(true);
        stock.setShortMarginRate(0.30);
        return stock;
    }

    @Test
    void everyFieldIsCapturedFromTheStock() {
        MarketSnapshot market = MarketSnapshot.of(primed(), TAKEN_AT);

        assertEquals("AAPL", market.ticker());
        assertEquals(TAKEN_AT, market.takenAtUnixMs());
        assertEquals(96.0, market.lastPrice(), 1.0e-9);
        assertEquals(100.0, market.previousClose(), 1.0e-9);
        assertEquals(99.0, market.dailyVWAP(), 1.0e-9);
        assertEquals(Decimal.get(1_000), market.lastMinuteVolume());
        assertEquals(Decimal.get(800), market.averageLast15MinuteVolume());
        assertEquals(0.25, market.longMarginRate(), 1.0e-9);
        assertTrue(market.longMarginRateVerified());
        assertEquals(0.30, market.shortMarginRate(), 1.0e-9);
        assertFalse(market.shortMarginRateVerified());
    }

    @Test
    void laterTicksDoNotReachASnapshotAlreadyTaken() {
        Stock stock = primed();
        MarketSnapshot market = MarketSnapshot.of(stock, TAKEN_AT);

        // The reader thread keeps writing while a decision is being made.
        stock.setLastPrice(90.0);
        stock.setDailyVWAP(80.0);
        stock.setPreviousClose(101.0);

        assertEquals(96.0, market.lastPrice(), 1.0e-9);
        assertEquals(99.0, market.dailyVWAP(), 1.0e-9);
        assertEquals(100.0, market.previousClose(), 1.0e-9);
    }

    @Test
    void twoSnapshotsOfOneStockSeeTheTapeAtTheirOwnMoments() {
        Stock stock = primed();
        MarketSnapshot screening = MarketSnapshot.of(stock, TAKEN_AT);
        stock.setLastPrice(94.0);
        MarketSnapshot admitted = MarketSnapshot.of(stock, TAKEN_AT + 500L);

        assertEquals(96.0, screening.lastPrice(), 1.0e-9);
        assertEquals(94.0, admitted.lastPrice(), 1.0e-9);
    }

    @Test
    void marginSizesOffTheCapturedRateForTheDirectionBeingTraded() {
        Stock stock = primed();
        MarketSnapshot market = MarketSnapshot.of(stock, TAKEN_AT);

        // The next what-if cycle reprices the symbol; a quantity already being
        // sized must not switch rates halfway through.
        stock.setLongMarginRate(0.90);

        assertEquals(2_400.0, market.marginRequirement("BUY", Decimal.get(100), 96.0), 1.0e-9);
        assertEquals(2_880.0, market.marginRequirement("SELL", Decimal.get(100), 96.0), 1.0e-9);
    }

    @Test
    void aSnapshotWithoutATickerIsRefused() {
        assertThrows(NullPointerException.class, () -> new MarketSnapshot(
                null, TAKEN_AT, 0, 0, 0, null, null, null, 1.0, false, 1.0, false));
        assertThrows(NullPointerException.class, () -> MarketSnapshot.of(null, TAKEN_AT));
    }
}
