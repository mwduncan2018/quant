package mwd.trading.indicator;

import com.ib.client.Bar;
import mwd.trading.broker.ibkr.RequestRegistry;
import mwd.trading.state.StockLookup;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RsiTracker {
	private static final Logger logger = LogManager.getLogger(RsiTracker.class);
	private final StockLookup stocks;
	private final RequestRegistry registry;
	private final Map<Integer, List<Bar>> historyBuffer = new ConcurrentHashMap<>();
	private final int period = 14;

	public RsiTracker(StockLookup stocks, RequestRegistry registry) {
		this.stocks = stocks;
		this.registry = registry;
	}

	public void onHistoricalData(int requestId, Bar bar) {
		historyBuffer.computeIfAbsent(requestId, k -> Collections.synchronizedList(new ArrayList<>())).add(bar);
	}

	public void onHistoricalDataEnd(int requestId, String start, String end) {
		List<Bar> bars = historyBuffer.get(requestId);
		if (bars != null) {
			synchronized (bars) {
				processUpdate(requestId);
			}
		}
	}

	public void onHistoricalDataUpdate(int requestId, Bar bar) {
		List<Bar> bars = historyBuffer.get(requestId);
		if (bars == null)
			return;

		synchronized (bars) {
			if (isNewBar(bars, bar)) {
				bars.add(bar);
				if (bars.size() > 1500) {
					bars.remove(0);
				}
				processUpdate(requestId);
			}
		}
	}

	private void processUpdate(int requestId) {
		List<Bar> bars = historyBuffer.get(requestId);
		if (bars == null || bars.size() <= period)
			return;

		String ticker = registry.getTickerFor(requestId);
		if (ticker != null) {
			double currentRSI = calculateRSI(bars);
			stocks.getStock(ticker).setRSI(currentRSI);
		}
	}

	private double calculateRSI(List<Bar> bars) {
		if (bars.size() <= period)
			return 50.0;

		double averageGain = 0;
		double averageLoss = 0;

		// 1. Initial Seed (The first SMA of gains and losses)
		// For RSI(14), we look at the first 14 changes (requires 15 bars)
		for (int i = 1; i <= period; i++) {
			double difference = bars.get(i).close() - bars.get(i - 1).close();
			if (difference >= 0)
				averageGain += difference;
			else
				averageLoss += Math.abs(difference);
		}

		averageGain /= period;
		averageLoss /= period;

		// 2. Wilder's Smoothing for all subsequent bars in the buffer
		// This loop handles everything from the 16th bar to the end
		for (int i = period + 1; i < bars.size(); i++) {
			double difference = bars.get(i).close() - bars.get(i - 1).close();
			double currentGain = difference > 0 ? difference : 0;
			double currentLoss = difference < 0 ? Math.abs(difference) : 0;

			// Wilder's Smoothing Formula: (PriorAvg * 13 + Current) / 14
			averageGain = (averageGain * (period - 1) + currentGain) / period;
			averageLoss = (averageLoss * (period - 1) + currentLoss) / period;
		}

		// 3. Final RS Calculation with Safety Guards
		if (averageLoss == 0) {
			return (averageGain == 0) ? 50.0 : 100.0;
		}

		double relativeStrength = averageGain / averageLoss;
		return 100 - (100 / (1 + relativeStrength));
	}

	private boolean isNewBar(List<Bar> bars, Bar newBar) {
		if (bars.isEmpty())
			return true;
		return !bars.get(bars.size() - 1).time().equals(newBar.time());
	}
}