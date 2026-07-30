---
paths:
  - "trading-engine/**/*"
---

# Package `mwd.trading.execution`

Sources:
- `trading-engine/trading-engine/src/main/java/mwd/trading/execution/BracketOrder.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/execution/BracketOrderExecutor.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/execution/BracketOrderGateway.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/execution/OrderLifecycleHandler.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/execution/OrderRegistry.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/execution/UncertainOrderSubmissionException.java`

---

## `BracketOrder`

`public class BracketOrder`

### 1. Class/Interface Responsibilities

Mutable record of one parent entry order plus its exit slices: trade identity fields, a `List<ExitSlice>`, a `LinkedHashMap<Integer, OrderLegState>` of per-leg broker state, an `AtomicInteger` update counter, and the consolidated entry price, quantities, and `Status`.

### 2. Injected Dependencies

Constructors:

`public BracketOrder(String ticker, int parentOrderId, Decimal totalQuantity)` — delegates to the eight-argument constructor with `"LEGACY-" + ticker + '-' + parentOrderId`, `"UNKNOWN"`, `""`, `0`, `""`.

`public BracketOrder(String tradeId, String strategyName, String account, int contractId, String action, String ticker, int parentOrderId, Decimal totalQuantity)`

| Parameter | Exact type |
| --- | --- |
| `tradeId` | `java.lang.String` |
| `strategyName` | `java.lang.String` |
| `account` | `java.lang.String` |
| `contractId` | `int` |
| `action` | `java.lang.String` |
| `ticker` | `java.lang.String` |
| `parentOrderId` | `int` |
| `totalQuantity` | `com.ib.client.Decimal` |

Nested `ExitSlice` constructor: `public ExitSlice(int takeProfitOrderId, int stopLossOrderId, int timeExitOrderId, Decimal quantity, String oneCancelsAllGroup)`.

Nested `OrderLegState` constructor: `private OrderLegState(int apiOrderId, String role, String orderReference, Decimal quantity)`.

### 3. Method Signatures

Nested types:
- `public enum Status { INITIALIZED, WORKING_PARENT, PARTIAL_PARENT, POSITION_OPEN, FILLED, CANCELLED, REJECTED }`
- `public static final class OrderLegState`
- `public static class ExitSlice`

```java
public BracketOrder(String ticker, int parentOrderId, Decimal totalQuantity)
public BracketOrder(String tradeId, String strategyName, String account, int contractId, String action, String ticker, int parentOrderId, Decimal totalQuantity)

public void addSlice(ExitSlice slice)
public synchronized void registerOrderLeg(int apiOrderId, String role, String orderReference, Decimal quantity)
public synchronized void applyBrokerUpdate(int apiOrderId, long permanentId, String brokerStatus, Decimal filled, Decimal remaining)
public synchronized Collection<OrderLegState> getLegStates()
public List<ExitSlice> getSlices()

public boolean isParentOrderId(int orderId)
public boolean isExitOrderId(int orderId)
public ExitSlice getSliceByOrderId(int orderId)
public String getLegName(int orderId)

public String getTradeId()
public String getStrategyName()
public String getAccount()
public int getContractId()
public String getAction()
public String getTicker()
public int getParentOrderId()
public double getEntryPrice()
public void setEntryPrice(double entryPrice)
public Decimal getTotalQuantity()
public Status getStatus()
public void setStatus(Status status)
public Decimal getFilledQuantity()
public void setFilledQuantity(Decimal filledQuantity)
public Decimal getRemainingQuantity()
public void setRemainingQuantity(Decimal remainingQuantity)
public int incrementUpdateCount()
public int getUpdateCount()
public String getGoodAfterTimeString(long timeExitValue)
```

`OrderLegState`:

```java
private OrderLegState(int apiOrderId, String role, String orderReference, Decimal quantity)
public int getApiOrderId()
public String getRole()
public String getOrderReference()
public long getPermanentId()
public String getStatus()
public Decimal getFilledQuantity()
public Decimal getRemainingQuantity()
public boolean isAcknowledged()
private void applyBrokerUpdate(long permanentId, String status, Decimal filled, Decimal remaining)
```

`ExitSlice`:

```java
public ExitSlice(int takeProfitOrderId, int stopLossOrderId, int timeExitOrderId, Decimal quantity, String oneCancelsAllGroup)
public long getLastModificationTime()
public void setLastModificationTime(long time)
public int getTakeProfitOrderId()
public int getStopLossOrderId()
public int getTimeExitOrderId()
public Decimal getQuantity()
public String getOneCancelsAllGroup()
public boolean isFilled()
public double getTakeProfitPrice()
public void setTakeProfitPrice(double takeProfitPrice)
public double getStopLossPrice()
public void setStopLossPrice(double stopLossPrice)
public long getTimeExit()
public void setTimeExit(long timeExit)
public void setFilled(boolean isFilled)
```

### 4. Global State Interactions

**Concurrent collections / atomics**

| Field | Declared type |
| --- | --- |
| `updateRequestCount` | `final AtomicInteger` |
| `slices` | `final List<ExitSlice>` = `new ArrayList<>()` (not synchronized) |
| `legStates` | `final Map<Integer, OrderLegState>` = `new LinkedHashMap<>()` (guarded by the `synchronized` methods below) |

| Method | Interaction |
| --- | --- |
| `incrementUpdateCount()` | Mutates `updateRequestCount` (`incrementAndGet`) |
| `getUpdateCount()` | Reads `updateRequestCount` (`get`) |
| `registerOrderLeg(int, String, String, Decimal)` | `synchronized`; mutates `legStates` |
| `applyBrokerUpdate(int, long, String, Decimal, Decimal)` | `synchronized`; reads `legStates` and mutates the resolved `OrderLegState` |
| `getLegStates()` | `synchronized`; reads `legStates`, returns `List.copyOf(...)` |
| `getLegName(int)` | Reads `legStates` (unsynchronized) and `slices` |
| `addSlice(ExitSlice)` | Mutates `slices` |
| `getSlices()` | Returns the mutable `slices` list itself |
| `isExitOrderId(int)`, `getSliceByOrderId(int)` | Read `slices` |

`OrderLegState` fields `permanentId`, `status`, `filledQuantity`, `remainingQuantity`, `acknowledged` are `volatile`. So are `BracketOrder`'s own `entryPrice`, `status`, `filledQuantity`, and `remainingQuantity`, and every mutable `ExitSlice` field; `totalQuantity` is `final`. All of them are written on the IBKR reader thread and read by the strategy poll threads.

**Centralized state objects**

No `Blackboard` reference. Instances are registered into `OrderRegistry` and attached to `Stock.setActiveBracket(BracketOrder)` by other classes.

---

## `BracketOrderGateway`

`public interface BracketOrderGateway`

### 1. Class/Interface Responsibilities

Declares the two order operations a strategy may invoke: place a parent entry with a list of exit-slice intents, and update one slice's exit legs.

### 2. Injected Dependencies

None. Interfaces declare no constructor.

### 3. Method Signatures

```java
BracketOrder placeTripleThreat(
        String strategyName,
        TradeDirection tradeDirection,
        String tickerSymbol,
        Decimal totalOrderQuantity,
        double entryLimitPrice,
        List<BracketOrderExecutor.SliceIntent> sliceIntents)

void updateTripleThreatExits(
        Stock stock,
        BracketOrder bracketOrder,
        BracketOrder.ExitSlice exitSlice,
        double newTakeProfitPrice,
        double newStopLossPrice,
        long newExitTime)
```

Both are implicitly `public abstract`.

### 4. Global State Interactions

None declared.

---

## `BracketOrderExecutor`

`public class BracketOrderExecutor implements BracketOrderGateway`

### 1. Class/Interface Responsibilities

Builds the IBKR `Order` bundle for a parent entry plus per-slice take-profit, stop-loss, and time-exit legs, validates the intent, registers the `BracketOrder`, persists it, and submits or updates the orders through `EClientSocket`.

### 2. Injected Dependencies

Constructor: `public BracketOrderExecutor(Blackboard blackboard, EClientSocket client, TradingGate tradingGate, JsonTradingStateStore stateStore, Config config)`

| Parameter | Exact type |
| --- | --- |
| `blackboard` | `mwd.trading.state.Blackboard` |
| `client` | `com.ib.client.EClientSocket` |
| `tradingGate` | `mwd.trading.lifecycle.TradingGate` |
| `stateStore` | `mwd.trading.persistence.JsonTradingStateStore` |
| `config` | `mwd.trading.config.Config` |

All five are `Objects.requireNonNull`-checked.

### 3. Method Signatures

Nested type: `public static class SliceIntent` with public fields `Decimal quantity`, `double takeProfitPrice`, `double stopLossPrice`, `long timeExit` and constructor `public SliceIntent(Decimal quantity, double takeProfitPrice, double stopLossPrice, long timeExit)`.

```java
public BracketOrderExecutor(Blackboard blackboard, EClientSocket client, TradingGate tradingGate, JsonTradingStateStore stateStore, Config config)

@Override public BracketOrder placeTripleThreat(String strategyName, TradeDirection tradeDirection, String tickerSymbol, Decimal totalOrderQuantity, double entryLimitPrice, List<SliceIntent> sliceIntents)
@Override public void updateTripleThreatExits(Stock stock, BracketOrder bracketOrder, BracketOrder.ExitSlice exitSlice, double newTakeProfitPrice, double newStopLossPrice, long newExitTime)

private void persistIntent(BracketOrder bracketOrder, String actionDirection)
private void validateEntryIntent(String tickerSymbol, Decimal totalOrderQuantity, double entryLimitPrice, List<SliceIntent> sliceIntents)
private String configuredAccount()
private static String newTradeId(String ticker)
private static String orderReference(String tradeId, String role)
private static String orderReferenceFor(BracketOrder bracketOrder, int apiOrderId)
private void halt(String reason)
```

### 4. Global State Interactions

**Concurrent collections**

Holds none of its own. Reaches the four `ConcurrentHashMap` instances inside `OrderRegistry` and the `ConcurrentHashMap<String, Stock>` inside `Blackboard`.

**Centralized state objects (`Blackboard`)**

| Method | Interaction |
| --- | --- |
| `placeTripleThreat(...)` | Reads `blackboard.getStock(String)`; mutates `blackboard.getNextOrderId()` (parent and every slice leg); mutates `blackboard.getOrderRegistry().register(BracketOrder)` and, on persistence failure, `unregister(BracketOrder)`; mutates `stock.setActiveBracket(BracketOrder)`; reads `blackboard.getAccount().getAccountId()` via `configuredAccount()`; reads `tradingGate.allowsNewEntries()` and `tradingGate.getMode()` |
| `updateTripleThreatExits(...)` | Reads `tradingGate.allowsAutomatedOrderChanges()` and `getMode()`; reads `stock.getPositionSize()`, `stock.getContract()`; mutates the `ExitSlice` prices and `bracketOrder.incrementUpdateCount()` |
| `configuredAccount()` | Reads `config.getExpectedAccount()` and `blackboard.getAccount().getAccountId()` |
| `persistIntent(BracketOrder, String)` | Calls `stateStore.recordIntent(BracketOrder, String)` |
| `halt(String)` | Mutates `blackboard.setSystemHalted(true)` and `tradingGate.requireManualIntervention(String)` |

`halt(String)` is called from `placeTripleThreat`, `updateTripleThreatExits`, `persistIntent`, and `validateEntryIntent`.

---

## `OrderLifecycleHandler`

`public class OrderLifecycleHandler`

### 1. Class/Interface Responsibilities

Receives the IBKR order, execution, and completed-order callbacks, resolves the owning `BracketOrder` through `OrderRegistry`, applies broker updates and status transitions, releases `Blackboard` reservations, persists to `JsonTradingStateStore`, and forwards each event to `ReconciliationManager`.

### 2. Injected Dependencies

Constructor: `public OrderLifecycleHandler(Blackboard blackboard, ReconciliationManager reconciliationManager, JsonTradingStateStore stateStore, TradingGate tradingGate)`

| Parameter | Exact type |
| --- | --- |
| `blackboard` | `mwd.trading.state.Blackboard` |
| `reconciliationManager` | `mwd.trading.reconciliation.ReconciliationManager` |
| `stateStore` | `mwd.trading.persistence.JsonTradingStateStore` |
| `tradingGate` | `mwd.trading.lifecycle.TradingGate` |

All four are `Objects.requireNonNull`-checked.

### 3. Method Signatures

```java
public OrderLifecycleHandler(Blackboard blackboard, ReconciliationManager reconciliationManager, JsonTradingStateStore stateStore, TradingGate tradingGate)

public void onOpenOrder(int orderIdentifier, Contract contract, Order order, OrderState orderState)
public void onOpenOrderEnd()
public void onOrderStatus(int orderIdentifier, String status, Decimal filledQuantity, Decimal remainingQuantity, double averageFillPrice, long permanentIdentifier, int parentIdentifier, double lastFillPrice, int clientIdentifier, String whyHeld, double marketCapPrice)
public void onError(int identifier, long time, int errorCode, String errorMessage, String advancedOrderRejectJson)
public void onExecDetails(int requestIdentifier, Contract contract, Execution execution)
public void onExecDetailsEnd(int requestIdentifier)
public void onCommissionAndFeesReport(CommissionAndFeesReport report)
public void onCompletedOrder(Contract contract, Order order, OrderState orderState)
public void onCompletedOrdersEnd()
public void onOrderBound(long permanentIdentifier, int apiClientIdentifier, int apiOrderIdentifier)

private void validateExitSlice(int orderIdentifier, Order order, BracketOrder bracketOrder)
private BracketOrder resolveBracket(int apiOrderId, long permanentId, String orderReference)
private void markPositionOpen(BracketOrder bracketOrder)
private void completeConfirmedFlat(BracketOrder bracketOrder)
private void persist(BracketOrder bracketOrder)
private void halt(String reason)
private static boolean isCancelledOrInactive(String status)
private static boolean isWorkingStatus(String status)
```

### 4. Global State Interactions

**Concurrent collections**

Holds none. Every lookup and identity write goes through the `ConcurrentHashMap` instances inside `OrderRegistry`, reached via `blackboard.getOrderRegistry()`.

**Centralized state objects (`Blackboard`)**

| Method | Interaction |
| --- | --- |
| `onOpenOrder(...)` | Reads/mutates `blackboard.getOrderRegistry().recordBrokerIdentity(...)`; reads `blackboard.getStock(...)` indirectly through `markPositionOpen`; calls `reconciliationManager.onOpenOrder(...)` |
| `onOpenOrderEnd()` | Mutates `blackboard.setOpenOrderEnd(true)`; calls `reconciliationManager.onOpenOrderEnd()` |
| `onOrderStatus(...)` | Mutates `blackboard.getOrderRegistry().recordBrokerIdentity(...)`; reads `blackboard.getStock(String)`; calls `markPositionOpen` / `completeConfirmedFlat`; calls `reconciliationManager.onOrderStatus(...)` |
| `onError(...)` | Reads `blackboard.getOrderRegistry().getBracketFor(int)`; reads `blackboard.getStock(String)`; calls `markPositionOpen` / `completeConfirmedFlat` |
| `onExecDetails(...)` | Mutates `blackboard.getOrderRegistry().recordBrokerIdentity(...)`; reads `blackboard.getStock(String)`; calls `reconciliationManager.onExecution(...)` |
| `onExecDetailsEnd(int)` | Calls `reconciliationManager.onExecutionEnd(int)` |
| `onCompletedOrder(...)` | Mutates `blackboard.getOrderRegistry().recordBrokerIdentity(...)`; reads `blackboard.getStock(String)`; calls `reconciliationManager.onCompletedOrder(...)` |
| `onCompletedOrdersEnd()` | Calls `reconciliationManager.onCompletedOrdersEnd()` |
| `onOrderBound(...)` | Mutates `blackboard.getOrderRegistry().recordBrokerIdentity(...)` |
| `onCommissionAndFeesReport(CommissionAndFeesReport)` | No state interaction; empty body with a comment |
| `markPositionOpen(BracketOrder)` | Mutates `blackboard.releaseGlobalPending(String, String)`. It writes no position state: the `OPEN` reading follows from the `Status` the caller set on the bracket |
| `completeConfirmedFlat(BracketOrder)` | Reads `blackboard.getStock(String)`; mutates `stock.setActiveBracket(null)` when it still points at this bracket, `blackboard.releaseGlobalPending(...)`, `blackboard.releasePosition(...)`. The `FLAT` reading follows from the terminal `Status` and the cleared bracket |
| `persist(BracketOrder)` | Calls `stateStore.recordBrokerUpdate(BracketOrder, String)` |
| `halt(String)` | Mutates `blackboard.setSystemHalted(true)` and `tradingGate.requireManualIntervention(String)` |

---

## `OrderRegistry`

`public class OrderRegistry`

### 1. Class/Interface Responsibilities

Indexes `BracketOrder` instances by API order ID, permanent ID, order reference, and trade ID in four `ConcurrentHashMap` fields, and supports register, lookup, broker-identity recording, snapshot, and unregister.

### 2. Injected Dependencies

None. The class declares no constructor.

### 3. Method Signatures

```java
public void register(BracketOrder bracketOrder)
public BracketOrder getBracketFor(int orderIdentifier)
public BracketOrder getBracketForPermanentId(long permanentIdentifier)
public BracketOrder getBracketForOrderReference(String orderReference)
public BracketOrder getBracketForTradeId(String tradeIdentifier)
public void recordBrokerIdentity(int orderIdentifier, long permanentIdentifier, String orderReference)
public Set<BracketOrder> getAllBracketsSnapshot()
public void unregister(BracketOrder bracketOrder)
```

### 4. Global State Interactions

**Concurrent collections**

| Field | Declared type |
| --- | --- |
| `identifierToBracketMap` | `Map<Integer, BracketOrder>` = `new ConcurrentHashMap<>()` |
| `permanentIdentifierToBracketMap` | `Map<Long, BracketOrder>` = `new ConcurrentHashMap<>()` |
| `orderReferenceToBracketMap` | `Map<String, BracketOrder>` = `new ConcurrentHashMap<>()` |
| `tradeIdentifierToBracketMap` | `Map<String, BracketOrder>` = `new ConcurrentHashMap<>()` |

| Method | Interaction |
| --- | --- |
| `register(BracketOrder)` | Mutates all four maps (`put`) |
| `getBracketFor(int)` | Reads `identifierToBracketMap` |
| `getBracketForPermanentId(long)` | Reads `permanentIdentifierToBracketMap` |
| `getBracketForOrderReference(String)` | Reads `orderReferenceToBracketMap` |
| `getBracketForTradeId(String)` | Reads `tradeIdentifierToBracketMap` |
| `recordBrokerIdentity(int, long, String)` | Reads `identifierToBracketMap` and `orderReferenceToBracketMap`; mutates `permanentIdentifierToBracketMap` and `orderReferenceToBracketMap` |
| `getAllBracketsSnapshot()` | Reads `tradeIdentifierToBracketMap` |
| `unregister(BracketOrder)` | Mutates all four maps (`remove`, `entrySet().removeIf`) |

**Centralized state objects**

No `Blackboard` reference. This instance is held by `Blackboard` and reached through `Blackboard.getOrderRegistry()`.

---

## `UncertainOrderSubmissionException`

`public final class UncertainOrderSubmissionException extends RuntimeException`

### 1. Class/Interface Responsibilities

Unchecked exception signalling that order submission started but the accepted legs are unknown.

### 2. Injected Dependencies

Constructor: `public UncertainOrderSubmissionException(String message, Throwable cause)`

| Parameter | Exact type |
| --- | --- |
| `message` | `java.lang.String` |
| `cause` | `java.lang.Throwable` |

### 3. Method Signatures

```java
public UncertainOrderSubmissionException(String message, Throwable cause)
```

Field: `private static final long serialVersionUID = 1L`.

### 4. Global State Interactions

None.
