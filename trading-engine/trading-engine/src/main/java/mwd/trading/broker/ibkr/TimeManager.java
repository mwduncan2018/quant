package mwd.trading.broker.ibkr;

public class TimeManager {
    // Written on the IBKR reader thread by BrokerTimeHandler.onCurrentTime and
    // read from the strategy threads and the Swing EDT.
    private volatile long offsetMillis = 0;

    public void setOffset(long serverTimeSeconds, long localTimeMillis) {
        // IBKR returns time in seconds; convert to milliseconds
        long serverTimeMillis = serverTimeSeconds * 1000;
        this.offsetMillis = serverTimeMillis - localTimeMillis;
    }

    public long getMarketTime() {
        return System.currentTimeMillis() + offsetMillis;
    }
}