package mwd.trading.calendar;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

/**
 * One decoded {@code /calendar} response from the options proxy.
 *
 * @param tradingDate  the session the proxy resolved for the request
 * @param status       {@code open}, {@code early-close}, or {@code closed}
 * @param opensAt      session open, always present for a session
 * @param closesAt     session close, always present for a session
 * @param hoursSource  {@code standard} or {@code early-close-record}
 * @param closedDates  dates the feed marks fully closed
 * @param coversFrom   earliest date the proxy's cache covers, may be null
 * @param coversTo     latest date the proxy's cache covers, may be null
 */
public record MarketSession(
        LocalDate tradingDate,
        String status,
        Instant opensAt,
        Instant closesAt,
        String hoursSource,
        Set<LocalDate> closedDates,
        LocalDate coversFrom,
        LocalDate coversTo) {

    public MarketSession {
        Objects.requireNonNull(tradingDate, "tradingDate");
        Objects.requireNonNull(status, "status");
        closedDates = Set.copyOf(Objects.requireNonNull(closedDates, "closedDates"));
    }

    public boolean isTradingSession() {
        return !"closed".equals(status) && closesAt != null;
    }

    public boolean isEarlyClose() {
        return "early-close".equals(status);
    }
}
