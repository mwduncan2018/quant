---
paths:
  - "trading-engine/**/*"
---

# Package `mwd.trading.strategy`

Sources:
- `trading-engine/trading-engine/src/main/java/mwd/trading/strategy/AbstractStrategy.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/strategy/TwoSigmaDownsideMeanReversionStrategy.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/strategy/OneSigmaDownsideMeanReversionStrategy.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/strategy/OneSigmaUpsideMeanReversionStrategy.java`

---

## `AbstractStrategy`

`public abstract class AbstractStrategy implements Runnable`

### 1. Class/Interface Responsibilities

Base class that owns the poll loop over a normalized ticker universe, the reservation and rollback sequence around `Blackboard` pending/position ownership and `Stock.PositionState`, the pending-acknowledgement and unready-reason bookkeeping maps, and the abstract hooks each concrete strategy implements.

### 2. Injected Dependencies

Protected constructor: `protected AbstractStrategy(Blackboard, BracketOrderGateway, TickStreamController, Config, TradingGate, MarketDataFreshness, Set<String>)` — delegates with `Clock.systemUTC()`.

Protected constructor: `protected AbstractStrategy(Blackboard, BracketOrderGateway, TickStreamController, Config, TradingGate, MarketDataFreshness, Set<String>, Clock)`

| Parameter | Exact type |
| --- | --- |
| `blackboard` | `mwd.trading.state.Blackboard` |
| `bracketOrderGateway` | `mwd.trading.execution.BracketOrderGateway` |
| `tickStreamController` | `mwd.trading.marketdata.TickStreamController` |
| `config` | `mwd.trading.config.Config` |
| `tradingGate` | `mwd.trading.lifecycle.TradingGate` |
| `marketDataFreshness` | `mwd.trading.marketdata.MarketDataFreshness` |
| `universe` | `java.util.Set<java.lang.String>` (trimmed, upper-cased, stored as `Set.copyOf(...)`; empty throws `IllegalArgumentException`) |
| `clock` | `java.time.Clock` |

All are `Objects.requireNonNull`-checked. `logger` is `LogManager.getLogger(getClass())`.

### 3. Method Signatures

Nested type: `private record PendingEntry(long submittedAtMillis)`

```java
protected AbstractStrategy(Blackboard blackboard, BracketOrderGateway bracketOrderGateway, TickStreamController tickStreamController, Config config, TradingGate tradingGate, MarketDataFreshness marketDataFreshness, Set<String> universe)
protected AbstractStrategy(Blackboard blackboard, BracketOrderGateway bracketOrderGateway, TickStreamController tickStreamController, Config config, TradingGate tradingGate, MarketDataFreshness marketDataFreshness, Set<String> universe, Clock clock)

@Override public final void run()
final void runOneCycle()
protected final void executeLifecycle(Stock stock)

private void evaluateNewEntry(Stock stock, String strategyId)
private void handlePendingEntry(Stock stock, String strategyId)
private void handleFlatWithLocalOwnership(Stock stock)
private void completeConfirmedFlat(Stock stock, String strategyId, BracketOrder bracketOrder)
private void cleanupOwnedLifecycle(Stock stock, String strategyId, BracketOrder bracketOrder)
private void rollbackEntryReservation(Stock stock, String strategyId)
private void processSymbolSafely(Stock stock)

protected final void updateExits(Stock stock, BracketOrder bracketOrder, BracketOrder.ExitSlice exitSlice, double takeProfitPrice, double stopLossPrice, long timeExitValue)
public final Set<String> getUniverse()

private boolean entryInputsReady(Stock stock)
private boolean automatedOrderChangesAllowed(Stock stock)
private boolean acknowledgementTimedOut(long submittedAtMillis)
private void escalate(Stock stock, String message)
private String strategyId()
private TradeDirection tradeDirection()
private static Decimal totalQuantity(List<BracketOrderExecutor.SliceIntent> sliceIntents)
private static boolean isZero(Decimal quantity)
private static boolean isConfirmedTerminal(BracketOrder.Status status)

protected void onPositionClosed(Stock stock)

protected abstract Set<MarketDataInput> requiredEntryInputs()
protected abstract Set<MarketDataInput> requiredManagementInputs()
protected abstract boolean isEntryConditionMet(Stock stock)
protected abstract double calculateEntryPrice(Stock stock)
protected abstract List<BracketOrderExecutor.SliceIntent> calculateSliceIntents(Stock stock, double entryPrice)
protected abstract void evaluateTickStreamNeed(Stock stock, double entryPrice)
protected abstract void manageOpenPosition(Stock stock)
protected abstract String getStrategyName()
protected abstract TradeDirection getTradeDirection()
```

Protected fields available to subclasses: `logger` (`Logger`), `blackboard` (`Blackboard`), `bracketOrderGateway` (`BracketOrderGateway`), `tickStreamController` (`TickStreamController`), `config` (`Config`), `tradingGate` (`TradingGate`), `marketDataFreshness` (`MarketDataFreshness`).

### 4. Global State Interactions

**Concurrent collections**

| Field | Declared type |
| --- | --- |
| `pendingEntries` | `ConcurrentMap<String, PendingEntry>` = `new ConcurrentHashMap<>()` |
| `escalatedPendingEntries` | `Set<String>` = `ConcurrentHashMap.newKeySet()` |
| `lastUnreadyReason` | `ConcurrentMap<String, String>` = `new ConcurrentHashMap<>()` |
| `universe` | `final Set<String>` (immutable) |

| Method | Interaction |
| --- | --- |
| `evaluateNewEntry(Stock, String)` | Mutates `pendingEntries` (`put`) |
| `handlePendingEntry(Stock, String)` | Reads `pendingEntries` (`get`); mutates `pendingEntries` (`remove`) and `escalatedPendingEntries` (`remove`) |
| `cleanupOwnedLifecycle(Stock, String, BracketOrder)` | Mutates `pendingEntries` (`remove`) and `escalatedPendingEntries` (`remove`) |
| `rollbackEntryReservation(Stock, String)` | Mutates `pendingEntries` (`remove`) and `escalatedPendingEntries` (`remove`) |
| `escalate(Stock, String)` | Mutates `escalatedPendingEntries` (`add`) |
| `entryInputsReady(Stock)` | Mutates `lastUnreadyReason` (`remove`, `put`) |
| `runOneCycle()` | Reads `universe` |
| `getUniverse()` | Reads `universe` |

**Centralized state objects (`Blackboard`)**

| Method | Interaction |
| --- | --- |
| `runOneCycle()` | Reads `blackboard.getStock(ticker)` for each universe member |
| `executeLifecycle(Stock)` | Reads `blackboard.getPositionOwner(String)`; reads `stock.getState().get()` |
| `evaluateNewEntry(Stock, String)` | Reads `tradingGate.allowsNewEntries()`, `stock.isTradeable()`, `blackboard.isAccountCurrentForNewEntry()`; mutates `blackboard.tryAcquireGlobalPending(...)`, `blackboard.tryReservePosition(...)`, `stock.getState().compareAndSet(FLAT, PENDING)`, `blackboard.recordEntrySubmitted(long)`; calls `bracketOrderGateway.placeTripleThreat(...)`; in the `finally` block mutates `blackboard.releaseGlobalPending(...)` and `blackboard.releasePosition(...)` |
| `handlePendingEntry(Stock, String)` | Reads `stock.getActiveBracket()`; mutates `stock.getState().set(...)` and `blackboard.releaseGlobalPending(...)` |
| `handleFlatWithLocalOwnership(Stock)` | Reads `stock.getActiveBracket()`; calls `cleanupOwnedLifecycle` or `escalate` |
| `completeConfirmedFlat(...)` | Mutates `stock.getState().set(FLAT)`; calls `cleanupOwnedLifecycle` |
| `cleanupOwnedLifecycle(...)` | Calls `onPositionClosed(Stock)`; mutates `blackboard.releaseGlobalPending(...)`, `blackboard.releasePosition(...)`, `stock.setActiveBracket(null)`; calls `tickStreamController.isStreamActive/cancelStream` |
| `rollbackEntryReservation(...)` | Mutates `stock.getState().compareAndSet(PENDING, FLAT)`, `blackboard.releasePosition(...)`, `blackboard.releaseGlobalPending(...)`; calls `tickStreamController.cancelStream` |
| `processSymbolSafely(Stock)` | On `RuntimeException` mutates `stock.setTradeable(false)`; reads `stock.getState().get()` and `blackboard.getPositionOwner(String)` |
| `updateExits(...)` | Reads `stock.getState().get()` and `blackboard.isPositionOwnedBy(...)`; calls `bracketOrderGateway.updateTripleThreatExits(...)` |
| `escalate(Stock, String)` | Mutates `blackboard.setSystemHalted(true)` and `tradingGate.requireManualIntervention(String)` |
| `entryInputsReady(Stock)` | Reads `marketDataFreshness.describeUnready(...)` |
| `automatedOrderChangesAllowed(Stock)` | Reads `tradingGate.allowsAutomatedOrderChanges()` and `marketDataFreshness.describeUnready(...)` |

---

## `TwoSigmaDownsideMeanReversionStrategy`

`public class TwoSigmaDownsideMeanReversionStrategy extends AbstractStrategy`

### 1. Class/Interface Responsibilities

Concrete `AbstractStrategy` for the strategy ID `TWO_SIGMA_DOWNSIDE` and `TradeDirection.LONG` that reads `OptionsIndicatorStore`, `EarningsStore`, and `MarketCalendarStore` alongside `Stock` market data to implement the entry, sizing, two-slice intent, tick-stream, and open-position hooks.

### 2. Injected Dependencies

Public constructor: `public TwoSigmaDownsideMeanReversionStrategy(Blackboard, BracketOrderGateway, TickStreamController, Config, TradingGate, MarketDataFreshness, OptionsIndicatorStore, EarningsStore, MarketCalendarStore)` — delegates with `Clock.systemUTC()`.

Package-private constructor adds a trailing `Clock clock`.

| Parameter | Exact type |
| --- | --- |
| `blackboard` | `mwd.trading.state.Blackboard` |
| `bracketOrderGateway` | `mwd.trading.execution.BracketOrderGateway` |
| `tickStreamController` | `mwd.trading.marketdata.TickStreamController` |
| `config` | `mwd.trading.config.Config` |
| `tradingGate` | `mwd.trading.lifecycle.TradingGate` |
| `marketDataFreshness` | `mwd.trading.marketdata.MarketDataFreshness` |
| `optionsIndicatorStore` | `mwd.trading.optionsproxy.OptionsIndicatorStore` (null-checked) |
| `earningsStore` | `mwd.trading.earnings.EarningsStore` (null-checked) |
| `marketCalendarStore` | `mwd.trading.calendar.MarketCalendarStore` (null-checked) |
| `clock` | `java.time.Clock` (stored as `newYorkClock = clock.withZone(NEW_YORK_ZONE)`) |

The universe passed to `super(...)` is `config.getStrategyUniverse(STRATEGY_ID)`.

Static fields: `public static final String STRATEGY_ID = "TWO_SIGMA_DOWNSIDE"`, `private static final ZoneId NEW_YORK_ZONE`, `private static final String MARKET_INDEX_TICKER = "SPY"`, `private static final Duration NO_ENTRY_BEFORE_CLOSE`, `private static final Duration EXIT_BEFORE_CLOSE`, `private static final Set<MarketDataInput> ENTRY_INPUTS`, `private static final Set<MarketDataInput> MANAGEMENT_INPUTS`.

### 3. Method Signatures

```java
public TwoSigmaDownsideMeanReversionStrategy(Blackboard blackboard, BracketOrderGateway bracketOrderGateway, TickStreamController tickStreamController, Config config, TradingGate tradingGate, MarketDataFreshness marketDataFreshness, OptionsIndicatorStore optionsIndicatorStore, EarningsStore earningsStore, MarketCalendarStore marketCalendarStore)
TwoSigmaDownsideMeanReversionStrategy(Blackboard blackboard, BracketOrderGateway bracketOrderGateway, TickStreamController tickStreamController, Config config, TradingGate tradingGate, MarketDataFreshness marketDataFreshness, OptionsIndicatorStore optionsIndicatorStore, EarningsStore earningsStore, MarketCalendarStore marketCalendarStore, Clock clock)

private LocalDate currentTradingDate()

@Override protected Set<MarketDataInput> requiredEntryInputs()
@Override protected Set<MarketDataInput> requiredManagementInputs()
@Override protected boolean isEntryConditionMet(Stock stock)
@Override protected double calculateEntryPrice(Stock stock)
@Override protected List<BracketOrderExecutor.SliceIntent> calculateSliceIntents(Stock stock, double entryPrice)
private Decimal calculateTotalQuantity(Stock stock, double entryPrice, double stopLossPrice)
private boolean isInEarningsBlackout(String ticker, LocalDate tradingDate)
protected long calculateTimeExit()
@Override protected void evaluateTickStreamNeed(Stock stock, double entryPrice)
@Override protected void manageOpenPosition(Stock stock)
@Override protected String getStrategyName()
@Override protected TradeDirection getTradeDirection()
```

### 4. Global State Interactions

**Concurrent collections**

None of its own; `ENTRY_INPUTS` and `MANAGEMENT_INPUTS` are immutable `Set.of(...)` constants. It inherits `pendingEntries`, `escalatedPendingEntries`, and `lastUnreadyReason` from `AbstractStrategy`.

**Centralized state objects**

| Method | Interaction |
| --- | --- |
| `isEntryConditionMet(Stock)` | Reads `stock.isLongMarginRateVerified()`, `getLastPrice()`, `getPreviousClose()`, `getDailyVWAP()`, `getLastMinuteBar()`, `getLastMinuteVolume()`, `getAverageLast15MinuteVolume()`; reads `marketCalendarStore.isWithinOfClose(...)` and `sessionClose(...)`; reads `optionsIndicatorStore.impliedMoveForNewEntry(...)` and `gammaFlipForNewEntry(...)`; reads `blackboard.getStock("SPY")` and `marketDataFreshness.isFresh("SPY", LAST_PRICE)`; calls `isInEarningsBlackout(...)` |
| `isInEarningsBlackout(String, LocalDate)` | Reads `earningsStore.earningsDate(...)`; reads `marketCalendarStore.nextSession(...)` and `previousSessionApproximate(...)` |
| `calculateEntryPrice(Stock)` | Reads `optionsIndicatorStore.lastKnownImpliedMove(...)` and `stock.getPreviousClose()` |
| `calculateSliceIntents(Stock, double)` | Reads `optionsIndicatorStore.lastKnownImpliedMove(...)`, `stock.getDailyVWAP()`; calls `calculateTimeExit()` and `calculateTotalQuantity(...)` |
| `calculateTotalQuantity(...)` | Reads `blackboard.getAccount().getNetLiquidation()` and `getAvailableFunds()`; reads `stock.calculateMarginRequirement(...)` and `stock.getLongMarginRate()` |
| `calculateTimeExit()` | Reads `marketCalendarStore.sessionClose(...)` |
| `evaluateTickStreamNeed(Stock, double)` | Reads `stock.getLastPrice()`; reads and mutates `tickStreamController.isStreamActive/tryRequestStream/cancelStream` (the `ConcurrentHashMap` and `AtomicInteger` inside `TickByTickManager`) |
| `manageOpenPosition(Stock)` | Reads `stock.getActiveBracket()`, `getLastPrice()`, `getDailyVWAP()`, `getPreviousClose()`; reads `optionsIndicatorStore.lastKnownImpliedMove(...)`; mutates `ExitSlice` state via `setLastModificationTime(long)`; calls the inherited `updateExits(...)` |

---

## `OneSigmaDownsideMeanReversionStrategy`

`public class OneSigmaDownsideMeanReversionStrategy extends AbstractStrategy`

### 1. Class/Interface Responsibilities

Concrete `AbstractStrategy` for the strategy ID `ONE_SIGMA_DOWNSIDE` and `TradeDirection.LONG` that reads `OptionsIndicatorStore` and `MarketCalendarStore`, keeps three per-ticker `ConcurrentHashMap` fields for the re-entry cooldown and take-profit update budget, and produces a single exit slice.

### 2. Injected Dependencies

Public constructor: `public OneSigmaDownsideMeanReversionStrategy(Blackboard, BracketOrderGateway, TickStreamController, Config, TradingGate, MarketDataFreshness, OptionsIndicatorStore, MarketCalendarStore)` — delegates with `Clock.systemUTC()`.

Package-private constructor adds a trailing `Clock clock`.

| Parameter | Exact type |
| --- | --- |
| `blackboard` | `mwd.trading.state.Blackboard` |
| `bracketOrderGateway` | `mwd.trading.execution.BracketOrderGateway` |
| `tickStreamController` | `mwd.trading.marketdata.TickStreamController` |
| `config` | `mwd.trading.config.Config` |
| `tradingGate` | `mwd.trading.lifecycle.TradingGate` |
| `marketDataFreshness` | `mwd.trading.marketdata.MarketDataFreshness` |
| `optionsIndicatorStore` | `mwd.trading.optionsproxy.OptionsIndicatorStore` (null-checked) |
| `marketCalendarStore` | `mwd.trading.calendar.MarketCalendarStore` (null-checked) |
| `clock` | `java.time.Clock` (stored as `newYorkClock = clock.withZone(NEW_YORK_ZONE)`) |

The universe passed to `super(...)` is `config.getStrategyUniverse(STRATEGY_ID)`.

Static fields: `public static final String STRATEGY_ID = "ONE_SIGMA_DOWNSIDE"`, `NEW_YORK_ZONE`, `ENTRY_MOVE_MULTIPLE`, `STOP_MOVE_MULTIPLE`, `MINIMUM_VWAP_MOVE_MULTIPLE`, `MAX_HOLD`, `NO_ENTRY_BEFORE_CLOSE`, `EXIT_BEFORE_CLOSE`, `REENTRY_COOLDOWN`, `MAX_TAKE_PROFIT_UPDATES`, `TAKE_PROFIT_UPDATE_INTERVAL`, `TAKE_PROFIT_UPDATE_THRESHOLD`.

### 3. Method Signatures

```java
public OneSigmaDownsideMeanReversionStrategy(Blackboard blackboard, BracketOrderGateway bracketOrderGateway, TickStreamController tickStreamController, Config config, TradingGate tradingGate, MarketDataFreshness marketDataFreshness, OptionsIndicatorStore optionsIndicatorStore, MarketCalendarStore marketCalendarStore)
OneSigmaDownsideMeanReversionStrategy(Blackboard blackboard, BracketOrderGateway bracketOrderGateway, TickStreamController tickStreamController, Config config, TradingGate tradingGate, MarketDataFreshness marketDataFreshness, OptionsIndicatorStore optionsIndicatorStore, MarketCalendarStore marketCalendarStore, Clock clock)

private LocalDate currentTradingDate()

@Override protected Set<MarketDataInput> requiredEntryInputs()
@Override protected Set<MarketDataInput> requiredManagementInputs()
@Override protected boolean isEntryConditionMet(Stock stock)
@Override protected double calculateEntryPrice(Stock stock)
@Override protected List<BracketOrderExecutor.SliceIntent> calculateSliceIntents(Stock stock, double entryPrice)
protected long calculateTimeExit()
private Decimal calculateTotalQuantity(Stock stock, double entryPrice, double stopLossPrice)
@Override protected void evaluateTickStreamNeed(Stock stock, double entryPrice)
@Override protected void manageOpenPosition(Stock stock)
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
| `isEntryConditionMet(Stock)` | Reads `lastExitByTicker` (`get`) |
| `manageOpenPosition(Stock)` | Reads `lastTakeProfitUpdate` (`get`) and `takeProfitUpdates` (`getOrDefault`); mutates both (`put`) |
| `onPositionClosed(Stock)` | Mutates `lastExitByTicker` (`put`), `takeProfitUpdates` (`remove`), `lastTakeProfitUpdate` (`remove`) |

**Centralized state objects**

| Method | Interaction |
| --- | --- |
| `isEntryConditionMet(Stock)` | Reads `stock.isLongMarginRateVerified()`, `getLastPrice()`, `getPreviousClose()`, `getDailyVWAP()`; reads `marketCalendarStore.sessionClose(...)` and `isWithinOfClose(...)`; reads `optionsIndicatorStore.impliedMoveForNewEntry(...)` |
| `calculateEntryPrice(Stock)` | Reads `optionsIndicatorStore.lastKnownImpliedMove(...)` and `stock.getPreviousClose()` |
| `calculateSliceIntents(Stock, double)` | Reads `optionsIndicatorStore.lastKnownImpliedMove(...)`, `stock.getPreviousClose()`, `stock.getDailyVWAP()`; calls `calculateTimeExit()` and `calculateTotalQuantity(...)` |
| `calculateTotalQuantity(...)` | Reads `blackboard.getAccount().getNetLiquidation()` and `getAvailableFunds()`; reads `stock.calculateMarginRequirement("BUY", ...)` and `stock.getLongMarginRate()` |
| `calculateTimeExit()` | Reads `marketCalendarStore.sessionClose(...)` |
| `evaluateTickStreamNeed(Stock, double)` | Empty body; no state interaction |
| `manageOpenPosition(Stock)` | Reads `stock.getActiveBracket()` and `stock.getDailyVWAP()`; calls the inherited `updateExits(...)` |

---

## `OneSigmaUpsideMeanReversionStrategy`

`public class OneSigmaUpsideMeanReversionStrategy extends AbstractStrategy`

### 1. Class/Interface Responsibilities

Concrete `AbstractStrategy` for the strategy ID `ONE_SIGMA_UPSIDE` and `TradeDirection.SHORT` that reads `OptionsIndicatorStore` and `MarketCalendarStore`, keeps three per-ticker `ConcurrentHashMap` fields for the re-entry cooldown and take-profit update budget, and produces a single exit slice.

### 2. Injected Dependencies

Public constructor: `public OneSigmaUpsideMeanReversionStrategy(Blackboard, BracketOrderGateway, TickStreamController, Config, TradingGate, MarketDataFreshness, OptionsIndicatorStore, MarketCalendarStore)` — delegates with `Clock.systemUTC()`.

Package-private constructor adds a trailing `Clock clock`.

| Parameter | Exact type |
| --- | --- |
| `blackboard` | `mwd.trading.state.Blackboard` |
| `bracketOrderGateway` | `mwd.trading.execution.BracketOrderGateway` |
| `tickStreamController` | `mwd.trading.marketdata.TickStreamController` |
| `config` | `mwd.trading.config.Config` |
| `tradingGate` | `mwd.trading.lifecycle.TradingGate` |
| `marketDataFreshness` | `mwd.trading.marketdata.MarketDataFreshness` |
| `optionsIndicatorStore` | `mwd.trading.optionsproxy.OptionsIndicatorStore` (null-checked) |
| `marketCalendarStore` | `mwd.trading.calendar.MarketCalendarStore` (null-checked) |
| `clock` | `java.time.Clock` (stored as `newYorkClock = clock.withZone(NEW_YORK_ZONE)`) |

The universe passed to `super(...)` is `config.getStrategyUniverse(STRATEGY_ID)`.

Static fields: `public static final String STRATEGY_ID = "ONE_SIGMA_UPSIDE"`, `NEW_YORK_ZONE`, `ENTRY_MOVE_MULTIPLE`, `STOP_MOVE_MULTIPLE`, `MAXIMUM_VWAP_MOVE_MULTIPLE`, `MAX_HOLD`, `NO_ENTRY_BEFORE_CLOSE`, `EXIT_BEFORE_CLOSE`, `REENTRY_COOLDOWN`, `MAX_TAKE_PROFIT_UPDATES`, `TAKE_PROFIT_UPDATE_INTERVAL`, `TAKE_PROFIT_UPDATE_THRESHOLD`.

### 3. Method Signatures

```java
public OneSigmaUpsideMeanReversionStrategy(Blackboard blackboard, BracketOrderGateway bracketOrderGateway, TickStreamController tickStreamController, Config config, TradingGate tradingGate, MarketDataFreshness marketDataFreshness, OptionsIndicatorStore optionsIndicatorStore, MarketCalendarStore marketCalendarStore)
OneSigmaUpsideMeanReversionStrategy(Blackboard blackboard, BracketOrderGateway bracketOrderGateway, TickStreamController tickStreamController, Config config, TradingGate tradingGate, MarketDataFreshness marketDataFreshness, OptionsIndicatorStore optionsIndicatorStore, MarketCalendarStore marketCalendarStore, Clock clock)

private LocalDate currentTradingDate()

@Override protected Set<MarketDataInput> requiredEntryInputs()
@Override protected Set<MarketDataInput> requiredManagementInputs()
@Override protected boolean isEntryConditionMet(Stock stock)
@Override protected double calculateEntryPrice(Stock stock)
@Override protected List<BracketOrderExecutor.SliceIntent> calculateSliceIntents(Stock stock, double entryPrice)
protected long calculateTimeExit()
private Decimal calculateTotalQuantity(Stock stock, double entryPrice, double stopLossPrice)
@Override protected void evaluateTickStreamNeed(Stock stock, double entryPrice)
@Override protected void manageOpenPosition(Stock stock)
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
| `isEntryConditionMet(Stock)` | Reads `lastExitByTicker` (`get`) |
| `manageOpenPosition(Stock)` | Reads `lastTakeProfitUpdate` (`get`) and `takeProfitUpdates` (`getOrDefault`); mutates both (`put`) |
| `onPositionClosed(Stock)` | Mutates `lastExitByTicker` (`put`), `takeProfitUpdates` (`remove`), `lastTakeProfitUpdate` (`remove`) |

**Centralized state objects**

| Method | Interaction |
| --- | --- |
| `isEntryConditionMet(Stock)` | Reads `stock.isShortMarginRateVerified()`, `getLastPrice()`, `getPreviousClose()`, `getDailyVWAP()`; reads `marketCalendarStore.sessionClose(...)` and `isWithinOfClose(...)`; reads `optionsIndicatorStore.impliedMoveForNewEntry(...)` |
| `calculateEntryPrice(Stock)` | Reads `optionsIndicatorStore.lastKnownImpliedMove(...)` and `stock.getPreviousClose()` |
| `calculateSliceIntents(Stock, double)` | Reads `optionsIndicatorStore.lastKnownImpliedMove(...)`, `stock.getPreviousClose()`, `stock.getDailyVWAP()`; calls `calculateTimeExit()` and `calculateTotalQuantity(...)` |
| `calculateTotalQuantity(...)` | Reads `blackboard.getAccount().getNetLiquidation()` and `getAvailableFunds()`; reads `stock.calculateMarginRequirement("SELL", ...)` and `stock.getShortMarginRate()` |
| `calculateTimeExit()` | Reads `marketCalendarStore.sessionClose(...)` |
| `evaluateTickStreamNeed(Stock, double)` | Empty body; no state interaction |
| `manageOpenPosition(Stock)` | Reads `stock.getActiveBracket()` and `stock.getDailyVWAP()`; calls the inherited `updateExits(...)` |
