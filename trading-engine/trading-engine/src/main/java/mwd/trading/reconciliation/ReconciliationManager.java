package mwd.trading.reconciliation;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.ib.client.Contract;
import com.ib.client.Decimal;
import com.ib.client.EClientSocket;
import com.ib.client.Execution;
import com.ib.client.ExecutionFilter;
import com.ib.client.Order;
import com.ib.client.OrderState;

import mwd.trading.lifecycle.EngineMode;
import mwd.trading.lifecycle.TradingGate;
import mwd.trading.persistence.JsonTradingStateStore;

/**
 * Collects a complete IBKR snapshot after startup or reconnect and compares it
 * with the local JSON intent journal. The first implementation is deliberately
 * read-only: any uncertainty closes the trading gate for manual review.
 */
public final class ReconciliationManager implements AutoCloseable {
    private static final Logger logger = LogManager.getLogger(ReconciliationManager.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);

    public record Result(boolean matched, String reason, List<String> differences, long completedAtEpochMillis) {
        public Result {
            differences = List.copyOf(differences);
        }
    }

    private static final class CollectionEpoch {
        private final long number;
        private final int executionRequestId;
        private final BrokerState collected = new BrokerState();
        private boolean positionsComplete;
        private boolean openOrdersComplete;
        private boolean completedOrdersComplete;
        private boolean executionsComplete;

        private CollectionEpoch(long number, int executionRequestId) {
            this.number = number;
            this.executionRequestId = executionRequestId;
        }

        private boolean complete() {
            return positionsComplete && openOrdersComplete && completedOrdersComplete && executionsComplete;
        }
    }

    private final BrokerState brokerState;
    private final JsonTradingStateStore stateStore;
    private final TradingGate tradingGate;
    private final IntSupplier requestIdSupplier;
    private final Set<String> managedSymbols;
    private final ScheduledExecutorService scheduler;
    private final Duration timeout;
    private EClientSocket client;
    private CollectionEpoch activeEpoch;
    private long nextEpoch;
    private volatile Result lastResult;

    public ReconciliationManager(
            BrokerState brokerState,
            JsonTradingStateStore stateStore,
            TradingGate tradingGate,
            IntSupplier requestIdSupplier,
            Set<String> managedSymbols) {
        this(brokerState, stateStore, tradingGate, requestIdSupplier, managedSymbols, DEFAULT_TIMEOUT);
    }

    ReconciliationManager(
            BrokerState brokerState,
            JsonTradingStateStore stateStore,
            TradingGate tradingGate,
            IntSupplier requestIdSupplier,
            Set<String> managedSymbols,
            Duration timeout) {
        this.brokerState = brokerState;
        this.stateStore = stateStore;
        this.tradingGate = tradingGate;
        this.requestIdSupplier = requestIdSupplier;
        this.managedSymbols = Set.copyOf(managedSymbols);
        this.timeout = timeout;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "IBKR-Reconciliation-Timeout");
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized void attachClient(EClientSocket client) {
        this.client = client;
    }

    public synchronized Result getLastResult() {
        return lastResult;
    }

    public synchronized boolean isReconciling() {
        return activeEpoch != null;
    }

    public synchronized void begin(String reason) {
        if (client == null || !client.isConnected()) {
            failWithoutSnapshot("Cannot reconcile while the IBKR API socket is disconnected");
            return;
        }

        int executionRequestId = requestIdSupplier.getAsInt();
        CollectionEpoch epoch = new CollectionEpoch(++nextEpoch, executionRequestId);
        activeEpoch = epoch;
        tradingGate.transitionTo(EngineMode.RECONCILING, reason);

        try {
            client.reqPositions();
            client.reqAllOpenOrders();
            client.reqCompletedOrders(false);
            client.reqExecutions(executionRequestId, new ExecutionFilter());
        } catch (RuntimeException exception) {
            failEpoch(epoch.number, "IBKR rejected reconciliation requests: " + exception.getMessage());
            return;
        }

        scheduler.schedule(
                () -> failEpoch(epoch.number, "Timed out waiting for the complete IBKR broker snapshot"),
                timeout.toMillis(),
                TimeUnit.MILLISECONDS);
        logger.info("Started broker reconciliation epoch {}: {}", epoch.number, reason);
    }

    public synchronized void onDisconnected(String reason) {
        activeEpoch = null;
        tradingGate.transitionTo(EngineMode.DEGRADED, reason);
    }

    public synchronized void onPosition(String account, Contract contract, Decimal position, double averageCost) {
        targetState().recordPosition(account, contract, position, averageCost);
    }

    public synchronized void onLivePortfolioPosition(
            String account, Contract contract, Decimal position, double averageCost) {
        brokerState.recordPosition(account, contract, position, averageCost);
    }

    public synchronized void onPositionEnd() {
        if (activeEpoch != null) {
            activeEpoch.positionsComplete = true;
            completeIfReady(activeEpoch);
        }
    }

    public synchronized void onOpenOrder(int orderId, Contract contract, Order order, OrderState orderState) {
        targetState().recordOpenOrder(orderId, contract, order, orderState);
    }

    public synchronized void onOpenOrderEnd() {
        if (activeEpoch != null) {
            activeEpoch.openOrdersComplete = true;
            completeIfReady(activeEpoch);
        }
    }

    public synchronized void onCompletedOrder(Contract contract, Order order, OrderState orderState) {
        targetState().recordCompletedOrder(contract, order, orderState);
    }

    public synchronized void onCompletedOrdersEnd() {
        if (activeEpoch != null) {
            activeEpoch.completedOrdersComplete = true;
            completeIfReady(activeEpoch);
        }
    }

    public synchronized void onExecution(Contract contract, Execution execution) {
        targetState().recordExecution(contract, execution);
    }

    public synchronized void onExecutionEnd(int requestId) {
        if (activeEpoch != null && activeEpoch.executionRequestId == requestId) {
            activeEpoch.executionsComplete = true;
            completeIfReady(activeEpoch);
        }
    }

    public synchronized void onOrderStatus(
            int orderId,
            String status,
            Decimal filled,
            Decimal remaining,
            long permanentId,
            int parentOrderId,
            int clientId) {
        targetState().recordOrderStatus(
                orderId, status, filled, remaining, permanentId, parentOrderId, clientId);
    }

    private BrokerState targetState() {
        return activeEpoch == null ? brokerState : activeEpoch.collected;
    }

    private void completeIfReady(CollectionEpoch epoch) {
        if (activeEpoch != epoch || !epoch.complete()) {
            return;
        }

        BrokerState.Snapshot snapshot = epoch.collected.snapshot();
        List<String> differences = compare(snapshot, stateStore.snapshot());
        activeEpoch = null;
        brokerState.replaceWith(snapshot);

        if (differences.isEmpty()) {
            lastResult = new Result(true, "Broker state matched local trading-engine state", List.of(),
                    System.currentTimeMillis());
            tradingGate.transitionTo(EngineMode.READY, lastResult.reason());
            logger.info("Broker reconciliation epoch {} completed successfully", epoch.number);
        } else {
            lastResult = new Result(false, "Broker reconciliation requires manual review", differences,
                    System.currentTimeMillis());
            tradingGate.requireManualIntervention(String.join("; ", differences));
            logger.error("Broker reconciliation epoch {} found differences: {}", epoch.number, differences);
        }
    }

    List<String> compare(
            BrokerState.Snapshot brokerSnapshot,
            JsonTradingStateStore.StateFile localState) {
        List<String> differences = new ArrayList<>();
        Map<String, JsonTradingStateStore.TradeRecord> activeTradesById = new HashMap<>();
        Map<String, List<JsonTradingStateStore.TradeRecord>> activeTradesBySymbol = new HashMap<>();

        for (JsonTradingStateStore.TradeRecord trade : localState.trades()) {
            if (!trade.terminal()) {
                activeTradesById.put(trade.tradeId(), trade);
                activeTradesBySymbol.computeIfAbsent(trade.symbol(), ignored -> new ArrayList<>()).add(trade);
            }
        }

        Map<String, List<BrokerState.OrderRecord>> brokerOrdersByTrade = new HashMap<>();
        for (BrokerState.OrderRecord order : brokerSnapshot.orders()) {
            String tradeId = tradeIdFromOrderReference(order.orderReference());
            if (tradeId == null) {
                continue;
            }
            brokerOrdersByTrade.computeIfAbsent(tradeId, ignored -> new ArrayList<>()).add(order);
            if (!activeTradesById.containsKey(tradeId) && !order.completed()) {
                differences.add("Unknown working trading-engine order " + order.orderReference());
            }
        }

        Map<String, List<BrokerState.ExecutionRecord>> executionsByTrade = new HashMap<>();
        for (BrokerState.ExecutionRecord execution : brokerSnapshot.executions()) {
            String tradeId = tradeIdFromOrderReference(execution.orderReference());
            if (tradeId != null) {
                executionsByTrade.computeIfAbsent(tradeId, ignored -> new ArrayList<>()).add(execution);
            }
        }

        for (BrokerState.PositionRecord position : brokerSnapshot.positions()) {
            if (!managedSymbols.contains(position.symbol()) || isZero(position.quantity())) {
                continue;
            }
            boolean recognizedPosition = activeTradesBySymbol
                    .getOrDefault(position.symbol(), List.of())
                    .stream()
                    .anyMatch(trade -> trade.account().isBlank() || trade.account().equals(position.account()));
            if (!recognizedPosition) {
                differences.add("Unexpected managed position " + position.account() + '/' + position.symbol()
                        + " quantity " + position.quantity());
            }
        }

        for (JsonTradingStateStore.TradeRecord trade : activeTradesById.values()) {
            List<BrokerState.OrderRecord> orders = brokerOrdersByTrade.getOrDefault(trade.tradeId(), List.of());
            List<BrokerState.ExecutionRecord> executions = executionsByTrade.getOrDefault(trade.tradeId(), List.of());
            List<BrokerState.PositionRecord> positions = brokerSnapshot.positions().stream()
                    .filter(position -> position.symbol().equals(trade.symbol()))
                    .filter(position -> trade.account().isBlank() || trade.account().equals(position.account()))
                    .toList();

            if (orders.isEmpty() && executions.isEmpty() && positions.isEmpty()) {
                differences.add("Active local trade " + trade.tradeId() + " has no broker evidence");
                continue;
            }

            for (BrokerState.OrderRecord order : orders) {
                JsonTradingStateStore.LegRecord localLeg = trade.legs().stream()
                        .filter(leg -> leg.orderReference().equals(order.orderReference()))
                        .findFirst()
                        .orElse(null);
                if (localLeg == null) {
                    differences.add("Broker order is not present in local trade " + order.orderReference());
                } else {
                    compareLegIdentity(trade, localLeg, order, differences);
                }
            }

            boolean hasPosition = positions.stream().anyMatch(position -> !isZero(position.quantity()));
            for (JsonTradingStateStore.LegRecord localLeg : trade.legs()) {
                boolean hasOrder = orders.stream()
                        .anyMatch(order -> localLeg.orderReference().equals(order.orderReference()));
                boolean hasExecution = executions.stream()
                        .anyMatch(execution -> localLeg.orderReference().equals(execution.orderReference()));
                boolean parentCoveredByPosition = "PARENT".equals(localLeg.role()) && hasPosition;
                if (!hasOrder && !hasExecution && !parentCoveredByPosition
                        && !isTerminalOrderStatus(localLeg.status())) {
                    differences.add("Local working leg has no broker evidence " + localLeg.orderReference());
                }
            }

            if (hasPosition) {
                boolean hasWorkingExit = orders.stream()
                        .filter(order -> !order.completed())
                        .filter(order -> !isTerminalOrderStatus(order.status()))
                        .anyMatch(order -> !order.orderReference().endsWith("|P"));
                if (!hasWorkingExit) {
                    differences.add("Position " + trade.symbol() + " has no recognized working protective exit");
                }
            }
        }

        return differences;
    }

    private static void compareLegIdentity(
            JsonTradingStateStore.TradeRecord trade,
            JsonTradingStateStore.LegRecord localLeg,
            BrokerState.OrderRecord brokerOrder,
            List<String> differences) {
        String orderReference = brokerOrder.orderReference();
        if (localLeg.apiOrderId() != brokerOrder.apiOrderId()) {
            differences.add("Order ID mismatch for " + orderReference);
        }
        if (localLeg.permanentId() > 0 && brokerOrder.permanentId() > 0
                && localLeg.permanentId() != brokerOrder.permanentId()) {
            differences.add("Permanent order ID mismatch for " + orderReference);
        }
        if (!trade.account().isBlank() && !trade.account().equals(brokerOrder.account())) {
            differences.add("Account mismatch for " + orderReference);
        }
        if (!trade.symbol().equals(brokerOrder.symbol())) {
            differences.add("Symbol mismatch for " + orderReference);
        }
        if (trade.contractId() > 0 && brokerOrder.contractId() > 0
                && trade.contractId() != brokerOrder.contractId()) {
            differences.add("Contract mismatch for " + orderReference);
        }

        String expectedAction = "PARENT".equals(localLeg.role())
                ? trade.action()
                : oppositeAction(trade.action());
        if (!expectedAction.isBlank() && !expectedAction.equalsIgnoreCase(brokerOrder.action())) {
            differences.add("Action mismatch for " + orderReference);
        }

        try {
            BigDecimal localQuantity = new BigDecimal(localLeg.filledQuantity())
                    .add(new BigDecimal(localLeg.remainingQuantity()));
            BigDecimal brokerQuantity = new BigDecimal(brokerOrder.totalQuantity());
            if (localQuantity.compareTo(brokerQuantity) != 0) {
                differences.add("Quantity mismatch for " + orderReference);
            }
        } catch (RuntimeException exception) {
            differences.add("Unreadable quantity for " + orderReference);
        }
    }

    private static String oppositeAction(String action) {
        if ("BUY".equalsIgnoreCase(action)) {
            return "SELL";
        }
        if ("SELL".equalsIgnoreCase(action)) {
            return "BUY";
        }
        return "";
    }

    private synchronized void failEpoch(long epochNumber, String reason) {
        if (activeEpoch == null || activeEpoch.number != epochNumber) {
            return;
        }
        activeEpoch = null;
        failWithoutSnapshot(reason);
    }

    private void failWithoutSnapshot(String reason) {
        lastResult = new Result(false, reason, List.of(reason), System.currentTimeMillis());
        tradingGate.requireManualIntervention(reason);
        logger.error(reason);
    }

    private static String tradeIdFromOrderReference(String orderReference) {
        if (orderReference == null
                || (!orderReference.startsWith("TE|")
                        && !orderReference.startsWith("SC|"))) {
            return null;
        }
        String[] parts = orderReference.split("\\|", 3);
        return parts.length == 3 && !parts[1].isBlank() ? parts[1] : null;
    }

    private static boolean isTerminalOrderStatus(String status) {
        return "Filled".equalsIgnoreCase(status)
                || "Cancelled".equalsIgnoreCase(status)
                || "ApiCancelled".equalsIgnoreCase(status)
                || "Inactive".equalsIgnoreCase(status);
    }

    private static boolean isZero(String quantity) {
        try {
            return new BigDecimal(quantity).compareTo(BigDecimal.ZERO) == 0;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
