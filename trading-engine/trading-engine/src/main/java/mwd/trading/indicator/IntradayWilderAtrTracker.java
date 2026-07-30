package mwd.trading.indicator;

import com.ib.client.Bar;
import com.ib.client.Decimal;
import mwd.trading.broker.ibkr.RequestRegistry;
import mwd.trading.state.StockLookup;
import mwd.trading.domain.Stock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class IntradayWilderAtrTracker {
    private static final Logger logger = 
        LogManager.getLogger(IntradayWilderAtrTracker.class);
    private final StockLookup stocks;
    private final RequestRegistry registry;
    private final Map<Integer, List<Bar>> historyBuffer = 
        new ConcurrentHashMap<>();
    private final int period = 14;

    public IntradayWilderAtrTracker(StockLookup stocks, 
                                   RequestRegistry registry) {
        this.stocks = stocks;
        this.registry = registry;
    }

    public void onHistoricalData(int requestId, Bar bar) {
        historyBuffer.computeIfAbsent(requestId, 
            k -> Collections.synchronizedList(new ArrayList<>())).add(bar);
    }

    public void onHistoricalDataEnd(int requestId, String start, 
                                  String end) {
        processUpdate(requestId, "Initialized", start, end);
    }

    public void onHistoricalDataUpdate(int requestId, Bar bar) {
        List<Bar> bars = historyBuffer.get(requestId);
        if (bars == null) return;

        if (isNewBar(bars, bar)) {
            synchronized (bars) {
                bars.add(bar);
                if (bars.size() > 1500) {
                    bars.remove(0);
                }
                processUpdate(requestId, "Updated", null, null);
            }
        }
    }

    private void processUpdate(int requestId, String action, 
                              String start, String end) {
        List<Bar> minuteBars = historyBuffer.get(requestId);
        String ticker = registry.getTickerFor(requestId);
        if (ticker == null || minuteBars == null) return;

        Stock stock = stocks.getStock(ticker);

        // Calculate and update each required ATR factor
        updateATR(stock, minuteBars, 4, action, start, end);
        updateATR(stock, minuteBars, 5, action, start, end);
        updateATR(stock, minuteBars, 12, action, start, end);
        updateATR(stock, minuteBars, 15, action, start, end);
    }

    private void updateATR(Stock stock, List<Bar> minuteBars, 
                          int factor, String action, 
                          String start, String end) {
        int required = (period + 1) * factor;
        if (minuteBars.size() < required) return;

        List<Bar> virtualBars = createVirtualBars(minuteBars, factor);
        double finalATR = calculateWildersATR(virtualBars);

        switch (factor) {
            case 4 -> stock.setIntradayATR4(finalATR);
            case 5 -> stock.setIntradayATR5(finalATR);
            case 12 -> stock.setIntradayATR12(finalATR);
            case 15 -> stock.setIntradayATR15(finalATR);
        }

        if (start != null) {
            logger.info("[{}] ATR{} {}: {}. Range: {} to {}", 
                stock.getTicker(), factor, action, finalATR, start, end);
        }
    }

    private List<Bar> createVirtualBars(List<Bar> minuteBars, 
                                       int factor) {
        List<Bar> virtualBars = new ArrayList<>();
        for (int i = 0; i <= minuteBars.size() - factor; i++) {
            virtualBars.add(aggregate(minuteBars.subList(i, i + factor)));
        }
        return virtualBars;
    }

    private Bar aggregate(List<Bar> window) {
        double high = window.stream().mapToDouble(Bar::high)
                           .max().orElse(0);
        double low = window.stream().mapToDouble(Bar::low)
                          .min().orElse(0);
        double open = window.get(0).open();
        double close = window.get(window.size() - 1).close();
        return new Bar(window.get(window.size() - 1).time(), 
                      open, high, low, close, Decimal.ZERO, 0, 
                      Decimal.ZERO);
    }

    private boolean isNewBar(List<Bar> bars, Bar newBar) {
        if (bars.isEmpty()) return true;
        return !bars.get(bars.size() - 1).time().equals(newBar.time());
    }

    private double calculateWildersATR(List<Bar> bars) {
        double trueRangeSum = 0;
        for (int i = 1; i <= period; i++) {
            trueRangeSum += calculateTrueRange(bars.get(i), 
                                             bars.get(i - 1).close());
        }
        double currentATR = trueRangeSum / period;
        for (int i = period + 1; i < bars.size(); i++) {
            double trueRange = calculateTrueRange(bars.get(i), 
                                                bars.get(i - 1).close());
            currentATR = ((currentATR * (period - 1)) + trueRange) 
                        / period;
        }
        return currentATR;
    }

    private double calculateTrueRange(Bar current, double previousClose) {
        double hl = current.high() - current.low();
        double hpc = Math.abs(current.high() - previousClose);
        double lpc = Math.abs(current.low() - previousClose);
        return Math.max(hl, Math.max(hpc, lpc));
    }
}