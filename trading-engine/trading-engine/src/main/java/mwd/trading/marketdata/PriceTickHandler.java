package mwd.trading.marketdata;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.ib.client.TickAttrib;
import com.ib.client.TickType;

import mwd.trading.state.StockLookup;
import mwd.trading.domain.Stock;
import mwd.trading.broker.ibkr.RequestRegistry;
import mwd.trading.broker.ibkr.TickMap;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class PriceTickHandler {
    private static final Logger logger = LogManager.getLogger(PriceTickHandler.class);
    private final StockLookup stocks;
    private final RequestRegistry registry;
    private final TickMap tickMap;
    private final MarketDataInputStore inputStore;
    /** Symbols whose first RTVolume payload has already been logged. */
    private final Set<String> formatConfirmed = ConcurrentHashMap.newKeySet();

    public PriceTickHandler(
            StockLookup stocks,
            RequestRegistry registry,
            TickMap tickMap,
            MarketDataInputStore inputStore) {
        this.stocks = stocks;
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

        Stock stock = stocks.getStock(ticker);

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
        // VWAP is absent from this switch on purpose: IBKR sends no VWAP price
        // tick. It arrives as a string tick, handled by onTickString below.
    }

    /**
     * Reads the session VWAP out of IBKR's {@code RT_VOLUME} tick.
     *
     * <p>
     * VWAP is the one quoted value IBKR does not publish as a price tick. It
     * rides inside {@code RT_VOLUME} (tick type 48), a semicolon-delimited
     * string enabled by generic tick {@code 233}, which
     * {@code requestLiveMarketData} already requests:
     *
     * <pre>
     *     price;size;time;totalVolume;VWAP;singleTradeFlag
     * </pre>
     *
     * <p>
     * There is no delayed equivalent — the delayed tick family runs 66
     * {@code DELAYED_BID} through 76 {@code DELAYED_OPEN} — so this arrives only
     * under a real-time subscription. Without one, {@code DAILY_VWAP} is never
     * recorded and every strategy stays gated out of entry, which is the correct
     * conservative outcome for a value that genuinely is not being received.
     */
    public void onTickString(int reqId, int tickType, String value) {
        if (tickType != TickType.RT_VOLUME.index()) return;
        // IBKR sends an empty payload when nothing has traded yet.
        if (value == null || value.isBlank()) return;

        String ticker = registry.getTickerFor(reqId);
        if (ticker == null) return;

        double vwap = parseRealTimeVolumeVwap(value);
        if (vwap <= 0 || !Double.isFinite(vwap)) {
            logger.debug("[{}] RTVolume payload '{}' carried no usable VWAP", ticker, value);
            return;
        }

        Stock stock = stocks.getStock(ticker);
        stock.setDailyVWAP(vwap);
        inputStore.record(ticker, MarketDataInput.DAILY_VWAP);

        if (formatConfirmed.add(ticker)) {
            // One line per symbol per session. The field order above comes from
            // IBKR's documentation rather than the JavaClient source, so this
            // makes it a five-second check against the first real session
            // instead of an assumption nobody ever revisits.
            logger.info("[{}] First RTVolume tick accepted. Raw '{}' parsed to VWAP {}",
                    ticker, value, vwap);
        }
    }

    /** Returns the VWAP field, or {@code NaN} when the payload is not the shape expected. */
    static double parseRealTimeVolumeVwap(String payload) {
        String[] parts = payload.split(";", -1);
        if (parts.length < 5) return Double.NaN;

        String field = parts[4].trim();
        if (field.isEmpty()) return Double.NaN;

        try {
            return Double.parseDouble(field);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }


	public void onTickByTickBidAsk(int reqId, double bidPrice, double askPrice) {
		String ticker = registry.getTickerFor(reqId);
		
		if (ticker == null)
			return;

		Stock stock = stocks.getStock(ticker);
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

		Stock stock = stocks.getStock(ticker);
		if (stock == null)
			return;

		if (price > 0 && Double.isFinite(price)) {
			stock.setLastPrice(price);
			inputStore.record(ticker, MarketDataInput.LAST_PRICE);
		}
	}
	
}