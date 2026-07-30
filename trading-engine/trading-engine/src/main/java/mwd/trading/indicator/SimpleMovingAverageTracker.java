package mwd.trading.indicator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.ib.client.Bar;
import com.ib.client.TickAttrib;

import mwd.trading.state.StockLookup;
import mwd.trading.domain.Stock;
import mwd.trading.broker.ibkr.RequestRegistry;
import mwd.trading.broker.ibkr.TickMap;

public class SimpleMovingAverageTracker {
	private static final Logger logger = LogManager.getLogger(SimpleMovingAverageTracker.class);
	private final StockLookup stocks;
	private final RequestRegistry registry;
	private final TickMap tickMap;

	private final Map<Integer, List<Double>> historyBuffer = new ConcurrentHashMap<>();
	private final Map<String, Double> sum199 = new ConcurrentHashMap<>();
	private final Map<String, Double> sum99 = new ConcurrentHashMap<>();
	private final Map<String, Double> sum49 = new ConcurrentHashMap<>();
	private final Map<String, Double> sum19 = new ConcurrentHashMap<>();
	private final Map<String, Double> sum9 = new ConcurrentHashMap<>();

	public SimpleMovingAverageTracker(StockLookup stocks, RequestRegistry registry, TickMap tickMap) {
		this.stocks = stocks;
		this.registry = registry;
		this.tickMap = tickMap;
	}

	public void onHistoricalData(int reqId, Bar bar) {
		historyBuffer.computeIfAbsent(reqId, k -> Collections.synchronizedList(new ArrayList<>())).add(bar.close());
	}

	public void onHistoricalDataEnd(int reqId, String startDate, String endDate) {
		List<Double> prices = historyBuffer.remove(reqId);

		// Guard: Ensure we have enough data for a 200 SMA
		if (prices == null || prices.size() < 200) {
			logger.warn("[ReqID {}] SimpleMovingAverageTracker.onHistoricalDataEnd - Insufficient history. Need 200 bars.", reqId);
			return;
		}

		String ticker = registry.getTickerFor(reqId);
		if (ticker == null)
			return;

		// Calculate tail sums (n-1) to prepare for the next price update
		sum199.put(ticker, calculateTailSum(prices, 199));
		sum99.put(ticker, calculateTailSum(prices, 99));
		sum49.put(ticker, calculateTailSum(prices, 49));
		sum19.put(ticker, calculateTailSum(prices, 19));
		sum9.put(ticker, calculateTailSum(prices, 9));

		// Initial calculation using the last close from history
		double lastClose = prices.get(prices.size() - 1);
		updateBlackboard(ticker, lastClose);

		logger.info("[{}] SMAs Initialized. Range: {} to {}", ticker, startDate, endDate);
	}

	public void onTickPrice(int reqId, int field, double price, TickAttrib attribs) {
		if (!tickMap.isLast(field) || price <= 0)
			return;

		String ticker = registry.getTickerFor(reqId);
		if (ticker == null)
			return;

		updateBlackboard(ticker, price);
		logger.debug("SMA Updated for {}: Price={}", ticker, price);
	}

	private void updateBlackboard(String ticker, double price) {
		Stock ss = stocks.getStock(ticker);
		if (ss == null)
			return;

		Double s199 = sum199.get(ticker);
		Double s99 = sum99.get(ticker);
		Double s49 = sum49.get(ticker);
		Double s19 = sum19.get(ticker);
		Double s9 = sum9.get(ticker);

		if (s199 != null)
			ss.setSma200((s199 + price) / 200);
		if (s99 != null)
			ss.setSma100((s99 + price) / 100);
		if (s49 != null)
			ss.setSma50((s49 + price) / 50);
		if (s19 != null)
			ss.setSma20((s19 + price) / 20);
		if (s9 != null)
			ss.setSma10((s9 + price) / 10);
	}

	private double calculateTailSum(List<Double> prices, int n) {
		int size = prices.size();
		int start = size - n;
		double sum = 0;
		for (int i = start; i < size; i++) {
			sum += prices.get(i);
		}
		return sum;
	}
}