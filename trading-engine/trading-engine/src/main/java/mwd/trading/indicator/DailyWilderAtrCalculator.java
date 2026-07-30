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

public class DailyWilderAtrCalculator {
    private static final Logger logger = LogManager.getLogger(DailyWilderAtrCalculator.class);
    private final StockLookup stocks;
    private final RequestRegistry registry;

    private final Map<Integer, List<Bar>> historyBuffer = new ConcurrentHashMap<>();
    private final int period = 14;

    public DailyWilderAtrCalculator(StockLookup stocks, RequestRegistry registry) {
        this.stocks = stocks;
        this.registry = registry;
    }

    public void onHistoricalData(int reqId, Bar bar) {
        historyBuffer.computeIfAbsent(reqId, k -> Collections.synchronizedList(new ArrayList<>()))
                     .add(bar);
    }

    public void onHistoricalDataEnd(int reqId, String startDate, String endDate) {
        List<Bar> bars = historyBuffer.remove(reqId); 
        
        if (bars == null || bars.size() <= (period + 1)) {
            logger.error("Insufficient daily data for ATR calculation. ID: {}", reqId);
            return;
        }

        String ticker = registry.getTickerFor(reqId);
        if (ticker != null) {
            double dailyATR = calculateWildersATR(bars);
            stocks.getStock(ticker).setDailyATR(dailyATR);
            
            logger.info("[{}] Daily ATR (Regime) Seeded: {}. Range: {} to {}", 
                        ticker, dailyATR, startDate, endDate);
        }
    }

    private double calculateWildersATR(List<Bar> bars) {
        double trSum = 0;
        for (int i = 1; i <= period; i++) {
            trSum += calculateTrueRange(bars.get(i), bars.get(i - 1).close());
        }
        
        double currentATR = trSum / period;
        
        for (int i = period + 1; i < bars.size(); i++) {
            double tr = calculateTrueRange(bars.get(i), bars.get(i - 1).close());
            currentATR = ((currentATR * (period - 1)) + tr) / period;
        }
        return currentATR;
    }

    private double calculateTrueRange(Bar current, double prevClose) {
        double hl = current.high() - current.low();
        double hpc = Math.abs(current.high() - prevClose);
        double lpc = Math.abs(current.low() - prevClose);
        return Math.max(hl, Math.max(hpc, lpc));
    }
    
}