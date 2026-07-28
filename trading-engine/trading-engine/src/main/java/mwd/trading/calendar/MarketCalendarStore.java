package mwd.trading.calendar;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The authoritative record of the current session's hours and the surrounding
 * market days.
 *
 * <p>
 * The engine holds no market-hours knowledge of its own. Both the standard
 * 09:30-16:00 session and any early close are resolved by the proxy, which owns
 * the holiday feed, so nothing here encodes a time. An early-close date is
 * still a session, so an engine assuming a 16:00 close would schedule exits
 * into a market that shut at 13:00.
 *
 * <p>
 * Validity is scoped to a session and needs no age limit: a snapshot carries
 * the date it describes, so yesterday's hours stop being usable the moment the
 * New York date rolls over.
 */
public final class MarketCalendarStore {
    private static final Logger logger = LogManager.getLogger(MarketCalendarStore.class);

    private final AtomicReference<MarketSession> session = new AtomicReference<>();
    private final AtomicLong acceptedCount = new AtomicLong();
    private final AtomicLong rejectedCount = new AtomicLong();
    private final AtomicReference<String> lastRejectionReason = new AtomicReference<>();

    /**
     * Records a decoded session if it describes the expected trading date.
     *
     * @return whether the snapshot was accepted
     */
    public boolean accept(MarketSession candidate, LocalDate expectedTradingDate) {
        if (candidate == null) {
            return reject("calendar snapshot was null");
        }
        Objects.requireNonNull(expectedTradingDate, "expectedTradingDate");
        if (!expectedTradingDate.equals(candidate.tradingDate())) {
            // The proxy resolves the next session on or after today, so on a
            // weekend or full holiday this disagreement is expected and correct.
            return reject("calendar snapshot is for session " + candidate.tradingDate()
                    + " rather than " + expectedTradingDate);
        }
        session.set(candidate);
        acceptedCount.incrementAndGet();
        lastRejectionReason.set(null);
        return true;
    }

    /** Whether a usable session description exists for the given date. */
    public boolean isReadyFor(LocalDate tradingDate) {
        MarketSession current = session.get();
        return current != null
                && current.tradingDate().equals(tradingDate)
                && current.isTradingSession();
    }

    /** The instant the session closes, present only for the current session. */
    public Optional<Instant> sessionClose(LocalDate tradingDate) {
        MarketSession current = session.get();
        if (current == null
                || !current.tradingDate().equals(tradingDate)
                || !current.isTradingSession()) {
            return Optional.empty();
        }
        return Optional.ofNullable(current.closesAt());
    }

    /** Whether {@code now} is within {@code window} of the session close. */
    public boolean isWithinOfClose(LocalDate tradingDate, Instant now, Duration window) {
        Objects.requireNonNull(window, "window");
        return sessionClose(tradingDate)
                .map(close -> !now.isBefore(close.minus(window)))
                .orElse(false);
    }

    /**
     * Whether a date is a trading session.
     *
     * <p>
     * Deliberately minimal: a weekday that the feed does not mark closed. The
     * richer arithmetic stays in the proxy, which owns the calendar; only this
     * predicate and the two adjacent-day walks below are needed here, and
     * keeping them small keeps the two implementations from drifting.
     *
     * <p>
     * Returns false for any date the proxy's cache does not cover, because
     * outside that window a missing holiday record means "unknown", not "open".
     */
    public boolean isSession(LocalDate candidate) {
        MarketSession current = session.get();
        if (current == null || candidate == null || !coversDate(candidate)) {
            return false;
        }
        if (candidate.getDayOfWeek() == DayOfWeek.SATURDAY
                || candidate.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return false;
        }
        return !current.closedDates().contains(candidate);
    }

    /**
     * Whether the proxy's holiday cache covers a date.
     *
     * <p>
     * The feed returns upcoming records only, so its earliest record is simply
     * the next holiday; dates between today and that are genuinely holiday-free
     * rather than unknown. Anything before the current session, or beyond the
     * cache's last record, is outside what can be answered.
     */
    public boolean coversDate(LocalDate candidate) {
        MarketSession current = session.get();
        if (current == null || candidate == null) {
            return false;
        }
        if (candidate.isBefore(current.tradingDate())) {
            return false;
        }
        return current.coversTo() == null || !candidate.isAfter(current.coversTo());
    }

    /** The next trading session strictly after a date, within the covered window. */
    public Optional<LocalDate> nextSession(LocalDate from) {
        if (from == null) {
            return Optional.empty();
        }
        LocalDate candidate = from.plusDays(1);
        // A run of non-sessions longer than a fortnight does not occur in the
        // US equity calendar; the bound only stops an unbounded walk.
        for (int step = 0; step < 14; step++) {
            if (!coversDate(candidate)) {
                return Optional.empty();
            }
            if (isSession(candidate)) {
                return Optional.of(candidate);
            }
            candidate = candidate.plusDays(1);
        }
        return Optional.empty();
    }

    /**
     * The previous trading session strictly before a date.
     *
     * <p>
     * Walks backwards using weekends only. Past holidays fall outside the
     * proxy's forward-looking cache, so a holiday immediately before the
     * current session cannot be detected; the result is the previous weekday in
     * that case. Callers comparing against an earnings date should treat this
     * as the reason the blackout errs towards including a day rather than
     * excluding one.
     */
    public Optional<LocalDate> previousSessionApproximate(LocalDate from) {
        if (from == null) {
            return Optional.empty();
        }
        MarketSession current = session.get();
        if (current == null) {
            return Optional.empty();
        }
        LocalDate candidate = from.minusDays(1);
        for (int step = 0; step < 14; step++) {
            boolean weekend = candidate.getDayOfWeek() == DayOfWeek.SATURDAY
                    || candidate.getDayOfWeek() == DayOfWeek.SUNDAY;
            if (!weekend && !current.closedDates().contains(candidate)) {
                return Optional.of(candidate);
            }
            candidate = candidate.minusDays(1);
        }
        return Optional.empty();
    }

    /** Describes why the calendar is unusable for a session, if it is. */
    public Optional<String> describeUnready(LocalDate tradingDate) {
        MarketSession current = session.get();
        if (current == null) {
            return Optional.of("no market calendar has been retrieved");
        }
        if (!current.tradingDate().equals(tradingDate)) {
            return Optional.of("the market calendar is for session " + current.tradingDate()
                    + " rather than " + tradingDate);
        }
        if (!current.isTradingSession()) {
            return Optional.of(tradingDate + " is not a trading session (" + current.status() + ")");
        }
        return Optional.empty();
    }

    public Optional<MarketSession> currentSession() {
        return Optional.ofNullable(session.get());
    }

    public long getAcceptedCount() {
        return acceptedCount.get();
    }

    public long getRejectedCount() {
        return rejectedCount.get();
    }

    public String getLastRejectionReason() {
        return lastRejectionReason.get();
    }

    private boolean reject(String reason) {
        rejectedCount.incrementAndGet();
        lastRejectionReason.set(reason);
        logger.warn("Rejected a market calendar snapshot: {}", reason);
        return false;
    }
}
