---
paths:
  - "options-proxy/**/*"
---

# Static Daily Implied Move Calculation Flow

Traces the background refresh loop that produces one `StaticDailyImpliedMove` per
configured ticker per trading session, from the Massive REST calls through ATM
pair selection to the module-level runtime state the broadcaster reads.

## 1. Workflow Components

| Component | Role in this flow |
| --- | --- |
| `main` (module) | Owns `runtime_by_ticker`, `refresh_lock`, `holiday_refresh_date`, and the `_refresh_loop` task created in `lifespan`. |
| `main.Runtime` | Dataclass holding `value`, `last_error`, `last_attempt_at`, `last_success_at` for one ticker. |
| `main.lifespan` | Async context manager that constructs the client, calendar, and calculator and creates the loop tasks. |
| `main._refresh_loop` | Coroutine that identifies uncovered tickers, refreshes the calendar, and calls `_refresh` per ticker. |
| `main._refresh` | Coroutine that runs one `calculate` under `refresh_lock` and records the result or error. |
| `main._refresh_calendar` | Coroutine that fetches holidays at most once per expected trading date. |
| `main._expected_trading_date` | Resolves the session the values are stamped with. |
| `main._current_implied_move` | Returns the stored value only when its `trading_date` matches the expected session. |
| `implied_move.StaticDailyImpliedMoveCalculator` | Performs the whole calculation: previous bars, expiry resolution, contract listing, ATM pair, option closes, session count. |
| `implied_move.select_atm_pair` | Chooses the nearest standard strike holding both a call and a put. |
| `implied_move.ImpliedMoveError` | Raised for any condition that makes the value untrustworthy. |
| `massive_client.MassiveRestClient` | REST client exposing `fetch_market_holidays`, `fetch_previous_bar`, `fetch_option_contracts`. |
| `massive_client.MassiveApiError` | Raised for transport, status, and shape failures. |
| `rate_limiter.SlidingWindowRateLimiter` | Reserves one start per HTTP attempt within the configured rolling window. |
| `market_calendar.MarketCalendar` | Holiday cache plus session arithmetic (`next_session_on_or_after`, `next_session_after`, `previous_session_on_or_before`, `sessions_between`, `nominal_friday`, `merge_and_save`). |
| `models.DailyBar` | Frozen dataclass `(ticker, session_date, close, timestamp_ms)`. |
| `models.OptionContract` | Frozen dataclass with `is_standard`, used for pair selection. |
| `models.MarketHoliday` | Frozen dataclass merged into the calendar cache. |
| `models.StaticDailyImpliedMove` | Frozen dataclass holding the result and its full provenance. |
| `config.Settings` / `config.cfg` | Supplies `ticker_symbols`, `massive_api_key`, `massive_base_url`, `rest_*`, `implied_move_multiplier`, `atm_strike_window_pct`, `calculation_check_interval_seconds`, `holiday_cache_path`. |
| `httpx.AsyncClient` | Transport used by `MassiveRestClient`. |

## 2. Execution Path

### Loop startup

1. **Initiating Component:** `main.lifespan`
   **Method Invocation:** `MassiveRestClient()`, `MarketCalendar(cfg.holiday_cache_path)`, `StaticDailyImpliedMoveCalculator(client, calendar)`
   **Receiving Component:** `MassiveRestClient`, `MarketCalendar`, `StaticDailyImpliedMoveCalculator`

2. **Initiating Component:** `main.lifespan`
   **Method Invocation:** `asyncio.create_task(_refresh_loop(client, calendar, calculator))`
   **Receiving Component:** the event loop

### One refresh pass

3. **Initiating Component:** `main._refresh_loop`
   **Method Invocation:** `_expected_trading_date(calendar)` → `calendar.next_session_on_or_after(datetime.now(EASTERN).date())`
   **Receiving Component:** `MarketCalendar`

4. **Initiating Component:** `main._refresh_loop`
   **Method Invocation:** `_current_implied_move(calendar, ticker)` for each `cfg.ticker_symbols` entry, building `missing_tickers`
   **Receiving Component:** `main.runtime_by_ticker`

5. **Initiating Component:** `main._refresh_loop`
   **Method Invocation:** `await _refresh_calendar(client, calendar, expected_date)`
   **Receiving Component:** `main._refresh_calendar`

6. **Initiating Component:** `main._refresh_calendar`
   **Method Invocation:** `await client.fetch_market_holidays()` then `calendar.merge_and_save(holidays)`; on `MassiveApiError`/`OSError` with no cache it writes `last_error` on every `Runtime` and returns `False`
   **Receiving Component:** `MassiveRestClient`, `MarketCalendar`

7. **Initiating Component:** `main._refresh_loop`
   **Method Invocation:** `await _refresh(ticker, calculator)` for each missing ticker
   **Receiving Component:** `main._refresh`

8. **Initiating Component:** `main._refresh`
   **Method Invocation:** `async with refresh_lock`, sets `state.last_attempt_at`, then `await calculator.calculate(ticker)`
   **Receiving Component:** `StaticDailyImpliedMoveCalculator`

### The calculation

9. **Initiating Component:** `StaticDailyImpliedMoveCalculator.calculate`
   **Method Invocation:** `await self.client.fetch_previous_bar(ticker)`
   **Receiving Component:** `MassiveRestClient` → `GET /v2/aggs/ticker/{ticker}/prev` → `DailyBar`

10. **Initiating Component:** `StaticDailyImpliedMoveCalculator.calculate`
    **Method Invocation:** `self.calendar.next_session_on_or_after(eastern_now.date())`, and `self.calendar.next_session_after(underlying_bar.session_date)` when the bar's session is not before the resolved date
    **Receiving Component:** `MarketCalendar`

11. **Initiating Component:** `StaticDailyImpliedMoveCalculator.calculate`
    **Method Invocation:** `self.calendar.nominal_friday(trading_date)` then `self.calendar.previous_session_on_or_before(nominal_friday)`; an expiration before the trading date raises `ImpliedMoveError`
    **Receiving Component:** `MarketCalendar`

12. **Initiating Component:** `StaticDailyImpliedMoveCalculator.calculate`
    **Method Invocation:** `await self.client.fetch_option_contracts(underlying_ticker=..., expiration_date=..., as_of=..., strike_gte=close * (1 - window), strike_lte=close * (1 + window))`
    **Receiving Component:** `MassiveRestClient` → paginated `GET /v3/reference/options/contracts` → `list[OptionContract]`

13. **Initiating Component:** `StaticDailyImpliedMoveCalculator.calculate`
    **Method Invocation:** `select_atm_pair(contracts, underlying_bar.close)`
    **Receiving Component:** `implied_move.select_atm_pair` → `(OptionContract call, OptionContract put)`

14. **Initiating Component:** `StaticDailyImpliedMoveCalculator.calculate`
    **Method Invocation:** `await asyncio.gather(self.client.fetch_previous_bar(call.ticker), self.client.fetch_previous_bar(put.ticker))`
    **Receiving Component:** `MassiveRestClient` (two concurrent requests) → two `DailyBar` values

15. **Initiating Component:** `StaticDailyImpliedMoveCalculator.calculate`
    **Method Invocation:** compares each option bar's `session_date` against the underlying bar's; a mismatch raises `ImpliedMoveError`
    **Receiving Component:** `implied_move`

16. **Initiating Component:** `StaticDailyImpliedMoveCalculator.calculate`
    **Method Invocation:** `self.calendar.sessions_between(underlying_bar.session_date, expiration_date)`; an empty range or a `trading_date` outside it raises `ImpliedMoveError`
    **Receiving Component:** `MarketCalendar`

17. **Initiating Component:** `StaticDailyImpliedMoveCalculator.calculate`
    **Method Invocation:** `expiration_move = settings.implied_move_multiplier * (call_bar.close + put_bar.close)`, `daily_move = expiration_move * math.sqrt(1.0 / remaining_sessions)`, then constructs and returns `StaticDailyImpliedMove(...)`
    **Receiving Component:** `models.StaticDailyImpliedMove`

18. **Initiating Component:** `main._refresh`
    **Method Invocation:** `state.value = value`, `state.last_error = None`, `state.last_success_at = datetime.now(UTC)`; an `ImpliedMoveError`/`OSError` sets `state.last_error` and returns without touching `state.value`
    **Receiving Component:** `main.runtime_by_ticker`

### The HTTP layer under every call above

19. **Initiating Component:** `MassiveRestClient._get_json`
    **Method Invocation:** `await self.rate_limiter.acquire()` before each attempt
    **Receiving Component:** `SlidingWindowRateLimiter`

20. **Initiating Component:** `MassiveRestClient._get_json`
    **Method Invocation:** `async with self._sem` (an `asyncio.Semaphore(settings.rest_concurrency)`), then `await client.get(url, params=params)`
    **Receiving Component:** `httpx.AsyncClient`

21. **Initiating Component:** `MassiveRestClient._ensure_client`
    **Method Invocation:** raises `MassiveApiError` when `massive_api_key` is empty; otherwise builds `httpx.AsyncClient` with the bearer header and connection limits
    **Receiving Component:** `httpx.AsyncClient`

22. **Initiating Component:** `MassiveRestClient._get_json`
    **Method Invocation:** on HTTP 429 or 5xx retries up to `rest_max_retries` with exponential backoff honouring `Retry-After`; any other non-200 raises `MassiveApiError` immediately
    **Receiving Component:** caller

23. **Initiating Component:** `main._refresh_loop`
    **Method Invocation:** `await asyncio.sleep(cfg.calculation_check_interval_seconds)`
    **Receiving Component:** the event loop

## 3. Data Payloads and State Handoffs

### Objects passed

- `date expected_date` — produced by `_expected_trading_date` and used both as the coverage key for `runtime_by_ticker` and as the guard on `holiday_refresh_date`.
- `list[MarketHoliday]` — decoded from `/v1/marketstatus/upcoming` and merged into `MarketCalendar._records` keyed by `(date, exchange)`, filtered to `NYSE`/`NASDAQ`, then written to disk atomically via `tempfile.mkstemp` + `os.fsync` + `os.replace`.
- `DailyBar` — one for the underlying and one for each of the ATM call and put; the underlying's `session_date` is the reference every other bar must match.
- `list[OptionContract]` — the strike window returned by Massive; `select_atm_pair` filters on `is_standard` (100 shares per contract, no additional underlyings) and `contract_type in {"call", "put"}`.
- `StaticDailyImpliedMove` — frozen dataclass carrying `ticker`, `trading_date`, `source_session_date`, `expiration_date`, `underlying_close`, `atm_strike`, `call_ticker`, `put_ticker`, `call_close`, `put_close`, `expiration_implied_move`, `remaining_trading_sessions`, `static_daily_implied_move`, `calculated_at`; stored on `Runtime.value` and read later by `_broadcast_loop` and `/indicator`.
- `Runtime` — mutable per-ticker state; the only field the broadcast path reads is `value`.

### Task and thread handoffs

| Handoff | Detail |
| --- | --- |
| `lifespan` → `_refresh_loop` | `asyncio.create_task`; the loop is an independent task on the same event loop as the FastAPI request handlers. |
| `_refresh_loop` ↔ concurrent calculations | `refresh_lock` (`asyncio.Lock`) is held for the whole of `_refresh`, so only one ticker is calculated at a time. |
| `_refresh_loop` → `_gamma_flip_loop` / `_broadcast_loop` / `_earnings_loop` | No direct call; the loops communicate only through the module-level `runtime_by_ticker` dict, which is safe because all four are tasks on one event loop. |
| `MassiveRestClient` → Massive | Cross-process HTTP over `httpx.AsyncClient`; every attempt is gated by `SlidingWindowRateLimiter.acquire()` (an `asyncio.Lock` plus a `deque` of start times) and by `asyncio.Semaphore(rest_concurrency)`. |
| `asyncio.gather` on the call and put bars | Two concurrent `fetch_previous_bar` coroutines; both still pass through the shared rate limiter, so they serialize against its window rather than against each other. |
| `MarketCalendar.merge_and_save` | Blocking file I/O executed inline on the event loop. |
| Task cancellation | `lifespan`'s `finally` block calls `task.cancel()` then awaits each task, suppressing `asyncio.CancelledError`, before `broadcaster.close()` and `await client.close()`. |

There are no OS threads in this flow. Every step above runs as a coroutine on the
single uvicorn event loop; the only blocking operations are the calendar cache
file writes.
