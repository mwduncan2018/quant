---
paths:
  - "trading-engine/**/*"
---

# Packages `mwd.trading.reconciliation` and `mwd.trading.risk`

Sources:
- `trading-engine/trading-engine/src/main/java/mwd/trading/reconciliation/BrokerState.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/reconciliation/ReconciliationManager.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/risk/MarginPacer.java`

---

## `BrokerState`

`public final class BrokerState`

### 1. Class/Interface Responsibilities

Holds broker-reported positions, orders, and executions as plain-value records in three `HashMap` fields behind `synchronized` accessors, and produces or restores an immutable `Snapshot`.

### 2. Injected Dependencies

None. The class declares no constructor.

### 3. Method Signatures

Nested types:
- `public record PositionRecord(String account, int contractId, String symbol, String securityType, String quantity, double averageCost)`
- `public record OrderRecord(int apiOrderId, long permanentId, int clientId, int parentOrderId, String account, int contractId, String symbol, String orderReference, String action, String orderType, String totalQuantity, double limitPrice, double auxiliaryPrice, String status, String filledQuantity, String remainingQuantity, boolean completed, long updatedAtEpochMillis)`
- `public record ExecutionRecord(String executionId, int apiOrderId, long permanentId, int clientId, String account, int contractId, String symbol, String orderReference, String side, String shares, String cumulativeQuantity, double price, double averagePrice, String brokerTime, long receivedAtEpochMillis)`
- `public record Snapshot(long asOfEpochMillis, List<PositionRecord> positions, List<OrderRecord> orders, List<ExecutionRecord> executions)`

```java
public synchronized void clear()
public synchronized void recordPosition(String account, Contract contract, Decimal quantity, double averageCost)
public synchronized void recordOpenOrder(int apiOrderId, Contract contract, Order order, OrderState orderState)
public synchronized void recordCompletedOrder(Contract contract, Order order, OrderState orderState)
private void recordOrder(int apiOrderId, Contract contract, Order order, OrderState orderState, boolean completed)
public synchronized void recordOrderStatus(int apiOrderId, String status, Decimal filled, Decimal remaining, long permanentId, int parentOrderId, int clientId)
public synchronized void recordExecution(Contract contract, Execution execution)
public synchronized Snapshot snapshot()
public synchronized void replaceWith(Snapshot snapshot)

private static String positionKey(String account, Contract contract)
private static String decimalText(Decimal value)
private static String safe(String value)
```

### 4. Global State Interactions

**Concurrent collections**

None. The three collection fields are non-concurrent `HashMap` instances guarded by the `synchronized` methods above:

| Field | Declared type |
| --- | --- |
| `positions` | `Map<String, PositionRecord>` = `new HashMap<>()` |
| `orders` | `Map<Integer, OrderRecord>` = `new HashMap<>()` |
| `executions` | `Map<String, ExecutionRecord>` = `new HashMap<>()` |

| Method | Interaction |
| --- | --- |
| `clear()` | Mutates all three (`clear`) |
| `recordPosition(...)` | Mutates `positions` (`put` or `remove`) |
| `recordOpenOrder(...)`, `recordCompletedOrder(...)`, `recordOrder(...)` | Read and mutate `orders` (`get`, `put`) |
| `recordOrderStatus(...)` | Reads and mutates `orders` (`get`, `put`) |
| `recordExecution(...)` | Mutates `executions` (`put`) |
| `snapshot()` | Reads all three |
| `replaceWith(Snapshot)` | Calls `clear()` then mutates all three (`put`) |

**Centralized state objects**

No `Blackboard` reference.

---

## `ReconciliationManager`

`public final class ReconciliationManager implements AutoCloseable`

### 1. Class/Interface Responsibilities

Runs a numbered collection epoch that issues `reqPositions`, `reqAllOpenOrders`, `reqCompletedOrders`, and `reqExecutions`, accumulates the callbacks into an epoch-local `BrokerState`, compares the resulting snapshot with the `JsonTradingStateStore` snapshot, and drives `TradingGate` to `READY` or `MANUAL_INTERVENTION`.

### 2. Injected Dependencies

Public constructor: `public ReconciliationManager(BrokerState brokerState, JsonTradingStateStore stateStore, TradingGate tradingGate, IntSupplier requestIdSupplier, Set<String> managedSymbols)` — delegates with `DEFAULT_TIMEOUT`.

Package-private constructor: `ReconciliationManager(BrokerState brokerState, JsonTradingStateStore stateStore, TradingGate tradingGate, IntSupplier requestIdSupplier, Set<String> managedSymbols, Duration timeout)`

| Parameter | Exact type |
| --- | --- |
| `brokerState` | `mwd.trading.reconciliation.BrokerState` |
| `stateStore` | `mwd.trading.persistence.JsonTradingStateStore` |
| `tradingGate` | `mwd.trading.lifecycle.TradingGate` |
| `requestIdSupplier` | `java.util.function.IntSupplier` |
| `managedSymbols` | `java.util.Set<java.lang.String>` (stored as `Set.copyOf(...)`) |
| `timeout` | `java.time.Duration` |

Constructed inside the constructor: `scheduler = Executors.newSingleThreadScheduledExecutor(...)` (thread name `IBKR-Reconciliation-Timeout`, daemon).

Injected after construction: `attachClient(EClientSocket)` sets the `EClientSocket client` field.

Static fields: `private static final Logger logger`, `private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20)`.

### 3. Method Signatures

Nested types:
- `public record Result(boolean matched, String reason, List<String> differences, long completedAtEpochMillis)`
- `private static final class CollectionEpoch`

```java
public ReconciliationManager(BrokerState brokerState, JsonTradingStateStore stateStore, TradingGate tradingGate, IntSupplier requestIdSupplier, Set<String> managedSymbols)
ReconciliationManager(BrokerState brokerState, JsonTradingStateStore stateStore, TradingGate tradingGate, IntSupplier requestIdSupplier, Set<String> managedSymbols, Duration timeout)

public synchronized void attachClient(EClientSocket client)
public synchronized Result getLastResult()
public synchronized boolean isReconciling()
public synchronized void begin(String reason)
public synchronized void onDisconnected(String reason)
public synchronized void onPosition(String account, Contract contract, Decimal position, double averageCost)
public synchronized void onLivePortfolioPosition(String account, Contract contract, Decimal position, double averageCost)
public synchronized void onPositionEnd()
public synchronized void onOpenOrder(int orderId, Contract contract, Order order, OrderState orderState)
public synchronized void onOpenOrderEnd()
public synchronized void onCompletedOrder(Contract contract, Order order, OrderState orderState)
public synchronized void onCompletedOrdersEnd()
public synchronized void onExecution(Contract contract, Execution execution)
public synchronized void onExecutionEnd(int requestId)
public synchronized void onOrderStatus(int orderId, String status, Decimal filled, Decimal remaining, long permanentId, int parentOrderId, int clientId)

private BrokerState targetState()
private void completeIfReady(CollectionEpoch epoch)
List<String> compare(BrokerState.Snapshot brokerSnapshot, JsonTradingStateStore.StateFile localState)
private static void compareLegIdentity(JsonTradingStateStore.TradeRecord trade, JsonTradingStateStore.LegRecord localLeg, BrokerState.OrderRecord brokerOrder, List<String> differences)
private static String oppositeAction(String action)
private synchronized void failEpoch(long epochNumber, String reason)
private void failWithoutSnapshot(String reason)
private static String tradeIdFromOrderReference(String orderReference)
private static boolean isTerminalOrderStatus(String status)
private static boolean isZero(String quantity)

@Override public void close()
```

`CollectionEpoch` members: `private CollectionEpoch(long number, int executionRequestId)`, `private boolean complete()`, plus fields `number`, `executionRequestId`, `collected` (`BrokerState`), `positionsComplete`, `openOrdersComplete`, `completedOrdersComplete`, `executionsComplete`.

### 4. Global State Interactions

**Concurrent collections**

None held directly. `scheduler` is a `ScheduledExecutorService`; `lastResult` is `volatile Result`. Mutual exclusion for `activeEpoch`, `nextEpoch`, and `client` comes from the `synchronized` methods.

`compare(...)` builds local `HashMap` and `ArrayList` instances only.

**Centralized state objects**

| Method | Interaction |
| --- | --- |
| `begin(String)` | Reads `client.isConnected()`; calls `requestIdSupplier.getAsInt()` (in `Main` this is `Blackboard::getNextRequestId`, which mutates the `AtomicInteger` in `IdManager`); mutates `tradingGate.transitionTo(EngineMode.RECONCILING, ...)`; schedules a timeout on `scheduler` |
| `onDisconnected(String)` | Clears `activeEpoch`; mutates `tradingGate.transitionTo(EngineMode.DEGRADED, ...)` |
| `onPosition`, `onOpenOrder`, `onCompletedOrder`, `onExecution`, `onOrderStatus` | Mutate the `BrokerState` returned by `targetState()` — the epoch-local one while an epoch is active, otherwise the injected `brokerState` |
| `onLivePortfolioPosition(...)` | Always mutates the injected `brokerState` directly |
| `onPositionEnd`, `onOpenOrderEnd`, `onCompletedOrdersEnd`, `onExecutionEnd(int)` | Mutate the active `CollectionEpoch` completion flags and call `completeIfReady` |
| `completeIfReady(CollectionEpoch)` | Reads `stateStore.snapshot()`; mutates `brokerState.replaceWith(Snapshot)`; mutates `lastResult`; mutates `tradingGate.transitionTo(EngineMode.READY, ...)` or `tradingGate.requireManualIntervention(String)` |
| `failEpoch(long, String)` / `failWithoutSnapshot(String)` | Mutate `lastResult` and `tradingGate.requireManualIntervention(String)` |
| `close()` | Calls `scheduler.shutdownNow()` |

No `Blackboard` reference is held; the only path to `Blackboard` is the injected `IntSupplier`.

---

## `MarginPacer`

`public class MarginPacer implements Runnable`

### 1. Class/Interface Responsibilities

Runs a loop that, while the socket is connected and the trading gate allows new entries, iterates every `Stock` on the `Blackboard` and submits a BUY and a SELL what-if `Order` for 100 shares with fixed sleeps between them.

### 2. Injected Dependencies

Constructor: `public MarginPacer(Blackboard blackboard, EClientSocket socket, TradingGate tradingGate)`

| Parameter | Exact type |
| --- | --- |
| `blackboard` | `mwd.trading.state.Blackboard` |
| `socket` | `com.ib.client.EClientSocket` (stored in the field `client`) |
| `tradingGate` | `mwd.trading.lifecycle.TradingGate` |

All three are `Objects.requireNonNull`-checked. Instance field: `private final int wait = 250`.

### 3. Method Signatures

```java
public MarginPacer(Blackboard blackboard, EClientSocket socket, TradingGate tradingGate)

@Override public void run()
private void requestWhatIf(Stock stock, String action)
```

### 4. Global State Interactions

**Concurrent collections**

Holds none. `run()` iterates the `ConcurrentHashMap<String, Stock>` inside `Blackboard` through `blackboard.forEachStock(Consumer<Stock>)`.

**Centralized state objects (`Blackboard`)**

| Method | Interaction |
| --- | --- |
| `run()` | Reads `client.isConnected()` and `tradingGate.allowsNewEntries()`; reads the `Blackboard` stock collection via `forEachStock(...)` |
| `requestWhatIf(Stock, String)` | Reads `stock.getTicker()`; mutates `blackboard.getNextOrderId()`; calls `client.placeOrder(int, Contract, Order)` with `whatIf(true)` |

The what-if responses are handled by `OrderLifecycleHandler.processWhatIf(...)`, which writes the margin rates back onto `Stock`.
