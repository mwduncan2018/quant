---
paths:
  - "trading-engine/**/*"
---

# Engine Startup and Connection Flow

Traces construction and wiring of every component from `Main.main(String[])` through
the TWS API handshake to the point where strategy threads are running.

## 1. Workflow Components

| Component | Role in this flow |
| --- | --- |
| `mwd.trading.app.Main` | Entry point that constructs every collaborator, starts the daemon threads, registers the shutdown hook, and blocks on a `CountDownLatch`. |
| `mwd.trading.config.Config` | Interface supplying strategy enablement/universes, endpoint URLs, timeouts, and IBKR host/port/client-id/account values read during construction. |
| `mwd.trading.config.EnvPropConfig` | Concrete `Config` instantiated as the first statement of `main`. |
| `mwd.trading.strategy.StrategyDefinition` / `StrategyActivationPolicy` | Resolve immutable activation metadata and reject unsafe mode, universe, or LIVE-account configuration before any external component starts. |
| `mwd.trading.app.StartupManifest` | Immutable mode/account/client/strategy/journal/log identity emitted once after activation validation. |
| `mwd.trading.broker.ibkr.RequestRegistry` | Maps IBKR request IDs to a ticker and an `EnumSet<DataConsumer>`; constructed before any request is issued. |
| `mwd.trading.broker.ibkr.TickMap` | Constructed with `Config` and passed to the tick handlers for field-number classification. |
| `mwd.trading.broker.ibkr.IdManager` | Holds the `AtomicInteger` order and request counters injected into `Blackboard`. |
| `mwd.trading.broker.ibkr.TimeManager` | Holds the broker-clock offset injected into `Blackboard`. |
| `mwd.trading.execution.OrderRegistry` | Bracket lookup maps injected into `Blackboard`. |
| `mwd.trading.state.Blackboard` | Aggregate constructed from `IdManager`, `TimeManager`, `OrderRegistry`, and `Config`; owns the `Stock` map and the entry-serialization primitives. |
| `mwd.trading.lifecycle.TradingGate` | Application-wide mode holder, constructed in `STARTING` and driven to `CONNECTING`/`RECONCILING`/`READY`; a LIVE instance independently starts entry-disarmed. |
| `mwd.trading.lifecycle.EngineMode` | Enum of the modes the gate transitions through. |
| `mwd.trading.lifecycle.TradingEnvironment` | Distinguishes PAPER/LIVE order destination from lifecycle state. |
| `mwd.trading.reconciliation.BrokerState` | Broker-side record container constructed before `ReconciliationManager`. |
| `mwd.trading.persistence.JsonTradingStateStore` | Loads the JSON trade journal in its constructor and reports `recoveredFromBackup()` / `activeTrades()` to `Main`. |
| `mwd.trading.reconciliation.ReconciliationManager` | Receives `blackboard::getNextRequestId` as an `IntSupplier` and the managed-symbol set; `attachClient` is called from the `IbkrSessionManager` constructor. |
| `mwd.trading.broker.ibkr.callback.AccountEventHandler` | Constructed from `Blackboard` and `ReconciliationManager`. |
| `mwd.trading.execution.OrderLifecycleHandler` | Constructed from `Blackboard`, `ReconciliationManager`, `JsonTradingStateStore`, and `TradingGate`. |
| `mwd.trading.marketdata.MarketDataInputStore` | Per-input readiness store constructed with `config.getMarketDataMaxAgeMs()`. |
| `mwd.trading.broker.ibkr.EWrapperRaptor` | `EWrapper` implementation constructed with the registry and all thirteen callback consumers; `attachLifecycle` is called after the session manager exists. |
| `mwd.trading.broker.ibkr.EWrapperAbstractBase` | Base class supplying default `EWrapper` methods. |
| `mwd.trading.broker.ibkr.IbkrSessionManager` | Creates the `EClientSocket`, owns the lifecycle executor and reader thread, and drives connect/reconnect. |
| `com.ib.client.EClientSocket` | TWS API socket constructed inside `IbkrSessionManager` from the wrapper and `EJavaSignal`. |
| `com.ib.client.EReader` / `com.ib.client.EJavaSignal` | Message reader and signal used by the `IBKR-Reader` thread. |
| `mwd.trading.marketdata.MarketDataSubscriptionManager` | Constructed from `sessionManager.client()`, `Blackboard`, `RequestRegistry`, the symbol list, and `MarketDataInputStore`. |
| `mwd.trading.marketdata.TickByTickManager` | Constructed from `Blackboard`, `sessionManager.client()`, and `RequestRegistry`. |
| `mwd.trading.optionsproxy.OptionsIndicatorStore` | Constructed with the enabled-strategy market-data symbol set and `config.getOptionsProxyFrameMaxAgeMs()`; omitted when every strategy is disabled. |
| `mwd.trading.optionsproxy.OptionsIndicatorFrameReceiver` | Constructed and started only when the store exists and `config.isOptionsProxyEnabled()`. |
| `mwd.trading.earnings.EarningsStore` / `EarningsClient` / `EarningsRefresher` | Store is omitted when every strategy is disabled; client/refresher thread additionally require `config.isEarningsEnabled()`. |
| `mwd.trading.calendar.MarketCalendarStore` / `MarketCalendarClient` / `MarketCalendarRefresher` | Constructed and started unconditionally on their own thread. |
| `mwd.trading.risk.UniverseReference` | Loaded from `config.getUniverseReferencePath()`; its coverage report and age are logged before any strategy starts. |
| `mwd.trading.risk.ConcentrationLimits` | Constructed from `Blackboard`, the reference table, and the three cap settings. |
| `mwd.trading.ui.BlackboardMonitor` | Swing monitor constructed via `SwingUtilities.invokeLater` when `config.showUI()`; owns the only production caller of `TradingGate.armLiveTrading()`. |
| `mwd.trading.execution.BracketOrderExecutor` | Single `BracketOrderGateway` instance shared by every enabled strategy. |
| `mwd.trading.strategy.AbstractStrategy` | `Runnable` base wrapped by `Main.strategyThread(AbstractStrategy)`. |
| `mwd.trading.strategy.TwoSigmaDownsideMeanReversionStrategy` | Constructed only when its activation definition is enabled; takes the earnings store in addition to shared collaborators. |
| `mwd.trading.strategy.OneSigmaDownsideMeanReversionStrategy` | Permanently PAPER-only and constructed only when enabled. |
| `mwd.trading.strategy.OneSigmaUpsideMeanReversionStrategy` | Permanently PAPER-only and constructed only when enabled. |
| `mwd.trading.broker.ibkr.callback.NextValidIdHandler` | Receives the first `nextValidId` callback and seeds `IdManager`. |
| `mwd.trading.broker.ibkr.callback.BrokerTimeHandler` | Receives `currentTime` and sets the `TimeManager` offset. |

## 2. Execution Path

### Construction (main thread)

1. **Initiating Component:** `Main`
   **Method Invocation:** `new EnvPropConfig()`
   **Receiving Component:** `EnvPropConfig`

2. **Initiating Component:** `Main`
   **Method Invocation:** `StrategyActivationPolicy.from(config)`, which reads `isStrategyEnabled`, universe, references, order environment, and expected account; then `StartupManifest.from(...)` is logged as `STARTUP_MANIFEST`
   **Receiving Component:** `StrategyActivationPolicy`, `StartupManifest`

2a. **Initiating Component:** `Main`
   **Method Invocation:** `activationPolicy.marketDataSymbols()`
   **Receiving Component:** distinct sorted `List<String>` containing only enabled-strategy trade and reference symbols

3. **Initiating Component:** `Main`
   **Method Invocation:** `new Blackboard(new IdManager(), new TimeManager(), new OrderRegistry(), config)` and `new TradingGate(config.isLiveTrading())`
   **Receiving Component:** `Blackboard`

4. **Initiating Component:** `Main`
   **Method Invocation:** `new JsonTradingStateStore(Path.of(config.getTradingStatePath()))`, then `stateStore.recoveredFromBackup()` and `stateStore.activeTrades()`
   **Receiving Component:** `JsonTradingStateStore`

5. **Initiating Component:** `Main`
   **Method Invocation:** `tradingGate.requireManualIntervention(String)` when `stateStore.activeTrades()` is non-empty
   **Receiving Component:** `TradingGate`

6. **Initiating Component:** `Main`
   **Method Invocation:** `new ReconciliationManager(brokerState, stateStore, tradingGate, blackboard::getNextRequestId, Set.copyOf(marketDataSymbols))`
   **Receiving Component:** `ReconciliationManager`

7. **Initiating Component:** `Main`
   **Method Invocation:** `new EWrapperRaptor(registry, IntradayWilderAtrTracker, DailyWilderAtrCalculator, IbkrErrorHandler, AccountEventHandler, SimpleMovingAverageTracker, NextValidIdHandler, OrderLifecycleHandler, RsiTracker, MinuteBarHandler, PriceTickHandler, SizeTickHandler, BrokerTimeHandler, MinuteVolumeTracker)`
   **Receiving Component:** `EWrapperRaptor`

8. **Initiating Component:** `Main`
   **Method Invocation:** `new IbkrSessionManager(wrapper, config, tradingGate, reconciliationManager)`, whose constructor calls `new EClientSocket(wrapper, signal)` and `reconciliationManager.attachClient(client)`
   **Receiving Component:** `IbkrSessionManager` → `EClientSocket` → `ReconciliationManager`

9. **Initiating Component:** `Main`
   **Method Invocation:** `sessionManager.attachSubscriptionManager(subscriptionManager)` and `wrapper.attachLifecycle(sessionManager, subscriptionManager)`
   **Receiving Component:** `IbkrSessionManager`, `EWrapperRaptor`

10. **Initiating Component:** `Main`
    **Method Invocation:** `optionsIndicatorFrameReceiver.start()` guarded by `config.isOptionsProxyEnabled()`
    **Receiving Component:** `OptionsIndicatorFrameReceiver`

11. **Initiating Component:** `Main`
    **Method Invocation:** `new Thread(earningsRefresher, "Earnings-Refresher-Thread").start()` and `new Thread(marketCalendarRefresher, "Market-Calendar-Refresher-Thread").start()` — each `setDaemon(true)`
    **Receiving Component:** `EarningsRefresher`, `MarketCalendarRefresher`

11a. **Initiating Component:** `Main`
    **Method Invocation:** `UniverseReference.load(Path.of(config.getUniverseReferencePath()), config.getMarginMethodology(), config.getDefaultLongMarginRate(), config.getDefaultShortMarginRate())`, then `describeCoverage(...)` logged line by line and `ageInDays(...)` warned past `config.getUniverseReferenceMaxAgeDays()`
    **Receiving Component:** `UniverseReference`

11b. **Initiating Component:** `Main`
    **Method Invocation:** `new ConcentrationLimits(blackboard, universeReference, config.getMaxTickerExposurePercent(), config.getMaxSectorExposurePercent(), config.getMinPositionNotional())`
    **Receiving Component:** `ConcentrationLimits`

12. **Initiating Component:** `Main`
    **Method Invocation:** `SwingUtilities.invokeLater(() -> new BlackboardMonitor(blackboard, universeReference, tradingGate, config.isLiveTrading(), config.getExpectedAccount()))` guarded by `config.showUI()`; LIVE with no UI logs that the process is permanently entry-disarmed
    **Receiving Component:** `BlackboardMonitor`

13. **Initiating Component:** `Main`
    **Method Invocation:** `new BracketOrderExecutor(blackboard, sessionManager.client(), tradingGate, stateStore, config, activationPolicy)`
    **Receiving Component:** `BracketOrderExecutor`

14. **Initiating Component:** `Main`
    **Method Invocation:** activation-ID checks followed by `strategyThread(AbstractStrategy)` for enabled strategies only, `List.copyOf(...)`, then `strategyThreads.forEach(Thread::start)`
    **Receiving Component:** `Thread` / `AbstractStrategy.run()`

15. **Initiating Component:** `Main`
    **Method Invocation:** `Runtime.getRuntime().addShutdownHook(new Thread(..., "Trading-Engine-Shutdown"))`
    **Receiving Component:** `Runtime`

16. **Initiating Component:** `Main`
    **Method Invocation:** `sessionManager.start()`, then `new CountDownLatch(1).await()`
    **Receiving Component:** `IbkrSessionManager`, then the main thread parks

### Connection handshake (`IBKR-Session-Lifecycle` and `IBKR-Reader` threads)

17. **Initiating Component:** `IbkrSessionManager.start()`
    **Method Invocation:** `lifecycleExecutor.execute(this::connectNow)`
    **Receiving Component:** `IbkrSessionManager.connectNow()` on the `IBKR-Session-Lifecycle` thread

18. **Initiating Component:** `IbkrSessionManager.connectNow()`
    **Method Invocation:** `tradingGate.transitionTo(EngineMode.CONNECTING, String)`
    **Receiving Component:** `TradingGate`

19. **Initiating Component:** `IbkrSessionManager.connectNow()`
    **Method Invocation:** `client.eConnect(config.getIBKRHost(), currentPort, config.getIBKRClientId())`
    **Receiving Component:** `EClientSocket`

20. **Initiating Component:** `IbkrSessionManager.startReaderThread()`
    **Method Invocation:** `new EReader(client, signal).start()`, then a `new Thread(..., "IBKR-Reader")` loop calling `signal.waitForSignal()` and `reader.processMsgs()`
    **Receiving Component:** `EReader`

21. **Initiating Component:** `EReader` (on `IBKR-Reader`)
    **Method Invocation:** `EWrapperRaptor.connectAck()`
    **Receiving Component:** `IbkrSessionManager.onConnectAck()`

22. **Initiating Component:** `EReader` (on `IBKR-Reader`)
    **Method Invocation:** `EWrapperRaptor.nextValidId(int orderId)`
    **Receiving Component:** `NextValidIdHandler.initialize(int)` → `Blackboard.initializeOrderId(int)` → `IdManager.initializeOrderId(int)`; then `IbkrSessionManager.onNextValidId()`

23. **Initiating Component:** `IbkrSessionManager.onNextValidId()`
    **Method Invocation:** `apiReadyHandled.compareAndSet(false, true)` then `lifecycleExecutor.execute(this::initializeConnectedSession)`
    **Receiving Component:** `IbkrSessionManager.initializeConnectedSession()` on `IBKR-Session-Lifecycle`

24. **Initiating Component:** `IbkrSessionManager.initializeConnectedSession()`
    **Method Invocation:** `client.reqMarketDataType(config.getMarketDataType())` and `client.reqCurrentTime()`
    **Receiving Component:** `EClientSocket`

25. **Initiating Component:** `IbkrSessionManager.initializeConnectedSession()`
    **Method Invocation:** `subscriptions.initializeIfNeeded()` on first connect, `subscriptions.resetAfterSocketReconnect()` when `hasConnectedBefore`
    **Receiving Component:** `MarketDataSubscriptionManager`

26. **Initiating Component:** `IbkrSessionManager.initializeConnectedSession()`
    **Method Invocation:** `reconciliationManager.begin("IBKR API session established; validating broker state")`
    **Receiving Component:** `ReconciliationManager`

27. **Initiating Component:** `EReader` (on `IBKR-Reader`)
    **Method Invocation:** `EWrapperRaptor.managedAccounts(String)` → `AccountEventHandler.onManagedAccounts(String)` and `IbkrSessionManager.onManagedAccounts(String)`
    **Receiving Component:** `Blackboard.getAccount().setAccountId(String)`; `EClientSocket.reqAccountUpdates(true, account)`

28. **Initiating Component:** `EReader` (on `IBKR-Reader`)
    **Method Invocation:** `EWrapperRaptor.currentTime(long)` → `BrokerTimeHandler.onCurrentTime(long)`
    **Receiving Component:** `Blackboard.getTimeManager().setOffset(long, long)`

29. **Initiating Component:** `ReconciliationManager.completeIfReady(CollectionEpoch)`
    **Method Invocation:** `tradingGate.transitionTo(EngineMode.READY, String)` when no differences are found, otherwise `tradingGate.requireManualIntervention(String)`
    **Receiving Component:** `TradingGate`

29a. **Initiating Component:** operator on the Swing event-dispatch thread (LIVE only)
    **Method Invocation:** warning-dialog `YES` response → `tradingGate.armLiveTrading()` while mode is `READY`
    **Receiving Component:** process-local `AtomicBoolean liveTradingArmed`; configuration and journal state have no write path to it

### Reconnect and shutdown

30. **Initiating Component:** `EWrapperRaptor.connectionClosed()` / `EWrapperRaptor.error(int, long, int, String, String)`
    **Method Invocation:** `IbkrSessionManager.onConnectionClosed()` / `IbkrSessionManager.onError(int, String)`
    **Receiving Component:** `ReconciliationManager.onDisconnected(String)`, `MarketDataSubscriptionManager.markAllStale()`, `IbkrSessionManager.scheduleReconnect()`

31. **Initiating Component:** `Trading-Engine-Shutdown` hook thread
    **Method Invocation:** `strategyThreads.forEach(Thread::interrupt)`, `earningsRefresherForShutdown.interrupt()`, `marketCalendarForShutdown.interrupt()`, `receiverForShutdown.stop()`, `sessionManager.close()`, `reconciliationManager.close()`, `LogManager.shutdown()`
    **Receiving Component:** each named component

## 3. Data Payloads and State Handoffs

### Objects passed

- `Config` → every constructed component that reads settings; passed by reference and never mutated.
- `List<String> marketDataSymbols` from enabled definitions → `MarketDataSubscriptionManager` (as `List.copyOf`), `ReconciliationManager` (as `Set.copyOf`), and, when nonempty, `OptionsIndicatorStore` / `EarningsStore` (as `Set.copyOf`).
- `Blackboard` → shared by the handlers, trackers, executor, concentration limits, optional monitor, and enabled strategies.
- `EClientSocket` (obtained via `sessionManager.client()`) → `MarketDataSubscriptionManager`, `TickByTickManager`, `BracketOrderExecutor`, `ReconciliationManager`.
- `IntSupplier` `blackboard::getNextRequestId` → `ReconciliationManager`, used in `begin(String)`.
- `OptionsIndicatorFrameReceiver.AcceptedFrameListener` lambda `frame -> Main.mirrorFrameForMonitor(blackboard, frame)` → `OptionsIndicatorFrameReceiver`.
- `Consumer<EarningsSnapshot>` lambda `snapshot -> Main.mirrorEarningsForMonitor(blackboard, snapshot)` → `EarningsRefresher`.
- `TradingGate.State` record (`EngineMode mode`, `String reason`, `long changedAtEpochMillis`) held in an `AtomicReference`, plus the independent LIVE `AtomicBoolean` arm.

### Thread handoffs

| From | To | Mechanism |
| --- | --- | --- |
| Main thread | `IBKR-Session-Lifecycle` (single-thread `ScheduledExecutorService`) | `IbkrSessionManager.start()` → `lifecycleExecutor.execute(this::connectNow)` |
| `IBKR-Session-Lifecycle` | `IBKR-Reader` daemon thread | `startReaderThread()` creates the thread that runs `signal.waitForSignal()` / `reader.processMsgs()` |
| `IBKR-Reader` | `IBKR-Session-Lifecycle` | `onNextValidId()` and `onError(1101/1102)` re-post work with `lifecycleExecutor.execute(...)` |
| `IBKR-Reader` | `IBKR-Reconciliation-Timeout` | `ReconciliationManager.begin` schedules `failEpoch` on its own single-thread scheduler |
| Main thread | Zero or more enabled `<StrategyClassSimpleName>-Thread` daemon threads | `Main.strategyThread(AbstractStrategy)` + `Thread::start` |
| Main thread | `Earnings-Refresher-Thread`, `Market-Calendar-Refresher-Thread` daemon threads | explicit `Thread` construction and `start()` |
| Main thread | `Options-Proxy-UDP-Receiver` daemon thread | `OptionsIndicatorFrameReceiver.start()` |
| Main thread | Swing event-dispatch thread | `SwingUtilities.invokeLater(...)` |
| JVM shutdown | `Trading-Engine-Shutdown` thread | `Runtime.addShutdownHook` |

Cross-thread state is published through `volatile` fields (`Stock`, `IbkrSessionManager`,
`EWrapperRaptor`), `Atomic*` holders (`TradingGate`, `IdManager`, `Blackboard.globalPendingOwner`),
`ConcurrentHashMap` instances (`Blackboard.stocks`, `RequestRegistry.idToData`,
`OrderRegistry`), and `synchronized` methods (`Blackboard.activePositionOwners`,
`ReconciliationManager`, `BrokerState`, `JsonTradingStateStore`).
