package mwd.trading.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /**
     * Market-data quality and the traded account used to be one flag, so enabling
     * a real-time subscription on the PAPER engine would also have switched the
     * TWS port to 7496 and the journal to the live file.
     */
    @Test
    void theTradedAccountIsSeparateFromTheMarketDataQuality() {
        Config config = new EnvPropConfig();

        // The shipped configuration is PAPER on delayed data.
        assertFalse(config.isLiveTrading());
        assertFalse(config.isLiveIBKRData());
        assertEquals(7497, config.getIBKRPort());
        assertEquals(3, config.getMarketDataType());
    }

    @Test
    void everyCoherentPairingIsAccepted() {
        assertDoesNotThrow(() -> EnvPropConfig.requireCoherentDataAndTradingPair(false, false));
        assertDoesNotThrow(() -> EnvPropConfig.requireCoherentDataAndTradingPair(true, false));
        assertDoesNotThrow(() -> EnvPropConfig.requireCoherentDataAndTradingPair(true, true));
    }

    @Test
    void liveTradingOnDelayedDataRefusesToStart() {
        // The one pairing the split makes expressible and nobody means to write.
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> EnvPropConfig.requireCoherentDataAndTradingPair(false, true));

        assertTrue(thrown.getMessage().contains("delayed market data"));
    }
}
