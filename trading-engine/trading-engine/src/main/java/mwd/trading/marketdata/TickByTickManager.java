package mwd.trading.marketdata;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.ib.client.Contract;
import com.ib.client.EClientSocket;

import mwd.trading.broker.ibkr.RequestRegistry;
import mwd.trading.state.Blackboard;
import mwd.trading.domain.Stock;

public class TickByTickManager implements TickStreamController {
	private static final Logger logger = LogManager.getLogger(TickByTickManager.class);

	private final Blackboard blackboard;
	private final EClientSocket client;
	private final RequestRegistry registry;

	private final int MAX_STREAMS = 5;
	private final AtomicInteger activeStreamCount = new AtomicInteger(0);
	private final Map<String, Integer> activeRequests = new ConcurrentHashMap<>();

	public TickByTickManager(Blackboard blackboard, EClientSocket client, RequestRegistry registry) {
		this.blackboard = blackboard;
		this.client = client;
		this.registry = registry;
	}

	public boolean isSlotAvailable() {
		return activeStreamCount.get() < MAX_STREAMS;
	}

	@Override
	public boolean isStreamActive(String ticker) {
		return activeRequests.containsKey(ticker);
	}

	public int getActiveStreamCount() {
		return activeStreamCount.get();
	}

	@Override
	public void tryRequestStream(String ticker) {
		if (isStreamActive(ticker))
			return;

		int current = activeStreamCount.get();
		while (current < MAX_STREAMS) {
			if (activeStreamCount.compareAndSet(current, current + 1)) {
				executeRequest(ticker);
				return;
			}
			current = activeStreamCount.get();
		}
		logger.warn("[{}] TickByTickManager.tryRequestStream - Limit reached (5). Cannot start stream for {}.", ticker, ticker);
	}

	@Override
	public void cancelStream(String ticker) {
		Integer reqId = activeRequests.remove(ticker);
		if (reqId != null) {
			client.cancelTickByTickData(reqId);
			registry.unregister(reqId);
			activeStreamCount.decrementAndGet();
			logger.info("[{}] TickByTickManager.cancelStream - Stream cancelled. ReqID={}", ticker, reqId);
		}
	}

	private void executeRequest(String ticker) {
		Stock stock = blackboard.getStock(ticker);
		Contract contract = stock.getContract();
		int reqId = blackboard.getNextRequestId();

		registry.register(reqId, ticker, RequestRegistry.DataConsumer.TICK_PRICE, RequestRegistry.DataConsumer.TICK_SIZE);

		activeRequests.put(ticker, reqId);
		client.reqTickByTickData(reqId, contract, "BidAsk", 0, false);

		logger.info("[{}] TickByTickManager.executeRequest - Stream requested. ReqID={}", ticker, reqId);
	}
}
