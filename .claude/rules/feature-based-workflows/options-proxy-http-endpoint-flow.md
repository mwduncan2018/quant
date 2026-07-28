---
paths:
  - "options-proxy/**/*"
---

# HTTP Endpoint Flow

Traces the four FastAPI routes the proxy serves — `/indicator`, `/calendar`,
`/earnings`, `/health` — from `app.state` population in `lifespan` through the
provider calls each route makes to the JSON payload returned.

## 1. Workflow Components

| Component | Role in this flow |
| --- | --- |
| `main.app` | `FastAPI` instance constructed with `lifespan=lifespan`; owns the four route handlers. |
| `main.lifespan` | Async context manager that assigns `app.state.client`, `app.state.calendar`, `app.state.calculator`, `app.state.gamma_flip_provider`, `app.state.earnings_provider`, `app.state.broadcaster`. |
| `main.indicator` | Route returning the configured tickers, the expected trading date, per-ticker implied moves, and the gamma-flip status. |
| `main.calendar_view` | Route returning the resolved session, the raw merged holiday records, and the cache coverage window. |
| `main.earnings` | Route delegating to `EarningsProvider.snapshot(date)`. |
| `main.health` | Route returning overall readiness, per-ticker refresh diagnostics, the earnings block, the rate-limiter status, and the UDP counters. |
| `main._expected_trading_date` | Resolves the session every route reports, via `calendar.next_session_on_or_after`. |
| `main._current_implied_move` | Returns a stored `StaticDailyImpliedMove` only when its `trading_date` matches the expected session. |
| `main.runtime_by_ticker` | Module-level `dict[str, Runtime]` read by `/indicator` and `/health`. |
| `main.Runtime` | Dataclass exposing `value`, `last_error`, `last_attempt_at`, `last_success_at`. |
| `market_calendar.MarketCalendar` | Answers `status_on`, `is_session`, `session_hours`, `previous_session_on_or_before`, `next_session_after`, `merged_records`, `coverage`, plus `has_cache` / `cache_path` / `fetched_at` / `load_error`. |
| `manual_gamma_flip.ManualGammaFlipProvider` | Answers `status(date)` with the validity flag, the level, the retained trading date, and the last error or staleness reason. |
| `earnings.EarningsProvider` | Answers `snapshot(date)` and `missing_tickers()`, and exposes the refresh timestamps. |
| `massive_client.MassiveRestClient` | Exposes `rate_limiter` for the `/health` payload. |
| `rate_limiter.SlidingWindowRateLimiter` | Answers `status()` with `enabled`, `max_requests`, `period_seconds`. |
| `udp_broadcaster.UdpBroadcaster` | Answers `stats()` with `sent`, `errors`, `sequence`. |
| `models.StaticDailyImpliedMove` | Serialized field-by-field into `/indicator` via `dataclasses.asdict`. |
| `config.Settings` / `config.cfg` | Supplies `ticker_symbols`, `host`, `port`, `log_level`. |
| `uvicorn` | Runs `app` under `__main__` with `cfg.host`, `cfg.port`, `cfg.log_level`. |
| `mwd.trading.earnings.EarningsClient` / `mwd.trading.calendar.MarketCalendarClient` (trading engine) | The two remote consumers, reaching `/earnings` and `/calendar` through `ProxyJsonFetcher`. |

## 2. Execution Path

### State population

1. **Initiating Component:** `uvicorn`
   **Method Invocation:** `uvicorn.run(app, host=cfg.host, port=cfg.port, log_level=cfg.log_level)`
   **Receiving Component:** `main.app`

2. **Initiating Component:** `main.app`
   **Method Invocation:** `lifespan(app)` on startup
   **Receiving Component:** `main.lifespan`

3. **Initiating Component:** `main.lifespan`
   **Method Invocation:** assigns `app.state.client`, `app.state.calendar`, `app.state.calculator`, `app.state.gamma_flip_provider`, `app.state.earnings_provider`, `app.state.broadcaster`
   **Receiving Component:** `app.state`

### `GET /indicator`

4. **Initiating Component:** HTTP client
   **Method Invocation:** `GET /indicator`
   **Receiving Component:** `main.indicator`

5. **Initiating Component:** `main.indicator`
   **Method Invocation:** `getattr(app.state, "calendar", None)` and `getattr(app.state, "gamma_flip_provider", None)`
   **Receiving Component:** `app.state`

6. **Initiating Component:** `main.indicator`
   **Method Invocation:** `_expected_trading_date(calendar)` when the calendar exists, else `None`
   **Receiving Component:** `MarketCalendar.next_session_on_or_after`

7. **Initiating Component:** `main.indicator`
   **Method Invocation:** `_current_implied_move(calendar, ticker)` per `cfg.ticker_symbols` entry
   **Receiving Component:** `main.runtime_by_ticker`

8. **Initiating Component:** `main.indicator`
   **Method Invocation:** `provider.status(expected_date)`; a missing provider substitutes `{"valid": False, "last_error": "Manual gamma-flip provider is not initialized"}`
   **Receiving Component:** `ManualGammaFlipProvider`

9. **Initiating Component:** `main.indicator`
   **Method Invocation:** `asdict(value)` per non-`None` implied move, assembling `{"tickers", "expected_trading_date", "static_daily_implied_moves", "spy_gamma_flip"}`
   **Receiving Component:** the JSON response

### `GET /calendar`

10. **Initiating Component:** trading engine `MarketCalendarRefresher` → `MarketCalendarClient` → `ProxyJsonFetcher.fetchObject()`
    **Method Invocation:** `GET /calendar`
    **Receiving Component:** `main.calendar_view`

11. **Initiating Component:** `main.calendar_view`
    **Method Invocation:** `getattr(app.state, "calendar", None)`; a `None` calendar returns `{"session": None, "holidays": [], "cache": {"present": False, "last_error": "Calendar is not initialized"}}`
    **Receiving Component:** `app.state`

12. **Initiating Component:** `main.calendar_view`
    **Method Invocation:** `_expected_trading_date(calendar)`, `datetime.now(EASTERN).date()`, `calendar.session_hours(expected_date)` returning `(opens_at, closes_at, hours_source)`, `calendar.coverage()`
    **Receiving Component:** `MarketCalendar`

13. **Initiating Component:** `main.calendar_view`
    **Method Invocation:** `calendar.status_on(expected_date)`, `calendar.is_session(expected_date)`, `calendar.previous_session_on_or_before(expected_date - timedelta(days=1))`, `calendar.next_session_after(expected_date)`
    **Receiving Component:** `MarketCalendar`

14. **Initiating Component:** `main.calendar_view`
    **Method Invocation:** `calendar.merged_records()`, which collapses the per-exchange duplicates and lets the more restrictive status win
    **Receiving Component:** `MarketCalendar`

15. **Initiating Component:** `main.calendar_view`
    **Method Invocation:** assembles `{"trading_date", "today", "session": {...}, "holidays": [...], "cache": {"present", "path", "fetched_at", "covers_from", "covers_to", "last_error"}}`
    **Receiving Component:** the JSON response → `MarketCalendarClient.decode(JsonNode)`

### `GET /earnings`

16. **Initiating Component:** trading engine `EarningsRefresher` → `EarningsClient` → `ProxyJsonFetcher.fetchObject()`
    **Method Invocation:** `GET /earnings`
    **Receiving Component:** `main.earnings`

17. **Initiating Component:** `main.earnings`
    **Method Invocation:** `getattr(app.state, "earnings_provider", None)`; a `None` provider returns a payload with empty `stocks`, full `missing_tickers`, and `"last_error": "Earnings retrieval is disabled"`
    **Receiving Component:** `app.state`

18. **Initiating Component:** `main.earnings`
    **Method Invocation:** `provider.snapshot(expected_date)`
    **Receiving Component:** `EarningsProvider` → the JSON response → `EarningsClient.decode(JsonNode)`

### `GET /health`

19. **Initiating Component:** HTTP client
    **Method Invocation:** `GET /health`
    **Receiving Component:** `main.health`

20. **Initiating Component:** `main.health`
    **Method Invocation:** `_expected_trading_date(calendar)`, `_current_implied_move(calendar, ticker)` per ticker, building `missing_tickers`
    **Receiving Component:** `MarketCalendar`, `main.runtime_by_ticker`

21. **Initiating Component:** `main.health`
    **Method Invocation:** `provider.status(expected_date)`, then `ready = not missing_tickers and bool(gamma_status["valid"])`
    **Receiving Component:** `ManualGammaFlipProvider`

22. **Initiating Component:** `main.health`
    **Method Invocation:** `earnings_provider.missing_tickers()` plus `refreshed_for_date`, `fetched_at`, `last_attempt_at`, `last_success_at`, `last_error or load_error`; a `None` provider yields `{"status": "disabled"}`
    **Receiving Component:** `EarningsProvider`

23. **Initiating Component:** `main.health`
    **Method Invocation:** `client.rate_limiter.status()` and `broadcaster.stats()`
    **Receiving Component:** `SlidingWindowRateLimiter`, `UdpBroadcaster`

24. **Initiating Component:** `main.health`
    **Method Invocation:** assembles `{"status", "tickers", "expected_trading_date", "static_daily_implied_move_coverage", "manual_spy_gamma_flip", "per_ticker_refresh", "earnings", "massive_rate_limit", "udp"}`
    **Receiving Component:** the JSON response

### Shutdown

25. **Initiating Component:** `main.lifespan` `finally` block
    **Method Invocation:** `task.cancel()` and `await task` for every background task, then `broadcaster.close()` and `await client.close()`
    **Receiving Component:** the loop tasks, `UdpBroadcaster`, `MassiveRestClient`

## 3. Data Payloads and State Handoffs

### Objects passed

- `app.state` — the single handoff surface between `lifespan` and the route handlers; every route reads it with `getattr(app.state, name, None)` and degrades to an explicit "not initialized"/"disabled" payload rather than raising.
- `date expected_trading_date` — recomputed per request by `_expected_trading_date`; it is the same value the UDP frames are stamped with, so a consumer comparing the two sees one session identity.
- `StaticDailyImpliedMove` — serialized whole by `asdict(value)` on `/indicator`, exposing the full provenance (`source_session_date`, `expiration_date`, `atm_strike`, `call_ticker`, `put_ticker`, `call_close`, `put_close`, `expiration_implied_move`, `remaining_trading_sessions`, `calculated_at`); `None` for an uncovered ticker.
- Gamma-flip status dict — `{"path", "valid", "ticker", "spy_gamma_flip", "trading_date", "loaded_at", "last_checked_at", "last_loaded_at", "last_error", "retaining_last_valid_value"}`; `valid` is `False` whenever the retained value belongs to a different session, and `last_error` then carries the staleness reason.
- `/calendar` session block — `{"trading_date", "status", "is_session", "opens_at", "closes_at", "hours_source", "previous_session", "next_session"}`. `closes_at` is the field the engine requires: `MarketCalendarClient.decode` throws `ProxyFetchException` when a non-`closed` status arrives without one.
- `/calendar` cache block — `{"present", "path", "fetched_at", "covers_from", "covers_to", "last_error"}`; the engine's `MarketCalendarStore.coversDate` is built from `covers_to`, so a date beyond the window is treated as unknown rather than open.
- `/calendar` holidays array — `merged_records()` output, one entry per date with `{"date", "status", "name", "opens_at", "closes_at"}`; the engine keeps only the entries whose `status` is `"closed"`.
- `/earnings` payload — see the earnings refresh flow; `valid` per ticker is the flag the engine's decoder honours before recording an entry.
- `/health` payload — read by operators only; no trading-engine component consumes it.

### Task and thread handoffs

| Handoff | Detail |
| --- | --- |
| `uvicorn` → `lifespan` | Startup runs the context manager to its `yield`, populating `app.state` and creating the background tasks before the first request is served. |
| Request handler ↔ background loops | All four routes are coroutines on the same event loop as `_refresh_loop`, `_gamma_flip_loop`, `_broadcast_loop`, and `_earnings_loop`; they interleave at `await` points and share `runtime_by_ticker` and the provider objects directly, with no lock. |
| Request handler → provider | `snapshot`, `status`, `stats`, and every `MarketCalendar` query are synchronous, non-awaiting calls, so each response is assembled from one consistent point in time. |
| `/earnings` and `/calendar` → trading engine | Cross-process HTTP request/response; the engine's `Earnings-Refresher-Thread` and `Market-Calendar-Refresher-Thread` each block in `HttpClient.send` while the proxy's event loop serves the route. |
| `refresh_lock` | Not taken by any route handler; a request issued during an in-flight implied-move calculation reads whatever `runtime_by_ticker` currently holds rather than waiting. |
| `lifespan` teardown | Cancels the background tasks and closes the broadcaster socket and `httpx.AsyncClient` after the server stops accepting requests. |

None of these routes performs a Massive request. `/health` reports the rate
limiter's configuration through `status()` but never calls `acquire()`, so polling
the endpoints consumes no upstream quota.
