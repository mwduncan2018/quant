package mwd.trading.risk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Per-ticker reference data that does not come from the market: the GICS sector
 * and the margin rates for both of IBKR's regimes.
 *
 * <p>
 * These used to be measured, by submitting a what-if order per symbol per
 * direction on a five-minute cycle. IBKR's own guidance is at most one what-if
 * per minute and one per ten real order submissions; that loop ran at roughly
 * twelve per minute and some four and a half thousand per session, which is not
 * a rate to negotiate down - it is the wrong mechanism. The rates are read off
 * IBKR's public margin calculator instead and kept in a file alongside the
 * engine, so refreshing them is an edit rather than a rebuild.
 *
 * <p>
 * The cost of a table is that it goes stale silently. {@link #retrieved()}
 * carries the date it was collected so the engine can say how old it is at
 * startup, and a symbol with no rate falls back to a deliberately conservative
 * default rather than a typical one: a forgotten symbol then under-sizes, which
 * is recoverable, instead of over-leveraging, which is not.
 */
public final class UniverseReference {

    /** One row of the file. Rates are per unit of notional, so 0.5 is 50%. */
    public record TickerReference(
            String ticker,
            Sector sector,
            double regTLong,
            double regTShort,
            double portfolioLong,
            double portfolioShort) {

        public TickerReference {
            Objects.requireNonNull(ticker, "ticker");
            Objects.requireNonNull(sector, "sector");
        }

        /** The rate for this regime and direction, or {@code NaN} when unset. */
        public double rate(MarginMethodology methodology, boolean isLong) {
            if (methodology == MarginMethodology.REG_T) {
                return isLong ? regTLong : regTShort;
            }
            return isLong ? portfolioLong : portfolioShort;
        }
    }

    private static final String RETRIEVED_PREFIX = "# retrieved:";

    private final Map<String, TickerReference> byTicker;
    private final LocalDate retrieved;
    private final MarginMethodology methodology;
    private final double defaultLongRate;
    private final double defaultShortRate;

    UniverseReference(
            Map<String, TickerReference> byTicker,
            LocalDate retrieved,
            MarginMethodology methodology,
            double defaultLongRate,
            double defaultShortRate) {
        this.byTicker = Map.copyOf(byTicker);
        this.retrieved = retrieved;
        this.methodology = Objects.requireNonNull(methodology, "methodology");
        this.defaultLongRate = requirePositiveRate(defaultLongRate, "defaultLongRate");
        this.defaultShortRate = requirePositiveRate(defaultShortRate, "defaultShortRate");
    }

    /**
     * Reads the CSV. A missing file is not an error here: the engine starts on
     * defaults and says so, which is preferable to refusing to run because a
     * reference table has not been filled in yet.
     */
    public static UniverseReference load(
            Path path,
            MarginMethodology methodology,
            double defaultLongRate,
            double defaultShortRate) throws IOException {
        Objects.requireNonNull(path, "path");
        if (!Files.exists(path)) {
            return new UniverseReference(
                    Map.of(), null, methodology, defaultLongRate, defaultShortRate);
        }
        return parse(
                Files.readAllLines(path, StandardCharsets.UTF_8),
                methodology, defaultLongRate, defaultShortRate);
    }

    static UniverseReference parse(
            List<String> lines,
            MarginMethodology methodology,
            double defaultLongRate,
            double defaultShortRate) {
        Map<String, TickerReference> rows = new LinkedHashMap<>();
        LocalDate retrievedDate = null;
        int lineNumber = 0;

        for (String rawLine : lines) {
            lineNumber++;
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                LocalDate parsed = parseRetrieved(line);
                if (parsed != null) {
                    retrievedDate = parsed;
                }
                continue;
            }
            if (line.toLowerCase(Locale.ROOT).startsWith("ticker,")) {
                continue;
            }

            String[] fields = line.split(",", -1);
            if (fields.length < 6) {
                throw new IllegalArgumentException(
                        "Line " + lineNumber + " has " + fields.length
                                + " fields; expected ticker,sector,regt_long,regt_short,pm_long,pm_short");
            }
            String ticker = fields[0].trim().toUpperCase(Locale.ROOT);
            if (ticker.isEmpty()) {
                throw new IllegalArgumentException("Line " + lineNumber + " has no ticker");
            }
            TickerReference reference;
            try {
                reference = new TickerReference(
                        ticker,
                        Sector.parse(fields[1]),
                        optionalRate(fields[2]),
                        optionalRate(fields[3]),
                        optionalRate(fields[4]),
                        optionalRate(fields[5]));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "Line " + lineNumber + " (" + ticker + "): " + exception.getMessage(),
                        exception);
            }
            if (rows.put(ticker, reference) != null) {
                throw new IllegalArgumentException(
                        "Line " + lineNumber + ": " + ticker + " appears more than once");
            }
        }
        return new UniverseReference(
                rows, retrievedDate, methodology, defaultLongRate, defaultShortRate);
    }

    /** The rate to size this direction against; the conservative default when unset. */
    public double marginRate(String ticker, boolean isLong) {
        TickerReference reference = lookup(ticker);
        if (reference != null) {
            double rate = reference.rate(methodology, isLong);
            if (isUsableRate(rate)) {
                return rate;
            }
        }
        return isLong ? defaultLongRate : defaultShortRate;
    }

    /** Margin consumed by a position of this size, at this ticker's rate. */
    public double marginRequirement(String ticker, boolean isLong, double notionalValue) {
        return Math.abs(notionalValue) * marginRate(ticker, isLong);
    }

    public Optional<Sector> sector(String ticker) {
        TickerReference reference = lookup(ticker);
        return reference == null ? Optional.empty() : Optional.of(reference.sector());
    }

    public MarginMethodology methodology() {
        return methodology;
    }

    /** The date the rates were collected, absent when the file carried no marker. */
    public Optional<LocalDate> retrieved() {
        return Optional.ofNullable(retrieved);
    }

    public Optional<Long> ageInDays(LocalDate today) {
        return retrieved().map(date -> java.time.temporal.ChronoUnit.DAYS.between(date, today));
    }

    public int coveredTickerCount() {
        return byTicker.size();
    }

    /**
     * Symbols the engine intends to trade that have no row at all. These get the
     * default rate and, more seriously, no sector - so their exposure cannot be
     * rolled into any sector total.
     */
    public Set<String> missingFrom(Set<String> tradedTickers) {
        Set<String> missing = new TreeSet<>();
        for (String ticker : tradedTickers) {
            if (lookup(ticker) == null) {
                missing.add(ticker.trim().toUpperCase(Locale.ROOT));
            }
        }
        return missing;
    }

    /**
     * Symbols that have a row but no rate for the configured regime, and are
     * therefore sizing against the conservative default rather than a measured
     * figure.
     */
    public Set<String> usingDefaultRate(Set<String> tradedTickers) {
        Set<String> defaulted = new TreeSet<>();
        for (String ticker : tradedTickers) {
            TickerReference reference = lookup(ticker);
            if (reference == null) {
                continue;
            }
            if (!isUsableRate(reference.rate(methodology, true))
                    || !isUsableRate(reference.rate(methodology, false))) {
                defaulted.add(reference.ticker());
            }
        }
        return defaulted;
    }

    /** Human-readable startup lines describing what the table does and does not cover. */
    public List<String> describeCoverage(Set<String> tradedTickers, LocalDate today) {
        List<String> report = new ArrayList<>();
        report.add("Margin methodology " + methodology + "; " + coveredTickerCount()
                + " tickers in the reference table");
        ageInDays(today).ifPresentOrElse(
                age -> report.add("Reference data retrieved " + retrieved + " (" + age + " days ago)"),
                () -> report.add("Reference data carries no retrieved date"));
        Set<String> missing = missingFrom(tradedTickers);
        if (!missing.isEmpty()) {
            report.add("No reference row, so no sector and a default margin rate: " + missing);
        }
        Set<String> defaulted = usingDefaultRate(tradedTickers);
        if (!defaulted.isEmpty()) {
            report.add("No " + methodology + " rate, sizing against the conservative default: "
                    + defaulted);
        }
        return report;
    }

    private TickerReference lookup(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return null;
        }
        return byTicker.get(ticker.trim().toUpperCase(Locale.ROOT));
    }

    private static boolean isUsableRate(double rate) {
        return Double.isFinite(rate) && rate > 0.0;
    }

    /** A blank cell means "not collected yet" and falls back to the default. */
    private static double optionalRate(String field) {
        String trimmed = field == null ? "" : field.trim();
        if (trimmed.isEmpty()) {
            return Double.NaN;
        }
        double parsed;
        try {
            parsed = Double.parseDouble(trimmed);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("'" + trimmed + "' is not a margin rate");
        }
        if (!Double.isFinite(parsed) || parsed <= 0.0 || parsed > 1.0) {
            throw new IllegalArgumentException(
                    "Margin rate " + trimmed + " must be greater than 0 and at most 1");
        }
        return parsed;
    }

    private static LocalDate parseRetrieved(String commentLine) {
        String lower = commentLine.toLowerCase(Locale.ROOT);
        if (!lower.startsWith(RETRIEVED_PREFIX)) {
            return null;
        }
        String value = commentLine.substring(RETRIEVED_PREFIX.length()).trim();
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                    "Retrieved date '" + value + "' is not an ISO yyyy-MM-dd date", exception);
        }
    }

    private static double requirePositiveRate(double rate, String name) {
        if (!Double.isFinite(rate) || rate <= 0.0 || rate > 1.0) {
            throw new IllegalArgumentException(
                    name + " must be greater than 0 and at most 1, was " + rate);
        }
        return rate;
    }
}
