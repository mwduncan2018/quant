---
paths:
  - "trading-engine/**/*"
---

# Package `mwd.trading.config`

Sources:
- `trading-engine/trading-engine/src/main/java/mwd/trading/config/Config.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/config/EnvPropConfig.java`

---

## `Config`

`public interface Config`

### 1. Class/Interface Responsibilities

Declares the read-only accessors for every engine setting: IBKR connection and account values, strategy poll rate and position cap, trading-state path, per-strategy symbol sets, options-proxy UDP settings, market-data age limit, and earnings/calendar endpoint settings.

### 2. Injected Dependencies

None. Interfaces declare no constructor.

### 3. Method Signatures

```java
boolean isLiveIBKRData()
boolean showUI()
String getIBKRHost()
int getIBKRPort()
int getIBKRClientId()
String getExpectedAccount()
int getMarketDataType()
long getStrategyPollRateMs()
long getEntryAcknowledgementTimeoutMs()
int getMaxActivePositions()
long getReconnectDelayMs()
String getTradingStatePath()
Set<String> getStrategyUniverse(String strategyId)
Set<String> getStrategyReferenceSymbols(String strategyId)
boolean isOptionsProxyEnabled()
String getOptionsProxyBindHost()
int getOptionsProxyUdpPort()
long getOptionsProxyFrameMaxAgeMs()
long getMarketDataMaxAgeMs()
boolean isEarningsEnabled()
String getEarningsEndpointUrl()
long getEarningsConnectTimeoutMs()
long getEarningsRequestTimeoutMs()
long getEarningsRetryDelayMs()
String getMarketCalendarEndpointUrl()
```

All methods are implicitly `public abstract`.

### 4. Global State Interactions

None declared.

---

## `EnvPropConfig`

`public class EnvPropConfig implements Config`

### 1. Class/Interface Responsibilities

Implements `Config` by loading `config.properties` from the classpath into a `java.util.Properties` field and overriding each value from the matching environment variable, storing the resolved values in private scalar fields.

### 2. Injected Dependencies

Constructor: `public EnvPropConfig()` — no parameters. The constructor calls `this.initialize()`, which reads `config.properties` via the class loader and `System.getenv(String)`.

Instance fields initialised with defaults:

| Field | Type | Default |
| --- | --- | --- |
| `isLive` | `boolean` | `false` |
| `showUI` | `boolean` | `true` |
| `strategyPollRateMs` | `long` | `16` |
| `entryAcknowledgementTimeoutMs` | `long` | `10000` |
| `maxActivePositions` | `int` | `3` |
| `ibkrHost` | `String` | `"127.0.0.1"` |
| `ibkrClientId` | `int` | `0` |
| `expectedAccount` | `String` | `""` |
| `reconnectDelayMs` | `long` | `5000` |
| `tradingStatePath` | `String` | `data/trading-state-live.json` or `data/trading-state-paper.json` |
| `optionsProxyEnabled` | `boolean` | `true` |
| `optionsProxyBindHost` | `String` | `"127.0.0.1"` |
| `optionsProxyUdpPort` | `int` | `5005` |
| `optionsProxyFrameMaxAgeMs` | `long` | `5000` |
| `marketDataMaxAgeMs` | `long` | `30000` |
| `earningsEnabled` | `boolean` | `true` |
| `earningsEndpointUrl` | `String` | `"http://127.0.0.1:8000/earnings"` |
| `earningsConnectTimeoutMs` | `long` | `3000` |
| `earningsRequestTimeoutMs` | `long` | `5000` |
| `earningsRetryDelayMs` | `long` | `30000` |
| `marketCalendarEndpointUrl` | `String` | `"http://127.0.0.1:8000/calendar"` |
| `properties` | `final Properties` | `new Properties()` |

### 3. Method Signatures

```java
public EnvPropConfig()

private void initialize()
private static String value(String key, Properties properties, String defaultValue)
private Set<String> symbolsFor(String strategyId, String suffix)

@Override public boolean showUI()
@Override public String getIBKRHost()
@Override public int getIBKRPort()
@Override public int getIBKRClientId()
@Override public String getExpectedAccount()
@Override public int getMarketDataType()
@Override public boolean isLiveIBKRData()
@Override public long getStrategyPollRateMs()
@Override public long getEntryAcknowledgementTimeoutMs()
@Override public int getMaxActivePositions()
@Override public long getReconnectDelayMs()
@Override public String getTradingStatePath()
@Override public Set<String> getStrategyUniverse(String strategyId)
@Override public Set<String> getStrategyReferenceSymbols(String strategyId)
@Override public boolean isOptionsProxyEnabled()
@Override public String getOptionsProxyBindHost()
@Override public int getOptionsProxyUdpPort()
@Override public long getOptionsProxyFrameMaxAgeMs()
@Override public long getMarketDataMaxAgeMs()
@Override public boolean isEarningsEnabled()
@Override public String getEarningsEndpointUrl()
@Override public long getEarningsConnectTimeoutMs()
@Override public long getEarningsRequestTimeoutMs()
@Override public long getEarningsRetryDelayMs()
@Override public String getMarketCalendarEndpointUrl()
```

### 4. Global State Interactions

**Concurrent collections**

None. The only collection field is `private final Properties properties`, mutated once by `initialize()` and read by `value(String, Properties, String)` and `symbolsFor(String, String)`.

**Centralized state objects**

No reference to `Blackboard` or any other centralized state object.

**Process-level state read**

`initialize()` and `value(String, Properties, String)` read `System.getenv(String)`; `initialize()` reads the classpath resource `config.properties`.
