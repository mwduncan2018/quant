package mwd.trading.marketdata;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.ib.client.Decimal;
import mwd.trading.state.Blackboard;
import mwd.trading.domain.Stock;
import mwd.trading.broker.ibkr.RequestRegistry;
import mwd.trading.broker.ibkr.TickMap;

public class SizeTickHandler {
	private static final Logger logger = LogManager.getLogger(SizeTickHandler.class);
	private final Blackboard blackboard;
	private final RequestRegistry registry;
	private final TickMap tickMap;

	public SizeTickHandler(Blackboard blackboard, RequestRegistry registry, TickMap tickMap) {
		this.blackboard = blackboard;
		this.registry = registry;
		this.tickMap = tickMap;
	}

	public void onTickSize(int reqId, int field, Decimal size) {
		if (size == null || size.isZero())
			return;

		String ticker = registry.getTickerFor(reqId);
		if (ticker == null)
			return;

		Stock stock = blackboard.getStock(ticker);

		if (tickMap.isBidSize(field)) {
			stock.setBidSize(size);
		} else if (tickMap.isAskSize(field)) {
			stock.setAskSize(size);
		} else if (tickMap.isLastSize(field)) {
			stock.setLastSize(size);
		} else if (tickMap.isVolume(field)) {
			stock.setIntradayVolume(size);
		} else if (tickMap.isAverageVolume(field)) {
			// Tick 21 is the 90-day average DAILY volume, not a 15-minute mean.
			// The rolling minute baseline the capitulation filter compares against
			// is owned by MinuteVolumeTracker, which builds it from minute bars.
			stock.setAverageDailyVolume(size);
		}
	}

	public void onTickByTickBidAsk(int reqId, Decimal bidSize, Decimal askSize) {
		String ticker = registry.getTickerFor(reqId);

		if (ticker == null)
			return;

		Stock stock = blackboard.getStock(ticker);
		if (stock == null)
			return;

		if (bidSize != null && !bidSize.isZero())
			stock.setBidSize(bidSize);

		if (askSize != null && !askSize.isZero())
			stock.setAskSize(askSize);

	}

	public void onTickByTickAllLast(int reqId, Decimal size) {
		String ticker = registry.getTickerFor(reqId);
		if (ticker == null)
			return;

		Stock stock = blackboard.getStock(ticker);
		if (stock == null)
			return;

		if (size != null && !size.isZero())
			stock.setLastSize(size);

	}

}