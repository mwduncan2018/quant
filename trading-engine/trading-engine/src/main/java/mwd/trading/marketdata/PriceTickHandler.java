package mwd.trading.marketdata;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.ib.client.TickAttrib;

import mwd.trading.state.Blackboard;
import mwd.trading.domain.Stock;
import mwd.trading.broker.ibkr.RequestRegistry;
import mwd.trading.broker.ibkr.TickMap;

public class PriceTickHandler {
    private static final Logger logger = LogManager.getLogger(PriceTickHandler.class);
    private final Blackboard blackboard;
    private final RequestRegistry registry;
    private final TickMap tickMap;
    private final MarketDataInputStore inputStore;

    public PriceTickHandler(
            Blackboard blackboard,
            RequestRegistry registry,
            TickMap tickMap,
            MarketDataInputStore inputStore) {
        this.blackboard = blackboard;
        this.registry = registry;
        this.tickMap = tickMap;
        this.inputStore = inputStore;
    }

    public void onTickPrice(int reqId, int field, double price, TickAttrib attribs) {
        // A non-positive price is IBKR reporting no value, not a price of zero.
        // Rejecting it here is what makes a recorded input mean the stored value
        // is usable.
        if (price <= 0 || !Double.isFinite(price)) return;

        String ticker = registry.getTickerFor(reqId);
        if (ticker == null) return;

        Stock stock = blackboard.getStock(ticker);

        if (tickMap.isBid(field)) {
            stock.setBid(price);
        } else if (tickMap.isAsk(field)) {
            stock.setAsk(price);
        } else if (tickMap.isLast(field)) {
            stock.setLastPrice(price);
            inputStore.record(ticker, MarketDataInput.LAST_PRICE);
        } else if (tickMap.isMarkPrice(field)) {
            stock.setMarkPrice(price);
        } else if (tickMap.isOpen(field)) {
            stock.setOpen(price);
        } else if (tickMap.isClose(field)) {
            stock.setPreviousClose(price);
            inputStore.record(ticker, MarketDataInput.PREVIOUS_CLOSE);
        } else if (tickMap.isHigh(field)) {
            stock.setDailyHigh(price);
        } else if (tickMap.isLow(field)) {
            stock.setDailyLow(price);
        }
        // VWAP is deliberately absent here. IBKR sends no VWAP price tick: the
        // figure travels inside RT_VOLUME (tick 48, a string tick) and has no
        // delayed equivalent at all. DailyVwapTracker derives it from the minute
        // bars instead, which works identically on live and delayed data.
    }


	public void onTickByTickBidAsk(int reqId, double bidPrice, double askPrice) {
		String ticker = registry.getTickerFor(reqId);
		
		if (ticker == null)
			return;

		Stock stock = blackboard.getStock(ticker);
		if (stock == null)
			return;

		if (bidPrice > 0)
			stock.setBid(bidPrice);
		
		if (askPrice > 0)
			stock.setAsk(askPrice);

	}

	public void onTickByTickAllLast(int reqId, double price) {
		String ticker = registry.getTickerFor(reqId);

		if (ticker == null)
			return;

		Stock stock = blackboard.getStock(ticker);
		if (stock == null)
			return;

		if (price > 0 && Double.isFinite(price)) {
			stock.setLastPrice(price);
			inputStore.record(ticker, MarketDataInput.LAST_PRICE);
		}
	}
	
}