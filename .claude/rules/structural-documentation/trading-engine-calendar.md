---
paths:
  - "trading-engine/**/*"
---

# Package `mwd.trading.calendar`

Sources:
- `trading-engine/trading-engine/src/main/java/mwd/trading/calendar/MarketCalendarClient.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/calendar/MarketCalendarRefresher.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/calendar/MarketCalendarStore.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/calendar/MarketSession.java`

---

## `MarketCalendarClient`

`public final class MarketCalendarClient`

### 1. Class/Interface Responsibilities

Fetches the options proxy's `/calendar` response through a `ProxyJsonFetcher` and decodes it into a `MarketSession`.

### 2. Injected Dependencies

Constructor: `public MarketCalendarClient(String endpointUrl, long connectTimeoutMs, long requestTimeoutMs)`

| Parameter | Exact type |
| --- | --- |
| `endpointUrl` | `java.lang.String` |
| `connectTimeoutMs` | `long` |
| `requestTimeoutMs` | `long` |

Constructed inside the constructor: `private final ProxyJsonFetcher fetcher = new ProxyJsonFetcher(endpointUrl, connectTimeoutMs, requestTimeoutMs)`.

### 3. Method Signatures

```java
public MarketCalendarClient(String endpointUrl, long connectTimeoutMs, long requestTimeoutMs)
public MarketSession fetch() throws ProxyFetchException
static MarketSession decode(String body) throws ProxyFetchException
static MarketSession decode(JsonNode root) throws ProxyFetchException
private static Instant readInstant(JsonNode node)
public URI getEndpoint()
```

### 4. Global State Interactions

**Concurrent collections**

None. `decode(JsonNode)` builds a local `LinkedHashSet<LocalDate>` for closed dates.

**Centralized state objects**

No `Blackboard` reference and no `MarketCalendarStore` reference; the decoded `MarketSession` is returned to the caller.

---

## `MarketCalendarRefresher`

`public final class MarketCalendarRefresher implements Runnable`

### 1. Class/Interface Responsibilities

Runs a loop that asks `MarketCalendarStore` whether the current New York trading date is covered, fetches a `MarketSession` through `MarketCalendarClient` when it is not, offers it to the store, and sleeps for the idle or retry delay accordingly.

### 2. Injected Dependencies

Public constructor: `public MarketCalendarRefresher(MarketCalendarClient client, MarketCalendarStore store, long retryDelayMs, long idleDelayMs)`

Package-private constructor: `MarketCalendarRefresher(MarketCalendarClient client, MarketCalendarStore store, long retryDelayMs, long idleDelayMs, Clock clock)`

| Parameter | Exact type |
| --- | --- |
| `client` | `mwd.trading.calendar.MarketCalendarClient` |
| `store` | `mwd.trading.calendar.MarketCalendarStore` |
| `retryDelayMs` | `long` |
| `idleDelayMs` | `long` |
| `clock` | `java.time.Clock` (public constructor supplies `Clock.systemUTC()`) |

### 3. Method Signatures

```java
public MarketCalendarRefresher(MarketCalendarClient client, MarketCalendarStore store, long retryDelayMs, long idleDelayMs)
MarketCalendarRefresher(MarketCalendarClient client, MarketCalendarStore store, long retryDelayMs, long idleDelayMs, Clock clock)

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
| `refreshOnce()` | Reads `store.isReadyFor(LocalDate)`; mutates the store via `store.accept(MarketSession, LocalDate)`; calls `client.fetch()` |

No `Blackboard` reference.

---

## `MarketCalendarStore`

`public final class MarketCalendarStore`

### 1. Class/Interface Responsibilities

Holds the current `MarketSession` in an `AtomicReference` plus accepted/rejected counters and the last rejection reason, and answers session-hours, coverage, and adjacent-session queries from it.

### 2. Injected Dependencies

None. The class declares no constructor.

### 3. Method Signatures

```java
public boolean accept(MarketSession candidate, LocalDate expectedTradingDate)
public boolean isReadyFor(LocalDate tradingDate)
public Optional<Instant> sessionClose(LocalDate tradingDate)
public boolean isWithinOfClose(LocalDate tradingDate, Instant now, Duration window)
public boolean isSession(LocalDate candidate)
public boolean coversDate(LocalDate candidate)
public Optional<LocalDate> nextSession(LocalDate from)
public Optional<LocalDate> previousSessionApproximate(LocalDate from)
public Optional<String> describeUnready(LocalDate tradingDate)
public Optional<MarketSession> currentSession()
public long getAcceptedCount()
public long getRejectedCount()
public String getLastRejectionReason()
private boolean reject(String reason)
```

### 4. Global State Interactions

**Concurrent collections / atomics**

| Field | Declared type |
| --- | --- |
| `session` | `AtomicReference<MarketSession>` |
| `acceptedCount` | `AtomicLong` |
| `rejectedCount` | `AtomicLong` |
| `lastRejectionReason` | `AtomicReference<String>` |

| Method | Interaction |
| --- | --- |
| `accept(MarketSession, LocalDate)` | Mutates `session` (`set`), `acceptedCount` (`incrementAndGet`), `lastRejectionReason` (`set`) |
| `reject(String)` | Mutates `rejectedCount` (`incrementAndGet`), `lastRejectionReason` (`set`) |
| `isReadyFor(LocalDate)` | Reads `session` |
| `sessionClose(LocalDate)` | Reads `session` |
| `isWithinOfClose(LocalDate, Instant, Duration)` | Reads `session` via `sessionClose` |
| `isSession(LocalDate)` | Reads `session` |
| `coversDate(LocalDate)` | Reads `session` |
| `nextSession(LocalDate)` | Reads `session` via `coversDate` and `isSession` |
| `previousSessionApproximate(LocalDate)` | Reads `session` |
| `describeUnready(LocalDate)` | Reads `session` |
| `currentSession()` | Reads `session` |
| `getAcceptedCount()` | Reads `acceptedCount` |
| `getRejectedCount()` | Reads `rejectedCount` |
| `getLastRejectionReason()` | Reads `lastRejectionReason` |

**Centralized state objects**

No `Blackboard` reference.

---

## `MarketSession`

`public record MarketSession(LocalDate tradingDate, String status, Instant opensAt, Instant closesAt, String hoursSource, Set<LocalDate> closedDates, LocalDate coversFrom, LocalDate coversTo)`

### 1. Class/Interface Responsibilities

Immutable record of one decoded `/calendar` response: trading date, status string, open and close instants, hours source, the set of fully-closed dates, and the cache coverage bounds.

### 2. Injected Dependencies

Canonical record constructor parameters:

| Parameter | Exact type |
| --- | --- |
| `tradingDate` | `java.time.LocalDate` |
| `status` | `java.lang.String` |
| `opensAt` | `java.time.Instant` |
| `closesAt` | `java.time.Instant` |
| `hoursSource` | `java.lang.String` |
| `closedDates` | `java.util.Set<java.time.LocalDate>` |
| `coversFrom` | `java.time.LocalDate` |
| `coversTo` | `java.time.LocalDate` |

The compact constructor null-checks `tradingDate` and `status` and replaces `closedDates` with `Set.copyOf(...)`.

### 3. Method Signatures

```java
public MarketSession(LocalDate tradingDate, String status, Instant opensAt, Instant closesAt, String hoursSource, Set<LocalDate> closedDates, LocalDate coversFrom, LocalDate coversTo)

public boolean isTradingSession()
public boolean isEarlyClose()
```

Record accessors generated for every component: `tradingDate()`, `status()`, `opensAt()`, `closesAt()`, `hoursSource()`, `closedDates()`, `coversFrom()`, `coversTo()`.

### 4. Global State Interactions

None. The record is immutable and holds no concurrent collection or centralized state reference.
