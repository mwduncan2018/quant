---
paths:
  - "trading-engine/**/*"
---

# Package `mwd.trading.app`

Source: `trading-engine/trading-engine/src/main/java/mwd/trading/app/Main.java`

---

## `Main`

`public class Main`

### 1. Class/Interface Responsibilities

Process entry point that constructs every engine component, wires the `EWrapperRaptor` callback handlers, starts the refresher/pacer/strategy threads, registers a JVM shutdown hook, and blocks on a `CountDownLatch`.

### 2. Injected Dependencies

None. The class declares no constructor and is never instantiated; all collaborators are constructed inside `main(String[])`.

Types instantiated in `main(String[])`, in construction order:

`EnvPropConfig`, `RequestRegistry`, `TickMap`, `IdManager`, `TimeManager`, `OrderRegistry`, `Blackboard`, `TradingGate`, `BrokerState`, `JsonTradingStateStore`, `ReconciliationManager`, `AccountEventHandler`, `OrderLifecycleHandler`, `MarketDataInputStore`, `IntradayWilderAtrTracker`, `DailyWilderAtrCalculator`, `IbkrErrorHandler`, `SimpleMovingAverageTracker`, `NextValidIdHandler`, `RsiTracker`, `MinuteBarHandler`, `PriceTickHandler`, `SizeTickHandler`, `BrokerTimeHandler`, `MinuteVolumeTracker`, `EWrapperRaptor`, `IbkrSessionManager`, `MarketDataSubscriptionManager`, `TickByTickManager`, `OptionsIndicatorStore`, `OptionsIndicatorFrameReceiver`, `EarningsStore`, `EarningsClient`, `EarningsRefresher`, `MarketCalendarStore`, `MarketCalendarClient`, `MarketCalendarRefresher`, `MarginPacer`, `BlackboardMonitor`, `BracketOrderExecutor`, `TwoSigmaDownsideMeanReversionStrategy`, `OneSigmaDownsideMeanReversionStrategy`, `OneSigmaUpsideMeanReversionStrategy`.

### 3. Method Signatures

```java
public static void main(String[] args) throws Exception
private static Thread strategyThread(AbstractStrategy strategy)
private static void logStartupContext(Config config)
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
| `main(String[])` | Constructs the single `Blackboard` instance; passes `blackboard::getNextRequestId` as the `IntSupplier` to `ReconciliationManager`; passes the instance to `AccountEventHandler`, `OrderLifecycleHandler`, every indicator/market-data handler, `MarketDataSubscriptionManager`, `TickByTickManager`, `MarginPacer`, `BlackboardMonitor`, `BracketOrderExecutor`, and all three strategies |
| `mirrorEarningsForMonitor(Blackboard, EarningsSnapshot)` | Mutates `Blackboard` state via `blackboard.getStock(ticker).setNextEarningsDate(Instant)` |
| `mirrorFrameForMonitor(Blackboard, IndicatorFrame)` | Mutates `Blackboard` state via `blackboard.getStock(...).setDailyImpliedMove(double)` and `blackboard.getStock("SPY").setGammaFlip(double)` |

Both mirror methods reach the `ConcurrentHashMap<String, Stock>` inside `Blackboard` through `getStock(String)`, which inserts absent tickers.

**Concurrent collections**

`main(String[])` holds no concurrent collection of its own. It creates `Set.copyOf(marketDataSymbols)` (immutable) for `ReconciliationManager`, `OptionsIndicatorStore`, and `EarningsStore`, and a `List<Thread>` of strategy threads that the shutdown hook iterates.

**Thread lifecycle**

| Thread name | Runnable |
| --- | --- |
| `Earnings-Refresher-Thread` | `EarningsRefresher` (daemon) |
| `Market-Calendar-Refresher-Thread` | `MarketCalendarRefresher` (daemon) |
| `Margin-Pacer-Thread` | `MarginPacer` (daemon) |
| `<StrategyClassSimpleName>-Thread` | each `AbstractStrategy` (daemon, via `strategyThread`) |
| `Trading-Engine-Shutdown` | shutdown hook interrupting the above and closing `OptionsIndicatorFrameReceiver`, `IbkrSessionManager`, `ReconciliationManager` |
