package mwd.trading.risk;

import java.util.Locale;

/**
 * The eleven GICS sectors, used to roll per-ticker exposure up into a sector
 * total that the concentration limit is measured against.
 *
 * <p>
 * All eleven are declared even though the current universe uses only eight,
 * because the universe is configuration and a symbol from any sector can be
 * added to it without touching code.
 */
public enum Sector {
    COMMUNICATION_SERVICES,
    CONSUMER_DISCRETIONARY,
    CONSUMER_STAPLES,
    ENERGY,
    FINANCIALS,
    HEALTH_CARE,
    INDUSTRIALS,
    INFORMATION_TECHNOLOGY,
    MATERIALS,
    REAL_ESTATE,
    UTILITIES;

    /**
     * Parses a sector written by hand in the reference file, tolerating case and
     * either spaces or underscores - "Health Care", "HEALTH_CARE", and
     * "health care" are the same sector.
     *
     * @throws IllegalArgumentException when the text names no known sector, so a
     *         typo fails the file rather than silently dropping the symbol out
     *         of its sector total
     */
    public static Sector parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("A sector is required");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z]+", "_");
        for (Sector sector : values()) {
            if (sector.name().equals(normalized)) {
                return sector;
            }
        }
        throw new IllegalArgumentException("Unknown sector '" + raw + "'");
    }
}
