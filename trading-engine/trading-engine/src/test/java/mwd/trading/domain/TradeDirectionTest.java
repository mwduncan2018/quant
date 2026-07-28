package mwd.trading.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TradeDirectionTest {
    @Test
    void longEntryAcceptsOnlyMarketAtOrBelowBuyLimit() {
        assertTrue(TradeDirection.LONG.acceptsEntryPrice(99.99, 100.00));
        assertTrue(TradeDirection.LONG.acceptsEntryPrice(100.00, 100.00));
        assertFalse(TradeDirection.LONG.acceptsEntryPrice(100.01, 100.00));
    }

    @Test
    void shortEntryAcceptsOnlyMarketAtOrAboveSellLimit() {
        assertTrue(TradeDirection.SHORT.acceptsEntryPrice(100.01, 100.00));
        assertTrue(TradeDirection.SHORT.acceptsEntryPrice(100.00, 100.00));
        assertFalse(TradeDirection.SHORT.acceptsEntryPrice(99.99, 100.00));
    }

    @Test
    void invalidPricesAreRejectedForBothDirections() {
        assertFalse(TradeDirection.LONG.acceptsEntryPrice(0.0, 100.0));
        assertFalse(TradeDirection.SHORT.acceptsEntryPrice(Double.NaN, 100.0));
        assertFalse(TradeDirection.LONG.acceptsEntryPrice(100.0, Double.POSITIVE_INFINITY));
    }
}
