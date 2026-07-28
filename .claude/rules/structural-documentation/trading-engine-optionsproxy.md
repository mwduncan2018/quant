---
paths:
  - "trading-engine/**/*"
---

# Package `mwd.trading.optionsproxy`

Sources:
- `trading-engine/trading-engine/src/main/java/mwd/trading/optionsproxy/OptionsIndicatorFrameReceiver.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/optionsproxy/OptionsIndicatorStore.java`

The `IndicatorFrame` protobuf type in `mwd.trading.optionsproxy.proto` is generated at build time from `options-proxy/proto/market_state.proto` into `target/generated-sources/protobuf`.

---

## `OptionsIndicatorFrameReceiver`

`public final class OptionsIndicatorFrameReceiver`

### 1. Class/Interface Responsibilities

Binds a `DatagramSocket` to the configured host and port, runs a daemon receive loop that parses each datagram into an `IndicatorFrame`, hands it to `OptionsIndicatorStore.accept(...)`, notifies the accepted-frame listener, and maintains received/malformed counters.

### 2. Injected Dependencies

Constructors:
- `public OptionsIndicatorFrameReceiver(OptionsIndicatorStore store, String bindHost, int bindPort, AcceptedFrameListener acceptedFrameListener)`
- `public OptionsIndicatorFrameReceiver(OptionsIndicatorStore store, String bindHost, int bindPort)` — delegates with a `null` listener

| Parameter | Exact type |
| --- | --- |
| `store` | `mwd.trading.optionsproxy.OptionsIndicatorStore` (null-checked) |
| `bindHost` | `java.lang.String` (null-checked) |
| `bindPort` | `int` |
| `acceptedFrameListener` | `mwd.trading.optionsproxy.OptionsIndicatorFrameReceiver.AcceptedFrameListener` (nullable) |

Static field: `private static final int RECEIVE_BUFFER_BYTES = 8192`.

### 3. Method Signatures

Nested type:

```java
@FunctionalInterface
public interface AcceptedFrameListener {
    void onAccepted(IndicatorFrame frame);
}
```

```java
public OptionsIndicatorFrameReceiver(OptionsIndicatorStore store, String bindHost, int bindPort, AcceptedFrameListener acceptedFrameListener)
public OptionsIndicatorFrameReceiver(OptionsIndicatorStore store, String bindHost, int bindPort)

public synchronized void start() throws SocketException
public synchronized void stop()
private void receiveLoop()

public boolean isRunning()
public int getBoundPort()
public long getReceivedDatagramCount()
public long getMalformedDatagramCount()
public long getLastDatagramAtUnixMs()
```

### 4. Global State Interactions

**Concurrent collections / atomics**

| Field | Declared type |
| --- | --- |
| `receivedDatagrams` | `AtomicLong` |
| `malformedDatagrams` | `AtomicLong` |

| Method | Interaction |
| --- | --- |
| `receiveLoop()` | Mutates `receivedDatagrams` (`incrementAndGet`) and `malformedDatagrams` (`incrementAndGet`); writes `lastDatagramAtUnixMs` |
| `getReceivedDatagramCount()` | Reads `receivedDatagrams` |
| `getMalformedDatagramCount()` | Reads `malformedDatagrams` |
| `stop()` | Reads `receivedDatagrams`, `store.getAcceptedFrameCount()`, `store.getRejectedFrameCount()` |

**Volatile fields**

`lastDatagramAtUnixMs`, `socket`, `receiverThread`, `running`. `start()` and `stop()` are `synchronized` on the instance and manage the `Options-Proxy-UDP-Receiver` daemon thread.

**Centralized state objects**

No `Blackboard` reference. `receiveLoop()` mutates `OptionsIndicatorStore` through `store.accept(IndicatorFrame, long)` and, on acceptance, invokes `acceptedFrameListener.onAccepted(IndicatorFrame)` on the receiver thread. In `Main` that listener writes onto `Blackboard`-held `Stock` objects.

---

## `OptionsIndicatorStore`

`public final class OptionsIndicatorStore`

### 1. Class/Interface Responsibilities

Validates each `IndicatorFrame` against a known-ticker set, trading-date parsing, emission time, sequence advance, and value finiteness, then stores per-ticker `TickerState` snapshots in a `ConcurrentMap` and the shared SPY gamma flip in an `AtomicReference`, and answers implied-move, gamma-flip, and freshness queries.

### 2. Injected Dependencies

Constructor: `public OptionsIndicatorStore(Set<String> knownTickers, long frameMaxAgeMs)`

| Parameter | Exact type |
| --- | --- |
| `knownTickers` | `java.util.Set<java.lang.String>` (normalized and stored as `Set.copyOf(...)`; empty throws `IllegalArgumentException`) |
| `frameMaxAgeMs` | `long` (must be positive) |

### 3. Method Signatures

Nested types:
- `public record ImpliedMove(String ticker, double value, LocalDate tradingDate, long sequence, long emittedAtUnixMs, long receivedAtUnixMs)`
- `public record GammaFlip(double level, boolean valid, LocalDate tradingDate, long sequence, long emittedAtUnixMs, long receivedAtUnixMs)`
- `private record TickerState(long sequence, long emittedAtUnixMs, long receivedAtUnixMs, LocalDate tradingDate, boolean impliedMoveValid, ImpliedMove lastValidImpliedMove)`

```java
public OptionsIndicatorStore(Set<String> knownTickers, long frameMaxAgeMs)

public boolean accept(IndicatorFrame frame, long receivedAtUnixMs)
public OptionalDouble impliedMoveForNewEntry(String ticker, LocalDate tradingDate, long nowUnixMs)
public OptionalDouble gammaFlipForNewEntry(LocalDate tradingDate, long nowUnixMs)
public OptionalDouble lastKnownImpliedMove(String ticker)
public Optional<ImpliedMove> impliedMove(String ticker)
public Optional<GammaFlip> gammaFlip()
public boolean hasFreshFrame(String ticker, long nowUnixMs)
public Optional<LocalDate> tradingDate(String ticker)
public Set<String> getKnownTickers()
public long getFrameMaxAgeMs()
public long getAcceptedFrameCount()
public long getRejectedFrameCount()
public String getLastRejectionReason()

private boolean isFresh(long receivedAtUnixMs, long nowUnixMs)
private boolean reject(String reason)
private static LocalDate parseTradingDate(String rawTradingDate)
private static boolean isPositiveFinite(double value)
private static String normalize(String ticker)
```

### 4. Global State Interactions

**Concurrent collections / atomics**

| Field | Declared type |
| --- | --- |
| `stateByTicker` | `ConcurrentMap<String, TickerState>` = `new ConcurrentHashMap<>()` |
| `gammaFlip` | `AtomicReference<GammaFlip>` |
| `acceptedFrames` | `AtomicLong` |
| `rejectedFrames` | `AtomicLong` |
| `knownTickers` | `final Set<String>` (immutable) |
| `lastRejectionReason` | `volatile String` |

| Method | Interaction |
| --- | --- |
| `accept(IndicatorFrame, long)` | Reads `knownTickers`; reads `stateByTicker` (`get`) for the sequence check; mutates `stateByTicker` (`put`), `gammaFlip` (`set`), `acceptedFrames` (`incrementAndGet`) |
| `reject(String)` | Mutates `lastRejectionReason` and `rejectedFrames` (`incrementAndGet`) |
| `impliedMoveForNewEntry(String, LocalDate, long)` | Reads `stateByTicker` |
| `gammaFlipForNewEntry(LocalDate, long)` | Reads `gammaFlip` |
| `lastKnownImpliedMove(String)` | Reads `stateByTicker` |
| `impliedMove(String)` | Reads `stateByTicker` |
| `gammaFlip()` | Reads `gammaFlip` |
| `hasFreshFrame(String, long)` | Reads `stateByTicker` |
| `tradingDate(String)` | Reads `stateByTicker` |
| `getKnownTickers()` | Reads `knownTickers` |
| `getAcceptedFrameCount()` | Reads `acceptedFrames` |
| `getRejectedFrameCount()` | Reads `rejectedFrames` |
| `getLastRejectionReason()` | Reads `lastRejectionReason` |

**Centralized state objects**

No `Blackboard` reference.
