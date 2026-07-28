package mwd.trading.marketdata;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.ib.client.Contract;
import com.ib.client.EClientSocket;

import mwd.trading.state.Blackboard;
import mwd.trading.broker.ibkr.RequestRegistry;

/**
 * Owns restartable IBKR market-data subscriptions. Request IDs and consumer
 * routing are recreated after a connection that loses subscription state.
 */
public final class MarketDataSubscriptionManager {
    private static final Logger logger = LogManager.getLogger(MarketDataSubscriptionManager.class);

    private final EClientSocket client;
    private final Blackboard blackboard;
    private final RequestRegistry registry;
    private final List<String> tickers;
    private final MarketDataInputStore inputStore;
    private final Map<String, Integer> liveMarketDataRequests = new ConcurrentHashMap<>();
    private final Map<String, Integer> minuteHistoryRequests = new ConcurrentHashMap<>();
    private final AtomicBoolean initialized = new AtomicBoolean();

    public MarketDataSubscriptionManager(
            EClientSocket client,
            Blackboard blackboard,
            RequestRegistry registry,
            List<String> tickers,
            MarketDataInputStore inputStore) {
        this.client = client;
        this.blackboard = blackboard;
        this.registry = registry;
        this.tickers = List.copyOf(tickers);
        this.inputStore = inputStore;
    }

    public void initializeIfNeeded() throws InterruptedException {
        if (!initialized.compareAndSet(false, true)) {
            return;
        }

        markAllStale();
        for (String ticker : tickers) {
            Contract contract = stockContract(ticker);
            blackboard.getStock(ticker).setContract(contract);
            requestDailyHistory(ticker, contract);
            requestMinuteHistory(ticker, contract);
            Thread.sleep(500);
        }
        for (String ticker : tickers) {
            requestLiveMarketData(ticker, stockContract(ticker));
            Thread.sleep(10);
        }
        logger.info("Initialized market data for {} symbols", tickers.size());
    }

    public void resetAfterSocketReconnect() throws InterruptedException {
        cancelTrackedRequests();
        initialized.set(false);
        initializeIfNeeded();
    }

    public void resubscribeAfterDataLoss() throws InterruptedException {
        markAllStale();
        cancelTrackedRequests();
        for (String ticker : tickers) {
            Contract contract = stockContract(ticker);
            blackboard.getStock(ticker).setContract(contract);
            requestMinuteHistory(ticker, contract);
            requestLiveMarketData(ticker, contract);
            Thread.sleep(50);
        }
        logger.info("Resubmitted live and updating historical data requests after IBKR data loss");
    }

    /**
     * Drops every recorded market-data input. Anything delivered before a
     * disconnect or a resubscribe cannot be assumed to still describe the market,
     * and each input must be re-established on its own.
     */
    public void markAllStale() {
        inputStore.markAllStale();
    }

    public List<String> tickers() {
        return tickers;
    }

    private void requestDailyHistory(String ticker, Contract contract) {
        int requestId = blackboard.getNextRequestId();
        registry.register(requestId, ticker,
                RequestRegistry.DataConsumer.ATR_DAILY_WILDERS,
                RequestRegistry.DataConsumer.MOVING_AVERAGE);
        client.reqHistoricalData(requestId, contract, "", "1 Y", "1 day", "TRADES", 1, 1, false, null);
    }

    private void requestMinuteHistory(String ticker, Contract contract) {
        int requestId = blackboard.getNextRequestId();
        registry.register(requestId, ticker,
                RequestRegistry.DataConsumer.ATR_MINUTE_WILDERS,
                RequestRegistry.DataConsumer.RSI,
                RequestRegistry.DataConsumer.VOLUME,
                RequestRegistry.DataConsumer.TICK_BAR);
        minuteHistoryRequests.put(ticker, requestId);
        client.reqHistoricalData(requestId, contract, "", "2 D", "1 min", "TRADES", 1, 1, true, null);
    }

    private void requestLiveMarketData(String ticker, Contract contract) {
        int requestId = blackboard.getNextRequestId();
        registry.register(requestId, ticker,
                RequestRegistry.DataConsumer.TICK_PRICE,
                RequestRegistry.DataConsumer.TICK_SIZE,
                RequestRegistry.DataConsumer.MOVING_AVERAGE);
        liveMarketDataRequests.put(ticker, requestId);
        client.reqMktData(requestId, contract, "233", false, false, null);
    }

    private void cancelTrackedRequests() {
        liveMarketDataRequests.forEach((ticker, requestId) -> {
            client.cancelMktData(requestId);
            registry.unregister(requestId);
        });
        minuteHistoryRequests.forEach((ticker, requestId) -> {
            client.cancelHistoricalData(requestId);
            registry.unregister(requestId);
        });
        liveMarketDataRequests.clear();
        minuteHistoryRequests.clear();
    }

    private static Contract stockContract(String ticker) {
        Contract contract = new Contract();
        contract.symbol(ticker);
        contract.secType("STK");
        contract.exchange("SMART");
        contract.currency("USD");
        return contract;
    }
}
