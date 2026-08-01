package mwd.trading.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void liveEntryPermissionRequiresVolatileArmingAfterReadiness() {
        TradingGate gate = new TradingGate(true);

        assertTrue(gate.requiresLiveTradingArming());
        assertFalse(gate.isLiveTradingArmed());
        assertThrows(IllegalStateException.class, gate::armLiveTrading);

        gate.transitionTo(EngineMode.READY, "reconciled");
        assertFalse(gate.allowsNewEntries());
        assertTrue(gate.allowsAutomatedOrderChanges());

        gate.armLiveTrading();
        assertTrue(gate.isLiveTradingArmed());
        assertTrue(gate.allowsNewEntries());

        gate.transitionTo(EngineMode.DEGRADED, "connection lost");
        assertFalse(gate.allowsNewEntries());

        TradingGate restartedProcess = new TradingGate(true);
        restartedProcess.transitionTo(EngineMode.READY, "reconciled");
        assertFalse(restartedProcess.isLiveTradingArmed());
        assertFalse(restartedProcess.allowsNewEntries());
    }

    @Test
    void paperGateCannotArmLiveTrading() {
        TradingGate paperGate = new TradingGate();
        paperGate.transitionTo(EngineMode.READY, "reconciled");

        assertThrows(IllegalStateException.class, paperGate::armLiveTrading);
        assertFalse(paperGate.isLiveTradingArmed());
        assertTrue(paperGate.allowsNewEntries());
    }
}
