package mwd.trading.reconciliation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.ib.client.Contract;
import com.ib.client.Decimal;
import com.ib.client.Execution;
import com.ib.client.Order;
import com.ib.client.OrderState;

/**
 * Broker-owned position, order, and execution state. The records intentionally
 * contain only ordinary Java values so snapshots can be compared, tested, and
 * persisted without serializing mutable IBKR API objects.
 */
public final class BrokerState {
    public record PositionRecord(
            String account,
            int contractId,
            String symbol,
            String securityType,
            String quantity,
            double averageCost) {
    }

    public record OrderRecord(
            int apiOrderId,
            long permanentId,
            int clientId,
            int parentOrderId,
            String account,
            int contractId,
            String symbol,
            String orderReference,
            String action,
            String orderType,
            String totalQuantity,
            double limitPrice,
            double auxiliaryPrice,
            String status,
            String filledQuantity,
            String remainingQuantity,
            boolean completed,
            long updatedAtEpochMillis) {
    }

    public record ExecutionRecord(
            String executionId,
            int apiOrderId,
            long permanentId,
            int clientId,
            String account,
            int contractId,
            String symbol,
            String orderReference,
            String side,
            String shares,
            String cumulativeQuantity,
            double price,
            double averagePrice,
            String brokerTime,
            long receivedAtEpochMillis) {
    }

    public record Snapshot(
            long asOfEpochMillis,
            List<PositionRecord> positions,
            List<OrderRecord> orders,
            List<ExecutionRecord> executions) {
        public Snapshot {
            positions = List.copyOf(positions);
            orders = List.copyOf(orders);
            executions = List.copyOf(executions);
        }
    }

    private final Map<String, PositionRecord> positions = new HashMap<>();
    private final Map<Integer, OrderRecord> orders = new HashMap<>();
    private final Map<String, ExecutionRecord> executions = new HashMap<>();

    public synchronized void clear() {
        positions.clear();
        orders.clear();
        executions.clear();
    }

    public synchronized void recordPosition(String account, Contract contract, Decimal quantity, double averageCost) {
        String key = positionKey(account, contract);
        if (quantity == null || quantity.isZero()) {
            positions.remove(key);
            return;
        }

        positions.put(key, new PositionRecord(
                safe(account),
                contract == null ? 0 : contract.conid(),
                contract == null ? "" : safe(contract.symbol()),
                contract == null || contract.secType() == null ? "" : contract.secType().toString(),
                decimalText(quantity),
                averageCost));
    }

    public synchronized void recordOpenOrder(int apiOrderId, Contract contract, Order order, OrderState orderState) {
        recordOrder(apiOrderId, contract, order, orderState, false);
    }

    public synchronized void recordCompletedOrder(Contract contract, Order order, OrderState orderState) {
        recordOrder(order.orderId(), contract, order, orderState, true);
    }

    private void recordOrder(int apiOrderId, Contract contract, Order order, OrderState orderState, boolean completed) {
        OrderRecord existing = orders.get(apiOrderId);
        String filled = existing == null ? "0" : existing.filledQuantity();
        String remaining = existing == null ? decimalText(order.totalQuantity()) : existing.remainingQuantity();
        String status = orderState == null ? "" : safe(orderState.getStatus());

        orders.put(apiOrderId, new OrderRecord(
                apiOrderId,
                order.permId(),
                order.clientId(),
                order.parentId(),
                safe(order.account()),
                contract == null ? 0 : contract.conid(),
                contract == null ? "" : safe(contract.symbol()),
                safe(order.orderRef()),
                order.action() == null ? "" : order.action().toString(),
                order.orderType() == null ? "" : order.orderType().toString(),
                decimalText(order.totalQuantity()),
                order.lmtPrice(),
                order.auxPrice(),
                status,
                filled,
                remaining,
                completed,
                System.currentTimeMillis()));
    }

    public synchronized void recordOrderStatus(
            int apiOrderId,
            String status,
            Decimal filled,
            Decimal remaining,
            long permanentId,
            int parentOrderId,
            int clientId) {
        OrderRecord existing = orders.get(apiOrderId);
        if (existing == null) {
            orders.put(apiOrderId, new OrderRecord(
                    apiOrderId, permanentId, clientId, parentOrderId,
                    "", 0, "", "", "", "", "0", 0.0, 0.0,
                    safe(status), decimalText(filled), decimalText(remaining), false,
                    System.currentTimeMillis()));
            return;
        }

        orders.put(apiOrderId, new OrderRecord(
                existing.apiOrderId(),
                permanentId > 0 ? permanentId : existing.permanentId(),
                clientId,
                parentOrderId,
                existing.account(),
                existing.contractId(),
                existing.symbol(),
                existing.orderReference(),
                existing.action(),
                existing.orderType(),
                existing.totalQuantity(),
                existing.limitPrice(),
                existing.auxiliaryPrice(),
                safe(status),
                decimalText(filled),
                decimalText(remaining),
                existing.completed(),
                System.currentTimeMillis()));
    }

    public synchronized void recordExecution(Contract contract, Execution execution) {
        String executionId = safe(execution.execId());
        if (executionId.isBlank()) {
            executionId = execution.orderId() + "@" + execution.time() + "@" + execution.cumQty();
        }
        executions.put(executionId, new ExecutionRecord(
                executionId,
                execution.orderId(),
                execution.permId(),
                execution.clientId(),
                safe(execution.acctNumber()),
                contract == null ? 0 : contract.conid(),
                contract == null ? "" : safe(contract.symbol()),
                safe(execution.orderRef()),
                safe(execution.side()),
                decimalText(execution.shares()),
                decimalText(execution.cumQty()),
                execution.price(),
                execution.avgPrice(),
                safe(execution.time()),
                System.currentTimeMillis()));
    }

    public synchronized Snapshot snapshot() {
        List<PositionRecord> positionList = new ArrayList<>(positions.values());
        positionList.sort(Comparator.comparing(PositionRecord::account)
                .thenComparing(PositionRecord::symbol)
                .thenComparingInt(PositionRecord::contractId));

        List<OrderRecord> orderList = new ArrayList<>(orders.values());
        orderList.sort(Comparator.comparingInt(OrderRecord::apiOrderId));

        List<ExecutionRecord> executionList = new ArrayList<>(executions.values());
        executionList.sort(Comparator.comparing(ExecutionRecord::executionId));

        return new Snapshot(System.currentTimeMillis(), positionList, orderList, executionList);
    }

    public synchronized void replaceWith(Snapshot snapshot) {
        clear();
        for (PositionRecord position : snapshot.positions()) {
            positions.put(position.account() + '|' + position.contractId() + '|' + position.symbol(), position);
        }
        for (OrderRecord order : snapshot.orders()) {
            orders.put(order.apiOrderId(), order);
        }
        for (ExecutionRecord execution : snapshot.executions()) {
            executions.put(execution.executionId(), execution);
        }
    }

    private static String positionKey(String account, Contract contract) {
        int contractId = contract == null ? 0 : contract.conid();
        String symbol = contract == null ? "" : safe(contract.symbol());
        return safe(account) + '|' + contractId + '|' + symbol;
    }

    private static String decimalText(Decimal value) {
        return value == null ? "0" : value.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
