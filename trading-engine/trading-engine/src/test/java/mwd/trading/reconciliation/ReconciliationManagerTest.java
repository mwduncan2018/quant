package mwd.trading.reconciliation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ib.client.Decimal;

import mwd.trading.execution.BracketOrder;
import mwd.trading.lifecycle.TradingGate;
import mwd.trading.persistence.JsonTradingStateStore;

class ReconciliationManagerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void exactWorkingOrderIdentityMatches() throws Exception {
        JsonTradingStateStore stateStore = stateStoreWithActiveTrade();
        BrokerState.Snapshot brokerSnapshot = new BrokerState.Snapshot(
                System.currentTimeMillis(),
                List.of(),
                List.of(parentOrder("DU123456"), exitOrder("DU123456")),
                List.of());

        try (ReconciliationManager manager = manager(stateStore)) {
            assertTrue(manager.compare(brokerSnapshot, stateStore.snapshot()).isEmpty());
        }
    }

    @Test
    void genericTradingEngineOrderReferencesMatch() throws Exception {
        JsonTradingStateStore stateStore = stateStoreWithActiveTrade("TE");
        BrokerState.Snapshot brokerSnapshot = new BrokerState.Snapshot(
                System.currentTimeMillis(),
                List.of(),
                List.of(parentOrder("DU123456", "TE"), exitOrder("DU123456", "TE")),
                List.of());

        try (ReconciliationManager manager = manager(stateStore)) {
            assertTrue(manager.compare(brokerSnapshot, stateStore.snapshot()).isEmpty());
        }
    }

    @Test
    void missingProtectiveLegRequiresReview() throws Exception {
        JsonTradingStateStore stateStore = stateStoreWithActiveTrade();
        BrokerState.Snapshot brokerSnapshot = new BrokerState.Snapshot(
                System.currentTimeMillis(),
                List.of(),
                List.of(parentOrder("DU123456")),
                List.of());

        try (ReconciliationManager manager = manager(stateStore)) {
            assertTrue(manager.compare(brokerSnapshot, stateStore.snapshot()).stream()
                    .anyMatch(difference -> difference.contains("SC|trade-1|S1TP")));
        }
    }

    @Test
    void positionInAnotherAccountRequiresReview() throws Exception {
        JsonTradingStateStore stateStore = stateStoreWithActiveTrade();
        BrokerState.PositionRecord wrongAccountPosition = new BrokerState.PositionRecord(
                "DU999999", 265598, "AAPL", "STK", "10", 210.25);
        BrokerState.Snapshot brokerSnapshot = new BrokerState.Snapshot(
                System.currentTimeMillis(),
                List.of(wrongAccountPosition),
                List.of(parentOrder("DU123456"), exitOrder("DU123456")),
                List.of());

        try (ReconciliationManager manager = manager(stateStore)) {
            assertTrue(manager.compare(brokerSnapshot, stateStore.snapshot()).stream()
                    .anyMatch(difference -> difference.contains("DU999999/AAPL")));
        }
    }

    private JsonTradingStateStore stateStoreWithActiveTrade() throws Exception {
        return stateStoreWithActiveTrade("SC");
    }

    private JsonTradingStateStore stateStoreWithActiveTrade(String referencePrefix) throws Exception {
        JsonTradingStateStore stateStore = new JsonTradingStateStore(
                temporaryDirectory.resolve("trading-state.json"));
        Decimal quantity = Decimal.get(10);
        BracketOrder bracket = new BracketOrder(
                "trade-1", "TWO_SIGMA_DOWNSIDE", "DU123456", 265598, "BUY",
                "AAPL", 100, quantity);
        bracket.registerOrderLeg(100, "PARENT", referencePrefix + "|trade-1|P", quantity);
        bracket.registerOrderLeg(
                101, "S1_TAKE_PROFIT", referencePrefix + "|trade-1|S1TP", quantity);
        stateStore.recordIntent(bracket, "BUY");
        return stateStore;
    }

    private static ReconciliationManager manager(JsonTradingStateStore stateStore) {
        return new ReconciliationManager(
                new BrokerState(),
                stateStore,
                new TradingGate(),
                () -> 1,
                Set.of("AAPL"));
    }

    private static BrokerState.OrderRecord parentOrder(String account) {
        return parentOrder(account, "SC");
    }

    private static BrokerState.OrderRecord parentOrder(String account, String referencePrefix) {
        return order(100, account, referencePrefix + "|trade-1|P", "BUY", "LMT");
    }

    private static BrokerState.OrderRecord exitOrder(String account) {
        return exitOrder(account, "SC");
    }

    private static BrokerState.OrderRecord exitOrder(String account, String referencePrefix) {
        return order(101, account, referencePrefix + "|trade-1|S1TP", "SELL", "LMT");
    }

    private static BrokerState.OrderRecord order(
            int orderId, String account, String reference, String action, String orderType) {
        return new BrokerState.OrderRecord(
                orderId, orderId + 1000L, 0, orderId == 100 ? 0 : 100,
                account, 265598, "AAPL", reference, action, orderType,
                "10", 210.25, 0.0, "Submitted", "0", "10", false,
                System.currentTimeMillis());
    }
}
