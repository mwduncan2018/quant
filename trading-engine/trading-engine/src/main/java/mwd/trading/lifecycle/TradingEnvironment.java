package mwd.trading.lifecycle;

/** The broker environment that receives orders from this process. */
public enum TradingEnvironment {
    PAPER,
    LIVE;

    public static TradingEnvironment fromLiveTrading(boolean liveTrading) {
        return liveTrading ? LIVE : PAPER;
    }
}
