package mwd.trading.marketdata;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.ib.client.Bar;

import mwd.trading.state.Blackboard;
import mwd.trading.domain.Stock;
import mwd.trading.broker.ibkr.RequestRegistry;

public class MinuteBarHandler {
	private static final Logger logger = LogManager.getLogger(MinuteBarHandler.class);

	private final Blackboard blackboard;
	private final RequestRegistry registry;
	private final MarketDataInputStore inputStore;

	public MinuteBarHandler(
			Blackboard blackboard, RequestRegistry registry, MarketDataInputStore inputStore) {
		this.blackboard = blackboard;
		this.registry = registry;
		this.inputStore = inputStore;
	}

	public void onHistoricalData(int reqId, Bar bar) {
		updateLastMinuteBar(reqId, bar);
	}

	public void onHistoricalDataUpdate(int reqId, Bar bar) {
		updateLastMinuteBar(reqId, bar);
	}

	private void updateLastMinuteBar(int reqId, Bar bar) {
		String ticker = registry.getTickerFor(reqId);
		if (ticker == null || !isUsable(bar)) {
			return;
		}
		Stock stock = blackboard.getStock(ticker);
		stock.setLastMinuteBar(bar);
		inputStore.record(ticker, MarketDataInput.MINUTE_BAR);
	}

	/**
	 * Rejects a bar whose prices cannot describe a real minute. The capitulation
	 * filter divides by the bar range and compares its low against a structural
	 * level, so a placeholder bar would produce a confident wrong answer rather
	 * than an obvious failure.
	 */
	private boolean isUsable(Bar bar) {
		if (bar == null) {
			return false;
		}
		boolean finite = Double.isFinite(bar.open()) && Double.isFinite(bar.high())
				&& Double.isFinite(bar.low()) && Double.isFinite(bar.close());
		if (!finite || bar.low() <= 0.0 || bar.high() < bar.low()) {
			logger.debug("Discarded an unusable minute bar at {}", bar.time());
			return false;
		}
		return true;
	}
}
