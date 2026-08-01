---
paths:
  - "trading-engine/**/*"
---

# Package `mwd.trading.app`

Sources:
- `trading-engine/trading-engine/src/main/java/mwd/trading/app/Main.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/app/StartupManifest.java`

---

## `Main`

`public class Main`

### 1. Class/Interface Responsibilities

Process entry point that resolves and validates strategy activation before external activity, logs an immutable startup manifest, constructs every engine component, wires the `EWrapperRaptor` callback handlers, starts the refresher and enabled-strategy threads, registers a JVM shutdown hook, and blocks on a `CountDownLatch`.

### 2. Injected Dependencies

None. The class declares no constructor and is never instantiated; all collaborators are constructed inside `main(String[])`.

Types instantiated in `main(String[])`, in construction order:

`EnvPropConfig`, `StrategyActivationPolicy`, `StartupManifest`, `RequestRegistry`, `TickMap`, `IdManager`, `TimeManager`, `OrderRegistry`, `Blackboard`, `TradingGate`, `BrokerState`, `JsonTradingStateStore`, `ReconciliationManager`, `AccountEventHandler`, `OrderLifecycleHandler`, `MarketDataInputStore`, `IntradayWilderAtrTracker`, `DailyWilderAtrCalculator`, `IbkrErrorHandler`, `SimpleMovingAverageTracker`, `NextValidIdHandler`, `RsiTracker`, `MinuteBarHandler`, `PriceTickHandler`, `SizeTickHandler`, `BrokerTimeHandler`, `MinuteVolumeTracker`, `EWrapperRaptor`, `IbkrSessionManager`, `MarketDataSubscriptionManager`, `TickByTickManager`, optional `OptionsIndicatorStore`/`OptionsIndicatorFrameReceiver` and `EarningsStore`/`EarningsClient`/`EarningsRefresher`, `MarketCalendarStore`, `MarketCalendarClient`, `MarketCalendarRefresher`, `UniverseReference`, `ConcentrationLimits`, optional `BlackboardMonitor`, `BracketOrderExecutor`, and only the enabled concrete strategies.

### 3. Method Signatures

```java
public static void main(String[] args) throws Exception
private static Thread strategyThread(AbstractStrategy strategy)
private static void logStartupContext(Config config, StrategyActivationPolicy activationPolicy)
private static String resolveSessionLogFile()
private static void mirrorEarningsForMonitor(Blackboard blackboard, EarningsSnapshot snapshot)
private static void mirrorFrameForMonitor(Blackboard blackboard, IndicatorFrame frame)
```

Static fields:

```java
private static final Logger logger
private static final long PROXY_IDLE_RECHECK_MS = 60_000L
```

### 4. Global State Interactions

**Centralized state objects (`Blackboard`)**

| Method | Interaction |
| --- | --- |
| `main(String[])` | Constructs the single `Blackboard` instance; passes `blackboard::getNextRequestId` as the `IntSupplier` to `ReconciliationManager`; passes the instance to `AccountEventHandler`, `OrderLifecycleHandler`, every indicator/market-data handler, `MarketDataSubscriptionManager`, `TickByTickManager`, `ConcentrationLimits`, optional `BlackboardMonitor`, `BracketOrderExecutor`, and enabled strategies only |
| `mirrorEarningsForMonitor(Blackboard, EarningsSnapshot)` | Mutates `Blackboard` state via `blackboard.getStock(ticker).setNextEarningsDate(Instant)` |
| `mirrorFrameForMonitor(Blackboard, IndicatorFrame)` | Mutates `Blackboard` state via `blackboard.getStock(...).setDailyImpliedMove(double)` and `blackboard.getStock("SPY").setGammaFlip(double)` |

Both mirror methods reach the `ConcurrentHashMap<String, Stock>` inside `Blackboard` through `getStock(String)`, which inserts absent tickers.

**Concurrent collections**

`main(String[])` holds no concurrent collection of its own. `StrategyActivationPolicy` exposes immutable definition lists and produces the distinct sorted symbol list for enabled strategies only. `Main` creates `Set.copyOf(marketDataSymbols)` for `ReconciliationManager` and, when nonempty, `OptionsIndicatorStore` and `EarningsStore`; it freezes the enabled strategy thread list with `List.copyOf` before the shutdown hook captures it.

**Thread lifecycle**

| Thread name | Runnable |
| --- | --- |
| `Earnings-Refresher-Thread` | `EarningsRefresher` (daemon) |
| `Market-Calendar-Refresher-Thread` | `MarketCalendarRefresher` (daemon) |
| `<StrategyClassSimpleName>-Thread` | each enabled `AbstractStrategy` (daemon, via `strategyThread`) |
| `Trading-Engine-Shutdown` | shutdown hook interrupting the above and closing `OptionsIndicatorFrameReceiver`, `IbkrSessionManager`, `ReconciliationManager` |

---

## `StartupManifest`

`public record StartupManifest(TradingEnvironment tradingEnvironment, String accountSelection, int clientId, List<String> enabledStrategies, String tradingStateFile, String sessionLogFile)`

### 1. Class/Interface Responsibilities

Immutable startup identity record logged once as `STARTUP_MANIFEST`. It names the PAPER/LIVE environment, configured account or explicit TWS-managed-account sentinel, API client ID, enabled strategy IDs, absolute normalized journal path, and resolved log path.

### 2. Injected Dependencies

The canonical record constructor defensively copies `enabledStrategies` and rejects blank textual fields. `from(Config, StrategyActivationPolicy, String)` derives the record from resolved startup policy before external components are created.

### 3. Method Signatures

```java
public static StartupManifest from(Config config, StrategyActivationPolicy activationPolicy, String sessionLogFile)
public String toLogValue()
```

### 4. Global State Interactions

None. All fields are immutable values; the enabled strategy list is `List.copyOf(...)`.
