package mwd.trading.config;

import java.io.InputStream;
import java.util.Collections;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

public class EnvPropConfig implements Config {
	private boolean isLive = false;
	private boolean isLiveTrading = false;
	private boolean showUI = true;
	private long strategyPollRateMs = 16;
	private long entryAcknowledgementTimeoutMs = 10000;
	private int maxActivePositions = 3;
	private String ibkrHost = "127.0.0.1";
	private int ibkrClientId = 0;
	private String expectedAccount = "";
	private long reconnectDelayMs = 5000;
	private String tradingStatePath;
	private boolean optionsProxyEnabled = true;
	private String optionsProxyBindHost = "127.0.0.1";
	private int optionsProxyUdpPort = 5005;
	private long optionsProxyFrameMaxAgeMs = 5000;
	private long marketDataMaxAgeMs = 30000;
	private boolean earningsEnabled = true;
	private String earningsEndpointUrl = "http://127.0.0.1:8000/earnings";
	private long earningsConnectTimeoutMs = 3000;
	private long earningsRequestTimeoutMs = 5000;
	private long earningsRetryDelayMs = 30000;
	private String marketCalendarEndpointUrl = "http://127.0.0.1:8000/calendar";
	private final Properties properties = new Properties();
	
	public EnvPropConfig() {
		this.initialize();
	}

	private void initialize() {
		// 1. Load properties from file as a baseline
		try (InputStream input = getClass().getClassLoader()
				.getResourceAsStream("config.properties")) {
			if (input != null) {
				properties.load(input);
			}
		} catch (Exception e) {
			System.out.println("Config Error: " + e.getMessage());
		}

		// 2. Initialize 'isLive'
		String envLive = System.getenv("LIVE_IBKR_DATA");
		if (envLive != null && !envLive.isEmpty()) {
			this.isLive = Boolean.parseBoolean(envLive);
		} else {
			this.isLive = Boolean.parseBoolean(properties
					.getProperty("LIVE_IBKR_DATA", "false"));
		}

		// 2b. Initialize 'isLiveTrading'
		//
		// Split from LIVE_IBKR_DATA on purpose. One flag used to select the TWS
		// port, the journal path, the market-data type, and the tick field
		// numbering at once, so turning on a real-time subscription for the PAPER
		// engine would also have pointed it at the LIVE port and the LIVE journal.
		String envLiveTrading = System.getenv("LIVE_IBKR_TRADING");
		if (envLiveTrading != null && !envLiveTrading.isEmpty()) {
			this.isLiveTrading = Boolean.parseBoolean(envLiveTrading);
		} else {
			this.isLiveTrading = Boolean.parseBoolean(properties
					.getProperty("LIVE_IBKR_TRADING", "false"));
		}

		requireCoherentDataAndTradingPair(this.isLive, this.isLiveTrading);

		// 3. Initialize 'showUI'
		String envUI = System.getenv("SHOW_UI");
		if (envUI != null && !envUI.isEmpty()) {
			this.showUI = Boolean.parseBoolean(envUI);
		} else {
			this.showUI = Boolean.parseBoolean(properties
					.getProperty("SHOW_UI", "true"));
		}
		
		// 4. Initialize 'strategyPollRateMs'
		String envPoll = System.getenv("STRATEGY_POLL_RATE_MS");
		if (envPoll != null && !envPoll.isEmpty()) {
			this.strategyPollRateMs = Long.parseLong(envPoll);
		} else {
			this.strategyPollRateMs = Long.parseLong(properties
					.getProperty("STRATEGY_POLL_RATE_MS", "16"));
		}
		this.entryAcknowledgementTimeoutMs = Long.parseLong(
				value("ENTRY_ACKNOWLEDGEMENT_TIMEOUT_MS", properties, "10000"));

		// 5. Initialize 'maxActivePositions'
		String envMaxPos = System.getenv("MAX_ACTIVE_POSITIONS");
		if (envMaxPos != null && !envMaxPos.isEmpty()) {
			this.maxActivePositions = Integer.parseInt(envMaxPos);
		} else {
			this.maxActivePositions = Integer.parseInt(properties
					.getProperty("MAX_ACTIVE_POSITIONS", "3"));
		}

		this.ibkrHost = value("IBKR_HOST", properties, "127.0.0.1");
		this.ibkrClientId = Integer.parseInt(value("IBKR_CLIENT_ID", properties, "0"));
		this.expectedAccount = value("IBKR_EXPECTED_ACCOUNT", properties, "").trim();
		this.reconnectDelayMs = Long.parseLong(value("IBKR_RECONNECT_DELAY_MS", properties, "5000"));
		String defaultStatePath = this.isLiveTrading
				? "data/trading-state-live.json"
				: "data/trading-state-paper.json";
		this.tradingStatePath = value("TRADING_STATE_PATH", properties, defaultStatePath);

		// 6. Initialize the Python options-proxy UDP listener
		this.optionsProxyEnabled = Boolean.parseBoolean(
				value("OPTIONS_PROXY_ENABLED", properties, "true"));
		this.optionsProxyBindHost = value("OPTIONS_PROXY_BIND_HOST", properties, "127.0.0.1").trim();
		this.optionsProxyUdpPort = Integer.parseInt(
				value("OPTIONS_PROXY_UDP_PORT", properties, "5005"));
		this.optionsProxyFrameMaxAgeMs = Long.parseLong(
				value("OPTIONS_PROXY_FRAME_MAX_AGE_MS", properties, "5000"));
		this.marketDataMaxAgeMs = Long.parseLong(
				value("MARKET_DATA_MAX_AGE_MS", properties, "30000"));

		// 7. Initialize the options-proxy earnings endpoint
		this.earningsEnabled = Boolean.parseBoolean(
				value("EARNINGS_ENABLED", properties, "true"));
		this.earningsEndpointUrl =
				value("EARNINGS_ENDPOINT_URL", properties, "http://127.0.0.1:8000/earnings").trim();
		this.earningsConnectTimeoutMs = Long.parseLong(
				value("EARNINGS_CONNECT_TIMEOUT_MS", properties, "3000"));
		this.earningsRequestTimeoutMs = Long.parseLong(
				value("EARNINGS_REQUEST_TIMEOUT_MS", properties, "5000"));
		this.earningsRetryDelayMs = Long.parseLong(
				value("EARNINGS_RETRY_DELAY_MS", properties, "30000"));

		// 8. Initialize the options-proxy market-calendar endpoint. It shares the
		// earnings timeouts and retry delay; both are the same local service.
		this.marketCalendarEndpointUrl = value(
				"MARKET_CALENDAR_ENDPOINT_URL", properties, "http://127.0.0.1:8000/calendar").trim();
	}

	/**
	 * Rejects the one pairing the split makes expressible: trading the LIVE
	 * account against delayed market data. Every other combination is legitimate —
	 * PAPER on delayed, PAPER on real-time, LIVE on real-time — but pricing real
	 * orders off a quote that is fifteen minutes old is not a configuration
	 * anyone means to write, and it is far better caught at startup than
	 * discovered by a fill.
	 */
	static void requireCoherentDataAndTradingPair(boolean liveData, boolean liveTrading) {
		if (liveTrading && !liveData) {
			throw new IllegalStateException(
					"LIVE_IBKR_TRADING is true while LIVE_IBKR_DATA is false: "
					+ "the engine will not trade the live account on delayed market data");
		}
	}

	private static String value(String key, Properties properties, String defaultValue) {
		String environmentValue = System.getenv(key);
		if (environmentValue != null && !environmentValue.isBlank()) {
			return environmentValue;
		}
		return properties.getProperty(key, defaultValue);
	}

	private Set<String> symbolsFor(String strategyId, String suffix) {
		String normalizedId = strategyId.trim().toUpperCase(Locale.ROOT)
				.replaceAll("[^A-Z0-9]+", "_");
		String raw = value("STRATEGY_" + normalizedId + "_" + suffix, properties, "");
		Set<String> symbols = new TreeSet<>();
		for (String symbol : raw.split(",")) {
			String normalizedSymbol = symbol.trim().toUpperCase(Locale.ROOT);
			if (!normalizedSymbol.isEmpty()) {
				symbols.add(normalizedSymbol);
			}
		}
		return Collections.unmodifiableSet(symbols);
	}

	@Override
	public boolean showUI() {
		return this.showUI;
	}

	@Override
	public String getIBKRHost() {
		return this.ibkrHost;
	}

	@Override
	public int getIBKRPort() {
		// 7496 = Live
		// 7497 = Paper
		return this.isLiveTrading ? 7496 : 7497;
	}

	@Override
	public int getIBKRClientId() {
		return this.ibkrClientId;
	}

	@Override
	public String getExpectedAccount() {
		return this.expectedAccount;
	}

	@Override
	public int getMarketDataType() {
		// LIVE = 1
		// DELAYED = 3
		return this.isLive ? 1 : 3;
	}

	@Override
	public boolean isLiveIBKRData() {
		return this.isLive;
	}

	@Override
	public boolean isLiveTrading() {
		return this.isLiveTrading;
	}
	
	@Override
	public long getStrategyPollRateMs() {
		return this.strategyPollRateMs;
	}

	@Override
	public long getEntryAcknowledgementTimeoutMs() {
		return this.entryAcknowledgementTimeoutMs;
	}

	@Override
	public int getMaxActivePositions() {
		return this.maxActivePositions;
	}

	@Override
	public long getReconnectDelayMs() {
		return this.reconnectDelayMs;
	}

	@Override
	public String getTradingStatePath() {
		return this.tradingStatePath;
	}

	@Override
	public Set<String> getStrategyUniverse(String strategyId) {
		return symbolsFor(strategyId, "UNIVERSE");
	}

	@Override
	public Set<String> getStrategyReferenceSymbols(String strategyId) {
		return symbolsFor(strategyId, "REFERENCE_SYMBOLS");
	}

	@Override
	public boolean isOptionsProxyEnabled() {
		return this.optionsProxyEnabled;
	}

	@Override
	public String getOptionsProxyBindHost() {
		return this.optionsProxyBindHost;
	}

	@Override
	public int getOptionsProxyUdpPort() {
		return this.optionsProxyUdpPort;
	}

	@Override
	public long getOptionsProxyFrameMaxAgeMs() {
		return this.optionsProxyFrameMaxAgeMs;
	}

	@Override
	public long getMarketDataMaxAgeMs() {
		return this.marketDataMaxAgeMs;
	}

	@Override
	public boolean isEarningsEnabled() {
		return this.earningsEnabled;
	}

	@Override
	public String getEarningsEndpointUrl() {
		return this.earningsEndpointUrl;
	}

	@Override
	public long getEarningsConnectTimeoutMs() {
		return this.earningsConnectTimeoutMs;
	}

	@Override
	public long getEarningsRequestTimeoutMs() {
		return this.earningsRequestTimeoutMs;
	}

	@Override
	public long getEarningsRetryDelayMs() {
		return this.earningsRetryDelayMs;
	}

	@Override
	public String getMarketCalendarEndpointUrl() {
		return this.marketCalendarEndpointUrl;
	}
}
