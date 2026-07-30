package mwd.trading.support;

import java.util.Set;

import mwd.trading.config.Config;

public final class TestConfig implements Config {
    private final int maxActivePositions;
    private final long acknowledgementTimeoutMs;
    private final Set<String> universe;

    public TestConfig(int maxActivePositions, long acknowledgementTimeoutMs, Set<String> universe) {
        this.maxActivePositions = maxActivePositions;
        this.acknowledgementTimeoutMs = acknowledgementTimeoutMs;
        this.universe = Set.copyOf(universe);
    }

    @Override public boolean isLiveIBKRData() { return false; }
    @Override public boolean isLiveTrading() { return false; }
    @Override public boolean showUI() { return false; }
    @Override public String getIBKRHost() { return "127.0.0.1"; }
    @Override public int getIBKRPort() { return 7497; }
    @Override public int getIBKRClientId() { return 0; }
    @Override public String getExpectedAccount() { return "DU123456"; }
    @Override public int getMarketDataType() { return 3; }
    @Override public long getStrategyPollRateMs() { return 1; }
    @Override public long getEntryAcknowledgementTimeoutMs() { return acknowledgementTimeoutMs; }
    @Override public int getMaxActivePositions() { return maxActivePositions; }
    @Override public long getReconnectDelayMs() { return 1; }
    @Override public String getTradingStatePath() { return "target/test-state.json"; }
    @Override public Set<String> getStrategyUniverse(String strategyId) { return universe; }
    @Override public Set<String> getStrategyReferenceSymbols(String strategyId) { return Set.of(); }
    @Override public boolean isOptionsProxyEnabled() { return true; }
    @Override public String getOptionsProxyBindHost() { return "127.0.0.1"; }
    @Override public int getOptionsProxyUdpPort() { return 0; }
    @Override public long getOptionsProxyFrameMaxAgeMs() { return 5000; }
    @Override public long getMarketDataMaxAgeMs() { return 30_000; }
    @Override public boolean isEarningsEnabled() { return true; }
    @Override public String getEarningsEndpointUrl() { return "http://127.0.0.1:8000/earnings"; }
    @Override public long getEarningsConnectTimeoutMs() { return 1000; }
    @Override public long getEarningsRequestTimeoutMs() { return 1000; }
    @Override public long getEarningsRetryDelayMs() { return 1000; }
    @Override public String getMarketCalendarEndpointUrl() { return "http://127.0.0.1:8000/calendar"; }
}
