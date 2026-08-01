package mwd.trading.strategy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import mwd.trading.lifecycle.TradingEnvironment;
import mwd.trading.support.TestConfig;

class StrategyActivationPolicyTest {
    private static final List<String> STRATEGY_IDS = List.of(
            TwoSigmaDownsideMeanReversionStrategy.STRATEGY_ID,
            OneSigmaDownsideMeanReversionStrategy.STRATEGY_ID,
            OneSigmaUpsideMeanReversionStrategy.STRATEGY_ID);

    @Test
    void paperAcceptsEveryEnabledDisabledCombination() {
        for (int mask = 0; mask < 8; mask++) {
            ActivationConfig config = configured(mask, false, "");
            StrategyActivationPolicy policy = assertDoesNotThrow(
                    () -> StrategyActivationPolicy.from(config), "PAPER mask " + mask);

            assertEquals(Integer.bitCount(mask), policy.enabledDefinitions().size());
            assertEquals(TradingEnvironment.PAPER, policy.environment());
        }
    }

    @Test
    void liveAcceptsOnlyCombinationsWithoutPaperOnlyStrategies() {
        for (int mask = 0; mask < 8; mask++) {
            ActivationConfig config = configured(mask, true, "U1234567");
            boolean paperOnlyEnabled = (mask & 0b110) != 0;

            if (paperOnlyEnabled) {
                IllegalStateException thrown = assertThrows(
                        IllegalStateException.class,
                        () -> StrategyActivationPolicy.from(config),
                        "LIVE mask " + mask);
                assertTrue(thrown.getMessage().contains("permanently restricted"));
            } else {
                StrategyActivationPolicy policy = assertDoesNotThrow(
                        () -> StrategyActivationPolicy.from(config), "LIVE mask " + mask);
                assertEquals(TradingEnvironment.LIVE, policy.environment());
            }
        }
    }

    @Test
    void liveRequiresAnExactExpectedAccountBeforeStartupContinues() {
        ActivationConfig config = configured(0b001, true, "   ");

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> StrategyActivationPolicy.from(config));

        assertTrue(thrown.getMessage().contains("IBKR_EXPECTED_ACCOUNT"));
    }

    @Test
    void enabledStrategyRequiresANonemptyUniverse() {
        ActivationConfig config = configured(0b001, false, "");
        config.universe(TwoSigmaDownsideMeanReversionStrategy.STRATEGY_ID, Set.of());

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> StrategyActivationPolicy.from(config));

        assertTrue(thrown.getMessage().contains("nonempty universe"));
    }

    @Test
    void disabledStrategyMayBeEmptyAndContributesNoSubscriptions() {
        ActivationConfig config = configured(0b001, false, "");
        config.universe(OneSigmaDownsideMeanReversionStrategy.STRATEGY_ID, Set.of());
        config.universe(OneSigmaUpsideMeanReversionStrategy.STRATEGY_ID, Set.of("MSFT"));
        config.reference(OneSigmaUpsideMeanReversionStrategy.STRATEGY_ID, Set.of("QQQ"));
        config.reference(TwoSigmaDownsideMeanReversionStrategy.STRATEGY_ID, Set.of("SPY"));

        StrategyActivationPolicy policy = StrategyActivationPolicy.from(config);

        assertEquals(List.of(TwoSigmaDownsideMeanReversionStrategy.STRATEGY_ID),
                policy.enabledStrategyIds());
        assertEquals(List.of("AAPL", "SPY"), policy.marketDataSymbols());
        assertFalse(policy.isEnabled(OneSigmaDownsideMeanReversionStrategy.STRATEGY_ID));
    }

    @Test
    void permanentModeCapabilitiesArePartOfTheDefinitions() {
        StrategyActivationPolicy policy = StrategyActivationPolicy.from(
                configured(0b111, false, ""));

        StrategyDefinition twoSigma = policy.definition(
                TwoSigmaDownsideMeanReversionStrategy.STRATEGY_ID).orElseThrow();
        StrategyDefinition downsideVerifier = policy.definition(
                OneSigmaDownsideMeanReversionStrategy.STRATEGY_ID).orElseThrow();
        StrategyDefinition upsideVerifier = policy.definition(
                OneSigmaUpsideMeanReversionStrategy.STRATEGY_ID).orElseThrow();

        assertEquals(Set.of(TradingEnvironment.PAPER, TradingEnvironment.LIVE),
                twoSigma.permittedEnvironments());
        assertEquals(Set.of(TradingEnvironment.PAPER),
                downsideVerifier.permittedEnvironments());
        assertEquals(Set.of(TradingEnvironment.PAPER),
                upsideVerifier.permittedEnvironments());
    }

    @Test
    void outboundAdmissionRejectsDisabledAndUnknownStrategies() {
        StrategyActivationPolicy policy = StrategyActivationPolicy.from(
                configured(0b001, false, ""));

        assertDoesNotThrow(() -> policy.requireEntrySubmissionAllowed(
                TwoSigmaDownsideMeanReversionStrategy.STRATEGY_ID));
        assertThrows(IllegalStateException.class, () -> policy.requireEntrySubmissionAllowed(
                OneSigmaDownsideMeanReversionStrategy.STRATEGY_ID));
        assertThrows(IllegalStateException.class,
                () -> policy.requireEntrySubmissionAllowed("UNREGISTERED_STRATEGY"));
    }

    private static ActivationConfig configured(
            int enabledMask, boolean liveTrading, String expectedAccount) {
        ActivationConfig config = new ActivationConfig(liveTrading, expectedAccount);
        for (int index = 0; index < STRATEGY_IDS.size(); index++) {
            String strategyId = STRATEGY_IDS.get(index);
            config.enabled(strategyId, (enabledMask & (1 << index)) != 0);
            config.universe(strategyId, Set.of("AAPL"));
        }
        return config;
    }

    private static final class ActivationConfig extends TestConfig {
        private final boolean liveTrading;
        private final String expectedAccount;
        private final Map<String, Boolean> enabledByStrategy = new HashMap<>();
        private final Map<String, Set<String>> universeByStrategy = new HashMap<>();
        private final Map<String, Set<String>> referenceByStrategy = new HashMap<>();

        private ActivationConfig(boolean liveTrading, String expectedAccount) {
            super(5, 10_000, Set.of("AAPL"));
            this.liveTrading = liveTrading;
            this.expectedAccount = expectedAccount;
        }

        private void enabled(String strategyId, boolean enabled) {
            enabledByStrategy.put(strategyId, enabled);
        }

        private void universe(String strategyId, Set<String> universe) {
            universeByStrategy.put(strategyId, universe);
        }

        private void reference(String strategyId, Set<String> referenceSymbols) {
            referenceByStrategy.put(strategyId, referenceSymbols);
        }

        @Override public boolean isLiveTrading() { return liveTrading; }
        @Override public String getExpectedAccount() { return expectedAccount; }
        @Override public boolean isStrategyEnabled(String strategyId) {
            return enabledByStrategy.getOrDefault(strategyId, false);
        }
        @Override public Set<String> getStrategyUniverse(String strategyId) {
            return universeByStrategy.getOrDefault(strategyId, Set.of());
        }
        @Override public Set<String> getStrategyReferenceSymbols(String strategyId) {
            return referenceByStrategy.getOrDefault(strategyId, Set.of());
        }
    }
}
