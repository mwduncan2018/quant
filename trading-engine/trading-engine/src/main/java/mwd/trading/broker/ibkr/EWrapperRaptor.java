package mwd.trading.broker.ibkr;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.ib.client.Bar;
import com.ib.client.CommissionAndFeesReport;
import com.ib.client.Contract;
import com.ib.client.Decimal;
import com.ib.client.Execution;
import com.ib.client.Order;
import com.ib.client.OrderState;
import com.ib.client.TickAttrib;
import com.ib.client.TickAttribBidAsk;
import com.ib.client.TickAttribLast;

import mwd.trading.broker.ibkr.RequestRegistry;
import mwd.trading.marketdata.MarketDataSubscriptionManager;
import mwd.trading.indicator.DailyWilderAtrCalculator;
import mwd.trading.indicator.IntradayWilderAtrTracker;
import mwd.trading.broker.ibkr.callback.AccountEventHandler;
import mwd.trading.broker.ibkr.callback.IbkrErrorHandler;
import mwd.trading.indicator.SimpleMovingAverageTracker;
import mwd.trading.broker.ibkr.callback.NextValidIdHandler;
import mwd.trading.execution.OrderLifecycleHandler;
import mwd.trading.indicator.RsiTracker;
import mwd.trading.marketdata.MinuteBarHandler;
import mwd.trading.marketdata.PriceTickHandler;
import mwd.trading.marketdata.SizeTickHandler;
import mwd.trading.broker.ibkr.callback.BrokerTimeHandler;
import mwd.trading.indicator.DailyVwapTracker;
import mwd.trading.indicator.MinuteVolumeTracker;

public class EWrapperRaptor extends EWrapperAbstractBase {
    private static final Logger logger = LogManager.getLogger(EWrapperRaptor.class);
    private final RequestRegistry registry;
    private final IntradayWilderAtrTracker intradayWilderAtrTracker;
    private final DailyWilderAtrCalculator dailyWilderAtrCalculator;
    private final IbkrErrorHandler ibkrErrorHandler;
    private final AccountEventHandler accountEventHandler;
	private final SimpleMovingAverageTracker simpleMovingAverageTracker;
	private final NextValidIdHandler nextValidIdHandler;
	private final OrderLifecycleHandler orderLifecycleHandler;
	private final RsiTracker rsiTracker;
	private final MinuteBarHandler minuteBarHandler;
	private final PriceTickHandler priceTickHandler;
	private final SizeTickHandler sizeTickHandler;
	private final BrokerTimeHandler brokerTimeHandler;
	private final MinuteVolumeTracker minuteVolumeTracker;
	private final DailyVwapTracker dailyVwapTracker;
	private volatile IbkrSessionManager sessionManager;
	private volatile MarketDataSubscriptionManager marketDataSubscriptionManager;

	public EWrapperRaptor(
			RequestRegistry registry,
			IntradayWilderAtrTracker intradayWilderAtrTracker,
			DailyWilderAtrCalculator dailyWilderAtrCalculator,
			IbkrErrorHandler ibkrErrorHandler,
			AccountEventHandler accountEventHandler,
			SimpleMovingAverageTracker simpleMovingAverageTracker,
			NextValidIdHandler nextValidIdHandler,
			OrderLifecycleHandler orderLifecycleHandler,
			RsiTracker rsiTracker,
			MinuteBarHandler minuteBarHandler,
			PriceTickHandler priceTickHandler,
			SizeTickHandler sizeTickHandler,
			BrokerTimeHandler brokerTimeHandler,
			MinuteVolumeTracker minuteVolumeTracker,
			DailyVwapTracker dailyVwapTracker) {
		this.registry = registry;
        this.intradayWilderAtrTracker = intradayWilderAtrTracker;
        this.dailyWilderAtrCalculator = dailyWilderAtrCalculator;
        this.ibkrErrorHandler = ibkrErrorHandler;
        this.accountEventHandler = accountEventHandler;
        this.simpleMovingAverageTracker = simpleMovingAverageTracker;
        this.nextValidIdHandler = nextValidIdHandler;
        this.orderLifecycleHandler = orderLifecycleHandler;
        this.rsiTracker = rsiTracker;
        this.minuteBarHandler = minuteBarHandler;
        this.priceTickHandler = priceTickHandler;
        this.sizeTickHandler= sizeTickHandler;
        this.brokerTimeHandler = brokerTimeHandler;
        this.minuteVolumeTracker = minuteVolumeTracker;
        this.dailyVwapTracker = dailyVwapTracker;
    }

	public void attachLifecycle(
			IbkrSessionManager sessionManager,
			MarketDataSubscriptionManager marketDataSubscriptionManager) {
		this.sessionManager = sessionManager;
		this.marketDataSubscriptionManager = marketDataSubscriptionManager;
	}

	/*
	 * Connection
	 */

	@Override public void nextValidId(int orderId) {
		nextValidIdHandler.initialize(orderId);
		if (sessionManager != null) {
			sessionManager.onNextValidId();
		}
	}

	/*
     * Connection & Error Handling
     */

	@Override public void error(Exception e) {
	    logger.error(">>> JAVA EXCEPTION: {}", e.getMessage(), e);
	}

	@Override public void error(String str) {
	    logger.error(">>> IBKR MESSAGE: {}", str);
	}

	@Override public void error(int id, long time, int errorCode, String errorMsg, String advancedOrderRejectJson) {
		ibkrErrorHandler.process(id, errorCode, errorMsg, advancedOrderRejectJson);
		orderLifecycleHandler.onError(id, time, errorCode, errorMsg, advancedOrderRejectJson);
		if (sessionManager != null) {
			sessionManager.onError(errorCode, errorMsg);
		}
    }

	@Override public void connectionClosed() {
		if (sessionManager != null) {
			sessionManager.onConnectionClosed();
		}
	}

	@Override public void connectAck() {
		if (sessionManager != null) {
			sessionManager.onConnectAck();
		}
	}

	/*
	 * Market Data (Ticks)
	 */
	// Market-data readiness is recorded by the handlers below, not here. Only they
	// know which field arrived and whether its value was accepted, and marking a
	// symbol ready from any tick is what previously let one bid size stand in for
	// every input a strategy requires.
	@Override public void tickPrice(int reqId, int field, double price, TickAttrib attribs) {
		//logger.info("RAW TICK!!! ID={}, FIELD={}, PRICE={}", reqId, field, price);
		var consumers = registry.getConsumersFor(reqId);
		if (consumers.contains(RequestRegistry.DataConsumer.TICK_PRICE))
			this.priceTickHandler.onTickPrice(reqId, field, price, attribs);
		if (consumers.contains(RequestRegistry.DataConsumer.MOVING_AVERAGE))
	        this.simpleMovingAverageTracker.onTickPrice(reqId, field, price, attribs);
	}

	@Override public void tickSize(int reqId, int field, Decimal size) {
		var consumers = registry.getConsumersFor(reqId);
		if (consumers.contains(RequestRegistry.DataConsumer.TICK_SIZE))
			this.sizeTickHandler.onTickSize(reqId, field, size);
	}

	@Override public void tickByTickBidAsk(int reqId, long time, double bidPrice,
	                             double askPrice, Decimal bidSize, Decimal askSize,
	                             TickAttribBidAsk tickAttribBidAsk) {
		var consumers = registry.getConsumersFor(reqId);
		if (consumers.contains(RequestRegistry.DataConsumer.TICK_PRICE))
			priceTickHandler.onTickByTickBidAsk(reqId, bidPrice, askPrice);
		if (consumers.contains(RequestRegistry.DataConsumer.TICK_SIZE))
			sizeTickHandler.onTickByTickBidAsk(reqId, bidSize, askSize);
	}

	@Override public void tickByTickAllLast(int reqId, int tickType, long time,
	        double price, Decimal size, TickAttribLast tickAttribLast,
	        String exchange, String specialConditions) {
		var consumers = registry.getConsumersFor(reqId);
		if (consumers.contains(RequestRegistry.DataConsumer.TICK_PRICE))
			priceTickHandler.onTickByTickAllLast(reqId, price);
		if (consumers.contains(RequestRegistry.DataConsumer.TICK_SIZE))
			sizeTickHandler.onTickByTickAllLast(reqId, size);
	}

	/*
	 * Historical Data
	 */
	@Override public void historicalData(int reqId, Bar bar) {
		var consumers = registry.getConsumersFor(reqId);
		if (consumers.contains(RequestRegistry.DataConsumer.ATR_MINUTE_WILDERS))
			this.intradayWilderAtrTracker.onHistoricalData(reqId, bar);
		if (consumers.contains(RequestRegistry.DataConsumer.ATR_DAILY_WILDERS))
			this.dailyWilderAtrCalculator.onHistoricalData(reqId, bar);
		if (consumers.contains(RequestRegistry.DataConsumer.MOVING_AVERAGE))
			this.simpleMovingAverageTracker.onHistoricalData(reqId, bar);
		if (consumers.contains(RequestRegistry.DataConsumer.RSI))
			this.rsiTracker.onHistoricalData(reqId, bar);
		if (consumers.contains(RequestRegistry.DataConsumer.VOLUME))
			this.minuteVolumeTracker.onHistoricalData(reqId, bar);
		if (consumers.contains(RequestRegistry.DataConsumer.TICK_BAR))
			this.minuteBarHandler.onHistoricalData(reqId, bar);
		if (consumers.contains(RequestRegistry.DataConsumer.VWAP))
			this.dailyVwapTracker.onHistoricalData(reqId, bar);
	}

	@Override public void historicalDataEnd(int reqId, String startDate, String endDate) {
		var consumers = registry.getConsumersFor(reqId);
		if (consumers.contains(RequestRegistry.DataConsumer.ATR_MINUTE_WILDERS))
			this.intradayWilderAtrTracker.onHistoricalDataEnd(reqId, startDate, endDate);
		if (consumers.contains(RequestRegistry.DataConsumer.ATR_DAILY_WILDERS))
			this.dailyWilderAtrCalculator.onHistoricalDataEnd(reqId, startDate, endDate);
		if (consumers.contains(RequestRegistry.DataConsumer.MOVING_AVERAGE))
			this.simpleMovingAverageTracker.onHistoricalDataEnd(reqId, startDate, endDate);
		if (consumers.contains(RequestRegistry.DataConsumer.RSI))
			this.rsiTracker.onHistoricalDataEnd(reqId, startDate, endDate);
		if (consumers.contains(RequestRegistry.DataConsumer.VOLUME))
			this.minuteVolumeTracker.onHistoricalDataEnd(reqId, startDate, endDate);
		if (consumers.contains(RequestRegistry.DataConsumer.VWAP))
			this.dailyVwapTracker.onHistoricalDataEnd(reqId, startDate, endDate);
	}

	@Override public void historicalDataUpdate(int reqId, Bar bar) {
		var consumers = registry.getConsumersFor(reqId);
		if (consumers.contains(RequestRegistry.DataConsumer.ATR_MINUTE_WILDERS))
			this.intradayWilderAtrTracker.onHistoricalDataUpdate(reqId, bar);
		if (consumers.contains(RequestRegistry.DataConsumer.RSI))
			this.rsiTracker.onHistoricalDataUpdate(reqId, bar);
		if (consumers.contains(RequestRegistry.DataConsumer.VOLUME))
			this.minuteVolumeTracker.onHistoricalDataUpdate(reqId, bar);
		if (consumers.contains(RequestRegistry.DataConsumer.TICK_BAR))
			this.minuteBarHandler.onHistoricalDataUpdate(reqId, bar);
		if (consumers.contains(RequestRegistry.DataConsumer.VWAP))
			this.dailyVwapTracker.onHistoricalDataUpdate(reqId, bar);
	}


	/*
	 * Order & Execution Status
	 */
	@Override public void orderStatus(int orderId, String status, Decimal filled, Decimal remaining,
            double avgFillPrice, long permId, int parentId,
            double lastFillPrice, int clientId, String whyHeld,
            double mktCapPrice) {
		orderLifecycleHandler.onOrderStatus(orderId, status, filled,
				remaining, avgFillPrice, permId,
				parentId, lastFillPrice, clientId,
				whyHeld, mktCapPrice);
	}

	@Override public void openOrder(int orderId, Contract contract, Order order, OrderState orderState) {
		orderLifecycleHandler.onOpenOrder(orderId, contract, order, orderState);
	}


    @Override public void openOrderEnd() {
        orderLifecycleHandler.onOpenOrderEnd();
    }

	@Override public void execDetails(int reqId, Contract contract, Execution execution) { orderLifecycleHandler.onExecDetails(reqId, contract, execution); }
    @Override public void execDetailsEnd(int reqId) { orderLifecycleHandler.onExecDetailsEnd(reqId) ;}
    @Override public void commissionAndFeesReport(CommissionAndFeesReport arg0) { orderLifecycleHandler.onCommissionAndFeesReport(arg0); }
    @Override public void completedOrder(Contract contract, Order order, OrderState orderState) { orderLifecycleHandler.onCompletedOrder(contract, order, orderState); }
    @Override public void completedOrdersEnd() { orderLifecycleHandler.onCompletedOrdersEnd(); }
    @Override public void orderBound(long orderId, int apiClientId, int apiOrderId) { orderLifecycleHandler.onOrderBound(orderId, apiClientId, apiOrderId); }

	/*
	 * Account
	 */
    @Override public void managedAccounts(String accountsList) {
        accountEventHandler.onManagedAccounts(accountsList);
		if (sessionManager != null) {
			sessionManager.onManagedAccounts(accountsList);
		}
    }

	@Override public void accountSummary(int reqId, String account, String tag, String value, String currency) {
	}

	// Use this for a snapshot of an account (I will not be using this)
	@Override public void position(String account, Contract contract, Decimal pos, double avgCost) {
	    accountEventHandler.onPosition(account, contract, pos, avgCost);
	}

	@Override public void positionEnd() {
		accountEventHandler.onPositionEnd();
	}

	@Override public void accountDownloadEnd(String accountName) {
		accountEventHandler.onAccountDownloadEnd(accountName);
	}

	@Override public void updatePortfolio(Contract contract, Decimal position,
			double marketPrice, double marketValue,
	        double averageCost, double unrealizedPNL,
	        double realizedPNL, String accountName) {
	    accountEventHandler.onUpdatePortfolio(contract, position,
                marketPrice, marketValue,
                averageCost, unrealizedPNL,
                realizedPNL, accountName);
	}

	@Override public void updateAccountTime(String timeStamp) {
		accountEventHandler.onUpdateAccountTime(timeStamp);
	}

	@Override public void updateAccountValue(String key, String value, String currency, String accountName) {
	    accountEventHandler.onUpdateAccountValue(key, value, currency, accountName);
	}

	/*
	 * Time
	 */
	@Override public void currentTime(long time) {
	    brokerTimeHandler.onCurrentTime(time);
	}

}
