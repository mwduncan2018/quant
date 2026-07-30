package mwd.trading.config;

import java.util.Set;

public interface Config {
	/**
	 * Whether market data is real-time. Governs the IBKR market-data type and the
	 * tick field numbering only; it does not decide which account is traded.
	 */
	boolean isLiveIBKRData();

	/**
	 * Whether orders go to the LIVE account. Governs the TWS port and the default
	 * trading-state path. Deliberately separate from {@link #isLiveIBKRData()} so
	 * that enabling a real-time subscription on the PAPER engine cannot also
	 * redirect its order flow.
	 */
	boolean isLiveTrading();
	boolean showUI();
	String getIBKRHost();
	int getIBKRPort();
	int getIBKRClientId();
	String getExpectedAccount();
	int getMarketDataType();
	long getStrategyPollRateMs();
	long getEntryAcknowledgementTimeoutMs();
	int getMaxActivePositions();
	long getReconnectDelayMs();
	String getTradingStatePath();
	Set<String> getStrategyUniverse(String strategyId);
	Set<String> getStrategyReferenceSymbols(String strategyId);

	/** Whether the UDP listener for the Python options proxy is started. */
	boolean isOptionsProxyEnabled();

	/** Local address the options-proxy UDP socket binds to. */
	String getOptionsProxyBindHost();

	/** Local UDP port the options proxy broadcasts to. */
	int getOptionsProxyUdpPort();

	/** Maximum age of the newest accepted proxy frame before new entries are blocked. */
	long getOptionsProxyFrameMaxAgeMs();

	/**
	 * Maximum age of an aged IBKR market-data input before it stops being usable.
	 * It must exceed the slowest input's delivery cadence, which is the one-minute
	 * bar stream.
	 */
	long getMarketDataMaxAgeMs();

	/** Whether the engine pulls earnings dates from the options proxy. */
	boolean isEarningsEnabled();

	/** Full URL of the proxy's earnings endpoint. */
	String getEarningsEndpointUrl();

	/** Connect timeout for an earnings request. */
	long getEarningsConnectTimeoutMs();

	/** Read timeout for an earnings request. */
	long getEarningsRequestTimeoutMs();

	/** Delay before retrying after a failed earnings refresh. */
	long getEarningsRetryDelayMs();

	/** Full URL of the proxy's market-calendar endpoint. */
	String getMarketCalendarEndpointUrl();
}
