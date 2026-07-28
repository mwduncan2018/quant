package mwd.trading.earnings;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

/**
 * One decoded {@code /earnings} response from the options proxy.
 *
 * <p>
 * Only entries the proxy flagged valid, with a parseable date, reach this
 * record. A ticker the proxy could not resolve is absent rather than present
 * with a null date, so nothing downstream can mistake "unknown" for "no
 * earnings scheduled".
 */
public record EarningsSnapshot(LocalDate tradingDate, Map<String, EarningsDate> byTicker) {

    public EarningsSnapshot {
        Objects.requireNonNull(tradingDate, "tradingDate");
        byTicker = Map.copyOf(Objects.requireNonNull(byTicker, "byTicker"));
    }

    /**
     * The report dates known for one ticker.
     *
     * <p>
     * Both are nullable and at least one is always present. {@code next} is
     * absent when the upstream source has not yet published the following
     * quarter; {@code last} is absent until a report has passed within the
     * proxy's retention window.
     *
     * <p>
     * {@code last} exists because the day after a report needs a date to
     * measure against, and the upstream source stops publishing it
     * inconsistently: some tickers roll to next quarter immediately, others
     * keep showing the date that just passed.
     */
    public record EarningsDate(
            String ticker, LocalDate nextEarningsDate, LocalDate lastEarningsDate) {

        public EarningsDate {
            Objects.requireNonNull(ticker, "ticker");
            if (nextEarningsDate == null && lastEarningsDate == null) {
                throw new IllegalArgumentException(
                        "An earnings entry needs at least one date for " + ticker);
            }
        }

        /** Every known report date, for a caller testing a window around each. */
        public java.util.List<LocalDate> knownDates() {
            if (nextEarningsDate == null) {
                return java.util.List.of(lastEarningsDate);
            }
            if (lastEarningsDate == null) {
                return java.util.List.of(nextEarningsDate);
            }
            return java.util.List.of(lastEarningsDate, nextEarningsDate);
        }
    }
}
