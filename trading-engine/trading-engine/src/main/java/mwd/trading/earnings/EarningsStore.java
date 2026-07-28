package mwd.trading.earnings;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import mwd.trading.earnings.EarningsSnapshot.EarningsDate;

/**
 * The authoritative record of what the options proxy has published about
 * earnings dates.
 *
 * <p>
 * Validity is scoped to a trading session and needs no age limit. A snapshot
 * carries the session it was computed for, so yesterday's answer stops being
 * usable the moment the New York date rolls over, whether or not a refresh has
 * succeeded yet. That is the failure mode worth designing for: a stale
 * earnings date silently surviving into a new session is how a strategy trades
 * into a report it believed was days away.
 *
 * <p>
 * The store holds whole snapshots rather than merging tickers across fetches.
 * A partial merge would let two sessions' answers coexist, and there would be
 * no single date to check them against.
 */
public final class EarningsStore {
    private static final Logger logger = LogManager.getLogger(EarningsStore.class);

    private final Set<String> knownTickers;
    private final AtomicReference<Accepted> accepted = new AtomicReference<>();
    private final AtomicLong acceptedSnapshotCount = new AtomicLong();
    private final AtomicLong rejectedSnapshotCount = new AtomicLong();
    private final AtomicReference<String> lastRejectionReason = new AtomicReference<>();

    private record Accepted(
            LocalDate tradingDate, Map<String, EarningsDate> byTicker, long receivedAtUnixMs) {
    }

    public EarningsStore(Set<String> knownTickers) {
        Objects.requireNonNull(knownTickers, "knownTickers");
        if (knownTickers.isEmpty()) {
            throw new IllegalArgumentException("An earnings store needs at least one ticker");
        }
        Set<String> normalized = new java.util.LinkedHashSet<>();
        for (String ticker : knownTickers) {
            if (ticker != null && !ticker.isBlank()) {
                normalized.add(ticker.trim().toUpperCase(Locale.ROOT));
            }
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("An earnings store needs at least one ticker");
        }
        this.knownTickers = Set.copyOf(normalized);
    }

    /**
     * Records a decoded snapshot if it applies to the expected session.
     *
     * <p>
     * A snapshot for the wrong session is rejected outright rather than
     * partially applied. Tickers outside this engine's universe are dropped
     * quietly: the proxy may legitimately cover a wider universe, and that is
     * not an error in the response.
     *
     * @return whether the snapshot was accepted
     */
    public boolean accept(
            EarningsSnapshot snapshot, LocalDate expectedTradingDate, long receivedAtUnixMs) {
        if (snapshot == null) {
            return reject("snapshot was null");
        }
        Objects.requireNonNull(expectedTradingDate, "expectedTradingDate");
        if (!expectedTradingDate.equals(snapshot.tradingDate())) {
            return reject("snapshot is for session " + snapshot.tradingDate()
                    + " rather than " + expectedTradingDate);
        }

        Map<String, EarningsDate> retained = new LinkedHashMap<>();
        int ignored = 0;
        for (Map.Entry<String, EarningsDate> entry : snapshot.byTicker().entrySet()) {
            String ticker = entry.getKey() == null
                    ? null
                    : entry.getKey().trim().toUpperCase(Locale.ROOT);
            if (ticker == null || !knownTickers.contains(ticker) || entry.getValue() == null) {
                ignored++;
                continue;
            }
            retained.put(ticker, entry.getValue());
        }

        accepted.set(new Accepted(snapshot.tradingDate(), Map.copyOf(retained), receivedAtUnixMs));
        acceptedSnapshotCount.incrementAndGet();
        lastRejectionReason.set(null);
        if (ignored > 0) {
            logger.debug("Ignored {} earnings entries outside this engine's universe", ignored);
        }
        return true;
    }

    /** The next report date for a ticker, valid only for the given session. */
    public Optional<EarningsDate> earningsDate(String ticker, LocalDate tradingDate) {
        Accepted current = accepted.get();
        if (current == null || ticker == null || tradingDate == null) {
            return Optional.empty();
        }
        if (!current.tradingDate().equals(tradingDate)) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                current.byTicker().get(ticker.trim().toUpperCase(Locale.ROOT)));
    }

    /**
     * Calendar days from the session to the next report, negative once it has
     * passed. Empty when no upcoming date is known.
     *
     * <p>
     * Calendar days, not trading sessions: this engine holds no market
     * calendar, and the holiday data lives in the proxy. A strategy needing
     * market days measures them against the calendar store instead.
     */
    public OptionalLong daysUntilEarnings(String ticker, LocalDate tradingDate) {
        return earningsDate(ticker, tradingDate)
                .map(EarningsDate::nextEarningsDate)
                .map(next -> OptionalLong.of(ChronoUnit.DAYS.between(tradingDate, next)))
                .orElseGet(OptionalLong::empty);
    }

    /** Whether a usable snapshot exists for the given session. */
    public boolean isReadyFor(LocalDate tradingDate) {
        Accepted current = accepted.get();
        return current != null && current.tradingDate().equals(tradingDate);
    }

    /**
     * Describes why the required tickers are not covered for this session, or
     * an empty optional when they are. A strategy that quietly stops trading is
     * indistinguishable from one that is broken.
     */
    public Optional<String> describeUnready(Set<String> requiredTickers, LocalDate tradingDate) {
        Objects.requireNonNull(requiredTickers, "requiredTickers");
        Accepted current = accepted.get();
        if (current == null) {
            return Optional.of("no earnings snapshot has been accepted");
        }
        if (!current.tradingDate().equals(tradingDate)) {
            return Optional.of("the earnings snapshot is for session " + current.tradingDate()
                    + " rather than " + tradingDate);
        }
        List<String> missing = new ArrayList<>();
        for (String ticker : requiredTickers) {
            if (ticker != null
                    && !current.byTicker().containsKey(ticker.trim().toUpperCase(Locale.ROOT))) {
                missing.add(ticker.trim().toUpperCase(Locale.ROOT));
            }
        }
        missing.sort(String::compareTo);
        return missing.isEmpty()
                ? Optional.empty()
                : Optional.of("no earnings date for " + String.join(", ", missing));
    }

    /** The session the retained snapshot was computed for, if any. */
    public Optional<LocalDate> snapshotTradingDate() {
        Accepted current = accepted.get();
        return current == null ? Optional.empty() : Optional.of(current.tradingDate());
    }

    /** Tickers covered by the retained snapshot, for diagnostics. */
    public Set<String> coveredTickers() {
        Accepted current = accepted.get();
        return current == null ? Set.of() : current.byTicker().keySet();
    }

    public long getAcceptedSnapshotCount() {
        return acceptedSnapshotCount.get();
    }

    public long getRejectedSnapshotCount() {
        return rejectedSnapshotCount.get();
    }

    public String getLastRejectionReason() {
        return lastRejectionReason.get();
    }

    private boolean reject(String reason) {
        rejectedSnapshotCount.incrementAndGet();
        lastRejectionReason.set(reason);
        logger.warn("Rejected an earnings snapshot: {}", reason);
        return false;
    }
}
