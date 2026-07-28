---
paths:
  - "trading-engine/**/*"
---

# Packages `mwd.trading.persistence` and `mwd.trading.proxy`

Sources:
- `trading-engine/trading-engine/src/main/java/mwd/trading/persistence/JsonTradingStateStore.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/proxy/ProxyJsonFetcher.java`
- `trading-engine/trading-engine/src/main/java/mwd/trading/proxy/ProxyFetchException.java`

---

## `JsonTradingStateStore`

`public final class JsonTradingStateStore`

### 1. Class/Interface Responsibilities

Loads and writes a versioned JSON journal of `TradeRecord` entries keyed by trade ID in a `LinkedHashMap`, persisting through a temporary file, a backup copy, and an atomic move.

### 2. Injected Dependencies

Constructor: `public JsonTradingStateStore(Path statePath) throws IOException`

| Parameter | Exact type |
| --- | --- |
| `statePath` | `java.nio.file.Path` |

Derived inside the constructor: `temporaryPath` (`Path`), `backupPath` (`Path`), `objectMapper` (`com.fasterxml.jackson.databind.ObjectMapper` with `SerializationFeature.INDENT_OUTPUT`). The constructor calls `load()`.

Static field: `public static final int SCHEMA_VERSION = 1`.

### 3. Method Signatures

Nested types:
- `public record LegRecord(String role, int apiOrderId, long permanentId, String orderReference, String status, String filledQuantity, String remainingQuantity, boolean acknowledged)`
- `public record TradeRecord(String tradeId, String strategy, String account, int contractId, String symbol, String action, String totalQuantity, double entryPrice, String status, long createdAtEpochMillis, long updatedAtEpochMillis, List<LegRecord> legs)` with `public boolean terminal()`
- `public record StateFile(int schemaVersion, long writtenAtEpochMillis, List<TradeRecord> trades)`

```java
public JsonTradingStateStore(Path statePath) throws IOException

public synchronized boolean recoveredFromBackup()
public synchronized StateFile snapshot()
public synchronized List<TradeRecord> activeTrades()
public synchronized Optional<TradeRecord> findTrade(String tradeId)
public synchronized void recordIntent(BracketOrder bracketOrder, String action) throws IOException
public synchronized void recordBrokerUpdate(BracketOrder bracketOrder, String action) throws IOException

private void load() throws IOException
private void loadFrom(Path source) throws IOException
private void persist() throws IOException
private static LegRecord toLegRecord(BracketOrder.OrderLegState leg)
private static String decimalText(Decimal value)
private static String backupFileName(Path path)
```

### 4. Global State Interactions

**Concurrent collections**

None. The single collection field is `private final Map<String, TradeRecord> tradesById = new LinkedHashMap<>()`, guarded by the `synchronized` public methods.

| Method | Interaction |
| --- | --- |
| `snapshot()` | Reads `tradesById` |
| `activeTrades()` | Reads `tradesById` |
| `findTrade(String)` | Reads `tradesById` |
| `recordIntent(BracketOrder, String)` | Reads and mutates `tradesById` (`get`, `put`); calls `persist()` |
| `recordBrokerUpdate(BracketOrder, String)` | Delegates to `recordIntent` |
| `loadFrom(Path)` | Mutates `tradesById` (`clear`, `put`) |

Instance field `recoveredFromBackup` (`boolean`) is written by `load()` and read by `recoveredFromBackup()`.

**Centralized state objects**

No `Blackboard` reference. `recordIntent` reads the supplied `BracketOrder`, including its `synchronized getLegStates()`.

**Filesystem state**

`persist()` writes `temporaryPath` via `FileChannel` with `force(true)`, copies `statePath` to `backupPath`, then moves the temporary file over `statePath` using `ATOMIC_MOVE` with a non-atomic fallback.

---

## `ProxyJsonFetcher`

`public final class ProxyJsonFetcher`

### 1. Class/Interface Responsibilities

Issues one GET request to an options-proxy endpoint through a `java.net.http.HttpClient` and returns the body as a Jackson `JsonNode` object, with static helpers for object parsing and strict `YYYY-MM-DD` date reading.

### 2. Injected Dependencies

Constructor: `public ProxyJsonFetcher(String endpointUrl, long connectTimeoutMs, long requestTimeoutMs)`

| Parameter | Exact type |
| --- | --- |
| `endpointUrl` | `java.lang.String` (null-checked, parsed with `URI.create`) |
| `connectTimeoutMs` | `long` (must be positive) |
| `requestTimeoutMs` | `long` (must be positive) |

Constructed inside the constructor: `httpClient` (`HttpClient` with `Redirect.NEVER`), `endpoint` (`URI`), `requestTimeout` (`Duration`).

Static field: `private static final ObjectMapper MAPPER`.

### 3. Method Signatures

```java
public ProxyJsonFetcher(String endpointUrl, long connectTimeoutMs, long requestTimeoutMs)

public JsonNode fetchObject() throws ProxyFetchException
public static JsonNode parseObject(String body) throws ProxyFetchException
public static LocalDate readDate(JsonNode node)
public URI getEndpoint()
```

### 4. Global State Interactions

**Concurrent collections**

None. All three instance fields are `final` and set in the constructor.

**Centralized state objects**

No `Blackboard` reference. The shared static `ObjectMapper MAPPER` is used by `parseObject(String)`.

---

## `ProxyFetchException`

`public final class ProxyFetchException extends Exception`

### 1. Class/Interface Responsibilities

Checked exception signalling that a proxy endpoint could not be reached, read, or decoded.

### 2. Injected Dependencies

Constructors:
- `public ProxyFetchException(String message)` — `message` is `java.lang.String`
- `public ProxyFetchException(String message, Throwable cause)` — `message` is `java.lang.String`, `cause` is `java.lang.Throwable`

### 3. Method Signatures

```java
public ProxyFetchException(String message)
public ProxyFetchException(String message, Throwable cause)
```

Field: `private static final long serialVersionUID = 1L`.

### 4. Global State Interactions

None.
