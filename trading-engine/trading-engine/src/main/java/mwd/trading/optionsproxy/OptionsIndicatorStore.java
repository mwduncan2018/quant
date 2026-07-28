package mwd.trading.optionsproxy;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import mwd.trading.optionsproxy.proto.IndicatorFrame;

/**
 * The authoritative record of what the Python options proxy has published.
 *
 * <p>Every stored value is an immutable snapshot, so a reader always sees a
 * self-consistent value, trading date, validity flag and reception time
 * together. Strategies must read indicators from here rather than from loose
 * {@code Stock} fields, which carry no validity or session information.
 *
 * <p>A frame is accepted only when it is structurally sound and belongs to the
 * stream we expect. The last valid Static Daily Implied Move for a ticker is
 * retained after the proxy goes silent so an already-open position can still be
 * managed and liquidated; freshness gating applies only to new entries.
 */
public final class OptionsIndicatorStore {
    /** A Static Daily Implied Move that the proxy explicitly marked valid. */
    public record ImpliedMove(
            String ticker,
            double value,
            LocalDate tradingDate,
            long sequence,
            long emittedAtUnixMs,
            long receivedAtUnixMs) {
    }

    /** The shared SPY Gamma Flip carried by every frame, valid or not. */
    public record GammaFlip(
            double level,
            boolean valid,
            LocalDate tradingDate,
            long sequence,
            long emittedAtUnixMs,
            long receivedAtUnixMs) {
    }

    private record TickerState(
            long sequence,
            long emittedAtUnixMs,
            long receivedAtUnixMs,
            LocalDate tradingDate,
            boolean impliedMoveValid,
            ImpliedMove lastValidImpliedMove) {
    }

    private final Set<String> knownTickers;
    private final long frameMaxAgeMs;
    private final ConcurrentMap<String, TickerState> stateByTicker = new ConcurrentHashMap<>();
    private final AtomicReference<GammaFlip> gammaFlip = new AtomicReference<>();
    private final AtomicLong acceptedFrames = new AtomicLong();
    private final AtomicLong rejectedFrames = new AtomicLong();
    private volatile String lastRejectionReason;

    public OptionsIndicatorStore(Set<String> knownTickers, long frameMaxAgeMs) {
        if (frameMaxAgeMs <= 0) {
            throw new IllegalArgumentException("frameMaxAgeMs must be positive");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String ticker : Objects.requireNonNull(knownTickers, "knownTickers")) {
            if (ticker != null && !ticker.isBlank()) {
                normalized.add(normalize(ticker));
            }
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("At least one known ticker is required");
        }
        this.knownTickers = Set.copyOf(normalized);
        this.frameMaxAgeMs = frameMaxAgeMs;
    }

    /**
     * Validate a decoded frame and fold it into the store.
     *
     * @return {@code true} when the frame was accepted; rejected frames leave
     *         every previously stored value untouched.
     */
    public boolean accept(IndicatorFrame frame, long receivedAtUnixMs) {
        if (frame == null) {
            return reject("frame was null");
        }

        String ticker = normalize(frame.getTicker());
        if (ticker.isEmpty()) {
            return reject("frame carried a blank ticker");
        }
        if (!knownTickers.contains(ticker)) {
            return reject("frame carried unknown ticker " + ticker);
        }

        LocalDate tradingDate = parseTradingDate(frame.getTradingDate());
        if (tradingDate == null) {
            return reject("frame carried an unusable trading_date '" + frame.getTradingDate() + "'");
        }

        long emittedAtUnixMs = frame.getEmittedAtUnixMs();
        if (emittedAtUnixMs <= 0L) {
            return reject("frame carried a nonpositive emitted_at_unix_ms");
        }
        if (emittedAtUnixMs - receivedAtUnixMs > frameMaxAgeMs) {
            return reject("frame was emitted "
                    + (emittedAtUnixMs - receivedAtUnixMs) + " ms in the future");
        }

        long sequence = frame.getSequence();
        if (sequence <= 0L) {
            return reject("frame carried a nonpositive sequence");
        }

        boolean impliedMoveValid = frame.getStaticDailyImpliedMoveValid();
        double impliedMoveValue = frame.getStaticDailyImpliedMove();
        if (impliedMoveValid && !isPositiveFinite(impliedMoveValue)) {
            return reject("frame declared a valid but unusable Static Daily Implied Move");
        }

        boolean gammaFlipValid = frame.getSpyGammaFlipValid();
        double gammaFlipLevel = frame.getSpyGammaFlip();
        if (gammaFlipValid && !isPositiveFinite(gammaFlipLevel)) {
            return reject("frame declared a valid but unusable SPY Gamma Flip");
        }

        TickerState previous = stateByTicker.get(ticker);
        if (previous != null
                && sequence <= previous.sequence()
                && receivedAtUnixMs - previous.receivedAtUnixMs() <= frameMaxAgeMs) {
            return reject("frame sequence " + sequence + " for " + ticker
                    + " did not advance past " + previous.sequence());
        }

        ImpliedMove retainedImpliedMove = previous == null ? null : previous.lastValidImpliedMove();
        if (impliedMoveValid) {
            retainedImpliedMove = new ImpliedMove(
                    ticker,
                    impliedMoveValue,
                    tradingDate,
                    sequence,
                    emittedAtUnixMs,
                    receivedAtUnixMs);
        }

        stateByTicker.put(ticker, new TickerState(
                sequence,
                emittedAtUnixMs,
                receivedAtUnixMs,
                tradingDate,
                impliedMoveValid,
                retainedImpliedMove));
        gammaFlip.set(new GammaFlip(
                gammaFlipValid ? gammaFlipLevel : 0.0,
                gammaFlipValid,
                tradingDate,
                sequence,
                emittedAtUnixMs,
                receivedAtUnixMs));
        acceptedFrames.incrementAndGet();
        return true;
    }

    /**
     * The Static Daily Implied Move a new entry may rely on: explicitly valid in
     * the newest frame, stamped for {@code tradingDate}, and recently received.
     */
    public OptionalDouble impliedMoveForNewEntry(
            String ticker, LocalDate tradingDate, long nowUnixMs) {
        TickerState state = stateByTicker.get(normalize(ticker));
        if (state == null
                || !state.impliedMoveValid()
                || !state.tradingDate().equals(tradingDate)
                || !isFresh(state.receivedAtUnixMs(), nowUnixMs)) {
            return OptionalDouble.empty();
        }
        ImpliedMove impliedMove = state.lastValidImpliedMove();
        if (impliedMove == null || !impliedMove.tradingDate().equals(tradingDate)) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(impliedMove.value());
    }

    /**
     * The SPY Gamma Flip a new entry may rely on. It is shared by every frame,
     * so any ticker's traffic keeps it fresh.
     */
    public OptionalDouble gammaFlipForNewEntry(LocalDate tradingDate, long nowUnixMs) {
        GammaFlip currentGammaFlip = gammaFlip.get();
        if (currentGammaFlip == null
                || !currentGammaFlip.valid()
                || !currentGammaFlip.tradingDate().equals(tradingDate)
                || !isFresh(currentGammaFlip.receivedAtUnixMs(), nowUnixMs)) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(currentGammaFlip.level());
    }

    /**
     * The last Static Daily Implied Move accepted for this ticker regardless of
     * age. Position management uses this so a silent proxy cannot strand an open
     * position without its exit arithmetic.
     */
    public OptionalDouble lastKnownImpliedMove(String ticker) {
        TickerState state = stateByTicker.get(normalize(ticker));
        if (state == null || state.lastValidImpliedMove() == null) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(state.lastValidImpliedMove().value());
    }

    public Optional<ImpliedMove> impliedMove(String ticker) {
        TickerState state = stateByTicker.get(normalize(ticker));
        return state == null ? Optional.empty() : Optional.ofNullable(state.lastValidImpliedMove());
    }

    public Optional<GammaFlip> gammaFlip() {
        return Optional.ofNullable(gammaFlip.get());
    }

    /** Whether a frame for this ticker arrived inside the configured window. */
    public boolean hasFreshFrame(String ticker, long nowUnixMs) {
        TickerState state = stateByTicker.get(normalize(ticker));
        return state != null && isFresh(state.receivedAtUnixMs(), nowUnixMs);
    }

    /** The trading date stamped on the newest accepted frame for this ticker. */
    public Optional<LocalDate> tradingDate(String ticker) {
        TickerState state = stateByTicker.get(normalize(ticker));
        return state == null ? Optional.empty() : Optional.of(state.tradingDate());
    }

    public Set<String> getKnownTickers() {
        return knownTickers;
    }

    public long getFrameMaxAgeMs() {
        return frameMaxAgeMs;
    }

    public long getAcceptedFrameCount() {
        return acceptedFrames.get();
    }

    public long getRejectedFrameCount() {
        return rejectedFrames.get();
    }

    public String getLastRejectionReason() {
        return lastRejectionReason;
    }

    // A small negative age is tolerated so a modest clock adjustment cannot
    // make an otherwise current frame look unusable.
    private boolean isFresh(long receivedAtUnixMs, long nowUnixMs) {
        return Math.abs(nowUnixMs - receivedAtUnixMs) <= frameMaxAgeMs;
    }

    private boolean reject(String reason) {
        lastRejectionReason = reason;
        rejectedFrames.incrementAndGet();
        return false;
    }

    private static LocalDate parseTradingDate(String rawTradingDate) {
        if (rawTradingDate == null) {
            return null;
        }
        String trimmed = rawTradingDate.trim();
        if (trimmed.length() != 10) {
            return null;
        }
        try {
            return LocalDate.parse(trimmed);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static boolean isPositiveFinite(double value) {
        return Double.isFinite(value) && value > 0.0;
    }

    private static String normalize(String ticker) {
        return ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT);
    }
}
