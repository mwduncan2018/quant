---
paths:
  - "trading-engine/**/*"
---

# Package `mwd.trading.lifecycle`

Sources:
- `trading-engine/trading-engine/src/main/java/mwd/trading/lifecycle/EngineMode.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/lifecycle/TradingEnvironment.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/lifecycle/TradingGate.java`

---

## `EngineMode`

`public enum EngineMode`

### 1. Class/Interface Responsibilities

Enumerates the seven application-wide lifecycle values `STARTING`, `CONNECTING`, `RECONCILING`, `READY`, `DEGRADED`, `MANUAL_INTERVENTION`, `STOPPING`.

### 2. Injected Dependencies

None. The enum declares no constructor parameters.

### 3. Method Signatures

No declared methods; constants only.

### 4. Global State Interactions

None.

---

## `TradingEnvironment`

`public enum TradingEnvironment`

### 1. Class/Interface Responsibilities

Names the broker environment receiving orders: `PAPER` or `LIVE`. It is deliberately separate from the connection lifecycle in `EngineMode`.

### 2. Injected Dependencies

None.

### 3. Method Signatures

```java
public static TradingEnvironment fromLiveTrading(boolean liveTrading)
```

### 4. Global State Interactions

None.

---

## `TradingGate`

`public final class TradingGate`

### 1. Class/Interface Responsibilities

Holds an `AtomicReference<State>` carrying the current `EngineMode`, a reason string, and a change timestamp. A LIVE instance also owns a process-local `AtomicBoolean` arming flag. New entries require `READY` and, for LIVE, explicit arming; automated protective-order changes require `READY` but do not require entry arming.

### 2. Injected Dependencies

`public TradingGate()` constructs a PAPER/test gate. `public TradingGate(boolean liveTradingArmingRequired)` constructs the production form; when the argument is true the gate starts entry-disarmed. No constructor accepts an initially armed value, and the flag is never serialized.

### 3. Method Signatures

Nested type: `public record State(EngineMode mode, String reason, long changedAtEpochMillis)` with a compact canonical constructor.

```java
public State getState()
public EngineMode getMode()
public boolean allowsNewEntries()
public boolean allowsAutomatedOrderChanges()
public boolean requiresLiveTradingArming()
public boolean isLiveTradingArmed()
public void armLiveTrading()
public void transitionTo(EngineMode mode, String reason)
public void requireManualIntervention(String reason)
```

### 4. Global State Interactions

**Concurrent collections / atomics**

| Field | Declared type |
| --- | --- |
| `state` | `AtomicReference<State>` |
| `liveTradingArmed` | `AtomicBoolean` |

| Method | Interaction |
| --- | --- |
| `getState()` | Reads `state` (`get`) |
| `getMode()` | Reads `state` (`get`) |
| `allowsNewEntries()` | Reads `state` via `getMode()` and, when `liveTradingArmingRequired`, reads `liveTradingArmed` |
| `allowsAutomatedOrderChanges()` | Reads `state` via `getMode()` |
| `requiresLiveTradingArming()` | Reads immutable `liveTradingArmingRequired` |
| `isLiveTradingArmed()` | Reads `liveTradingArmed` |
| `armLiveTrading()` | Requires a LIVE gate in `READY`, then sets `liveTradingArmed`; called by the Swing confirmation path and never persisted |
| `transitionTo(EngineMode, String)` | Mutates `state` (`updateAndGet`) |
| `requireManualIntervention(String)` | Mutates `state` via `transitionTo` |

**Centralized state objects**

No reference to `Blackboard` or any other centralized state object.
