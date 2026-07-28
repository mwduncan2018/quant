---
paths:
  - "trading-engine/**/*"
---

# Package `mwd.trading.marketdata`

Sources:
- `trading-engine/trading-engine/src/main/java/mwd/trading/marketdata/MarketDataFreshness.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/marketdata/MarketDataInput.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/marketdata/MarketDataInputStore.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/marketdata/MarketDataSubscriptionManager.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/marketdata/MinuteBarHandler.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/marketdata/PriceTickHandler.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/marketdata/SizeTickHandler.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/marketdata/TickByTickManager.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/marketdata/TickStreamController.java`

---

## `MarketDataFreshness`

`public interface MarketDataFreshness`

### 1. Class/Interface Responsibilities

Declares the three per-input readiness queries for one symbol's IBKR-sourced market data.

### 2. Injected Dependencies

None. Interfaces declare no constructor.

### 3. Method Signatures

```java
boolean isFresh(String ticker, MarketDataInput input)
boolean areAllFresh(String ticker, Set<MarketDataInput> required)
Optional<String> describeUnready(String ticker, Set<MarketDataInput> required)
```

All are implicitly `public abstract`.

### 4. Global State Interactions

None declared.

---

## `MarketDataInput`

`public enum MarketDataInput`

### 1. Class/Interface Responsibilities

Enumerates the five individually tracked IBKR market-data inputs (`LAST_PRICE`, `PREVIOUS_CLOSE`, `DAILY_VWAP`, `MINUTE_BAR`, `MINUTE_VOLUME_BASELINE`), each with a description string and an `Expiry` mode.

### 2. Injected Dependencies

Constructor: `MarketDataInput(String description, Expiry expiry)` (implicitly `private`)

| Parameter | Exact type |
| --- | --- |
| `description` | `java.lang.String` |
| `expiry` | `mwd.trading.marketdata.MarketDataInput.Expiry` |

Constants: `LAST_PRICE("last price", Expiry.AGED)`, `PREVIOUS_CLOSE("previous close", Expiry.SESSION_SCOPED)`, `DAILY_VWAP("daily VWAP", Expiry.AGED)`, `MINUTE_BAR("one-minute bar", Expiry.AGED)`, `MINUTE_VOLUME_BASELINE("rolling 15-minute volume baseline", Expiry.AGED)`.

### 3. Method Signatures

Nested type: `public enum Expiry { AGED, SESSION_SCOPED }`

```java
MarketDataInput(String description, Expiry expiry)
public String description()
public Expiry expiry()
```

### 4. Global State Interactions

None. Both fields are `final` and there are no collections or centralized state references.

---

## `MarketDataInputStore`

`public final class MarketDataInputStore implements MarketDataFreshness`

### 1. Class/Interface Responsibilities

Records, per symbol and per `MarketDataInput`, the reception timestamp and session date of the last accepted value in a `ConcurrentHashMap<String, AtomicReferenceArray<Reading>>`, and answers freshness queries against a configured maximum age and the New York date.

### 2. Injected Dependencies

Constructors:
- `public MarketDataInputStore(long maxAgeMs)` — delegates with `Clock.systemUTC()`
- `public MarketDataInputStore(long maxAgeMs, Clock clock)`

| Parameter | Exact type |
| --- | --- |
| `maxAgeMs` | `long` (must be positive) |
| `clock` | `java.time.Clock`, stored as `newYorkClock = clock.withZone(NEW_YORK_ZONE)` |

Static fields: `private static final ZoneId NEW_YORK_ZONE`, `private static final MarketDataInput[] INPUTS = MarketDataInput.values()`.

### 3. Method Signatures

Nested type: `private record Reading(long recordedAtUnixMs, LocalDate sessionDate)`

```java
public MarketDataInputStore(long maxAgeMs)
public MarketDataInputStore(long maxAgeMs, Clock clock)

public void record(String ticker, MarketDataInput input)
public void markAllStale()
@Override public boolean isFresh(String ticker, MarketDataInput input)
@Override public boolean areAllFresh(String ticker, Set<MarketDataInput> required)
@Override public Optional<String> describeUnready(String ticker, Set<MarketDataInput> required)
public OptionalLong ageMs(String ticker, MarketDataInput input)
public int coveredSymbolCount()

private String unreadyReason(String normalizedTicker, MarketDataInput input)
private Reading readingFor(String normalizedTicker, MarketDataInput input)
private static String normalize(String ticker)
```

### 4. Global State Interactions

**Concurrent collections**

| Field | Declared type |
| --- | --- |
| `readings` | `Map<String, AtomicReferenceArray<Reading>>` = `new ConcurrentHashMap<>()` |

| Method | Interaction |
| --- | --- |
| `record(String, MarketDataInput)` | Mutates `readings` (`computeIfAbsent`) and the `AtomicReferenceArray` (`set`) |
| `markAllStale()` | Mutates `readings` (`clear`) |
| `isFresh(String, MarketDataInput)` | Reads `readings` via `unreadyReason` |
| `areAllFresh(String, Set<MarketDataInput>)` | Reads `readings` via `unreadyReason` |
| `describeUnready(String, Set<MarketDataInput>)` | Reads `readings` via `unreadyReason` |
| `ageMs(String, MarketDataInput)` | Reads `readings` via `readingFor` |
| `coveredSymbolCount()` | Reads `readings` (`size`) |
| `readingFor(String, MarketDataInput)` | Reads `readings` (`get`) and the `AtomicReferenceArray` (`get`) |

**Centralized state objects**

No `Blackboard` reference. The store holds metadata only; the values themselves live on `Stock`.

---

## `MarketDataSubscriptionManager`

`public final class MarketDataSubscriptionManager`

### 1. Class/Interface Responsibilities

Creates the IBKR stock `Contract` for each configured ticker, issues the daily-history, minute-history, and live market-data requests with fresh request IDs registered into `RequestRegistry`, tracks those request IDs in two `ConcurrentHashMap` fields, and cancels/reissues them on reconnect or data loss.

### 2. Injected Dependencies

Constructor: `public MarketDataSubscriptionManager(EClientSocket client, Blackboard blackboard, RequestRegistry registry, List<String> tickers, MarketDataInputStore inputStore)`

| Parameter | Exact type |
| --- | --- |
| `client` | `com.ib.client.EClientSocket` |
| `blackboard` | `mwd.trading.state.Blackboard` |
| `registry` | `mwd.trading.broker.ibkr.RequestRegistry` |
| `tickers` | `java.util.List<java.lang.String>` (stored as `List.copyOf(tickers)`) |
| `inputStore` | `mwd.trading.marketdata.MarketDataInputStore` |

### 3. Method Signatures

```java
public MarketDataSubscriptionManager(EClientSocket client, Blackboard blackboard, RequestRegistry registry, List<String> tickers, MarketDataInputStore inputStore)

public void initializeIfNeeded() throws InterruptedException
public void resetAfterSocketReconnect() throws InterruptedException
public void resubscribeAfterDataLoss() throws InterruptedException
public void markAllStale()
public List<String> tickers()

private void requestDailyHistory(String ticker, Contract contract)
private void requestMinuteHistory(String ticker, Contract contract)
private void requestLiveMarketData(String ticker, Contract contract)
private void cancelTrackedRequests()
private static Contract stockContract(String ticker)
```

### 4. Global State Interactions

**Concurrent collections / atomics**

| Field | Declared type |
| --- | --- |
| `liveMarketDataRequests` | `Map<String, Integer>` = `new ConcurrentHashMap<>()` |
| `minuteHistoryRequests` | `Map<String, Integer>` = `new ConcurrentHashMap<>()` |
| `initialized` | `AtomicBoolean` |

| Method | Interaction |
| --- | --- |
| `initializeIfNeeded()` | Mutates `initialized` (`compareAndSet`) |
| `resetAfterSocketReconnect()` | Mutates `initialized` (`set(false)`); calls `cancelTrackedRequests()` |
| `requestMinuteHistory(String, Contract)` | Mutates `minuteHistoryRequests` (`put`); mutates `registry.register(...)` |
| `requestLiveMarketData(String, Contract)` | Mutates `liveMarketDataRequests` (`put`); mutates `registry.register(...)` |
| `requestDailyHistory(String, Contract)` | Mutates `registry.register(...)` |
| `cancelTrackedRequests()` | Reads and clears both maps; mutates `registry.unregister(int)` |
| `resubscribeAfterDataLoss()` | Calls `markAllStale()` and `cancelTrackedRequests()` |

**Centralized state objects (`Blackboard`) and `MarketDataInputStore`**

| Method | Interaction |
| --- | --- |
| `initializeIfNeeded()` | Mutates `blackboard.getStock(ticker).setContract(Contract)` |
| `resubscribeAfterDataLoss()` | Mutates `blackboard.getStock(ticker).setContract(Contract)` |
| `requestDailyHistory`, `requestMinuteHistory`, `requestLiveMarketData` | Mutate `blackboard.getNextRequestId()` |
| `markAllStale()` | Mutates `inputStore.markAllStale()` |

---

## `MinuteBarHandler`

`public class MinuteBarHandler`

### 1. Class/Interface Responsibilities

Validates each incoming minute `Bar`, stores it on the matching `Stock`, and records `MarketDataInput.MINUTE_BAR` in `MarketDataInputStore`.

### 2. Injected Dependencies

Constructor: `public MinuteBarHandler(Blackboard blackboard, RequestRegistry registry, MarketDataInputStore inputStore)`

| Parameter | Exact type |
| --- | --- |
| `blackboard` | `mwd.trading.state.Blackboard` |
| `registry` | `mwd.trading.broker.ibkr.RequestRegistry` |
| `inputStore` | `mwd.trading.marketdata.MarketDataInputStore` |

### 3. Method Signatures

```java
public MinuteBarHandler(Blackboard blackboard, RequestRegistry registry, MarketDataInputStore inputStore)

public void onHistoricalData(int reqId, Bar bar)
public void onHistoricalDataUpdate(int reqId, Bar bar)

private void updateLastMinuteBar(int reqId, Bar bar)
private boolean isUsable(Bar bar)
```

### 4. Global State Interactions

**Concurrent collections**

Holds none.

**Centralized state objects (`Blackboard`) and `MarketDataInputStore`**

| Method | Interaction |
| --- | --- |
| `updateLastMinuteBar(int, Bar)` | Reads `registry.getTickerFor(int)`; mutates `blackboard.getStock(ticker).setLastMinuteBar(Bar)`; mutates `inputStore.record(ticker, MarketDataInput.MINUTE_BAR)` |

Both public methods delegate to `updateLastMinuteBar`.

---

## `PriceTickHandler`

`public class PriceTickHandler`

### 1. Class/Interface Responsibilities

Routes IBKR price ticks and tick-by-tick price callbacks to the matching `Stock` setter using `TickMap` field resolution, and records `LAST_PRICE`, `PREVIOUS_CLOSE`, and `DAILY_VWAP` in `MarketDataInputStore`.

### 2. Injected Dependencies

Constructor: `public PriceTickHandler(Blackboard blackboard, RequestRegistry registry, TickMap tickMap, MarketDataInputStore inputStore)`

| Parameter | Exact type |
| --- | --- |
| `blackboard` | `mwd.trading.state.Blackboard` |
| `registry` | `mwd.trading.broker.ibkr.RequestRegistry` |
| `tickMap` | `mwd.trading.broker.ibkr.TickMap` |
| `inputStore` | `mwd.trading.marketdata.MarketDataInputStore` |

### 3. Method Signatures

```java
public PriceTickHandler(Blackboard blackboard, RequestRegistry registry, TickMap tickMap, MarketDataInputStore inputStore)

public void onTickPrice(int reqId, int field, double price, TickAttrib attribs)
public void onTickByTickBidAsk(int reqId, double bidPrice, double askPrice)
public void onTickByTickAllLast(int reqId, double price)
```

### 4. Global State Interactions

**Concurrent collections**

Holds none.

**Centralized state objects (`Blackboard`) and `MarketDataInputStore`**

| Method | Interaction |
| --- | --- |
| `onTickPrice(int, int, double, TickAttrib)` | Reads `registry.getTickerFor(int)` and `tickMap.isBid/isAsk/isLast/isMarkPrice/isOpen/isClose/isHigh/isLow/isVwap(int)`; mutates `blackboard.getStock(ticker)` via `setBid`, `setAsk`, `setLastPrice`, `setMarkPrice`, `setOpen`, `setPreviousClose`, `setDailyHigh`, `setDailyLow`, `setDailyVWAP`; mutates `inputStore.record(...)` for `LAST_PRICE`, `PREVIOUS_CLOSE`, `DAILY_VWAP` |
| `onTickByTickBidAsk(int, double, double)` | Reads `registry.getTickerFor(int)`; mutates `blackboard.getStock(ticker)` via `setBid`, `setAsk` |
| `onTickByTickAllLast(int, double)` | Reads `registry.getTickerFor(int)`; mutates `stock.setLastPrice(double)` and `inputStore.record(ticker, MarketDataInput.LAST_PRICE)` |

---

## `SizeTickHandler`

`public class SizeTickHandler`

### 1. Class/Interface Responsibilities

Routes IBKR size ticks and tick-by-tick size callbacks to the matching `Stock` setter using `TickMap` field resolution.

### 2. Injected Dependencies

Constructor: `public SizeTickHandler(Blackboard blackboard, RequestRegistry registry, TickMap tickMap)`

| Parameter | Exact type |
| --- | --- |
| `blackboard` | `mwd.trading.state.Blackboard` |
| `registry` | `mwd.trading.broker.ibkr.RequestRegistry` |
| `tickMap` | `mwd.trading.broker.ibkr.TickMap` |

### 3. Method Signatures

```java
public SizeTickHandler(Blackboard blackboard, RequestRegistry registry, TickMap tickMap)

public void onTickSize(int reqId, int field, Decimal size)
public void onTickByTickBidAsk(int reqId, Decimal bidSize, Decimal askSize)
public void onTickByTickAllLast(int reqId, Decimal size)
```

### 4. Global State Interactions

**Concurrent collections**

Holds none.

**Centralized state objects (`Blackboard`)**

| Method | Interaction |
| --- | --- |
| `onTickSize(int, int, Decimal)` | Reads `registry.getTickerFor(int)` and `tickMap.isBidSize/isAskSize/isLastSize/isVolume/isAverageVolume(int)`; mutates `blackboard.getStock(ticker)` via `setBidSize`, `setAskSize`, `setLastSize`, `setIntradayVolume`, `setAverageDailyVolume` |
| `onTickByTickBidAsk(int, Decimal, Decimal)` | Reads `registry.getTickerFor(int)`; mutates `setBidSize`, `setAskSize` |
| `onTickByTickAllLast(int, Decimal)` | Reads `registry.getTickerFor(int)`; mutates `setLastSize` |

This handler does not write to `MarketDataInputStore`.

---

## `TickStreamController`

`public interface TickStreamController`

### 1. Class/Interface Responsibilities

Declares the three tick-by-tick stream operations a strategy may invoke for one ticker.

### 2. Injected Dependencies

None. Interfaces declare no constructor.

### 3. Method Signatures

```java
boolean isStreamActive(String ticker)
void tryRequestStream(String ticker)
void cancelStream(String ticker)
```

All are implicitly `public abstract`.

### 4. Global State Interactions

None declared.

---

## `TickByTickManager`

`public class TickByTickManager implements TickStreamController`

### 1. Class/Interface Responsibilities

Tracks up to five concurrent IBKR tick-by-tick subscriptions using an `AtomicInteger` counter and a `ConcurrentHashMap` of ticker-to-request-ID, and issues or cancels the underlying `reqTickByTickData`/`cancelTickByTickData` calls.

### 2. Injected Dependencies

Constructor: `public TickByTickManager(Blackboard blackboard, EClientSocket client, RequestRegistry registry)`

| Parameter | Exact type |
| --- | --- |
| `blackboard` | `mwd.trading.state.Blackboard` |
| `client` | `com.ib.client.EClientSocket` |
| `registry` | `mwd.trading.broker.ibkr.RequestRegistry` |

Instance field: `private final int MAX_STREAMS = 5`.

### 3. Method Signatures

```java
public TickByTickManager(Blackboard blackboard, EClientSocket client, RequestRegistry registry)

public boolean isSlotAvailable()
@Override public boolean isStreamActive(String ticker)
public int getActiveStreamCount()
@Override public void tryRequestStream(String ticker)
@Override public void cancelStream(String ticker)

private void executeRequest(String ticker)
```

### 4. Global State Interactions

**Concurrent collections / atomics**

| Field | Declared type |
| --- | --- |
| `activeStreamCount` | `AtomicInteger` |
| `activeRequests` | `Map<String, Integer>` = `new ConcurrentHashMap<>()` |

| Method | Interaction |
| --- | --- |
| `isSlotAvailable()` | Reads `activeStreamCount` (`get`) |
| `isStreamActive(String)` | Reads `activeRequests` (`containsKey`) |
| `getActiveStreamCount()` | Reads `activeStreamCount` (`get`) |
| `tryRequestStream(String)` | Reads `activeRequests` via `isStreamActive`; mutates `activeStreamCount` (`compareAndSet`) |
| `cancelStream(String)` | Mutates `activeRequests` (`remove`), `activeStreamCount` (`decrementAndGet`), and `registry.unregister(int)` |
| `executeRequest(String)` | Mutates `activeRequests` (`put`) and `registry.register(...)` |

**Centralized state objects (`Blackboard`)**

| Method | Interaction |
| --- | --- |
| `executeRequest(String)` | Reads `blackboard.getStock(ticker)` and `stock.getContract()`; mutates `blackboard.getNextRequestId()` |
