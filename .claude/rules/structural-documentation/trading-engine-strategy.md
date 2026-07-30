---
paths:
  - "trading-engine/**/*"
---

# Package `mwd.trading.strategy`

Sources:
- `trading-engine/trading-engine/src/main/java/mwd/trading/strategy/AbstractStrategy.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/strategy/EntryAdmission.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/strategy/TwoSigmaDownsideMeanReversionStrategy.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/strategy/OneSigmaDownsideMeanReversionStrategy.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/strategy/OneSigmaUpsideMeanReversionStrategy.java`

---

## `AbstractStrategy`

`public abstract class AbstractStrategy implements Runnable`

### 1. Class/Interface Responsibilities

Base class that owns the poll loop over a normalized ticker universe, the dispatch on the derived `Stock.PositionState`, the edge-triggered acknowledgement of each broker status, the pending-acknowledgement and unready-reason bookkeeping maps, the per-symbol `MarketSnapshot` handed to every decision, and the abstract hooks each concrete strategy implements. The three-step entry claim itself lives in `EntryAdmission`.

### 2. Injected Dependencies

Protected constructor: `protected AbstractStrategy(StrategyBlackboard, BracketOrderGateway, TickStreamController, Config, TradingGate, MarketDataFreshness, UniverseReference, ConcentrationLimits, Set<String>)` — delegates with `Clock.systemUTC()`.

Protected constructor: `protected AbstractStrategy(StrategyBlackboard, BracketOrderGateway, TickStreamController, Config, TradingGate, MarketDataFreshness, UniverseReference, ConcentrationLimits, Set<String>, Clock)`

| Parameter | Exact type |
| --- | --- |
| `blackboard` | `mwd.trading.state.StrategyBlackboard` — the narrow view: `PositionLedger` and `StockLookup` plus `isAccountCurrentForNewEntry()`, `recordEntrySubmitted(long)`, `getAccount()`, `setSystemHalted(boolean)`. `Blackboard` implements it |
| `bracketOrderGateway` | `mwd.trading.execution.BracketOrderGateway` |
| `tickStreamController` | `mwd.trading.marketdata.TickStreamController` |
| `config` | `mwd.trading.config.Config` |
| `tradingGate` | `mwd.trading.lifecycle.TradingGate` |
| `marketDataFreshness` | `mwd.trading.marketdata.MarketDataFreshness` |
| `universeReference` | `mwd.trading.risk.UniverseReference` — per-ticker sectors and margin rates, configuration rather than measurement |
| `concentrationLimits` | `mwd.trading.risk.ConcentrationLimits` — account-level caps that reduce, but never raise, a strategy's own sizing |
| `universe` | `java.util.Set<java.lang.String>` (trimmed, upper-cased, stored as `Set.copyOf(...)`; empty throws `IllegalArgumentException`) |
| `clock` | `java.time.Clock` |

All are `Objects.requireNonNull`-checked. `logger` is `LogManager.getLogger(getClass())`. The
constructor also builds `private final EntryAdmission entryAdmission = new EntryAdmission(this.blackboard)`.

### 3. Method Signatures

Nested type: `private record PendingEntry(long submittedAtMillis)`

```java
protected AbstractStrategy(StrategyBlackboard blackboard, BracketOrderGateway bracketOrderGateway, TickStreamController tickStreamController, Config config, TradingGate tradingGate, MarketDataFreshness marketDataFreshness, UniverseReference universeReference, ConcentrationLimits concentrationLimits, Set<String> universe)
protected AbstractStrategy(StrategyBlackboard blackboard, BracketOrderGateway bracketOrderGateway, TickStreamController tickStreamController, Config config, TradingGate tradingGate, MarketDataFreshness marketDataFreshness, UniverseReference universeReference, ConcentrationLimits concentrationLimits, Set<String> universe, Clock clock)

@Override public final void run()
final void runOneCycle()
protected final void executeLifecycle(Stock stock)

private void acknowledgeStatusChange(Stock stock, String strategyId)
private void evaluateNewEntry(Stock stock, String strategyId)
private void handlePendingEntry(Stock stock)
private void handleFlatWithLocalOwnership(Stock stock)
private void cleanupOwnedLifecycle(Stock stock, String strategyId, BracketOrder bracketOrder)
private void rollbackEntryReservation(Stock stock, EntryAdmission.Reservation reservation)
private void processSymbolSafely(Stock stock)

protected final void updateExits(Stock stock, BracketOrder bracketOrder, BracketOrder.ExitSlice exitSlice, double takeProfitPrice, double stopLossPrice, long timeExitValue)
public final Set<String> getUniverse()

protected final MarketSnapshot snapshot(Stock stock)
protected final MarketSnapshot snapshot(String ticker)

private boolean entryInputsReady(Stock stock)
private boolean automatedOrderChangesAllowed(Stock stock)
private boolean acknowledgementTimedOut(long submittedAtMillis)
private void escalate(Stock stock, String message)
private String strategyId()
private TradeDirection tradeDirection()
static List<BracketOrderExecutor.SliceIntent> trimToTotal(List<BracketOrderExecutor.SliceIntent> sliceIntents, Decimal requested, Decimal allowed)
private static Decimal totalQuantity(List<BracketOrderExecutor.SliceIntent> sliceIntents)
private static boolean isZero(Decimal quantity)
private static boolean isConfirmedTerminal(BracketOrder.Status status)

protected void onPositionClosed(Stock stock)

protected abstract Set<MarketDataInput> requiredEntryInputs()
protected abstract Set<MarketDataInput> requiredManagementInputs()
protected abstract boolean isEntryConditionMet(MarketSnapshot market)
protected abstract double calculateEntryPrice(MarketSnapshot market)
protected abstract List<BracketOrderExecutor.SliceIntent> calculateSliceIntents(MarketSnapshot market, double entryPrice)
protected abstract void evaluateTickStreamNeed(MarketSnapshot market, double entryPrice)
protected abstract void manageOpenPosition(Stock stock, MarketSnapshot market)
protected abstract String getStrategyName()
protected abstract TradeDirection getTradeDirection()
```

Every hook that reads market data takes a `MarketSnapshot`; `Stock` is passed only where
identity or lifecycle is needed. `manageOpenPosition` takes both, because it reads the
active bracket and calls `updateExits`.

Protected fields available to subclasses: `logger` (`Logger`), `blackboard` (`StrategyBlackboard`), `universeReference` (`UniverseReference`), `concentrationLimits` (`ConcentrationLimits`), `bracketOrderGateway` (`BracketOrderGateway`), `tickStreamController` (`TickStreamController`), `config` (`Config`), `tradingGate` (`TradingGate`), `marketDataFreshness` (`MarketDataFreshness`).

### 4. Global State Interactions

**Concurrent collections**

| Field | Declared type |
| --- | --- |
| `pendingEntries` | `ConcurrentMap<String, PendingEntry>` = `new ConcurrentHashMap<>()` |
| `escalatedPendingEntries` | `Set<String>` = `ConcurrentHashMap.newKeySet()` |
| `lastUnreadyReason` | `ConcurrentMap<String, String>` = `new ConcurrentHashMap<>()` |
| `acknowledgedStatus` | `ConcurrentMap<String, BracketOrder.Status>` = `new ConcurrentHashMap<>()` — the last broker status this strategy reacted to, per ticker |
| `universe` | `final Set<String>` (immutable) |

| Method | Interaction |
| --- | --- |
| `evaluateNewEntry(Stock, String)` | Mutates `pendingEntries` (`put`) |
| `acknowledgeStatusChange(Stock, String)` | Reads and mutates `acknowledgedStatus` (`get`, `put`, `remove`); on `POSITION_OPEN` mutates `pendingEntries` (`remove`) and `escalatedPendingEntries` (`remove`) |
| `handlePendingEntry(Stock)` | Reads `pendingEntries` (`get`) only |
| `cleanupOwnedLifecycle(Stock, String, BracketOrder)` | Mutates `pendingEntries`, `escalatedPendingEntries`, and `acknowledgedStatus` (`remove`) |
| `rollbackEntryReservation(Stock, EntryAdmission.Reservation)` | Mutates `pendingEntries`, `escalatedPendingEntries`, and `acknowledgedStatus` (`remove`) |
| `escalate(Stock, String)` | Mutates `escalatedPendingEntries` (`add`) |
| `entryInputsReady(Stock)` | Mutates `lastUnreadyReason` (`remove`, `put`) |
| `runOneCycle()` | Reads `universe` |
| `getUniverse()` | Reads `universe` |

**Centralized state objects (`StrategyBlackboard`)**

| Method | Interaction |
| --- | --- |
| `runOneCycle()` | Reads `blackboard.getStock(ticker)` for each universe member |
| `executeLifecycle(Stock)` | Reads `blackboard.getPositionOwner(String)`, then dispatches on `stock.positionState(owner != null)` |
| `acknowledgeStatusChange(Stock, String)` | Reads `stock.getActiveBracket()` and its `getStatus()` / `getFilledQuantity()`; on `WORKING_PARENT`, `PARTIAL_PARENT`, `POSITION_OPEN`, and terminal-with-fill mutates `blackboard.releaseGlobalPending(...)` |
| `evaluateNewEntry(Stock, String)` | Reads `tradingGate.allowsNewEntries()`, `stock.isTradeable()`, `blackboard.isAccountCurrentForNewEntry()`; takes two `MarketSnapshot` views via `snapshot(Stock)`; delegates the three-step claim to `entryAdmission.tryAdmit(...)`; calls `concentrationLimits.allowedQuantity(...)` and, when that is smaller, `trimToTotal(...)`; mutates `blackboard.recordEntrySubmitted(long)`; calls `bracketOrderGateway.placeTripleThreat(...)`; resolves the reservation with `keep()` or `rollbackEntryReservation(...)`, and the try-with-resources `close()` releases any path that did neither |
| `trimToTotal(List, Decimal, Decimal)` | Pure function over the slice intents. Scales each proportionally and gives the rounding loss to the first, because `BracketOrderExecutor.validateEntryIntent` requires the parts to sum exactly to the parent. Returns an empty list when any slice would round to zero: the strategy chose how many exits the position has, and shipping fewer would hand it a shape it never asked for |
| `handlePendingEntry(Stock)` | Reads `stock.getActiveBracket()` and the clock only; no state writes beyond `escalate` |
| `handleFlatWithLocalOwnership(Stock)` | Reads `stock.getActiveBracket()`; calls `cleanupOwnedLifecycle` or `escalate` |
| `cleanupOwnedLifecycle(...)` | Calls `onPositionClosed(Stock)`; mutates `blackboard.releaseGlobalPending(...)`, `blackboard.releasePosition(...)`, `stock.setActiveBracket(null)`; calls `tickStreamController.isStreamActive/cancelStream` |
| `rollbackEntryReservation(...)` | Calls `reservation.release()`, which gives back both holdings; calls `tickStreamController.cancelStream` |
| `processSymbolSafely(Stock)` | On `RuntimeException` mutates `stock.setTradeable(false)`; reads `blackboard.getPositionOwner(String)` and `stock.getActiveBracket()` |
| `snapshot(Stock)` / `snapshot(String)` | Reads every market-data field of the `Stock` once into an immutable `MarketSnapshot`; `snapshot(String)` resolves the symbol through `blackboard.getStock(...)` first |
| `updateExits(...)` | Reads `stock.positionState(true)` and `blackboard.isPositionOwnedBy(...)`; calls `bracketOrderGateway.updateTripleThreatExits(...)` |
| `escalate(Stock, String)` | Mutates `blackboard.setSystemHalted(true)` and `tradingGate.requireManualIntervention(String)` |
| `entryInputsReady(Stock)` | Reads `marketDataFreshness.describeUnready(...)` |
| `automatedOrderChangesAllowed(Stock)` | Reads `tradingGate.allowsAutomatedOrderChanges()` and `marketDataFreshness.describeUnready(...)` |

---

## `EntryAdmission`

`public final class EntryAdmission`

### 1. Class/Interface Responsibilities

Owns the three-step gate every new entry passes through, and is the only place
that sequence is written: acquire the engine-wide pending lock, reserve the
ticker, then confirm the symbol still derives `PENDING`. Any step can lose a race
with another strategy thread or with the IBKR reader thread, and whatever was
already taken is given back before returning `null`.

### 2. Injected Dependencies

Constructor: `public EntryAdmission(PositionLedger positions)`

| Parameter | Exact type |
| --- | --- |
| `positions` | `mwd.trading.state.PositionLedger` (null-checked) |

`Blackboard` implements `PositionLedger`; this class sees nothing else on it.

### 3. Method Signatures

Nested type: `public static final class Reservation implements AutoCloseable`

```java
public EntryAdmission(PositionLedger positions)
public Reservation tryAdmit(String strategyId, Stock stock)
```

`Reservation`:

```java
private Reservation(PositionLedger positions, String strategyId, Stock stock)
public void keep()
public void release()
@Override public void close()
```

### 4. Global State Interactions

**Concurrent collections**

None held. Every claim goes through `PositionLedger`, whose implementation guards
`globalPendingOwner` with an `AtomicReference` compare-and-set and
`activePositionOwners` with the `Blackboard` monitor.

| Method | Interaction |
| --- | --- |
| `tryAdmit(String, Stock)` | Mutates `positions.tryAcquireGlobalPending(...)`; on success mutates `positions.tryReservePosition(...)`; reads `stock.positionState(true)` and, when it is not `PENDING`, mutates `positions.releasePosition(...)` and `positions.releaseGlobalPending(...)` |
| `Reservation.keep()` | Sets `resolved = true`; hands both holdings to the pending-entry lifecycle |
| `Reservation.release()` | Idempotent, and a no-op after `keep()`; otherwise mutates `positions.releasePosition(...)` and `positions.releaseGlobalPending(...)` |
| `Reservation.close()` | Calls `release()`, so an unconsidered exit path frees the engine-wide lock rather than parking every strategy |

The third step is a guard, not a write: releasing the reservation is what returns
the derived state to `FLAT`, because there is no stored field to reset.

---

## `TwoSigmaDownsideMeanReversionStrategy`

`public class TwoSigmaDownsideMeanReversionStrategy extends AbstractStrategy`

### 1. Class/Interface Responsibilities

Concrete `AbstractStrategy` for the strategy ID `TWO_SIGMA_DOWNSIDE` and `TradeDirection.LONG` that reads `OptionsIndicatorStore`, `EarningsStore`, and `MarketCalendarStore` alongside `Stock` market data to implement the entry, sizing, two-slice intent, tick-stream, and open-position hooks.

### 2. Injected Dependencies

Public constructor: `public TwoSigmaDownsideMeanReversionStrategy(StrategyBlackboard, BracketOrderGateway, TickStreamController, Config, TradingGate, MarketDataFreshness, UniverseReference, ConcentrationLimits, OptionsIndicatorStore, EarningsStore, MarketCalendarStore)` — delegates with `Clock.systemUTC()`.

Package-private constructor adds a trailing `Clock clock`.

| Parameter | Exact type |
| --- | --- |
| `blackboard` | `mwd.trading.state.StrategyBlackboard` |
| `bracketOrderGateway` | `mwd.trading.execution.BracketOrderGateway` |
| `tickStreamController` | `mwd.trading.marketdata.TickStreamController` |
| `config` | `mwd.trading.config.Config` |
| `tradingGate` | `mwd.trading.lifecycle.TradingGate` |
| `marketDataFreshness` | `mwd.trading.marketdata.MarketDataFreshness` |
| `universeReference` | `mwd.trading.risk.UniverseReference` |
| `concentrationLimits` | `mwd.trading.risk.ConcentrationLimits` |
| `optionsIndicatorStore` | `mwd.trading.optionsproxy.OptionsIndicatorStore` (null-checked) |
| `earningsStore` | `mwd.trading.earnings.EarningsStore` (null-checked) |
| `marketCalendarStore` | `mwd.trading.calendar.MarketCalendarStore` (null-checked) |
| `clock` | `java.time.Clock` (stored as `newYorkClock = clock.withZone(NEW_YORK_ZONE)`) |

The universe passed to `super(...)` is `config.getStrategyUniverse(STRATEGY_ID)`.

Static fields: `public static final String STRATEGY_ID = "TWO_SIGMA_DOWNSIDE"`, `private static final ZoneId NEW_YORK_ZONE`, `private static final String MARKET_INDEX_TICKER = "SPY"`, `private static final Duration NO_ENTRY_BEFORE_CLOSE`, `private static final Duration EXIT_BEFORE_CLOSE`, `private static final Set<MarketDataInput> ENTRY_INPUTS`, `private static final Set<MarketDataInput> MANAGEMENT_INPUTS`.

### 3. Method Signatures

```java
public TwoSigmaDownsideMeanReversionStrategy(StrategyBlackboard blackboard, BracketOrderGateway bracketOrderGateway, TickStreamController tickStreamController, Config config, TradingGate tradingGate, MarketDataFreshness marketDataFreshness, UniverseReference universeReference, ConcentrationLimits concentrationLimits, OptionsIndicatorStore optionsIndicatorStore, EarningsStore earningsStore, MarketCalendarStore marketCalendarStore)
TwoSigmaDownsideMeanReversionStrategy(StrategyBlackboard blackboard, BracketOrderGateway bracketOrderGateway, TickStreamController tickStreamController, Config config, TradingGate tradingGate, MarketDataFreshness marketDataFreshness, UniverseReference universeReference, ConcentrationLimits concentrationLimits, OptionsIndicatorStore optionsIndicatorStore, EarningsStore earningsStore, MarketCalendarStore marketCalendarStore, Clock clock)

private LocalDate currentTradingDate()

@Override protected Set<MarketDataInput> requiredEntryInputs()
@Override protected Set<MarketDataInput> requiredManagementInputs()
@Override protected boolean isEntryConditionMet(MarketSnapshot market)
@Override protected double calculateEntryPrice(MarketSnapshot market)
@Override protected List<BracketOrderExecutor.SliceIntent> calculateSliceIntents(MarketSnapshot market, double entryPrice)
private Decimal calculateTotalQuantity(MarketSnapshot market, double entryPrice, double stopLossPrice)
private boolean isInEarningsBlackout(String ticker, LocalDate tradingDate)
protected long calculateTimeExit()
@Override protected void evaluateTickStreamNeed(MarketSnapshot market, double entryPrice)
@Override protected void manageOpenPosition(Stock stock, MarketSnapshot market)
@Override protected String getStrategyName()
@Override protected TradeDirection getTradeDirection()
```

### 4. Global State Interactions

**Concurrent collections**

None of its own; `ENTRY_INPUTS` and `MANAGEMENT_INPUTS` are immutable `Set.of(...)` constants. It inherits `pendingEntries`, `escalatedPendingEntries`, and `lastUnreadyReason` from `AbstractStrategy`.

**Centralized state objects**

| Method | Interaction |
| --- | --- |
| `isEntryConditionMet(MarketSnapshot)` | Reads `lastPrice()`, `previousClose()`, `dailyVWAP()`, `lastMinuteBar()`, `lastMinuteVolume()`, `averageLast15MinuteVolume()`; reads `marketCalendarStore.isWithinOfClose(...)` and `sessionClose(...)`; reads `optionsIndicatorStore.impliedMoveForNewEntry(...)` and `gammaFlipForNewEntry(...)`; reads `blackboard.getStock("SPY")` and `marketDataFreshness.isFresh("SPY", LAST_PRICE)`; calls `isInEarningsBlackout(...)` |
| `isInEarningsBlackout(String, LocalDate)` | Reads `earningsStore.earningsDate(...)`; reads `marketCalendarStore.nextSession(...)` and `previousSessionApproximate(...)` |
| `calculateEntryPrice(MarketSnapshot)` | Reads `optionsIndicatorStore.lastKnownImpliedMove(...)` and `market.previousClose()` |
| `calculateSliceIntents(MarketSnapshot, double)` | Reads `optionsIndicatorStore.lastKnownImpliedMove(...)`, `market.dailyVWAP()`; calls `calculateTimeExit()` and `calculateTotalQuantity(...)` |
| `calculateTotalQuantity(...)` | Reads `blackboard.getAccount().getNetLiquidation()` and `getAvailableFunds()`; reads `universeReference.marginRate(market.ticker(), true)` |
| `calculateTimeExit()` | Reads `marketCalendarStore.sessionClose(...)` |
| `evaluateTickStreamNeed(MarketSnapshot, double)` | Reads `market.lastPrice()`; reads and mutates `tickStreamController.isStreamActive/tryRequestStream/cancelStream` (the `ConcurrentHashMap` and `AtomicInteger` inside `TickByTickManager`) |
| `manageOpenPosition(Stock, MarketSnapshot)` | Reads `stock.getActiveBracket()` and `market.lastPrice()`, `market.dailyVWAP()`, `market.previousClose()`; reads `optionsIndicatorStore.lastKnownImpliedMove(...)`; mutates `ExitSlice` state via `setLastModificationTime(long)`; calls the inherited `updateExits(...)` |

---

## `OneSigmaDownsideMeanReversionStrategy`

`public class OneSigmaDownsideMeanReversionStrategy extends AbstractStrategy`

### 1. Class/Interface Responsibilities

Concrete `AbstractStrategy` for the strategy ID `ONE_SIGMA_DOWNSIDE` and `TradeDirection.LONG` that reads `OptionsIndicatorStore` and `MarketCalendarStore`, keeps three per-ticker `ConcurrentHashMap` fields for the re-entry cooldown and take-profit update budget, and produces a single exit slice.

### 2. Injected Dependencies

Public constructor: `public OneSigmaDownsideMeanReversionStrategy(StrategyBlackboard, BracketOrderGateway, TickStreamController, Config, TradingGate, MarketDataFreshness, UniverseReference, ConcentrationLimits, OptionsIndicatorStore, MarketCalendarStore)` — delegates with `Clock.systemUTC()`.

Package-private constructor adds a trailing `Clock clock`.

| Parameter | Exact type |
| --- | --- |
| `blackboard` | `mwd.trading.state.StrategyBlackboard` |
| `bracketOrderGateway` | `mwd.trading.execution.BracketOrderGateway` |
| `tickStreamController` | `mwd.trading.marketdata.TickStreamController` |
| `config` | `mwd.trading.config.Config` |
| `tradingGate` | `mwd.trading.lifecycle.TradingGate` |
| `marketDataFreshness` | `mwd.trading.marketdata.MarketDataFreshness` |
| `universeReference` | `mwd.trading.risk.UniverseReference` |
| `concentrationLimits` | `mwd.trading.risk.ConcentrationLimits` |
| `optionsIndicatorStore` | `mwd.trading.optionsproxy.OptionsIndicatorStore` (null-checked) |
| `marketCalendarStore` | `mwd.trading.calendar.MarketCalendarStore` (null-checked) |
| `clock` | `java.time.Clock` (stored as `newYorkClock = clock.withZone(NEW_YORK_ZONE)`) |

The universe passed to `super(...)` is `config.getStrategyUniverse(STRATEGY_ID)`.

Static fields: `public static final String STRATEGY_ID = "ONE_SIGMA_DOWNSIDE"`, `NEW_YORK_ZONE`, `ENTRY_MOVE_MULTIPLE`, `STOP_MOVE_MULTIPLE`, `MINIMUM_VWAP_MOVE_MULTIPLE`, `MAX_HOLD`, `NO_ENTRY_BEFORE_CLOSE`, `EXIT_BEFORE_CLOSE`, `REENTRY_COOLDOWN`, `MAX_TAKE_PROFIT_UPDATES`, `TAKE_PROFIT_UPDATE_INTERVAL`, `TAKE_PROFIT_UPDATE_THRESHOLD`.

### 3. Method Signatures

```java
public OneSigmaDownsideMeanReversionStrategy(StrategyBlackboard blackboard, BracketOrderGateway bracketOrderGateway, TickStreamController tickStreamController, Config config, TradingGate tradingGate, MarketDataFreshness marketDataFreshness, UniverseReference universeReference, ConcentrationLimits concentrationLimits, OptionsIndicatorStore optionsIndicatorStore, MarketCalendarStore marketCalendarStore)
OneSigmaDownsideMeanReversionStrategy(StrategyBlackboard blackboard, BracketOrderGateway bracketOrderGateway, TickStreamController tickStreamController, Config config, TradingGate tradingGate, MarketDataFreshness marketDataFreshness, UniverseReference universeReference, ConcentrationLimits concentrationLimits, OptionsIndicatorStore optionsIndicatorStore, MarketCalendarStore marketCalendarStore, Clock clock)

private LocalDate currentTradingDate()

@Override protected Set<MarketDataInput> requiredEntryInputs()
@Override protected Set<MarketDataInput> requiredManagementInputs()
@Override protected boolean isEntryConditionMet(MarketSnapshot market)
@Override protected double calculateEntryPrice(MarketSnapshot market)
@Override protected List<BracketOrderExecutor.SliceIntent> calculateSliceIntents(MarketSnapshot market, double entryPrice)
protected long calculateTimeExit()
private Decimal calculateTotalQuantity(MarketSnapshot market, double entryPrice, double stopLossPrice)
@Override protected void evaluateTickStreamNeed(MarketSnapshot market, double entryPrice)
@Override protected void manageOpenPosition(Stock stock, MarketSnapshot market)
@Override protected void onPositionClosed(Stock stock)
@Override protected String getStrategyName()
@Override protected TradeDirection getTradeDirection()
```

### 4. Global State Interactions

**Concurrent collections**

| Field | Declared type |
| --- | --- |
| `lastExitByTicker` | `Map<String, Instant>` = `new ConcurrentHashMap<>()` |
| `takeProfitUpdates` | `Map<String, Integer>` = `new ConcurrentHashMap<>()` |
| `lastTakeProfitUpdate` | `Map<String, Instant>` = `new ConcurrentHashMap<>()` |

| Method | Interaction |
| --- | --- |
| `isEntryConditionMet(MarketSnapshot)` | Reads `lastExitByTicker` (`get`) |
| `manageOpenPosition(Stock, MarketSnapshot)` | Reads `lastTakeProfitUpdate` (`get`) and `takeProfitUpdates` (`getOrDefault`); mutates both (`put`) |
| `onPositionClosed(Stock)` | Mutates `lastExitByTicker` (`put`), `takeProfitUpdates` (`remove`), `lastTakeProfitUpdate` (`remove`) |

**Centralized state objects**

| Method | Interaction |
| --- | --- |
| `isEntryConditionMet(MarketSnapshot)` | Reads `lastPrice()`, `previousClose()`, `dailyVWAP()`; reads `marketCalendarStore.sessionClose(...)` and `isWithinOfClose(...)`; reads `optionsIndicatorStore.impliedMoveForNewEntry(...)` |
| `calculateEntryPrice(MarketSnapshot)` | Reads `optionsIndicatorStore.lastKnownImpliedMove(...)` and `market.previousClose()` |
| `calculateSliceIntents(MarketSnapshot, double)` | Reads `optionsIndicatorStore.lastKnownImpliedMove(...)`, `market.previousClose()`, `market.dailyVWAP()`; calls `calculateTimeExit()` and `calculateTotalQuantity(...)` |
| `calculateTotalQuantity(...)` | Reads `blackboard.getAccount().getNetLiquidation()` and `getAvailableFunds()`; reads `universeReference.marginRate(market.ticker(), true)` |
| `calculateTimeExit()` | Reads `marketCalendarStore.sessionClose(...)` |
| `evaluateTickStreamNeed(MarketSnapshot, double)` | Empty body; no state interaction |
| `manageOpenPosition(Stock, MarketSnapshot)` | Reads `stock.getActiveBracket()` and `market.dailyVWAP()`; calls the inherited `updateExits(...)` |

---

## `OneSigmaUpsideMeanReversionStrategy`

`public class OneSigmaUpsideMeanReversionStrategy extends AbstractStrategy`

### 1. Class/Interface Responsibilities

Concrete `AbstractStrategy` for the strategy ID `ONE_SIGMA_UPSIDE` and `TradeDirection.SHORT` that reads `OptionsIndicatorStore` and `MarketCalendarStore`, keeps three per-ticker `ConcurrentHashMap` fields for the re-entry cooldown and take-profit update budget, and produces a single exit slice.

### 2. Injected Dependencies

Public constructor: `public OneSigmaUpsideMeanReversionStrategy(StrategyBlackboard, BracketOrderGateway, TickStreamController, Config, TradingGate, MarketDataFreshness, UniverseReference, ConcentrationLimits, OptionsIndicatorStore, MarketCalendarStore)` — delegates with `Clock.systemUTC()`.

Package-private constructor adds a trailing `Clock clock`.

| Parameter | Exact type |
| --- | --- |
| `blackboard` | `mwd.trading.state.StrategyBlackboard` |
| `bracketOrderGateway` | `mwd.trading.execution.BracketOrderGateway` |
| `tickStreamController` | `mwd.trading.marketdata.TickStreamController` |
| `config` | `mwd.trading.config.Config` |
| `tradingGate` | `mwd.trading.lifecycle.TradingGate` |
| `marketDataFreshness` | `mwd.trading.marketdata.MarketDataFreshness` |
| `universeReference` | `mwd.trading.risk.UniverseReference` |
| `concentrationLimits` | `mwd.trading.risk.ConcentrationLimits` |
| `optionsIndicatorStore` | `mwd.trading.optionsproxy.OptionsIndicatorStore` (null-checked) |
| `marketCalendarStore` | `mwd.trading.calendar.MarketCalendarStore` (null-checked) |
| `clock` | `java.time.Clock` (stored as `newYorkClock = clock.withZone(NEW_YORK_ZONE)`) |

The universe passed to `super(...)` is `config.getStrategyUniverse(STRATEGY_ID)`.

Static fields: `public static final String STRATEGY_ID = "ONE_SIGMA_UPSIDE"`, `NEW_YORK_ZONE`, `ENTRY_MOVE_MULTIPLE`, `STOP_MOVE_MULTIPLE`, `MAXIMUM_VWAP_MOVE_MULTIPLE`, `MAX_HOLD`, `NO_ENTRY_BEFORE_CLOSE`, `EXIT_BEFORE_CLOSE`, `REENTRY_COOLDOWN`, `MAX_TAKE_PROFIT_UPDATES`, `TAKE_PROFIT_UPDATE_INTERVAL`, `TAKE_PROFIT_UPDATE_THRESHOLD`.

### 3. Method Signatures

```java
public OneSigmaUpsideMeanReversionStrategy(StrategyBlackboard blackboard, BracketOrderGateway bracketOrderGateway, TickStreamController tickStreamController, Config config, TradingGate tradingGate, MarketDataFreshness marketDataFreshness, UniverseReference universeReference, ConcentrationLimits concentrationLimits, OptionsIndicatorStore optionsIndicatorStore, MarketCalendarStore marketCalendarStore)
OneSigmaUpsideMeanReversionStrategy(StrategyBlackboard blackboard, BracketOrderGateway bracketOrderGateway, TickStreamController tickStreamController, Config config, TradingGate tradingGate, MarketDataFreshness marketDataFreshness, UniverseReference universeReference, ConcentrationLimits concentrationLimits, OptionsIndicatorStore optionsIndicatorStore, MarketCalendarStore marketCalendarStore, Clock clock)

private LocalDate currentTradingDate()

@Override protected Set<MarketDataInput> requiredEntryInputs()
@Override protected Set<MarketDataInput> requiredManagementInputs()
@Override protected boolean isEntryConditionMet(MarketSnapshot market)
@Override protected double calculateEntryPrice(MarketSnapshot market)
@Override protected List<BracketOrderExecutor.SliceIntent> calculateSliceIntents(MarketSnapshot market, double entryPrice)
protected long calculateTimeExit()
private Decimal calculateTotalQuantity(MarketSnapshot market, double entryPrice, double stopLossPrice)
@Override protected void evaluateTickStreamNeed(MarketSnapshot market, double entryPrice)
@Override protected void manageOpenPosition(Stock stock, MarketSnapshot market)
@Override protected void onPositionClosed(Stock stock)
@Override protected String getStrategyName()
@Override protected TradeDirection getTradeDirection()
```

### 4. Global State Interactions

**Concurrent collections**

| Field | Declared type |
| --- | --- |
| `lastExitByTicker` | `Map<String, Instant>` = `new ConcurrentHashMap<>()` |
| `takeProfitUpdates` | `Map<String, Integer>` = `new ConcurrentHashMap<>()` |
| `lastTakeProfitUpdate` | `Map<String, Instant>` = `new ConcurrentHashMap<>()` |

| Method | Interaction |
| --- | --- |
| `isEntryConditionMet(MarketSnapshot)` | Reads `lastExitByTicker` (`get`) |
| `manageOpenPosition(Stock, MarketSnapshot)` | Reads `lastTakeProfitUpdate` (`get`) and `takeProfitUpdates` (`getOrDefault`); mutates both (`put`) |
| `onPositionClosed(Stock)` | Mutates `lastExitByTicker` (`put`), `takeProfitUpdates` (`remove`), `lastTakeProfitUpdate` (`remove`) |

**Centralized state objects**

| Method | Interaction |
| --- | --- |
| `isEntryConditionMet(MarketSnapshot)` | Reads `lastPrice()`, `previousClose()`, `dailyVWAP()`; reads `marketCalendarStore.sessionClose(...)` and `isWithinOfClose(...)`; reads `optionsIndicatorStore.impliedMoveForNewEntry(...)` |
| `calculateEntryPrice(MarketSnapshot)` | Reads `optionsIndicatorStore.lastKnownImpliedMove(...)` and `market.previousClose()` |
| `calculateSliceIntents(MarketSnapshot, double)` | Reads `optionsIndicatorStore.lastKnownImpliedMove(...)`, `market.previousClose()`, `market.dailyVWAP()`; calls `calculateTimeExit()` and `calculateTotalQuantity(...)` |
| `calculateTotalQuantity(...)` | Reads `blackboard.getAccount().getNetLiquidation()` and `getAvailableFunds()`; reads `universeReference.marginRate(market.ticker(), false)` |
| `calculateTimeExit()` | Reads `marketCalendarStore.sessionClose(...)` |
| `evaluateTickStreamNeed(MarketSnapshot, double)` | Empty body; no state interaction |
| `manageOpenPosition(Stock, MarketSnapshot)` | Reads `stock.getActiveBracket()` and `market.dailyVWAP()`; calls the inherited `updateExits(...)` |
