package mwd.trading.broker.ibkr;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class RequestRegistry {
    private static final Logger logger = LogManager.getLogger(RequestRegistry.class);
    public enum DataConsumer {
    	ATR_MINUTE_WILDERS,
    	ATR_DAILY_WILDERS,
    	ERROR,
    	MOVING_AVERAGE,
    	NEXT_VALID_ID,
    	RSI,
    	TICK_BAR,
    	TICK_PRICE,
    	TICK_SIZE,
    	VOLUME,
    	VWAP
    }
    private static record ReqData(String ticker, EnumSet<DataConsumer> consumers) {}

    private final Map<Integer, ReqData> idToData = new ConcurrentHashMap<>();

    public void register(int reqId, String ticker, DataConsumer... consumers) {
        EnumSet<DataConsumer> consumerSet = EnumSet.noneOf(DataConsumer.class);
        for (DataConsumer consumer : consumers)
            consumerSet.add(consumer);
        idToData.put(reqId, new ReqData(ticker, consumerSet));
    }

    public String getTickerFor(int reqId) {
    	ReqData reqData = idToData.get(reqId);
    	if (reqData == null) {
    		logger.debug(">>> Registry.getTicker: Ticker for reqId {} not found", reqId);
    		return null;
    	}
    	return reqData.ticker();
    }

    public List<String> getAllTickers() {
    	return idToData.values().stream().map(ReqData::ticker).distinct().toList();
    }

    public EnumSet<DataConsumer> getConsumersFor(int reqId) {
    	ReqData reqData = idToData.get(reqId);
    	if (reqData == null)
            return EnumSet.noneOf(DataConsumer.class);
        return reqData.consumers();
    }

    public void unregister(int reqId) {
        idToData.remove(reqId);
    }

}