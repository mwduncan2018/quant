---
paths:
  - "trading-engine/**/*"
---

# Package `mwd.trading.broker.ibkr.callback`

Sources:
- `trading-engine/trading-engine/src/main/java/mwd/trading/broker/ibkr/callback/AccountEventHandler.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/broker/ibkr/callback/BrokerTimeHandler.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/broker/ibkr/callback/IbkrErrorHandler.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/broker/ibkr/callback/NextValidIdHandler.java`

---

## `AccountEventHandler`

`public class AccountEventHandler`

### 1. Class/Interface Responsibilities

Receives the IBKR account, position, and portfolio callbacks and writes their values onto the `Account` and `Stock` objects held by `Blackboard` while forwarding position and portfolio events to `ReconciliationManager`.

`onUpdateAccountValue` matches tags by exact string, so a name IBKR never sends is indistinguishable from a value that never arrives: the field stays at zero and nothing says so. That is how `ExcessLiquidity` came to be read as `ExcessMargin`, which is not an IBKR tag. The `default ->` arm now routes every unread key through `reportUnhandled(String)`, which names it once at DEBUG. The authoritative list is `com.ib.controller.AccountSummaryTag`, but that enum describes `reqAccountSummary` and this engine subscribes with `reqAccountUpdates`, so which of those tags actually arrive is a question only a live session answers.

### 2. Injected Dependencies

Constructor: `public AccountEventHandler(Blackboard blackboard, ReconciliationManager reconciliationManager)`

| Parameter | Exact type |
| --- | --- |
| `blackboard` | `mwd.trading.state.Blackboard` |
| `reconciliationManager` | `mwd.trading.reconciliation.ReconciliationManager` |

Both are `Objects.requireNonNull`-checked and stored in `private final` fields.

### 3. Method Signatures

```java
public AccountEventHandler(Blackboard blackboard, ReconciliationManager reconciliationManager)

public void onManagedAccounts(String accountsList)
public void onUpdateAccountTime(String timeStamp)
public void onUpdateAccountValue(String key, String value, String currency, String accountName)
public void onPosition(String account, Contract contract, Decimal pos, double avgCost)
public void onPositionEnd()
public void onAccountDownloadEnd(String accountName)
public void onUpdatePortfolio(Contract contract, Decimal position, double marketPrice, double marketValue, double averageCost, double unrealizedPNL, double realizedPNL, String accountName)

private void reportUnhandled(String key)
```

### 4. Global State Interactions

**Concurrent collections**

| Field | Declared type |
| --- | --- |
| `reportedUnhandledKeys` | `Set<String>` = `ConcurrentHashMap.newKeySet()` |

Also reaches the `ConcurrentHashMap<String, Stock>` inside `Blackboard` via `getStock(String)` in `onPosition` and `onUpdatePortfolio`.

**Centralized state objects (`Blackboard`)**

| Method | Interaction |
| --- | --- |
| `onManagedAccounts(String)` | Mutates `blackboard.getAccount().setAccountId(String)` |
| `onUpdateAccountTime(String)` | Mutates `blackboard.getAccount().setLastRefreshedAtMillis(long)` |
| `onUpdateAccountValue(String, String, String, String)` | Reads `blackboard.getAccount()`; mutates `setNetLiquidation`, `setTotalCashValue`, `setSettledCash`, `setBuyingPower`, `setAvailableFunds`, `setExcessLiquidity`, `setRealizedPnL`, `setUnrealizedPnL`, `setCushion` |
| `onPosition(String, Contract, Decimal, double)` | Mutates `blackboard.getStock(symbol)` via `setPositionSize`, `setAverageCost`; calls `reconciliationManager.onPosition(...)` |
| `onPositionEnd()` | Calls `reconciliationManager.onPositionEnd()` |
| `onUpdatePortfolio(...)` | Mutates `blackboard.getStock(symbol)` via `setPositionSize`, `setAverageCost`, `setMarketValue`, `setRealizedPnL`, `setUnrealizedPnl`; calls `reconciliationManager.onLivePortfolioPosition(...)` |
| `onAccountDownloadEnd(String)` | No state interaction; logging only |
| `reportUnhandled(String)` | Mutates `reportedUnhandledKeys` (`add`), and logs the tag name at DEBUG the first time each one arrives |

---

## `BrokerTimeHandler`

`public class BrokerTimeHandler`

### 1. Class/Interface Responsibilities

Receives the IBKR `currentTime` callback and sets the clock offset on the `TimeManager` held by `Blackboard`.

### 2. Injected Dependencies

Constructor: `public BrokerTimeHandler(Blackboard blackboard)`

| Parameter | Exact type |
| --- | --- |
| `blackboard` | `mwd.trading.state.Blackboard` |

### 3. Method Signatures

```java
public BrokerTimeHandler(Blackboard blackboard)
public Blackboard getBlackboard()
public void onCurrentTime(long time)
```

### 4. Global State Interactions

**Concurrent collections**

None.

**Centralized state objects (`Blackboard`)**

| Method | Interaction |
| --- | --- |
| `getBlackboard()` | Returns the `Blackboard` reference |
| `onCurrentTime(long)` | Mutates `blackboard.getTimeManager().setOffset(long, long)`; reads `blackboard.getTimeManager().getMarketTime()` |

---

## `IbkrErrorHandler`

`public class IbkrErrorHandler`

### 1. Class/Interface Responsibilities

Logs every IBKR error callback and sets the `Blackboard` update-required flag for the connection-level error codes 503, 505, 507, 508, and 323.

### 2. Injected Dependencies

Constructor: `public IbkrErrorHandler(Blackboard blackboard)`

| Parameter | Exact type |
| --- | --- |
| `blackboard` | `mwd.trading.state.Blackboard` |

### 3. Method Signatures

```java
public IbkrErrorHandler(Blackboard blackboard)
public void process(int reqId, int errorCode, String errorMsg, String advancedOrderRejectJson)
```

### 4. Global State Interactions

**Concurrent collections**

None.

**Centralized state objects (`Blackboard`)**

| Method | Interaction |
| --- | --- |
| `process(int, int, String, String)` | Mutates `blackboard.setSystemUpdateRequired(true)` when `reqId <= 0` and `errorCode` is 503, 505, 507, 508, or 323. The `reqId > 0` branches for codes 201 and 202 contain comments only and perform no state change. |

---

## `NextValidIdHandler`

`public class NextValidIdHandler`

### 1. Class/Interface Responsibilities

Forwards the IBKR `nextValidId` value to `Blackboard.initializeOrderId(int)`.

### 2. Injected Dependencies

Constructor: `public NextValidIdHandler(Blackboard blackboard)`

| Parameter | Exact type |
| --- | --- |
| `blackboard` | `mwd.trading.state.Blackboard` |

### 3. Method Signatures

```java
public NextValidIdHandler(Blackboard blackboard)
public void initialize(int orderId)
```

### 4. Global State Interactions

**Concurrent collections**

None held. Reaches the `AtomicInteger` inside `IdManager` through `Blackboard`.

**Centralized state objects (`Blackboard`)**

| Method | Interaction |
| --- | --- |
| `initialize(int)` | Mutates `blackboard.initializeOrderId(int)` |
