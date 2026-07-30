package mwd.trading.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import com.ib.client.Decimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import mwd.trading.broker.ibkr.IdManager;
import mwd.trading.broker.ibkr.TimeManager;
import mwd.trading.domain.Stock;
import mwd.trading.execution.BracketOrder;
import mwd.trading.execution.OrderRegistry;
import mwd.trading.state.Blackboard;
import mwd.trading.support.TestConfig;

/**
 * Covers the three-step entry gate on its own.
 *
 * <p>
 * These interleavings used to be reachable only through {@code evaluateNewEntry},
 * where the unwind was two booleans and a {@code finally} block wrapped around
 * sizing and order submission. Testing "the position reservation lost a race, so
 * the engine-wide lock must be given back" meant driving a whole strategy cycle.
 * Here each case is three lines.
 */
class EntryAdmissionTest {
    private static final String TICKER = "AAPL";
    private static final String STRATEGY = "TEST_STRATEGY";
    private static final String OTHER_STRATEGY = "OTHER_STRATEGY";

    private Blackboard blackboard;
    private EntryAdmission admission;
    private Stock stock;

    @BeforeEach
    void setUp() {
        blackboard = new Blackboard(
                new IdManager(), new TimeManager(), new OrderRegistry(),
                new TestConfig(3, 10_000L, Set.of(TICKER)));
        admission = new EntryAdmission(blackboard);
        stock = blackboard.getStock(TICKER);
    }

    private Stock.PositionState state() {
        return state(stock);
    }

    private Stock.PositionState state(Stock subject) {
        return subject.positionState(
                blackboard.getPositionOwner(subject.getTicker()) != null);
    }

    @Test
    void admissionTakesAllThreeHoldings() {
        EntryAdmission.Reservation reservation = admission.tryAdmit(STRATEGY, stock);

        assertNotNull(reservation);
        assertEquals(STRATEGY, blackboard.getPositionOwner(TICKER));
        assertEquals(Stock.PositionState.PENDING, state());
        assertTrue(blackboard.isGlobalPendingOwnedBy(STRATEGY, TICKER));
    }

    @Test
    void aSecondStrategyIsRefusedWhileAnEntryIsOutstanding() {
        assertNotNull(admission.tryAdmit(STRATEGY, stock));

        Stock other = blackboard.getStock("MSFT");
        assertNull(admission.tryAdmit(OTHER_STRATEGY, other),
                "only one entry may be outstanding at IBKR at a time");
        assertEquals(Stock.PositionState.FLAT, state(other));
        assertNull(blackboard.getPositionOwner("MSFT"));
    }

    @Test
    void aLostPositionReservationGivesBackTheEngineWideLock() {
        // Another strategy already owns the ticker, so step two fails.
        assertTrue(blackboard.tryReservePosition(TICKER, OTHER_STRATEGY));

        assertNull(admission.tryAdmit(STRATEGY, stock));
        assertNull(blackboard.getGlobalPendingOwner(),
                "a failed admission must not leave the engine-wide lock held");
        assertEquals(OTHER_STRATEGY, blackboard.getPositionOwner(TICKER),
                "the reservation it lost to is untouched");
    }

    @Test
    void aLiveBracketOnTheTickerGivesBackBothHoldings() {
        // The symbol is unreserved but already carries a filled bracket, so the
        // reservation succeeds and the third check is the only thing standing
        // between this strategy and a second position on one ticker.
        BracketOrder bracketOrder = new BracketOrder(
                "trade-1", OTHER_STRATEGY, "DU123456", 1, "BUY",
                TICKER, 1, Decimal.get(10));
        bracketOrder.setStatus(BracketOrder.Status.POSITION_OPEN);
        stock.setActiveBracket(bracketOrder);

        assertNull(admission.tryAdmit(STRATEGY, stock));
        assertNull(blackboard.getGlobalPendingOwner());
        assertNull(blackboard.getPositionOwner(TICKER),
                "a failed admission must not leave the ticker reserved");
        assertEquals(Stock.PositionState.OPEN, state(), "the bracket it lost to is untouched");
    }

    @Test
    void theCapOnActivePositionsIsEnforced() {
        // TestConfig allows three.
        assertTrue(blackboard.tryReservePosition("A", OTHER_STRATEGY));
        assertTrue(blackboard.tryReservePosition("B", OTHER_STRATEGY));
        assertTrue(blackboard.tryReservePosition("C", OTHER_STRATEGY));

        assertNull(admission.tryAdmit(STRATEGY, stock));
        assertNull(blackboard.getGlobalPendingOwner());
        assertEquals(Stock.PositionState.FLAT, state());
    }

    @Test
    void releaseUnwindsEverythingAndReturnsToFlat() {
        EntryAdmission.Reservation reservation = admission.tryAdmit(STRATEGY, stock);

        reservation.release();

        assertEquals(Stock.PositionState.FLAT, state());
        assertNull(blackboard.getPositionOwner(TICKER));
        assertNull(blackboard.getGlobalPendingOwner());
    }

    @Test
    void releaseIsIdempotent() {
        EntryAdmission.Reservation reservation = admission.tryAdmit(STRATEGY, stock);
        reservation.release();

        // A second strategy takes the freed slot; a stray release must not
        // reach in and cancel someone else's reservation.
        Stock other = blackboard.getStock(TICKER);
        assertNotNull(admission.tryAdmit(OTHER_STRATEGY, other));

        reservation.release();

        assertEquals(OTHER_STRATEGY, blackboard.getPositionOwner(TICKER));
        assertTrue(blackboard.isGlobalPendingOwnedBy(OTHER_STRATEGY, TICKER));
    }

    @Test
    void keepingLeavesTheHoldingsInPlaceForTheLifecycle() {
        EntryAdmission.Reservation reservation = admission.tryAdmit(STRATEGY, stock);

        reservation.keep();
        reservation.release();

        assertEquals(Stock.PositionState.PENDING, state());
        assertEquals(STRATEGY, blackboard.getPositionOwner(TICKER));
        assertTrue(blackboard.isGlobalPendingOwnedBy(STRATEGY, TICKER),
                "a kept entry keeps the lock until the lifecycle releases it on acknowledgement");
    }

    @Test
    void closingWithoutKeepingReleases() {
        try (EntryAdmission.Reservation reservation = admission.tryAdmit(STRATEGY, stock)) {
            assertSame(Stock.PositionState.PENDING, state());
        }

        assertEquals(Stock.PositionState.FLAT, state(),
                "an unconsidered exit path must free the lock, not park every strategy");
        assertNull(blackboard.getGlobalPendingOwner());
        assertNull(blackboard.getPositionOwner(TICKER));
    }

    @Test
    void closingAfterKeepingIsANoOp() {
        try (EntryAdmission.Reservation reservation = admission.tryAdmit(STRATEGY, stock)) {
            reservation.keep();
        }

        assertEquals(Stock.PositionState.PENDING, state());
        assertEquals(STRATEGY, blackboard.getPositionOwner(TICKER));
    }
}
