package mwd.trading.risk;

import java.util.Locale;

/**
 * Which of IBKR's two margin regimes the account is on, and therefore which pair
 * of rates in the reference file applies.
 *
 * <p>
 * The distinction is not cosmetic. Under {@link #REG_T} the initial requirement
 * for long equity is the Federal Reserve's flat 50%, identical for every
 * marginable symbol, and per-symbol variation appears only where IBKR imposes a
 * house requirement above it. Under {@link #PORTFOLIO} the requirement is
 * risk-based and genuinely differs per symbol. The reference file carries both
 * pairs so that changing regime is a configuration change rather than a
 * re-collection of every rate.
 */
public enum MarginMethodology {
    REG_T,
    PORTFOLIO;

    public static MarginMethodology parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("A margin methodology is required");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z]+", "_");
        for (MarginMethodology methodology : values()) {
            if (methodology.name().equals(normalized)) {
                return methodology;
            }
        }
        throw new IllegalArgumentException("Unknown margin methodology '" + raw + "'");
    }
}
