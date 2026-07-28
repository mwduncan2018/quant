package mwd.trading.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TradingGateTest {
    @Test
    void onlyReadyAllowsNewEntries() {
        TradingGate gate = new TradingGate();

        assertEquals(EngineMode.STARTING, gate.getMode());
        assertFalse(gate.allowsNewEntries());

        gate.transitionTo(EngineMode.READY, "reconciled");
        assertTrue(gate.allowsNewEntries());

        gate.transitionTo(EngineMode.DEGRADED, "connection lost");
        assertFalse(gate.allowsNewEntries());

        gate.requireManualIntervention("state mismatch");
        assertEquals(EngineMode.MANUAL_INTERVENTION, gate.getMode());
        assertFalse(gate.allowsNewEntries());

        gate.transitionTo(EngineMode.READY, "late success callback");
        assertEquals(EngineMode.MANUAL_INTERVENTION, gate.getMode());

        gate.transitionTo(EngineMode.STOPPING, "shutdown");
        assertEquals(EngineMode.STOPPING, gate.getMode());
    }
}
