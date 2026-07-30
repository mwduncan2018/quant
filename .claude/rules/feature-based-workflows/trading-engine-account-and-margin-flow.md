---
paths:
  - "trading-engine/**/*"
---

# Account State and Margin-Rate Flow

Traces two related paths: the IBKR account-update subscription that keeps
`Account` current, and the `MarginPacer` what-if order cycle that measures
per-symbol margin rates.

## 1. Workflow Components

| Component | Role in this flow |
| --- | --- |
| `mwd.trading.broker.ibkr.IbkrSessionManager` | Resolves the account from `managedAccounts` and issues `reqAccountUpdates`. |
| `mwd.trading.config.Config` | Supplies `getExpectedAccount()`, read by both the session manager and `BracketOrderExecutor`. |
| `mwd.trading.broker.ibkr.EWrapperRaptor` | Delivers `managedAccounts`, `updateAccountValue`, `updateAccountTime`, `updatePortfolio`, `accountDownloadEnd`, `position`, `positionEnd`, and the what-if `openOrder`. |
| `mwd.trading.broker.ibkr.callback.AccountEventHandler` | Writes account values and portfolio values onto `Account` and `Stock`, and stamps the account refresh time. |
| `mwd.trading.domain.Account` | Holds the account id, `netLiquidation`, `availableFunds`, and the other balances plus `lastRefreshedAtMillis`. |
| `mwd.trading.state.Blackboard` | Owns the `Account`, allocates order IDs for what-if orders, and answers `isAccountCurrentForNewEntry()` / `recordEntrySubmitted(long)`. |
| `mwd.trading.risk.MarginPacer` | `Runnable` that submits a `whatIf` BUY and SELL order per `Stock` on a five-minute cycle. |
| `mwd.trading.lifecycle.TradingGate` | Gates the pacer loop through `allowsNewEntries()`. |
| `com.ib.client.EClientSocket` | Carries `reqAccountUpdates` and the what-if `placeOrder` calls. |
| `com.ib.client.Order` | The 100-share market order with `whatIf(true)` used to price margin. |
| `com.ib.client.OrderState` | Carries `initMarginChange()` back on the what-if `openOrder` callback. |
| `mwd.trading.execution.OrderLifecycleHandler` | Intercepts `order.whatIf()` in `onOpenOrder` and routes it to `processWhatIf`. |
| `mwd.trading.domain.Stock` | Receives `setLongMarginRate` / `setShortMarginRate` and the corresponding verified flags, plus the portfolio fields. |
| `mwd.trading.reconciliation.ReconciliationManager` | Receives `position` and `updatePortfolio` payloads alongside the `Stock` writes. |
| `mwd.trading.strategy.AbstractStrategy` | Reads `blackboard.isAccountCurrentForNewEntry()` before evaluating an entry and calls `recordEntrySubmitted` before submitting one. |
| `mwd.trading.strategy.TwoSigmaDownsideMeanReversionStrategy` | Reads `market.longMarginRateVerified()`, `account.getNetLiquidation()`, `account.getAvailableFunds()`, `market.marginRequirement(String, Decimal, double)`, `market.longMarginRate()`. |

## 2. Execution Path

### Account subscription

1. **Initiating Component:** `EReader` (`IBKR-Reader`)
   **Method Invocation:** `EWrapperRaptor.managedAccounts(String accountsList)`
   **Receiving Component:** `AccountEventHandler.onManagedAccounts(String)` and `IbkrSessionManager.onManagedAccounts(String)`

2. **Initiating Component:** `AccountEventHandler.onManagedAccounts(String)`
   **Method Invocation:** `blackboard.getAccount().setAccountId(String)` using the first comma-separated entry
   **Receiving Component:** `Account`

3. **Initiating Component:** `IbkrSessionManager.onManagedAccounts(String)`
   **Method Invocation:** compares `config.getExpectedAccount()` against the managed list; a mismatch or an empty list calls `tradingGate.requireManualIntervention(String)`
   **Receiving Component:** `TradingGate`

4. **Initiating Component:** `IbkrSessionManager.onManagedAccounts(String)`
   **Method Invocation:** `client.reqAccountUpdates(true, account)` when `client.isConnected()`
   **Receiving Component:** `EClientSocket`

5. **Initiating Component:** `EWrapperRaptor.updateAccountValue(String key, String value, String currency, String accountName)`
   **Method Invocation:** `accountEventHandler.onUpdateAccountValue(...)`, which skips any currency other than `USD` or `BASE` and switches on `NetLiquidation`, `TotalCashValue`, `SettledCash`, `BuyingPower`, `AvailableFunds`, `ExcessMargin`, `RealizedPnL`, `UnrealizedPnL`, `Cushion`
   **Receiving Component:** `Account`

6. **Initiating Component:** `EWrapperRaptor.updateAccountTime(String timeStamp)`
   **Method Invocation:** `accountEventHandler.onUpdateAccountTime(String)` → `blackboard.getAccount().setLastRefreshedAtMillis(System.currentTimeMillis())`
   **Receiving Component:** `Account`

7. **Initiating Component:** `EWrapperRaptor.updatePortfolio(Contract contract, Decimal position, double marketPrice, double marketValue, double averageCost, double unrealizedPNL, double realizedPNL, String accountName)`
   **Method Invocation:** `accountEventHandler.onUpdatePortfolio(...)` → `reconciliationManager.onLivePortfolioPosition(String, Contract, Decimal, double)`, then `stock.setPositionSize`, `setAverageCost`, `setMarketValue`, `setRealizedPnL`, `setUnrealizedPnl`
   **Receiving Component:** `ReconciliationManager`, `Stock`

8. **Initiating Component:** `EWrapperRaptor.position(String account, Contract contract, Decimal pos, double avgCost)`
   **Method Invocation:** `accountEventHandler.onPosition(...)` → `reconciliationManager.onPosition(...)`, `blackboard.getStock(contract.symbol()).setPositionSize(Decimal)`, `setAverageCost(double)`
   **Receiving Component:** `ReconciliationManager`, `Stock`

9. **Initiating Component:** `EWrapperRaptor.accountDownloadEnd(String accountName)`
   **Method Invocation:** `accountEventHandler.onAccountDownloadEnd(String)`
   **Receiving Component:** `AccountEventHandler` (logging only)

### Entry-sizing gate

10. **Initiating Component:** `AbstractStrategy.evaluateNewEntry(Stock, String)`
    **Method Invocation:** `blackboard.isAccountCurrentForNewEntry()`, which requires `account.getLastRefreshedAtMillis() > lastEntrySubmittedAtMillis`
    **Receiving Component:** `Blackboard`, `Account`

11. **Initiating Component:** `AbstractStrategy.evaluateNewEntry(Stock, String)`
    **Method Invocation:** `blackboard.recordEntrySubmitted(clock.millis())` immediately before `placeTripleThreat`
    **Receiving Component:** `Blackboard` (`AtomicLong.updateAndGet`)

12. **Initiating Component:** the concrete strategy's `calculateTotalQuantity(MarketSnapshot, double, double)`
    **Method Invocation:** `blackboard.getAccount()`, `account.getNetLiquidation()`, `account.getAvailableFunds()`, `market.marginRequirement("BUY", Decimal, double)`, `market.longMarginRate()` — the rate is the one captured in the snapshot, so the quantity matches the rate the direction was verified against
    **Receiving Component:** `Account`, `MarketSnapshot`

### Margin what-if cycle (`Margin-Pacer-Thread`)

13. **Initiating Component:** `Main.main(String[])`
    **Method Invocation:** `new MarginPacer(blackboard, sessionManager.client(), tradingGate)` then `new Thread(marginPacer, "Margin-Pacer-Thread").start()`
    **Receiving Component:** `MarginPacer`

14. **Initiating Component:** `MarginPacer.run()`
    **Method Invocation:** `client.isConnected()` and `tradingGate.allowsNewEntries()`; when either is false it sleeps 1000 ms and re-loops
    **Receiving Component:** `EClientSocket`, `TradingGate`

15. **Initiating Component:** `MarginPacer.run()`
    **Method Invocation:** `blackboard.forEachStock(Consumer<Stock>)`, invoking `requestWhatIf(stock, "BUY")` and `requestWhatIf(stock, "SELL")` with a 250 ms sleep after each, then `Thread.sleep(Duration.ofMinutes(5))`
    **Receiving Component:** `Blackboard`, `MarginPacer`

16. **Initiating Component:** `MarginPacer.requestWhatIf(Stock, String)`
    **Method Invocation:** builds a `Contract` (`STK`/`SMART`/`USD`) and an `Order` with `orderType("MKT")`, `totalQuantity(Decimal.get(100))`, `whatIf(true)`; then `blackboard.getNextOrderId()` and `client.placeOrder(reqId, contract, mOrder)`
    **Receiving Component:** `Blackboard`, `EClientSocket`

17. **Initiating Component:** `EWrapperRaptor.openOrder(int orderId, Contract contract, Order order, OrderState orderState)`
    **Method Invocation:** `orderLifecycleHandler.onOpenOrder(...)`, which tests `order.whatIf()` first and calls `processWhatIf(Contract, Order, OrderState)` before any reconciliation or bracket resolution
    **Receiving Component:** `OrderLifecycleHandler`

18. **Initiating Component:** `OrderLifecycleHandler.processWhatIf(Contract, Order, OrderState)`
    **Method Invocation:** `blackboard.getStock(contract.symbol())`, `parseMarginChange(orderState.initMarginChange())`, `100 * stock.getLastPrice()` as the notional; a null, blank, unparseable, non-positive, or `Double.MAX_VALUE` margin figure returns without writing, as does a non-positive notional
    **Receiving Component:** `Blackboard`, `Stock`, `OrderState`

19. **Initiating Component:** `OrderLifecycleHandler.processWhatIf(Contract, Order, OrderState)`
    **Method Invocation:** `order.action() == Action.BUY` (`com.ib.client.Types.Action`) → `stock.setLongMarginRate(double)` + `stock.setLongMarginRateVerified(true)`; otherwise `stock.setShortMarginRate(double)` + `stock.setShortMarginRateVerified(true)`
    **Receiving Component:** `Stock`

20. **Initiating Component:** `TwoSigmaDownsideMeanReversionStrategy.isEntryConditionMet(MarketSnapshot)`
    **Method Invocation:** `market.longMarginRateVerified()`; false blocks the entry
    **Receiving Component:** `MarketSnapshot`

21. **Initiating Component:** `Trading-Engine-Shutdown` hook thread
    **Method Invocation:** `marginPacerThread.interrupt()`
    **Receiving Component:** `MarginPacer`

## 3. Data Payloads and State Handoffs

### Objects passed

- `String accountsList` — the raw comma-separated `managedAccounts` payload, split independently by `AccountEventHandler.onManagedAccounts` and `IbkrSessionManager.accounts(String)`.
- `Account` — a single instance created inside the `Blackboard` constructor and shared by the handler (writer) and every strategy (reader).
- `long lastRefreshedAtMillis` on `Account` and `AtomicLong lastEntrySubmittedAtMillis` on `Blackboard` — the pair compared by `isAccountCurrentForNewEntry()`.
- What-if `com.ib.client.Order` — a 100-share `MKT` order with `whatIf(true)`, submitted under an ID from `getNextOrderId()`, i.e. drawn from the same counter as real orders.
- `OrderState.initMarginChange()` — a `String` parsed to `double` and divided by the notional to produce the stored rate.
- `Stock.longMarginRate` / `shortMarginRate` / `longMarginRateVerified` / `shortMarginRateVerified` — `volatile` fields defaulting to `1.0` and `false`; the verified flags are set per direction, never together.
- `Decimal positionSize` on `Stock` — written from both `position` and `updatePortfolio` and read by `BracketOrderExecutor.updateTripleThreatExits` to derive the exit direction.

### Thread handoffs

| Handoff | Detail |
| --- | --- |
| `IBKR-Reader` → `<Strategy>-Thread` | `Account` balances, `Account.lastRefreshedAtMillis`, and the `Stock` margin fields are written on the reader thread and read on the strategy threads. |
| `<Strategy>-Thread` → `IBKR-Reader` | `Blackboard.recordEntrySubmitted(long)` is written on a strategy thread and compared against the reader-written refresh timestamp; the write uses `AtomicLong.updateAndGet(previous -> Math.max(previous, atMillis))`. |
| `Margin-Pacer-Thread` → TWS | `requestWhatIf` calls `EClientSocket.placeOrder` directly from the pacer thread. |
| TWS → `IBKR-Reader` | The what-if response returns as an `openOrder` callback and is handled inline by `OrderLifecycleHandler.processWhatIf`. |
| `Margin-Pacer-Thread` ↔ `<Strategy>-Thread` on the ID counter | Both call `Blackboard.getNextOrderId()`, backed by `IdManager`'s `AtomicInteger.getAndIncrement()`. |
| `Margin-Pacer-Thread` iteration | `blackboard.forEachStock(...)` iterates the `ConcurrentHashMap` values while the reader thread may be inserting new `Stock` entries via `getStock(String)`. |
| Shutdown hook thread → `Margin-Pacer-Thread` | `interrupt()`, which the pacer's `Thread.sleep` calls convert into loop termination. |

`Account` and every margin-related `Stock` field are `volatile`, which is what
makes the reader-thread writes visible to the strategy threads without further
synchronization.
