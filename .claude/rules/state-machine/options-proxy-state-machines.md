---
paths:
  - "options-proxy/**/*"
---

# Options Proxy — State Machine Reference

Extracted from `options-proxy/`. Every state, method signature, and condition
below is taken verbatim from the source. The proxy declares no `Enum` types; its
states are string literals returned by classifier methods, and mutable instance
attributes.

---

## 1. State Definitions and Ownership

### 1.1 String-Literal State Sets

#### Session status — `closed` / `early-close` / `open`

- **Owning class:** `MarketCalendar` (`market_calendar.py`)
- **Producing method:** `def status_on(self, candidate: date) -> str`
- **Backing field:** `self._records: dict[tuple[date, str], MarketHoliday]`, populated by `load()` and `merge_and_save(...)`; only records whose `exchange` is in `RELEVANT_EXCHANGES = frozenset({"NYSE", "NASDAQ"})` are retained.
- **Related field:** `MarketHoliday.status: str` (`models.py`), lowercased at parse time.

#### Session-hours source — `closed` / `early-close-record` / `standard`

- **Owning class:** `MarketCalendar`
- **Producing method:** `def session_hours(self, candidate: date) -> tuple[str | None, str | None, str]`
- **Module constants:** `STANDARD_OPEN = time(9, 30)`, `STANDARD_CLOSE = time(16, 0)`, `EASTERN = ZoneInfo("America/New_York")`

#### Merged-record precedence rank

- **Owning method:** `def merged_records(self) -> list[dict[str, object]]`
- **Rank map:** `ranked = {"open": 0, "early-close": 1, "closed": 2}`

#### Overall proxy health — `ready` / `degraded`

- **Owning module:** `main.py`
- **Producing method:** `async def health() -> dict`
- **Backing expression:** `ready = not missing_tickers and bool(gamma_status["valid"])`

#### Earnings subsystem health — `disabled` / `ready` / `degraded`

- **Owning module:** `main.py`
- **Producing method:** `async def health() -> dict`
- **Backing expressions:** `earnings_provider is None` → `{"status": "disabled"}`; otherwise `"ready" if not earnings_missing else "degraded"` where `earnings_missing = earnings_provider.missing_tickers()`

### 1.2 Mutable State Attributes

#### `main.Runtime` — per-ticker implied-move state

- **Definition:** `@dataclass(slots=True) class Runtime` with `value: StaticDailyImpliedMove | None = None`, `last_error: str | None = None`, `last_attempt_at: datetime | None = None`, `last_success_at: datetime | None = None`
- **Owning container:** `runtime_by_ticker = {ticker: Runtime() for ticker in cfg.ticker_symbols}` (module-level)
- **Serialization guard:** `refresh_lock = asyncio.Lock()` (module-level)
- **Calendar-refresh marker:** `holiday_refresh_date: date | None = None` (module-level, mutated through `global`)

#### `ManualGammaFlipProvider` (`manual_gamma_flip.py`)

| Field | Initial value |
|---|---|
| `self.value: SpyGammaFlip \| None` | `None` |
| `self.last_error: str \| None` | `None` |
| `self.last_checked_at: datetime \| None` | `None` |
| `self.last_loaded_at: datetime \| None` | `None` |
| `self._accepted_signature: tuple[int, int] \| None` | `None` |

- **Derived validity:** `status(trading_date)["valid"]` is `current is not None` where `current = self.current_for(trading_date)`.

#### `EarningsProvider` (`earnings.py`)

| Field | Initial value |
|---|---|
| `self._observed: dict[str, tuple[date, ...]]` | `{}` (repopulated by `load()`) |
| `self._fetched_at_by_ticker: dict[str, datetime]` | `{}` |
| `self._errors: dict[str, str]` | `{}` |
| `self.fetched_at: datetime \| None` | `None` |
| `self.last_attempt_at: datetime \| None` | `None` |
| `self.last_success_at: datetime \| None` | `None` |
| `self.last_error: str \| None` | `None` |
| `self.load_error: str \| None` | `None` |
| `self.refreshed_for_date: date \| None` | `None` |

- **Derived states:** `has_cache` → `bool(self._observed)`; `missing_tickers()` → tickers with no observed dates; `needs_refresh(trading_date)` → `self.refreshed_for_date != trading_date`

#### `MassiveWebSocket` (`massive_ws.py`)

| Field | Initial value |
|---|---|
| `self._ws: Any` | `None` |
| `self._active_subs: set[str]` | `set()` |
| `self._running: bool` | `False` |
| `self._reconnect_task: asyncio.Task[None] \| None` | `None` |
| `self._last_msg_time: float` | `0.0` |

- **Local loop state:** `backoff` in `_connection_loop`, initialized to `cfg.ws_reconnect_base_s`

#### `MassiveRestClient` (`massive_client.py`)

| Field | Initial value |
|---|---|
| `self._client: httpx.AsyncClient \| None` | `None` |
| `self._sem` | `asyncio.Semaphore(settings.rest_concurrency)` |
| `self.rate_limiter` | `SlidingWindowRateLimiter(...)` or the injected instance |

- **Local retry state:** `backoff = 1.0` and `attempt` in `_get_json`

#### `SlidingWindowRateLimiter` (`rate_limiter.py`)

| Field | Initial value |
|---|---|
| `self.enabled: bool` | constructor argument |
| `self._starts: deque[float]` | `deque()` |
| `self._lock` | `asyncio.Lock()` |

#### `UdpBroadcaster` (`udp_broadcaster.py`)

| Field | Initial value |
|---|---|
| `self._socket: socket.socket \| None` | `None` |
| `self._sequence: int` | `0` |
| `self._sent: int` | `0` |
| `self._errors: int` | `0` |

#### `MarketCalendar` cache state (`market_calendar.py`)

| Field | Initial value |
|---|---|
| `self._records: dict[tuple[date, str], MarketHoliday]` | `{}` (repopulated by `load()`) |
| `self.fetched_at: datetime \| None` | `None` |
| `self.load_error: str \| None` | `None` |

#### Application lifespan state (`main.py`)

- **Owning method:** `@asynccontextmanager async def lifespan(app: FastAPI) -> AsyncIterator[None]`
- **Fields set on `app.state`:** `client`, `calendar`, `calculator`, `gamma_flip_provider`, `earnings_provider`, `broadcaster`
- **Background tasks:** `_refresh_loop`, `_gamma_flip_loop`, `_broadcast_loop`, and `_earnings_loop` when `earnings_provider is not None`

---

## 2. Transition Mapping

### 2.1 Session status (`MarketCalendar.status_on`)

- **State Change:** date -> `closed`
  - **Controlling Method:** `def status_on(self, candidate: date) -> str`
  - **Transition Conditions:** `candidate.weekday() >= 5`; **or** `"closed" in statuses` where `statuses = {record.status for record in self._records.values() if record.date == candidate}`
- **State Change:** date -> `early-close`
  - **Controlling Method:** `def status_on(self, candidate: date) -> str`
  - **Transition Conditions:** `candidate.weekday() < 5`, `"closed" not in statuses`, and `"early-close" in statuses`
- **State Change:** date -> `open`
  - **Controlling Method:** `def status_on(self, candidate: date) -> str`
  - **Transition Conditions:** `candidate.weekday() < 5` and neither `"closed"` nor `"early-close"` is present in `statuses` (the fall-through `return "open"`)

### 2.2 Session predicate (`MarketCalendar.is_session`)

- **State Change:** date -> `False` (not a session)
  - **Controlling Method:** `def is_session(self, candidate: date) -> bool`
  - **Transition Conditions:** `candidate.weekday() >= 5`; **or** `any(record.date == candidate and record.status == "closed" for record in self._records.values())`
- **State Change:** date -> `True`
  - **Transition Conditions:** neither of the above holds

### 2.3 Session hours (`MarketCalendar.session_hours`)

- **State Change:** -> `(None, None, "closed")`
  - **Controlling Method:** `def session_hours(self, candidate: date) -> tuple[str | None, str | None, str]`
  - **Transition Conditions:** `not self.is_session(candidate)`
- **State Change:** -> `(opens_at, closes_at, "early-close-record")`
  - **Transition Conditions:** `self.is_session(candidate)` and `opens_at and closes_at`, where `opens_at, closes_at = self.recorded_window(candidate)`
- **State Change:** -> `(_eastern_instant(candidate, STANDARD_OPEN), _eastern_instant(candidate, STANDARD_CLOSE), "standard")`
  - **Transition Conditions:** `self.is_session(candidate)` and not both of `opens_at`, `closes_at`
- **Supporting method:** `def recorded_window(self, candidate: date) -> tuple[str | None, str | None]` returns the first record where `record.date == candidate and (record.open_at or record.close_at)`, otherwise `(None, None)`

### 2.4 Holiday cache (`MarketCalendar`)

- **State Change:** empty cache -> populated
  - **Controlling Method:** `def load(self) -> None`
  - **Transition Conditions:** `self.cache_path.exists()`; per record, `record.exchange in RELEVANT_EXCHANGES`
- **State Change:** load path -> `load_error` set
  - **Controlling Method:** `def __init__(self, cache_path: Path) -> None`
  - **Transition Conditions:** `load()` raises `HolidayCacheError`, itself raised on `(OSError, ValueError, TypeError, KeyError)` inside `load()`
- **State Change:** cache -> merged and persisted
  - **Controlling Method:** `def merge_and_save(self, records: Iterable[MarketHoliday], *, fetched_at: datetime | None = None) -> None`
  - **Transition Conditions:** per record, `record.exchange in RELEVANT_EXCHANGES`; `self.fetched_at = fetched_at or datetime.now(UTC)`; write is `tempfile.mkstemp(...)` → `os.fsync` → `os.replace(temporary_name, self.cache_path)`, with `os.unlink(temporary_name)` on exception

### 2.5 Calendar refresh marker (`main._refresh_calendar`)

- **State Change:** `holiday_refresh_date` -> `expected_date`; returns `True`
  - **Controlling Method:** `async def _refresh_calendar(client: MassiveRestClient, calendar: MarketCalendar, expected_date: date) -> bool`
  - **Transition Conditions:** either `client.fetch_market_holidays()` succeeded and `calendar.merge_and_save(holidays)` ran, **or** it raised `(MassiveApiError, OSError)` while `calendar.has_cache` is truthy
- **State Change:** no marker change; returns `True` immediately
  - **Transition Conditions:** `holiday_refresh_date == expected_date`
- **State Change:** every `Runtime.last_error` -> failure message; returns `False`
  - **Transition Conditions:** `except (MassiveApiError, OSError)` and `not calendar.has_cache`

### 2.6 Per-ticker implied-move `Runtime` (`main._refresh`)

- **State Change:** `last_attempt_at` -> `datetime.now(UTC)`
  - **Controlling Method:** `async def _refresh(ticker: str, calculator: StaticDailyImpliedMoveCalculator) -> None`
  - **Transition Conditions:** unconditional, inside `async with refresh_lock`
- **State Change:** `last_error` -> `str(exc)`; `value` unchanged
  - **Transition Conditions:** `except (ImpliedMoveError, OSError) as exc` from `await calculator.calculate(ticker)`; the method returns immediately
- **State Change:** `value` -> new `StaticDailyImpliedMove`; `last_error` -> `None`; `last_success_at` -> `datetime.now(UTC)`
  - **Transition Conditions:** `calculator.calculate(ticker)` returned without raising

### 2.7 Implied-move validity gate (`main._current_implied_move`)

- **State Change:** stored value -> reported as unavailable (`None`)
  - **Controlling Method:** `def _current_implied_move(calendar: MarketCalendar, ticker: str) -> StaticDailyImpliedMove | None`
  - **Transition Conditions:** `value is None` **or** `value.trading_date != expected_date`, where `expected_date = _expected_trading_date(calendar)` = `calendar.next_session_on_or_after(datetime.now(EASTERN).date())`
- **State Change:** stored value -> reported as available
  - **Transition Conditions:** `value is not None and value.trading_date == expected_date`

### 2.8 Refresh loop scheduling (`main._refresh_loop`)

- **Controlling Method:** `async def _refresh_loop(client: MassiveRestClient, calendar: MarketCalendar, calculator: StaticDailyImpliedMoveCalculator) -> None`
- **Transition Conditions per iteration:**
  - `missing_tickers = [ticker for ticker in cfg.ticker_symbols if _current_implied_move(calendar, ticker) is None]`
  - `_refresh(ticker, calculator)` runs for each missing ticker only when `missing_tickers` is non-empty **and** `await _refresh_calendar(client, calendar, expected_date)` returned `True`
  - `await asyncio.sleep(cfg.calculation_check_interval_seconds)` closes every iteration

### 2.9 Manual gamma-flip provider (`ManualGammaFlipProvider.poll`)

- **Controlling Method:** `def poll(self, now: datetime | None = None) -> SpyGammaFlip | None`
- **State Change:** `last_checked_at` -> `checked_at`
  - **Transition Conditions:** unconditional, first statement
- **State Change:** `last_error` -> `"Manual gamma-flip file is unavailable: {exc}"`; returns `None`
  - **Transition Conditions:** `except OSError as exc` from `self.path.stat()`
- **State Change:** none; returns `None` (no re-read)
  - **Transition Conditions:** `signature == self._accepted_signature and self.last_error is None`, where `signature = (before.st_mtime_ns, before.st_size)`
- **State Change:** `last_error` -> `str(exc)`; `value`, `_accepted_signature` unchanged; returns `None`
  - **Transition Conditions:** `except (OSError, UnicodeError, json.JSONDecodeError, ManualGammaFlipError)`, raised by:
    - `after_signature != signature` → `"Manual gamma-flip file changed while it was being read"`
    - `_parse_value` rejections (see below)
- **State Change:** `value` -> new `SpyGammaFlip`; `last_error` -> `None`; `last_loaded_at` -> `checked_at`; `_accepted_signature` -> `after_signature`
  - **Transition Conditions:** stat, read, re-stat, `json.loads`, and `_parse_value` all succeeded

#### `_parse_value` rejection conditions

- **Controlling Method:** `def _parse_value(payload: Any, ticker: str, now: datetime) -> SpyGammaFlip`
- **Raises `ManualGammaFlipError` when:**
  1. `not isinstance(payload, dict)`
  2. `isinstance(raw_level, bool) or not isinstance(raw_level, (int, float))`
  3. `OverflowError` from `float(raw_level)`
  4. `not math.isfinite(level) or level <= 0.0`
  5. `not isinstance(raw_trading_date, str) or not raw_trading_date.strip()`
  6. `ValueError` from `date.fromisoformat(normalized_trading_date)`
  7. `trading_date.isoformat() != normalized_trading_date`

#### Gamma-flip validity gate

- **Controlling Method:** `def current_for(self, trading_date: date) -> SpyGammaFlip | None`
- **Transition Conditions:** returns `None` when `self.value is None` **or** `self.value.trading_date != trading_date`; otherwise returns `self.value`
- **Controlling Method:** `def status(self, trading_date: date | None) -> dict[str, Any]`
  - `"valid"` -> `current is not None`
  - `stale_reason` is set when `self.value is not None and trading_date is not None and current is None`
  - `"last_error"` -> `self.last_error or stale_reason`
  - `"retaining_last_valid_value"` -> `self.value is not None`

### 2.10 Gamma-flip poll loop (`main._gamma_flip_loop`)

- **Controlling Method:** `async def _gamma_flip_loop(provider: ManualGammaFlipProvider) -> None`
- **Transition Conditions per iteration:**
  - `value = provider.poll()`; when `value is not None` → log and `last_reported_error = None`
  - `elif provider.last_error and provider.last_error != last_reported_error` → log warning and `last_reported_error = provider.last_error`
  - Interval: `interval = max(cfg.manual_gamma_flip_poll_interval_seconds, 0.1)`

### 2.11 Earnings provider (`EarningsProvider.refresh`)

- **Controlling Method:** `async def refresh(self, trading_date: date) -> int`
- **State Change:** `last_attempt_at` -> `datetime.now(UTC)`
  - **Transition Conditions:** unconditional, first statement
- **Per-ticker outcome:**
  - `errors[ticker] = f"{type(exc).__name__}: {exc}"` when `self._fetch_one(ticker)` raises `Exception`
  - `errors[ticker] = "No earnings date was reported"` when `observed is None`
  - `resolved[ticker] = observed` otherwise
  - Pacing: `await asyncio.sleep(self._request_spacing_seconds)` when `index and self._request_spacing_seconds`
- **State Change (success path):** `refreshed_for_date` -> `trading_date`; `fetched_at` -> `now`; `last_success_at` -> `now`; `last_error` -> `None if not errors else f"{len(errors)} ticker(s) unresolved"`
  - **Transition Conditions:** `resolved` is non-empty
  - **Follow-on:** `self.save()`; on `except OSError as exc`, `last_error` -> `f"Earnings cache could not be written: {exc}"`
- **State Change (failure path):** `last_error` -> `"No earnings dates could be resolved"`; `refreshed_for_date` unchanged
  - **Transition Conditions:** `resolved` is empty
- **Always:** `self._record(ticker, observed, trading_date)` per resolved ticker, then `self._expire(trading_date)`, then `self._errors = errors`

#### Held-date set transitions (`EarningsProvider._record` / `_expire`)

- **Controlling Method:** `def _record(self, ticker: str, observed: date, today: date) -> None`
- **Transition Conditions:** a held date survives when `item < today or item == observed`; then `held.add(observed)`; result stored as `tuple(sorted(held))`
- **Controlling Method:** `def _expire(self, today: date) -> None`
- **Transition Conditions:** `cutoff = today - timedelta(days=self.retention_days)`; a date survives when `item >= cutoff`. When nothing survives for a ticker, `del self._observed[ticker]` and `self._fetched_at_by_ticker.pop(ticker, None)`

#### Earnings cache load (`EarningsProvider.load`)

- **Controlling Method:** `def load(self) -> None`
- **Transition Conditions:**
  - returns immediately when `not self.cache_path.exists()`
  - `raw_dates = [item["next_earnings_date"]]` when `item.get("dates")` is `None`
  - on `(OSError, ValueError, TypeError, KeyError)`: `self._observed.clear()`, `self._fetched_at_by_ticker.clear()`, raise `EarningsCacheError`
- **Constructor handling:** `except EarningsCacheError as exc` → `self.load_error = str(exc)`

#### Earnings loop (`main._earnings_loop`)

- **Controlling Method:** `async def _earnings_loop(provider: EarningsProvider, calendar: MarketCalendar) -> None`
- **Transition Conditions:** `await provider.refresh(expected_date)` runs only when `provider.needs_refresh(expected_date)` (`self.refreshed_for_date != trading_date`); interval is `max(cfg.earnings_check_interval_seconds, 1.0)`

### 2.12 WebSocket connection (`MassiveWebSocket`)

- **State Change:** stopped -> running
  - **Controlling Method:** `async def start(self) -> None`
  - **Transition Conditions:** unconditional; `self._running = True`, `self._reconnect_task = asyncio.create_task(self._connection_loop())`
- **State Change:** running -> stopped
  - **Controlling Method:** `async def stop(self) -> None`
  - **Transition Conditions:** `self._running = False`; `await self._ws.close()` when `self._ws` is truthy; `self._reconnect_task.cancel()` when `self._reconnect_task` is truthy, then `except asyncio.CancelledError: pass`
- **State Change:** connected -> reconnecting (backoff grows)
  - **Controlling Method:** `async def _connection_loop(self) -> None`
  - **Transition Conditions:**
    - loop runs `while self._running`
    - `except (websockets.exceptions.ConnectionClosed, websockets.exceptions.WebSocketException, OSError)` → `return` when `not self._running`, else `await asyncio.sleep(backoff)` and `backoff = min(backoff * 2, cfg.ws_reconnect_max_s)`
    - `except asyncio.CancelledError` → `return`
    - `except Exception` → `return` when `not self._running`, else the same sleep-and-double
- **State Change:** backoff -> reset
  - **Transition Conditions:** the `else` clause of the `try` — `_connect_and_listen()` returned without raising → `backoff = cfg.ws_reconnect_base_s`
- **State Change:** disconnected -> authenticated and subscribed
  - **Controlling Method:** `async def _connect_and_listen(self) -> None`
  - **Transition Conditions:** inside `async with websockets.connect(...)`: `self._ws = ws`, `self._last_msg_time = time.monotonic()`, send `{"action": "auth", "params": cfg.massive_api_key}`, await one `recv()`, then `await self._send_subscribe(list(self._active_subs))` when `self._active_subs` is non-empty
- **State Change:** `_last_msg_time` -> `time.monotonic()`
  - **Transition Conditions:** every frame received in `async for raw in ws`; `json.JSONDecodeError` is caught and logged without changing state

#### Message routing (`MassiveWebSocket._dispatch`)

- **Controlling Method:** `async def _dispatch(self, msg: dict[str, Any]) -> None`
- **Transition Conditions:** `ev = msg.get("ev", "")`
  - `ev == "Q" and self._on_quote` → `await self._on_quote(msg)`
  - `ev == "T" and self._on_trade` → `await self._on_trade(msg)`
  - `ev == "status"` → debug log only
  - `ev == "AM" or ev == "A"` → `pass`
  - otherwise → debug log only

#### Subscription set (`_active_subs`)

- **State Change:** symbols added
  - **Controlling Method:** `async def subscribe(self, contract_symbols: list[str]) -> None`
  - **Transition Conditions:**
    1. `new_symbols = [s for s in contract_symbols if s not in self._active_subs]` is non-empty
    2. `available_slots = cfg.max_ws_subscriptions - len(self._active_subs)` and `available_slots > 0`
    3. `to_add = new_symbols[:available_slots]`, then `await self._send_subscribe(to_add)` and `self._active_subs.update(to_add)`
- **State Change:** symbols removed
  - **Controlling Method:** `async def unsubscribe(self, contract_symbols: list[str]) -> None`
  - **Transition Conditions:** `to_remove = [s for s in contract_symbols if s in self._active_subs]` is non-empty; then `await self._send_unsubscribe(to_remove)` and `self._active_subs -= set(to_remove)`
- **State Change:** old set -> new set
  - **Controlling Method:** `async def replace_subscriptions(self, old_symbols: list[str], new_symbols: list[str]) -> None`
  - **Transition Conditions:** `await self.unsubscribe(old_symbols)` then `await self.subscribe(new_symbols)`
- **Send guards:** `_send_subscribe` and `_send_unsubscribe` both return immediately when `not self._ws or not symbols`

### 2.13 REST client (`MassiveRestClient`)

- **State Change:** `_client` `None`/closed -> open `httpx.AsyncClient`
  - **Controlling Method:** `async def _ensure_client(self) -> httpx.AsyncClient`
  - **Transition Conditions:** raises `MassiveApiError("MASSIVE_API_KEY is not configured")` when `not self.settings.massive_api_key`; constructs a new client when `self._client is None or self._client.is_closed`
- **State Change:** open -> closed
  - **Controlling Method:** `async def close(self) -> None`
  - **Transition Conditions:** `self._client is not None and not self._client.is_closed`

#### Retry state (`MassiveRestClient._get_json`)

- **Controlling Method:** `async def _get_json(self, url: str, params: dict[str, Any] | None = None) -> Any`
- **Transition Conditions per attempt** (`for attempt in range(self.settings.rest_max_retries + 1)`, `backoff = 1.0`):
  - Every attempt first performs `await self.rate_limiter.acquire()` and enters `async with self._sem`
  - `except httpx.HTTPError as exc` → raise `MassiveApiError` when `attempt >= self.settings.rest_max_retries`; otherwise fall through to sleep
  - `response.status_code == 200` → return `response.json()`; `ValueError` → raise `MassiveApiError("Massive returned invalid JSON for {url}")`
  - `response.status_code != 429 and response.status_code < 500` → raise `MassiveApiError` (no retry)
  - `attempt >= self.settings.rest_max_retries` → raise `MassiveApiError("Massive exhausted retries ...")`
  - `Retry-After` header present and `float(retry_after)` parses → `backoff = max(backoff, float(retry_after))`; `ValueError` → `pass`
  - After the attempt: `await asyncio.sleep(backoff)` then `backoff = min(backoff * 2.0, 30.0)`
  - Loop exhaustion → raise `MassiveApiError(f"Massive request failed for {url}")`
- **Pagination:** `fetch_option_contracts` loops `while url`, setting `url = str(next_url) if next_url else ""` and `params = None` after the first page

### 2.14 Rate limiter (`SlidingWindowRateLimiter.acquire`)

- **State Change:** window entries expired, then a start reserved
  - **Controlling Method:** `async def acquire(self) -> None`
  - **Transition Conditions:**
    1. Loop runs `while self.enabled` — returns immediately without reserving when `self.enabled` is false
    2. Inside `async with self._lock`: `cutoff = now - self.period_seconds`; `self._starts.popleft()` while `self._starts and self._starts[0] <= cutoff`
    3. `len(self._starts) < self.max_requests` → `self._starts.append(now)` and `return`
    4. otherwise `wait_seconds = self.period_seconds - (now - self._starts[0])`, then `await self._sleep(max(wait_seconds, 0.0))` outside the lock and repeat
- **Constructor rejections:** `max_requests < 1` → `ValueError("max_requests must be at least 1")`; `period_seconds <= 0` → `ValueError("period_seconds must be positive")`

### 2.15 UDP broadcaster (`UdpBroadcaster`)

- **State Change:** `_socket` `None` -> open datagram socket
  - **Controlling Method:** `def open(self) -> None`
  - **Transition Conditions:** `self._socket is None`; then `socket.socket(socket.AF_INET, socket.SOCK_DGRAM)` and `setblocking(False)`
- **State Change:** open -> `None`
  - **Controlling Method:** `def close(self) -> None`
  - **Transition Conditions:** `self._socket is not None`
- **State Change:** `_sequence` incremented; frame produced
  - **Controlling Method:** `def serialize(self, ticker: str, trading_date: date, implied_move: StaticDailyImpliedMove | None, gamma_flip: SpyGammaFlip | None) -> bytes`
  - **Rejection Conditions (raise `ValueError`, no increment):**
    1. `not ticker` after `strip().upper()`
    2. `implied_move is not None and (implied_move.ticker != ticker or implied_move.trading_date != trading_date)`
    3. `gamma_flip is not None and gamma_flip.trading_date != trading_date`
    4. `len(payload) > self.settings.udp_mtu` (after `self._sequence += 1`)
  - **Frame validity flags:** `static_daily_implied_move_valid=implied_move is not None`; `spy_gamma_flip_valid=gamma_flip is not None`; the numeric fields are `0.0` when the corresponding input is `None`
- **State Change:** `_sent` or `_errors` incremented
  - **Controlling Method:** `async def broadcast(self, ticker: str, trading_date: date, implied_move: StaticDailyImpliedMove | None, gamma_flip: SpyGammaFlip | None) -> None`
  - **Transition Conditions:** `self.open()` first when `self._socket is None`; `self._sent += 1` after `loop.sock_sendto(...)` returns; `except OSError` → `self._errors += 1` and re-raise
- **Broadcast loop:** `async def _broadcast_loop(broadcaster: UdpBroadcaster, calendar: MarketCalendar, gamma_flip_provider: ManualGammaFlipProvider) -> None` catches `OSError` per ticker and continues; interval is `max(cfg.broadcast_interval_ms / 1000.0, 0.05)`

### 2.16 Health status (`main.health`)

- **State Change:** -> `"ready"`
  - **Controlling Method:** `async def health() -> dict`
  - **Transition Conditions:** `not missing_tickers and bool(gamma_status["valid"])`, where `missing_tickers = [ticker for ticker, value in implied_moves.items() if value is None]` and each `value = _current_implied_move(calendar, ticker) if calendar else None`
- **State Change:** -> `"degraded"`
  - **Transition Conditions:** `missing_tickers` non-empty **or** `gamma_status["valid"]` falsy
- **Gamma status fallback:** when `provider is None`, `gamma_status = {"valid": False, "last_error": "Manual gamma-flip provider is not initialized"}`

### 2.17 Earnings status (`main.health`)

- **State Change:** -> `{"status": "disabled"}`
  - **Transition Conditions:** `earnings_provider is None` (set in `lifespan` when `cfg.earnings_enabled` is false)
- **State Change:** -> `"ready"`
  - **Transition Conditions:** `earnings_provider is not None` and `earnings_provider.missing_tickers()` is empty
- **State Change:** -> `"degraded"`
  - **Transition Conditions:** `earnings_provider is not None` and `missing_tickers()` is non-empty

### 2.18 Application lifespan (`main.lifespan`)

- **State Change:** unstarted -> running
  - **Controlling Method:** `@asynccontextmanager async def lifespan(app: FastAPI) -> AsyncIterator[None]`
  - **Transition Conditions:**
    1. `MassiveRestClient()`, `MarketCalendar(cfg.holiday_cache_path)`, `StaticDailyImpliedMoveCalculator(client, calendar)`, `ManualGammaFlipProvider(cfg.manual_gamma_flip_path, "SPY")` constructed
    2. `gamma_flip_provider.poll()` runs once; logged when the result `is not None`
    3. `EarningsProvider(...)` constructed only when `cfg.earnings_enabled`
    4. `broadcaster.open()`
    5. Tasks created: `_refresh_loop`, `_gamma_flip_loop`, `_broadcast_loop`; `_earnings_loop` appended when `earnings_provider is not None`
- **State Change:** running -> shut down
  - **Transition Conditions:** in the `finally` block — `task.cancel()` for every task, then `await task` inside `contextlib.suppress(asyncio.CancelledError)`, then `broadcaster.close()` and `await client.close()`
- **Startup diagnostics:** `calendar.load_error` truthy → warning; `earnings_provider.load_error` truthy → warning; `elif earnings_provider.has_cache` → info

### 2.19 Endpoint state gates

| Endpoint | Controlling Method | Conditions |
|---|---|---|
| `/indicator` | `async def indicator() -> dict` | `calendar = getattr(app.state, "calendar", None)`; `expected_date` is `None` when `calendar` is falsy; per ticker the value is `_current_implied_move(calendar, ticker) if calendar else None`; `gamma_status` falls back to `{"valid": False, "last_error": ...}` when `provider is None` |
| `/calendar` | `async def calendar_view() -> dict` | returns `{"session": None, "holidays": [], "cache": {"present": False, "last_error": "Calendar is not initialized"}}` when `calendar is None`; otherwise reports `calendar.status_on(expected_date)`, `calendar.is_session(expected_date)`, `calendar.session_hours(expected_date)`, `calendar.coverage()` |
| `/earnings` | `async def earnings() -> dict` | returns a zero-coverage payload with `"last_error": "Earnings retrieval is disabled"` when `provider is None`; otherwise `provider.snapshot(expected_date)` |
| `/health` | `async def health() -> dict` | see 2.16 and 2.17 |

`EarningsProvider.snapshot(trading_date)` marks each ticker `"valid": held is not None` where `held = self.dates_for(ticker)`, and reports `"last_error": self.last_error or self.load_error`.
