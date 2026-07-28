---
paths:
  - "trading-engine/**/*"
---

# Proxy Reference-Data Flow (HTTP Pull)

Traces the two request/response pulls the engine makes against the options proxy:
`/earnings` and `/calendar`. Both run on dedicated daemon threads, decode into a
record, and hand the record to a session-scoped store that strategies read.

## 1. Workflow Components

| Component | Role in this flow |
| --- | --- |
| `mwd.trading.app.Main` | Constructs both client/store/refresher triples, starts their threads, and supplies the earnings monitor-mirroring `Consumer`. |
| `mwd.trading.config.Config` | Supplies `isEarningsEnabled()`, `getEarningsEndpointUrl()`, `getMarketCalendarEndpointUrl()`, `getEarningsConnectTimeoutMs()`, `getEarningsRequestTimeoutMs()`, `getEarningsRetryDelayMs()`. |
| `mwd.trading.proxy.ProxyJsonFetcher` | Transport: builds a `java.net.http.HttpClient`, issues one GET, rejects non-200 and non-object bodies, and exposes `parseObject(String)` / `readDate(JsonNode)`. |
| `mwd.trading.proxy.ProxyFetchException` | Checked exception raised for any transport or shape failure. |
| `mwd.trading.earnings.EarningsClient` | Decodes `/earnings` into an `EarningsSnapshot`; drops entries whose `valid` flag is false or whose dates are unparseable. |
| `mwd.trading.earnings.EarningsSnapshot` | Record `(LocalDate tradingDate, Map<String, EarningsDate> byTicker)`. |
| `mwd.trading.earnings.EarningsSnapshot.EarningsDate` | Record `(String ticker, LocalDate nextEarningsDate, LocalDate lastEarningsDate)` with `knownDates()`. |
| `mwd.trading.earnings.EarningsStore` | Accepts a whole snapshot only when its `tradingDate` matches the expected session; answers `earningsDate`, `isReadyFor`, `describeUnready`. |
| `mwd.trading.earnings.EarningsRefresher` | `Runnable` that fetches once per trading date and retries on failure. |
| `mwd.trading.calendar.MarketCalendarClient` | Decodes `/calendar` into a `MarketSession`, requiring a `session` object, a `trading_date`, a `status`, and a close instant for any non-closed status. |
| `mwd.trading.calendar.MarketSession` | Record `(LocalDate tradingDate, String status, Instant opensAt, Instant closesAt, String hoursSource, Set<LocalDate> closedDates, LocalDate coversFrom, LocalDate coversTo)`. |
| `mwd.trading.calendar.MarketCalendarStore` | Accepts a session for the expected date; answers `sessionClose`, `isWithinOfClose`, `isSession`, `coversDate`, `nextSession`, `previousSessionApproximate`, `describeUnready`. |
| `mwd.trading.calendar.MarketCalendarRefresher` | `Runnable` mirroring `EarningsRefresher` for the calendar. |
| `com.fasterxml.jackson.databind.JsonNode` / `ObjectMapper` | JSON tree used by both decoders. |
| `mwd.trading.state.Blackboard` / `mwd.trading.domain.Stock` | Reached only by the earnings monitor-mirroring lambda. |
| `mwd.trading.strategy.TwoSigmaDownsideMeanReversionStrategy` | Reads both stores: the calendar for the entry window and time exit, the earnings store for the blackout test. |
| `mwd.trading.strategy.OneSigmaDownsideMeanReversionStrategy` / `OneSigmaUpsideMeanReversionStrategy` | Read the calendar store only. |

## 2. Execution Path

### Earnings pull (`Earnings-Refresher-Thread`)

1. **Initiating Component:** `Main.main(String[])`
   **Method Invocation:** `new EarningsStore(Set.copyOf(marketDataSymbols))`, then when `config.isEarningsEnabled()` — `new EarningsRefresher(new EarningsClient(config.getEarningsEndpointUrl(), config.getEarningsConnectTimeoutMs(), config.getEarningsRequestTimeoutMs()), earningsStore, config.getEarningsRetryDelayMs(), PROXY_IDLE_RECHECK_MS, snapshot -> mirrorEarningsForMonitor(blackboard, snapshot))`
   **Receiving Component:** `EarningsStore`, `EarningsClient`, `EarningsRefresher`

2. **Initiating Component:** `Main.main(String[])`
   **Method Invocation:** `new Thread(earningsRefresher, "Earnings-Refresher-Thread")`, `setDaemon(true)`, `start()`
   **Receiving Component:** `EarningsRefresher.run()`

3. **Initiating Component:** `EarningsRefresher.run()`
   **Method Invocation:** `refreshOnce()`, then `Thread.sleep(idleDelayMs)` on success or `Thread.sleep(retryDelayMs)` on failure
   **Receiving Component:** `EarningsRefresher`

4. **Initiating Component:** `EarningsRefresher.refreshOnce()`
   **Method Invocation:** `LocalDate.now(newYorkClock)` then `store.isReadyFor(LocalDate)`; a covered session returns immediately without a request
   **Receiving Component:** `EarningsStore`

5. **Initiating Component:** `EarningsRefresher.refreshOnce()`
   **Method Invocation:** `client.fetch()`
   **Receiving Component:** `EarningsClient`

6. **Initiating Component:** `EarningsClient.fetch()`
   **Method Invocation:** `fetcher.fetchObject()`
   **Receiving Component:** `ProxyJsonFetcher`

7. **Initiating Component:** `ProxyJsonFetcher.fetchObject()`
   **Method Invocation:** `HttpRequest.newBuilder(endpoint).timeout(requestTimeout).header("Accept", "application/json").GET().build()` then `httpClient.send(request, HttpResponse.BodyHandlers.ofString())`; a non-200 status or non-object body throws `ProxyFetchException`
   **Receiving Component:** `java.net.http.HttpClient`, options-proxy `/earnings`

8. **Initiating Component:** `EarningsClient.decode(JsonNode)`
   **Method Invocation:** `ProxyJsonFetcher.readDate(root.path("trading_date"))`, `root.path("stocks")`, then per entry `value.path("valid").asBoolean(false)` and `readDate` on `next_earnings_date` / `last_earnings_date`
   **Receiving Component:** `EarningsSnapshot`, `EarningsSnapshot.EarningsDate`

9. **Initiating Component:** `EarningsRefresher.refreshOnce()`
   **Method Invocation:** `store.accept(EarningsSnapshot snapshot, LocalDate expectedTradingDate, long receivedAtUnixMs)`
   **Receiving Component:** `EarningsStore`

10. **Initiating Component:** `EarningsStore.accept(...)`
    **Method Invocation:** rejects when `snapshot.tradingDate()` differs from the expected date; otherwise filters to `knownTickers` and calls `accepted.set(new Accepted(tradingDate, Map.copyOf(retained), receivedAtUnixMs))`
    **Receiving Component:** `AtomicReference<Accepted>`

11. **Initiating Component:** `EarningsRefresher.refreshOnce()`
    **Method Invocation:** `acceptedListener.accept(snapshot)`
    **Receiving Component:** `Main.mirrorEarningsForMonitor(Blackboard, EarningsSnapshot)`

12. **Initiating Component:** `Main.mirrorEarningsForMonitor(Blackboard, EarningsSnapshot)`
    **Method Invocation:** `blackboard.getStock(ticker).setNextEarningsDate(value.nextEarningsDate().atTime(LocalTime.NOON).atZone(ZoneId.of("America/New_York")).toInstant())`
    **Receiving Component:** `Stock`

### Calendar pull (`Market-Calendar-Refresher-Thread`)

13. **Initiating Component:** `Main.main(String[])`
    **Method Invocation:** `new MarketCalendarStore()` and `new MarketCalendarRefresher(new MarketCalendarClient(config.getMarketCalendarEndpointUrl(), config.getEarningsConnectTimeoutMs(), config.getEarningsRequestTimeoutMs()), marketCalendarStore, config.getEarningsRetryDelayMs(), PROXY_IDLE_RECHECK_MS)`, then `new Thread(marketCalendarRefresher, "Market-Calendar-Refresher-Thread").start()`
    **Receiving Component:** `MarketCalendarStore`, `MarketCalendarClient`, `MarketCalendarRefresher`

14. **Initiating Component:** `MarketCalendarRefresher.refreshOnce()`
    **Method Invocation:** `store.isReadyFor(LocalDate.now(newYorkClock))`, then `client.fetch()`
    **Receiving Component:** `MarketCalendarStore`, `MarketCalendarClient`

15. **Initiating Component:** `MarketCalendarClient.decode(JsonNode)`
    **Method Invocation:** `root.path("session")`, `ProxyJsonFetcher.readDate(session.path("trading_date"))`, `session.path("status")`, `readInstant(session.path("opens_at"))`, `readInstant(session.path("closes_at"))`, iteration of `root.path("holidays")` collecting dates whose `status` is `"closed"`, and `readDate` on `cache.covers_from` / `cache.covers_to`
    **Receiving Component:** `MarketSession`

16. **Initiating Component:** `MarketCalendarRefresher.refreshOnce()`
    **Method Invocation:** `store.accept(MarketSession candidate, LocalDate expectedTradingDate)`
    **Receiving Component:** `MarketCalendarStore` → `AtomicReference<MarketSession>.set(...)`

### Strategy consumption (strategy threads)

17. **Initiating Component:** a concrete strategy's `isEntryConditionMet(Stock)`
    **Method Invocation:** `marketCalendarStore.isWithinOfClose(LocalDate, Instant, Duration)` and `marketCalendarStore.sessionClose(LocalDate)`
    **Receiving Component:** `MarketCalendarStore`

18. **Initiating Component:** a concrete strategy's `calculateTimeExit()`
    **Method Invocation:** `marketCalendarStore.sessionClose(currentTradingDate()).map(close -> close.minus(EXIT_BEFORE_CLOSE).toEpochMilli()).orElse(0L)`
    **Receiving Component:** `MarketCalendarStore`

19. **Initiating Component:** `TwoSigmaDownsideMeanReversionStrategy.isInEarningsBlackout(String, LocalDate)`
    **Method Invocation:** `earningsStore.earningsDate(String ticker, LocalDate tradingDate)`, then `marketCalendarStore.nextSession(LocalDate)` and `marketCalendarStore.previousSessionApproximate(LocalDate)`, then `EarningsDate.knownDates()`
    **Receiving Component:** `EarningsStore`, `MarketCalendarStore`, `EarningsSnapshot.EarningsDate`

### Shutdown

20. **Initiating Component:** `Trading-Engine-Shutdown` hook thread
    **Method Invocation:** `earningsRefresherForShutdown.interrupt()` and `marketCalendarForShutdown.interrupt()`
    **Receiving Component:** both refresher threads

## 3. Data Payloads and State Handoffs

### Objects passed

- HTTP request: a GET with `Accept: application/json`, `HttpClient.Redirect.NEVER`, a connect timeout from `getEarningsConnectTimeoutMs()`, and a request timeout from `getEarningsRequestTimeoutMs()`.
- HTTP response body: parsed by `ProxyJsonFetcher.parseObject(String)` into a Jackson `JsonNode`, which is the only form the decoders see.
- `EarningsSnapshot` — immutable; `byTicker` is a `Map.copyOf`. Entries are present only when the proxy flagged them `valid` and at least one date parsed.
- `EarningsStore.Accepted` — private record `(LocalDate tradingDate, Map<String, EarningsDate> byTicker, long receivedAtUnixMs)` held in an `AtomicReference` and replaced whole; no per-ticker merging occurs.
- `MarketSession` — immutable record with a `Set.copyOf` of closed dates, held in an `AtomicReference` in `MarketCalendarStore`.
- `Optional<EarningsDate>`, `Optional<Instant>`, `Optional<LocalDate>`, `Optional<String>` — the read-side payloads returned to strategies.
- `Consumer<EarningsSnapshot>` — the `Main` lambda that mirrors dates onto `Stock.nextEarningsDate` for display only.

### Thread handoffs

| Handoff | Detail |
| --- | --- |
| Main thread → `Earnings-Refresher-Thread` and `Market-Calendar-Refresher-Thread` | Explicit daemon `Thread` construction and `start()` in `Main`. |
| Refresher thread → options-proxy process | Blocking `HttpClient.send` performs the cross-process request/response on the refresher thread. |
| Refresher thread → `<Strategy>-Thread` | Strategies read the stores through `AtomicReference` holders of immutable records, so a partially applied snapshot is never observable. |
| `Earnings-Refresher-Thread` → Swing EDT | The accepted-frame `Consumer` writes `Stock.nextEarningsDate` (a plain field) on the refresher thread; `BlackboardMonitor` reads it on the event-dispatch thread. |
| Shutdown hook thread → refresher threads | `Thread.interrupt()`, which the `Thread.sleep` in each `run()` loop converts into a re-set interrupt flag ending the loop. |

Both stores keep `AtomicLong` accepted/rejected counters and an
`AtomicReference<String>` last-rejection reason. Neither refresher retries inside
the client: `ProxyJsonFetcher` performs exactly one request per call, and the
refresher's loop delay is the only retry mechanism.
