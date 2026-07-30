package mwd.trading.execution;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import com.ib.client.Decimal;

public class BracketOrder {
    public enum Status {
        INITIALIZED,     // Local memory only, not yet sent to broker
        WORKING_PARENT,  // Parent entry order is live at the exchange
        PARTIAL_PARENT,  // Parent entry order is partially filled
        POSITION_OPEN,   // Parent is 100% filled, exit slices are now active
        FILLED,          // Every exit slice in this bracket has been completed
        CANCELLED,       // The order bundle was cancelled
        REJECTED         // The broker rejected the entry order
    }

    public static final class OrderLegState {
        private final int apiOrderId;
        private final String role;
        private final String orderReference;
        private volatile long permanentId;
        private volatile String status = "PENDING_SUBMIT";
        private volatile Decimal filledQuantity = Decimal.ZERO;
        private volatile Decimal remainingQuantity;
        private volatile boolean acknowledged;

        private OrderLegState(int apiOrderId, String role, String orderReference, Decimal quantity) {
            this.apiOrderId = apiOrderId;
            this.role = role;
            this.orderReference = orderReference;
            this.remainingQuantity = quantity;
        }

        public int getApiOrderId() { return apiOrderId; }
        public String getRole() { return role; }
        public String getOrderReference() { return orderReference; }
        public long getPermanentId() { return permanentId; }
        public String getStatus() { return status; }
        public Decimal getFilledQuantity() { return filledQuantity; }
        public Decimal getRemainingQuantity() { return remainingQuantity; }
        public boolean isAcknowledged() { return acknowledged; }

        private void applyBrokerUpdate(long permanentId, String status, Decimal filled, Decimal remaining) {
            if (permanentId > 0) {
                this.permanentId = permanentId;
            }
            if (status != null && !status.isBlank()) {
                this.status = status;
            }
            if (filled != null) {
                this.filledQuantity = filled;
            }
            if (remaining != null) {
                this.remainingQuantity = remaining;
            }
            this.acknowledged = true;
        }
    }

    /**
     * Inner class representing a specific exit "intention" or "slice" of the trade.
     * This allows Strategy C to have two slices (Fixed and VWAP) and Strategy Example to have three.
     */
    public static class ExitSlice {
        private final int takeProfitOrderId;
        private final int stopLossOrderId;
        private final int timeExitOrderId;
        private final Decimal quantity;
        private final String oneCancelsAllGroup;
        
        // Written by the strategy thread through BracketOrderExecutor and by the
        // IBKR reader thread through OrderLifecycleHandler, and read by both plus
        // the Swing EDT. Every one of them needs a visibility guarantee.
        private volatile double takeProfitPrice;
        private volatile double stopLossPrice;
        private volatile long timeExit;
        private volatile boolean isFilled = false;

        public ExitSlice(int takeProfitOrderId, int stopLossOrderId, int timeExitOrderId, 
                         Decimal quantity, String oneCancelsAllGroup) {
            this.takeProfitOrderId = takeProfitOrderId;
            this.stopLossOrderId = stopLossOrderId;
            this.timeExitOrderId = timeExitOrderId;
            this.quantity = quantity;
            this.oneCancelsAllGroup = oneCancelsAllGroup;
        }

        private long lastModificationTime = 0;
        public long getLastModificationTime() { return lastModificationTime; }
        public void setLastModificationTime(long time) { this.lastModificationTime = time; }

        // Slice Identity Getters
        public int getTakeProfitOrderId() { return takeProfitOrderId; }
        public int getStopLossOrderId() { return stopLossOrderId; }
        public int getTimeExitOrderId() { return timeExitOrderId; }
        public Decimal getQuantity() { return quantity; }
        public String getOneCancelsAllGroup() { return oneCancelsAllGroup; }
        public boolean isFilled() { return isFilled; }
        
        // Slice Dynamic Parameter Getters and Setters
        public double getTakeProfitPrice() { return takeProfitPrice; }
        public void setTakeProfitPrice(double takeProfitPrice) { this.takeProfitPrice = takeProfitPrice; }
        public double getStopLossPrice() { return stopLossPrice; }
        public void setStopLossPrice(double stopLossPrice) { this.stopLossPrice = stopLossPrice; }
        public long getTimeExit() { return timeExit; }
        public void setTimeExit(long timeExit) { this.timeExit = timeExit; }
        public void setFilled(boolean isFilled) { this.isFilled = isFilled; }
    }

    // Identity and Collection
    private final String tradeId;
    private final String strategyName;
    private final String account;
    private final int contractId;
    private final String action;
    private final String ticker;
    private final int parentOrderId;
    private final List<ExitSlice> slices = new ArrayList<>();
    private final Map<Integer, OrderLegState> legStates = new LinkedHashMap<>();
    
    // Modification tracking for the IBKR 20:1 Order-to-Fill Ratio
    private final AtomicInteger updateRequestCount = new AtomicInteger(0);

    // Consolidated Entry State.
    //
    // These are written on the IBKR reader thread and read by three strategy
    // threads polling every 16 ms. Without volatile there is no happens-before
    // edge between the write and the read, so a strategy may never observe the
    // new value: the JIT is free to hoist a non-volatile field read out of a
    // poll loop. status is the field that matters most, because it drives the
    // pending-entry state machine including the WORKING_PARENT release of the
    // engine-wide entry lock.
    private volatile double entryPrice;
    private final Decimal totalQuantity;
    private volatile Status status;
    private volatile Decimal filledQuantity = Decimal.get(0);
    private volatile Decimal remainingQuantity;

    public BracketOrder(String ticker, int parentOrderId, Decimal totalQuantity) {
        this("LEGACY-" + ticker + '-' + parentOrderId, "UNKNOWN", "", 0, "",
                ticker, parentOrderId, totalQuantity);
    }

    public BracketOrder(String tradeId, String strategyName, String account, int contractId, String action,
            String ticker, int parentOrderId, Decimal totalQuantity) {
        this.tradeId = tradeId;
        this.strategyName = strategyName;
        this.account = account == null ? "" : account;
        this.contractId = contractId;
        this.action = action == null ? "" : action;
        this.ticker = ticker;
        this.parentOrderId = parentOrderId;
        this.totalQuantity = totalQuantity;
        this.remainingQuantity = totalQuantity;
        this.status = Status.INITIALIZED;
    }

    /**
     * Attaches a new exit intention (slice) to this bracket.
     */
    public void addSlice(ExitSlice slice) {
        this.slices.add(slice);
    }

    public synchronized void registerOrderLeg(int apiOrderId, String role, String orderReference, Decimal quantity) {
        legStates.put(apiOrderId, new OrderLegState(apiOrderId, role, orderReference, quantity));
    }

    public synchronized void applyBrokerUpdate(
            int apiOrderId, long permanentId, String brokerStatus, Decimal filled, Decimal remaining) {
        OrderLegState legState = legStates.get(apiOrderId);
        if (legState != null) {
            legState.applyBrokerUpdate(permanentId, brokerStatus, filled, remaining);
        }
    }

    public synchronized Collection<OrderLegState> getLegStates() {
        return List.copyOf(legStates.values());
    }

    public List<ExitSlice> getSlices() {
        return slices;
    }

    // --- Identification and Lookup Logic ---

    public boolean isParentOrderId(int orderId) {
        return orderId == parentOrderId;
    }

    /**
     * Checks if a given order ID belongs to any exit leg within any slice.
     */
    public boolean isExitOrderId(int orderId) {
        return getSliceByOrderId(orderId) != null;
    }

    /**
     * Returns the specific ExitSlice associated with an order ID.
     */
    public ExitSlice getSliceByOrderId(int orderId) {
        for (ExitSlice slice : slices) {
            if (orderId == slice.getTakeProfitOrderId() || 
                orderId == slice.getStopLossOrderId() || 
                orderId == slice.getTimeExitOrderId()) {
                return slice;
            }
        }
        return null;
    }

    /**
     * Returns a human-readable name for logging purposes.
     */
    public String getLegName(int orderId) {
        OrderLegState legState = legStates.get(orderId);
        if (legState != null) {
            return legState.getRole();
        }
        if (orderId == this.parentOrderId) {
            return "PARENT";
        }
        
        for (int index = 0; index < slices.size(); index++) {
            ExitSlice slice = slices.get(index);
            String prefix = "SLICE_" + (index + 1) + "_";
            if (orderId == slice.getTakeProfitOrderId()) return prefix + "TAKE_PROFIT";
            if (orderId == slice.getStopLossOrderId()) return prefix + "STOP_LOSS";
            if (orderId == slice.getTimeExitOrderId()) return prefix + "TIME_EXIT";
        }
        return "UNKNOWN";
    }

    // --- Core getters and setters ---

    public String getTradeId() { return tradeId; }
    public String getStrategyName() { return strategyName; }
    public String getAccount() { return account; }
    public int getContractId() { return contractId; }
    public String getAction() { return action; }
    public String getTicker() { return ticker; }
    public int getParentOrderId() { return parentOrderId; }   
    public double getEntryPrice() { return entryPrice; }
    public void setEntryPrice(double entryPrice) { this.entryPrice = entryPrice; }
    public Decimal getTotalQuantity() { return totalQuantity; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Decimal getFilledQuantity() { return filledQuantity; }
    public void setFilledQuantity(Decimal filledQuantity) { this.filledQuantity = filledQuantity; }
    public Decimal getRemainingQuantity() { return remainingQuantity; }
    public void setRemainingQuantity(Decimal remainingQuantity) { this.remainingQuantity = remainingQuantity; }

    public int incrementUpdateCount() {
        return updateRequestCount.incrementAndGet();
    }

    public int getUpdateCount() {
        return updateRequestCount.get();
    }
    
    /**
     * Formats the time exit for the IBKR Good-After-Time requirement.
     */
    public String getGoodAfterTimeString(long timeExitValue) {
        if (timeExitValue <= 0) {
            return "";
        }
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyyMMdd HH:mm:ss");
        return simpleDateFormat.format(new java.util.Date(timeExitValue));
    }
}
