package mwd.trading.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import com.ib.client.Bar;
import com.ib.client.Contract;
import com.ib.client.Decimal;

import mwd.trading.execution.BracketOrder;

public class Stock {
    public enum PositionState {
        FLAT, PENDING, OPEN, CLOSING
    };

    private final AtomicReference<PositionState> state = new AtomicReference<>(PositionState.FLAT);

    public AtomicReference<PositionState> getState() {
        return state;
    }

    private final String ticker;
    private Contract contract;
    private volatile BracketOrder activeBracket;

    private volatile boolean isTradeable = true;

    public boolean isTradeable() {
        return isTradeable;
    }

    public void setTradeable(boolean tradeable) {
        this.isTradeable = tradeable;
        this.refreshLastUpdate();
    }

    public Stock(String ticker) {
        this.ticker = ticker;
        this.lastUpdate = System.currentTimeMillis();
    }

    private volatile long lastUpdate = System.currentTimeMillis();

    private void refreshLastUpdate() {
        long now = System.currentTimeMillis();
        if (now - this.lastUpdate > 1000) {
            this.lastUpdate = now;
        }
    }

    /*
     * Margin Info
     */
    // Written by the IBKR reader thread from what-if responses and read by every
    // strategy thread, so all of these must be volatile like the rest of Stock.
    //
    // The defaults of 1.0 mean "assume 100% margin", which sizes a position as
    // though it were paid for in cash. That is the safe direction, but it is
    // indistinguishable from a measured rate, which is why verification is
    // tracked per direction rather than inferred from the value.
    private volatile double longMarginRate = 1.0;
    private volatile double shortMarginRate = 1.0;
    private volatile boolean longMarginRateVerified = false;
    private volatile boolean shortMarginRateVerified = false;

    /**
     * Whether IBKR has priced a BUY what-if for this symbol.
     *
     * <p>
     * Separate from the short flag because the margin pacer requests the two
     * directions independently. A single combined flag would report a symbol as
     * verified once either arrived, letting the other direction size against an
     * untouched 1.0 default.
     */
    public boolean isLongMarginRateVerified() {
        return longMarginRateVerified;
    }

    /** Whether IBKR has priced a SELL what-if for this symbol. */
    public boolean isShortMarginRateVerified() {
        return shortMarginRateVerified;
    }

    public void setLongMarginRateVerified(boolean verified) {
        this.longMarginRateVerified = verified;
    }

    public void setShortMarginRateVerified(boolean verified) {
        this.shortMarginRateVerified = verified;
    }

    public double calculateMarginRequirement(String action, Decimal qty, double price) {
        double notionalValue = Math.abs(qty.value().doubleValue()) * price;
        double rate = action.equalsIgnoreCase("BUY") ? longMarginRate : shortMarginRate;
        return notionalValue * rate;
    }

    public double getLongMarginRate() {
        return this.longMarginRate;
    }

    public double getShortMarginRate() {
        return this.shortMarginRate;
    }

    public void setLongMarginRate(double rate) {
        this.longMarginRate = rate;
    }

    public void setShortMarginRate(double rate) {
        this.shortMarginRate = rate;
    }

    // Tick Price
    private volatile double lastPrice;
    private volatile double bid;
    private volatile double ask;
    private volatile double markPrice;
    private volatile double dailyHigh;
    private volatile double dailyLow;
    private volatile double open;
    private volatile double previousClose;
    private volatile double dailyVWAP;

    // Moving Averages
    private volatile double sma10, sma20, sma50, sma100, sma200;

    // Tick Size
    private volatile Decimal bidSize = Decimal.ZERO;
    private volatile Decimal askSize = Decimal.ZERO;
    private volatile Decimal lastSize = Decimal.ZERO;

    // Volume
    private volatile Decimal intradayVolume = Decimal.ZERO;
    private volatile Decimal lastMinuteVolume = Decimal.ZERO;
    // Mean of the last 15 completed minute bars of the current session, kept by
    // MinuteVolumeTracker. Zero means the window is not yet full, never "no volume".
    private volatile Decimal averageLast15MinuteVolume = Decimal.ZERO;
    // IBKR tick 21: the 90-day average DAILY volume. Reference only; it shares no
    // time base with the two figures above and must not be compared against them.
    private volatile Decimal averageDailyVolume = Decimal.ZERO;

    // Gamma Flip
    private volatile double gammaFlip;

    // Implied Move
    private volatile double dailyImpliedMove;
    private volatile double weeklyImpliedMove;
    private volatile double monthlyImpliedMove;

    // Average True Range
    private volatile double intradayATR4;
    private volatile double intradayATR5;
    private volatile double intradayATR12;
    private volatile double intradayATR15;
    private volatile double dailyATR;

    // Relative Strength Index
    private volatile double relativeStrengthIndex;

    // Portfolio
    private volatile Decimal positionSize = Decimal.ZERO;
    private volatile double averageCost = 0.0;
    private volatile double marketValue = 0.0;
    private volatile double realizedPnL = 0.0;
    private volatile double unrealizedPnl = 0.0;

    // Earnings. Display only: the monitor reads this, strategies read
    // EarningsStore, which carries the session and validity this does not.
    private Instant nextEarningsDate;

    // Last Minute Bar
    private volatile Bar lastMinuteBar;

    public void setContract(Contract contract) {
        this.contract = contract;
    }

    public void setActiveBracket(BracketOrder bracket) {
        this.activeBracket = bracket;
    }

    public void setLastPrice(double lastPrice) {
        this.lastPrice = lastPrice;
        this.refreshLastUpdate();
    }

    public void setBid(double bid) {
        this.bid = bid;
        this.refreshLastUpdate();
    }

    public void setAsk(double ask) {
        this.ask = ask;
        this.refreshLastUpdate();
    }

    public void setMarkPrice(double markPrice) {
        this.markPrice = markPrice;
        this.refreshLastUpdate();
    }

    public void setDailyHigh(double dailyHigh) {
        this.dailyHigh = dailyHigh;
        this.refreshLastUpdate();
    }

    public void setDailyLow(double dailyLow) {
        this.dailyLow = dailyLow;
        this.refreshLastUpdate();
    }

    public void setOpen(double open) {
        this.open = open;
        this.refreshLastUpdate();
    }

    public void setPreviousClose(double previousClose) {
        this.previousClose = previousClose;
        this.refreshLastUpdate();
    }

    public void setDailyVWAP(double dailyVWAP) {
        this.dailyVWAP = dailyVWAP;
        this.refreshLastUpdate();
    }

    public void setSma10(double sma10) {
        this.sma10 = sma10;
        this.refreshLastUpdate();
    }

    public void setSma20(double sma20) {
        this.sma20 = sma20;
        this.refreshLastUpdate();
    }

    public void setSma50(double sma50) {
        this.sma50 = sma50;
        this.refreshLastUpdate();
    }

    public void setSma100(double sma100) {
        this.sma100 = sma100;
        this.refreshLastUpdate();
    }

    public void setSma200(double sma200) {
        this.sma200 = sma200;
        this.refreshLastUpdate();
    }

    public void setBidSize(Decimal bidSize) {
        this.bidSize = bidSize;
        this.refreshLastUpdate();
    }

    public void setAskSize(Decimal askSize) {
        this.askSize = askSize;
        this.refreshLastUpdate();
    }

    public void setLastSize(Decimal lastSize) {
        this.lastSize = lastSize;
        this.refreshLastUpdate();
    }

    public void setIntradayVolume(Decimal intradayVolume) {
        this.intradayVolume = intradayVolume;
        this.refreshLastUpdate();
    }

    public void setLastMinuteVolume(Decimal lastMinuteVolume) {
        this.lastMinuteVolume = lastMinuteVolume;
        this.refreshLastUpdate();
    }

    public void setAverageLast15MinuteVolume(Decimal averageLast15MinuteVolume) {
        this.averageLast15MinuteVolume = averageLast15MinuteVolume;
        this.refreshLastUpdate();
    }

    public void setAverageDailyVolume(Decimal averageDailyVolume) {
        this.averageDailyVolume = averageDailyVolume;
        this.refreshLastUpdate();
    }

    public void setGammaFlip(double gammaFlip) {
        this.gammaFlip = gammaFlip;
        this.refreshLastUpdate();
    }

    public void setDailyImpliedMove(double dailyImpliedMove) {
        this.dailyImpliedMove = dailyImpliedMove;
        this.refreshLastUpdate();
    }

    public void setWeeklyImpliedMove(double weeklyImpliedMove) {
        this.weeklyImpliedMove = weeklyImpliedMove;
        this.refreshLastUpdate();
    }

    public void setMonthlyImpliedMove(double monthlyImpliedMove) {
        this.monthlyImpliedMove = monthlyImpliedMove;
        this.refreshLastUpdate();
    }

    public void setIntradayATR4(double intradayATR4) {
        this.intradayATR4 = intradayATR4;
        this.refreshLastUpdate();
    }

    public void setIntradayATR5(double intradayATR5) {
        this.intradayATR5 = intradayATR5;
        this.refreshLastUpdate();
    }

    public void setIntradayATR12(double intradayATR12) {
        this.intradayATR12 = intradayATR12;
        this.refreshLastUpdate();
    }

    public void setIntradayATR15(double intradayATR15) {
        this.intradayATR15 = intradayATR15;
        this.refreshLastUpdate();
    }

    public void setDailyATR(double dailyATR) {
        this.dailyATR = dailyATR;
        this.refreshLastUpdate();
    }

    public void setRSI(double relativeStrengthIndex) {
        this.relativeStrengthIndex = relativeStrengthIndex;
        this.refreshLastUpdate();
    }

    public void setAverageCost(double averageCost) {
        this.averageCost = averageCost;
    }

    public void setPositionSize(Decimal positionSize) {
        this.positionSize = positionSize;
    }

    public void setMarketValue(double marketValue) {
        this.marketValue = marketValue;
    }

    public void setRealizedPnL(double realizedPnL) {
        this.realizedPnL = realizedPnL;
    }

    public void setUnrealizedPnl(double unrealizedPnl) {
        this.unrealizedPnl = unrealizedPnl;
    }

    public void setNextEarningsDate(Instant nextEarningsDate) {
        this.nextEarningsDate = nextEarningsDate;
    }


    public void setLastMinuteBar(Bar lastMinuteBar) {
        this.lastMinuteBar = lastMinuteBar;
    }

    public String getTicker() {
        return ticker;
    }

    public Contract getContract() {
        return contract;
    }

    public long getLastUpdate() {
        return lastUpdate;
    }

    public BracketOrder getActiveBracket() {
        return this.activeBracket;
    }

    public double getLastPrice() {
        return lastPrice;
    }

    public double getBid() {
        return bid;
    }

    public double getAsk() {
        return ask;
    }

    public double getMarkPrice() {
        return markPrice;
    }

    public double getDailyHigh() {
        return dailyHigh;
    }

    public double getDailyLow() {
        return dailyLow;
    }

    public double getOpen() {
        return open;
    }

    public double getPreviousClose() {
        return previousClose;
    }

    public double getDailyVWAP() {
        return dailyVWAP;
    }

    public double getSma10() {
        return sma10;
    }

    public double getSma20() {
        return sma20;
    }

    public double getSma50() {
        return sma50;
    }

    public double getSma100() {
        return sma100;
    }

    public double getSma200() {
        return sma200;
    }

    public Decimal getBidSize() {
        return bidSize;
    }

    public Decimal getAskSize() {
        return askSize;
    }

    public Decimal getLastSize() {
        return lastSize;
    }

    public Decimal getIntradayVolume() {
        return intradayVolume;
    }

    public Decimal getLastMinuteVolume() {
        return lastMinuteVolume;
    }

    public Decimal getAverageLast15MinuteVolume() {
        return averageLast15MinuteVolume;
    }

    public Decimal getAverageDailyVolume() {
        return averageDailyVolume;
    }

    public double getGammaFlip() {
        return gammaFlip;
    }

    public double getDailyImpliedMove() {
        return dailyImpliedMove;
    }

    public double getWeeklyImpliedMove() {
        return weeklyImpliedMove;
    }

    public double getMonthlyImpliedMove() {
        return monthlyImpliedMove;
    }

    public double getIntradayATR4() {
        return intradayATR4;
    }

    public double getIntradayATR5() {
        return intradayATR5;
    }

    public double getIntradayATR12() {
        return intradayATR12;
    }

    public double getIntradayATR15() {
        return intradayATR15;
    }

    public double getDailyATR() {
        return dailyATR;
    }

    public double getRSI() {
        return relativeStrengthIndex;
    }

    public Decimal getPositionSize() {
        return positionSize;
    }

    public double getAverageCost() {
        return averageCost;
    }

    public double getMarketValue() {
        return marketValue;
    }

    public double getRealizedPnL() {
        return realizedPnL;
    }

    public double getUnrealizedPnl() {
        return unrealizedPnl;
    }

    public Instant getNextEarningsDate() {
        return nextEarningsDate;
    }


    public Bar getLastMinuteBar() {
        return lastMinuteBar;
    }

    @Override
    public String toString() {
        String timeStr = Instant.ofEpochMilli(lastUpdate)
                .atZone(ZoneId.of("America/New_York"))
                .toLocalTime().toString().substring(0, 8);

        return String.format(
                "[%s] %-5s | Price: %8.2f | VWAP: %8.2f | SMA200: %8.2f%n" +
                        "       Vols: [DailyATR: %.2f | ATR5: %.2f | Implied: %.2f]%n" +
                        "       Levels: [G-Flip: %.2f | Vol: %s | AvgVol: %s]",
                timeStr,
                ticker,
                lastPrice,
                dailyVWAP,
                sma200,
                dailyATR,
                intradayATR5,
                dailyImpliedMove,
                gammaFlip,
                intradayVolume.toString(),
                averageLast15MinuteVolume.toString());
    }
}
