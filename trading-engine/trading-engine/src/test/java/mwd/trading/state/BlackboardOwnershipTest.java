package mwd.trading.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import mwd.trading.broker.ibkr.IdManager;
import mwd.trading.broker.ibkr.TimeManager;
import mwd.trading.execution.OrderRegistry;
import mwd.trading.support.TestConfig;

class BlackboardOwnershipTest {
    @Test
    void positionReservationIsOwnedAndReleasedExactlyOnce() {
        Blackboard blackboard = blackboard(2);

        assertTrue(blackboard.tryReservePosition("AAPL", "DOWN"));
        assertFalse(blackboard.tryReservePosition("AAPL", "UP"));
        assertEquals(1, blackboard.getActivePositionCount());
        assertEquals("DOWN", blackboard.getPositionOwner("AAPL"));

        assertFalse(blackboard.releasePosition("AAPL", "UP"));
        assertEquals(1, blackboard.getActivePositionCount());
        assertTrue(blackboard.releasePosition("AAPL", "DOWN"));
        assertFalse(blackboard.releasePosition("AAPL", "DOWN"));
        assertEquals(0, blackboard.getActivePositionCount());
        assertNull(blackboard.getPositionOwner("AAPL"));
    }

    @Test
    void maximumCountsUniqueOwnedSymbolsRatherThanCleanupCalls() {
        Blackboard blackboard = blackboard(2);

        assertTrue(blackboard.tryReservePosition("AAPL", "DOWN"));
        assertTrue(blackboard.tryReservePosition("MSFT", "DOWN"));
        assertFalse(blackboard.tryReservePosition("NVDA", "DOWN"));
        assertEquals(2, blackboard.getActivePositionCount());
    }

    @Test
    void pendingSerializationCanOnlyBeReleasedByItsOwner() {
        Blackboard blackboard = blackboard(2);

        assertTrue(blackboard.tryAcquireGlobalPending("DOWN", "AAPL"));
        assertFalse(blackboard.tryAcquireGlobalPending("UP", "MSFT"));
        assertFalse(blackboard.releaseGlobalPending("UP", "AAPL"));
        assertTrue(blackboard.isGlobalPendingOwnedBy("DOWN", "AAPL"));
        assertTrue(blackboard.releaseGlobalPending("DOWN", "AAPL"));
        assertNull(blackboard.getGlobalPendingOwner());
    }

    private static Blackboard blackboard(int maximumPositions) {
        return new Blackboard(
                new IdManager(),
                new TimeManager(),
                new OrderRegistry(),
                new TestConfig(maximumPositions, 1000, Set.of("AAPL")));
    }
}
