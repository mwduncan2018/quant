package mwd.trading.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ib.client.Decimal;

import mwd.trading.broker.ibkr.IdManager;
import mwd.trading.broker.ibkr.TimeManager;
import mwd.trading.domain.Stock;
import mwd.trading.lifecycle.EngineMode;
import mwd.trading.lifecycle.TradingGate;
import mwd.trading.persistence.JsonTradingStateStore;
import mwd.trading.reconciliation.BrokerState;
import mwd.trading.reconciliation.ReconciliationManager;
import mwd.trading.state.Blackboard;
import mwd.trading.support.TestConfig;

class OrderLifecycleHandlerTest {
    private static final String STRATEGY = "TEST_STRATEGY";
    private static final String TICKER = "AAPL";
    private static final int PARENT_ID = 100;

    @TempDir
    Path temporaryDirectory;

    @Test
    void zeroFillParentCancellationReleasesBothOwners() throws Exception {
        try (Fixture fixture = fixture()) {
            BracketOrder bracket = fixture.registerPendingBracket();

            fixture.handler.onOrderStatus(
                    PARENT_ID, "Cancelled", Decimal.ZERO, Decimal.get(10),
                    0.0, 0L, 0, 0.0, 0, "", 0.0);

            Stock stock = fixture.blackboard.getStock(TICKER);
            assertEquals(BracketOrder.Status.CANCELLED, bracket.getStatus());
            assertEquals(Stock.PositionState.FLAT, stock.getState().get());
            assertNull(stock.getActiveBracket());
            assertNull(fixture.blackboard.getPositionOwner(TICKER));
            assertNull(fixture.blackboard.getGlobalPendingOwner());
            assertEquals(0, fixture.blackboard.getActivePositionCount());
        }
    }

    @Test
    void parentFillReleasesOnlyEntrySerialization() throws Exception {
        try (Fixture fixture = fixture()) {
            BracketOrder bracket = fixture.registerPendingBracket();

            fixture.handler.onOrderStatus(
                    PARENT_ID, "Filled", Decimal.get(10), Decimal.ZERO,
                    100.0, 1000L, 0, 100.0, 0, "", 0.0);

            Stock stock = fixture.blackboard.getStock(TICKER);
            assertEquals(BracketOrder.Status.POSITION_OPEN, bracket.getStatus());
            assertEquals(Stock.PositionState.OPEN, stock.getState().get());
            assertSame(bracket, stock.getActiveBracket());
            assertEquals(STRATEGY, fixture.blackboard.getPositionOwner(TICKER));
            assertNull(fixture.blackboard.getGlobalPendingOwner());
            assertEquals(1, fixture.blackboard.getActivePositionCount());
        }
    }

    @Test
    void partialFillNeverDiscardsPositionOwnership() throws Exception {
        try (Fixture fixture = fixture()) {
            BracketOrder bracket = fixture.registerPendingBracket();

            fixture.handler.onOrderStatus(
                    PARENT_ID, "Cancelled", Decimal.get(2), Decimal.get(8),
                    100.0, 1000L, 0, 100.0, 0, "", 0.0);

            Stock stock = fixture.blackboard.getStock(TICKER);
            assertEquals(BracketOrder.Status.PARTIAL_PARENT, bracket.getStatus());
            assertEquals(Stock.PositionState.OPEN, stock.getState().get());
            assertSame(bracket, stock.getActiveBracket());
            assertEquals(STRATEGY, fixture.blackboard.getPositionOwner(TICKER));
            assertNull(fixture.blackboard.getGlobalPendingOwner());
            assertEquals(1, fixture.blackboard.getActivePositionCount());
            assertEquals(EngineMode.MANUAL_INTERVENTION, fixture.tradingGate.getMode());
            assertTrue(fixture.blackboard.getSystemHalted());
        }
    }

    private Fixture fixture() throws Exception {
        TestConfig config = new TestConfig(5, 1000, Set.of(TICKER));
        Blackboard blackboard = new Blackboard(
                new IdManager(), new TimeManager(), new OrderRegistry(), config);
        TradingGate tradingGate = new TradingGate();
        tradingGate.transitionTo(EngineMode.READY, "test");
        JsonTradingStateStore stateStore = new JsonTradingStateStore(
                temporaryDirectory.resolve("trading-state.json"));
        ReconciliationManager reconciliationManager = new ReconciliationManager(
                new BrokerState(),
                stateStore,
                tradingGate,
                () -> 1,
                Set.of(TICKER));
        OrderLifecycleHandler handler = new OrderLifecycleHandler(
                blackboard, reconciliationManager, stateStore, tradingGate);
        return new Fixture(
                blackboard, tradingGate, stateStore, reconciliationManager, handler);
    }

    private static final class Fixture implements AutoCloseable {
        private final Blackboard blackboard;
        private final TradingGate tradingGate;
        private final JsonTradingStateStore stateStore;
        private final ReconciliationManager reconciliationManager;
        private final OrderLifecycleHandler handler;

        private Fixture(
                Blackboard blackboard,
                TradingGate tradingGate,
                JsonTradingStateStore stateStore,
                ReconciliationManager reconciliationManager,
                OrderLifecycleHandler handler) {
            this.blackboard = blackboard;
            this.tradingGate = tradingGate;
            this.stateStore = stateStore;
            this.reconciliationManager = reconciliationManager;
            this.handler = handler;
        }

        BracketOrder registerPendingBracket() throws Exception {
            Decimal quantity = Decimal.get(10);
            BracketOrder bracket = new BracketOrder(
                    "trade-1",
                    STRATEGY,
                    "DU123456",
                    1,
                    "BUY",
                    TICKER,
                    PARENT_ID,
                    quantity);
            bracket.setEntryPrice(100.0);
            bracket.registerOrderLeg(PARENT_ID, "PARENT", "TE|trade-1|P", quantity);
            blackboard.getOrderRegistry().register(bracket);
            blackboard.getStock(TICKER).setActiveBracket(bracket);
            blackboard.getStock(TICKER).getState().set(Stock.PositionState.PENDING);
            assertTrue(blackboard.tryReservePosition(TICKER, STRATEGY));
            assertTrue(blackboard.tryAcquireGlobalPending(STRATEGY, TICKER));
            stateStore.recordIntent(bracket, "BUY");
            return bracket;
        }

        @Override
        public void close() {
            reconciliationManager.close();
        }
    }
}
