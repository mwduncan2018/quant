package mwd.trading.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;

import mwd.trading.lifecycle.TradingEnvironment;
import mwd.trading.strategy.OneSigmaDownsideMeanReversionStrategy;
import mwd.trading.strategy.OneSigmaUpsideMeanReversionStrategy;
import mwd.trading.strategy.StrategyActivationPolicy;
import mwd.trading.strategy.TwoSigmaDownsideMeanReversionStrategy;
import mwd.trading.support.TestConfig;

class StartupManifestTest {
    @Test
    void manifestUnambiguouslyCapturesPaperIdentityAndEnabledStrategies() {
        TestConfig config = new TestConfig(5, 10_000, Set.of("AAPL"));
        StrategyActivationPolicy activationPolicy = StrategyActivationPolicy.from(config);
        String logFile = Path.of("target", "paper-engine.log")
                .toAbsolutePath().normalize().toString();

        StartupManifest manifest = StartupManifest.from(config, activationPolicy, logFile);

        assertEquals(TradingEnvironment.PAPER, manifest.tradingEnvironment());
        assertEquals("DU123456", manifest.accountSelection());
        assertEquals(0, manifest.clientId());
        assertEquals(3, manifest.enabledStrategies().size());
        assertEquals(Path.of("target/test-state.json").toAbsolutePath().normalize().toString(),
                manifest.tradingStateFile());
        assertEquals(logFile, manifest.sessionLogFile());
        assertTrue(manifest.toLogValue().contains("mode=PAPER"));
        assertTrue(manifest.toLogValue().contains("account=DU123456"));
        assertThrows(UnsupportedOperationException.class,
                () -> manifest.enabledStrategies().add("ANOTHER"));
    }

    @Test
    void blankPaperAccountIsNamedAsBrokerSelectedRatherThanLoggedAmbiguously() {
        TestConfig config = new TestConfig(5, 10_000, Set.of("AAPL")) {
            @Override public String getExpectedAccount() { return ""; }
        };
        StrategyActivationPolicy activationPolicy = StrategyActivationPolicy.from(config);

        StartupManifest manifest = StartupManifest.from(
                config, activationPolicy, StartupManifest.UNAVAILABLE_LOG_FILE);

        assertEquals(StartupManifest.BROKER_SELECTED_ACCOUNT, manifest.accountSelection());
        assertTrue(manifest.toLogValue().contains("account=<TWS_MANAGED_ACCOUNT>"));
    }

    @Test
    void manifestUnambiguouslyCapturesLiveIdentityWithoutPaperOnlyStrategies() {
        TestConfig config = new TestConfig(5, 10_000, Set.of("AAPL")) {
            @Override public boolean isLiveTrading() { return true; }
            @Override public String getExpectedAccount() { return "U7654321"; }
            @Override public int getIBKRClientId() { return 31; }
            @Override public String getTradingStatePath() { return "target/live-state.json"; }
            @Override public boolean isStrategyEnabled(String strategyId) {
                return TwoSigmaDownsideMeanReversionStrategy.STRATEGY_ID.equals(strategyId);
            }
        };
        StrategyActivationPolicy activationPolicy = StrategyActivationPolicy.from(config);

        StartupManifest manifest = StartupManifest.from(
                config, activationPolicy, "C:\\logs\\live-engine.log");

        assertEquals(TradingEnvironment.LIVE, manifest.tradingEnvironment());
        assertEquals("U7654321", manifest.accountSelection());
        assertEquals(31, manifest.clientId());
        assertEquals(Set.of(TwoSigmaDownsideMeanReversionStrategy.STRATEGY_ID),
                Set.copyOf(manifest.enabledStrategies()));
        assertTrue(manifest.toLogValue().contains("mode=LIVE"));
        assertFalse(manifest.enabledStrategies().contains(
                OneSigmaDownsideMeanReversionStrategy.STRATEGY_ID));
        assertFalse(manifest.enabledStrategies().contains(
                OneSigmaUpsideMeanReversionStrategy.STRATEGY_ID));
    }
}
