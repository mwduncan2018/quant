package mwd.trading.calendar;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import mwd.trading.proxy.ProxyFetchException;

/**
 * Keeps {@link MarketCalendarStore} current for the session in progress.
 *
 * <p>
 * Session hours are static within a day, so this fetches once per trading date
 * rather than polling. It retries while the session is uncovered because the
 * usual cause is the proxy not being up yet.
 */
public final class MarketCalendarRefresher implements Runnable {
    private static final Logger logger = LogManager.getLogger(MarketCalendarRefresher.class);
    private static final ZoneId NEW_YORK_ZONE = ZoneId.of("America/New_York");

    private final MarketCalendarClient client;
    private final MarketCalendarStore store;
    private final Clock newYorkClock;
    private final long retryDelayMs;
    private final long idleDelayMs;

    private String lastReportedFailure;

    public MarketCalendarRefresher(
            MarketCalendarClient client,
            MarketCalendarStore store,
            long retryDelayMs,
            long idleDelayMs) {
        this(client, store, retryDelayMs, idleDelayMs, Clock.systemUTC());
    }

    MarketCalendarRefresher(
            MarketCalendarClient client,
            MarketCalendarStore store,
            long retryDelayMs,
            long idleDelayMs,
            Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.store = Objects.requireNonNull(store, "store");
        if (retryDelayMs <= 0 || idleDelayMs <= 0) {
            throw new IllegalArgumentException("Calendar refresh delays must be positive");
        }
        this.retryDelayMs = retryDelayMs;
        this.idleDelayMs = idleDelayMs;
        this.newYorkClock = Objects.requireNonNull(clock, "clock").withZone(NEW_YORK_ZONE);
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            long sleepMs = refreshOnce() ? idleDelayMs : retryDelayMs;
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** @return whether the current session is covered when this returns */
    boolean refreshOnce() {
        LocalDate tradingDate = LocalDate.now(newYorkClock);
        if (store.isReadyFor(tradingDate)) {
            return true;
        }

        MarketSession session;
        try {
            session = client.fetch();
        } catch (ProxyFetchException exception) {
            String failure = exception.getMessage();
            if (!failure.equals(lastReportedFailure)) {
                logger.warn("Market calendar refresh failed; retrying every {}ms: {}",
                        retryDelayMs, failure);
                lastReportedFailure = failure;
            }
            return false;
        }

        if (!store.accept(session, tradingDate)) {
            // Expected on a weekend or full holiday: the proxy resolves the
            // next session, which is not today. The store logs the reason.
            return false;
        }

        if (lastReportedFailure != null) {
            logger.info("Market calendar refresh recovered");
            lastReportedFailure = null;
        }
        logger.info("Market session {} is {} ({} hours), closing at {}",
                session.tradingDate(), session.status(), session.hoursSource(),
                session.closesAt());
        return true;
    }
}
