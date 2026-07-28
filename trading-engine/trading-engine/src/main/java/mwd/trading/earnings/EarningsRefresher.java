package mwd.trading.earnings;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import mwd.trading.proxy.ProxyFetchException;

/**
 * Keeps {@link EarningsStore} current for the session in progress.
 *
 * <p>
 * Earnings dates are static within a session, so this fetches once per trading
 * date rather than polling. A failure retries on a fixed delay until the
 * session is covered, because the usual cause is the proxy not being up yet
 * rather than anything about the data.
 *
 * <p>
 * Nothing here decides whether a strategy may trade. The store simply stops
 * reporting a session as covered once the date rolls over, and each strategy
 * applies its own policy to what it finds.
 */
public final class EarningsRefresher implements Runnable {
    private static final Logger logger = LogManager.getLogger(EarningsRefresher.class);
    private static final ZoneId NEW_YORK_ZONE = ZoneId.of("America/New_York");

    private final EarningsClient client;
    private final EarningsStore store;
    private final Clock newYorkClock;
    private final long retryDelayMs;
    private final long idleDelayMs;
    private final Consumer<EarningsSnapshot> acceptedListener;

    private String lastReportedFailure;

    public EarningsRefresher(
            EarningsClient client,
            EarningsStore store,
            long retryDelayMs,
            long idleDelayMs,
            Consumer<EarningsSnapshot> acceptedListener) {
        this(client, store, retryDelayMs, idleDelayMs, acceptedListener, Clock.systemUTC());
    }

    EarningsRefresher(
            EarningsClient client,
            EarningsStore store,
            long retryDelayMs,
            long idleDelayMs,
            Consumer<EarningsSnapshot> acceptedListener,
            Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.store = Objects.requireNonNull(store, "store");
        if (retryDelayMs <= 0 || idleDelayMs <= 0) {
            throw new IllegalArgumentException("Earnings refresh delays must be positive");
        }
        this.retryDelayMs = retryDelayMs;
        this.idleDelayMs = idleDelayMs;
        this.acceptedListener = acceptedListener;
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

    /**
     * Brings the store up to date for the current session if it is not already.
     *
     * @return whether the session is covered when this returns
     */
    boolean refreshOnce() {
        LocalDate tradingDate = LocalDate.now(newYorkClock);
        if (store.isReadyFor(tradingDate)) {
            return true;
        }

        EarningsSnapshot snapshot;
        try {
            snapshot = client.fetch();
        } catch (ProxyFetchException exception) {
            // Log a repeated failure once. A proxy that is down for an hour
            // should leave one line and a recovery line, not hundreds.
            String failure = exception.getMessage();
            if (!failure.equals(lastReportedFailure)) {
                logger.warn("Earnings refresh failed; retrying every {}ms: {}",
                        retryDelayMs, failure);
                lastReportedFailure = failure;
            }
            return false;
        }

        if (!store.accept(snapshot, tradingDate, System.currentTimeMillis())) {
            // A snapshot for a different session is normal shortly after
            // midnight or on a non-session day; the store logs the reason.
            return false;
        }

        if (lastReportedFailure != null) {
            logger.info("Earnings refresh recovered");
            lastReportedFailure = null;
        }
        logger.info("Earnings dates loaded for session {}: {} of the engine's tickers covered",
                tradingDate, store.coveredTickers().size());
        if (acceptedListener != null) {
            acceptedListener.accept(snapshot);
        }
        return true;
    }
}
