---
paths:
  - "trading-engine/**/*"
---

# Order Execution Flow

Traces a new entry from the strategy poll loop through bracket construction and
`placeOrder` submission, then back through the IBKR order callbacks that drive
`BracketOrder` state and the JSON journal.

## 1. Workflow Components

| Component | Role in this flow |
| --- | --- |
| `mwd.trading.strategy.AbstractStrategy` | `Runnable` poll loop that gates, sizes, and submits an entry, then interprets the resulting `BracketOrder.Status`. |
| `mwd.trading.strategy.EntryAdmission` | Takes and unwinds the two entry claims through `PositionLedger`, and hands back an `AutoCloseable` `Reservation`. |
| `mwd.trading.marketdata.MarketSnapshot` | The frozen market-data view each entry decision is built from; one for screening, one taken after the lock is held. |
| `mwd.trading.risk.UniverseReference` | Per-ticker margin rate the sizing multiplies against, read from a file rather than measured. |
| `mwd.trading.risk.ConcentrationLimits` | Per-ticker and per-sector caps that can only reduce the quantity a strategy asked for. |
| `mwd.trading.strategy.TwoSigmaDownsideMeanReversionStrategy` | Concrete strategy supplying `isEntryConditionMet`, `calculateEntryPrice`, `calculateSliceIntents`, `evaluateTickStreamNeed`, `manageOpenPosition`, `getStrategyName`, `getTradeDirection`. |
| `mwd.trading.strategy.OneSigmaDownsideMeanReversionStrategy` | Concrete strategy with the same override set, `TradeDirection.LONG`. |
| `mwd.trading.strategy.OneSigmaUpsideMeanReversionStrategy` | Concrete strategy with the same override set, `TradeDirection.SHORT`. |
| `mwd.trading.strategy.StrategyActivationPolicy` | Immutable startup policy rechecked by the executor so disabled, unknown, or wrong-environment strategy IDs cannot reach order construction. |
| `mwd.trading.execution.BracketOrderGateway` | Interface declaring `placeTripleThreat(...)` and `updateTripleThreatExits(...)`; the only execution API a strategy holds. |
| `mwd.trading.execution.BracketOrderExecutor` | `BracketOrderGateway` implementation that rechecks activation, validates, builds the order bundle, registers it, persists intent, and calls `placeOrder`. |
| `mwd.trading.execution.BracketOrderExecutor.SliceIntent` | Mutable holder of `Decimal quantity`, `double takeProfitPrice`, `double stopLossPrice`, `long timeExit` produced by the strategy. |
| `mwd.trading.execution.BracketOrder` | Aggregate holding trade identity, `Status`, filled/remaining quantities, the `ExitSlice` list, and the `OrderLegState` map. |
| `mwd.trading.execution.BracketOrder.ExitSlice` | One slice's three order IDs, quantity, OCA group, and current exit prices/time. |
| `mwd.trading.execution.BracketOrder.OrderLegState` | Per-leg broker view: API order ID, role, order reference, perm ID, status, filled/remaining, acknowledged flag. |
| `mwd.trading.execution.OrderRegistry` | Four `ConcurrentHashMap`s resolving a bracket by API order ID, perm ID, order reference, or trade ID. |
| `mwd.trading.execution.UncertainOrderSubmissionException` | Thrown when `placeOrder` fails part-way through the bundle. |
| `mwd.trading.domain.TradeDirection` | Enum supplying `entryAction()`, `exitAction()`, and `acceptsEntryPrice(double, double)`. |
| `mwd.trading.domain.Stock` | Holds the `Contract` and the active `BracketOrder`, and derives `PositionState` from that bracket. |
| `mwd.trading.state.Blackboard` | Allocates order IDs and owns the global pending-entry lock, the position-owner map, and the last-entry timestamp. |
| `mwd.trading.state.Blackboard.EntryOwner` | Record `(String strategyName, String ticker)` held in the global pending `AtomicReference`. |
| `mwd.trading.lifecycle.TradingGate` | Consulted by both the strategy and the executor before anything is sent. |
| `mwd.trading.config.Config` | Supplies `getStrategyPollRateMs()`, `getEntryAcknowledgementTimeoutMs()`, `getExpectedAccount()`, `getMaxActivePositions()`. |
| `mwd.trading.persistence.JsonTradingStateStore` | Writes the trade intent before transmission and every broker update afterwards. |
| `com.ib.client.EClientSocket` | Carries `placeOrder` to TWS. |
| `com.ib.client.Order` | One IBKR order leg built by `BracketOrderExecutor`. |
| `com.ib.client.Decimal` | Quantity type used throughout. |
| `mwd.trading.broker.ibkr.EWrapperRaptor` | Receives `openOrder`, `orderStatus`, `execDetails`, `completedOrder`, `orderBound`, `error` and forwards them. |
| `mwd.trading.execution.OrderLifecycleHandler` | Applies every broker order callback to the resolved `BracketOrder`, `Stock`, `Blackboard`, and journal. |
| `mwd.trading.reconciliation.ReconciliationManager` | Receives a copy of every order callback before the bracket is resolved. |
| `mwd.trading.marketdata.MarketDataFreshness` | Consulted for the required entry inputs before and inside the reservation block. |
| `mwd.trading.marketdata.TickStreamController` | Driven by `evaluateTickStreamNeed` and cancelled during lifecycle cleanup. |

## 2. Execution Path

### Entry evaluation (strategy thread)

1. **Initiating Component:** `Thread` (`<StrategyClassSimpleName>-Thread`)
   **Method Invocation:** `AbstractStrategy.run()` → `runOneCycle()` → `processSymbolSafely(Stock)` → `executeLifecycle(Stock)`, then `Thread.sleep(config.getStrategyPollRateMs())`
   **Receiving Component:** `AbstractStrategy`

2. **Initiating Component:** `AbstractStrategy.executeLifecycle(Stock)`
   **Method Invocation:** `blackboard.getPositionOwner(String)`, then `stock.positionState(owner != null)`
   **Receiving Component:** `Blackboard`, `Stock`

3. **Initiating Component:** `AbstractStrategy.executeLifecycle(Stock)`
   **Method Invocation:** `evaluateNewEntry(Stock, String)` when the state is `FLAT` and no owner is recorded
   **Receiving Component:** `AbstractStrategy`

4. **Initiating Component:** `AbstractStrategy.evaluateNewEntry(Stock, String)`
   **Method Invocation:** `tradingGate.allowsNewEntries()`, `stock.isTradeable()`, `blackboard.isAccountCurrentForNewEntry()`, `entryInputsReady(Stock)`, then `snapshot(stock)` and `isEntryConditionMet(MarketSnapshot)` on that screening view
   **Receiving Component:** `TradingGate`, `Stock`, `Blackboard`, `MarketDataInputStore`, `MarketSnapshot`, the concrete strategy

5. **Initiating Component:** `AbstractStrategy.evaluateNewEntry(Stock, String)`
   **Method Invocation:** `calculateEntryPrice(MarketSnapshot)`, `evaluateTickStreamNeed(MarketSnapshot, double)`, `tradeDirection().acceptsEntryPrice(screening.lastPrice(), entryPrice)`
   **Receiving Component:** the concrete strategy, `TickByTickManager`, `TradeDirection`

6. **Initiating Component:** `AbstractStrategy.evaluateNewEntry(Stock, String)`
   **Method Invocation:** `entryAdmission.tryAdmit(strategyId, stock)` → `positions.tryAcquireGlobalPending(strategyId, stock.getTicker())`
   **Receiving Component:** `Blackboard` (`AtomicReference<EntryOwner>.compareAndSet`)

7. **Initiating Component:** `AbstractStrategy.evaluateNewEntry(Stock, String)`
   **Method Invocation:** `positions.tryReservePosition(stock.getTicker(), strategyId)`, then the guard `stock.positionState(true) != Stock.PositionState.PENDING`, which unwinds both claims rather than writing anything
   **Receiving Component:** `Blackboard`, `Stock`

8. **Initiating Component:** `AbstractStrategy.evaluateNewEntry(Stock, String)`
   **Method Invocation:** re-checks `tradingGate.allowsNewEntries()` and `marketDataFreshness.areAllFresh(String, Set<MarketDataInput>)`, then takes a second `snapshot(stock)` — the only view the order is built from — and re-runs `isEntryConditionMet(MarketSnapshot)`, `calculateEntryPrice(MarketSnapshot)`, and `acceptsEntryPrice(market.lastPrice(), entryPrice)`; any failure calls `rollbackEntryReservation(Stock, EntryAdmission.Reservation)`
   **Receiving Component:** `TradingGate`, `MarketDataInputStore`, the concrete strategy

9. **Initiating Component:** `AbstractStrategy.evaluateNewEntry(Stock, String)`
   **Method Invocation:** `calculateSliceIntents(MarketSnapshot, double)` on that same view, then the private `totalQuantity(List<SliceIntent>)`, then `concentrationLimits.allowedQuantity(ticker, entryPrice, totalQuantity)`; a smaller allowance is applied by `trimToTotal(...)`, which scales every slice and gives the rounding loss to the first so the parts still sum exactly to the parent. A slice that would round to zero, or a total below `MIN_POSITION_NOTIONAL`, rolls the reservation back instead
   **Receiving Component:** the concrete strategy, `ConcentrationLimits`

10. **Initiating Component:** `AbstractStrategy.evaluateNewEntry(Stock, String)`
    **Method Invocation:** `pendingEntries.put(ticker, new PendingEntry(clock.millis()))` and `blackboard.recordEntrySubmitted(clock.millis())`
    **Receiving Component:** `AbstractStrategy`, `Blackboard`

11. **Initiating Component:** `AbstractStrategy.evaluateNewEntry(Stock, String)`
    **Method Invocation:** `bracketOrderGateway.placeTripleThreat(String strategyName, TradeDirection tradeDirection, String tickerSymbol, Decimal totalOrderQuantity, double entryLimitPrice, List<BracketOrderExecutor.SliceIntent> sliceIntents)`
    **Receiving Component:** `BracketOrderExecutor`

### Bracket construction and submission (strategy thread)

12. **Initiating Component:** `BracketOrderExecutor.placeTripleThreat(...)`
    **Method Invocation:** `tradingGate.allowsNewEntries()`; normalize the strategy ID; `strategyActivationPolicy.requireEntrySubmissionAllowed(strategyName)`; `validateEntryIntent(String, Decimal, double, List<SliceIntent>)` which calls `client.isConnected()` and checks quantity, price, and that slice quantities sum to the parent quantity
    **Receiving Component:** `TradingGate`, `StrategyActivationPolicy`, `EClientSocket`

13. **Initiating Component:** `BracketOrderExecutor.placeTripleThreat(...)`
    **Method Invocation:** `blackboard.getStock(tickerSymbol)`, `stock.getContract()`, `configuredAccount()` (`config.getExpectedAccount()` else `blackboard.getAccount().getAccountId()`); a missing contract or blank account calls `halt(String)` and throws `IllegalStateException`
    **Receiving Component:** `Blackboard`, `Stock`, `Config`, `Account`

14. **Initiating Component:** `BracketOrderExecutor.placeTripleThreat(...)`
    **Method Invocation:** `blackboard.getNextOrderId()` for the parent, `newTradeId(String)`, `new BracketOrder(tradeId, strategyName, account, stock.getContract().conid(), actionDirection, ticker, parentEntryOrderId, totalOrderQuantity)`, `bracketOrder.setEntryPrice(double)`
    **Receiving Component:** `Blackboard`, `BracketOrder`

15. **Initiating Component:** `BracketOrderExecutor.placeTripleThreat(...)`
    **Method Invocation:** builds the parent `Order` (`orderType("LMT")`, `lmtPrice`, `account`, `orderRef("TE|<tradeId>|P")`, `transmit(false)`) and calls `bracketOrder.registerOrderLeg(parentEntryOrderId, "PARENT", parentReference, totalOrderQuantity)`
    **Receiving Component:** `com.ib.client.Order`, `BracketOrder`

16. **Initiating Component:** `BracketOrderExecutor.placeTripleThreat(...)`
    **Method Invocation:** per `SliceIntent`, three `blackboard.getNextOrderId()` calls, `new BracketOrder.ExitSlice(takeProfitOrderId, stopLossOrderId, timeExitOrderId, intent.quantity, tradeId + '-' + rolePrefix)`, `exitSlice.setTakeProfitPrice/setStopLossPrice/setTimeExit`, `bracketOrder.addSlice(ExitSlice)`
    **Receiving Component:** `Blackboard`, `BracketOrder.ExitSlice`, `BracketOrder`

17. **Initiating Component:** `BracketOrderExecutor.placeTripleThreat(...)`
    **Method Invocation:** builds the take-profit (`LMT`, `hidden(true)`), stop-loss (`STP`, `auxPrice`), and time-exit (`MKT`, `goodAfterTime` formatted `yyyyMMdd HH:mm:ss` in `America/New_York`) orders, each with `parentId(parentEntryOrderId)`, `ocaGroup`, `ocaType(2)`, `transmit(false)`, and a `registerOrderLeg(...)` call
    **Receiving Component:** `com.ib.client.Order`, `BracketOrder`

18. **Initiating Component:** `BracketOrderExecutor.placeTripleThreat(...)`
    **Method Invocation:** `blackboard.getOrderRegistry().register(bracketOrder)` then `stock.setActiveBracket(bracketOrder)`
    **Receiving Component:** `OrderRegistry`, `Stock`

19. **Initiating Component:** `BracketOrderExecutor.persistIntent(BracketOrder, String)`
    **Method Invocation:** `stateStore.recordIntent(bracketOrder, actionDirection)`; on `IOException` it calls `halt(String)` and throws, and the caller reverses step 18 with `unregister` / `setActiveBracket(null)`
    **Receiving Component:** `JsonTradingStateStore`

20. **Initiating Component:** `BracketOrderExecutor.placeTripleThreat(...)`
    **Method Invocation:** `orderBundle.get(orderBundle.size() - 1).transmit(true)`, then for each order `client.placeOrder(individualOrder.orderId(), stock.getContract(), individualOrder)` and `bracketOrder.incrementUpdateCount()`
    **Receiving Component:** `EClientSocket`, `BracketOrder`

21. **Initiating Component:** `BracketOrderExecutor.placeTripleThreat(...)`
    **Method Invocation:** on `RuntimeException`, `halt(String)` then `throw new UncertainOrderSubmissionException(String, Throwable)`
    **Receiving Component:** `Blackboard.setSystemHalted(true)`, `TradingGate.requireManualIntervention(String)`, caller

22. **Initiating Component:** `AbstractStrategy.evaluateNewEntry(Stock, String)`
    **Method Invocation:** asserts `stock.getActiveBracket() == bracketOrder`; catches `UncertainOrderSubmissionException` → `escalate(Stock, String)`; catches other `RuntimeException` → `rollbackEntryReservation` or `escalate` depending on `stock.getActiveBracket()`
    **Receiving Component:** `AbstractStrategy`, `TradingGate`

### Broker acknowledgement (IBKR reader thread)

23. **Initiating Component:** `EReader` (`IBKR-Reader`)
    **Method Invocation:** `EWrapperRaptor.openOrder(int orderId, Contract contract, Order order, OrderState orderState)`
    **Receiving Component:** `OrderLifecycleHandler.onOpenOrder(int, Contract, Order, OrderState)`

24. **Initiating Component:** `OrderLifecycleHandler.onOpenOrder(...)`
    **Method Invocation:** `reconciliationManager.onOpenOrder(int, Contract, Order, OrderState)`
    **Receiving Component:** `ReconciliationManager`

25. **Initiating Component:** `OrderLifecycleHandler.resolveBracket(int, long, String)`
    **Method Invocation:** `orderRegistry.getBracketFor(int)`, then `getBracketForPermanentId(long)`, then `getBracketForOrderReference(String)`
    **Receiving Component:** `OrderRegistry`

26. **Initiating Component:** `OrderLifecycleHandler.onOpenOrder(...)`
    **Method Invocation:** `orderRegistry.recordBrokerIdentity(orderIdentifier, order.permId(), order.orderRef())`, `bracketOrder.applyBrokerUpdate(int, long, String, Decimal, Decimal)`, price/quantity comparison against `bracketOrder.getEntryPrice()` and `getTotalQuantity()`, `bracketOrder.setStatus(BracketOrder.Status.WORKING_PARENT)`, `validateExitSlice(int, Order, BracketOrder)`, `persist(BracketOrder)`
    **Receiving Component:** `OrderRegistry`, `BracketOrder`, `JsonTradingStateStore`

27. **Initiating Component:** `EWrapperRaptor.orderStatus(int orderId, String status, Decimal filled, Decimal remaining, double avgFillPrice, long permId, int parentId, double lastFillPrice, int clientId, String whyHeld, double mktCapPrice)`
    **Method Invocation:** `orderLifecycleHandler.onOrderStatus(...)` with the same argument list
    **Receiving Component:** `OrderLifecycleHandler`

28. **Initiating Component:** `OrderLifecycleHandler.onOrderStatus(...)`
    **Method Invocation:** `reconciliationManager.onOrderStatus(int, String, Decimal, Decimal, long, int, int)`, `resolveBracket(...)`, `orderRegistry.recordBrokerIdentity(...)`, `bracketOrder.applyBrokerUpdate(...)`
    **Receiving Component:** `ReconciliationManager`, `OrderRegistry`, `BracketOrder`

29. **Initiating Component:** `OrderLifecycleHandler.onOrderStatus(...)` for the parent leg
    **Method Invocation:** `bracketOrder.setFilledQuantity(Decimal)` / `setRemainingQuantity(Decimal)`; `"Filled"` → `setStatus(POSITION_OPEN)` + `markPositionOpen(BracketOrder)`; a non-zero partial fill → `setStatus(PARTIAL_PARENT)` + `markPositionOpen` + `halt(String)`; a working status → `setStatus(WORKING_PARENT)`
    **Receiving Component:** `BracketOrder`, `Stock`, `Blackboard`, `TradingGate`

30. **Initiating Component:** `OrderLifecycleHandler.onOrderStatus(...)` for an exit leg
    **Method Invocation:** `bracketOrder.getSliceByOrderId(int)` → `filledSlice.setFilled(true)`; when every slice reports `isFilled()` → `setStatus(FILLED)` + `completeConfirmedFlat(BracketOrder)`
    **Receiving Component:** `BracketOrder.ExitSlice`, `BracketOrder`, `Stock`, `Blackboard`

31. **Initiating Component:** `OrderLifecycleHandler.markPositionOpen(BracketOrder)`
    **Method Invocation:** `blackboard.releaseGlobalPending(String, String)`. No position state is written; `OPEN` follows from the `Status` the caller set on the bracket
    **Receiving Component:** `Stock`, `Blackboard`

32. **Initiating Component:** `OrderLifecycleHandler.completeConfirmedFlat(BracketOrder)`
    **Method Invocation:** `stock.setActiveBracket(null)` when it still points at this bracket, `blackboard.releaseGlobalPending(String, String)`, `blackboard.releasePosition(String, String)`. `FLAT` follows from the terminal `Status` and the cleared bracket
    **Receiving Component:** `Stock`, `Blackboard`

33. **Initiating Component:** `EWrapperRaptor.execDetails(int reqId, Contract contract, Execution execution)`
    **Method Invocation:** `orderLifecycleHandler.onExecDetails(int, Contract, Execution)` → `reconciliationManager.onExecution(Contract, Execution)`, `resolveBracket(execution.orderId(), execution.permId(), execution.orderRef())`, `bracketOrder.applyBrokerUpdate(..., "EXECUTION", execution.cumQty(), null)`
    **Receiving Component:** `ReconciliationManager`, `OrderRegistry`, `BracketOrder`

34. **Initiating Component:** `EWrapperRaptor.completedOrder(Contract contract, Order order, OrderState orderState)`
    **Method Invocation:** `orderLifecycleHandler.onCompletedOrder(Contract, Order, OrderState)` → terminal-status branches setting `POSITION_OPEN`, `REJECTED`, `CANCELLED`, `PARTIAL_PARENT`, or `FILLED`
    **Receiving Component:** `BracketOrder`, `Stock`, `Blackboard`, `TradingGate`

35. **Initiating Component:** `EWrapperRaptor.orderBound(long orderId, int apiClientId, int apiOrderId)`
    **Method Invocation:** `orderLifecycleHandler.onOrderBound(long, int, int)` → `orderRegistry.recordBrokerIdentity(apiOrderIdentifier, permanentIdentifier, null)` and `applyBrokerUpdate(..., "BOUND", null, null)`
    **Receiving Component:** `OrderRegistry`, `BracketOrder`

36. **Initiating Component:** `EWrapperRaptor.error(int id, long time, int errorCode, String errorMsg, String advancedOrderRejectJson)`
    **Method Invocation:** `ibkrErrorHandler.process(int, int, String, String)`, `orderLifecycleHandler.onError(int, long, int, String, String)`, `sessionManager.onError(int, String)`
    **Receiving Component:** `IbkrErrorHandler`, `OrderLifecycleHandler`, `IbkrSessionManager`

37. **Initiating Component:** `OrderLifecycleHandler.onError(...)`
    **Method Invocation:** `orderRegistry.getBracketFor(int)`; codes `102, 110, 136, 161, 201, 321, 10147, 10148, 10197` set `REJECTED`/`PARTIAL_PARENT`/`POSITION_OPEN` and call `halt(String)`; code `202` sets `CANCELLED` or `PARTIAL_PARENT`; all paths end in `persist(BracketOrder)`
    **Receiving Component:** `BracketOrder`, `Stock`, `Blackboard`, `TradingGate`, `JsonTradingStateStore`

38. **Initiating Component:** `OrderLifecycleHandler.persist(BracketOrder)`
    **Method Invocation:** `stateStore.recordBrokerUpdate(bracketOrder, bracketOrder.getAction())`; on `IOException` it calls `halt(String)`
    **Receiving Component:** `JsonTradingStateStore`

### Strategy-side resolution of the pending entry (strategy thread)

39. **Initiating Component:** `AbstractStrategy.executeLifecycle(Stock)`
    **Method Invocation:** `acknowledgeStatusChange(Stock, String)` then `handlePendingEntry(Stock)`, while the derived state is `PENDING` and the strategy owns the ticker
    **Receiving Component:** `AbstractStrategy`

40. **Initiating Component:** `AbstractStrategy.acknowledgeStatusChange(Stock, String)`
    **Method Invocation:** returns immediately when `bracketOrder.getStatus()` and `getTradeId()` both equal the pair recorded in `acknowledgedStatus`; otherwise records them and switches — `WORKING_PARENT` → `blackboard.releaseGlobalPending`; `PARTIAL_PARENT` → `releaseGlobalPending` + `escalate`; `POSITION_OPEN` → `releaseGlobalPending` and pending-entry cleanup; `CANCELLED`/`REJECTED` with a non-zero fill → `releaseGlobalPending` + `escalate`; `INITIALIZED` and `FILLED` carry no once-only work
    **Receiving Component:** `BracketOrder`, `Blackboard`, `TradingGate`

40a. **Initiating Component:** `AbstractStrategy.handlePendingEntry(Stock)`
    **Method Invocation:** the clock-driven half only — returns unless `pendingEntry == null || acknowledgementTimedOut(...)`, then escalates for a missing bracket or a still-`INITIALIZED` one. A clean terminal derives `FLAT` and is cleaned up through `handleFlatWithLocalOwnership` on the same cycle
    **Receiving Component:** `BracketOrder`, `Stock`, `Blackboard`, `TradingGate`

41. **Initiating Component:** `AbstractStrategy.cleanupOwnedLifecycle(Stock, String, BracketOrder)`, reached from `handleFlatWithLocalOwnership` or — the ordinary case, because `completeConfirmedFlat` releases the ticker on the reader thread — from the `FLAT` branch when `hasUnfinishedLifecycle(ticker)` reports leftover per-ticker state
    **Method Invocation:** `onPositionClosed(Stock)`, `blackboard.releaseGlobalPending`, `blackboard.releasePosition`, `tickStreamController.cancelStream(String)`, `stock.setActiveBracket(null)`
    **Receiving Component:** the concrete strategy, `Blackboard`, `TickByTickManager`, `Stock`

## 3. Data Payloads and State Handoffs

### Objects passed

- `List<BracketOrderExecutor.SliceIntent>` — built by `calculateSliceIntents(MarketSnapshot, double)` on the strategy thread and handed to `placeTripleThreat`; each intent's fields are copied into a `BracketOrder.ExitSlice` and into the corresponding `com.ib.client.Order`.
- `Decimal totalOrderQuantity` — summed from the slice intents by the strategy and re-validated by `validateEntryIntent` to equal the sum of slice quantities.
- `TradeDirection` — supplies `entryAction()` for the parent order and `exitAction()` for all three exit legs.
- `BracketOrder` — created inside the executor, registered in `OrderRegistry`, installed on `Stock.setActiveBracket`, returned to the strategy, and thereafter mutated by `OrderLifecycleHandler` on the reader thread.
- `String tradeId` — `<TICKER>-<yyyyMMdd>-<8 hex chars>`; embedded in every `orderRef` as `TE|<tradeId>|<role>` and used as the `OrderRegistry` and journal key.
- `String orderRef` — the durable identity IBKR echoes back on `openOrder`, `execDetails`, and `completedOrder`, used by `resolveBracket` when the API order ID is unknown.
- `com.ib.client.Order` bundle — a `List<Order>` where only the last element has `transmit(true)`.
- `JsonTradingStateStore.TradeRecord` / `LegRecord` — derived from `BracketOrder.getLegStates()` and written atomically before transmission and after every callback.
- `Blackboard.EntryOwner(strategyName, ticker)` — the global single-entry lock value.

### Thread handoffs

| Handoff | Detail |
| --- | --- |
| Strategy thread → TWS | `BracketOrderExecutor.placeTripleThreat` runs entirely on `<Strategy>-Thread` and ends in `EClientSocket.placeOrder`, which writes to the socket. |
| TWS → `IBKR-Reader` thread | Every acknowledgement (`openOrder`, `orderStatus`, `execDetails`, `completedOrder`, `orderBound`, `error`) is dispatched by `EReader.processMsgs()` and handled inline by `OrderLifecycleHandler`. |
| `IBKR-Reader` → strategy thread | The reader thread mutates `BracketOrder.status` (`volatile`) and the `Blackboard` ownership maps; the strategy thread derives `Stock.PositionState` from them on its next poll in `executeLifecycle`, and `acknowledgedStatus` makes each status observable exactly once in `acknowledgeStatusChange`. |
| Strategy thread ↔ `IBKR-Reader` on the pending lock | `tryAcquireGlobalPending` is called on the strategy thread through `EntryAdmission`; `releaseGlobalPending` is called from **both** the strategy thread (`acknowledgeStatusChange`, `cleanupOwnedLifecycle`, `Reservation.release()`) and the reader thread (`markPositionOpen` / `completeConfirmedFlat`), guarded by `AtomicReference.compareAndSet` on `EntryOwner`. |
| Strategy thread ↔ `IBKR-Reader` on position ownership | `Blackboard.tryReservePosition` / `releasePosition` / `getPositionOwner` are `synchronized` on the `Blackboard` instance and are called from both threads. |
| Both threads → journal | `JsonTradingStateStore.recordIntent` (strategy thread) and `recordBrokerUpdate` (reader thread) are `synchronized` and each performs a temp-file write, backup copy, and `ATOMIC_MOVE`. |
| Reader thread → `IBKR-Session-Lifecycle` | `error` codes handled by `IbkrSessionManager.onError` may re-post work to the lifecycle executor. |

`BracketOrder` publishes leg state with `volatile` fields inside `OrderLegState` and
guards `registerOrderLeg`, `applyBrokerUpdate`, and `getLegStates` with
`synchronized`; `status`, `filledQuantity`, and `remainingQuantity` on the bracket
itself are plain fields written by the reader thread and read by the strategy thread.
