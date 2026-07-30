---
paths:
  - "trading-engine/**/*"
---

# Trading Engine — State Machine Reference

Extracted from `trading-engine/trading-engine/src/main/java/mwd/trading/`. Every
state, method signature, and condition below is taken verbatim from the source.

---

## 1. State Definitions and Ownership

### 1.1 Enumerated States

#### `Stock.PositionState` — per-symbol position lifecycle

| State | Declared in |
|---|---|
| `FLAT` | `mwd.trading.domain.Stock.PositionState` |
| `PENDING` | `mwd.trading.domain.Stock.PositionState` |
| `OPEN` | `mwd.trading.domain.Stock.PositionState` |

- **Owning class:** `mwd.trading.domain.Stock`
- **Owning field:** none. The state is derived, not stored.
- **Accessors:** `public static PositionState positionStateOf(boolean owned, BracketOrder bracket)` and
  `public PositionState positionState(boolean owned)`, which applies the static form to this
  instance's `activeBracket`.
- **Inputs:** whether the caller holds the ticker reservation, and `BracketOrder.getStatus()`
  plus `getFilledQuantity()` on the active bracket.

There is no transition table for this machine, because there are no transitions:
every read recomputes the value from the two inputs above. `positionStateOf` is
total over them:

| `bracket` | `bracket.getStatus()` | Result |
|---|---|---|
| non-`null` | `INITIALIZED`, `WORKING_PARENT` | `PENDING` |
| non-`null` | `PARTIAL_PARENT`, `POSITION_OPEN` | `OPEN` |
| non-`null` | `FILLED` | `FLAT` |
| non-`null` | `CANCELLED`, `REJECTED` | `FLAT` when `getFilledQuantity()` is `null` or zero, otherwise `OPEN` |
| `null` | — | `PENDING` when `owned`, otherwise `FLAT` |

Consequences worth stating, all verbatim from the source:

- A terminal bracket reads `FLAT` while the ticker is still reserved. That is what
  lets `AbstractStrategy.handleFlatWithLocalOwnership` notice the trade finished
  and release the reservation.
- A terminal status that followed a fill reads `OPEN`, because a live position was
  left behind.
- Ownership with no bracket reads `PENDING`: the entry has been admitted and the
  bracket is not built yet.
- `CLOSING` no longer exists. It was declared but never assigned.

#### `BracketOrder.Status` — per-bracket order lifecycle

| State | Source comment |
|---|---|
| `INITIALIZED` | `Local memory only, not yet sent to broker` |
| `WORKING_PARENT` | `Parent entry order is live at the exchange` |
| `PARTIAL_PARENT` | `Parent entry order is partially filled` |
| `POSITION_OPEN` | `Parent is 100% filled, exit slices are now active` |
| `FILLED` | `Every exit slice in this bracket has been completed` |
| `CANCELLED` | `The order bundle was cancelled` |
| `REJECTED` | `The broker rejected the entry order` |

- **Owning class:** `mwd.trading.execution.BracketOrder`
- **Owning field:** `private Status status;`
- **Accessors:** `public Status getStatus()` / `public void setStatus(Status status)`
- **Initial value:** `this.status = Status.INITIALIZED;` assigned in `public BracketOrder(String tradeId, String strategyName, String account, int contractId, String action, String ticker, int parentOrderId, Decimal totalQuantity)`
- **Terminal set** (per `AbstractStrategy.isConfirmedTerminal(BracketOrder.Status)`): `FILLED`, `CANCELLED`, `REJECTED`

#### `EngineMode` — application-wide trading lifecycle

| State |
|---|
| `STARTING` |
| `CONNECTING` |
| `RECONCILING` |
| `READY` |
| `DEGRADED` |
| `MANUAL_INTERVENTION` |
| `STOPPING` |

- **Declared in:** `mwd.trading.lifecycle.EngineMode`
- **Owning class:** `mwd.trading.lifecycle.TradingGate`
- **Owning field:** `private final AtomicReference<State> state = new AtomicReference<>(new State(EngineMode.STARTING, "Application starting", System.currentTimeMillis()));`
- **State record:** `public record State(EngineMode mode, String reason, long changedAtEpochMillis)`
- **Readers:** `getState()`, `getMode()`, `allowsNewEntries()` (`getMode() == EngineMode.READY`), `allowsAutomatedOrderChanges()` (`getMode() == EngineMode.READY`)
- **Writers:** `transitionTo(EngineMode, String)`, `requireManualIntervention(String)`

#### `BlackboardMonitor.ViewState` — UI column visibility

| State |
|---|
| `VIEW_1` |
| `VIEW_2` |
| `VIEW_ALL` |

- **Owning class:** `mwd.trading.ui.BlackboardMonitor`
- **Owning field:** `private ViewState currentViewState = ViewState.VIEW_1;`

### 1.2 Non-Enumerated State Fields

| Field | Owning class | Type / initial value |
|---|---|---|
| `activeBracket` | `Stock` | `private volatile BracketOrder activeBracket;` — `null` / non-`null` |
| `isTradeable` | `Stock` | `private volatile boolean isTradeable = true;` |
| `systemHalted` | `Blackboard` | `private volatile boolean systemHalted = false;` |
| `systemUpdateRequired` | `Blackboard` | `private volatile boolean systemUpdateRequired = false;` |
| `openOrderEnd` | `Blackboard` | `private volatile boolean openOrderEnd = false;` |
| `globalPendingOwner` | `Blackboard` | `private final AtomicReference<EntryOwner> globalPendingOwner = new AtomicReference<>();` — `null` / `EntryOwner(strategyName, ticker)` |
| `activePositionOwners` | `Blackboard` | `private final Map<String, String> activePositionOwners = new HashMap<>();` — ticker absent / ticker → strategyName |
| `lastEntrySubmittedAtMillis` | `Blackboard` | `AtomicLong` |
| `pendingEntries` | `AbstractStrategy` | `ConcurrentMap<String, PendingEntry>` where `private record PendingEntry(long submittedAtMillis)` |
| `acknowledgedStatus` | `AbstractStrategy` | `ConcurrentMap<String, Acknowledged>` where `private record Acknowledged(String tradeId, BracketOrder.Status status)` — the last broker status this strategy acted on, per ticker, tied to the trade it belonged to |
| `escalatedPendingEntries` | `AbstractStrategy` | `Set<String>` (`ConcurrentHashMap.newKeySet()`) |
| `lastUnreadyReason` | `AbstractStrategy` | `ConcurrentMap<String, String>` |
| `isFilled` | `BracketOrder.ExitSlice` | `private boolean isFilled = false;` |
| `status` | `BracketOrder.OrderLegState` | `private volatile String status = "PENDING_SUBMIT";` |
| `acknowledged` | `BracketOrder.OrderLegState` | `private volatile boolean acknowledged;` |
| `status` (string) | `JsonTradingStateStore.TradeRecord` | `public boolean terminal() { return "FILLED".equals(status) \|\| "CANCELLED".equals(status) \|\| "REJECTED".equals(status); }` |
| `activeEpoch` | `ReconciliationManager` | `private CollectionEpoch activeEpoch;` — `null` (idle) / non-`null` (collecting) |
| `positionsComplete`, `openOrdersComplete`, `completedOrdersComplete`, `executionsComplete` | `ReconciliationManager.CollectionEpoch` | `private boolean` each; `private boolean complete()` requires all four |
| `lastResult` | `ReconciliationManager` | `private volatile Result lastResult;` where `public record Result(boolean matched, String reason, List<String> differences, long completedAtEpochMillis)` |
| `stopping`, `hasConnectedBefore` | `IbkrSessionManager` | `private volatile boolean` |
| `reconnectScheduled`, `apiReadyHandled` | `IbkrSessionManager` | `private final AtomicBoolean` |
| `initialized` | `MarketDataSubscriptionManager` | `private final AtomicBoolean initialized = new AtomicBoolean();` |
| `activeRequests`, `activeStreamCount` | `TickByTickManager` | `Map<String, Integer>`, `AtomicInteger`; `private final int MAX_STREAMS = 5;` |
| `readings` | `MarketDataInputStore` | `Map<String, AtomicReferenceArray<Reading>>` where `private record Reading(long recordedAtUnixMs, LocalDate sessionDate)` — per input: absent / present |
| `stateByTicker`, `gammaFlip` | `OptionsIndicatorStore` | `ConcurrentMap<String, TickerState>`, `AtomicReference<GammaFlip>` |

---

## 2. Transition Mapping

### 2.1 `Stock.PositionState`

Nothing writes this state, so this section records what each derived value causes
instead. `AbstractStrategy.executeLifecycle(Stock)` reads
`blackboard.getPositionOwner(ticker)` once, then dispatches on
`stock.positionState(owner != null)`.

#### FLAT

| Condition | Effect |
|---|---|
| `owner == null` | `evaluateNewEntry(stock, strategyId)` |
| `strategyId.equals(owner)` | `handleFlatWithLocalOwnership(stock)` — `cleanupOwnedLifecycle` when the bracket is `null` or `isConfirmedTerminal(...)`, otherwise `escalate(stock, "Stock is FLAT while its local bracket is still non-terminal")` |
| `owner != null` and not ours | return |
| `owner == null` and `hasUnfinishedLifecycle(ticker)` | `cleanupOwnedLifecycle(...)` first, then `evaluateNewEntry`. `OrderLifecycleHandler.completeConfirmedFlat` clears the bracket and releases the ticker in one call, so a strategy whose trade the reader thread finished never observes `FLAT` while still owning the symbol — without this the cleanup path is unreachable after any completed trade |

#### PENDING

| Condition | Effect |
|---|---|
| `strategyId.equals(owner)` | `acknowledgeStatusChange(stock, strategyId)` then `handlePendingEntry(stock)` |
| `owner == null` | `escalate(stock, "Pending position has no strategy owner")` |

#### OPEN

| Condition | Effect |
|---|---|
| `strategyId.equals(owner)` | `acknowledgeStatusChange(stock, strategyId)`, then `manageOpenPosition(stock, snapshot(stock))` when `automatedOrderChangesAllowed(stock)` |
| `owner == null` | `escalate(stock, "Open position has no strategy owner")` |
| owned by another strategy | return |

#### Edge-triggered work: `acknowledgeStatusChange(Stock, String)`

Deriving the state removed the lag that used to make each broker status observable
exactly once. `acknowledgedStatus` restores that explicitly: the method returns
immediately when `bracketOrder.getStatus()` **and** `getTradeId()` both equal the
recorded pair, records the new one, and then runs the once-per-transition work.

Matching on the trade as well as the status is what stops a value left over from an
earlier trade on the same ticker reading as already acknowledged. A previous order
that reached `WORKING_PARENT` and then died unfilled leaves that status behind, and
the next order on the ticker rests at `WORKING_PARENT` too; the engine-wide lock is
released here and nowhere else for a resting parent, so a single false match parks
every strategy in the engine until that order fills or terminates.

| Status | Effect |
|---|---|
| `WORKING_PARENT` | `blackboard.releaseGlobalPending(strategyId, ticker)`. The ticker stays reserved; there is no timeout on this state |
| `PARTIAL_PARENT` | `releaseGlobalPending`, then `escalate(stock, "Entry received a partial fill; verify position and protective exits")` |
| `POSITION_OPEN` | `releaseGlobalPending`, `pendingEntries.remove(ticker)`, `escalatedPendingEntries.remove(ticker)` |
| `CANCELLED`, `REJECTED` | when `!isZero(bracketOrder.getFilledQuantity())`: `releaseGlobalPending` then `escalate(stock, "Terminal parent status followed a fill; verify the live position")`. A clean terminal derives `FLAT` and is cleaned up through `handleFlatWithLocalOwnership` on the same cycle |
| `INITIALIZED`, `FILLED` | nothing. `INITIALIZED` is the status the entry was admitted in; `FILLED` derives `FLAT` |
| bracket is `null` | `acknowledgedStatus.remove(ticker)` and return |

#### The entry claim: `EntryAdmission.tryAdmit(String, Stock)`

Three steps, each able to fail independently, with the unwind in reverse:

1. `positions.tryAcquireGlobalPending(strategyId, ticker)` — returns `null` on failure.
2. `positions.tryReservePosition(ticker, strategyId)` — on failure, `releaseGlobalPending` then `null`.
3. `stock.positionState(true) != PositionState.PENDING` — on failure, `releasePosition` and `releaseGlobalPending` then `null`.

Step three is a guard rather than a write. Success returns a `Reservation` holding
both claims; `keep()` hands them to the pending-entry lifecycle, and `release()` —
which `close()` calls, so an unconsidered path cannot leak the lock — gives both
back. Releasing the reservation is what returns the derived state to `FLAT`.

`AbstractStrategy.rollbackEntryReservation(Stock, EntryAdmission.Reservation)`
calls `reservation.release()`, removes the ticker from `pendingEntries`,
`escalatedPendingEntries`, and `acknowledgedStatus`, and cancels any tick stream.

`cleanupOwnedLifecycle(Stock, String, BracketOrder)` clears the three per-ticker maps
*before* it calls `onPositionClosed(Stock)`, so a strategy hook that throws cannot
leave the ticker in a state that runs the whole cleanup again on the next poll and
every poll after it. Both blackboard releases compare the caller against the recorded
owner, so they are no-ops when the reader thread already released.
It is invoked from `evaluateNewEntry` when, after the claim is held:

- `!tradingGate.allowsNewEntries() || !marketDataFreshness.areAllFresh(ticker, requiredEntryInputs())`
- `!isEntryConditionMet(market)` on the post-lock snapshot
- `!tradeDirection().acceptsEntryPrice(market.lastPrice(), entryPrice)`
- `totalQuantity(sliceIntents).compareTo(Decimal.ZERO) <= 0`
- `catch (RuntimeException)` and `stock.getActiveBracket() == null`

#### Reader-thread effects that no longer write position state

`OrderLifecycleHandler.markPositionOpen(BracketOrder)` now only calls
`blackboard.releaseGlobalPending(...)`; the `OPEN` reading follows from the status
it set on the bracket. `OrderLifecycleHandler.completeConfirmedFlat(BracketOrder)`
clears `stock.setActiveBracket(null)` when it still points at that bracket and
releases both claims; the `FLAT` reading follows from the same. The call-site
conditions for both are unchanged and are listed in §2.6 and the execution flow
document.

### 2.2 `BracketOrder.Status`

#### (constructor) -> INITIALIZED

- **State Change:** — -> `INITIALIZED`
- **Controlling Method:** `public BracketOrder(String tradeId, String strategyName, String account, int contractId, String action, String ticker, int parentOrderId, Decimal totalQuantity)`
- **Transition Conditions:** unconditional assignment `this.status = Status.INITIALIZED;`

#### INITIALIZED -> WORKING_PARENT (open-order callback)

- **Controlling Method:** `public void onOpenOrder(int orderIdentifier, Contract contract, Order order, OrderState orderState)` (`OrderLifecycleHandler`)
- **Transition Conditions:**
  1. `resolveBracket(orderIdentifier, order.permId(), order.orderRef()) != null`
  2. `bracketOrder.isParentOrderId(orderIdentifier)`
- **Adjacent halts (do not change `Status`):** `Math.abs(order.lmtPrice() - bracketOrder.getEntryPrice()) > 0.0001`; `!order.totalQuantity().equals(bracketOrder.getTotalQuantity())`

#### -> WORKING_PARENT (status callback)

- **Controlling Method:** `public void onOrderStatus(int orderIdentifier, String status, Decimal filledQuantity, Decimal remainingQuantity, double averageFillPrice, long permanentIdentifier, int parentIdentifier, double lastFillPrice, int clientIdentifier, String whyHeld, double marketCapPrice)`
- **Transition Conditions:**
  1. `bracketOrder.isParentOrderId(orderIdentifier)`
  2. `!"Filled".equalsIgnoreCase(status)`
  3. not (`filledQuantity != null && !filledQuantity.isZero() && !isCancelledOrInactive(status)`)
  4. `isWorkingStatus(status)` — `"PendingSubmit"`, `"PreSubmitted"`, `"Submitted"`, or `"PendingCancel"` (case-insensitive)

#### -> POSITION_OPEN

- **Controlling Methods and Conditions:**
  - `onOrderStatus(...)`: `bracketOrder.isParentOrderId(orderIdentifier) && "Filled".equalsIgnoreCase(status)`
  - `onError(int identifier, long time, int errorCode, String errorMessage, String advancedOrderRejectJson)`: `errorCode` in `{102, 110, 136, 161, 201, 321, 10147, 10148, 10197}` and `!bracketOrder.isParentOrderId(identifier)`
  - `onExecDetails(int requestIdentifier, Contract contract, Execution execution)`: `bracketOrder.isParentOrderId(execution.orderId()) && cumulativeQuantity != null && !cumulativeQuantity.isZero() && cumulativeQuantity.compareTo(bracketOrder.getTotalQuantity()) >= 0`
  - `onCompletedOrder(Contract contract, Order order, OrderState orderState)`: `bracketOrder.isParentOrderId(order.orderId()) && "Filled".equalsIgnoreCase(completedStatus)`

#### -> PARTIAL_PARENT

- **Controlling Methods and Conditions:**
  - `onOrderStatus(...)`: `isParentOrderId(orderIdentifier) && !"Filled".equalsIgnoreCase(status) && filledQuantity != null && !filledQuantity.isZero() && !isCancelledOrInactive(status)`
  - `onOrderStatus(...)`: `isCancelledOrInactive(status) && isParentOrderId(orderIdentifier) && !(filledQuantity == null || filledQuantity.isZero())`
  - `onError(...)`: `errorCode` in `{102, 110, 136, 161, 201, 321, 10147, 10148, 10197}`, `isParentOrderId(identifier)`, and `!(getFilledQuantity() == null || getFilledQuantity().isZero())`
  - `onError(...)`: `errorCode == 202`, `isParentOrderId(identifier)`, and `!(getFilledQuantity() == null || getFilledQuantity().isZero())`
  - `onExecDetails(...)`: `isParentOrderId(execution.orderId()) && cumulativeQuantity != null && !cumulativeQuantity.isZero() && cumulativeQuantity.compareTo(bracketOrder.getTotalQuantity()) < 0`
  - `onCompletedOrder(...)`: `isParentOrderId(order.orderId())`, `(isCancelledOrInactive(completedStatus) || "Rejected".equalsIgnoreCase(completedStatus))`, and `!(getFilledQuantity() == null || getFilledQuantity().isZero())`

#### -> FILLED

- **Controlling Methods and Conditions:**
  - `onOrderStatus(...)`: `bracketOrder.isExitOrderId(orderIdentifier) && "Filled".equalsIgnoreCase(status) && bracketOrder.getSlices().stream().allMatch(BracketOrder.ExitSlice::isFilled)`
  - `onCompletedOrder(...)`: `bracketOrder.isExitOrderId(order.orderId()) && "Filled".equalsIgnoreCase(completedStatus) && bracketOrder.getSlices().stream().allMatch(BracketOrder.ExitSlice::isFilled)`

#### -> CANCELLED

- **Controlling Methods and Conditions:**
  - `onOrderStatus(...)`: `isCancelledOrInactive(status) && bracketOrder.isParentOrderId(orderIdentifier) && (filledQuantity == null || filledQuantity.isZero())`
  - `onError(...)`: `errorCode == 202 && bracketOrder.isParentOrderId(identifier) && (getFilledQuantity() == null || getFilledQuantity().isZero())`
  - `onCompletedOrder(...)`: `isParentOrderId(order.orderId())`, `isCancelledOrInactive(completedStatus)`, `!"Rejected".equalsIgnoreCase(completedStatus)`, and `(getFilledQuantity() == null || getFilledQuantity().isZero())`
- **`isCancelledOrInactive(String status)`:** `"Cancelled"`, `"ApiCancelled"`, or `"Inactive"` (case-insensitive)

#### -> REJECTED

- **Controlling Methods and Conditions:**
  - `onError(...)`: `errorCode` in `{102, 110, 136, 161, 201, 321, 10147, 10148, 10197}`, `bracketOrder.isParentOrderId(identifier)`, and `(getFilledQuantity() == null || getFilledQuantity().isZero())`
  - `onCompletedOrder(...)`: `isParentOrderId(order.orderId())`, `"Rejected".equalsIgnoreCase(completedStatus)`, and `(getFilledQuantity() == null || getFilledQuantity().isZero())`

### 2.3 `BracketOrder.ExitSlice.isFilled`

- **State Change:** `false` -> `true`
- **Controlling Methods:**
  - `public void onOrderStatus(...)` — conditions: `bracketOrder.isExitOrderId(orderIdentifier)`, `"Filled".equalsIgnoreCase(status)`, and `bracketOrder.getSliceByOrderId(orderIdentifier) != null`
  - `public void onCompletedOrder(Contract contract, Order order, OrderState orderState)` — conditions: `bracketOrder.isExitOrderId(order.orderId())` and `"Filled".equalsIgnoreCase(completedStatus)`
- **Setter:** `public void setFilled(boolean isFilled)`. No `setFilled(false)` call exists in the source.

### 2.4 `BracketOrder.OrderLegState`

- **State Change:** `status` `"PENDING_SUBMIT"` -> broker-supplied string; `acknowledged` `false` -> `true`
- **Controlling Method:** `private void applyBrokerUpdate(long permanentId, String status, Decimal filled, Decimal remaining)`, reached through `public synchronized void applyBrokerUpdate(int apiOrderId, long permanentId, String brokerStatus, Decimal filled, Decimal remaining)`
- **Transition Conditions:**
  - `legStates.get(apiOrderId) != null` (otherwise no write)
  - `permanentId > 0` for the `permanentId` field
  - `status != null && !status.isBlank()` for the `status` field
  - `filled != null` for `filledQuantity`; `remaining != null` for `remainingQuantity`
  - `this.acknowledged = true;` unconditional once the leg is resolved
- **Registration:** `public synchronized void registerOrderLeg(int apiOrderId, String role, String orderReference, Decimal quantity)` creates the leg in `"PENDING_SUBMIT"`.

### 2.5 `EngineMode` (via `TradingGate`)

#### Global write guard

- **Controlling Method:** `public void transitionTo(EngineMode mode, String reason)`
- **Transition Conditions:** the update is discarded (`return current`) when
  `current.mode() == EngineMode.MANUAL_INTERVENTION && mode != EngineMode.MANUAL_INTERVENTION && mode != EngineMode.STOPPING`.
  Otherwise `new State(mode, reason, System.currentTimeMillis())` is stored.

#### -> CONNECTING

- **Controlling Method:** `private void connectNow()` (`IbkrSessionManager`)
  - Conditions: `!stopping && !client.isConnected()`
- **Controlling Method:** `private void scheduleReconnect()` (`IbkrSessionManager`)
  - Conditions: `!stopping && reconnectScheduled.compareAndSet(false, true)`

#### -> RECONCILING

- **Controlling Method:** `public synchronized void begin(String reason)` (`ReconciliationManager`)
  - Conditions: `client != null && client.isConnected()`; an epoch is created before the transition
- **Controlling Method:** `private void restoreBrokerConnectivity(boolean dataLost)` (`IbkrSessionManager`)
  - Conditions: `!stopping && client.isConnected()`; reached from `onError` codes `1101` (`dataLost = true`) and `1102` (`dataLost = false`)

#### -> READY

- **Controlling Method:** `private void completeIfReady(CollectionEpoch epoch)` (`ReconciliationManager`)
- **Transition Conditions:**
  1. `activeEpoch == epoch`
  2. `epoch.complete()` — `positionsComplete && openOrdersComplete && completedOrdersComplete && executionsComplete`
  3. `differences.isEmpty()` where `differences = compare(snapshot, stateStore.snapshot())`

#### -> DEGRADED

- **Controlling Method:** `public synchronized void onDisconnected(String reason)` (`ReconciliationManager`); sets `activeEpoch = null` first
- **Transition Conditions:** unconditional once called. Call sites in `IbkrSessionManager`:
  - `public void onConnectionClosed()` when `!stopping`
  - `public void onError(int errorCode, String errorMessage)` for `errorCode == 1100`
  - `onError` for `errorCode == 1300`
  - `onError` for `errorCode` in `{502, 504, 509}`

#### -> MANUAL_INTERVENTION

- **Controlling Method:** `public void requireManualIntervention(String reason)` → `transitionTo(EngineMode.MANUAL_INTERVENTION, reason)`
- **Transition Conditions by call site:**

| Call site | Condition |
|---|---|
| `Main.main(String[])` | `!stateStore.activeTrades().isEmpty()` |
| `IbkrSessionManager.onManagedAccounts(String accountsList)` | `accounts(accountsList).length == 0` |
| `IbkrSessionManager.onManagedAccounts(String accountsList)` | `!expectedAccount.isBlank() && !expectedAccountIsManaged` |
| `IbkrSessionManager.initializeConnectedSession()` | `catch (InterruptedException)` / `catch (RuntimeException)` |
| `IbkrSessionManager.restoreBrokerConnectivity(boolean dataLost)` | `catch (InterruptedException)` / `catch (RuntimeException)` |
| `ReconciliationManager.completeIfReady(CollectionEpoch epoch)` | `!differences.isEmpty()` |
| `ReconciliationManager.failWithoutSnapshot(String reason)` via `begin(String reason)` | `client == null \|\| !client.isConnected()` |
| `ReconciliationManager.failWithoutSnapshot(String reason)` via `failEpoch(long epochNumber, String reason)` | scheduler timeout after `timeout.toMillis()` (default `Duration.ofSeconds(20)`), or `catch (RuntimeException)` around the four `client.req*` calls; guarded by `activeEpoch != null && activeEpoch.number == epochNumber` |
| `AbstractStrategy.escalate(Stock stock, String message)` | `escalatedPendingEntries.add(stock.getTicker())` returns `true`; also sets `blackboard.setSystemHalted(true)` |
| `OrderLifecycleHandler.halt(String reason)` | see 2.6 |
| `BracketOrderExecutor.halt(String reason)` | see 2.7 |

#### -> STOPPING

- **Controlling Method:** `public void close()` (`IbkrSessionManager`); sets `stopping = true` first
- **Transition Conditions:** unconditional; permitted through the `MANUAL_INTERVENTION` guard

### 2.6 `OrderLifecycleHandler.halt(String reason)` triggers

`private void halt(String reason)` sets `blackboard.setSystemHalted(true)` and calls `tradingGate.requireManualIntervention(reason)`.

| Controlling Method | Condition |
|---|---|
| `onOpenOrder(...)` | `bracketOrder.isParentOrderId(orderIdentifier) && Math.abs(order.lmtPrice() - bracketOrder.getEntryPrice()) > 0.0001` |
| `onOpenOrder(...)` | `bracketOrder.isParentOrderId(orderIdentifier) && !order.totalQuantity().equals(bracketOrder.getTotalQuantity())` |
| `validateExitSlice(int orderIdentifier, Order order, BracketOrder bracketOrder)` | `orderIdentifier == exitSlice.getStopLossOrderId() && Math.abs(order.auxPrice() - exitSlice.getStopLossPrice()) > 0.0001` |
| `validateExitSlice(...)` | `orderIdentifier == exitSlice.getTakeProfitOrderId() && Math.abs(order.lmtPrice() - exitSlice.getTakeProfitPrice()) > 0.0001` |
| `validateExitSlice(...)` | `orderIdentifier == exitSlice.getTimeExitOrderId() && !brokerTime.equals(bracketOrder.getGoodAfterTimeString(exitSlice.getTimeExit()))` |
| `validateExitSlice(...)` | `!order.totalQuantity().equals(exitSlice.getQuantity())` |
| `onOrderStatus(...)` | parent partially filled: `isParentOrderId && filledQuantity != null && !filledQuantity.isZero() && !isCancelledOrInactive(status)` |
| `onOrderStatus(...)` | `isCancelledOrInactive(status) && isParentOrderId && filled non-zero` |
| `onError(...)` | `errorCode` in `{102, 110, 136, 161, 201, 321, 10147, 10148, 10197}` (all branches) |
| `onError(...)` | `errorCode == 202 && isParentOrderId(identifier) && filled non-zero` |
| `onExecDetails(...)` | `cumulativeQuantity.compareTo(bracketOrder.getTotalQuantity()) < 0` |
| `onCompletedOrder(...)` | completed parent cancelled/inactive/rejected with `filled` non-zero |
| `persist(BracketOrder bracketOrder)` | `catch (IOException)` from `stateStore.recordBrokerUpdate(bracketOrder, bracketOrder.getAction())` |

### 2.7 `BracketOrderExecutor.halt(String reason)` triggers

`private void halt(String reason)` sets `blackboard.setSystemHalted(true)` and calls `tradingGate.requireManualIntervention(reason)`.

| Controlling Method | Condition |
|---|---|
| `placeTripleThreat(String strategyName, TradeDirection tradeDirection, String tickerSymbol, Decimal totalOrderQuantity, double entryLimitPrice, List<SliceIntent> sliceIntents)` | `stock.getContract() == null` |
| `placeTripleThreat(...)` | `configuredAccount().isBlank()` |
| `placeTripleThreat(...)` | `catch (RuntimeException)` around `client.placeOrder(...)` → also throws `UncertainOrderSubmissionException` |
| `validateEntryIntent(String tickerSymbol, Decimal totalOrderQuantity, double entryLimitPrice, List<SliceIntent> sliceIntents)` | `!client.isConnected()` |
| `updateTripleThreatExits(Stock stock, BracketOrder bracketOrder, BracketOrder.ExitSlice exitSlice, double newTakeProfitPrice, double newStopLossPrice, long newExitTime)` | `!client.isConnected()` |
| `updateTripleThreatExits(...)` | `stock.getPositionSize().value().signum() == 0` |
| `updateTripleThreatExits(...)` | `catch (RuntimeException)` around `client.placeOrder(...)` |
| `persistIntent(BracketOrder bracketOrder, String actionDirection)` | `catch (IOException)` from `stateStore.recordIntent(...)` |

`placeTripleThreat` additionally throws before any state write when `!tradingGate.allowsNewEntries()`.

### 2.8 `Blackboard.globalPendingOwner`

- **State Change:** `null` -> `EntryOwner(strategyName, ticker)`
  - **Controlling Method:** `public boolean tryAcquireGlobalPending(String strategyName, String ticker)`
  - **Transition Conditions:** `globalPendingOwner.compareAndSet(null, new EntryOwner(strategyName, ticker))` returns `true`
- **State Change:** `EntryOwner(strategyName, ticker)` -> `null`
  - **Controlling Method:** `public boolean releaseGlobalPending(String strategyName, String ticker)`
  - **Transition Conditions:** `expectedOwner.equals(currentOwner)` (otherwise returns `false` with no write), then `globalPendingOwner.compareAndSet(currentOwner, null)` in a retry loop
  - **Release call sites:** `AbstractStrategy.acknowledgeStatusChange` (`WORKING_PARENT`, `PARTIAL_PARENT`, `POSITION_OPEN`, terminal-with-fill branches), `cleanupOwnedLifecycle`, `EntryAdmission.tryAdmit`'s step-two and step-three unwinds, `EntryAdmission.Reservation.release()`, `OrderLifecycleHandler.markPositionOpen`, `OrderLifecycleHandler.completeConfirmedFlat`

### 2.9 `Blackboard.activePositionOwners`

- **State Change:** ticker absent -> ticker owned by `strategyName`
  - **Controlling Method:** `public synchronized boolean tryReservePosition(String ticker, String strategyName)`
  - **Transition Conditions:** `!activePositionOwners.containsKey(normalizedTicker) && activePositionOwners.size() < config.getMaxActivePositions()`; identifiers must be non-null and non-blank (`requireIdentifier`)
- **State Change:** ticker owned -> ticker absent
  - **Controlling Method:** `public synchronized boolean releasePosition(String ticker, String strategyName)`
  - **Transition Conditions:** `activePositionOwners.remove(normalizedTicker, normalizedStrategy)` — removal only when the recorded owner equals `strategyName`
  - **Release call sites:** `cleanupOwnedLifecycle`, `EntryAdmission.tryAdmit`'s step-three unwind, `EntryAdmission.Reservation.release()`, `OrderLifecycleHandler.completeConfirmedFlat`

### 2.10 `ReconciliationManager` collection epoch

- **State Change:** idle (`activeEpoch == null`) -> collecting
  - **Controlling Method:** `public synchronized void begin(String reason)`
  - **Transition Conditions:** `client != null && client.isConnected()`; assigns `activeEpoch = new CollectionEpoch(++nextEpoch, executionRequestId)` before issuing `reqPositions()`, `reqAllOpenOrders()`, `reqCompletedOrders(false)`, `reqExecutions(executionRequestId, new ExecutionFilter())`
- **Sub-state completion flags** (each requires `activeEpoch != null`):

| Flag | Controlling Method | Extra condition |
|---|---|---|
| `positionsComplete` | `public synchronized void onPositionEnd()` | — |
| `openOrdersComplete` | `public synchronized void onOpenOrderEnd()` | — |
| `completedOrdersComplete` | `public synchronized void onCompletedOrdersEnd()` | — |
| `executionsComplete` | `public synchronized void onExecutionEnd(int requestId)` | `activeEpoch.executionRequestId == requestId` |

- **State Change:** collecting -> idle (success or difference report)
  - **Controlling Method:** `private void completeIfReady(CollectionEpoch epoch)`
  - **Transition Conditions:** `activeEpoch == epoch && epoch.complete()`; sets `activeEpoch = null`, `brokerState.replaceWith(snapshot)`, then branches on `differences.isEmpty()`
- **State Change:** collecting -> idle (failure)
  - **Controlling Method:** `private synchronized void failEpoch(long epochNumber, String reason)`
  - **Transition Conditions:** `activeEpoch != null && activeEpoch.number == epochNumber`
- **State Change:** collecting -> idle (disconnect)
  - **Controlling Method:** `public synchronized void onDisconnected(String reason)`
  - **Transition Conditions:** unconditional `activeEpoch = null`
- **Record routing:** `private BrokerState targetState()` returns `activeEpoch == null ? brokerState : activeEpoch.collected`, which determines whether `onPosition`, `onOpenOrder`, `onCompletedOrder`, `onExecution`, and `onOrderStatus` write to the live state or the epoch snapshot.

### 2.11 Tick-by-tick stream state (`TickByTickManager`)

- **State Change:** inactive -> active
  - **Controlling Method:** `public void tryRequestStream(String ticker)`
  - **Transition Conditions:** `!isStreamActive(ticker)`; then a CAS loop while `current < MAX_STREAMS` (`MAX_STREAMS = 5`) on `activeStreamCount.compareAndSet(current, current + 1)`. On CAS success `executeRequest(ticker)` puts the request id in `activeRequests`.
- **State Change:** active -> inactive
  - **Controlling Method:** `public void cancelStream(String ticker)`
  - **Transition Conditions:** `activeRequests.remove(ticker) != null`; then `client.cancelTickByTickData(reqId)`, `registry.unregister(reqId)`, `activeStreamCount.decrementAndGet()`
- **Strategy-level triggers:**
  - `protected void evaluateTickStreamNeed(MarketSnapshot market, double entryPrice)` (`TwoSigmaDownsideMeanReversionStrategy`):
    - request: `!isStreamActive && lastPrice <= entryPrice * 1.0025`
    - cancel: `isStreamActive && lastPrice > entryPrice * 1.0035`
  - `protected void evaluateTickStreamNeed(MarketSnapshot market, double entryPrice)` (`OneSigmaDownsideMeanReversionStrategy`, `OneSigmaUpsideMeanReversionStrategy`): empty method bodies; no transition.
  - `AbstractStrategy.cleanupOwnedLifecycle` and `AbstractStrategy.rollbackEntryReservation` both cancel when `tickStreamController.isStreamActive(stock.getTicker())`.

### 2.12 Market-data input readiness (`MarketDataInputStore`)

- **State Change:** input unrecorded -> recorded
  - **Controlling Method:** `public void record(String ticker, MarketDataInput input)`
  - **Transition Conditions:** `normalize(ticker) != null` and `input != null`; writes `new Reading(newYorkClock.millis(), LocalDate.now(newYorkClock))`
- **State Change:** all inputs recorded -> unrecorded
  - **Controlling Method:** `public void markAllStale()` — unconditional `readings.clear()`
  - **Call sites:** `MarketDataSubscriptionManager.markAllStale()`, itself called from `initializeIfNeeded()`, `resubscribeAfterDataLoss()`, and `IbkrSessionManager.markMarketDataStale()` when `subscriptionManager != null`
- **Readiness evaluation:** `private String unreadyReason(String normalizedTicker, MarketDataInput input)` returns non-`null` (unready) when:
  - `normalizedTicker == null`, or `input == null`
  - `readingFor(...) == null`
  - `input.expiry() == MarketDataInput.Expiry.SESSION_SCOPED && !reading.sessionDate().equals(LocalDate.now(newYorkClock))`
  - otherwise `Math.abs(newYorkClock.millis() - reading.recordedAtUnixMs()) > maxAgeMs`
- **Aggregate readers:** `public boolean areAllFresh(String ticker, Set<MarketDataInput> required)` returns `false` when `required.isEmpty()`; `public Optional<String> describeUnready(String ticker, Set<MarketDataInput> required)` returns `Optional.of("no required market-data inputs are declared")` when `required.isEmpty()`.

### 2.13 Subscription initialization (`MarketDataSubscriptionManager`)

- **State Change:** uninitialized -> initialized
  - **Controlling Method:** `public void initializeIfNeeded() throws InterruptedException`
  - **Transition Conditions:** `initialized.compareAndSet(false, true)` returns `true` (otherwise returns immediately)
- **State Change:** initialized -> uninitialized -> initialized
  - **Controlling Method:** `public void resetAfterSocketReconnect() throws InterruptedException`
  - **Transition Conditions:** unconditional `cancelTrackedRequests()`, `initialized.set(false)`, `initializeIfNeeded()`
- **Selection at session start:** `IbkrSessionManager.initializeConnectedSession()` calls `resetAfterSocketReconnect()` when `hasConnectedBefore`, otherwise `initializeIfNeeded()`; both guarded by `subscriptions != null`.

### 2.14 Session-manager latches (`IbkrSessionManager`)

| Field | State Change | Controlling Method | Conditions |
|---|---|---|---|
| `apiReadyHandled` | `false` -> `true` | `public void onNextValidId()` | `apiReadyHandled.compareAndSet(false, true)`; otherwise returns |
| `apiReadyHandled` | `true` -> `false` | `public void onConnectionClosed()` | `!stopping` |
| `apiReadyHandled` | -> `false` | `private void connectNow()` | after the `CONNECTING` transition |
| `reconnectScheduled` | `false` -> `true` | `private void scheduleReconnect()` | `!stopping && reconnectScheduled.compareAndSet(false, true)` |
| `reconnectScheduled` | `true` -> `false` | `private void connectNow()` | `!stopping && !client.isConnected()` |
| `hasConnectedBefore` | `false` -> `true` | `private void initializeConnectedSession()` | reached after the subscription branch, requires `!stopping && client.isConnected()` |
| `stopping` | `false` -> `true` | `public void close()` | unconditional |
| `currentPort` | changed | `private void updatePortFromMessage(String message)` | `message != null`, `PORT_PATTERN` matches, `parsedPort > 0 && parsedPort <= 65535`; reached from `onError` code `1300` |

### 2.15 `Blackboard` and `Stock` boolean latches

| State Change | Controlling Method | Conditions |
|---|---|---|
| `systemHalted` `false` -> `true` | `AbstractStrategy.escalate(Stock stock, String message)` | unconditional within `escalate` |
| `systemHalted` `false` -> `true` | `OrderLifecycleHandler.halt(String reason)` | see 2.6 |
| `systemHalted` `false` -> `true` | `BracketOrderExecutor.halt(String reason)` | see 2.7 |
| `systemUpdateRequired` `false` -> `true` | `IbkrErrorHandler.process(int reqId, int errorCode, String errorMsg, String advancedOrderRejectJson)` | `reqId <= 0` and `errorCode` in `{503, 505, 507, 508, 323}` |
| `openOrderEnd` `false` -> `true` | `OrderLifecycleHandler.onOpenOrderEnd()` | unconditional |
| `isTradeable` `true` -> `false` | `AbstractStrategy.processSymbolSafely(Stock stock)` | `catch (RuntimeException)` |

No source location resets `systemHalted`, `systemUpdateRequired`, `openOrderEnd`, or `isTradeable` to `false`.

### 2.16 `Stock.activeBracket`

| State Change | Controlling Method | Conditions |
|---|---|---|
| `null` -> bracket | `BracketOrderExecutor.placeTripleThreat(...)` | after `blackboard.getOrderRegistry().register(bracketOrder)` |
| bracket -> `null` | `BracketOrderExecutor.placeTripleThreat(...)` | `catch (RuntimeException)` from `persistIntent(...)` |
| bracket -> `null` | `AbstractStrategy.cleanupOwnedLifecycle(Stock stock, String strategyId, BracketOrder bracketOrder)` | `bracketOrder == null || isConfirmedTerminal(bracketOrder.getStatus())` |
| bracket -> `null` | `OrderLifecycleHandler.completeConfirmedFlat(BracketOrder bracketOrder)` | `stock.getActiveBracket() == bracketOrder` |

`evaluateNewEntry` throws `IllegalStateException("Order gateway did not install the returned bracket")` when `bracketOrder == null || stock.getActiveBracket() != bracketOrder`.

### 2.17 Persisted trade status (`JsonTradingStateStore`)

- **State Change:** trade record `status` mirrors `BracketOrder.getStatus().name()`
  - **Controlling Method:** `public synchronized void recordIntent(BracketOrder bracketOrder, String action) throws IOException`
  - **Transition Conditions:** unconditional overwrite of `tradesById.get(bracketOrder.getTradeId())`; `createdAtEpochMillis` is preserved when `existing != null`
  - `public synchronized void recordBrokerUpdate(BracketOrder bracketOrder, String action)` delegates to `recordIntent`
- **Terminal predicate:** `public boolean terminal()` — `"FILLED".equals(status) || "CANCELLED".equals(status) || "REJECTED".equals(status)`
- **Active set:** `public synchronized List<TradeRecord> activeTrades()` filters `!trade.terminal()`
- **Recovery flag:** `recoveredFromBackup` is set in `private void load()` when `loadFrom(statePath)` throws `IOException` and `Files.exists(backupPath)`

### 2.18 Options-indicator frame acceptance (`OptionsIndicatorStore`)

- **State Change:** stored `TickerState` / `GammaFlip` replaced (accept), or unchanged (reject)
- **Controlling Method:** `public boolean accept(IndicatorFrame frame, long receivedAtUnixMs)`
- **Rejection Conditions** (each returns `false` via `reject(String)` and leaves prior state untouched):
  1. `frame == null`
  2. `normalize(frame.getTicker()).isEmpty()`
  3. `!knownTickers.contains(ticker)`
  4. `parseTradingDate(frame.getTradingDate()) == null` (requires trimmed length `10` and a parseable `LocalDate`)
  5. `frame.getEmittedAtUnixMs() <= 0L`
  6. `emittedAtUnixMs - receivedAtUnixMs > frameMaxAgeMs`
  7. `frame.getSequence() <= 0L`
  8. `impliedMoveValid && !isPositiveFinite(impliedMoveValue)`
  9. `gammaFlipValid && !isPositiveFinite(gammaFlipLevel)`
  10. `previous != null && sequence <= previous.sequence() && receivedAtUnixMs - previous.receivedAtUnixMs() <= frameMaxAgeMs`
- **Accept effects:** `stateByTicker.put(ticker, new TickerState(...))`; `gammaFlip.set(new GammaFlip(...))`; `acceptedFrames.incrementAndGet()`. `retainedImpliedMove` is replaced only when `impliedMoveValid`, otherwise the previous `lastValidImpliedMove` is carried forward.
- **Read gates:**
  - `public OptionalDouble impliedMoveForNewEntry(String ticker, LocalDate tradingDate, long nowUnixMs)` returns empty when `state == null || !state.impliedMoveValid() || !state.tradingDate().equals(tradingDate) || !isFresh(state.receivedAtUnixMs(), nowUnixMs)`, or when `impliedMove == null || !impliedMove.tradingDate().equals(tradingDate)`
  - `public OptionalDouble gammaFlipForNewEntry(LocalDate tradingDate, long nowUnixMs)` returns empty when `currentGammaFlip == null || !currentGammaFlip.valid() || !currentGammaFlip.tradingDate().equals(tradingDate) || !isFresh(...)`
  - `private boolean isFresh(long receivedAtUnixMs, long nowUnixMs)` — `Math.abs(nowUnixMs - receivedAtUnixMs) <= frameMaxAgeMs`

### 2.19 `BlackboardMonitor.ViewState`

- **State Change:** `VIEW_1` / `VIEW_2` / `VIEW_ALL` -> target state
- **Controlling Method:** `private void updateView(ViewState targetViewState)`
- **Transition Conditions:** unconditional assignment, then `applyColumnVisibility()` and `updateButtonStyles()`. Triggered by `view1Button` → `VIEW_1`, `view2Button` → `VIEW_2`, `viewAllButton` → `VIEW_ALL`, and by the constructor call `updateView(ViewState.VIEW_1)`.

---

## 3. Guard Predicates Referenced Above

| Predicate | Owning class | Definition |
|---|---|---|
| `isConfirmedTerminal(BracketOrder.Status status)` | `AbstractStrategy` | `status == FILLED \|\| status == CANCELLED \|\| status == REJECTED` |
| `isZero(Decimal quantity)` | `AbstractStrategy` | `quantity == null \|\| quantity.isZero()` |
| `acknowledgementTimedOut(long submittedAtMillis)` | `AbstractStrategy` | `clock.millis() - submittedAtMillis >= config.getEntryAcknowledgementTimeoutMs()` |
| `entryInputsReady(Stock stock)` | `AbstractStrategy` | `marketDataFreshness.describeUnready(stock.getTicker(), requiredEntryInputs()).isEmpty()` |
| `automatedOrderChangesAllowed(Stock stock)` | `AbstractStrategy` | `tradingGate.allowsAutomatedOrderChanges()` and `marketDataFreshness.describeUnready(stock.getTicker(), requiredManagementInputs()).isEmpty()` |
| `isCancelledOrInactive(String status)` | `OrderLifecycleHandler` | `"Cancelled"`, `"ApiCancelled"`, or `"Inactive"` (case-insensitive) |
| `isWorkingStatus(String status)` | `OrderLifecycleHandler` | `"PendingSubmit"`, `"PreSubmitted"`, `"Submitted"`, or `"PendingCancel"` (case-insensitive) |
| `isTerminalOrderStatus(String status)` | `ReconciliationManager` | `"Filled"`, `"Cancelled"`, `"ApiCancelled"`, or `"Inactive"` (case-insensitive) |
| `isAccountCurrentForNewEntry()` | `Blackboard` | `refreshedAt > 0 && refreshedAt > lastEntrySubmittedAtMillis.get()` |
| `allowsNewEntries()` | `TradingGate` | `getMode() == EngineMode.READY` |
| `allowsAutomatedOrderChanges()` | `TradingGate` | `getMode() == EngineMode.READY` |

### Escalation without a state write

`AbstractStrategy.handlePendingEntry(Stock)` holds only the clock-driven half of the
pending entry; everything status-driven moved to `acknowledgeStatusChange`. It
returns unless `pendingEntry == null || acknowledgementTimedOut(pendingEntry.submittedAtMillis())`,
and then escalates when:

- `bracketOrder == null` → `"Pending entry has no local bracket and cannot be resolved safely"`
- `bracketOrder.getStatus() == INITIALIZED` → `"IBKR did not acknowledge the entry before the configured timeout"`

`AbstractStrategy.updateExits(Stock stock, BracketOrder bracketOrder, BracketOrder.ExitSlice exitSlice, double takeProfitPrice, double stopLossPrice, long timeExitValue)` returns without acting when
`stock.positionState(true) != Stock.PositionState.OPEN || !blackboard.isPositionOwnedBy(stock.getTicker(), strategyId()) || !automatedOrderChangesAllowed(stock)`.
