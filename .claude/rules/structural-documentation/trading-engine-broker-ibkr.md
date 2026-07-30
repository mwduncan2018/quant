---
paths:
  - "trading-engine/**/*"
---

# Package `mwd.trading.broker.ibkr`

Sources:
- `trading-engine/trading-engine/src/main/java/mwd/trading/broker/ibkr/EWrapperRaptor.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/broker/ibkr/IbkrSessionManager.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/broker/ibkr/IdManager.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/broker/ibkr/RequestRegistry.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/broker/ibkr/TickMap.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/broker/ibkr/TimeManager.java`

`EWrapperAbstractBase` is documented in `trading-engine-broker-ibkr-ewrapper-base.md`.
The `callback` subpackage is documented in `trading-engine-broker-ibkr-callback.md`.

---

## `EWrapperRaptor`

`public class EWrapperRaptor extends EWrapperAbstractBase`

### 1. Class/Interface Responsibilities

Overrides the `EWrapper` callbacks the engine uses and forwards each one to the injected handler objects, consulting `RequestRegistry.getConsumersFor(int)` to decide which handlers receive tick, historical-data, and historical-update callbacks.

### 2. Injected Dependencies

Constructor: `public EWrapperRaptor(RequestRegistry, IntradayWilderAtrTracker, DailyWilderAtrCalculator, IbkrErrorHandler, AccountEventHandler, SimpleMovingAverageTracker, NextValidIdHandler, OrderLifecycleHandler, RsiTracker, MinuteBarHandler, PriceTickHandler, SizeTickHandler, BrokerTimeHandler, MinuteVolumeTracker)`

| Parameter | Exact type |
| --- | --- |
| `registry` | `mwd.trading.broker.ibkr.RequestRegistry` |
| `intradayWilderAtrTracker` | `mwd.trading.indicator.IntradayWilderAtrTracker` |
| `dailyWilderAtrCalculator` | `mwd.trading.indicator.DailyWilderAtrCalculator` |
| `ibkrErrorHandler` | `mwd.trading.broker.ibkr.callback.IbkrErrorHandler` |
| `accountEventHandler` | `mwd.trading.broker.ibkr.callback.AccountEventHandler` |
| `simpleMovingAverageTracker` | `mwd.trading.indicator.SimpleMovingAverageTracker` |
| `nextValidIdHandler` | `mwd.trading.broker.ibkr.callback.NextValidIdHandler` |
| `orderLifecycleHandler` | `mwd.trading.execution.OrderLifecycleHandler` |
| `rsiTracker` | `mwd.trading.indicator.RsiTracker` |
| `minuteBarHandler` | `mwd.trading.marketdata.MinuteBarHandler` |
| `priceTickHandler` | `mwd.trading.marketdata.PriceTickHandler` |
| `sizeTickHandler` | `mwd.trading.marketdata.SizeTickHandler` |
| `brokerTimeHandler` | `mwd.trading.broker.ibkr.callback.BrokerTimeHandler` |
| `minuteVolumeTracker` | `mwd.trading.indicator.MinuteVolumeTracker` |

Injected after construction via `attachLifecycle`: `volatile IbkrSessionManager sessionManager`, `volatile MarketDataSubscriptionManager marketDataSubscriptionManager`.

### 3. Method Signatures

```java
public EWrapperRaptor(
        RequestRegistry registry,
        IntradayWilderAtrTracker intradayWilderAtrTracker,
        DailyWilderAtrCalculator dailyWilderAtrCalculator,
        IbkrErrorHandler ibkrErrorHandler,
        AccountEventHandler accountEventHandler,
        SimpleMovingAverageTracker simpleMovingAverageTracker,
        NextValidIdHandler nextValidIdHandler,
        OrderLifecycleHandler orderLifecycleHandler,
        RsiTracker rsiTracker,
        MinuteBarHandler minuteBarHandler,
        PriceTickHandler priceTickHandler,
        SizeTickHandler sizeTickHandler,
        BrokerTimeHandler brokerTimeHandler,
        MinuteVolumeTracker minuteVolumeTracker)

public void attachLifecycle(IbkrSessionManager sessionManager, MarketDataSubscriptionManager marketDataSubscriptionManager)

@Override public void nextValidId(int orderId)
@Override public void error(Exception e)
@Override public void error(String str)
@Override public void error(int id, long time, int errorCode, String errorMsg, String advancedOrderRejectJson)
@Override public void connectionClosed()
@Override public void connectAck()

@Override public void tickPrice(int reqId, int field, double price, TickAttrib attribs)
@Override public void tickSize(int reqId, int field, Decimal size)
@Override public void tickString(int reqId, int tickType, String value)
@Override public void tickByTickBidAsk(int reqId, long time, double bidPrice, double askPrice, Decimal bidSize, Decimal askSize, TickAttribBidAsk tickAttribBidAsk)
@Override public void tickByTickAllLast(int reqId, int tickType, long time, double price, Decimal size, TickAttribLast tickAttribLast, String exchange, String specialConditions)

@Override public void historicalData(int reqId, Bar bar)
@Override public void historicalDataEnd(int reqId, String startDate, String endDate)
@Override public void historicalDataUpdate(int reqId, Bar bar)

@Override public void orderStatus(int orderId, String status, Decimal filled, Decimal remaining, double avgFillPrice, long permId, int parentId, double lastFillPrice, int clientId, String whyHeld, double mktCapPrice)
@Override public void openOrder(int orderId, Contract contract, Order order, OrderState orderState)
@Override public void openOrderEnd()
@Override public void execDetails(int reqId, Contract contract, Execution execution)
@Override public void execDetailsEnd(int reqId)
@Override public void commissionAndFeesReport(CommissionAndFeesReport arg0)
@Override public void completedOrder(Contract contract, Order order, OrderState orderState)
@Override public void completedOrdersEnd()
@Override public void orderBound(long orderId, int apiClientId, int apiOrderId)

@Override public void managedAccounts(String accountsList)
@Override public void accountSummary(int reqId, String account, String tag, String value, String currency)
@Override public void position(String account, Contract contract, Decimal pos, double avgCost)
@Override public void positionEnd()
@Override public void accountDownloadEnd(String accountName)
@Override public void updatePortfolio(Contract contract, Decimal position, double marketPrice, double marketValue, double averageCost, double unrealizedPNL, double realizedPNL, String accountName)
@Override public void updateAccountTime(String timeStamp)
@Override public void updateAccountValue(String key, String value, String currency, String accountName)

@Override public void currentTime(long time)
```

### 4. Global State Interactions

**Concurrent collections**

Holds no collection of its own. Reads the `ConcurrentHashMap` inside `RequestRegistry` through `registry.getConsumersFor(int)` in: `tickPrice`, `tickSize`, `tickString`, `tickByTickBidAsk`, `tickByTickAllLast`, `historicalData`, `historicalDataEnd`, `historicalDataUpdate`.

**Centralized state objects**

No direct `Blackboard` reference. Every mutation of `Blackboard` reached from this class happens inside the injected handlers (`AccountEventHandler`, `IbkrErrorHandler`, `NextValidIdHandler`, `OrderLifecycleHandler`, `BrokerTimeHandler`, the indicator trackers, and the market-data handlers).

**Volatile fields**

`sessionManager` and `marketDataSubscriptionManager` are written by `attachLifecycle` and read by `nextValidId`, `error(int, long, int, String, String)`, `connectionClosed`, `connectAck`, `managedAccounts`.

---

## `IbkrSessionManager`

`public final class IbkrSessionManager implements AutoCloseable`

### 1. Class/Interface Responsibilities

Owns the `EClientSocket`, `EJavaSignal`, the `IBKR-Reader` thread, and a single-threaded `ScheduledExecutorService`, and drives connect, reconnect, market-data-type/current-time requests, subscription reset, and `TradingGate` transitions in response to IBKR connection callbacks.

### 2. Injected Dependencies

Constructor: `public IbkrSessionManager(EWrapper wrapper, Config config, TradingGate tradingGate, ReconciliationManager reconciliationManager)`

| Parameter | Exact type |
| --- | --- |
| `wrapper` | `com.ib.client.EWrapper` |
| `config` | `mwd.trading.config.Config` |
| `tradingGate` | `mwd.trading.lifecycle.TradingGate` |
| `reconciliationManager` | `mwd.trading.reconciliation.ReconciliationManager` |

Constructed inside the constructor: `EJavaSignal signal`, `EClientSocket client = new EClientSocket(wrapper, signal)`, `lifecycleExecutor = Executors.newSingleThreadScheduledExecutor(...)` (thread name `IBKR-Session-Lifecycle`, daemon).

Injected after construction via `attachSubscriptionManager`: `volatile MarketDataSubscriptionManager subscriptionManager`.

### 3. Method Signatures

```java
public IbkrSessionManager(EWrapper wrapper, Config config, TradingGate tradingGate, ReconciliationManager reconciliationManager)

public EClientSocket client()
public void attachSubscriptionManager(MarketDataSubscriptionManager subscriptionManager)
public void start()
public void onConnectAck()
public void onNextValidId()
public void onManagedAccounts(String accountsList)
public void onConnectionClosed()
public void onError(int errorCode, String errorMessage)

private void connectNow()
private void startReaderThread()
private void initializeConnectedSession()
private void restoreBrokerConnectivity(boolean dataLost)
private void scheduleReconnect()
private void markMarketDataStale()
private void updatePortFromMessage(String message)
private static String[] accounts(String accountsList)

@Override public void close()
```

Static field: `private static final Pattern PORT_PATTERN`.

### 4. Global State Interactions

**Concurrent collections / atomics**

| Field | Declared type |
| --- | --- |
| `reconnectScheduled` | `AtomicBoolean` |
| `apiReadyHandled` | `AtomicBoolean` |
| `lifecycleExecutor` | `ScheduledExecutorService` |

| Method | Interaction |
| --- | --- |
| `onNextValidId()` | Mutates `apiReadyHandled` (`compareAndSet`); submits to `lifecycleExecutor` |
| `onConnectionClosed()` | Mutates `apiReadyHandled` (`set`) |
| `connectNow()` | Mutates `reconnectScheduled` (`set`), `apiReadyHandled` (`set`) |
| `scheduleReconnect()` | Mutates `reconnectScheduled` (`compareAndSet`); schedules on `lifecycleExecutor` |
| `start()`, `onError(int, String)` | Submit tasks to `lifecycleExecutor` |
| `close()` | Calls `lifecycleExecutor.shutdownNow()` |

**Volatile fields**

`subscriptionManager`, `stopping`, `hasConnectedBefore`, `currentPort`, `readerThread`.

**Centralized state objects**

No `Blackboard` reference. Mutates `TradingGate` (`transitionTo`, `requireManualIntervention`) from `connectNow`, `initializeConnectedSession`, `restoreBrokerConnectivity`, `scheduleReconnect`, `onManagedAccounts`, `close`. Calls `ReconciliationManager.attachClient`, `begin`, `onDisconnected`. Calls `MarketDataSubscriptionManager.markAllStale`, `initializeIfNeeded`, `resetAfterSocketReconnect`, `resubscribeAfterDataLoss`.

---

## `IdManager`

`public class IdManager`

### 1. Class/Interface Responsibilities

Holds two `AtomicInteger` counters, one for IBKR order IDs and one for request IDs, with monotonic initialisation and increment-and-return accessors.

### 2. Injected Dependencies

None. The class declares no constructor.

### 3. Method Signatures

```java
public void initializeRequestId(int requestId)
public int getNextRequestId()
public void initializeOrderId(int orderId)
public int getNextOrderId()
```

### 4. Global State Interactions

**Concurrent collections / atomics**

| Field | Declared type |
| --- | --- |
| `orderId` | `AtomicInteger` |
| `requestId` | `AtomicInteger` |

| Method | Interaction |
| --- | --- |
| `initializeRequestId(int)` | Mutates `requestId` (`accumulateAndGet` with `Math::max`) |
| `getNextRequestId()` | Mutates `requestId` (`getAndIncrement`) |
| `initializeOrderId(int)` | Mutates `orderId` (`accumulateAndGet` with `Math::max`) |
| `getNextOrderId()` | Mutates `orderId` (`getAndIncrement`) |

**Centralized state objects**

None. This instance is itself held by `Blackboard` and reached only through it.

---

## `RequestRegistry`

`public class RequestRegistry`

### 1. Class/Interface Responsibilities

Maps an IBKR request ID to a ticker and an `EnumSet<DataConsumer>` inside a `ConcurrentHashMap`, and exposes register, lookup, and unregister operations.

### 2. Injected Dependencies

None. The class declares no constructor.

### 3. Method Signatures

Nested types:
- `public enum DataConsumer { ATR_MINUTE_WILDERS, ATR_DAILY_WILDERS, ERROR, MOVING_AVERAGE, NEXT_VALID_ID, RSI, TICK_BAR, TICK_PRICE, TICK_SIZE, VOLUME }`
- `private static record ReqData(String ticker, EnumSet<DataConsumer> consumers)`

```java
public void register(int reqId, String ticker, DataConsumer... consumers)
public String getTickerFor(int reqId)
public List<String> getAllTickers()
public EnumSet<DataConsumer> getConsumersFor(int reqId)
public void unregister(int reqId)
```

### 4. Global State Interactions

**Concurrent collections**

| Field | Declared type |
| --- | --- |
| `idToData` | `Map<Integer, ReqData>` = `new ConcurrentHashMap<>()` |

| Method | Interaction |
| --- | --- |
| `register(int, String, DataConsumer...)` | Mutates `idToData` (`put`) |
| `getTickerFor(int)` | Reads `idToData` (`get`) |
| `getAllTickers()` | Reads `idToData` (`values().stream()`) |
| `getConsumersFor(int)` | Reads `idToData` (`get`) |
| `unregister(int)` | Mutates `idToData` (`remove`) |

**Centralized state objects**

No `Blackboard` reference.

---

## `TickMap`

`public class TickMap`

### 1. Class/Interface Responsibilities

Answers whether an IBKR tick field number corresponds to a named price or size field, selecting between the live and delayed field-number sets from a single `boolean liveIBKRData`. It declares no VWAP predicate: IBKR defines no VWAP price tick in either set, and the figure arrives on `tickString` as `RT_VOLUME`.

### 2. Injected Dependencies

Constructors:
- `public TickMap(Config config)` — parameter type `mwd.trading.config.Config`; delegates to the package-private constructor with `config.isLiveIBKRData()`
- `TickMap(boolean liveIBKRData)` — package-private, parameter type `boolean`

### 3. Method Signatures

```java
public TickMap(Config config)
TickMap(boolean liveIBKRData)

private boolean liveIBKRData()

public boolean isBid(int field)
public boolean isAsk(int field)
public boolean isLast(int field)
public boolean isMarkPrice(int field)
public boolean isOpen(int field)
public boolean isClose(int field)
public boolean isHigh(int field)
public boolean isLow(int field)

public boolean isBidSize(int field)
public boolean isAskSize(int field)
public boolean isLastSize(int field)
public boolean isVolume(int field)
public boolean isAverageVolume(int field)
```

### 4. Global State Interactions

None. The single field `private final boolean liveIBKRData` is immutable after construction; there are no collections and no centralized state references.

---

## `TimeManager`

`public class TimeManager`

### 1. Class/Interface Responsibilities

Stores a millisecond offset between the IBKR server clock and the local clock and returns `System.currentTimeMillis()` adjusted by it.

### 2. Injected Dependencies

None. The class declares no constructor.

### 3. Method Signatures

```java
public void setOffset(long serverTimeSeconds, long localTimeMillis)
public long getMarketTime()
```

### 4. Global State Interactions

**Concurrent collections**

None. The single field `private long offsetMillis` is not declared `volatile` or atomic.

**Centralized state objects**

None directly. This instance is held by `Blackboard` and reached through `Blackboard.getTimeManager()` and `Blackboard.getMarketTime()`.
