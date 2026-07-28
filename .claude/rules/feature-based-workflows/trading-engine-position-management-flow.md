---
paths:
  - "trading-engine/**/*"
---

# Open-Position Management and Exit-Modification Flow

Traces the path taken once a `Stock` reaches `PositionState.OPEN`: the strategy
reprices its `ExitSlice` values, `BracketOrderExecutor` re-transmits only the
changed legs, and the broker callbacks fold the result back into `BracketOrder`.

## 1. Workflow Components

| Component | Role in this flow |
| --- | --- |
| `mwd.trading.strategy.AbstractStrategy` | Gates management on state, ownership, and management-input freshness, then calls `updateExits(...)`. |
| `mwd.trading.strategy.TwoSigmaDownsideMeanReversionStrategy` | Overrides `manageOpenPosition(Stock)` with a two-slice ripcord / break-even / VWAP-step routine. |
| `mwd.trading.strategy.OneSigmaDownsideMeanReversionStrategy` | Overrides `manageOpenPosition(Stock)` and calls `updateExits` with the current VWAP as the take-profit. |
| `mwd.trading.strategy.OneSigmaUpsideMeanReversionStrategy` | Overrides `manageOpenPosition(Stock)` and calls `updateExits` with the current VWAP as the take-profit. |
| `mwd.trading.execution.BracketOrderGateway` | Declares `updateTripleThreatExits(Stock, BracketOrder, BracketOrder.ExitSlice, double, double, long)`. |
| `mwd.trading.execution.BracketOrderExecutor` | Compares requested values against the slice, builds only the changed `Order` legs, and re-sends them. |
| `mwd.trading.execution.BracketOrder` | Supplies `getSlices()`, `getEntryPrice()`, `getUpdateCount()`, `getAccount()`, `getParentOrderId()`, `getLegStates()`, `incrementUpdateCount()`. |
| `mwd.trading.execution.BracketOrder.ExitSlice` | Holds and receives the current take-profit price, stop-loss price, time exit, `isFilled` flag, and `lastModificationTime`. |
| `mwd.trading.execution.BracketOrder.OrderLegState` | Supplies the stored `orderReference` that `orderReferenceFor(BracketOrder, int)` re-attaches to a modification. |
| `mwd.trading.domain.Stock` | Supplies `getState()`, `getActiveBracket()`, `getPositionSize()`, `getLastPrice()`, `getDailyVWAP()`, `getPreviousClose()`, `getContract()`. |
| `mwd.trading.state.Blackboard` | Answers `isPositionOwnedBy(String, String)` and receives `setSystemHalted(boolean)` on a halt. |
| `mwd.trading.lifecycle.TradingGate` | Answers `allowsAutomatedOrderChanges()` in both the strategy and the executor. |
| `mwd.trading.marketdata.MarketDataFreshness` | Answers `describeUnready(String, Set<MarketDataInput>)` for the narrower management input set. |
| `mwd.trading.optionsproxy.OptionsIndicatorStore` | Answers `lastKnownImpliedMove(String)`, which prices every implied-move-dependent adjustment. |
| `com.ib.client.EClientSocket` | Carries the modification `placeOrder` calls and answers `isConnected()`. |
| `com.ib.client.Order` | Rebuilt modification leg carrying the original order ID and order reference. |
| `mwd.trading.execution.OrderLifecycleHandler` | Receives the resulting `openOrder` / `orderStatus` callbacks and re-validates each slice. |
| `mwd.trading.persistence.JsonTradingStateStore` | Records the resulting broker updates. |

## 2. Execution Path

1. **Initiating Component:** `AbstractStrategy.executeLifecycle(Stock)`
   **Method Invocation:** on `Stock.PositionState.OPEN`, verifies `strategyId.equals(blackboard.getPositionOwner(ticker))` then calls `automatedOrderChangesAllowed(Stock)`
   **Receiving Component:** `Blackboard`, `AbstractStrategy`

2. **Initiating Component:** `AbstractStrategy.automatedOrderChangesAllowed(Stock)`
   **Method Invocation:** `tradingGate.allowsAutomatedOrderChanges()` then `marketDataFreshness.describeUnready(stock.getTicker(), requiredManagementInputs())`
   **Receiving Component:** `TradingGate`, `MarketDataInputStore`

3. **Initiating Component:** `AbstractStrategy.executeLifecycle(Stock)`
   **Method Invocation:** `manageOpenPosition(Stock)`
   **Receiving Component:** the concrete strategy

4. **Initiating Component:** the concrete strategy's `manageOpenPosition(Stock)`
   **Method Invocation:** `stock.getActiveBracket()`, `bracketOrder.getSlices()`, `stock.getLastPrice()`, `stock.getDailyVWAP()`, `bracketOrder.getEntryPrice()`, `slice.getStopLossPrice()`, `slice.getTakeProfitPrice()`, `slice.isFilled()`
   **Receiving Component:** `Stock`, `BracketOrder`, `BracketOrder.ExitSlice`

5. **Initiating Component:** the concrete strategy's `manageOpenPosition(Stock)`
   **Method Invocation:** `optionsIndicatorStore.lastKnownImpliedMove(stock.getTicker())`; an empty `OptionalDouble` returns without touching the resting exits
   **Receiving Component:** `OptionsIndicatorStore`

6. **Initiating Component:** the concrete strategy's `manageOpenPosition(Stock)`
   **Method Invocation:** `updateExits(Stock stock, BracketOrder bracketOrder, BracketOrder.ExitSlice exitSlice, double takeProfitPrice, double stopLossPrice, long timeExitValue)`
   **Receiving Component:** `AbstractStrategy`

7. **Initiating Component:** `AbstractStrategy.updateExits(...)`
   **Method Invocation:** re-checks `stock.getState().get() == Stock.PositionState.OPEN`, `blackboard.isPositionOwnedBy(ticker, strategyId())`, `automatedOrderChangesAllowed(Stock)`
   **Receiving Component:** `Stock`, `Blackboard`, `TradingGate`, `MarketDataInputStore`

8. **Initiating Component:** `AbstractStrategy.updateExits(...)`
   **Method Invocation:** `bracketOrderGateway.updateTripleThreatExits(stock, bracketOrder, exitSlice, takeProfitPrice, stopLossPrice, timeExitValue)`
   **Receiving Component:** `BracketOrderExecutor`

9. **Initiating Component:** `BracketOrderExecutor.updateTripleThreatExits(...)`
   **Method Invocation:** `tradingGate.allowsAutomatedOrderChanges()`; null bracket or slice returns; `client.isConnected()` false calls `halt(String)` and returns
   **Receiving Component:** `TradingGate`, `EClientSocket`

10. **Initiating Component:** `BracketOrderExecutor.updateTripleThreatExits(...)`
    **Method Invocation:** `exitSlice.getOneCancelsAllGroup()`, `exitSlice.getQuantity()`, `bracketOrder.getAccount()`, `stock.getPositionSize().value().signum()`; a zero sign calls `halt(String)` and returns
    **Receiving Component:** `BracketOrder.ExitSlice`, `BracketOrder`, `Stock`

11. **Initiating Component:** `BracketOrderExecutor.updateTripleThreatExits(...)`
    **Method Invocation:** for each of stop-loss, take-profit, and time-exit, compares the requested value with `exitSlice.getStopLossPrice()` / `getTakeProfitPrice()` / `getTimeExit()`; only a difference builds an `Order` with `orderId(exitSlice.get*OrderId())`, `parentId(bracketOrder.getParentOrderId())`, `orderRef(orderReferenceFor(bracketOrder, apiOrderId))`, `ocaGroup`, `ocaType(2)`, `transmit(false)`
    **Receiving Component:** `com.ib.client.Order`

12. **Initiating Component:** `BracketOrderExecutor.updateTripleThreatExits(...)`
    **Method Invocation:** for each built leg, `exitSlice.setStopLossPrice(double)` / `setTakeProfitPrice(double)` / `setTimeExit(long)` and `bracketOrder.incrementUpdateCount()`
    **Receiving Component:** `BracketOrder.ExitSlice`, `BracketOrder`

13. **Initiating Component:** `BracketOrderExecutor.orderReferenceFor(BracketOrder, int)`
    **Method Invocation:** `bracketOrder.getLegStates()` filtered by `OrderLegState.getApiOrderId()`; a missing reference throws `IllegalStateException`
    **Receiving Component:** `BracketOrder`, `BracketOrder.OrderLegState`

14. **Initiating Component:** `BracketOrderExecutor.updateTripleThreatExits(...)`
    **Method Invocation:** an empty `updateBundle` returns; otherwise `updateBundle.get(updateBundle.size() - 1).transmit(true)` then `client.placeOrder(individualOrder.orderId(), stock.getContract(), individualOrder)` per leg
    **Receiving Component:** `EClientSocket`

15. **Initiating Component:** `BracketOrderExecutor.updateTripleThreatExits(...)`
    **Method Invocation:** on `RuntimeException`, `halt(String)` then rethrow
    **Receiving Component:** `Blackboard.setSystemHalted(true)`, `TradingGate.requireManualIntervention(String)`

16. **Initiating Component:** the concrete strategy's `manageOpenPosition(Stock)` (two-slice variant)
    **Method Invocation:** `sliceB.setLastModificationTime(System.currentTimeMillis())` after a successful VWAP-step update
    **Receiving Component:** `BracketOrder.ExitSlice`

17. **Initiating Component:** `EReader` (`IBKR-Reader`)
    **Method Invocation:** `EWrapperRaptor.openOrder(int, Contract, Order, OrderState)` → `OrderLifecycleHandler.onOpenOrder(...)` → `validateExitSlice(int, Order, BracketOrder)`
    **Receiving Component:** `OrderLifecycleHandler`

18. **Initiating Component:** `OrderLifecycleHandler.validateExitSlice(int, Order, BracketOrder)`
    **Method Invocation:** compares `order.auxPrice()` against `exitSlice.getStopLossPrice()`, `order.lmtPrice()` against `exitSlice.getTakeProfitPrice()`, `order.goodAfterTime()` against `bracketOrder.getGoodAfterTimeString(exitSlice.getTimeExit())`, and `order.totalQuantity()` against `exitSlice.getQuantity()`; any mismatch calls `halt(String)`
    **Receiving Component:** `BracketOrder.ExitSlice`, `BracketOrder`, `TradingGate`, `Blackboard`

19. **Initiating Component:** `OrderLifecycleHandler.onOrderStatus(...)`
    **Method Invocation:** `bracketOrder.getSliceByOrderId(int)` → `setFilled(true)` when the exit leg reports `"Filled"`; all slices filled → `setStatus(BracketOrder.Status.FILLED)` + `completeConfirmedFlat(BracketOrder)`
    **Receiving Component:** `BracketOrder.ExitSlice`, `BracketOrder`, `Stock`, `Blackboard`

20. **Initiating Component:** `OrderLifecycleHandler.persist(BracketOrder)`
    **Method Invocation:** `stateStore.recordBrokerUpdate(BracketOrder, String)`
    **Receiving Component:** `JsonTradingStateStore`

## 3. Data Payloads and State Handoffs

### Objects passed

- `Stock` — passed by reference into `updateTripleThreatExits` so the executor can read `getPositionSize()` for the exit direction and `getContract()` for `placeOrder`.
- `BracketOrder.ExitSlice` — the single mutable object shared between the strategy that proposes prices and the executor that both transmits and writes them back; its `takeProfitPrice`, `stopLossPrice`, `timeExit`, `isFilled`, and `lastModificationTime` fields are plain (non-volatile).
- `double newTakeProfitPrice`, `double newStopLossPrice`, `long newExitTime` — the proposed values; each is compared against the slice before any order is built.
- `List<Order> updateBundle` — only the legs whose values changed, with `transmit(true)` on the last element.
- `String orderRef` — recovered from `OrderLegState` so a modification carries the same `TE|<tradeId>|<role>` identity as the original leg.
- `OptionalDouble` from `OptionsIndicatorStore.lastKnownImpliedMove(String)` — the retained value used to price break-even triggers and step targets.
- `AtomicInteger updateRequestCount` inside `BracketOrder` — incremented per transmitted leg and read back by the two-slice strategy as `bracketOrder.getUpdateCount() - 7`.

### Thread handoffs

| Handoff | Detail |
| --- | --- |
| `<Strategy>-Thread` → TWS | `manageOpenPosition` → `updateExits` → `updateTripleThreatExits` → `EClientSocket.placeOrder` all execute on the strategy thread. |
| `<Strategy>-Thread` writes slice state | `ExitSlice.setStopLossPrice/setTakeProfitPrice/setTimeExit` are written by the executor on the strategy thread. |
| `IBKR-Reader` reads slice state | `OrderLifecycleHandler.validateExitSlice` reads the same `ExitSlice` fields on the reader thread to compare them against the broker echo. |
| `IBKR-Reader` writes slice state | `ExitSlice.setFilled(true)` is written by `onOrderStatus` and `onCompletedOrder` on the reader thread and read by `manageOpenPosition` on the strategy thread. |
| `IBKR-Reader` → `<Strategy>-Thread` | A completed bracket set to `FILLED` plus `Stock.state` set to `FLAT` by `completeConfirmedFlat` is observed by the strategy on its next poll, which then runs `handleFlatWithLocalOwnership` / `cleanupOwnedLifecycle`. |
| Either thread → journal | `JsonTradingStateStore` is `synchronized`; the strategy thread reaches it only through the entry path, the reader thread through `persist`. |
