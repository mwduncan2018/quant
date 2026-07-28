package mwd.trading.lifecycle;

import java.util.Objects;
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

    public State getState() {
        return state.get();
    }

    public EngineMode getMode() {
        return state.get().mode();
    }

    public boolean allowsNewEntries() {
        return getMode() == EngineMode.READY;
    }

    public boolean allowsAutomatedOrderChanges() {
        return getMode() == EngineMode.READY;
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
