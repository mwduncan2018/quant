---
paths:
  - "trading-engine/**/*"
---

# Package `mwd.trading.lifecycle`

Sources:
- `trading-engine/trading-engine/src/main/java/mwd/trading/lifecycle/EngineMode.java`
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

## `TradingGate`

`public final class TradingGate`

### 1. Class/Interface Responsibilities

Holds a single `AtomicReference<State>` carrying the current `EngineMode`, a reason string, and a change timestamp, and exposes read predicates plus two transition methods.

### 2. Injected Dependencies

None. The class declares no constructor; the implicit no-argument constructor initialises `state` to `new State(EngineMode.STARTING, "Application starting", System.currentTimeMillis())`.

### 3. Method Signatures

Nested type: `public record State(EngineMode mode, String reason, long changedAtEpochMillis)` with a compact canonical constructor.

```java
public State getState()
public EngineMode getMode()
public boolean allowsNewEntries()
public boolean allowsAutomatedOrderChanges()
public void transitionTo(EngineMode mode, String reason)
public void requireManualIntervention(String reason)
```

### 4. Global State Interactions

**Concurrent collections / atomics**

| Field | Declared type |
| --- | --- |
| `state` | `AtomicReference<State>` |

| Method | Interaction |
| --- | --- |
| `getState()` | Reads `state` (`get`) |
| `getMode()` | Reads `state` (`get`) |
| `allowsNewEntries()` | Reads `state` via `getMode()` |
| `allowsAutomatedOrderChanges()` | Reads `state` via `getMode()` |
| `transitionTo(EngineMode, String)` | Mutates `state` (`updateAndGet`) |
| `requireManualIntervention(String)` | Mutates `state` via `transitionTo` |

**Centralized state objects**

No reference to `Blackboard` or any other centralized state object.
