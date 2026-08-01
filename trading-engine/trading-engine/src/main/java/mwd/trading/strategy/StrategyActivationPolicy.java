package mwd.trading.strategy;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import mwd.trading.config.Config;
import mwd.trading.lifecycle.TradingEnvironment;

/**
 * Resolves and validates strategy activation before any broker or background
 * component is started.
 */
public final class StrategyActivationPolicy {
    private static final Set<TradingEnvironment> PAPER_AND_LIVE =
            Set.of(TradingEnvironment.PAPER, TradingEnvironment.LIVE);
    private static final Set<TradingEnvironment> PAPER_ONLY =
            Set.of(TradingEnvironment.PAPER);

    private final TradingEnvironment environment;
    private final List<StrategyDefinition> definitions;
    private final List<StrategyDefinition> enabledDefinitions;

    private StrategyActivationPolicy(
            TradingEnvironment environment, List<StrategyDefinition> definitions) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.definitions = List.copyOf(definitions);
        validate();
        this.enabledDefinitions = this.definitions.stream()
                .filter(StrategyDefinition::enabled)
                .toList();
    }

    public static StrategyActivationPolicy from(Config config) {
        Objects.requireNonNull(config, "config");
        TradingEnvironment environment =
                TradingEnvironment.fromLiveTrading(config.isLiveTrading());

        List<StrategyDefinition> definitions = List.of(
                definition(config, TwoSigmaDownsideMeanReversionStrategy.STRATEGY_ID,
                        PAPER_AND_LIVE),
                definition(config, OneSigmaDownsideMeanReversionStrategy.STRATEGY_ID,
                        PAPER_ONLY),
                definition(config, OneSigmaUpsideMeanReversionStrategy.STRATEGY_ID,
                        PAPER_ONLY));

        if (environment == TradingEnvironment.LIVE
                && config.getExpectedAccount().isBlank()) {
            throw new IllegalStateException(
                    "IBKR_EXPECTED_ACCOUNT is required when LIVE_IBKR_TRADING is true");
        }
        return new StrategyActivationPolicy(environment, definitions);
    }

    public TradingEnvironment environment() {
        return environment;
    }

    public List<StrategyDefinition> definitions() {
        return definitions;
    }

    public List<StrategyDefinition> enabledDefinitions() {
        return enabledDefinitions;
    }

    public List<String> enabledStrategyIds() {
        return enabledDefinitions.stream().map(StrategyDefinition::id).toList();
    }

    public boolean isEnabled(String strategyId) {
        return definition(strategyId).map(StrategyDefinition::enabled).orElse(false);
    }

    /** Final outbound defense used by the shared order executor. */
    public void requireEntrySubmissionAllowed(String strategyId) {
        StrategyDefinition definition = definition(strategyId)
                .orElseThrow(() -> new IllegalStateException(
                        "Unknown strategy may not submit an entry: " + strategyId));
        if (!definition.enabled()) {
            throw new IllegalStateException(
                    "Disabled strategy may not submit an entry: " + definition.id());
        }
        if (!definition.permits(environment)) {
            throw new IllegalStateException("Strategy " + definition.id()
                    + " may not submit an entry in " + environment);
        }
    }

    public Optional<StrategyDefinition> definition(String strategyId) {
        if (strategyId == null) {
            return Optional.empty();
        }
        return definitions.stream()
                .filter(definition -> definition.id().equals(strategyId))
                .findFirst();
    }

    /** Symbols needed only by strategies that will actually be constructed. */
    public List<String> marketDataSymbols() {
        return enabledDefinitions.stream()
                .flatMap(definition -> Stream.concat(
                        definition.universe().stream(),
                        definition.referenceSymbols().stream()))
                .distinct()
                .sorted()
                .toList();
    }

    private void validate() {
        for (StrategyDefinition definition : definitions) {
            if (!definition.enabled()) {
                continue;
            }
            if (!definition.permits(environment)) {
                throw new IllegalStateException("Strategy " + definition.id()
                        + " is enabled in " + environment
                        + " but is permanently restricted to "
                        + definition.permittedEnvironments());
            }
            if (definition.universe().isEmpty()) {
                throw new IllegalStateException("Enabled strategy " + definition.id()
                        + " must have a nonempty universe");
            }
        }
    }

    private static StrategyDefinition definition(
            Config config, String strategyId, Set<TradingEnvironment> permittedEnvironments) {
        return new StrategyDefinition(
                strategyId,
                permittedEnvironments,
                config.isStrategyEnabled(strategyId),
                config.getStrategyUniverse(strategyId),
                config.getStrategyReferenceSymbols(strategyId));
    }
}
