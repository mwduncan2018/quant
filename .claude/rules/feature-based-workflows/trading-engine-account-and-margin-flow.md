---
paths:
  - "trading-engine/**/*"
---

# Account State and Margin-Rate Flow

Traces two related paths: the IBKR account-update subscription that keeps
`Account` current, and the file-backed margin rates that entry sizing multiplies
against.

Margin rates used to be measured, by a `MarginPacer` what-if order per symbol per
direction on a five-minute cycle. IBKR asks for at most one what-if per minute and
one per ten real order submissions; that loop ran at roughly twelve a minute, some
four and a half thousand per session, and cancelled none of them. It is gone. The
rates are read off IBKR's public margin calculator into
`data/universe-reference.csv` and loaded once at startup.

## 1. Workflow Components

| Component | Role in this flow |
| --- | --- |
| `mwd.trading.broker.ibkr.IbkrSessionManager` | Resolves the account from `managedAccounts` and issues `reqAccountUpdates`. |
| `mwd.trading.config.Config` | Supplies `getExpectedAccount()`, read by both the session manager and `BracketOrderExecutor`. |
| `mwd.trading.broker.ibkr.EWrapperRaptor` | Delivers `managedAccounts`, `updateAccountValue`, `updateAccountTime`, `updatePortfolio`, `accountDownloadEnd`, `position`, and `positionEnd`. |
| `mwd.trading.broker.ibkr.callback.AccountEventHandler` | Writes account values and portfolio values onto `Account` and `Stock`, and stamps the account refresh time. |
| `mwd.trading.domain.Account` | Holds the account id, `netLiquidation`, `availableFunds`, and the other balances plus `lastRefreshedAtMillis`. |
| `mwd.trading.state.Blackboard` | Owns the `Account` and answers `isAccountCurrentForNewEntry()` / `recordEntrySubmitted(long)`. |
| `mwd.trading.risk.UniverseReference` | Loads the per-ticker sectors and margin rates, and answers `marginRate(String, boolean)`. |
| `mwd.trading.risk.MarginMethodology` | Selects which pair of rates in the table applies, `REG_T` or `PORTFOLIO`. |
| `mwd.trading.risk.ConcentrationLimits` | Caps the sized quantity against per-ticker and per-sector exposure. |
| `com.ib.client.EClientSocket` | Carries `reqAccountUpdates`. |
| `mwd.trading.domain.Stock` | Receives the portfolio fields. It no longer holds margin rates. |
| `mwd.trading.reconciliation.ReconciliationManager` | Receives `position` and `updatePortfolio` payloads alongside the `Stock` writes. |
| `mwd.trading.strategy.AbstractStrategy` | Reads `blackboard.isAccountCurrentForNewEntry()` before evaluating an entry and calls `recordEntrySubmitted` before submitting one. |
| `mwd.trading.strategy.TwoSigmaDownsideMeanReversionStrategy` | Reads `account.getNetLiquidation()`, `account.getAvailableFunds()`, and `universeReference.marginRate(market.ticker(), true)`. |

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
   **Method Invocation:** `accountEventHandler.onUpdateAccountValue(...)`, which skips any currency other than `USD` or `BASE` and switches on `NetLiquidation`, `TotalCashValue`, `SettledCash`, `BuyingPower`, `AvailableFunds`, `ExcessLiquidity`, `RealizedPnL`, `UnrealizedPnL`, `Cushion`, and routes every other key to `reportUnhandled(String)`. `ExcessLiquidity` is the tag IBKR sends; the engine previously matched `ExcessMargin`, which is not one, so the field sat at zero for the life of the process
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
    **Method Invocation:** `blackboard.getAccount()`, `account.getNetLiquidation()`, `account.getAvailableFunds()`, then `universeReference.marginRate(market.ticker(), isLong)`; the requirement is `idealShareCount × entryPrice × marginRate`, and exceeding `availableFunds` recomputes an affordable share count at the same rate
    **Receiving Component:** `Account`, `UniverseReference`

12a. **Initiating Component:** `AbstractStrategy.evaluateNewEntry(Stock, String)`
    **Method Invocation:** `concentrationLimits.allowedQuantity(ticker, entryPrice, totalQuantity)`; a smaller allowance is applied through `trimToTotal(...)`, and a total below `MIN_POSITION_NOTIONAL` rolls the reservation back
    **Receiving Component:** `ConcentrationLimits`

### Margin-rate resolution (main thread, once at startup)

13. **Initiating Component:** `Main.main(String[])`
    **Method Invocation:** `UniverseReference.load(Path.of(config.getUniverseReferencePath()), config.getMarginMethodology(), config.getDefaultLongMarginRate(), config.getDefaultShortMarginRate())` — the regime is already a `MarginMethodology`, parsed and validated when `EnvPropConfig` was constructed
    **Receiving Component:** `UniverseReference`

14. **Initiating Component:** `Main.main(String[])`
    **Method Invocation:** `universeReference.describeCoverage(Set.copyOf(marketDataSymbols), LocalDate.now())` logged line by line — naming every traded symbol with no row and every symbol falling back to the conservative default
    **Receiving Component:** `Logger`

15. **Initiating Component:** `Main.main(String[])`
    **Method Invocation:** `universeReference.ageInDays(LocalDate.now())` warned past `config.getUniverseReferenceMaxAgeDays()`; IBKR reprices margin without notice, so an old table is a silent sizing error
    **Receiving Component:** `Logger`

16. **Initiating Component:** `Main.main(String[])`
    **Method Invocation:** `new ConcentrationLimits(blackboard, universeReference, config.getMaxTickerExposurePercent(), config.getMaxSectorExposurePercent(), config.getMinPositionNotional())`, then passed to all three strategies
    **Receiving Component:** `ConcentrationLimits`

17. **Initiating Component:** `BlackboardMonitor.updateDashboardData()` (monitor refresh thread)
    **Method Invocation:** `universeReference.marginRate(stock.getTicker(), true)` and `(..., false)` for the `L-Margin` and `S-Margin` columns. These now echo configuration rather than reporting what IBKR charged; the stale-date warning is what replaces that signal
    **Receiving Component:** `UniverseReference`

## 3. Data Payloads and State Handoffs

### Objects passed

- `String accountsList` — the raw comma-separated `managedAccounts` payload, split independently by `AccountEventHandler.onManagedAccounts` and `IbkrSessionManager.accounts(String)`.
- `Account` — a single instance created inside the `Blackboard` constructor and shared by the handler (writer) and every strategy (reader).
- `long lastRefreshedAtMillis` on `Account` and `AtomicLong lastEntrySubmittedAtMillis` on `Blackboard` — the pair compared by `isAccountCurrentForNewEntry()`.
- `UniverseReference.TickerReference` — immutable record `(ticker, sector, regTLong, regTShort, portfolioLong, portfolioShort)`; a blank rate is `NaN` and falls back to the conservative default.
- `ConcentrationLimits.Exposure` — immutable record `(netLiquidation, byTicker, bySector, unsectored)`, rebuilt on each `allowedQuantity` call rather than cached, because the reader thread is writing the fields it sums.
- `Decimal positionSize` on `Stock` — written from both `position` and `updatePortfolio` and read by `BracketOrderExecutor.updateTripleThreatExits` to derive the exit direction.

### Thread handoffs

| Handoff | Detail |
| --- | --- |
| `IBKR-Reader` → `<Strategy>-Thread` | `Account` balances and `Account.lastRefreshedAtMillis` are written on the reader thread and read on the strategy threads. |
| `<Strategy>-Thread` → `IBKR-Reader` | `Blackboard.recordEntrySubmitted(long)` is written on a strategy thread and compared against the reader-written refresh timestamp; the write uses `AtomicLong.updateAndGet(previous -> Math.max(previous, atMillis))`. |
| main thread → every strategy thread | `UniverseReference` is loaded once and is immutable afterwards, so the rates need no synchronization to publish. |
| `<Strategy>-Thread` iteration | `ConcentrationLimits.currentExposure()` walks `blackboard.forEachStock(...)` on the strategy thread while the reader thread may be writing `positionSize`, `lastPrice`, and bracket quantities. Each field is `volatile`, so the sum is of individually current values rather than one instant — over-counting a fill in flight is the conservative direction. |

`Account` fields are `volatile`, which is what makes the reader-thread writes
visible to the strategy threads without further synchronization. The margin rates
need none: they are immutable configuration published once at startup.
