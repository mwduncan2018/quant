package mwd.trading.execution;

import java.io.IOException;
import java.util.Objects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.ib.client.CommissionAndFeesReport;
import com.ib.client.Contract;
import com.ib.client.Decimal;
import com.ib.client.Execution;
import com.ib.client.Order;
import com.ib.client.OrderState;
import com.ib.client.Types.Action;

import mwd.trading.state.Blackboard;
import mwd.trading.execution.BracketOrder;
import mwd.trading.domain.Stock;
import mwd.trading.lifecycle.TradingGate;
import mwd.trading.persistence.JsonTradingStateStore;
import mwd.trading.reconciliation.ReconciliationManager;

public class OrderLifecycleHandler {
    private static final Logger logger = LogManager.getLogger(OrderLifecycleHandler.class);
    private final Blackboard blackboard;
    private final ReconciliationManager reconciliationManager;
    private final JsonTradingStateStore stateStore;
    private final TradingGate tradingGate;

    public OrderLifecycleHandler(
            Blackboard blackboard,
            ReconciliationManager reconciliationManager,
            JsonTradingStateStore stateStore,
            TradingGate tradingGate) {
        this.blackboard = Objects.requireNonNull(blackboard, "blackboard");
        this.reconciliationManager = Objects.requireNonNull(
                reconciliationManager, "reconciliationManager");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.tradingGate = Objects.requireNonNull(tradingGate, "tradingGate");
    }

    public void onOpenOrder(int orderIdentifier, Contract contract, Order order, OrderState orderState) {

        reconciliationManager.onOpenOrder(orderIdentifier, contract, order, orderState);

        BracketOrder bracketOrder = resolveBracket(orderIdentifier, order.permId(), order.orderRef());
        if (bracketOrder == null) {
            return;
        }

        blackboard.getOrderRegistry().recordBrokerIdentity(orderIdentifier, order.permId(), order.orderRef());
        bracketOrder.applyBrokerUpdate(
                orderIdentifier,
                order.permId(),
                orderState == null ? "" : orderState.getStatus(),
                null,
                order.totalQuantity());

        if (bracketOrder.isParentOrderId(orderIdentifier)) {
            if (Math.abs(order.lmtPrice() - bracketOrder.getEntryPrice()) > 0.0001) {
                halt("[" + bracketOrder.getTicker() + "] Parent price mismatch: local="
                        + bracketOrder.getEntryPrice() + ", broker=" + order.lmtPrice());
            }
            if (!order.totalQuantity().equals(bracketOrder.getTotalQuantity())) {
                halt("[" + bracketOrder.getTicker() + "] Parent quantity mismatch: local="
                        + bracketOrder.getTotalQuantity() + ", broker=" + order.totalQuantity());
            }
            bracketOrder.setStatus(BracketOrder.Status.WORKING_PARENT);
        }

        validateExitSlice(orderIdentifier, order, bracketOrder);

        String warningText = orderState == null ? null : orderState.warningText();
        if (warningText != null && !warningText.isEmpty()) {
            logger.warn("[{}] IBKR warning for {}: {}", bracketOrder.getTicker(), orderIdentifier, warningText);
        }

        persist(bracketOrder);
        logger.info("[{}] Open order update: {}, ID={}, Status={}",
                bracketOrder.getTicker(), bracketOrder.getLegName(orderIdentifier), orderIdentifier,
                orderState == null ? "" : orderState.getStatus());
    }

    public void onOpenOrderEnd() {
        blackboard.setOpenOrderEnd(true);
        reconciliationManager.onOpenOrderEnd();
    }

    public void onOrderStatus(
            int orderIdentifier,
            String status,
            Decimal filledQuantity,
            Decimal remainingQuantity,
            double averageFillPrice,
            long permanentIdentifier,
            int parentIdentifier,
            double lastFillPrice,
            int clientIdentifier,
            String whyHeld,
            double marketCapPrice) {
        reconciliationManager.onOrderStatus(
                orderIdentifier, status, filledQuantity, remainingQuantity,
                permanentIdentifier, parentIdentifier, clientIdentifier);

        BracketOrder bracketOrder = resolveBracket(orderIdentifier, permanentIdentifier, null);
        if (bracketOrder == null) {
            return;
        }

        blackboard.getOrderRegistry().recordBrokerIdentity(orderIdentifier, permanentIdentifier, null);
        bracketOrder.applyBrokerUpdate(
                orderIdentifier, permanentIdentifier, status, filledQuantity, remainingQuantity);

        if (bracketOrder.isParentOrderId(orderIdentifier)) {
            bracketOrder.setFilledQuantity(filledQuantity);
            bracketOrder.setRemainingQuantity(remainingQuantity);

            if ("Filled".equalsIgnoreCase(status)) {
                bracketOrder.setStatus(BracketOrder.Status.POSITION_OPEN);
                Stock stock = blackboard.getStock(bracketOrder.getTicker());
                markPositionOpen(bracketOrder);
            } else if (filledQuantity != null
                    && !filledQuantity.isZero()
                    && !isCancelledOrInactive(status)) {
                bracketOrder.setStatus(BracketOrder.Status.PARTIAL_PARENT);
                Stock stock = blackboard.getStock(bracketOrder.getTicker());
                markPositionOpen(bracketOrder);
                halt("[" + bracketOrder.getTicker()
                        + "] Parent order is partially filled; verify the live position and exits");
            } else if (isWorkingStatus(status)) {
                bracketOrder.setStatus(BracketOrder.Status.WORKING_PARENT);
            }
        }

        if (bracketOrder.isExitOrderId(orderIdentifier) && "Filled".equalsIgnoreCase(status)) {
            BracketOrder.ExitSlice filledSlice = bracketOrder.getSliceByOrderId(orderIdentifier);
            if (filledSlice != null) {
                filledSlice.setFilled(true);
                logger.info("[{}] Exit slice completed by {}",
                        bracketOrder.getTicker(), bracketOrder.getLegName(orderIdentifier));
            }

            if (bracketOrder.getSlices().stream().allMatch(BracketOrder.ExitSlice::isFilled)) {
                bracketOrder.setStatus(BracketOrder.Status.FILLED);
                completeConfirmedFlat(bracketOrder);
            }
        }

        if (isCancelledOrInactive(status) && bracketOrder.isParentOrderId(orderIdentifier)) {
            if (filledQuantity == null || filledQuantity.isZero()) {
                bracketOrder.setStatus(BracketOrder.Status.CANCELLED);
                completeConfirmedFlat(bracketOrder);
            } else {
                bracketOrder.setStatus(BracketOrder.Status.PARTIAL_PARENT);
                markPositionOpen(bracketOrder);
                halt("[" + bracketOrder.getTicker()
                        + "] Parent order stopped after a partial fill; verify the live position and exits");
            }
        }

        persist(bracketOrder);
        logger.info("[{}] Order status: {} - {} | Filled: {} | Remaining: {}",
                bracketOrder.getTicker(), bracketOrder.getLegName(orderIdentifier),
                status, filledQuantity, remainingQuantity);
    }

    public void onError(int identifier, long time, int errorCode, String errorMessage, String advancedOrderRejectJson) {
        BracketOrder bracketOrder = blackboard.getOrderRegistry().getBracketFor(identifier);
        if (bracketOrder == null) {
            return;
        }

        String ticker = bracketOrder.getTicker();
        switch (errorCode) {
            case 102, 110, 136, 161, 201, 321, 10147, 10148, 10197 -> {
                if (bracketOrder.isParentOrderId(identifier)) {
                    if (bracketOrder.getFilledQuantity() == null || bracketOrder.getFilledQuantity().isZero()) {
                        bracketOrder.setStatus(BracketOrder.Status.REJECTED);
                        completeConfirmedFlat(bracketOrder);
                    } else {
                        bracketOrder.setStatus(BracketOrder.Status.PARTIAL_PARENT);
                        markPositionOpen(bracketOrder);
                    }
                } else {
                    bracketOrder.setStatus(BracketOrder.Status.POSITION_OPEN);
                    markPositionOpen(bracketOrder);
                }
                halt("[" + ticker + "] Critical order error " + errorCode + ": " + errorMessage);
            }
            case 202 -> {
                if (bracketOrder.isParentOrderId(identifier)
                        && (bracketOrder.getFilledQuantity() == null || bracketOrder.getFilledQuantity().isZero())) {
                    bracketOrder.setStatus(BracketOrder.Status.CANCELLED);
                    completeConfirmedFlat(bracketOrder);
                } else if (bracketOrder.isParentOrderId(identifier)) {
                    bracketOrder.setStatus(BracketOrder.Status.PARTIAL_PARENT);
                    markPositionOpen(bracketOrder);
                    halt("[" + ticker
                            + "] Parent cancellation followed a partial fill; verify the live position and exits");
                }
                logger.info("[{}] Order cancelled: ID={}", ticker, identifier);
            }
            default -> logger.warn("[{}] IBKR order advisory: code={}, message={}",
                    ticker, errorCode, errorMessage);
        }
        persist(bracketOrder);
    }

    public void onExecDetails(int requestIdentifier, Contract contract, Execution execution) {
        reconciliationManager.onExecution(contract, execution);
        BracketOrder bracketOrder = resolveBracket(
                execution.orderId(), execution.permId(), execution.orderRef());
        if (bracketOrder != null) {
            blackboard.getOrderRegistry().recordBrokerIdentity(
                    execution.orderId(), execution.permId(), execution.orderRef());
            bracketOrder.applyBrokerUpdate(
                    execution.orderId(), execution.permId(), "EXECUTION",
                    execution.cumQty(), null);
            Decimal cumulativeQuantity = execution.cumQty();
            if (bracketOrder.isParentOrderId(execution.orderId())
                    && cumulativeQuantity != null
                    && !cumulativeQuantity.isZero()) {
                bracketOrder.setFilledQuantity(cumulativeQuantity);
                if (cumulativeQuantity.compareTo(bracketOrder.getTotalQuantity()) >= 0) {
                    bracketOrder.setStatus(BracketOrder.Status.POSITION_OPEN);
                } else {
                    bracketOrder.setStatus(BracketOrder.Status.PARTIAL_PARENT);
                    halt("[" + bracketOrder.getTicker()
                            + "] Execution reported a partial parent fill; verify protective exits");
                }
                markPositionOpen(bracketOrder);
            }
            persist(bracketOrder);
        }
    }

    public void onExecDetailsEnd(int requestIdentifier) {
        reconciliationManager.onExecutionEnd(requestIdentifier);
    }

    public void onCommissionAndFeesReport(CommissionAndFeesReport report) {
        // Commissions are not needed to determine position/order ownership.
    }

    public void onCompletedOrder(Contract contract, Order order, OrderState orderState) {
        reconciliationManager.onCompletedOrder(contract, order, orderState);
        BracketOrder bracketOrder = resolveBracket(order.orderId(), order.permId(), order.orderRef());
        if (bracketOrder != null) {
            blackboard.getOrderRegistry().recordBrokerIdentity(order.orderId(), order.permId(), order.orderRef());
            String completedStatus = orderState == null ? "" : orderState.getStatus();
            bracketOrder.applyBrokerUpdate(
                    order.orderId(), order.permId(), completedStatus, null, Decimal.ZERO);
            if (bracketOrder.isParentOrderId(order.orderId())) {
                if ("Filled".equalsIgnoreCase(completedStatus)) {
                    bracketOrder.setStatus(BracketOrder.Status.POSITION_OPEN);
                    markPositionOpen(bracketOrder);
                } else if (isCancelledOrInactive(completedStatus)
                        || "Rejected".equalsIgnoreCase(completedStatus)) {
                    if (bracketOrder.getFilledQuantity() == null
                            || bracketOrder.getFilledQuantity().isZero()) {
                        bracketOrder.setStatus(
                                "Rejected".equalsIgnoreCase(completedStatus)
                                        ? BracketOrder.Status.REJECTED
                                        : BracketOrder.Status.CANCELLED);
                        completeConfirmedFlat(bracketOrder);
                    } else {
                        bracketOrder.setStatus(BracketOrder.Status.PARTIAL_PARENT);
                        markPositionOpen(bracketOrder);
                        halt("[" + bracketOrder.getTicker()
                                + "] Completed parent retained a partial fill; verify the position");
                    }
                }
            } else if (bracketOrder.isExitOrderId(order.orderId())
                    && "Filled".equalsIgnoreCase(completedStatus)) {
                BracketOrder.ExitSlice exitSlice =
                        bracketOrder.getSliceByOrderId(order.orderId());
                exitSlice.setFilled(true);
                if (bracketOrder.getSlices().stream()
                        .allMatch(BracketOrder.ExitSlice::isFilled)) {
                    bracketOrder.setStatus(BracketOrder.Status.FILLED);
                    completeConfirmedFlat(bracketOrder);
                }
            }
            persist(bracketOrder);
        }
    }

    public void onCompletedOrdersEnd() {
        reconciliationManager.onCompletedOrdersEnd();
    }

    public void onOrderBound(long permanentIdentifier, int apiClientIdentifier, int apiOrderIdentifier) {
        blackboard.getOrderRegistry().recordBrokerIdentity(apiOrderIdentifier, permanentIdentifier, null);
        BracketOrder bracketOrder = resolveBracket(apiOrderIdentifier, permanentIdentifier, null);
        if (bracketOrder != null) {
            bracketOrder.applyBrokerUpdate(apiOrderIdentifier, permanentIdentifier, "BOUND", null, null);
            persist(bracketOrder);
        }
    }

    private void validateExitSlice(int orderIdentifier, Order order, BracketOrder bracketOrder) {
        BracketOrder.ExitSlice exitSlice = bracketOrder.getSliceByOrderId(orderIdentifier);
        if (exitSlice == null) {
            return;
        }
        if (orderIdentifier == exitSlice.getStopLossOrderId()
                && Math.abs(order.auxPrice() - exitSlice.getStopLossPrice()) > 0.0001) {
            halt("[" + bracketOrder.getTicker() + "] Stop price mismatch for "
                    + bracketOrder.getLegName(orderIdentifier));
        }
        if (orderIdentifier == exitSlice.getTakeProfitOrderId()
                && Math.abs(order.lmtPrice() - exitSlice.getTakeProfitPrice()) > 0.0001) {
            halt("[" + bracketOrder.getTicker() + "] Target price mismatch for "
                    + bracketOrder.getLegName(orderIdentifier));
        }
        if (orderIdentifier == exitSlice.getTimeExitOrderId()) {
            String brokerTime = order.goodAfterTime() == null ? "" : order.goodAfterTime();
            if (!brokerTime.equals(bracketOrder.getGoodAfterTimeString(exitSlice.getTimeExit()))) {
                halt("[" + bracketOrder.getTicker() + "] Time-exit mismatch for "
                        + bracketOrder.getLegName(orderIdentifier));
            }
        }
        if (!order.totalQuantity().equals(exitSlice.getQuantity())) {
            halt("[" + bracketOrder.getTicker() + "] Exit quantity mismatch for "
                    + bracketOrder.getLegName(orderIdentifier));
        }
    }

    private BracketOrder resolveBracket(int apiOrderId, long permanentId, String orderReference) {
        BracketOrder bracketOrder = blackboard.getOrderRegistry().getBracketFor(apiOrderId);
        if (bracketOrder == null && permanentId > 0) {
            bracketOrder = blackboard.getOrderRegistry().getBracketForPermanentId(permanentId);
        }
        if (bracketOrder == null && orderReference != null) {
            bracketOrder = blackboard.getOrderRegistry().getBracketForOrderReference(orderReference);
        }
        return bracketOrder;
    }

    /**
     * Releases the engine-wide entry lock now that the parent is filled. It writes
     * no position state: OPEN is derived from the {@code Status} the caller set on
     * the bracket, so there is nothing here that could disagree with it.
     */
    private void markPositionOpen(BracketOrder bracketOrder) {
        blackboard.releaseGlobalPending(
                bracketOrder.getStrategyName(), bracketOrder.getTicker());
    }

    private void completeConfirmedFlat(BracketOrder bracketOrder) {
        Stock stock = blackboard.getStock(bracketOrder.getTicker());
        if (stock.getActiveBracket() == bracketOrder) {
            stock.setActiveBracket(null);
        }
        blackboard.releaseGlobalPending(
                bracketOrder.getStrategyName(), bracketOrder.getTicker());
        blackboard.releasePosition(
                bracketOrder.getTicker(), bracketOrder.getStrategyName());
    }

    private void persist(BracketOrder bracketOrder) {
        try {
            stateStore.recordBrokerUpdate(bracketOrder, bracketOrder.getAction());
        } catch (IOException exception) {
            halt("Unable to persist trading state: " + exception.getMessage());
            logger.error("Unable to persist trading state", exception);
        }
    }

    private void halt(String reason) {
        blackboard.setSystemHalted(true);
        tradingGate.requireManualIntervention(reason);
        logger.error(reason);
    }

    private static boolean isCancelledOrInactive(String status) {
        return "Cancelled".equalsIgnoreCase(status)
                || "ApiCancelled".equalsIgnoreCase(status)
                || "Inactive".equalsIgnoreCase(status);
    }

    private static boolean isWorkingStatus(String status) {
        return "PendingSubmit".equalsIgnoreCase(status)
                || "PreSubmitted".equalsIgnoreCase(status)
                || "Submitted".equalsIgnoreCase(status)
                || "PendingCancel".equalsIgnoreCase(status);
    }
}
