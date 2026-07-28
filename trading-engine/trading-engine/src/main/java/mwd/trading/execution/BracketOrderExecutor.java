package mwd.trading.execution;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TimeZone;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.ib.client.Decimal;
import com.ib.client.EClientSocket;
import com.ib.client.Order;

import mwd.trading.config.Config;
import mwd.trading.domain.Stock;
import mwd.trading.domain.TradeDirection;
import mwd.trading.lifecycle.TradingGate;
import mwd.trading.persistence.JsonTradingStateStore;
import mwd.trading.state.Blackboard;

public class BracketOrderExecutor implements BracketOrderGateway {
    private static final Logger logger = LogManager.getLogger(BracketOrderExecutor.class);
    private final Blackboard blackboard;
    private final EClientSocket client;
    private final TradingGate tradingGate;
    private final JsonTradingStateStore stateStore;
    private final Config config;

    public BracketOrderExecutor(
            Blackboard blackboard,
            EClientSocket client,
            TradingGate tradingGate,
            JsonTradingStateStore stateStore,
            Config config) {
        this.blackboard = Objects.requireNonNull(blackboard);
        this.client = Objects.requireNonNull(client);
        this.tradingGate = Objects.requireNonNull(tradingGate);
        this.stateStore = Objects.requireNonNull(stateStore);
        this.config = Objects.requireNonNull(config);
    }

    /**
     * Helper class to define the parameters for each portion of the trade.
     */
    public static class SliceIntent {
        public Decimal quantity;
        public double takeProfitPrice;
        public double stopLossPrice;
        public long timeExit;

        public SliceIntent(Decimal quantity, double takeProfitPrice, double stopLossPrice, long timeExit) {
            this.quantity = quantity;
            this.takeProfitPrice = takeProfitPrice;
            this.stopLossPrice = stopLossPrice;
            this.timeExit = timeExit;
        }
    }

    /**
     * Places a consolidated entry order with multiple independent exit slices.
     */
    @Override
    public BracketOrder placeTripleThreat(
            String strategyName,
            TradeDirection tradeDirection,
            String tickerSymbol,
            Decimal totalOrderQuantity,
            double entryLimitPrice,
            List<SliceIntent> sliceIntents) {

        if (!tradingGate.allowsNewEntries()) {
            throw new IllegalStateException("New entries are disabled while engine mode is "
                    + tradingGate.getMode());
        }
        String normalizedStrategyName = Objects.requireNonNull(strategyName).trim();
        if (normalizedStrategyName.isEmpty()) {
            throw new IllegalArgumentException("strategyName must not be blank");
        }
        TradeDirection normalizedDirection = Objects.requireNonNull(tradeDirection);
        validateEntryIntent(tickerSymbol, totalOrderQuantity, entryLimitPrice, sliceIntents);
        String actionDirection = normalizedDirection.entryAction();

        Stock stock = blackboard.getStock(tickerSymbol);
        if (stock.getContract() == null) {
            halt("[" + tickerSymbol + "] Cannot submit an order without an IBKR contract");
            throw new IllegalStateException("Missing IBKR contract for " + tickerSymbol);
        }

        String account = configuredAccount();
        if (account.isBlank()) {
            halt("[" + tickerSymbol + "] Cannot submit an order before the IBKR account is known");
            throw new IllegalStateException("Missing IBKR account");
        }

        int parentEntryOrderId = blackboard.getNextOrderId();
        String tradeId = newTradeId(tickerSymbol);

        BracketOrder bracketOrder = new BracketOrder(
                tradeId,
                normalizedStrategyName,
                account,
                stock.getContract().conid(),
                actionDirection,
                tickerSymbol,
                parentEntryOrderId,
                totalOrderQuantity);
        bracketOrder.setEntryPrice(entryLimitPrice);

        List<Order> orderBundle = new ArrayList<>();
        String exitDirection = normalizedDirection.exitAction();

        // 1. Create the Parent Entry Order
        Order parentEntryOrder = new Order();
        parentEntryOrder.orderId(parentEntryOrderId);
        parentEntryOrder.action(actionDirection);
        parentEntryOrder.orderType("LMT");
        parentEntryOrder.lmtPrice(entryLimitPrice);
        parentEntryOrder.totalQuantity(totalOrderQuantity);
        parentEntryOrder.account(account);
        String parentReference = orderReference(tradeId, "P");
        parentEntryOrder.orderRef(parentReference);
        parentEntryOrder.transmit(false);
        bracketOrder.registerOrderLeg(parentEntryOrderId, "PARENT", parentReference, totalOrderQuantity);
        orderBundle.add(parentEntryOrder);

        // 2. Process each Slice Intent
        for (int sliceIndex = 0; sliceIndex < sliceIntents.size(); sliceIndex++) {
            SliceIntent intent = sliceIntents.get(sliceIndex);
            int takeProfitOrderId = blackboard.getNextOrderId();
            int stopLossOrderId = blackboard.getNextOrderId();
            int timeExitOrderId = blackboard.getNextOrderId();

            // Each slice needs a unique OCA group name to remain independent
            String rolePrefix = "S" + (sliceIndex + 1);
            String oneCancelsAllGroup = tradeId + '-' + rolePrefix;

            BracketOrder.ExitSlice exitSlice = new BracketOrder.ExitSlice(
                    takeProfitOrderId,
                    stopLossOrderId,
                    timeExitOrderId,
                    intent.quantity,
                    oneCancelsAllGroup
            );

            exitSlice.setTakeProfitPrice(intent.takeProfitPrice);
            exitSlice.setStopLossPrice(intent.stopLossPrice);
            exitSlice.setTimeExit(intent.timeExit);
            bracketOrder.addSlice(exitSlice);

            // Build Take Profit Order
            Order takeProfitOrder = new Order();
            takeProfitOrder.orderId(takeProfitOrderId);
            takeProfitOrder.parentId(parentEntryOrderId);
            takeProfitOrder.action(exitDirection);
            takeProfitOrder.orderType("LMT");
            takeProfitOrder.lmtPrice(intent.takeProfitPrice);
            takeProfitOrder.totalQuantity(intent.quantity);
            takeProfitOrder.account(account);
            String takeProfitReference = orderReference(tradeId, rolePrefix + "TP");
            takeProfitOrder.orderRef(takeProfitReference);
            takeProfitOrder.ocaGroup(oneCancelsAllGroup);
            takeProfitOrder.ocaType(2); // Reduce with Restrict
            takeProfitOrder.hidden(true);
            takeProfitOrder.transmit(false);
            bracketOrder.registerOrderLeg(
                    takeProfitOrderId, rolePrefix + "_TAKE_PROFIT", takeProfitReference, intent.quantity);
            orderBundle.add(takeProfitOrder);

            // Build Stop Loss Order
            Order stopLossOrder = new Order();
            stopLossOrder.orderId(stopLossOrderId);
            stopLossOrder.parentId(parentEntryOrderId);
            stopLossOrder.action(exitDirection);
            stopLossOrder.orderType("STP");
            stopLossOrder.auxPrice(intent.stopLossPrice);
            stopLossOrder.totalQuantity(intent.quantity);
            stopLossOrder.account(account);
            String stopLossReference = orderReference(tradeId, rolePrefix + "SL");
            stopLossOrder.orderRef(stopLossReference);
            stopLossOrder.ocaGroup(oneCancelsAllGroup);
            stopLossOrder.ocaType(2);
            stopLossOrder.transmit(false);
            bracketOrder.registerOrderLeg(
                    stopLossOrderId, rolePrefix + "_STOP_LOSS", stopLossReference, intent.quantity);
            orderBundle.add(stopLossOrder);

            // Build Time Exit Order
            Order timeExitOrder = new Order();
            timeExitOrder.orderId(timeExitOrderId);
            timeExitOrder.parentId(parentEntryOrderId);
            timeExitOrder.action(exitDirection);
            timeExitOrder.orderType("MKT");
            timeExitOrder.totalQuantity(intent.quantity);
            timeExitOrder.account(account);
            String timeExitReference = orderReference(tradeId, rolePrefix + "TX");
            timeExitOrder.orderRef(timeExitReference);
            timeExitOrder.ocaGroup(oneCancelsAllGroup);
            timeExitOrder.ocaType(2);

            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd HH:mm:ss");
            dateFormat.setTimeZone(TimeZone.getTimeZone("America/New_York"));
            timeExitOrder.goodAfterTime(dateFormat.format(intent.timeExit));
            timeExitOrder.transmit(false);
            bracketOrder.registerOrderLeg(
                    timeExitOrderId, rolePrefix + "_TIME_EXIT", timeExitReference, intent.quantity);
            orderBundle.add(timeExitOrder);
        }

        // 3. Register and Submit
        blackboard.getOrderRegistry().register(bracketOrder);
        stock.setActiveBracket(bracketOrder);

        try {
            persistIntent(bracketOrder, actionDirection);
        } catch (RuntimeException exception) {
            blackboard.getOrderRegistry().unregister(bracketOrder);
            stock.setActiveBracket(null);
            throw exception;
        }

        // Set transmit to true only on the very last order in the entire bundle
        orderBundle.get(orderBundle.size() - 1).transmit(true);

        try {
            for (Order individualOrder : orderBundle) {
                client.placeOrder(individualOrder.orderId(), stock.getContract(), individualOrder);
                bracketOrder.incrementUpdateCount();
            }
        } catch (RuntimeException exception) {
            halt("[" + tickerSymbol + "] Order submission became uncertain: " + exception.getMessage());
            throw new UncertainOrderSubmissionException(
                    "Order submission became uncertain for " + tickerSymbol, exception);
        }

        logger.info("[{}] Composite Bracket Initialized with {} slices for total quantity {}",
                tickerSymbol, sliceIntents.size(), totalOrderQuantity);
        return bracketOrder;
    }

    /**
     * Updates specific exit legs for a single slice without affecting other intentions.
     */
    @Override
    public void updateTripleThreatExits(Stock stock, BracketOrder bracketOrder,
            BracketOrder.ExitSlice exitSlice, double newTakeProfitPrice,
            double newStopLossPrice, long newExitTime) {

        if (!tradingGate.allowsAutomatedOrderChanges()) {
            logger.warn("[{}] Exit update suppressed while engine mode is {}",
                    stock.getTicker(), tradingGate.getMode());
            return;
        }
        if (bracketOrder == null || exitSlice == null) {
            logger.warn("[{}] Cannot update exits: Bracket or Slice is null", stock.getTicker());
            return;
        }
        if (!client.isConnected()) {
            halt("[" + stock.getTicker() + "] Cannot update exits while disconnected from IBKR");
            return;
        }

        List<Order> updateBundle = new ArrayList<>();
        String oneCancelsAllGroup = exitSlice.getOneCancelsAllGroup();
        Decimal sliceQuantity = exitSlice.getQuantity();
        String account = bracketOrder.getAccount();

        int positionSign = stock.getPositionSize().value().signum();
        if (positionSign == 0) {
            halt("[" + stock.getTicker() + "] Refusing to infer an exit direction for a zero position");
            return;
        }
        String exitDirection = positionSign > 0 ? "SELL" : "BUY";

        // Check Stop Loss
        if (newStopLossPrice != exitSlice.getStopLossPrice()) {
            Order stopLossOrder = new Order();
            stopLossOrder.orderId(exitSlice.getStopLossOrderId());
            stopLossOrder.parentId(bracketOrder.getParentOrderId());
            stopLossOrder.action(exitDirection);
            stopLossOrder.orderType("STP");
            stopLossOrder.auxPrice(newStopLossPrice);
            stopLossOrder.totalQuantity(sliceQuantity);
            stopLossOrder.account(account);
            stopLossOrder.orderRef(orderReferenceFor(bracketOrder, exitSlice.getStopLossOrderId()));
            stopLossOrder.ocaGroup(oneCancelsAllGroup);
            stopLossOrder.ocaType(2);
            stopLossOrder.transmit(false);

            updateBundle.add(stopLossOrder);
            exitSlice.setStopLossPrice(newStopLossPrice);
            bracketOrder.incrementUpdateCount();
        }

        // Check Take Profit
        if (newTakeProfitPrice != exitSlice.getTakeProfitPrice()) {
            Order takeProfitOrder = new Order();
            takeProfitOrder.orderId(exitSlice.getTakeProfitOrderId());
            takeProfitOrder.parentId(bracketOrder.getParentOrderId());
            takeProfitOrder.action(exitDirection);
            takeProfitOrder.orderType("LMT");
            takeProfitOrder.lmtPrice(newTakeProfitPrice);
            takeProfitOrder.totalQuantity(sliceQuantity);
            takeProfitOrder.account(account);
            takeProfitOrder.orderRef(orderReferenceFor(bracketOrder, exitSlice.getTakeProfitOrderId()));
            takeProfitOrder.ocaGroup(oneCancelsAllGroup);
            takeProfitOrder.ocaType(2);
            takeProfitOrder.hidden(true);
            takeProfitOrder.transmit(false);

            updateBundle.add(takeProfitOrder);
            exitSlice.setTakeProfitPrice(newTakeProfitPrice);
            bracketOrder.incrementUpdateCount();
        }

        // Check Time Exit
        if (newExitTime != exitSlice.getTimeExit()) {
            Order timeExitOrder = new Order();
            timeExitOrder.orderId(exitSlice.getTimeExitOrderId());
            timeExitOrder.parentId(bracketOrder.getParentOrderId());
            timeExitOrder.action(exitDirection);
            timeExitOrder.orderType("MKT");
            timeExitOrder.totalQuantity(sliceQuantity);
            timeExitOrder.account(account);
            timeExitOrder.orderRef(orderReferenceFor(bracketOrder, exitSlice.getTimeExitOrderId()));
            timeExitOrder.ocaGroup(oneCancelsAllGroup);
            timeExitOrder.ocaType(2);

            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd HH:mm:ss");
            dateFormat.setTimeZone(TimeZone.getTimeZone("America/New_York"));
            timeExitOrder.goodAfterTime(dateFormat.format(newExitTime));
            timeExitOrder.transmit(false);

            updateBundle.add(timeExitOrder);
            exitSlice.setTimeExit(newExitTime);
            bracketOrder.incrementUpdateCount();
        }

        if (updateBundle.isEmpty()) {
            return;
        }

        updateBundle.get(updateBundle.size() - 1).transmit(true);

        try {
            for (Order individualOrder : updateBundle) {
                client.placeOrder(individualOrder.orderId(), stock.getContract(), individualOrder);
            }
        } catch (RuntimeException exception) {
            halt("[" + stock.getTicker() + "] Exit update became uncertain: " + exception.getMessage());
            throw exception;
        }

        logger.info("[{}] Sent {} updates for slice within OCA Group {}",
                stock.getTicker(), updateBundle.size(), oneCancelsAllGroup);
    }

    private void persistIntent(BracketOrder bracketOrder, String actionDirection) {
        try {
            stateStore.recordIntent(bracketOrder, actionDirection);
        } catch (IOException exception) {
            halt("[" + bracketOrder.getTicker() + "] Unable to persist order intent: " + exception.getMessage());
            throw new IllegalStateException("Unable to persist order intent", exception);
        }
    }

    private void validateEntryIntent(
            String tickerSymbol,
            Decimal totalOrderQuantity,
            double entryLimitPrice,
            List<SliceIntent> sliceIntents) {
        if (!client.isConnected()) {
            halt("[" + tickerSymbol + "] Cannot submit an entry while disconnected from IBKR");
            throw new IllegalStateException("Cannot submit " + tickerSymbol + " while disconnected from IBKR");
        }
        if (totalOrderQuantity == null || totalOrderQuantity.compareTo(Decimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Order quantity must be positive");
        }
        if (!Double.isFinite(entryLimitPrice) || entryLimitPrice <= 0.0) {
            throw new IllegalArgumentException("Entry limit price must be positive and finite");
        }
        if (sliceIntents == null || sliceIntents.isEmpty()) {
            throw new IllegalArgumentException("At least one protective exit slice is required");
        }

        Decimal sliceTotal = Decimal.ZERO;
        for (SliceIntent intent : sliceIntents) {
            if (intent == null || intent.quantity == null || intent.quantity.compareTo(Decimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Every exit slice must have a positive quantity");
            }
            sliceTotal = sliceTotal.add(intent.quantity);
        }
        if (sliceTotal.compareTo(totalOrderQuantity) != 0) {
            throw new IllegalArgumentException(
                    "Exit-slice quantities must equal the parent order quantity");
        }
    }

    private String configuredAccount() {
        if (!config.getExpectedAccount().isBlank()) {
            return config.getExpectedAccount();
        }
        String accountId = blackboard.getAccount().getAccountId();
        return accountId == null ? "" : accountId;
    }

    private static String newTradeId(String ticker) {
        String date = LocalDate.now(ZoneId.of("America/New_York")).toString().replace("-", "");
        String random = UUID.randomUUID().toString().substring(0, 8);
        return ticker + '-' + date + '-' + random;
    }

    private static String orderReference(String tradeId, String role) {
        return "TE|" + tradeId + '|' + role;
    }

    private static String orderReferenceFor(BracketOrder bracketOrder, int apiOrderId) {
        return bracketOrder.getLegStates().stream()
                .filter(leg -> leg.getApiOrderId() == apiOrderId)
                .map(BracketOrder.OrderLegState::getOrderReference)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing order reference for " + apiOrderId));
    }

    private void halt(String reason) {
        blackboard.setSystemHalted(true);
        tradingGate.requireManualIntervention(reason);
        logger.error(reason);
    }
}
