---
paths:
  - "trading-engine/**/*"
---

# Trading Engine — Concurrency Model and Execution Boundaries

Extracted from `trading-engine/trading-engine/src/main/java/mwd/trading/**`.
Every entry below is present verbatim in the source; nothing is inferred.

---

## 1. Thread Execution Boundaries

### 1.1 Thread inventory

All threads are created in `Main.main` unless stated otherwise.

| Thread name | Created at | Daemon | Entry point |
|---|---|---|---|
| `main` | JVM | no | `Main.main(String[])`, terminating in `new CountDownLatch(1).await()` |
| `TwoSigmaDownsideMeanReversionStrategy-Thread` | `Main.strategyThread` | yes | `AbstractStrategy.run()` |
| `OneSigmaDownsideMeanReversionStrategy-Thread` | `Main.strategyThread` | yes | `AbstractStrategy.run()` |
| `OneSigmaUpsideMeanReversionStrategy-Thread` | `Main.strategyThread` | yes | `AbstractStrategy.run()` |
| `Earnings-Refresher-Thread` | `Main.main` (only when `config.isEarningsEnabled()`) | yes | `EarningsRefresher.run()` |
| `Market-Calendar-Refresher-Thread` | `Main.main` | yes | `MarketCalendarRefresher.run()` |
| `Options-Proxy-UDP-Receiver` | `OptionsIndicatorFrameReceiver.start()` | yes | `OptionsIndicatorFrameReceiver.receiveLoop()` |
| `IBKR-Reader` | `IbkrSessionManager.startReaderThread()` | yes | loop of `signal.waitForSignal()` then `reader.processMsgs()` |
| *(TWS API `EReader`)* | `IbkrSessionManager.startReaderThread()` — `new EReader(client, signal); reader.start()` | per TWS API | socket decode thread owned by `JavaClient` |
| `IBKR-Session-Lifecycle` | `IbkrSessionManager` constructor — `Executors.newSingleThreadScheduledExecutor` | yes | tasks submitted via `lifecycleExecutor.execute` / `.schedule` |
| `IBKR-Reconciliation-Timeout` | `ReconciliationManager` constructor — `Executors.newSingleThreadScheduledExecutor` | yes | scheduled `failEpoch` timeout task |
| `Trading-Engine-Shutdown` | `Runtime.getRuntime().addShutdownHook` in `Main.main` | n/a | shutdown lambda |
| Swing Event Dispatch Thread | `SwingUtilities.invokeLater` in `Main.main` (only when `config.showUI()`) | — | `new BlackboardMonitor(blackboard)` |
| *(unnamed monitor refresh)* | `BlackboardMonitor.startDataRefreshThread()` | yes | loop of `updateDashboardData()` + `Thread.sleep(250)` |
| *(unnamed siren audio)* | `BlackboardMonitor.startSiren()` | yes | audio write loop |

### 1.2 Strategy poll threads

`AbstractStrategy.run()` is `final`; the loop is
`while (!Thread.currentThread().isInterrupted()) { runOneCycle(); Thread.sleep(config.getStrategyPollRateMs()); }`.

Executing on a strategy poll thread:

| Class | Methods |
|---|---|
| `AbstractStrategy` | `run`, `runOneCycle`, `processSymbolSafely`, `executeLifecycle`, `acknowledgeStatusChange`, `evaluateNewEntry`, `handlePendingEntry`, `handleFlatWithLocalOwnership`, `cleanupOwnedLifecycle`, `rollbackEntryReservation`, `snapshot`, `trimToTotal`, `updateExits`, `entryInputsReady`, `automatedOrderChangesAllowed`, `acknowledgementTimedOut`, `escalate`, `strategyId`, `tradeDirection` |
| `EntryAdmission` | `tryAdmit`, and `Reservation.keep` / `release` / `close` |
| `MarketSnapshot` | `of`, taken once per screening pass and once per admitted entry, plus once per management cycle |
| `UniverseReference` | `marginRate`, `sector` — immutable after construction, so no synchronization is involved |
| `ConcentrationLimits` | `allowedQuantity`, `currentExposure` — iterates `Blackboard.forEachStock` on the strategy thread while the reader thread may be writing the very fields being summed |
| `TwoSigmaDownsideMeanReversionStrategy`, `OneSigmaDownsideMeanReversionStrategy`, `OneSigmaUpsideMeanReversionStrategy` | `isEntryConditionMet`, `calculateEntryPrice`, `calculateSliceIntents`, `evaluateTickStreamNeed`, `manageOpenPosition`, `onPositionClosed`, `requiredEntryInputs`, `requiredManagementInputs`, `getStrategyName`, `getTradeDirection` |
| `BracketOrderExecutor` (via `BracketOrderGateway`) | `placeTripleThreat`, `updateTripleThreatExits`, `persistIntent`, `validateEntryIntent`, `configuredAccount`, `halt` |
| `JsonTradingStateStore` | `recordIntent` (reached from `BracketOrderExecutor.persistIntent`) |
| `TickByTickManager` (via `TickStreamController`) | `isStreamActive`, `tryRequestStream`, `cancelStream`, `executeRequest`, `isSlotAvailable` |
| `Blackboard` via `StrategyBlackboard` | `getStock`, `getPositionOwner`, `tryReservePosition`, `releasePosition`, `isPositionOwnedBy`, `tryAcquireGlobalPending`, `releaseGlobalPending`, `isAccountCurrentForNewEntry`, `recordEntrySubmitted`, `getAccount`, `setSystemHalted`. A strategy holds the narrow type and cannot reach anything else on the blackboard |
| `Blackboard` via `BracketOrderExecutor` | `getNextOrderId`, `getOrderRegistry`, `getStock` — reached on the strategy thread, but through the executor, which holds the full `Blackboard` |
| `TradingGate` | `allowsNewEntries`, `allowsAutomatedOrderChanges`, `getMode`, `requireManualIntervention` |
| `MarketDataInputStore` (via `MarketDataFreshness`) | `areAllFresh`, `describeUnready`, `isFresh` — read side only |
| `OptionsIndicatorStore` | `impliedMoveForNewEntry`, `gammaFlipForNewEntry`, `lastKnownImpliedMove`, `hasFreshFrame`, `tradingDate` — read side only |
| `EarningsStore` | `earningsDate`, `daysUntilEarnings`, `isReadyFor`, `describeUnready` — read side only |
| `MarketCalendarStore` | `isReadyFor`, `sessionClose`, `isWithinOfClose`, `isSession`, `coversDate`, `nextSession`, `previousSessionApproximate`, `describeUnready` — read side only |

`EClientSocket.placeOrder`, `cancelTickByTickData`, and `reqTickByTickData` are invoked directly from the strategy poll threads through `BracketOrderExecutor` and `TickByTickManager`.

### 1.3 IBKR callback reader thread

Every `EWrapperRaptor` override runs on the reader thread. `EWrapperRaptor` performs no state mutation of its own beyond `attachLifecycle`; it dispatches on `registry.getConsumersFor(reqId)`.

| `EWrapperRaptor` callback | Delegates to (same thread) |
|---|---|
| `nextValidId` | `NextValidIdHandler.initialize` → `Blackboard.initializeOrderId`; `IbkrSessionManager.onNextValidId` |
| `error(int,long,int,String,String)` | `IbkrErrorHandler.process`; `OrderLifecycleHandler.onError`; `IbkrSessionManager.onError` |
| `error(Exception)`, `error(String)` | logging only |
| `connectionClosed` | `IbkrSessionManager.onConnectionClosed` |
| `connectAck` | `IbkrSessionManager.onConnectAck` |
| `tickPrice` | `PriceTickHandler.onTickPrice`; `SimpleMovingAverageTracker.onTickPrice` |
| `tickSize` | `SizeTickHandler.onTickSize` |
| `tickString` | `PriceTickHandler.onTickString` |
| `tickByTickBidAsk` | `PriceTickHandler.onTickByTickBidAsk`; `SizeTickHandler.onTickByTickBidAsk` |
| `tickByTickAllLast` | `PriceTickHandler.onTickByTickAllLast`; `SizeTickHandler.onTickByTickAllLast` |
| `historicalData` | `IntradayWilderAtrTracker.onHistoricalData`; `DailyWilderAtrCalculator.onHistoricalData`; `SimpleMovingAverageTracker.onHistoricalData`; `RsiTracker.onHistoricalData`; `MinuteVolumeTracker.onHistoricalData`; `MinuteBarHandler.onHistoricalData` |
| `historicalDataEnd` | `IntradayWilderAtrTracker.onHistoricalDataEnd`; `DailyWilderAtrCalculator.onHistoricalDataEnd`; `SimpleMovingAverageTracker.onHistoricalDataEnd`; `RsiTracker.onHistoricalDataEnd`; `MinuteVolumeTracker.onHistoricalDataEnd` |
| `historicalDataUpdate` | `IntradayWilderAtrTracker.onHistoricalDataUpdate`; `RsiTracker.onHistoricalDataUpdate`; `MinuteVolumeTracker.onHistoricalDataUpdate`; `MinuteBarHandler.onHistoricalDataUpdate` |
| `orderStatus` | `OrderLifecycleHandler.onOrderStatus` |
| `openOrder` | `OrderLifecycleHandler.onOpenOrder` |
| `openOrderEnd` | `OrderLifecycleHandler.onOpenOrderEnd` |
| `execDetails`, `execDetailsEnd` | `OrderLifecycleHandler.onExecDetails`, `onExecDetailsEnd` |
| `commissionAndFeesReport` | `OrderLifecycleHandler.onCommissionAndFeesReport` |
| `completedOrder`, `completedOrdersEnd` | `OrderLifecycleHandler.onCompletedOrder`, `onCompletedOrdersEnd` |
| `orderBound` | `OrderLifecycleHandler.onOrderBound` |
| `managedAccounts` | `AccountEventHandler.onManagedAccounts`; `IbkrSessionManager.onManagedAccounts` |
| `accountSummary` | empty body |
| `position`, `positionEnd` | `AccountEventHandler.onPosition`, `onPositionEnd` |
| `accountDownloadEnd` | `AccountEventHandler.onAccountDownloadEnd` |
| `updatePortfolio` | `AccountEventHandler.onUpdatePortfolio` |
| `updateAccountTime` | `AccountEventHandler.onUpdateAccountTime` |
| `updateAccountValue` | `AccountEventHandler.onUpdateAccountValue` |
| `currentTime` | `BrokerTimeHandler.onCurrentTime` |

Reached transitively on the same reader thread:

- `OrderLifecycleHandler` private members: `validateExitSlice`, `resolveBracket`, `markPositionOpen`, `completeConfirmedFlat`, `persist`, `halt`.
- `ReconciliationManager.onPosition`, `onLivePortfolioPosition`, `onPositionEnd`, `onOpenOrder`, `onOpenOrderEnd`, `onCompletedOrder`, `onCompletedOrdersEnd`, `onExecution`, `onExecutionEnd`, `onOrderStatus`, plus `completeIfReady` when an epoch completes.
- `BrokerState.recordPosition`, `recordOpenOrder`, `recordCompletedOrder`, `recordOrderStatus`, `recordExecution`, `snapshot`, `replaceWith`, `clear`.
- `JsonTradingStateStore.recordBrokerUpdate` → `recordIntent` → `persist` (via `OrderLifecycleHandler.persist`).
- `Blackboard.releaseGlobalPending`, `releasePosition`, `setSystemHalted`, `setOpenOrderEnd`, `getOrderRegistry().recordBrokerIdentity`.
- `Stock.setActiveBracket(null)` and the `Stock` setters listed in §3. The reader thread no longer writes any position state: `Stock.PositionState` is derived from the bracket status it sets.

`IbkrSessionManager.onConnectionClosed` and `onError` execute on the reader thread and hand work to `lifecycleExecutor`; `onManagedAccounts` calls `client.reqAccountUpdates` directly on the reader thread.

### 1.4 `IBKR-Session-Lifecycle` executor thread

Single-threaded `ScheduledExecutorService`. Runs `IbkrSessionManager.connectNow`, `startReaderThread`, `initializeConnectedSession`, `restoreBrokerConnectivity`, and the delayed `connectNow` submitted by `scheduleReconnect`. Through those it runs `MarketDataSubscriptionManager.initializeIfNeeded`, `resetAfterSocketReconnect`, `resubscribeAfterDataLoss`, `cancelTrackedRequests`, `markAllStale`, and `ReconciliationManager.begin`.

### 1.5 `IBKR-Reconciliation-Timeout` executor thread

Single-threaded `ScheduledExecutorService`. Runs only the scheduled lambda `() -> failEpoch(epoch.number, "Timed out waiting for the complete IBKR broker snapshot")` from `ReconciliationManager.begin`.

### 1.7 `Earnings-Refresher-Thread`

`EarningsRefresher.run` → `refreshOnce` → `EarningsClient.fetch` → `ProxyJsonFetcher.fetchObject` → `EarningsStore.accept` → `acceptedListener` → `Main.mirrorEarningsForMonitor` → `Blackboard.getStock(...).setNextEarningsDate`.

### 1.8 `Market-Calendar-Refresher-Thread`

`MarketCalendarRefresher.run` → `refreshOnce` → `MarketCalendarClient.fetch` → `ProxyJsonFetcher.fetchObject` → `MarketCalendarStore.accept`.

### 1.9 `Options-Proxy-UDP-Receiver`

`OptionsIndicatorFrameReceiver.receiveLoop` → `IndicatorFrame.parseFrom` → `OptionsIndicatorStore.accept` → `AcceptedFrameListener.onAccepted` → `Main.mirrorFrameForMonitor` → `Blackboard.getStock(...).setDailyImpliedMove` / `setGammaFlip`. The interface Javadoc states the listener is "Notified for each frame the store accepted, on the receiver thread."

### 1.10 `main` thread

Constructs `RequestRegistry`, `TickMap`, `Blackboard`, `TradingGate`, `BrokerState`, `JsonTradingStateStore`, `ReconciliationManager`, the callback handlers, `EWrapperRaptor`, `IbkrSessionManager`, `MarketDataSubscriptionManager`, `TickByTickManager`, `OptionsIndicatorStore`, `OptionsIndicatorFrameReceiver`, `EarningsStore`, `MarketCalendarStore`, `UniverseReference`, `ConcentrationLimits`, `BracketOrderExecutor`, and the three strategies. Calls `stateStore.recoveredFromBackup()`, `stateStore.activeTrades()`, `tradingGate.requireManualIntervention`, `wrapper.attachLifecycle`, `sessionManager.attachSubscriptionManager`, `optionsIndicatorFrameReceiver.start()`, `Thread::start` on every thread listed above, `sessionManager.start()`, `tickByTickManager.getActiveStreamCount()`, then blocks on `new CountDownLatch(1).await()`.

### 1.11 `Trading-Engine-Shutdown` hook thread

`strategyThreads.forEach(Thread::interrupt)`, `earningsRefresherForShutdown.interrupt()`, `marketCalendarForShutdown.interrupt()`, `receiverForShutdown.stop()`, `sessionManager.close()`, `reconciliationManager.close()`, `LogManager.shutdown()`.

### 1.12 Swing Event Dispatch Thread and monitor threads

- EDT: `BlackboardMonitor` constructor, `updateView`, `applyColumnVisibility`, `updateButtonStyles`, `addAccountLabel`, `updateTableCellIfChanged`, `updatePnLLabel`, the body of the `updateDashboardData` `invokeLater` lambda, `startSiren` (called from inside that lambda), `performSystemStatusFade` (`Timer(50, …)`), `monitorTable.repaint()` (`Timer(33, …)`), `FlashCellRenderer.getTableCellRendererComponent`, and the `MouseAdapter` handlers.
- Monitor refresh daemon thread: the `startDataRefreshThread` loop, which calls `updateDashboardData()`; that method's entire body is inside `SwingUtilities.invokeLater`.
- Siren audio daemon thread: the `startSiren` lambda, reading `currentFadeAlpha` and writing to `SourceDataLine`.

### 1.13 Cross-boundary handoffs

| Producer thread | Mechanism | Consumer thread |
|---|---|---|
| IBKR reader | `volatile` fields on `Stock` / `Account` | strategy poll threads, monitor refresh thread |
| IBKR reader | `MarketDataInputStore.record` | strategy poll threads via `MarketDataFreshness` |
| IBKR reader | `BracketOrder.status` (`volatile`), from which the strategy threads derive `Stock.PositionState`; `Blackboard.releaseGlobalPending`, `Blackboard.releasePosition` | strategy poll threads |
| IBKR reader | `lifecycleExecutor.execute` / `.schedule` | `IBKR-Session-Lifecycle` |
| `Options-Proxy-UDP-Receiver` | `OptionsIndicatorStore` (`ConcurrentMap` + `AtomicReference`) | strategy poll threads |
| `Earnings-Refresher-Thread` | `EarningsStore.accepted` `AtomicReference` | strategy poll threads |
| `Market-Calendar-Refresher-Thread` | `MarketCalendarStore.session` `AtomicReference` | strategy poll threads |
| any | `TradingGate.state` `AtomicReference` | strategy poll threads |
| monitor refresh thread | `SwingUtilities.invokeLater` | Swing EDT |
| `main` | `SwingUtilities.invokeLater` | Swing EDT |

---

## 2. State Synchronization Mechanisms

### 2.1 Concurrent collections

| Declaration | Class |
|---|---|
| `Map<String, Stock> stocks = new ConcurrentHashMap<>()` | `Blackboard` |
| `Map<Integer, ReqData> idToData = new ConcurrentHashMap<>()` | `RequestRegistry` |
| `Map<Integer, BracketOrder> identifierToBracketMap = new ConcurrentHashMap<>()` | `OrderRegistry` |
| `Map<Long, BracketOrder> permanentIdentifierToBracketMap = new ConcurrentHashMap<>()` | `OrderRegistry` |
| `Map<String, BracketOrder> orderReferenceToBracketMap = new ConcurrentHashMap<>()` | `OrderRegistry` |
| `Map<String, BracketOrder> tradeIdentifierToBracketMap = new ConcurrentHashMap<>()` | `OrderRegistry` |
| `Map<String, AtomicReferenceArray<Reading>> readings = new ConcurrentHashMap<>()` | `MarketDataInputStore` |
| `Map<String, Integer> liveMarketDataRequests = new ConcurrentHashMap<>()` | `MarketDataSubscriptionManager` |
| `Map<String, Integer> minuteHistoryRequests = new ConcurrentHashMap<>()` | `MarketDataSubscriptionManager` |
| `Map<String, Integer> activeRequests = new ConcurrentHashMap<>()` | `TickByTickManager` |
| `ConcurrentMap<String, TickerState> stateByTicker = new ConcurrentHashMap<>()` | `OptionsIndicatorStore` |
| `ConcurrentMap<String, PendingEntry> pendingEntries = new ConcurrentHashMap<>()` | `AbstractStrategy` |
| `Set<String> escalatedPendingEntries = ConcurrentHashMap.newKeySet()` | `AbstractStrategy` |
| `ConcurrentMap<String, String> lastUnreadyReason = new ConcurrentHashMap<>()` | `AbstractStrategy` |
| `ConcurrentMap<String, BracketOrder.Status> acknowledgedStatus = new ConcurrentHashMap<>()` | `AbstractStrategy` |
| `Map<String, Instant> lastExitByTicker = new ConcurrentHashMap<>()` | `OneSigmaDownsideMeanReversionStrategy`, `OneSigmaUpsideMeanReversionStrategy` |
| `Map<String, Integer> takeProfitUpdates = new ConcurrentHashMap<>()` | `OneSigmaDownsideMeanReversionStrategy`, `OneSigmaUpsideMeanReversionStrategy` |
| `Map<String, Instant> lastTakeProfitUpdate = new ConcurrentHashMap<>()` | `OneSigmaDownsideMeanReversionStrategy`, `OneSigmaUpsideMeanReversionStrategy` |
| `Map<Integer, List<Bar>> historyBuffer = new ConcurrentHashMap<>()` | `IntradayWilderAtrTracker`, `DailyWilderAtrCalculator`, `RsiTracker` |
| `Map<Integer, List<Double>> historyBuffer = new ConcurrentHashMap<>()` | `SimpleMovingAverageTracker` |
| `Map<String, Double> sum199`, `sum99`, `sum49`, `sum19`, `sum9` — all `ConcurrentHashMap` | `SimpleMovingAverageTracker` |
| `Map<String, VolumeWindow> windows = new ConcurrentHashMap<>()` | `MinuteVolumeTracker` |
| `Set<String> formatConfirmed = ConcurrentHashMap.newKeySet()` | `PriceTickHandler` |
| `Map<String, Long> lastUpdateTimestampMap = new ConcurrentHashMap<>()` | `BlackboardMonitor` |

`Collections.synchronizedList(new ArrayList<>())` is the value type inserted into `historyBuffer` by `IntradayWilderAtrTracker.onHistoricalData`, `DailyWilderAtrCalculator.onHistoricalData`, `RsiTracker.onHistoricalData`, and `SimpleMovingAverageTracker.onHistoricalData`.

`AtomicReferenceArray<Reading>` (length `MarketDataInput.values().length`) is the per-symbol value in `MarketDataInputStore.readings`, written by `record` and read by `readingFor`.

### 2.2 Atomic variables and thread-safe primitives

| Type | Field | Class |
|---|---|---|
| `AtomicInteger` | `orderId`, `requestId` | `IdManager` |
| `AtomicInteger` | `updateRequestCount` | `BracketOrder` |
| `AtomicInteger` | `activeStreamCount` | `TickByTickManager` |
| `AtomicLong` | `lastEntrySubmittedAtMillis` | `Blackboard` |
| `AtomicLong` | `acceptedFrames`, `rejectedFrames` | `OptionsIndicatorStore` |
| `AtomicLong` | `receivedDatagrams`, `malformedDatagrams` | `OptionsIndicatorFrameReceiver` |
| `AtomicLong` | `acceptedSnapshotCount`, `rejectedSnapshotCount` | `EarningsStore` |
| `AtomicLong` | `acceptedCount`, `rejectedCount` | `MarketCalendarStore` |
| `AtomicBoolean` | `reconnectScheduled`, `apiReadyHandled` | `IbkrSessionManager` |
| `AtomicBoolean` | `initialized` | `MarketDataSubscriptionManager` |
| `AtomicReference<EntryOwner>` | `globalPendingOwner` | `Blackboard` |
| `AtomicReference<State>` | `state` | `TradingGate` |
| `AtomicReference<GammaFlip>` | `gammaFlip` | `OptionsIndicatorStore` |
| `AtomicReference<Accepted>` | `accepted` | `EarningsStore` |
| `AtomicReference<String>` | `lastRejectionReason` | `EarningsStore`, `MarketCalendarStore` |
| `AtomicReference<MarketSession>` | `session` | `MarketCalendarStore` |
| `CountDownLatch` | `new CountDownLatch(1)` awaited at the end of `main` | `Main` |
| `EJavaSignal` | `signal`, driven by `signal.waitForSignal()` in the `IBKR-Reader` loop | `IbkrSessionManager` |
| `ScheduledExecutorService` | `lifecycleExecutor` (single thread, daemon) | `IbkrSessionManager` |
| `ScheduledExecutorService` | `scheduler` (single thread, daemon) | `ReconciliationManager` |

Compare-and-set / read-modify-write call sites:

| Operation | Location |
|---|---|
| `globalPendingOwner.compareAndSet(null, new EntryOwner(...))` | `Blackboard.tryAcquireGlobalPending` |
| `globalPendingOwner.compareAndSet(currentOwner, null)` inside a retry loop | `Blackboard.releaseGlobalPending` |
| `lastEntrySubmittedAtMillis.updateAndGet(previous -> Math.max(previous, atMillis))` | `Blackboard.recordEntrySubmitted` |
| `state.updateAndGet(...)` with `MANUAL_INTERVENTION` retained unless target is `MANUAL_INTERVENTION` or `STOPPING` | `TradingGate.transitionTo` |
| `requestId.accumulateAndGet(requestId, Math::max)`, `orderId.accumulateAndGet(orderId, Math::max)` | `IdManager.initializeRequestId`, `initializeOrderId` |
| `requestId.getAndIncrement()`, `orderId.getAndIncrement()` | `IdManager.getNextRequestId`, `getNextOrderId` |
| `apiReadyHandled.compareAndSet(false, true)` | `IbkrSessionManager.onNextValidId` |
| `reconnectScheduled.compareAndSet(false, true)` | `IbkrSessionManager.scheduleReconnect` |
| `initialized.compareAndSet(false, true)` | `MarketDataSubscriptionManager.initializeIfNeeded` |
| `activeStreamCount.compareAndSet(current, current + 1)` inside a `while (current < MAX_STREAMS)` loop | `TickByTickManager.tryRequestStream` |
| `activeStreamCount.decrementAndGet()` | `TickByTickManager.cancelStream` |
| `updateRequestCount.incrementAndGet()` | `BracketOrder.incrementUpdateCount` |
| `readings.computeIfAbsent(...).set(input.ordinal(), new Reading(...))` | `MarketDataInputStore.record` |
| `stocks.computeIfAbsent(ticker, Stock::new)` | `Blackboard.getStock` |
| `windows.computeIfAbsent(ticker, key -> new VolumeWindow())` | `MinuteVolumeTracker.updateVolume` |
| `activePositionOwners.remove(normalizedTicker, normalizedStrategy)` | `Blackboard.releasePosition` |
| `tradeIdentifierToBracketMap.remove(bracketOrder.getTradeId(), bracketOrder)` | `OrderRegistry.unregister` |

### 2.3 Explicit locks, synchronized blocks, and serialization barriers

**`synchronized` methods**

| Class | Methods | Guarded state |
|---|---|---|
| `Blackboard` | `tryReservePosition`, `releasePosition`, `isPositionOwnedBy`, `getPositionOwner`, `getActivePositionCount`, `getPositionOwnersSnapshot` | `activePositionOwners` (`HashMap`) |
| `BracketOrder` | `registerOrderLeg`, `applyBrokerUpdate`, `getLegStates` | `legStates` (`LinkedHashMap`) |
| `BrokerState` | `clear`, `recordPosition`, `recordOpenOrder`, `recordCompletedOrder`, `recordOrderStatus`, `recordExecution`, `snapshot`, `replaceWith` | `positions`, `orders`, `executions` (all `HashMap`) |
| `ReconciliationManager` | `attachClient`, `getLastResult`, `isReconciling`, `begin`, `onDisconnected`, `onPosition`, `onLivePortfolioPosition`, `onPositionEnd`, `onOpenOrder`, `onOpenOrderEnd`, `onCompletedOrder`, `onCompletedOrdersEnd`, `onExecution`, `onExecutionEnd`, `onOrderStatus`, `failEpoch` | `client`, `activeEpoch`, `nextEpoch`, `lastResult` |
| `JsonTradingStateStore` | `recoveredFromBackup`, `snapshot`, `activeTrades`, `findTrade`, `recordIntent`, `recordBrokerUpdate` | `tradesById` (`LinkedHashMap`), `recoveredFromBackup`, on-disk file |
| `OptionsIndicatorFrameReceiver` | `start`, `stop` | `socket`, `receiverThread`, `running` |
| `MinuteVolumeTracker.VolumeWindow` | `pending`, `setPending`, `size`, `sessionDate`, `commit`, `baseline` | `completed` (`ArrayDeque`), `sum`, `sessionDate`, `pending` |

**`synchronized` blocks**

| Location | Monitor |
|---|---|
| `MinuteVolumeTracker.updateVolume` — `synchronized (window)` | the per-ticker `VolumeWindow` instance |
| `IntradayWilderAtrTracker.onHistoricalDataUpdate` — `synchronized (bars)` | the `Collections.synchronizedList` for that request id |
| `RsiTracker.onHistoricalDataEnd` — `synchronized (bars)` | as above |
| `RsiTracker.onHistoricalDataUpdate` — `synchronized (bars)` | as above |

**Serialization barriers**

| Barrier | Location |
|---|---|
| Engine-wide single-owner entry gate (`AtomicReference<EntryOwner>` CAS from `null`) | `Blackboard.globalPendingOwner`; acquired in `EntryAdmission.tryAdmit`, released in `AbstractStrategy.acknowledgeStatusChange` (`WORKING_PARENT`, `PARTIAL_PARENT`, `POSITION_OPEN`, terminal-with-fill), `cleanupOwnedLifecycle`, `EntryAdmission.Reservation.release()` — which `close()` calls on any path that did not `keep()` — and `OrderLifecycleHandler.markPositionOpen` / `completeConfirmedFlat` |
| Per-ticker ownership map plus `config.getMaxActivePositions()` cap, under the `Blackboard` monitor | `Blackboard.tryReservePosition` |
| Per-ticker lifecycle state derived rather than stored, so the reader and strategy threads cannot disagree about it | `Stock.positionStateOf(boolean, BracketOrder)` |
| Per-symbol market data frozen for the length of one decision | `MarketSnapshot.of(Stock, long)` |
| Per-ticker and per-sector exposure caps, shared across strategies so one sector total is seen by all of them | `ConcentrationLimits.allowedQuantity(String, double, Decimal)` |
| Edge-triggering for work that must happen once per broker status | `AbstractStrategy.acknowledgedStatus` |
| Single application-wide mode authority; `MANUAL_INTERVENTION` is sticky except for `STOPPING` | `TradingGate.transitionTo` |
| All connect/reconnect/subscription work confined to one thread | `IbkrSessionManager.lifecycleExecutor` (`newSingleThreadScheduledExecutor`) |
| Reconciliation epoch collection confined by the instance monitor; timeout fires on a separate single-thread scheduler and re-checks `activeEpoch.number` under the same monitor | `ReconciliationManager` |
| Atomic journal write: temp file, `channel.force(true)`, backup copy, `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)` with `AtomicMoveNotSupportedException` fallback | `JsonTradingStateStore.persist` |
| One-shot API-ready and reconnect-scheduling guards | `IbkrSessionManager.apiReadyHandled`, `reconnectScheduled` |
| One-shot subscription initialization guard | `MarketDataSubscriptionManager.initialized` |
| Bounded stream slots (`MAX_STREAMS = 5`) via CAS loop | `TickByTickManager.tryRequestStream` |
| Request pacing `Thread.sleep(500)` / `Thread.sleep(10)` / `Thread.sleep(50)` | `MarketDataSubscriptionManager.initializeIfNeeded`, `resubscribeAfterDataLoss` |
| Swing single-thread confinement | `SwingUtilities.invokeLater` in `Main.main` and `BlackboardMonitor.updateDashboardData` |
| Receiver shutdown join | `OptionsIndicatorFrameReceiver.stop` — `thread.join(2000)` |
| Process liveness barrier | `Main.main` — `new CountDownLatch(1).await()` |
| Interrupt-based cooperative stop | `AbstractStrategy.run`, `EarningsRefresher.run`, `MarketCalendarRefresher.run`, `BlackboardMonitor.startDataRefreshThread` — all loop on `!Thread.currentThread().isInterrupted()` and re-assert the flag in their `InterruptedException` handlers |

---

## 3. Concurrency Variables and Fields

### 3.1 `mwd.trading.state.Blackboard`

| Field | Declaration | Mutating methods |
|---|---|---|
| `netCallPremium` | `volatile double` | `setNetCallPremium` |
| `netPutPremium` | `volatile double` | `setNetPutPremium` |
| `systemHalted` | `volatile boolean` | `setSystemHalted` |
| `systemUpdateRequired` | `volatile boolean` | `setSystemUpdateRequired` |
| `openOrderEnd` | `volatile boolean` | `setOpenOrderEnd` |
| `activePositionOwners` | `final Map<String,String>` (`HashMap`), all access `synchronized` | `tryReservePosition`, `releasePosition` |
| `stocks` | `final Map<String,Stock>` (`ConcurrentHashMap`) | `getStock` (via `computeIfAbsent`) |
| `globalPendingOwner` | `final AtomicReference<EntryOwner>` | `tryAcquireGlobalPending`, `releaseGlobalPending` |
| `lastEntrySubmittedAtMillis` | `final AtomicLong` | `recordEntrySubmitted` |

### 3.2 `mwd.trading.domain.Stock`

| Field | Declaration | Mutating methods |
|---|---|---|
| `contract` | `Contract` (non-volatile) | `setContract` |
| `activeBracket` | `volatile BracketOrder` | `setActiveBracket` |
| `isTradeable` | `volatile boolean` | `setTradeable` |
| `lastUpdate` | `volatile long` | `refreshLastUpdate` (called by every setter that ends with it) |
| `lastPrice`, `bid`, `ask`, `markPrice`, `dailyHigh`, `dailyLow`, `open`, `previousClose`, `dailyVWAP` | `volatile double` | `setLastPrice`, `setBid`, `setAsk`, `setMarkPrice`, `setDailyHigh`, `setDailyLow`, `setOpen`, `setPreviousClose`, `setDailyVWAP` |
| `sma10`, `sma20`, `sma50`, `sma100`, `sma200` | `volatile double` | `setSma10`, `setSma20`, `setSma50`, `setSma100`, `setSma200` |
| `bidSize`, `askSize`, `lastSize` | `volatile Decimal` | `setBidSize`, `setAskSize`, `setLastSize` |
| `intradayVolume`, `lastMinuteVolume`, `averageLast15MinuteVolume`, `averageDailyVolume` | `volatile Decimal` | `setIntradayVolume`, `setLastMinuteVolume`, `setAverageLast15MinuteVolume`, `setAverageDailyVolume` |
| `gammaFlip` | `volatile double` | `setGammaFlip` |
| `dailyImpliedMove`, `weeklyImpliedMove`, `monthlyImpliedMove` | `volatile double` | `setDailyImpliedMove`, `setWeeklyImpliedMove`, `setMonthlyImpliedMove` |
| `intradayATR4`, `intradayATR5`, `intradayATR12`, `intradayATR15`, `dailyATR` | `volatile double` | `setIntradayATR4`, `setIntradayATR5`, `setIntradayATR12`, `setIntradayATR15`, `setDailyATR` |
| `relativeStrengthIndex` | `volatile double` | `setRSI` |
| `positionSize` | `volatile Decimal` | `setPositionSize` |
| `averageCost`, `marketValue`, `realizedPnL`, `unrealizedPnl` | `volatile double` | `setAverageCost`, `setMarketValue`, `setRealizedPnL`, `setUnrealizedPnl` |
| `nextEarningsDate` | `Instant` (non-volatile) | `setNextEarningsDate` |
| `lastMinuteBar` | `volatile Bar` | `setLastMinuteBar` |

### 3.3 `mwd.trading.domain.Account`

| Field | Declaration | Mutating methods |
|---|---|---|
| `accountId` | `volatile String` | `setAccountId` |
| `lastRefreshedAtMillis` | `volatile long` | `setLastRefreshedAtMillis` |
| `netLiquidation`, `totalCashValue`, `settledCash`, `buyingPower`, `availableFunds`, `excessLiquidity`, `realizedPnL`, `unrealizedPnL`, `cushion` | `volatile double` | `setNetLiquidation`, `setTotalCashValue`, `setSettledCash`, `setBuyingPower`, `setAvailableFunds`, `setExcessLiquidity`, `setRealizedPnL`, `setUnrealizedPnL`, `setCushion` |

### 3.4 `mwd.trading.strategy.AbstractStrategy`

| Field | Declaration | Mutating methods |
|---|---|---|
| `pendingEntries` | `final ConcurrentMap<String,PendingEntry>` | `evaluateNewEntry` (put), `acknowledgeStatusChange` (remove on `POSITION_OPEN`), `cleanupOwnedLifecycle` (remove), `rollbackEntryReservation` (remove) |
| `escalatedPendingEntries` | `final Set<String>` = `ConcurrentHashMap.newKeySet()` | `escalate` (add), `acknowledgeStatusChange` (remove on `POSITION_OPEN`), `cleanupOwnedLifecycle` (remove), `rollbackEntryReservation` (remove) |
| `acknowledgedStatus` | `final ConcurrentMap<String,BracketOrder.Status>` | `acknowledgeStatusChange` (`put`, and `remove` when the bracket is gone), `cleanupOwnedLifecycle` (remove), `rollbackEntryReservation` (remove) |
| `lastUnreadyReason` | `final ConcurrentMap<String,String>` | `entryInputsReady` (`put` / `remove`) |

### 3.5 `OneSigmaDownsideMeanReversionStrategy` and `OneSigmaUpsideMeanReversionStrategy`

| Field | Declaration | Mutating methods |
|---|---|---|
| `lastExitByTicker` | `final Map<String,Instant>` (`ConcurrentHashMap`) | `onPositionClosed` (put) |
| `takeProfitUpdates` | `final Map<String,Integer>` (`ConcurrentHashMap`) | take-profit budget accounting (put), `onPositionClosed` (remove) |
| `lastTakeProfitUpdate` | `final Map<String,Instant>` (`ConcurrentHashMap`) | take-profit budget accounting (put), `onPositionClosed` (remove) |

`TwoSigmaDownsideMeanReversionStrategy` declares no mutable per-ticker fields.

### 3.6 `mwd.trading.broker.ibkr.IdManager`

| Field | Declaration | Mutating methods |
|---|---|---|
| `orderId` | `final AtomicInteger` | `initializeOrderId`, `getNextOrderId` |
| `requestId` | `final AtomicInteger` | `initializeRequestId`, `getNextRequestId` |

### 3.7 `mwd.trading.broker.ibkr.RequestRegistry`

| Field | Declaration | Mutating methods |
|---|---|---|
| `idToData` | `final Map<Integer,ReqData>` (`ConcurrentHashMap`) | `register`, `unregister` |

`ReqData` holds an `EnumSet<DataConsumer>` returned directly by `getConsumersFor` (no defensive copy).

### 3.8 `mwd.trading.broker.ibkr.TimeManager`

| Field | Declaration | Mutating methods |
|---|---|---|
| `offsetMillis` | `volatile long` | `setOffset` (called from `BrokerTimeHandler.onCurrentTime` on the reader thread); read by `getMarketTime` from strategy threads and the Swing EDT |

`TickMap` holds only `private final boolean liveIBKRData`, set in the constructor.

### 3.9 `mwd.trading.broker.ibkr.EWrapperRaptor`

| Field | Declaration | Mutating methods |
|---|---|---|
| `sessionManager` | `volatile IbkrSessionManager` | `attachLifecycle` |
| `marketDataSubscriptionManager` | `volatile MarketDataSubscriptionManager` | `attachLifecycle` |

### 3.10 `mwd.trading.broker.ibkr.IbkrSessionManager`

| Field | Declaration | Mutating methods |
|---|---|---|
| `reconnectScheduled` | `final AtomicBoolean` | `connectNow` (`set(false)`), `scheduleReconnect` (`compareAndSet`) |
| `apiReadyHandled` | `final AtomicBoolean` | `onNextValidId` (`compareAndSet`), `onConnectionClosed` (`set(false)`), `connectNow` (`set(false)`) |
| `subscriptionManager` | `volatile MarketDataSubscriptionManager` | `attachSubscriptionManager` |
| `stopping` | `volatile boolean` | `close` |
| `hasConnectedBefore` | `volatile boolean` | `initializeConnectedSession` |
| `currentPort` | `volatile int` | constructor, `updatePortFromMessage` |
| `readerThread` | `volatile Thread` | `startReaderThread` |
| `lifecycleExecutor` | `final ScheduledExecutorService` (single daemon thread `IBKR-Session-Lifecycle`) | `close` (`shutdownNow`) |
| `signal` | `final EJavaSignal` | signalled by the TWS `EReader`; awaited in the `IBKR-Reader` loop |

### 3.11 `mwd.trading.lifecycle.TradingGate`

| Field | Declaration | Mutating methods |
|---|---|---|
| `state` | `final AtomicReference<State>` | `transitionTo`, `requireManualIntervention` |

### 3.12 `mwd.trading.execution.OrderRegistry`

| Field | Declaration | Mutating methods |
|---|---|---|
| `identifierToBracketMap` | `final Map<Integer,BracketOrder>` (`ConcurrentHashMap`) | `register`, `unregister` |
| `permanentIdentifierToBracketMap` | `final Map<Long,BracketOrder>` (`ConcurrentHashMap`) | `register`, `recordBrokerIdentity`, `unregister` |
| `orderReferenceToBracketMap` | `final Map<String,BracketOrder>` (`ConcurrentHashMap`) | `register`, `recordBrokerIdentity`, `unregister` |
| `tradeIdentifierToBracketMap` | `final Map<String,BracketOrder>` (`ConcurrentHashMap`) | `register`, `unregister` |

### 3.13 `mwd.trading.execution.BracketOrder`

| Field | Declaration | Mutating methods |
|---|---|---|
| `legStates` | `final Map<Integer,OrderLegState>` (`LinkedHashMap`), all access `synchronized` | `registerOrderLeg`, `applyBrokerUpdate` |
| `updateRequestCount` | `final AtomicInteger` | `incrementUpdateCount` |
| `slices` | `final List<ExitSlice>` (`ArrayList`, unsynchronized) | `addSlice`; returned unwrapped by `getSlices` |
| `entryPrice` | `volatile double` | `setEntryPrice` |
| `totalQuantity` | `final Decimal` | constructor |
| `status` | `volatile Status` | `setStatus` |
| `filledQuantity` | `volatile Decimal` | `setFilledQuantity` |
| `remainingQuantity` | `volatile Decimal` | `setRemainingQuantity` |

`BracketOrder.OrderLegState`

| Field | Declaration | Mutating methods |
|---|---|---|
| `permanentId` | `volatile long` | `applyBrokerUpdate` |
| `status` | `volatile String` | `applyBrokerUpdate` |
| `filledQuantity` | `volatile Decimal` | `applyBrokerUpdate` |
| `remainingQuantity` | `volatile Decimal` | `applyBrokerUpdate` |
| `acknowledged` | `volatile boolean` | `applyBrokerUpdate` |

`BracketOrder.ExitSlice`

| Field | Declaration | Mutating methods |
|---|---|---|
| `takeProfitPrice` | `volatile double` | `setTakeProfitPrice` — called from `BracketOrderExecutor.placeTripleThreat` and `updateTripleThreatExits` |
| `stopLossPrice` | `volatile double` | `setStopLossPrice` — same call sites |
| `timeExit` | `volatile long` | `setTimeExit` — same call sites |
| `isFilled` | `volatile boolean` | `setFilled` — called from `OrderLifecycleHandler.onOrderStatus` and `onCompletedOrder` on the reader thread; read by `BlackboardMonitor` on the EDT |
| `lastModificationTime` | `long` (non-volatile) | `setLastModificationTime` |

### 3.14 `mwd.trading.reconciliation.BrokerState`

| Field | Declaration | Mutating methods |
|---|---|---|
| `positions` | `final Map<String,PositionRecord>` (`HashMap`), all access `synchronized` | `recordPosition`, `clear`, `replaceWith` |
| `orders` | `final Map<Integer,OrderRecord>` (`HashMap`), all access `synchronized` | `recordOrder` (via `recordOpenOrder` / `recordCompletedOrder`), `recordOrderStatus`, `clear`, `replaceWith` |
| `executions` | `final Map<String,ExecutionRecord>` (`HashMap`), all access `synchronized` | `recordExecution`, `clear`, `replaceWith` |

### 3.15 `mwd.trading.reconciliation.ReconciliationManager`

| Field | Declaration | Mutating methods |
|---|---|---|
| `client` | `EClientSocket`, guarded by the instance monitor | `attachClient` |
| `activeEpoch` | `CollectionEpoch`, guarded by the instance monitor | `begin`, `onDisconnected`, `completeIfReady`, `failEpoch` |
| `nextEpoch` | `long`, guarded by the instance monitor | `begin` (`++nextEpoch`) |
| `lastResult` | `volatile Result` | `completeIfReady`, `failWithoutSnapshot` |
| `CollectionEpoch.positionsComplete` | `boolean`, mutated under the manager monitor | `onPositionEnd` |
| `CollectionEpoch.openOrdersComplete` | `boolean`, mutated under the manager monitor | `onOpenOrderEnd` |
| `CollectionEpoch.completedOrdersComplete` | `boolean`, mutated under the manager monitor | `onCompletedOrdersEnd` |
| `CollectionEpoch.executionsComplete` | `boolean`, mutated under the manager monitor | `onExecutionEnd` |
| `CollectionEpoch.collected` | `final BrokerState` | `targetState()` routes every record call here while an epoch is active |
| `scheduler` | `final ScheduledExecutorService` (single daemon thread `IBKR-Reconciliation-Timeout`) | `close` (`shutdownNow`) |

### 3.16 `mwd.trading.persistence.JsonTradingStateStore`

| Field | Declaration | Mutating methods |
|---|---|---|
| `tradesById` | `final Map<String,TradeRecord>` (`LinkedHashMap`), all access `synchronized` | `recordIntent`, `loadFrom` |
| `recoveredFromBackup` | `boolean`, read under `synchronized` | `load` |
| on-disk `statePath` / `temporaryPath` / `backupPath` | files | `persist`, called from `recordIntent` under the instance monitor |

### 3.17 `mwd.trading.marketdata.MarketDataInputStore`

| Field | Declaration | Mutating methods |
|---|---|---|
| `readings` | `final Map<String,AtomicReferenceArray<Reading>>` (`ConcurrentHashMap`) | `record` (`computeIfAbsent` + `AtomicReferenceArray.set`), `markAllStale` (`clear`) |

### 3.18 `mwd.trading.marketdata.MarketDataSubscriptionManager`

| Field | Declaration | Mutating methods |
|---|---|---|
| `liveMarketDataRequests` | `final Map<String,Integer>` (`ConcurrentHashMap`) | `requestLiveMarketData` (put), `cancelTrackedRequests` (`clear`) |
| `minuteHistoryRequests` | `final Map<String,Integer>` (`ConcurrentHashMap`) | `requestMinuteHistory` (put), `cancelTrackedRequests` (`clear`) |
| `initialized` | `final AtomicBoolean` | `initializeIfNeeded` (`compareAndSet`), `resetAfterSocketReconnect` (`set(false)`) |

### 3.19 `mwd.trading.marketdata.TickByTickManager`

| Field | Declaration | Mutating methods |
|---|---|---|
| `activeStreamCount` | `final AtomicInteger` | `tryRequestStream` (`compareAndSet`), `cancelStream` (`decrementAndGet`) |
| `activeRequests` | `final Map<String,Integer>` (`ConcurrentHashMap`) | `executeRequest` (put), `cancelStream` (remove) |

### 3.20 `mwd.trading.optionsproxy.OptionsIndicatorStore`

| Field | Declaration | Mutating methods |
|---|---|---|
| `stateByTicker` | `final ConcurrentMap<String,TickerState>` | `accept` (put) |
| `gammaFlip` | `final AtomicReference<GammaFlip>` | `accept` (`set`) |
| `acceptedFrames` | `final AtomicLong` | `accept` (`incrementAndGet`) |
| `rejectedFrames` | `final AtomicLong` | `reject` (`incrementAndGet`) |
| `lastRejectionReason` | `volatile String` | `reject` |

### 3.21 `mwd.trading.optionsproxy.OptionsIndicatorFrameReceiver`

| Field | Declaration | Mutating methods |
|---|---|---|
| `receivedDatagrams` | `final AtomicLong` | `receiveLoop` (`incrementAndGet`) |
| `malformedDatagrams` | `final AtomicLong` | `receiveLoop` (`incrementAndGet`) |
| `lastDatagramAtUnixMs` | `volatile long` | `receiveLoop` |
| `socket` | `volatile DatagramSocket` | `start` (`synchronized`), `stop` (`synchronized`) |
| `receiverThread` | `volatile Thread` | `start` (`synchronized`), `stop` (`synchronized`) |
| `running` | `volatile boolean` | `start` (`synchronized`), `stop` (`synchronized`); read by `receiveLoop` |

### 3.22 `mwd.trading.earnings.EarningsStore`

| Field | Declaration | Mutating methods |
|---|---|---|
| `accepted` | `final AtomicReference<Accepted>` | `accept` (`set`) |
| `acceptedSnapshotCount` | `final AtomicLong` | `accept` |
| `rejectedSnapshotCount` | `final AtomicLong` | `reject` |
| `lastRejectionReason` | `final AtomicReference<String>` | `accept` (`set(null)`), `reject` (`set`) |

`EarningsRefresher.lastReportedFailure` is a plain `String` field confined to `Earnings-Refresher-Thread`.

### 3.23 `mwd.trading.calendar.MarketCalendarStore`

| Field | Declaration | Mutating methods |
|---|---|---|
| `session` | `final AtomicReference<MarketSession>` | `accept` (`set`) |
| `acceptedCount` | `final AtomicLong` | `accept` |
| `rejectedCount` | `final AtomicLong` | `reject` |
| `lastRejectionReason` | `final AtomicReference<String>` | `accept` (`set(null)`), `reject` (`set`) |

`MarketCalendarRefresher.lastReportedFailure` is a plain `String` field confined to `Market-Calendar-Refresher-Thread`.

### 3.24 Indicator trackers

| Class | Field | Declaration | Mutating methods |
|---|---|---|---|
| `IntradayWilderAtrTracker` | `historyBuffer` | `final Map<Integer,List<Bar>>` (`ConcurrentHashMap` of `Collections.synchronizedList`) | `onHistoricalData` (`computeIfAbsent(...).add`), `onHistoricalDataUpdate` (`add` / `remove(0)` inside `synchronized (bars)`) |
| `DailyWilderAtrCalculator` | `historyBuffer` | `final Map<Integer,List<Bar>>` (same) | `onHistoricalData` (add), `onHistoricalDataEnd` (`remove(reqId)`) |
| `RsiTracker` | `historyBuffer` | `final Map<Integer,List<Bar>>` (same) | `onHistoricalData` (add), `onHistoricalDataUpdate` (`add` / `remove(0)` inside `synchronized (bars)`) |
| `SimpleMovingAverageTracker` | `historyBuffer` | `final Map<Integer,List<Double>>` (same) | `onHistoricalData` (add), `onHistoricalDataEnd` (`remove(reqId)`) |
| `SimpleMovingAverageTracker` | `sum199`, `sum99`, `sum49`, `sum19`, `sum9` | `final Map<String,Double>` (`ConcurrentHashMap`) | `onHistoricalDataEnd` (put) |
| `MinuteVolumeTracker` | `windows` | `final Map<String,VolumeWindow>` (`ConcurrentHashMap`) | `updateVolume` (`computeIfAbsent`) |
| `MinuteVolumeTracker.VolumeWindow` | `completed` | `final Deque<BigDecimal>` (`ArrayDeque`), guarded by the window monitor | `commit` |
| `MinuteVolumeTracker.VolumeWindow` | `sum` | `BigDecimal`, guarded by the window monitor | `commit` |
| `MinuteVolumeTracker.VolumeWindow` | `sessionDate` | `LocalDate`, guarded by the window monitor | `commit` |
| `MinuteVolumeTracker.VolumeWindow` | `pending` | `MinuteBar`, guarded by the window monitor | `setPending` |

`SizeTickHandler`, `MinuteBarHandler`, `BrokerTimeHandler`, `NextValidIdHandler`, `IbkrErrorHandler`, `BracketOrderExecutor`, `UniverseReference`, and `ConcentrationLimits` declare no mutable instance fields; they mutate only the shared state listed in §3.1–§3.3 and §3.12–§3.17.

`AccountEventHandler` holds one of its own: `reportedUnhandledKeys`, a `ConcurrentHashMap.newKeySet()` that makes the DEBUG line for an unread account tag fire once rather than on every batch. It is written only on the reader thread, and would be correct unsynchronized; the concurrent set states the intent rather than relying on that.

### 3.25 `mwd.trading.ui.BlackboardMonitor`

| Field | Declaration | Mutating methods |
|---|---|---|
| `lastUpdateTimestampMap` | `final Map<String,Long>` (`ConcurrentHashMap`) | `updateTableCellIfChanged` (put, EDT); read by `FlashCellRenderer.getTableCellRendererComponent` (EDT) |
| `currentFadeAlpha` | `volatile float` | `performSystemStatusFade` (EDT); read by the siren audio thread |
| `isSirenStarted` | `volatile boolean` | `startSiren` (EDT) |
| `hoveredRowIndex` | `int` (non-volatile) | `MouseAdapter.mouseMoved`, `mouseExited` (EDT) |
| `currentViewState` | `ViewState` (non-volatile) | `updateView` (EDT) |
| `isFadingUp` | `boolean` (non-volatile) | `performSystemStatusFade` (EDT) |
