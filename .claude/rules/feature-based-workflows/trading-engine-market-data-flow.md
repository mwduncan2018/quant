---
paths:
  - "trading-engine/**/*"
---

# Market Data Flow

Traces IBKR market-data subscription, callback fan-out, per-input readiness
recording, and strategy consumption.

## 1. Workflow Components

| Component | Role in this flow |
| --- | --- |
| `mwd.trading.broker.ibkr.IbkrSessionManager` | Calls `reqMarketDataType` and triggers subscription creation or rebuild after every connect, reconnect, and data-farm event. |
| `mwd.trading.marketdata.MarketDataSubscriptionManager` | Builds the `Contract` per ticker, allocates request IDs, registers consumers, and issues `reqHistoricalData` / `reqMktData`; also cancels and re-issues them. |
| `com.ib.client.EClientSocket` | TWS API socket that carries the outbound subscription requests. |
| `com.ib.client.Contract` | Stock contract (`STK`/`SMART`/`USD`) built by `MarketDataSubscriptionManager.stockContract(String)` and stored on `Stock`. |
| `mwd.trading.broker.ibkr.RequestRegistry` | Stores `reqId -> (ticker, EnumSet<DataConsumer>)` and answers `getTickerFor(int)` / `getConsumersFor(int)`. |
| `mwd.trading.broker.ibkr.RequestRegistry.DataConsumer` | Enum whose members select which handler receives a callback. |
| `mwd.trading.broker.ibkr.EWrapperRaptor` | `EWrapper` implementation that resolves consumers per `reqId` and dispatches each callback to the matching handlers. |
| `mwd.trading.broker.ibkr.TickMap` | Classifies an IBKR tick field number into bid/ask/last/mark/open/close/high/low and the size variants. |
| `mwd.trading.marketdata.PriceTickHandler` | Validates prices, writes them to `Stock`, and records `LAST_PRICE`, `PREVIOUS_CLOSE`, and — from the `RT_VOLUME` string tick — `DAILY_VWAP`. |
| `mwd.trading.marketdata.SizeTickHandler` | Validates sizes and writes bid/ask/last size, intraday volume, and average daily volume to `Stock`. |
| `mwd.trading.marketdata.MinuteBarHandler` | Validates a `Bar`, stores it as the last minute bar, and records `MINUTE_BAR`. |
| `mwd.trading.indicator.MinuteVolumeTracker` | Maintains the per-session completed-bar window, writes both minute-volume figures, and records `MINUTE_VOLUME_BASELINE` once the window is full. |
| `mwd.trading.indicator.SimpleMovingAverageTracker` | Consumes daily history and live tick prices to write the SMA fields. |
| `mwd.trading.indicator.RsiTracker` | Consumes minute history and updates to write the RSI field. |
| `mwd.trading.indicator.IntradayWilderAtrTracker` | Consumes minute history and updates to write the intraday ATR fields. |
| `mwd.trading.indicator.DailyWilderAtrCalculator` | Consumes daily history to write the daily ATR field. |
| `mwd.trading.marketdata.MarketDataInputStore` | Records, per symbol and per `MarketDataInput`, when a usable value was last accepted; implements `MarketDataFreshness`. |
| `mwd.trading.marketdata.MarketDataInput` | Enum of individually tracked inputs with an `Expiry` of `AGED` or `SESSION_SCOPED`. |
| `mwd.trading.marketdata.MarketDataFreshness` | Read-side interface (`isFresh`, `areAllFresh`, `describeUnready`) consumed by strategies. |
| `mwd.trading.domain.Stock` | Holds every market-data value in `volatile` fields plus the `Contract` and last `Bar`. |
| `mwd.trading.state.Blackboard` | Supplies `getStock(String)` and `getNextRequestId()`. |
| `mwd.trading.marketdata.TickByTickManager` | Implements `TickStreamController`; opens and cancels bounded `reqTickByTickData` streams. |
| `mwd.trading.marketdata.TickStreamController` | Interface (`isStreamActive`, `tryRequestStream`, `cancelStream`) through which strategies drive tick-by-tick streams. |
| `mwd.trading.strategy.AbstractStrategy` | Reads freshness through `MarketDataFreshness` before evaluating an entry or repricing exits. |

## 2. Execution Path

### Subscription establishment

1. **Initiating Component:** `IbkrSessionManager.initializeConnectedSession()`
   **Method Invocation:** `client.reqMarketDataType(config.getMarketDataType())`
   **Receiving Component:** `EClientSocket`

2. **Initiating Component:** `IbkrSessionManager.initializeConnectedSession()`
   **Method Invocation:** `subscriptions.initializeIfNeeded()` (first connect) or `subscriptions.resetAfterSocketReconnect()` (subsequent connects)
   **Receiving Component:** `MarketDataSubscriptionManager`

3. **Initiating Component:** `MarketDataSubscriptionManager.initializeIfNeeded()`
   **Method Invocation:** `inputStore.markAllStale()` via `markAllStale()`
   **Receiving Component:** `MarketDataInputStore`

4. **Initiating Component:** `MarketDataSubscriptionManager.initializeIfNeeded()`
   **Method Invocation:** `blackboard.getStock(ticker).setContract(contract)` for each ticker
   **Receiving Component:** `Stock`

5. **Initiating Component:** `MarketDataSubscriptionManager.requestDailyHistory(String, Contract)`
   **Method Invocation:** `registry.register(requestId, ticker, DataConsumer.ATR_DAILY_WILDERS, DataConsumer.MOVING_AVERAGE)` then `client.reqHistoricalData(requestId, contract, "", "1 Y", "1 day", "TRADES", 1, 1, false, null)`
   **Receiving Component:** `RequestRegistry`, `EClientSocket`

6. **Initiating Component:** `MarketDataSubscriptionManager.requestMinuteHistory(String, Contract)`
   **Method Invocation:** `registry.register(requestId, ticker, DataConsumer.ATR_MINUTE_WILDERS, DataConsumer.RSI, DataConsumer.VOLUME, DataConsumer.TICK_BAR)` then `client.reqHistoricalData(requestId, contract, "", "2 D", "1 min", "TRADES", 1, 1, true, null)`
   **Receiving Component:** `RequestRegistry`, `EClientSocket`

7. **Initiating Component:** `MarketDataSubscriptionManager.requestLiveMarketData(String, Contract)`
   **Method Invocation:** `registry.register(requestId, ticker, DataConsumer.TICK_PRICE, DataConsumer.TICK_SIZE, DataConsumer.MOVING_AVERAGE)` then `client.reqMktData(requestId, contract, "233", false, false, null)`
   **Receiving Component:** `RequestRegistry`, `EClientSocket`

### Inbound tick callbacks

8. **Initiating Component:** `EReader` (`IBKR-Reader` thread)
   **Method Invocation:** `EWrapperRaptor.tickPrice(int reqId, int field, double price, TickAttrib attribs)`
   **Receiving Component:** `EWrapperRaptor`

9. **Initiating Component:** `EWrapperRaptor.tickPrice(...)`
   **Method Invocation:** `registry.getConsumersFor(reqId)`
   **Receiving Component:** `RequestRegistry`

10. **Initiating Component:** `EWrapperRaptor.tickPrice(...)`
    **Method Invocation:** `priceTickHandler.onTickPrice(reqId, field, price, attribs)` when the set contains `TICK_PRICE`; `simpleMovingAverageTracker.onTickPrice(reqId, field, price, attribs)` when it contains `MOVING_AVERAGE`
    **Receiving Component:** `PriceTickHandler`, `SimpleMovingAverageTracker`

11. **Initiating Component:** `PriceTickHandler.onTickPrice(...)`
    **Method Invocation:** `registry.getTickerFor(reqId)`, `blackboard.getStock(ticker)`, `tickMap.isBid/isAsk/isLast/isMarkPrice/isOpen/isClose/isHigh/isLow(field)`
    **Receiving Component:** `RequestRegistry`, `Blackboard`, `TickMap`

12. **Initiating Component:** `PriceTickHandler.onTickPrice(...)`
    **Method Invocation:** `stock.setLastPrice(double)` + `inputStore.record(ticker, MarketDataInput.LAST_PRICE)`; `stock.setPreviousClose(double)` + `inputStore.record(ticker, MarketDataInput.PREVIOUS_CLOSE)`; other fields are written without a readiness record. VWAP is absent from this callback: IBKR publishes no VWAP price tick, and it arrives on `tickString` instead
    **Receiving Component:** `Stock`, `MarketDataInputStore`

13. **Initiating Component:** `EWrapperRaptor.tickSize(int reqId, int field, Decimal size)`
    **Method Invocation:** `sizeTickHandler.onTickSize(reqId, field, size)` when the consumer set contains `TICK_SIZE`
    **Receiving Component:** `SizeTickHandler` → `Stock.setBidSize/setAskSize/setLastSize/setIntradayVolume/setAverageDailyVolume`

### Inbound historical-bar callbacks

14. **Initiating Component:** `EReader` (`IBKR-Reader` thread)
    **Method Invocation:** `EWrapperRaptor.historicalData(int reqId, Bar bar)`
    **Receiving Component:** `EWrapperRaptor`

15. **Initiating Component:** `EWrapperRaptor.historicalData(...)`
    **Method Invocation:** per consumer flag — `intradayWilderAtrTracker.onHistoricalData(reqId, bar)`, `dailyWilderAtrCalculator.onHistoricalData(reqId, bar)`, `simpleMovingAverageTracker.onHistoricalData(reqId, bar)`, `rsiTracker.onHistoricalData(reqId, bar)`, `minuteVolumeTracker.onHistoricalData(reqId, bar)`, `minuteBarHandler.onHistoricalData(reqId, bar)`
    **Receiving Component:** the six indicator/market-data consumers

16. **Initiating Component:** `EWrapperRaptor.historicalDataUpdate(int reqId, Bar bar)`
    **Method Invocation:** `intradayWilderAtrTracker.onHistoricalDataUpdate`, `rsiTracker.onHistoricalDataUpdate`, `minuteVolumeTracker.onHistoricalDataUpdate`, `minuteBarHandler.onHistoricalDataUpdate`
    **Receiving Component:** the four update consumers

17. **Initiating Component:** `EWrapperRaptor.historicalDataEnd(int reqId, String startDate, String endDate)`
    **Method Invocation:** `onHistoricalDataEnd(reqId, startDate, endDate)` on `IntradayWilderAtrTracker`, `DailyWilderAtrCalculator`, `SimpleMovingAverageTracker`, `RsiTracker`, `MinuteVolumeTracker`
    **Receiving Component:** the five consumers declaring that request

18. **Initiating Component:** `MinuteBarHandler.updateLastMinuteBar(int, Bar)`
    **Method Invocation:** `registry.getTickerFor(reqId)`, `isUsable(bar)`, `stock.setLastMinuteBar(bar)`, `inputStore.record(ticker, MarketDataInput.MINUTE_BAR)`
    **Receiving Component:** `RequestRegistry`, `Stock`, `MarketDataInputStore`

19. **Initiating Component:** `MinuteVolumeTracker.updateVolume(int, Bar)`
    **Method Invocation:** `stock.setLastMinuteVolume(Decimal)`, then on a minute rollover `window.commit(MinuteBar)` and `publish(String, Stock, VolumeWindow)` → `stock.setAverageLast15MinuteVolume(Decimal)` and `inputStore.record(ticker, MarketDataInput.MINUTE_VOLUME_BASELINE)` when the baseline is non-zero
    **Receiving Component:** `Stock`, `MarketDataInputStore`

### Tick-by-tick streams

20. **Initiating Component:** `AbstractStrategy.evaluateNewEntry(Stock, String)` via the subclass override `evaluateTickStreamNeed(MarketSnapshot, double)`
    **Method Invocation:** `tickStreamController.isStreamActive(String)`, `tickStreamController.tryRequestStream(String)`, `tickStreamController.cancelStream(String)`
    **Receiving Component:** `TickByTickManager`

21. **Initiating Component:** `TickByTickManager.executeRequest(String)`
    **Method Invocation:** `blackboard.getNextRequestId()`, `registry.register(reqId, ticker, DataConsumer.TICK_PRICE, DataConsumer.TICK_SIZE)`, `client.reqTickByTickData(reqId, contract, "BidAsk", 0, false)`
    **Receiving Component:** `Blackboard`, `RequestRegistry`, `EClientSocket`

22. **Initiating Component:** `EWrapperRaptor.tickByTickBidAsk(int reqId, long time, double bidPrice, double askPrice, Decimal bidSize, Decimal askSize, TickAttribBidAsk tickAttribBidAsk)`
    **Method Invocation:** `priceTickHandler.onTickByTickBidAsk(reqId, bidPrice, askPrice)` and `sizeTickHandler.onTickByTickBidAsk(reqId, bidSize, askSize)`
    **Receiving Component:** `PriceTickHandler`, `SizeTickHandler`

23. **Initiating Component:** `EWrapperRaptor.tickByTickAllLast(int reqId, int tickType, long time, double price, Decimal size, TickAttribLast tickAttribLast, String exchange, String specialConditions)`
    **Method Invocation:** `priceTickHandler.onTickByTickAllLast(reqId, price)` (which records `LAST_PRICE`) and `sizeTickHandler.onTickByTickAllLast(reqId, size)`
    **Receiving Component:** `PriceTickHandler`, `SizeTickHandler`

24. **Initiating Component:** `TickByTickManager.cancelStream(String)`
    **Method Invocation:** `client.cancelTickByTickData(reqId)`, `registry.unregister(reqId)`, `activeStreamCount.decrementAndGet()`
    **Receiving Component:** `EClientSocket`, `RequestRegistry`

### Consumption and invalidation

25. **Initiating Component:** `AbstractStrategy.entryInputsReady(Stock)`
    **Method Invocation:** `marketDataFreshness.describeUnready(stock.getTicker(), requiredEntryInputs())`
    **Receiving Component:** `MarketDataInputStore`

26. **Initiating Component:** `AbstractStrategy.evaluateNewEntry(Stock, String)`
    **Method Invocation:** `marketDataFreshness.areAllFresh(stock.getTicker(), requiredEntryInputs())` inside the reservation block
    **Receiving Component:** `MarketDataInputStore`

27. **Initiating Component:** `AbstractStrategy.automatedOrderChangesAllowed(Stock)`
    **Method Invocation:** `marketDataFreshness.describeUnready(stock.getTicker(), requiredManagementInputs())`
    **Receiving Component:** `MarketDataInputStore`

28. **Initiating Component:** `IbkrSessionManager.onConnectionClosed()` / `onError(int, String)` for codes `1100`, `1300`, `502`, `504`, `509`
    **Method Invocation:** `markMarketDataStale()` → `subscriptionManager.markAllStale()` → `inputStore.markAllStale()`
    **Receiving Component:** `MarketDataSubscriptionManager`, `MarketDataInputStore`

29. **Initiating Component:** `IbkrSessionManager.restoreBrokerConnectivity(boolean)` for code `1101`
    **Method Invocation:** `subscriptionManager.resubscribeAfterDataLoss()` → `markAllStale()`, `cancelTrackedRequests()`, then `requestMinuteHistory` and `requestLiveMarketData` per ticker
    **Receiving Component:** `MarketDataSubscriptionManager`

## 3. Data Payloads and State Handoffs

### Objects passed

- `Contract` — built per ticker in `MarketDataSubscriptionManager.stockContract(String)`, stored on `Stock` via `setContract`, and passed to `reqHistoricalData`, `reqMktData`, and `reqTickByTickData`; later read by `BracketOrderExecutor` when placing orders.
- `int reqId` — allocated by `Blackboard.getNextRequestId()` (delegating to `IdManager`) and used as the sole key linking a callback back to a ticker and consumer set.
- `EnumSet<RequestRegistry.DataConsumer>` — returned by `getConsumersFor(int)` and used by `EWrapperRaptor` as the dispatch predicate.
- `com.ib.client.Bar` — passed unmodified from `historicalData` / `historicalDataUpdate` into the trackers; `MinuteBarHandler` stores the reference on `Stock`.
- `com.ib.client.Decimal` — carries every size and volume value from `tickSize` / `tickByTick*` into `Stock`.
- `MarketDataInput` — the enum constant recorded into `MarketDataInputStore` alongside a `Reading(long recordedAtUnixMs, LocalDate sessionDate)`.
- `Optional<String>` — the unready description returned by `describeUnready` to `AbstractStrategy`.
- Two request-ID maps (`liveMarketDataRequests`, `minuteHistoryRequests`) held in `MarketDataSubscriptionManager` and used by `cancelTrackedRequests()`.

### Thread handoffs

| Point | From thread | To thread |
| --- | --- | --- |
| `subscriptions.initializeIfNeeded()` / `resetAfterSocketReconnect()` / `resubscribeAfterDataLoss()` | `IBKR-Session-Lifecycle` | outbound socket write; these methods `Thread.sleep` between symbols on the lifecycle thread |
| Every `EWrapperRaptor` market-data callback | `IBKR-Reader` | handler code runs inline on `IBKR-Reader` |
| `Stock` field writes by handlers, `Stock` field reads by strategies | `IBKR-Reader` | `<Strategy>-Thread`, via `volatile` fields |
| `MarketDataInputStore.record(...)` and `isFresh/areAllFresh/describeUnready` | `IBKR-Reader` | `<Strategy>-Thread`, via `ConcurrentHashMap<String, AtomicReferenceArray<Reading>>` |
| `TickByTickManager.tryRequestStream` / `cancelStream` | `<Strategy>-Thread` | outbound socket write; the resulting callbacks return on `IBKR-Reader` |
| `MinuteVolumeTracker.VolumeWindow` mutation | `IBKR-Reader` | guarded by `synchronized (window)` plus per-method `synchronized` on `VolumeWindow` |

`MarketDataSubscriptionManager` guards one-time initialization with an
`AtomicBoolean` (`initialized`) and tracks request IDs in `ConcurrentHashMap`s, so a
reconnect on the lifecycle thread can cancel requests whose callbacks are still
being dispatched on the reader thread.
