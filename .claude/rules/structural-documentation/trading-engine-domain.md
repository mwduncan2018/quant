---
paths:
  - "trading-engine/**/*"
---

# Package `mwd.trading.domain`

Sources:
- `trading-engine/trading-engine/src/main/java/mwd/trading/domain/Account.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/domain/Stock.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/domain/TradeDirection.java`

---

## `Account`

`public class Account`

### 1. Class/Interface Responsibilities

Holds the IBKR account identifier, nine `volatile double` balance/PnL figures, and the `volatile long` timestamp of the last account-value batch, with a getter and setter for each.

### 2. Injected Dependencies

None. The class declares no constructor.

### 3. Method Signatures

```java
public String getAccountId()
public void setAccountId(String id)
public long getLastRefreshedAtMillis()
public void setLastRefreshedAtMillis(long millis)
public double getNetLiquidation()
public void setNetLiquidation(double val)
public double getTotalCashValue()
public void setTotalCashValue(double val)
public double getSettledCash()
public void setSettledCash(double val)
public double getBuyingPower()
public void setBuyingPower(double val)
public double getAvailableFunds()
public void setAvailableFunds(double val)
public double getExcessMargin()
public void setExcessMargin(double val)
public double getRealizedPnL()
public void setRealizedPnL(double val)
public double getUnrealizedPnL()
public void setUnrealizedPnL(double val)
public double getCushion()
public void setCushion(double val)
```

### 4. Global State Interactions

**Concurrent collections**

None. All fields are `volatile` scalars (`accountId`, `netLiquidation`, `totalCashValue`, `settledCash`, `buyingPower`, `availableFunds`, `excessMargin`, `realizedPnL`, `unrealizedPnL`, `cushion`, `lastRefreshedAtMillis`) mutated by the setters and read by the getters listed above.

**Centralized state objects**

No `Blackboard` reference. The single instance is created inside the `Blackboard` constructor and reached through `Blackboard.getAccount()`.

---

## `Stock`

`public class Stock`

### 1. Class/Interface Responsibilities

Per-ticker mutable value holder for position state, IBKR `Contract`, active `BracketOrder`, margin rates and verification flags, tick prices and sizes, moving averages, volumes, ATR values, RSI, implied-move/gamma-flip mirror values, portfolio figures, the next earnings instant, and the last minute `Bar`.

### 2. Injected Dependencies

Constructor: `public Stock(String ticker)`

| Parameter | Exact type |
| --- | --- |
| `ticker` | `java.lang.String` |

The constructor also initialises `lastUpdate` to `System.currentTimeMillis()`.

### 3. Method Signatures

Nested type: `public enum PositionState { FLAT, PENDING, OPEN, CLOSING }`

```java
public Stock(String ticker)

public AtomicReference<PositionState> getState()
public boolean isTradeable()
public void setTradeable(boolean tradeable)
private void refreshLastUpdate()

public boolean isLongMarginRateVerified()
public boolean isShortMarginRateVerified()
public void setLongMarginRateVerified(boolean verified)
public void setShortMarginRateVerified(boolean verified)
public double calculateMarginRequirement(String action, Decimal qty, double price)
public double getLongMarginRate()
public double getShortMarginRate()
public void setLongMarginRate(double rate)
public void setShortMarginRate(double rate)

public void setContract(Contract contract)
public void setActiveBracket(BracketOrder bracket)
public void setLastPrice(double lastPrice)
public void setBid(double bid)
public void setAsk(double ask)
public void setMarkPrice(double markPrice)
public void setDailyHigh(double dailyHigh)
public void setDailyLow(double dailyLow)
public void setOpen(double open)
public void setPreviousClose(double previousClose)
public void setDailyVWAP(double dailyVWAP)
public void setSma10(double sma10)
public void setSma20(double sma20)
public void setSma50(double sma50)
public void setSma100(double sma100)
public void setSma200(double sma200)
public void setBidSize(Decimal bidSize)
public void setAskSize(Decimal askSize)
public void setLastSize(Decimal lastSize)
public void setIntradayVolume(Decimal intradayVolume)
public void setLastMinuteVolume(Decimal lastMinuteVolume)
public void setAverageLast15MinuteVolume(Decimal averageLast15MinuteVolume)
public void setAverageDailyVolume(Decimal averageDailyVolume)
public void setGammaFlip(double gammaFlip)
public void setDailyImpliedMove(double dailyImpliedMove)
public void setWeeklyImpliedMove(double weeklyImpliedMove)
public void setMonthlyImpliedMove(double monthlyImpliedMove)
public void setIntradayATR4(double intradayATR4)
public void setIntradayATR5(double intradayATR5)
public void setIntradayATR12(double intradayATR12)
public void setIntradayATR15(double intradayATR15)
public void setDailyATR(double dailyATR)
public void setRSI(double relativeStrengthIndex)
public void setAverageCost(double averageCost)
public void setPositionSize(Decimal positionSize)
public void setMarketValue(double marketValue)
public void setRealizedPnL(double realizedPnL)
public void setUnrealizedPnl(double unrealizedPnl)
public void setNextEarningsDate(Instant nextEarningsDate)
public void setLastMinuteBar(Bar lastMinuteBar)

public String getTicker()
public Contract getContract()
public long getLastUpdate()
public BracketOrder getActiveBracket()
public double getLastPrice()
public double getBid()
public double getAsk()
public double getMarkPrice()
public double getDailyHigh()
public double getDailyLow()
public double getOpen()
public double getPreviousClose()
public double getDailyVWAP()
public double getSma10()
public double getSma20()
public double getSma50()
public double getSma100()
public double getSma200()
public Decimal getBidSize()
public Decimal getAskSize()
public Decimal getLastSize()
public Decimal getIntradayVolume()
public Decimal getLastMinuteVolume()
public Decimal getAverageLast15MinuteVolume()
public Decimal getAverageDailyVolume()
public double getGammaFlip()
public double getDailyImpliedMove()
public double getWeeklyImpliedMove()
public double getMonthlyImpliedMove()
public double getIntradayATR4()
public double getIntradayATR5()
public double getIntradayATR12()
public double getIntradayATR15()
public double getDailyATR()
public double getRSI()
public Decimal getPositionSize()
public double getAverageCost()
public double getMarketValue()
public double getRealizedPnL()
public double getUnrealizedPnl()
public Instant getNextEarningsDate()
public Bar getLastMinuteBar()

@Override public String toString()
```

### 4. Global State Interactions

**Concurrent collections / atomics**

| Field | Declared type |
| --- | --- |
| `state` | `final AtomicReference<PositionState>` initialised to `PositionState.FLAT` |

`getState()` returns the `AtomicReference` itself, so callers mutate it directly via `set` and `compareAndSet`.

**Volatile fields**

`activeBracket`, `isTradeable`, `lastUpdate`, `longMarginRate`, `shortMarginRate`, `longMarginRateVerified`, `shortMarginRateVerified`, `lastPrice`, `bid`, `ask`, `markPrice`, `dailyHigh`, `dailyLow`, `open`, `previousClose`, `dailyVWAP`, `sma10`, `sma20`, `sma50`, `sma100`, `sma200`, `bidSize`, `askSize`, `lastSize`, `intradayVolume`, `lastMinuteVolume`, `averageLast15MinuteVolume`, `averageDailyVolume`, `gammaFlip`, `dailyImpliedMove`, `weeklyImpliedMove`, `monthlyImpliedMove`, `intradayATR4`, `intradayATR5`, `intradayATR12`, `intradayATR15`, `dailyATR`, `relativeStrengthIndex`, `positionSize`, `averageCost`, `marketValue`, `realizedPnL`, `unrealizedPnl`, `lastMinuteBar`.

Non-volatile fields: `ticker` (final), `contract`, `nextEarningsDate`.

`refreshLastUpdate()` is called by `setTradeable` and by every price, moving-average, size, volume, gamma-flip, implied-move, ATR, and RSI setter.

**Centralized state objects**

No `Blackboard` reference. Instances live inside the `ConcurrentHashMap<String, Stock>` owned by `Blackboard` and are obtained through `Blackboard.getStock(String)`.

---

## `TradeDirection`

`public enum TradeDirection`

### 1. Class/Interface Responsibilities

Enumerates `LONG` and `SHORT`, each carrying its IBKR entry and exit action strings and its own implementation of the entry-price acceptance predicate.

### 2. Injected Dependencies

Constructor: `TradeDirection(String entryAction, String exitAction)` (implicitly `private`)

| Parameter | Exact type |
| --- | --- |
| `entryAction` | `java.lang.String` |
| `exitAction` | `java.lang.String` |

Constants: `LONG("BUY", "SELL")`, `SHORT("SELL", "BUY")`.

### 3. Method Signatures

```java
TradeDirection(String entryAction, String exitAction)

public String entryAction()
public String exitAction()
public abstract boolean acceptsEntryPrice(double marketPrice, double limitPrice)
private static boolean validPrices(double marketPrice, double limitPrice)
```

Both constants override `acceptsEntryPrice(double, double)` with a constant-specific body.

### 4. Global State Interactions

None. The enum holds two `final String` fields and no collections or centralized state references.
