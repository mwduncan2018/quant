---
paths:
  - "trading-engine/**/*"
---

# Package `mwd.trading.state`

Sources:
- `trading-engine/trading-engine/src/main/java/mwd/trading/state/Blackboard.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/state/PositionLedger.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/state/StockLookup.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/state/StrategyBlackboard.java`

---

## `Blackboard`

`public class Blackboard implements StrategyBlackboard`

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

---

## `StockLookup`

`public interface StockLookup`

### 1. Class/Interface Responsibilities

Resolves a ticker to the `Stock` that holds its values. This is the whole
dependency on shared state of every indicator tracker and of `MinuteBarHandler`,
`PriceTickHandler`, and `SizeTickHandler`.

### 2. Injected Dependencies

None. Interfaces declare no constructor.

### 3. Method Signatures

```java
Stock getStock(String ticker)
```

### 4. Global State Interactions

None declared. `Blackboard.getStock(String)` satisfies it and inserts absent
tickers via `computeIfAbsent`.

---

## `PositionLedger`

`public interface PositionLedger`

### 1. Class/Interface Responsibilities

The two claims that stand between a strategy and a live order: the engine-wide
entry lock, so only one submission is outstanding at IBKR at a time, and the
per-ticker reservation, which is also what enforces `MAX_ACTIVE_POSITIONS`.
`EntryAdmission` is implemented against this and nothing else.

### 2. Injected Dependencies

None. Interfaces declare no constructor.

### 3. Method Signatures

```java
boolean tryAcquireGlobalPending(String strategyName, String ticker)
boolean releaseGlobalPending(String strategyName, String ticker)
boolean tryReservePosition(String ticker, String strategyName)
boolean releasePosition(String ticker, String strategyName)
String getPositionOwner(String ticker)
boolean isPositionOwnedBy(String ticker, String strategyName)
```

### 4. Global State Interactions

None declared. In `Blackboard`, the first two are backed by the
`AtomicReference<EntryOwner> globalPendingOwner` compare-and-set and the last four
by the `activePositionOwners` `HashMap` under the instance monitor.

---

## `StrategyBlackboard`

`public interface StrategyBlackboard extends PositionLedger, StockLookup`

### 1. Class/Interface Responsibilities

Everything a strategy may reach on the blackboard, and nothing else: the two roles
it composes plus the account gate and the halt switch. A strategy holding this
type cannot allocate an IBKR order id, reach `OrderRegistry`, iterate every symbol,
or clear `openOrderEnd`.

### 2. Injected Dependencies

None. Interfaces declare no constructor.

### 3. Method Signatures

Inherited: the six `PositionLedger` methods and `StockLookup.getStock(String)`.

```java
boolean isAccountCurrentForNewEntry()
void recordEntrySubmitted(long atMillis)
Account getAccount()
void setSystemHalted(boolean halted)
```

### 4. Global State Interactions

None declared. `Blackboard` satisfies every method as it already stood, so no call
site changed when `AbstractStrategy` narrowed to this type.
