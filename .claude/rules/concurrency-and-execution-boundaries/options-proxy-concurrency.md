---
paths:
  - "options-proxy/**/*"
---

# Options Proxy — Concurrency Model and Execution Boundaries

Extracted from `options-proxy/*.py`. Every entry below is present verbatim in
the source; nothing is inferred.

---

## 1. Thread Execution Boundaries

### 1.1 Process and thread inventory

| Execution context | Created at | Entry point |
|---|---|---|
| Main OS thread / asyncio event loop | `main.py` `__main__` — `uvicorn.run(app, host=cfg.host, port=cfg.port, log_level=cfg.log_level)` | uvicorn's event loop |
| `asyncio` default `ThreadPoolExecutor` worker | `EarningsProvider.refresh` — `await asyncio.to_thread(self._fetch_one, ticker)` | `EarningsProvider._fetch_one` |

No `threading.Thread`, `multiprocessing`, or explicitly constructed executor
appears anywhere in the package. All concurrency other than the `to_thread`
call above is cooperative multitasking on the single event loop.

### 1.2 Background `asyncio` tasks

Created in `main.lifespan` via `asyncio.create_task` and held in the local
`tasks` list:

| Task | Coroutine | Loop interval |
|---|---|---|
| implied-move refresh | `main._refresh_loop(client, calendar, calculator)` | `asyncio.sleep(cfg.calculation_check_interval_seconds)` |
| manual gamma-flip poll | `main._gamma_flip_loop(gamma_flip_provider)` | `asyncio.sleep(max(cfg.manual_gamma_flip_poll_interval_seconds, 0.1))` |
| UDP broadcast | `main._broadcast_loop(broadcaster, calendar, gamma_flip_provider)` | `asyncio.sleep(max(cfg.broadcast_interval_ms / 1000.0, 0.05))` |
| earnings refresh (only when `cfg.earnings_enabled`) | `main._earnings_loop(earnings_provider, calendar)` | `asyncio.sleep(max(cfg.earnings_check_interval_seconds, 1.0))` |

`massive_ws.MassiveWebSocket.start` creates a further task
(`asyncio.create_task(self._connection_loop())`), but `massive_ws` is not
imported by `main.py`; it is referenced only by `tests/test_imports.py`.

### 1.3 Coroutines on the event loop

| Module | Callables executing on the event loop |
|---|---|
| `main` | `lifespan`, `_refresh`, `_refresh_calendar`, `_refresh_loop`, `_gamma_flip_loop`, `_earnings_loop`, `_broadcast_loop`, and the HTTP handlers `indicator`, `calendar_view`, `earnings`, `health` |
| `main` (synchronous helpers, called from the above) | `_expected_trading_date`, `_current_implied_move` |
| `implied_move` | `StaticDailyImpliedMoveCalculator.calculate`; `select_atm_pair` (synchronous, called from it) |
| `massive_client` | `MassiveRestClient._ensure_client`, `close`, `_get_json`, `fetch_market_holidays`, `fetch_previous_bar`, `fetch_option_contracts` |
| `rate_limiter` | `SlidingWindowRateLimiter.acquire`; `status` (synchronous) |
| `udp_broadcaster` | `UdpBroadcaster.broadcast`; `open`, `close`, `serialize`, `stats` (synchronous) |
| `earnings` | `EarningsProvider.refresh`; `_record`, `_expire`, `dates_for`, `missing_tickers`, `needs_refresh`, `snapshot`, `load`, `save` (synchronous) |
| `manual_gamma_flip` | `ManualGammaFlipProvider.poll`, `current_for`, `status` — all synchronous, called from `_gamma_flip_loop`, `_broadcast_loop`, and the HTTP handlers |
| `market_calendar` | `MarketCalendar.load`, `merge_and_save`, `is_session`, `status_on`, `recorded_window`, `session_hours`, `coverage`, `merged_records`, `next_session_on_or_after`, `next_session_after`, `previous_session_on_or_before`, `sessions_between`, `nominal_friday` — all synchronous |

### 1.4 Off-loop execution

`EarningsProvider._fetch_one` and, through it, `EarningsProvider._ticker_handle`
and the lazy `import yfinance` run on a `ThreadPoolExecutor` worker via
`asyncio.to_thread`. The module docstring for that call states it is the
"Blocking yfinance lookup for one ticker, run off the event loop."

`_parse_calendar` and `_coerce_date` are module-level functions invoked from
`_fetch_one`, so they also execute on the worker thread.

`EarningsProvider.save`, `MarketCalendar.merge_and_save`, and
`ManualGammaFlipProvider.poll` perform blocking file I/O (`read_text`,
`tempfile.mkstemp`, `os.fsync`, `os.replace`, `Path.stat`) directly on the event
loop.

### 1.5 Task lifecycle and shutdown

`main.lifespan` is an `@asynccontextmanager`. On startup it constructs
`MassiveRestClient`, `MarketCalendar`, `StaticDailyImpliedMoveCalculator`,
`ManualGammaFlipProvider`, optionally `EarningsProvider`, and `UdpBroadcaster`
(calling `broadcaster.open()`), publishes them onto `app.state`, then creates the
tasks. On shutdown the `finally` block runs:

1. `task.cancel()` for every task,
2. `await task` for every task wrapped in `contextlib.suppress(asyncio.CancelledError)`,
3. `broadcaster.close()`,
4. `await client.close()`.

`MassiveWebSocket.stop` performs the analogous sequence for its own task:
`self._running = False`, `await self._ws.close()`, `self._reconnect_task.cancel()`,
`await self._reconnect_task` inside `try/except asyncio.CancelledError`.

### 1.6 Cross-boundary handoffs

| Producer | Mechanism | Consumer |
|---|---|---|
| `_refresh_loop` | `runtime_by_ticker[ticker].value` (module-global dict of `Runtime`) | `_broadcast_loop`, `indicator`, `health` |
| `_gamma_flip_loop` | `ManualGammaFlipProvider.value` | `_broadcast_loop`, `indicator`, `health` |
| `_earnings_loop` | `EarningsProvider._observed` / `_errors` / timestamps | `earnings`, `health` |
| `_refresh_calendar` | `MarketCalendar._records`, module-global `holiday_refresh_date` | `_expected_trading_date` in every loop and handler |
| `lifespan` | `app.state.client`, `.calendar`, `.calculator`, `.gamma_flip_provider`, `.earnings_provider`, `.broadcaster` | `indicator`, `calendar_view`, `earnings`, `health` (read with `getattr(app.state, ..., None)`) |
| `EarningsProvider._fetch_one` (worker thread) | `asyncio.to_thread` return value | `EarningsProvider.refresh` (event loop) |
| `UdpBroadcaster.broadcast` | `loop.sock_sendto` on a non-blocking `AF_INET`/`SOCK_DGRAM` socket | trading engine's `Options-Proxy-UDP-Receiver` |

---

## 2. State Synchronization Mechanisms

### 2.1 Concurrent collections

The package uses no thread-safe collection types. Every shared container is a
plain `dict`, `set`, `tuple`, or `collections.deque`, relying on single-threaded
event-loop execution:

| Declaration | Location |
|---|---|
| `runtime_by_ticker = {ticker: Runtime() for ticker in cfg.ticker_symbols}` | `main` module scope |
| `self._starts: deque[float] = deque()` | `SlidingWindowRateLimiter.__init__` |
| `self._observed: dict[str, tuple[date, ...]]` | `EarningsProvider.__init__` |
| `self._fetched_at_by_ticker: dict[str, datetime]` | `EarningsProvider.__init__` |
| `self._errors: dict[str, str]` | `EarningsProvider.__init__` |
| `self._records: dict[tuple[date, str], MarketHoliday]` | `MarketCalendar.__init__` |
| `self._active_subs: set[str]` | `MassiveWebSocket.__init__` |

### 2.2 Asynchronous primitives

| Primitive | Declaration | Purpose as written |
|---|---|---|
| `asyncio.Lock` | `refresh_lock = asyncio.Lock()` — `main` module scope | held for the whole body of `main._refresh` via `async with refresh_lock` |
| `asyncio.Lock` | `self._lock = asyncio.Lock()` — `SlidingWindowRateLimiter.__init__` | held around the `_starts` deque prune-and-append in `acquire` |
| `asyncio.Semaphore` | `self._sem = asyncio.Semaphore(settings.rest_concurrency)` — `MassiveRestClient.__init__` (`rest_concurrency: int = 5`) | `async with self._sem` around each `client.get` attempt in `_get_json` |
| `asyncio.gather` | `implied_move.StaticDailyImpliedMoveCalculator.calculate` | concurrent `fetch_previous_bar` for the ATM call and put |
| `asyncio.to_thread` | `EarningsProvider.refresh` | moves the blocking yfinance call off the loop |
| `asyncio.Task` cancellation | `main.lifespan` `finally`; `MassiveWebSocket.stop` | cooperative shutdown |
| `contextlib.suppress(asyncio.CancelledError)` | `main.lifespan` `finally` | awaits cancelled tasks without propagating |
| `asyncio.sleep` | every loop in `main`, `SlidingWindowRateLimiter.acquire` (injectable via the `sleep` constructor parameter), `MassiveRestClient._get_json` backoff, `EarningsProvider.refresh` spacing, `MassiveWebSocket._connection_loop` backoff | pacing / yielding |
| `httpx.AsyncClient` | `MassiveRestClient._ensure_client`, built with `httpx.Limits(max_connections=10, max_keepalive_connections=5)` and `httpx.Timeout(30.0, connect=10.0)` | bounded connection pool |
| Non-blocking socket | `UdpBroadcaster.open` — `self._socket.setblocking(False)`; sent via `await loop.sock_sendto(...)` | keeps datagram sends off the blocking path |

### 2.3 Rate limiting and pacing barriers

| Barrier | Location |
|---|---|
| Sliding-window limiter: at most `max_requests` starts in any `period_seconds`, defaults `rest_rate_limit_requests = 5` over `rest_rate_limit_period_seconds = 60.0` | `SlidingWindowRateLimiter.acquire`, applied in `MassiveRestClient._get_json` before **every** HTTP attempt (comment: "Retries and pagination therefore consume capacity just like first attempts") |
| Concurrency cap of `rest_concurrency` in-flight HTTP requests | `MassiveRestClient._sem` |
| Capped exponential backoff, `backoff = min(backoff * 2.0, 30.0)`, seeded from the `Retry-After` header when present | `MassiveRestClient._get_json` |
| Per-ticker request spacing `await asyncio.sleep(self._request_spacing_seconds)` between yfinance lookups | `EarningsProvider.refresh` |
| One in-flight implied-move refresh at a time, engine-wide | `main.refresh_lock` around `main._refresh` |
| Once-per-session holiday refresh guard via module-global `holiday_refresh_date` | `main._refresh_calendar` |
| Once-per-session earnings refresh guard via `refreshed_for_date` | `EarningsProvider.needs_refresh` / `refresh` |
| WebSocket reconnect backoff `min(backoff * 2, cfg.ws_reconnect_max_s)`, reset on clean disconnect; `ping_interval=20`, `ping_timeout=10`, `close_timeout=5`, `max_size=2**20` | `MassiveWebSocket._connection_loop`, `_connect_and_listen` |
| Subscription cap `cfg.max_ws_subscriptions` enforced before sending | `MassiveWebSocket.subscribe` |

### 2.4 Serialization barriers on shared external state

| Barrier | Location |
|---|---|
| Atomic file replace: `tempfile.mkstemp` in the target directory, `handle.flush()`, `os.fsync(handle.fileno())`, `os.replace(temporary_name, self.cache_path)`, with `os.unlink` of the temp file on any exception | `EarningsProvider.save`, `MarketCalendar.merge_and_save` |
| Torn-read detection: `Path.stat()` before and after `read_text`, comparing `(st_mtime_ns, st_size)`; a mismatch raises `ManualGammaFlipError("Manual gamma-flip file changed while it was being read")` and the signature is not accepted | `ManualGammaFlipProvider.poll` |
| Change detection short-circuit: `if signature == self._accepted_signature and self.last_error is None: return None` | `ManualGammaFlipProvider.poll` |
| Monotonic sequence stamped on every outbound frame (`self._sequence += 1`) plus `emitted_at_unix_ms = time.time_ns() // 1_000_000` | `UdpBroadcaster.serialize` |
| Payload bound: `ValueError` when `len(payload) > self.settings.udp_mtu` | `UdpBroadcaster.serialize` |
| Frame self-consistency checks before send: ticker and `trading_date` must match the supplied `StaticDailyImpliedMove` and `SpyGammaFlip` | `UdpBroadcaster.serialize` |

There are no `threading.Lock`, `RLock`, `Event`, `Condition`, `Semaphore`, or
`queue.Queue` objects in the package.

---

## 3. Concurrency Variables and Fields

### 3.1 `main` (module-level globals)

| Name | Declaration | Mutating functions |
|---|---|---|
| `runtime_by_ticker` | `dict[str, Runtime]`, built at import from `cfg.ticker_symbols` | mutated through its `Runtime` values by `_refresh` and `_refresh_calendar` |
| `refresh_lock` | `asyncio.Lock()` | acquired/released by `_refresh` |
| `holiday_refresh_date` | `date | None`, rebound under `global holiday_refresh_date` | `_refresh_calendar` |

### 3.2 `main.Runtime` (`@dataclass(slots=True)`)

| Field | Declaration | Mutating functions |
|---|---|---|
| `value` | `StaticDailyImpliedMove | None` | `_refresh` |
| `last_error` | `str | None` | `_refresh` (set on failure, cleared on success), `_refresh_calendar` (set for every ticker when the holiday refresh fails with no cache) |
| `last_attempt_at` | `datetime | None` | `_refresh` |
| `last_success_at` | `datetime | None` | `_refresh` |

### 3.3 `app.state` (FastAPI application state)

| Attribute | Set in | Read by |
|---|---|---|
| `client` | `lifespan` | `health` |
| `calendar` | `lifespan` | `indicator`, `calendar_view`, `earnings`, `health` |
| `calculator` | `lifespan` | — |
| `gamma_flip_provider` | `lifespan` | `indicator`, `health` |
| `earnings_provider` | `lifespan` | `earnings`, `health` |
| `broadcaster` | `lifespan` | `health` |

All reads use `getattr(app.state, "<name>", None)`.

### 3.4 `udp_broadcaster.UdpBroadcaster`

| Field | Declaration | Mutating methods |
|---|---|---|
| `_socket` | `socket.socket | None` | `open` (creates and sets `setblocking(False)`), `close` (sets `None`), `broadcast` (calls `open` when `None`) |
| `_sequence` | `int`, starts at `0` | `serialize` (`self._sequence += 1`) |
| `_sent` | `int` | `broadcast` (increment on success) |
| `_errors` | `int` | `broadcast` (increment in the `except OSError` branch before re-raising) |

### 3.5 `rate_limiter.SlidingWindowRateLimiter`

| Field | Declaration | Mutating methods |
|---|---|---|
| `_starts` | `deque[float]` | `acquire` — `popleft` of entries at or before the cutoff, `append(now)` on reservation; both inside `async with self._lock` |
| `_lock` | `asyncio.Lock()` | `acquire` |
| `enabled`, `max_requests`, `period_seconds` | set in `__init__` | not mutated after construction; `enabled` is read on every `acquire` iteration |

### 3.6 `massive_client.MassiveRestClient`

| Field | Declaration | Mutating methods |
|---|---|---|
| `_client` | `httpx.AsyncClient | None` | `_ensure_client` (creates when `None` or `is_closed`) |
| `_sem` | `asyncio.Semaphore(settings.rest_concurrency)` | acquired/released in `_get_json` |
| `rate_limiter` | `SlidingWindowRateLimiter` | `acquire` called in `_get_json` |
| local `backoff` | `float` starting at `1.0`, per-call | `_get_json` (`Retry-After` widening, then `min(backoff * 2.0, 30.0)`) |

### 3.7 `earnings.EarningsProvider`

| Field | Declaration | Mutating methods |
|---|---|---|
| `_observed` | `dict[str, tuple[date, ...]]` | `_record`, `_expire`, `load` (populate / `clear` on error) |
| `_fetched_at_by_ticker` | `dict[str, datetime]` | `refresh`, `_expire` (`pop`), `load` (populate / `clear` on error) |
| `_errors` | `dict[str, str]` | `refresh` (whole-dict replacement) |
| `fetched_at` | `datetime | None` | `refresh`, `load` |
| `last_attempt_at` | `datetime | None` | `refresh` |
| `last_success_at` | `datetime | None` | `refresh` |
| `last_error` | `str | None` | `refresh` (including the cache-write failure branch) |
| `load_error` | `str | None` | `__init__` (`except EarningsCacheError`) |
| `refreshed_for_date` | `date | None` | `refresh` (only when at least one ticker resolved) |
| cache file at `cache_path` | on disk | `save` (temp file + `os.replace`) |

### 3.8 `market_calendar.MarketCalendar`

| Field | Declaration | Mutating methods |
|---|---|---|
| `_records` | `dict[tuple[date, str], MarketHoliday]` | `load`, `merge_and_save` |
| `fetched_at` | `datetime | None` | `load`, `merge_and_save` |
| `load_error` | `str | None` | `__init__` (`except HolidayCacheError`) |
| cache file at `cache_path` | on disk | `merge_and_save` (temp file + `os.replace`) |

### 3.9 `manual_gamma_flip.ManualGammaFlipProvider`

| Field | Declaration | Mutating methods |
|---|---|---|
| `value` | `SpyGammaFlip | None` | `poll` (only after successful parse) |
| `last_error` | `str | None` | `poll` (set on every failure path, cleared on acceptance) |
| `last_checked_at` | `datetime | None` | `poll` (every invocation) |
| `last_loaded_at` | `datetime | None` | `poll` (on acceptance) |
| `_accepted_signature` | `tuple[int, int] | None` — `(st_mtime_ns, st_size)` | `poll` (set to `after_signature` on acceptance only) |

### 3.10 `implied_move.StaticDailyImpliedMoveCalculator`

Holds `client`, `calendar`, and `settings`, all assigned once in `__init__` and
never reassigned. `calculate` keeps all working state in local variables and
issues one `asyncio.gather` over two `fetch_previous_bar` calls.

### 3.11 `massive_ws.MassiveWebSocket`

Not wired into `main.py`; listed for completeness.

| Field | Declaration | Mutating methods |
|---|---|---|
| `_ws` | `Any` | `_connect_and_listen` (assigned inside the `async with`) |
| `_active_subs` | `set[str]` | `subscribe` (`update`), `unsubscribe` (`-=`) |
| `_running` | `bool` | `start` (`True`), `stop` (`False`) |
| `_reconnect_task` | `asyncio.Task[None] | None` | `start` (`create_task`), `stop` (`cancel`) |
| `_last_msg_time` | `float` | `_connect_and_listen` (set on connect and on every inbound frame) |
| local `backoff` | `float` in `_connection_loop` | doubled to `cfg.ws_reconnect_max_s`, reset to `cfg.ws_reconnect_base_s` on clean disconnect |
