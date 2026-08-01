package mwd.trading.strategy;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import mwd.trading.lifecycle.TradingEnvironment;

/** Immutable startup metadata for one strategy. */
public record StrategyDefinition(
        String id,
        Set<TradingEnvironment> permittedEnvironments,
        boolean enabled,
        Set<String> universe,
        Set<String> referenceSymbols) {

    public StrategyDefinition {
        id = Objects.requireNonNull(id, "id").trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("Strategy id must not be blank");
        }
        permittedEnvironments = Set.copyOf(Objects.requireNonNull(
                permittedEnvironments, "permittedEnvironments"));
        if (permittedEnvironments.isEmpty()) {
            throw new IllegalArgumentException("A strategy must permit at least one environment");
        }
        universe = normalizeSymbols(universe, "universe");
        referenceSymbols = normalizeSymbols(referenceSymbols, "referenceSymbols");
    }

    public boolean permits(TradingEnvironment environment) {
        return permittedEnvironments.contains(Objects.requireNonNull(environment, "environment"));
    }

    private static Set<String> normalizeSymbols(Set<String> symbols, String name) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String symbol : Objects.requireNonNull(symbols, name)) {
            if (symbol != null && !symbol.isBlank()) {
                normalized.add(symbol.trim().toUpperCase(Locale.ROOT));
            }
        }
        return Set.copyOf(normalized);
    }
}
