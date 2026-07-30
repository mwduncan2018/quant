---
paths:
  - "trading-engine/**/*"
---

# Package `mwd.trading.indicator`

Sources:
- `trading-engine/trading-engine/src/main/java/mwd/trading/indicator/DailyVwapTracker.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/indicator/DailyWilderAtrCalculator.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/indicator/IntradayWilderAtrTracker.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/indicator/MinuteVolumeTracker.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/indicator/RsiTracker.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/indicator/SimpleMovingAverageTracker.java`

---

## `DailyVwapTracker`

`public class DailyVwapTracker`

### 1. Class/Interface Responsibilities

Maintains a per-ticker `VwapSession` of running volume-weighted sums in a `ConcurrentHashMap`, derives the session VWAP from the minute-bar stream, writes it to `Stock`, and records `MarketDataInput.DAILY_VWAP` in `MarketDataInputStore`. IBKR publishes no VWAP price tick in either the live or the delayed field set, so the figure is computed rather than received.

### 2. Injected Dependencies

Constructor: `public DailyVwapTracker(Blackboard blackboard, RequestRegistry registry, MarketDataInputStore inputStore)`

| Parameter | Exact type |
| --- | --- |
| `blackboard` | `mwd.trading.state.Blackboard` |
| `registry` | `mwd.trading.broker.ibkr.RequestRegistry` |
| `inputStore` | `mwd.trading.marketdata.MarketDataInputStore` |

Static fields: `private static final ZoneId NEW_YORK_ZONE`, `private static final DateTimeFormatter BAR_DATE`, `private static final int VWAP_SCALE = 6`, `private static final Logger logger`.

### 3. Method Signatures

Nested types:
- `private record VwapBar(LocalDate sessionDate, long epochSecond, BigDecimal notional, BigDecimal volume)`
- `private static final class VwapSession`

```java
public DailyVwapTracker(Blackboard blackboard, RequestRegistry registry, MarketDataInputStore inputStore)

public void onHistoricalData(int reqId, Bar bar)
public void onHistoricalDataUpdate(int reqId, Bar bar)
public void onHistoricalDataEnd(int reqId, String start, String end)

private void updateVwap(int reqId, Bar bar)
private static VwapBar describe(String ticker, Bar bar)
private static BigDecimal usableVolume(Bar bar)
private static BigDecimal usablePrice(Bar bar)
private static boolean isAllDigits(String value)
```

`VwapSession`:

```java
synchronized LocalDate sessionDate()
synchronized void observe(VwapBar bar)
synchronized BigDecimal vwap()
```

### 4. Global State Interactions

**Concurrent collections**

| Field | Declared type |
| --- | --- |
| `sessions` | `Map<String, VwapSession>` = `new ConcurrentHashMap<>()` |

| Method | Interaction |
| --- | --- |
| `updateVwap(int, Bar)` | Mutates `sessions` (`computeIfAbsent`); inside `synchronized (session)` calls `observe(...)` and `vwap()` |
| `onHistoricalDataEnd(int, String, String)` | Reads `sessions` (`get`), then `sessionDate()` and `vwap()` |

`VwapSession` guards its `completedNotional`, `completedVolume`, `sessionDate`, and `pending` with the instance monitor.

**Centralized state objects (`Blackboard`) and `MarketDataInputStore`**

| Method | Interaction |
| --- | --- |
| `updateVwap(int, Bar)` | Reads `registry.getTickerFor(int)`; reads `blackboard.getStock(ticker)`; mutates `stock.setDailyVWAP(double)` and `inputStore.record(ticker, MarketDataInput.DAILY_VWAP)` when the session has traded volume |

---

## `DailyWilderAtrCalculator`

`public class DailyWilderAtrCalculator`

### 1. Class/Interface Responsibilities

Buffers daily historical `Bar` objects per request ID in a `ConcurrentHashMap`, computes a 14-period Wilder ATR when the historical stream ends, and writes it to the matching `Stock`.

### 2. Injected Dependencies

Constructor: `public DailyWilderAtrCalculator(Blackboard blackboard, RequestRegistry registry)`

| Parameter | Exact type |
| --- | --- |
| `blackboard` | `mwd.trading.state.Blackboard` |
| `registry` | `mwd.trading.broker.ibkr.RequestRegistry` |

Instance field: `private final int period = 14`.

### 3. Method Signatures

```java
public DailyWilderAtrCalculator(Blackboard blackboard, RequestRegistry registry)

public void onHistoricalData(int reqId, Bar bar)
public void onHistoricalDataEnd(int reqId, String startDate, String endDate)

private double calculateWildersATR(List<Bar> bars)
private double calculateTrueRange(Bar current, double prevClose)
```

### 4. Global State Interactions

**Concurrent collections**

| Field | Declared type |
| --- | --- |
| `historyBuffer` | `Map<Integer, List<Bar>>` = `new ConcurrentHashMap<>()`; values are `Collections.synchronizedList(new ArrayList<>())` |

| Method | Interaction |
| --- | --- |
| `onHistoricalData(int, Bar)` | Mutates `historyBuffer` (`computeIfAbsent`, then `add` on the synchronized list) |
| `onHistoricalDataEnd(int, String, String)` | Mutates `historyBuffer` (`remove`) |

**Centralized state objects (`Blackboard`)**

| Method | Interaction |
| --- | --- |
| `onHistoricalDataEnd(int, String, String)` | Reads `registry.getTickerFor(int)`; mutates `blackboard.getStock(ticker).setDailyATR(double)` |

---

## `IntradayWilderAtrTracker`

`public class IntradayWilderAtrTracker`

### 1. Class/Interface Responsibilities

Buffers minute `Bar` objects per request ID in a `ConcurrentHashMap`, aggregates them into 4-, 5-, 12-, and 15-minute virtual bars, and writes the four Wilder ATR values to the matching `Stock`.

### 2. Injected Dependencies

Constructor: `public IntradayWilderAtrTracker(Blackboard blackboard, RequestRegistry registry)`

| Parameter | Exact type |
| --- | --- |
| `blackboard` | `mwd.trading.state.Blackboard` |
| `registry` | `mwd.trading.broker.ibkr.RequestRegistry` |

Instance field: `private final int period = 14`.

### 3. Method Signatures

```java
public IntradayWilderAtrTracker(Blackboard blackboard, RequestRegistry registry)

public void onHistoricalData(int requestId, Bar bar)
public void onHistoricalDataEnd(int requestId, String start, String end)
public void onHistoricalDataUpdate(int requestId, Bar bar)

private void processUpdate(int requestId, String action, String start, String end)
private void updateATR(Stock stock, List<Bar> minuteBars, int factor, String action, String start, String end)
private List<Bar> createVirtualBars(List<Bar> minuteBars, int factor)
private Bar aggregate(List<Bar> window)
private boolean isNewBar(List<Bar> bars, Bar newBar)
private double calculateWildersATR(List<Bar> bars)
private double calculateTrueRange(Bar current, double previousClose)
```

### 4. Global State Interactions

**Concurrent collections**

| Field | Declared type |
| --- | --- |
| `historyBuffer` | `Map<Integer, List<Bar>>` = `new ConcurrentHashMap<>()`; values are `Collections.synchronizedList(new ArrayList<>())` |

| Method | Interaction |
| --- | --- |
| `onHistoricalData(int, Bar)` | Mutates `historyBuffer` (`computeIfAbsent`, then `add`) |
| `onHistoricalDataUpdate(int, Bar)` | Reads `historyBuffer`; inside `synchronized (bars)` mutates the buffered list (`add`, `remove(0)` beyond 1500 entries) |
| `processUpdate(int, String, String, String)` | Reads `historyBuffer` |

**Centralized state objects (`Blackboard`)**

| Method | Interaction |
| --- | --- |
| `processUpdate(...)` | Reads `registry.getTickerFor(int)`; reads `blackboard.getStock(ticker)` |
| `updateATR(...)` | Mutates `stock.setIntradayATR4/5/12/15(double)` |

---

## `MinuteVolumeTracker`

`public class MinuteVolumeTracker`

### 1. Class/Interface Responsibilities

Maintains a per-ticker `VolumeWindow` of completed minute-bar volumes in a `ConcurrentHashMap`, publishes the newest bar's volume and the 15-bar mean onto `Stock`, and records `MarketDataInput.MINUTE_VOLUME_BASELINE` in `MarketDataInputStore` once the window is full.

### 2. Injected Dependencies

Constructor: `public MinuteVolumeTracker(Blackboard blackboard, RequestRegistry registry, MarketDataInputStore inputStore)`

| Parameter | Exact type |
| --- | --- |
| `blackboard` | `mwd.trading.state.Blackboard` |
| `registry` | `mwd.trading.broker.ibkr.RequestRegistry` |
| `inputStore` | `mwd.trading.marketdata.MarketDataInputStore` |

Static fields: `static final int BASELINE_BARS = 15`, `private static final ZoneId NEW_YORK_ZONE`, `private static final DateTimeFormatter BAR_DATE`, `private static final BigDecimal BASELINE_DIVISOR`, `private static final Logger logger`.

### 3. Method Signatures

Nested types:
- `private record MinuteBar(LocalDate sessionDate, long epochSecond, BigDecimal volume)`
- `private static final class VolumeWindow`

```java
public MinuteVolumeTracker(Blackboard blackboard, RequestRegistry registry, MarketDataInputStore inputStore)

public void onHistoricalData(int reqId, Bar bar)
public void onHistoricalDataEnd(int reqId, String start, String end)
public void onHistoricalDataUpdate(int reqId, Bar bar)

private void updateVolume(int reqId, Bar bar)
private void publish(String ticker, Stock stock, VolumeWindow window)
private static BigDecimal usableVolume(Bar bar)
private static MinuteBar describe(String ticker, Bar bar, BigDecimal volume)
private static boolean isAllDigits(String value)
```

`VolumeWindow`:

```java
synchronized MinuteBar pending()
synchronized void setPending(MinuteBar pending)
synchronized int size()
synchronized LocalDate sessionDate()
synchronized void commit(MinuteBar bar)
synchronized Decimal baseline()
```

### 4. Global State Interactions

**Concurrent collections**

| Field | Declared type |
| --- | --- |
| `windows` | `Map<String, VolumeWindow>` = `new ConcurrentHashMap<>()` |

| Method | Interaction |
| --- | --- |
| `updateVolume(int, Bar)` | Mutates `windows` (`computeIfAbsent`); inside `synchronized (window)` reads and mutates the window via `pending()`, `setPending(...)`, `commit(...)` |
| `onHistoricalDataEnd(int, String, String)` | Reads `windows` (`get`), then `window.size()` and `window.sessionDate()` |

`VolumeWindow` guards its `ArrayDeque<BigDecimal> completed`, `BigDecimal sum`, `LocalDate sessionDate`, and `MinuteBar pending` with the instance monitor.

**Centralized state objects (`Blackboard`) and `MarketDataInputStore`**

| Method | Interaction |
| --- | --- |
| `updateVolume(int, Bar)` | Reads `registry.getTickerFor(int)`; reads `blackboard.getStock(ticker)`; mutates `stock.setLastMinuteVolume(Decimal)` |
| `publish(String, Stock, VolumeWindow)` | Mutates `stock.setAverageLast15MinuteVolume(Decimal)`; mutates `inputStore.record(ticker, MarketDataInput.MINUTE_VOLUME_BASELINE)` when the baseline is non-zero |

---

## `RsiTracker`

`public class RsiTracker`

### 1. Class/Interface Responsibilities

Buffers minute `Bar` objects per request ID in a `ConcurrentHashMap`, computes a 14-period Wilder-smoothed RSI, and writes it to the matching `Stock`.

### 2. Injected Dependencies

Constructor: `public RsiTracker(Blackboard blackboard, RequestRegistry registry)`

| Parameter | Exact type |
| --- | --- |
| `blackboard` | `mwd.trading.state.Blackboard` |
| `registry` | `mwd.trading.broker.ibkr.RequestRegistry` |

Instance field: `private final int period = 14`.

### 3. Method Signatures

```java
public RsiTracker(Blackboard blackboard, RequestRegistry registry)

public void onHistoricalData(int requestId, Bar bar)
public void onHistoricalDataEnd(int requestId, String start, String end)
public void onHistoricalDataUpdate(int requestId, Bar bar)

private void processUpdate(int requestId)
private double calculateRSI(List<Bar> bars)
private boolean isNewBar(List<Bar> bars, Bar newBar)
```

### 4. Global State Interactions

**Concurrent collections**

| Field | Declared type |
| --- | --- |
| `historyBuffer` | `Map<Integer, List<Bar>>` = `new ConcurrentHashMap<>()`; values are `Collections.synchronizedList(new ArrayList<>())` |

| Method | Interaction |
| --- | --- |
| `onHistoricalData(int, Bar)` | Mutates `historyBuffer` (`computeIfAbsent`, then `add`) |
| `onHistoricalDataEnd(int, String, String)` | Reads `historyBuffer`; enters `synchronized (bars)` |
| `onHistoricalDataUpdate(int, Bar)` | Reads `historyBuffer`; inside `synchronized (bars)` mutates the list (`add`, `remove(0)` beyond 1500 entries) |
| `processUpdate(int)` | Reads `historyBuffer` |

**Centralized state objects (`Blackboard`)**

| Method | Interaction |
| --- | --- |
| `processUpdate(int)` | Reads `registry.getTickerFor(int)`; mutates `blackboard.getStock(ticker).setRSI(double)` |

---

## `SimpleMovingAverageTracker`

`public class SimpleMovingAverageTracker`

### 1. Class/Interface Responsibilities

Buffers historical closes per request ID, stores five per-ticker tail sums in five `ConcurrentHashMap` fields, and writes the 10/20/50/100/200 simple moving averages onto `Stock` on historical completion and on each qualifying last-price tick.

### 2. Injected Dependencies

Constructor: `public SimpleMovingAverageTracker(Blackboard blackboard, RequestRegistry registry, TickMap tickMap)`

| Parameter | Exact type |
| --- | --- |
| `blackboard` | `mwd.trading.state.Blackboard` |
| `registry` | `mwd.trading.broker.ibkr.RequestRegistry` |
| `tickMap` | `mwd.trading.broker.ibkr.TickMap` |

### 3. Method Signatures

```java
public SimpleMovingAverageTracker(Blackboard blackboard, RequestRegistry registry, TickMap tickMap)

public void onHistoricalData(int reqId, Bar bar)
public void onHistoricalDataEnd(int reqId, String startDate, String endDate)
public void onTickPrice(int reqId, int field, double price, TickAttrib attribs)

private void updateBlackboard(String ticker, double price)
private double calculateTailSum(List<Double> prices, int n)
```

### 4. Global State Interactions

**Concurrent collections**

| Field | Declared type |
| --- | --- |
| `historyBuffer` | `Map<Integer, List<Double>>` = `new ConcurrentHashMap<>()`; values are `Collections.synchronizedList(new ArrayList<>())` |
| `sum199` | `Map<String, Double>` = `new ConcurrentHashMap<>()` |
| `sum99` | `Map<String, Double>` = `new ConcurrentHashMap<>()` |
| `sum49` | `Map<String, Double>` = `new ConcurrentHashMap<>()` |
| `sum19` | `Map<String, Double>` = `new ConcurrentHashMap<>()` |
| `sum9` | `Map<String, Double>` = `new ConcurrentHashMap<>()` |

| Method | Interaction |
| --- | --- |
| `onHistoricalData(int, Bar)` | Mutates `historyBuffer` (`computeIfAbsent`, then `add`) |
| `onHistoricalDataEnd(int, String, String)` | Mutates `historyBuffer` (`remove`); mutates `sum199`, `sum99`, `sum49`, `sum19`, `sum9` (`put`) |
| `updateBlackboard(String, double)` | Reads `sum199`, `sum99`, `sum49`, `sum19`, `sum9` (`get`) |

**Centralized state objects (`Blackboard`)**

| Method | Interaction |
| --- | --- |
| `onHistoricalDataEnd(int, String, String)` | Reads `registry.getTickerFor(int)`; calls `updateBlackboard` |
| `onTickPrice(int, int, double, TickAttrib)` | Reads `tickMap.isLast(int)` and `registry.getTickerFor(int)`; calls `updateBlackboard` |
| `updateBlackboard(String, double)` | Reads `blackboard.getStock(ticker)`; mutates `setSma200`, `setSma100`, `setSma50`, `setSma20`, `setSma10` |
