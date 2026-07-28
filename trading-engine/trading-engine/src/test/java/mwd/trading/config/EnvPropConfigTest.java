package mwd.trading.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import mwd.trading.strategy.TwoSigmaDownsideMeanReversionStrategy;

class EnvPropConfigTest {
    @Test
    void strategyTradeUniverseIsSeparateFromReferenceSymbols() {
        Config config = new EnvPropConfig();

        assertTrue(config.getStrategyUniverse(TwoSigmaDownsideMeanReversionStrategy.STRATEGY_ID).contains("AAPL"));
        assertFalse(config.getStrategyUniverse(TwoSigmaDownsideMeanReversionStrategy.STRATEGY_ID).contains("SPY"));
        assertTrue(config.getStrategyReferenceSymbols(TwoSigmaDownsideMeanReversionStrategy.STRATEGY_ID).contains("SPY"));
    }
}
