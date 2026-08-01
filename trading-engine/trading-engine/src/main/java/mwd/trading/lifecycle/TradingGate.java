package mwd.trading.lifecycle;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single application-wide authority for deciding whether a strategy may submit
 * a new entry. Connection and reconciliation code may close the gate; strategy
 * code may only read it.
 */
public final class TradingGate {
    public record State(EngineMode mode, String reason, long changedAtEpochMillis) {
        public State {
            Objects.requireNonNull(mode, "mode");
            reason = reason == null ? "" : reason;
        }
    }

    private final AtomicReference<State> state = new AtomicReference<>(
            new State(EngineMode.STARTING, "Application starting", System.currentTimeMillis()));
    private final boolean liveTradingArmingRequired;
    private final AtomicBoolean liveTradingArmed = new AtomicBoolean(false);

    /** PAPER/test gate: READY is sufficient for new entries. */
    public TradingGate() {
        this(false);
    }

    /**
     * LIVE gates always start disarmed. No constructor accepts an initial armed
     * value, so configuration and persisted state cannot restore it.
     */
    public TradingGate(boolean liveTradingArmingRequired) {
        this.liveTradingArmingRequired = liveTradingArmingRequired;
    }

    public State getState() {
        return state.get();
    }

    public EngineMode getMode() {
        return state.get().mode();
    }

    public boolean allowsNewEntries() {
        return getMode() == EngineMode.READY
                && (!liveTradingArmingRequired || liveTradingArmed.get());
    }

    public boolean allowsAutomatedOrderChanges() {
        return getMode() == EngineMode.READY;
    }

    public boolean requiresLiveTradingArming() {
        return liveTradingArmingRequired;
    }

    public boolean isLiveTradingArmed() {
        return liveTradingArmed.get();
    }

    /**
     * Arms LIVE entry submission for this process only. The UI is the sole
     * production caller; the state is intentionally never serialized.
     */
    public void armLiveTrading() {
        if (!liveTradingArmingRequired) {
            throw new IllegalStateException("This PAPER gate cannot arm LIVE trading");
        }
        if (getMode() != EngineMode.READY) {
            throw new IllegalStateException(
                    "LIVE trading can be armed only after reconciliation reaches READY");
        }
        liveTradingArmed.set(true);
    }

    public void transitionTo(EngineMode mode, String reason) {
        State next = new State(mode, reason, System.currentTimeMillis());
        state.updateAndGet(current -> {
            if (current.mode() == EngineMode.MANUAL_INTERVENTION
                    && mode != EngineMode.MANUAL_INTERVENTION
                    && mode != EngineMode.STOPPING) {
                return current;
            }
            return next;
        });
    }

    public void requireManualIntervention(String reason) {
        transitionTo(EngineMode.MANUAL_INTERVENTION, reason);
    }
}
