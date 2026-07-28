---
paths:
  - "trading-engine/**/*"
---

# Package `mwd.trading.earnings`

Sources:
- `trading-engine/trading-engine/src/main/java/mwd/trading/earnings/EarningsClient.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/earnings/EarningsRefresher.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/earnings/EarningsSnapshot.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/earnings/EarningsStore.java`

---

## `EarningsClient`

`public final class EarningsClient`

### 1. Class/Interface Responsibilities

Fetches the options proxy's `/earnings` response through a `ProxyJsonFetcher` and decodes it into an `EarningsSnapshot`.

### 2. Injected Dependencies

Constructor: `public EarningsClient(String endpointUrl, long connectTimeoutMs, long requestTimeoutMs)`

| Parameter | Exact type |
| --- | --- |
| `endpointUrl` | `java.lang.String` |
| `connectTimeoutMs` | `long` |
| `requestTimeoutMs` | `long` |

Constructed inside the constructor: `private final ProxyJsonFetcher fetcher`.

### 3. Method Signatures

```java
public EarningsClient(String endpointUrl, long connectTimeoutMs, long requestTimeoutMs)
public EarningsSnapshot fetch() throws ProxyFetchException
static EarningsSnapshot decode(String body) throws ProxyFetchException
static EarningsSnapshot decode(JsonNode root) throws ProxyFetchException
public URI getEndpoint()
```

### 4. Global State Interactions

**Concurrent collections**

None. `decode(JsonNode)` builds a local `LinkedHashMap<String, EarningsDate>`.

**Centralized state objects**

No `Blackboard` or `EarningsStore` reference; the decoded snapshot is returned to the caller.

---

## `EarningsRefresher`

`public final class EarningsRefresher implements Runnable`

### 1. Class/Interface Responsibilities

Runs a loop that asks `EarningsStore` whether the current New York trading date is covered, fetches an `EarningsSnapshot` through `EarningsClient` when it is not, offers it to the store, notifies the accepted-snapshot `Consumer`, and sleeps for the idle or retry delay.

### 2. Injected Dependencies

Public constructor: `public EarningsRefresher(EarningsClient client, EarningsStore store, long retryDelayMs, long idleDelayMs, Consumer<EarningsSnapshot> acceptedListener)`

Package-private constructor: `EarningsRefresher(EarningsClient client, EarningsStore store, long retryDelayMs, long idleDelayMs, Consumer<EarningsSnapshot> acceptedListener, Clock clock)`

| Parameter | Exact type |
| --- | --- |
| `client` | `mwd.trading.earnings.EarningsClient` |
| `store` | `mwd.trading.earnings.EarningsStore` |
| `retryDelayMs` | `long` |
| `idleDelayMs` | `long` |
| `acceptedListener` | `java.util.function.Consumer<mwd.trading.earnings.EarningsSnapshot>` |
| `clock` | `java.time.Clock` (public constructor supplies `Clock.systemUTC()`) |

### 3. Method Signatures

```java
public EarningsRefresher(EarningsClient client, EarningsStore store, long retryDelayMs, long idleDelayMs, Consumer<EarningsSnapshot> acceptedListener)
EarningsRefresher(EarningsClient client, EarningsStore store, long retryDelayMs, long idleDelayMs, Consumer<EarningsSnapshot> acceptedListener, Clock clock)

@Override public void run()
boolean refreshOnce()
```

Static fields: `private static final Logger logger`, `private static final ZoneId NEW_YORK_ZONE`.
Instance field: `private String lastReportedFailure` (non-volatile).

### 4. Global State Interactions

**Concurrent collections**

None.

**Centralized state objects**

| Method | Interaction |
| --- | --- |
| `run()` | Calls `refreshOnce()` in a loop |
| `refreshOnce()` | Reads `store.isReadyFor(LocalDate)` and `store.coveredTickers()`; mutates the store via `store.accept(EarningsSnapshot, LocalDate, long)`; calls `client.fetch()`; invokes `acceptedListener.accept(EarningsSnapshot)` |

The `acceptedListener` supplied by `Main` writes onto `Blackboard`-held `Stock` objects; `EarningsRefresher` itself holds no `Blackboard` reference.

---

## `EarningsSnapshot`

`public record EarningsSnapshot(LocalDate tradingDate, Map<String, EarningsDate> byTicker)`

### 1. Class/Interface Responsibilities

Immutable record pairing one trading date with the per-ticker earnings dates decoded from a single `/earnings` response.

### 2. Injected Dependencies

Canonical record constructor parameters:

| Parameter | Exact type |
| --- | --- |
| `tradingDate` | `java.time.LocalDate` |
| `byTicker` | `java.util.Map<java.lang.String, mwd.trading.earnings.EarningsSnapshot.EarningsDate>` |

The compact constructor null-checks `tradingDate` and replaces `byTicker` with `Map.copyOf(...)`.

Nested record `EarningsDate` constructor parameters:

| Parameter | Exact type |
| --- | --- |
| `ticker` | `java.lang.String` |
| `nextEarningsDate` | `java.time.LocalDate` |
| `lastEarningsDate` | `java.time.LocalDate` |

Its compact constructor null-checks `ticker` and throws `IllegalArgumentException` when both dates are null.

### 3. Method Signatures

```java
public EarningsSnapshot(LocalDate tradingDate, Map<String, EarningsDate> byTicker)
public LocalDate tradingDate()
public Map<String, EarningsDate> byTicker()

public record EarningsDate(String ticker, LocalDate nextEarningsDate, LocalDate lastEarningsDate)
public String ticker()
public LocalDate nextEarningsDate()
public LocalDate lastEarningsDate()
public java.util.List<LocalDate> knownDates()
```

### 4. Global State Interactions

None. Both records are immutable and hold no concurrent collection or centralized state reference.

---

## `EarningsStore`

`public final class EarningsStore`

### 1. Class/Interface Responsibilities

Holds one accepted snapshot in an `AtomicReference<Accepted>` scoped to a trading date, plus accepted/rejected counters and the last rejection reason, and answers per-ticker earnings-date, days-until, readiness, and coverage queries against a fixed known-ticker set.

### 2. Injected Dependencies

Constructor: `public EarningsStore(Set<String> knownTickers)`

| Parameter | Exact type |
| --- | --- |
| `knownTickers` | `java.util.Set<java.lang.String>` |

Trimmed, upper-cased, and stored as `private final Set<String> knownTickers = Set.copyOf(normalized)`; an empty or all-blank set throws `IllegalArgumentException`.

### 3. Method Signatures

Nested type: `private record Accepted(LocalDate tradingDate, Map<String, EarningsDate> byTicker, long receivedAtUnixMs)`

```java
public EarningsStore(Set<String> knownTickers)

public boolean accept(EarningsSnapshot snapshot, LocalDate expectedTradingDate, long receivedAtUnixMs)
public Optional<EarningsDate> earningsDate(String ticker, LocalDate tradingDate)
public OptionalLong daysUntilEarnings(String ticker, LocalDate tradingDate)
public boolean isReadyFor(LocalDate tradingDate)
public Optional<String> describeUnready(Set<String> requiredTickers, LocalDate tradingDate)
public Optional<LocalDate> snapshotTradingDate()
public Set<String> coveredTickers()
public long getAcceptedSnapshotCount()
public long getRejectedSnapshotCount()
public String getLastRejectionReason()
private boolean reject(String reason)
```

### 4. Global State Interactions

**Concurrent collections / atomics**

| Field | Declared type |
| --- | --- |
| `accepted` | `AtomicReference<Accepted>` |
| `acceptedSnapshotCount` | `AtomicLong` |
| `rejectedSnapshotCount` | `AtomicLong` |
| `lastRejectionReason` | `AtomicReference<String>` |
| `knownTickers` | `final Set<String>` (immutable) |

| Method | Interaction |
| --- | --- |
| `accept(EarningsSnapshot, LocalDate, long)` | Reads `knownTickers`; mutates `accepted` (`set`), `acceptedSnapshotCount` (`incrementAndGet`), `lastRejectionReason` (`set`) |
| `reject(String)` | Mutates `rejectedSnapshotCount` (`incrementAndGet`), `lastRejectionReason` (`set`) |
| `earningsDate(String, LocalDate)` | Reads `accepted` |
| `daysUntilEarnings(String, LocalDate)` | Reads `accepted` via `earningsDate` |
| `isReadyFor(LocalDate)` | Reads `accepted` |
| `describeUnready(Set<String>, LocalDate)` | Reads `accepted` |
| `snapshotTradingDate()` | Reads `accepted` |
| `coveredTickers()` | Reads `accepted` |
| `getAcceptedSnapshotCount()` | Reads `acceptedSnapshotCount` |
| `getRejectedSnapshotCount()` | Reads `rejectedSnapshotCount` |
| `getLastRejectionReason()` | Reads `lastRejectionReason` |

**Centralized state objects**

No `Blackboard` reference.
