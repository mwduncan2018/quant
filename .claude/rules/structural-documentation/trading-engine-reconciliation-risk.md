---
paths:
  - "trading-engine/**/*"
---

# Packages `mwd.trading.reconciliation` and `mwd.trading.risk`

Sources:
- `trading-engine/trading-engine/src/main/java/mwd/trading/reconciliation/BrokerState.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/reconciliation/ReconciliationManager.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/risk/ConcentrationLimits.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/risk/MarginMethodology.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/risk/Sector.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/risk/UniverseReference.java`

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

## `Sector`

`public enum Sector`

### 1. Class/Interface Responsibilities

The eleven GICS sectors, used to roll per-ticker exposure into a sector total that
`ConcentrationLimits` measures against. All eleven are declared even though the
current universe uses eight, because the universe is configuration and a symbol
from any sector can be added without touching code.

### 2. Injected Dependencies

None. The enum declares no constructor parameters.

### 3. Method Signatures

```java
public static Sector parse(String raw)
```

Constants: `COMMUNICATION_SERVICES`, `CONSUMER_DISCRETIONARY`, `CONSUMER_STAPLES`,
`ENERGY`, `FINANCIALS`, `HEALTH_CARE`, `INDUSTRIALS`, `INFORMATION_TECHNOLOGY`,
`MATERIALS`, `REAL_ESTATE`, `UTILITIES`.

`parse` upper-cases and replaces every run of non-letters with a single
underscore, so `"Health Care"`, `HEALTH_CARE`, and `"health care"` are one sector.
Unknown text throws `IllegalArgumentException` rather than returning null, so a
typo fails the reference file instead of silently dropping the symbol out of its
sector total.

### 4. Global State Interactions

None.

---

## `MarginMethodology`

`public enum MarginMethodology`

### 1. Class/Interface Responsibilities

Which of IBKR's two margin regimes the account is on, and therefore which pair of
rates in the reference table applies. Under `REG_T` the initial requirement for
long equity is the Federal Reserve's flat 50%, identical for every marginable
symbol, and per-symbol variation appears only where IBKR imposes a house
requirement above it. Under `PORTFOLIO` the requirement is risk-based and
genuinely differs per symbol.

### 2. Injected Dependencies

None.

### 3. Method Signatures

```java
public static MarginMethodology parse(String raw)
```

Constants: `REG_T`, `PORTFOLIO`.

### 4. Global State Interactions

None.

---

## `UniverseReference`

`public final class UniverseReference`

### 1. Class/Interface Responsibilities

Per-ticker reference data that does not come from the market: the GICS sector and
the margin rates for both regimes. These used to be measured by `MarginPacer`,
which submitted a what-if order per symbol per direction every five minutes -
roughly twelve requests a minute against IBKR's stated ceiling of one, some four
and a half thousand per session, none of them cancelled. The rates are read off
IBKR's public margin calculator instead and kept in a file beside the engine, so
refreshing them is an edit rather than a rebuild.

### 2. Injected Dependencies

Package-private constructor: `UniverseReference(Map<String, TickerReference> byTicker, LocalDate retrieved, MarginMethodology methodology, double defaultLongRate, double defaultShortRate)`

| Parameter | Exact type |
| --- | --- |
| `byTicker` | `java.util.Map<java.lang.String, mwd.trading.risk.UniverseReference.TickerReference>` (stored as `Map.copyOf(...)`) |
| `retrieved` | `java.time.LocalDate` (nullable) |
| `methodology` | `mwd.trading.risk.MarginMethodology` (null-checked) |
| `defaultLongRate`, `defaultShortRate` | `double`, each greater than 0 and at most 1 |

Instances are built by the two static factories below rather than directly.

### 3. Method Signatures

Nested type: `public record TickerReference(String ticker, Sector sector, double regTLong, double regTShort, double portfolioLong, double portfolioShort)` with `public double rate(MarginMethodology methodology, boolean isLong)`.

```java
public static UniverseReference load(Path path, MarginMethodology methodology, double defaultLongRate, double defaultShortRate) throws IOException
public static UniverseReference parse(List<String> lines, MarginMethodology methodology, double defaultLongRate, double defaultShortRate)

public double marginRate(String ticker, boolean isLong)
public double marginRequirement(String ticker, boolean isLong, double notionalValue)
public Optional<Sector> sector(String ticker)
public MarginMethodology methodology()
public Optional<LocalDate> retrieved()
public Optional<Long> ageInDays(LocalDate today)
public int coveredTickerCount()
public Set<String> missingFrom(Set<String> tradedTickers)
public Set<String> usingDefaultRate(Set<String> tradedTickers)
public List<String> describeCoverage(Set<String> tradedTickers, LocalDate today)

private TickerReference lookup(String ticker)
private static boolean isUsableRate(double rate)
private static double optionalRate(String field)
private static LocalDate parseRetrieved(String commentLine)
private static double requirePositiveRate(double rate, String name)
```

File format, one row per ticker: `ticker,sector,regt_long,regt_short,pm_long,pm_short`.
Blank and `#` lines are skipped, except that a `# retrieved: yyyy-MM-dd` comment
sets the collection date. A header line beginning `ticker,` is skipped.

### 4. Global State Interactions

**Concurrent collections**

None. `byTicker` is an immutable `Map.copyOf`; every field is `final` and set at
construction.

| Method | Interaction |
| --- | --- |
| `load(Path, ...)` | Reads the file; a **missing** file is not an error and yields an empty table on defaults, because refusing to start over an unfilled reference table is worse than starting and saying so |
| `parse(List<String>, ...)` | Throws `IllegalArgumentException` on a malformed sector, an unparseable or out-of-range rate, a duplicated ticker, or a row with fewer than six fields |
| `marginRate(String, boolean)` | Returns the configured rate for the selected regime, or the conservative default when the row or the rate is absent |
| `describeCoverage(Set<String>, LocalDate)` | Names every traded symbol with no row and every symbol falling back to the default, plus the table's age |

A blank rate falls back to a default deliberately set **higher** than a typical
requirement, so a symbol added to a universe and forgotten here under-sizes rather
than over-leverages.

---

## `ConcentrationLimits`

`public final class ConcentrationLimits`

### 1. Class/Interface Responsibilities

Caps how much of the account may ride on one symbol and on one sector. This is
account-level risk policy rather than strategy logic and it has to be shared: two
strategies both entering technology names must see the same sector total, or
neither limit means anything. Each strategy keeps its own risk budget and sizes
itself; this only ever reduces the result.

Both limits apply whatever margin regime the account is on. Concentration is how
much is at stake on one name; the regime only decides how much the broker lends
against it.

### 2. Injected Dependencies

Constructor: `public ConcentrationLimits(Blackboard blackboard, UniverseReference universeReference, double maxTickerExposurePercent, double maxSectorExposurePercent, double minPositionNotional)`

| Parameter | Exact type |
| --- | --- |
| `blackboard` | `mwd.trading.state.Blackboard` — the full type, because exposure requires iterating every symbol |
| `universeReference` | `mwd.trading.risk.UniverseReference` |
| `maxTickerExposurePercent`, `maxSectorExposurePercent` | `double`, a percent of net liquidation; each must be greater than 0 and at most 100 |
| `minPositionNotional` | `double`, zero or more |

### 3. Method Signatures

Nested type: `public record Exposure(double netLiquidation, Map<String, Double> byTicker, Map<Sector, Double> bySector, double unsectored)` with `forTicker(String)` and `forSector(Sector)`.

```java
public ConcentrationLimits(Blackboard blackboard, UniverseReference universeReference, double maxTickerExposurePercent, double maxSectorExposurePercent, double minPositionNotional)

public Decimal allowedQuantity(String ticker, double entryPrice, Decimal requested)
public Exposure currentExposure()
public double getMinPositionNotional()

private static double filledNotional(Stock stock)
private static double workingNotional(Stock stock)
private static double requireFraction(double percent, String name)
```

### 4. Global State Interactions

**Concurrent collections**

Holds none. `currentExposure()` iterates the `ConcurrentHashMap<String, Stock>`
inside `Blackboard` through `forEachStock(Consumer<Stock>)` and builds fresh local
maps each call.

| Method | Interaction |
| --- | --- |
| `allowedQuantity(String, double, Decimal)` | Calls `currentExposure()`; returns `Decimal.ZERO` when net liquidation is not positive, when the remaining headroom is below `minPositionNotional`, or when the shares that headroom buys are worth less than it; otherwise the smaller of the requested quantity and what the tighter cap allows |
| `currentExposure()` | Reads `blackboard.getAccount().getNetLiquidation()`; per symbol reads `Stock.getPositionSize()`, `getLastPrice()`, `getActiveBracket()` and that bracket's `getStatus()`, `getTotalQuantity()`, `getFilledQuantity()`, `getEntryPrice()`; reads `universeReference.sector(...)` to bucket each figure |
| `filledNotional(Stock)` | `abs(positionSize) × lastPrice`, or zero when either is absent |
| `workingNotional(Stock)` | `(totalQuantity − filledQuantity) × entryPrice` for a non-terminal bracket; zero for `FILLED`, `CANCELLED`, and `REJECTED` |

Exposure counts unfilled entries as well as filled positions. The engine-wide
entry lock is released on acknowledgement rather than on fill, so a second entry
can be admitted while the first is still working at the exchange; reading only
`Stock.positionSize` would show nothing for it and let two same-sector entries
each pass the check and both fill. A partially filled bracket contributes its
filled half through the position size and its remainder through the bracket, which
composes rather than double-counting.

A symbol with no row in the reference table has no sector, so only the per-ticker
cap can be enforced for it and its exposure is invisible to every other symbol's
sector total. That is a gap in the file, named at startup, not something this
class papers over.
