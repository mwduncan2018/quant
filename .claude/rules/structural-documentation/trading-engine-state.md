---
paths:
  - "trading-engine/**/*"
---

# Package `mwd.trading.state`

Source: `trading-engine/trading-engine/src/main/java/mwd/trading/state/Blackboard.java`

---

## `Blackboard`

`public class Blackboard`

### 1. Class/Interface Responsibilities

Holds the engine-wide shared state: the `Account`, the `ConcurrentHashMap` of `Stock` objects keyed by ticker, the active-position owner map, the global pending-entry owner reference, the last-entry-submission timestamp, the halt/update/open-order-end flags, and delegating accessors for `IdManager`, `TimeManager`, and `OrderRegistry`.

### 2. Injected Dependencies

Constructor: `public Blackboard(IdManager idManager, TimeManager timeManager, OrderRegistry orderRegistry, Config config)`

| Parameter | Exact type |
| --- | --- |
| `idManager` | `mwd.trading.broker.ibkr.IdManager` |
| `timeManager` | `mwd.trading.broker.ibkr.TimeManager` |
| `orderRegistry` | `mwd.trading.execution.OrderRegistry` |
| `config` | `mwd.trading.config.Config` |

Non-injected field constructed inside the constructor: `private final Account account = new Account()` (`mwd.trading.domain.Account`).

### 3. Method Signatures

Nested type: `public record EntryOwner(String strategyName, String ticker)` with a compact canonical constructor.

```java
public Blackboard(IdManager idManager, TimeManager timeManager, OrderRegistry orderRegistry, Config config)

public double getNetCallPremium()
public void setNetCallPremium(double netCallPremium)
public double getNetPutPremium()
public void setNetPutPremium(double netPutPremium)

public void recordEntrySubmitted(long atMillis)
public boolean isAccountCurrentForNewEntry()

public boolean tryAcquireGlobalPending(String strategyName, String ticker)
public boolean releaseGlobalPending(String strategyName, String ticker)
public boolean isGlobalPendingOwnedBy(String strategyName, String ticker)
public EntryOwner getGlobalPendingOwner()

public synchronized boolean tryReservePosition(String ticker, String strategyName)
public synchronized boolean releasePosition(String ticker, String strategyName)
public synchronized boolean isPositionOwnedBy(String ticker, String strategyName)
public synchronized String getPositionOwner(String ticker)
public synchronized int getActivePositionCount()
public synchronized Map<String, String> getPositionOwnersSnapshot()

public Account getAccount()
public TimeManager getTimeManager()
public long getMarketTime()
public OrderRegistry getOrderRegistry()

public boolean getSystemHalted()
public void setSystemHalted(boolean bool)
public boolean getOpenOrderEnd()
public void setOpenOrderEnd(boolean bool)
public boolean getSystemUpdateRequired()
public void setSystemUpdateRequired(boolean systemUpdateRequired)

public void initializeOrderId(int orderId)
public void initializeRequestId(int requestId)
public int getNextOrderId()
public int getNextRequestId()

public Stock getStock(String ticker)
public Collection<Stock> getAllStocksUnmodifiable()
public List<Stock> getAllStocksSnapshot()
public void forEachStock(Consumer<Stock> action)
public Stream<Stock> streamStocks()

private static String requireIdentifier(String value, String name)
```

### 4. Global State Interactions

**Concurrent collections held by this class**

| Field | Declared type |
| --- | --- |
| `stocks` | `Map<String, Stock>` = `new ConcurrentHashMap<>()` |
| `globalPendingOwner` | `AtomicReference<EntryOwner>` |
| `lastEntrySubmittedAtMillis` | `java.util.concurrent.atomic.AtomicLong` |
| `activePositionOwners` | `Map<String, String>` = `new HashMap<>()` (non-concurrent; every accessor is `synchronized`) |

**Methods that read or mutate them**

| Method | Interaction |
| --- | --- |
| `getStock(String)` | Mutates `stocks` via `computeIfAbsent` |
| `getAllStocksUnmodifiable()` | Reads `stocks` |
| `getAllStocksSnapshot()` | Reads `stocks` |
| `forEachStock(Consumer<Stock>)` | Reads `stocks` |
| `streamStocks()` | Reads `stocks` |
| `tryAcquireGlobalPending(String, String)` | Mutates `globalPendingOwner` (`compareAndSet`) |
| `releaseGlobalPending(String, String)` | Reads and mutates `globalPendingOwner` (`get`, `compareAndSet`) |
| `isGlobalPendingOwnedBy(String, String)` | Reads `globalPendingOwner` |
| `getGlobalPendingOwner()` | Reads `globalPendingOwner` |
| `recordEntrySubmitted(long)` | Mutates `lastEntrySubmittedAtMillis` (`updateAndGet`) |
| `isAccountCurrentForNewEntry()` | Reads `lastEntrySubmittedAtMillis`; reads `account.getLastRefreshedAtMillis()` |
| `tryReservePosition(String, String)` | Mutates `activePositionOwners`; reads `config.getMaxActivePositions()` |
| `releasePosition(String, String)` | Mutates `activePositionOwners` |
| `isPositionOwnedBy(String, String)` | Reads `activePositionOwners` |
| `getPositionOwner(String)` | Reads `activePositionOwners` |
| `getActivePositionCount()` | Reads `activePositionOwners` |
| `getPositionOwnersSnapshot()` | Reads `activePositionOwners` |

**Volatile scalar state**

`netCallPremium`, `netPutPremium`, `systemHalted`, `systemUpdateRequired`, `openOrderEnd` are `volatile` fields read and written by their matching getter/setter pairs listed above.

**Delegation to other centralized objects**

| Method | Delegates to |
| --- | --- |
| `initializeOrderId(int)`, `getNextOrderId()` | `IdManager` (`AtomicInteger` backed) |
| `initializeRequestId(int)`, `getNextRequestId()` | `IdManager` (`AtomicInteger` backed) |
| `getMarketTime()`, `getTimeManager()` | `TimeManager` |
| `getOrderRegistry()` | `OrderRegistry` (four `ConcurrentHashMap` instances) |
| `getAccount()` | `Account` |
