package mwd.trading.marketdata;

public interface TickStreamController {
    boolean isStreamActive(String ticker);

    void tryRequestStream(String ticker);

    void cancelStream(String ticker);
}
