---
paths:
  - "trading-engine/**/*"
---

# Broker Reconciliation Flow

Traces the collection of a complete IBKR snapshot after connect or reconnect, its
comparison with the local JSON journal, and the resulting `TradingGate` transition.

## 1. Workflow Components

| Component | Role in this flow |
| --- | --- |
| `mwd.trading.broker.ibkr.IbkrSessionManager` | Starts a reconciliation epoch after the API handshake and after connectivity restoration; reports disconnects. |
| `mwd.trading.reconciliation.ReconciliationManager` | Owns the epoch, issues the four snapshot requests, collects callbacks, compares, and moves the gate. |
| `mwd.trading.reconciliation.ReconciliationManager.CollectionEpoch` | Private holder of the epoch number, execution request ID, a private `BrokerState`, and four completion flags. |
| `mwd.trading.reconciliation.ReconciliationManager.Result` | Record `(boolean matched, String reason, List<String> differences, long completedAtEpochMillis)`. |
| `mwd.trading.reconciliation.BrokerState` | Accumulates `PositionRecord`, `OrderRecord`, and `ExecutionRecord` values and produces an immutable `Snapshot`. |
| `mwd.trading.reconciliation.BrokerState.Snapshot` | Record `(long asOfEpochMillis, List<PositionRecord>, List<OrderRecord>, List<ExecutionRecord>)`. |
| `mwd.trading.persistence.JsonTradingStateStore` | Supplies the local `StateFile` the broker snapshot is compared against. |
| `mwd.trading.persistence.JsonTradingStateStore.StateFile` / `TradeRecord` / `LegRecord` | The local intent journal records used in the comparison. |
| `mwd.trading.lifecycle.TradingGate` | Receives `transitionTo(EngineMode.RECONCILING/READY/DEGRADED, String)` or `requireManualIntervention(String)`. |
| `mwd.trading.lifecycle.EngineMode` | Enum of the modes used above. |
| `mwd.trading.broker.ibkr.EWrapperRaptor` | Delivers `position`, `positionEnd`, `openOrder`, `openOrderEnd`, `completedOrder`, `completedOrdersEnd`, `execDetails`, `execDetailsEnd`, `orderStatus`. |
| `mwd.trading.broker.ibkr.callback.AccountEventHandler` | Forwards `position`, `positionEnd`, and `updatePortfolio` into `ReconciliationManager`. |
| `mwd.trading.execution.OrderLifecycleHandler` | Forwards every order and execution callback into `ReconciliationManager` before resolving the local bracket. |
| `com.ib.client.EClientSocket` | Carries `reqPositions`, `reqAllOpenOrders`, `reqCompletedOrders`, `reqExecutions`. |
| `com.ib.client.ExecutionFilter` | Empty filter passed to `reqExecutions`. |
| `mwd.trading.state.Blackboard` | Supplies request IDs through the `IntSupplier` `blackboard::getNextRequestId`. |
| `mwd.trading.app.Main` | Constructs the manager with the managed-symbol set and closes it from the shutdown hook. |

## 2. Execution Path

### Epoch start

1. **Initiating Component:** `IbkrSessionManager.initializeConnectedSession()` (on `IBKR-Session-Lifecycle`)
   **Method Invocation:** `reconciliationManager.begin("IBKR API session established; validating broker state")`
   **Receiving Component:** `ReconciliationManager`

2. **Initiating Component:** `IbkrSessionManager.restoreBrokerConnectivity(boolean)` (error codes `1101`/`1102`)
   **Method Invocation:** `reconciliationManager.begin("IBKR server connectivity restored")`
   **Receiving Component:** `ReconciliationManager`

3. **Initiating Component:** `ReconciliationManager.begin(String)`
   **Method Invocation:** `client.isConnected()`; when false, `failWithoutSnapshot(String)` → `tradingGate.requireManualIntervention(String)`
   **Receiving Component:** `EClientSocket`, `TradingGate`

4. **Initiating Component:** `ReconciliationManager.begin(String)`
   **Method Invocation:** `requestIdSupplier.getAsInt()` (bound to `Blackboard.getNextRequestId()`), `new CollectionEpoch(++nextEpoch, executionRequestId)`, `tradingGate.transitionTo(EngineMode.RECONCILING, reason)`
   **Receiving Component:** `Blackboard`, `TradingGate`

5. **Initiating Component:** `ReconciliationManager.begin(String)`
   **Method Invocation:** `client.reqPositions()`, `client.reqAllOpenOrders()`, `client.reqCompletedOrders(false)`, `client.reqExecutions(executionRequestId, new ExecutionFilter())`; a `RuntimeException` calls `failEpoch(long, String)`
   **Receiving Component:** `EClientSocket`

6. **Initiating Component:** `ReconciliationManager.begin(String)`
   **Method Invocation:** `scheduler.schedule(() -> failEpoch(epoch.number, "Timed out waiting for the complete IBKR broker snapshot"), timeout.toMillis(), TimeUnit.MILLISECONDS)` with a default timeout of 20 seconds
   **Receiving Component:** `ScheduledExecutorService` thread `IBKR-Reconciliation-Timeout`

### Snapshot collection (IBKR reader thread)

7. **Initiating Component:** `EWrapperRaptor.position(String account, Contract contract, Decimal pos, double avgCost)`
   **Method Invocation:** `accountEventHandler.onPosition(...)` → `reconciliationManager.onPosition(String, Contract, Decimal, double)` → `targetState().recordPosition(...)`
   **Receiving Component:** `CollectionEpoch.collected` (a `BrokerState`)

8. **Initiating Component:** `EWrapperRaptor.positionEnd()`
   **Method Invocation:** `accountEventHandler.onPositionEnd()` → `reconciliationManager.onPositionEnd()` → sets `positionsComplete` and calls `completeIfReady(CollectionEpoch)`
   **Receiving Component:** `ReconciliationManager`

9. **Initiating Component:** `EWrapperRaptor.openOrder(int, Contract, Order, OrderState)`
   **Method Invocation:** `orderLifecycleHandler.onOpenOrder(...)` → `reconciliationManager.onOpenOrder(int, Contract, Order, OrderState)` → `targetState().recordOpenOrder(...)`
   **Receiving Component:** `BrokerState`

10. **Initiating Component:** `EWrapperRaptor.openOrderEnd()`
    **Method Invocation:** `orderLifecycleHandler.onOpenOrderEnd()` → `blackboard.setOpenOrderEnd(true)` and `reconciliationManager.onOpenOrderEnd()` → sets `openOrdersComplete`, `completeIfReady(...)`
    **Receiving Component:** `Blackboard`, `ReconciliationManager`

11. **Initiating Component:** `EWrapperRaptor.completedOrder(Contract, Order, OrderState)`
    **Method Invocation:** `orderLifecycleHandler.onCompletedOrder(...)` → `reconciliationManager.onCompletedOrder(Contract, Order, OrderState)` → `targetState().recordCompletedOrder(...)`
    **Receiving Component:** `BrokerState`

12. **Initiating Component:** `EWrapperRaptor.completedOrdersEnd()`
    **Method Invocation:** `orderLifecycleHandler.onCompletedOrdersEnd()` → `reconciliationManager.onCompletedOrdersEnd()` → sets `completedOrdersComplete`, `completeIfReady(...)`
    **Receiving Component:** `ReconciliationManager`

13. **Initiating Component:** `EWrapperRaptor.execDetails(int reqId, Contract contract, Execution execution)`
    **Method Invocation:** `orderLifecycleHandler.onExecDetails(...)` → `reconciliationManager.onExecution(Contract, Execution)` → `targetState().recordExecution(...)`
    **Receiving Component:** `BrokerState`

14. **Initiating Component:** `EWrapperRaptor.execDetailsEnd(int reqId)`
    **Method Invocation:** `orderLifecycleHandler.onExecDetailsEnd(int)` → `reconciliationManager.onExecutionEnd(int)`; the flag is set only when `requestId == activeEpoch.executionRequestId`
    **Receiving Component:** `ReconciliationManager`

15. **Initiating Component:** `EWrapperRaptor.orderStatus(...)`
    **Method Invocation:** `orderLifecycleHandler.onOrderStatus(...)` → `reconciliationManager.onOrderStatus(int, String, Decimal, Decimal, long, int, int)` → `targetState().recordOrderStatus(...)`
    **Receiving Component:** `BrokerState`

16. **Initiating Component:** `EWrapperRaptor.updatePortfolio(...)`
    **Method Invocation:** `accountEventHandler.onUpdatePortfolio(...)` → `reconciliationManager.onLivePortfolioPosition(String, Contract, Decimal, double)` → `brokerState.recordPosition(...)` on the long-lived state, not the epoch's
    **Receiving Component:** `BrokerState`

### Comparison and gate transition

17. **Initiating Component:** `ReconciliationManager.completeIfReady(CollectionEpoch)`
    **Method Invocation:** `epoch.complete()` requiring all four flags; then `epoch.collected.snapshot()`
    **Receiving Component:** `BrokerState`

18. **Initiating Component:** `ReconciliationManager.completeIfReady(CollectionEpoch)`
    **Method Invocation:** `compare(BrokerState.Snapshot, stateStore.snapshot())`
    **Receiving Component:** `JsonTradingStateStore`, `ReconciliationManager`

19. **Initiating Component:** `ReconciliationManager.compare(...)`
    **Method Invocation:** `tradeIdFromOrderReference(String)` on each broker order and execution (accepting the `TE|` and `SC|` prefixes), then per-trade checks producing `differences` entries: unknown working order, unexpected managed position, active trade with no broker evidence, broker order absent locally, local working leg with no broker evidence, position with no recognized working protective exit
    **Receiving Component:** `BrokerState.OrderRecord`, `BrokerState.ExecutionRecord`, `BrokerState.PositionRecord`, `JsonTradingStateStore.TradeRecord`, `JsonTradingStateStore.LegRecord`

20. **Initiating Component:** `ReconciliationManager.compareLegIdentity(TradeRecord, LegRecord, OrderRecord, List<String>)`
    **Method Invocation:** compares API order ID, permanent ID, account, symbol, contract ID, expected action (`trade.action()` for `"PARENT"`, `oppositeAction(...)` otherwise), and `BigDecimal` filled+remaining against the broker total quantity
    **Receiving Component:** `differences` list

21. **Initiating Component:** `ReconciliationManager.completeIfReady(CollectionEpoch)`
    **Method Invocation:** `activeEpoch = null`, `brokerState.replaceWith(snapshot)`
    **Receiving Component:** `BrokerState`

22. **Initiating Component:** `ReconciliationManager.completeIfReady(CollectionEpoch)`
    **Method Invocation:** empty differences → `tradingGate.transitionTo(EngineMode.READY, "Broker state matched local trading-engine state")`; otherwise `tradingGate.requireManualIntervention(String.join("; ", differences))`
    **Receiving Component:** `TradingGate`

23. **Initiating Component:** `ScheduledExecutorService` (`IBKR-Reconciliation-Timeout`)
    **Method Invocation:** `failEpoch(long, String)` → `failWithoutSnapshot(String)` → `tradingGate.requireManualIntervention(String)`; a no-op when `activeEpoch` is null or its number differs
    **Receiving Component:** `ReconciliationManager`, `TradingGate`

24. **Initiating Component:** `IbkrSessionManager.onConnectionClosed()` / `onError(int, String)` for codes `1100`, `1300`, `502`, `504`, `509`
    **Method Invocation:** `reconciliationManager.onDisconnected(String)` → `activeEpoch = null` and `tradingGate.transitionTo(EngineMode.DEGRADED, reason)`
    **Receiving Component:** `ReconciliationManager`, `TradingGate`

25. **Initiating Component:** `Main` shutdown hook thread
    **Method Invocation:** `reconciliationManager.close()` → `scheduler.shutdownNow()`
    **Receiving Component:** `ScheduledExecutorService`

## 3. Data Payloads and State Handoffs

### Objects passed

- `Set<String> managedSymbols` — copied from the market-data symbol list in `Main` and used to filter broker positions during comparison.
- `IntSupplier requestIdSupplier` — `blackboard::getNextRequestId`, invoked once per epoch to obtain the `reqExecutions` request ID that later matches `execDetailsEnd`.
- `CollectionEpoch` — private per-attempt container; while it is active every callback is written into `epoch.collected` instead of the long-lived `brokerState`, selected by `targetState()`.
- `BrokerState.Snapshot` — immutable, sorted lists of `PositionRecord`, `OrderRecord`, `ExecutionRecord`; installed into the long-lived `BrokerState` via `replaceWith(Snapshot)`.
- `JsonTradingStateStore.StateFile` — the local side of the comparison, produced by `stateStore.snapshot()`.
- `List<String> differences` — the comparison output, joined into the `requireManualIntervention` reason and stored on `Result`.
- `ReconciliationManager.Result` — held in a `volatile` field and readable through `getLastResult()`.
- `String orderReference` — the `TE|<tradeId>|<role>` string that links a broker order or execution back to a local `TradeRecord`.

### Thread handoffs

| Handoff | Detail |
| --- | --- |
| `IBKR-Session-Lifecycle` → `ReconciliationManager` | `begin(String)` is invoked from `initializeConnectedSession()` and `restoreBrokerConnectivity(boolean)`, both running on the lifecycle executor. |
| `IBKR-Session-Lifecycle` → `IBKR-Reconciliation-Timeout` | `begin` schedules `failEpoch` on the manager's own single-thread `ScheduledExecutorService`. |
| `IBKR-Reader` → `ReconciliationManager` | Every collection callback arrives on the reader thread through `EWrapperRaptor`, `AccountEventHandler`, or `OrderLifecycleHandler`. |
| `IBKR-Reader` → `TradingGate` | `completeIfReady` runs on whichever reader-thread callback completes the fourth flag, and performs the `READY` / `MANUAL_INTERVENTION` transition there. |
| `IBKR-Reconciliation-Timeout` → `TradingGate` | A timed-out epoch performs the `MANUAL_INTERVENTION` transition on the scheduler thread. |
| Any of the above → `<Strategy>-Thread` | Strategies observe lifecycle and LIVE-entry-arm state only through `TradingGate.allowsNewEntries()` / `allowsAutomatedOrderChanges()`, backed by `AtomicReference<State>` plus the LIVE `AtomicBoolean`. |

Every public method of `ReconciliationManager` that touches `activeEpoch`,
`brokerState`, or `client` is `synchronized` on the manager instance, so lifecycle,
reader, and timeout threads serialize against each other. `BrokerState` is
independently `synchronized`. `JsonTradingStateStore.snapshot()` is `synchronized`
on the store.
