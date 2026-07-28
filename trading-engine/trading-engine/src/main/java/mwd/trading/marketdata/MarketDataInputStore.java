package mwd.trading.marketdata;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Tracks, per symbol and per input, when IBKR last delivered a usable value.
 *
 * <p>
 * This replaces a coarse per-symbol flag that was set by any arriving tick and
 * cleared only by an explicit resubscribe. Under that rule one bid-size tick
 * marked a symbol ready even if its previous close, VWAP, or minute bars had
 * never arrived, and a stream that froze mid-session stayed "fresh" forever.
 *
 * <p>
 * Only handlers record here, and only after accepting a value, so a recorded
 * input carries a real guarantee: a value that passed validation was written to
 * the {@link mwd.trading.domain.Stock}. The values themselves stay in
 * {@code Stock}; this store holds only the metadata about them, so the two can
 * never disagree about a number.
 *
 * <p>
 * Expiry follows {@link MarketDataInput.Expiry}. Aged inputs lapse once older
 * than the configured maximum; session-scoped inputs stay usable for the
 * session that received them.
 */
public final class MarketDataInputStore implements MarketDataFreshness {
    private static final ZoneId NEW_YORK_ZONE = ZoneId.of("America/New_York");
    private static final MarketDataInput[] INPUTS = MarketDataInput.values();

    private final long maxAgeMs;
    private final Clock newYorkClock;
    private final Map<String, AtomicReferenceArray<Reading>> readings = new ConcurrentHashMap<>();

    /** One accepted delivery: when it arrived and which session it belongs to. */
    private record Reading(long recordedAtUnixMs, LocalDate sessionDate) {
    }

    public MarketDataInputStore(long maxAgeMs) {
        this(maxAgeMs, Clock.systemUTC());
    }

    public MarketDataInputStore(long maxAgeMs, Clock clock) {
        if (maxAgeMs <= 0) {
            throw new IllegalArgumentException(
                    "A market-data maximum age must be positive, got " + maxAgeMs);
        }
        this.maxAgeMs = maxAgeMs;
        this.newYorkClock = Objects.requireNonNull(clock, "clock").withZone(NEW_YORK_ZONE);
    }

    /**
     * Records that a usable value for {@code input} was just written for
     * {@code ticker}. Call this only after the value has been accepted; recording
     * a rejected callback would reintroduce the flag this store replaced.
     */
    public void record(String ticker, MarketDataInput input) {
        String normalized = normalize(ticker);
        if (normalized == null || input == null) {
            return;
        }
        readings.computeIfAbsent(normalized, key -> new AtomicReferenceArray<>(INPUTS.length))
                .set(input.ordinal(), new Reading(newYorkClock.millis(), LocalDate.now(newYorkClock)));
    }

    /**
     * Drops every recorded input. Called when the socket disconnects or the
     * subscriptions are rebuilt, because nothing delivered before that point can
     * be assumed to still describe the market.
     */
    public void markAllStale() {
        readings.clear();
    }

    @Override
    public boolean isFresh(String ticker, MarketDataInput input) {
        return unreadyReason(normalize(ticker), input) == null;
    }

    @Override
    public boolean areAllFresh(String ticker, Set<MarketDataInput> required) {
        Objects.requireNonNull(required, "required");
        if (required.isEmpty()) {
            // A strategy that requires nothing would trade on no data at all.
            return false;
        }
        String normalized = normalize(ticker);
        for (MarketDataInput input : required) {
            if (unreadyReason(normalized, input) != null) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Optional<String> describeUnready(String ticker, Set<MarketDataInput> required) {
        Objects.requireNonNull(required, "required");
        if (required.isEmpty()) {
            return Optional.of("no required market-data inputs are declared");
        }

        String normalized = normalize(ticker);
        List<String> reasons = new ArrayList<>();
        // Iterate the enum rather than the set so the reason text is stable
        // regardless of how the caller ordered its declaration.
        for (MarketDataInput input : INPUTS) {
            if (!required.contains(input)) {
                continue;
            }
            String reason = unreadyReason(normalized, input);
            if (reason != null) {
                reasons.add(reason);
            }
        }
        return reasons.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", reasons));
    }

    /** Milliseconds since an input was last recorded, for diagnostics. */
    public OptionalLong ageMs(String ticker, MarketDataInput input) {
        Reading reading = readingFor(normalize(ticker), input);
        return reading == null
                ? OptionalLong.empty()
                : OptionalLong.of(newYorkClock.millis() - reading.recordedAtUnixMs());
    }

    /** The number of symbols holding at least one recorded input. */
    public int coveredSymbolCount() {
        return readings.size();
    }

    private String unreadyReason(String normalizedTicker, MarketDataInput input) {
        if (normalizedTicker == null) {
            return "no ticker was supplied";
        }
        if (input == null) {
            return "no input was supplied";
        }

        Reading reading = readingFor(normalizedTicker, input);
        if (reading == null) {
            return normalizedTicker + " has never received its " + input.description();
        }

        if (input.expiry() == MarketDataInput.Expiry.SESSION_SCOPED) {
            LocalDate today = LocalDate.now(newYorkClock);
            if (!reading.sessionDate().equals(today)) {
                return normalizedTicker + " has a " + input.description()
                        + " from session " + reading.sessionDate() + " rather than " + today;
            }
            return null;
        }

        long age = newYorkClock.millis() - reading.recordedAtUnixMs();
        // A small negative age is tolerated so a modest clock adjustment cannot
        // make an otherwise current value look unusable.
        if (Math.abs(age) > maxAgeMs) {
            return normalizedTicker + " has a " + input.description()
                    + " that is " + age + "ms old, beyond the " + maxAgeMs + "ms limit";
        }
        return null;
    }

    private Reading readingFor(String normalizedTicker, MarketDataInput input) {
        if (normalizedTicker == null || input == null) {
            return null;
        }
        AtomicReferenceArray<Reading> perInput = readings.get(normalizedTicker);
        return perInput == null ? null : perInput.get(input.ordinal());
    }

    private static String normalize(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return null;
        }
        return ticker.trim().toUpperCase(Locale.ROOT);
    }
}
