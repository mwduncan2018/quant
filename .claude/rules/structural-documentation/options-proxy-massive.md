---
paths:
  - "options-proxy/**/*"
---

# `massive_client.py`, `rate_limiter.py`, `massive_ws.py`

Sources:
- `options-proxy/massive_client.py`
- `options-proxy/rate_limiter.py`
- `options-proxy/massive_ws.py`

Python has no access modifiers. Names are recorded as written; a leading underscore marks a module- or class-private name by convention.

---

## `SlidingWindowRateLimiter`

`class SlidingWindowRateLimiter`

### 1. Class/Interface Responsibilities

Guarantees at most `max_requests` acquisitions in any rolling `period_seconds` window by recording start timestamps in a `collections.deque` guarded by an `asyncio.Lock`.

### 2. Injected Dependencies

`def __init__(self, *, enabled: bool, max_requests: int, period_seconds: float, clock: Callable[[], float] = time.monotonic, sleep: Callable[[float], Awaitable[None]] = asyncio.sleep) -> None`

| Parameter | Exact type | Default |
| --- | --- | --- |
| `enabled` | `bool` | keyword-only, required |
| `max_requests` | `int` | keyword-only, required (`< 1` raises `ValueError`) |
| `period_seconds` | `float` | keyword-only, required (`<= 0` raises `ValueError`) |
| `clock` | `Callable[[], float]` | `time.monotonic` |
| `sleep` | `Callable[[float], Awaitable[None]]` | `asyncio.sleep` |

### 3. Method Signatures

```python
def __init__(self, *, enabled: bool, max_requests: int, period_seconds: float, clock: Callable[[], float] = time.monotonic, sleep: Callable[[float], Awaitable[None]] = asyncio.sleep) -> None
async def acquire(self) -> None
def status(self) -> dict[str, bool | int | float]
```

### 4. Global State Interactions

**Concurrent collections / async primitives**

| Field | Declared type |
| --- | --- |
| `_starts` | `deque[float]` |
| `_lock` | `asyncio.Lock` |

| Method | Interaction |
| --- | --- |
| `acquire()` | Acquires `_lock`; reads and mutates `_starts` (`popleft` for expired entries, `append` to reserve a start); awaits `self._sleep(...)` outside the lock |
| `status()` | Reads `enabled`, `max_requests`, `period_seconds`; does not touch `_starts` or `_lock` |

**Centralized state objects**

None. The instance is owned by `MassiveRestClient`.

---

## `MassiveApiError`

`class MassiveApiError(RuntimeError)`

### 1. Class/Interface Responsibilities

Exception type raised when Massive cannot supply valid data.

### 2. Injected Dependencies

None declared; inherits `RuntimeError.__init__`.

### 3. Method Signatures

None declared.

### 4. Global State Interactions

None.

---

## `MassiveRestClient`

`class MassiveRestClient`

### 1. Class/Interface Responsibilities

Asynchronous REST client for the three Massive endpoints the proxy uses, owning a lazily created `httpx.AsyncClient`, an `asyncio.Semaphore` for concurrency, a `SlidingWindowRateLimiter`, and retry/backoff handling for 429 and 5xx responses.

### 2. Injected Dependencies

`def __init__(self, settings: Settings = cfg, *, transport: httpx.AsyncBaseTransport | None = None, rate_limiter: SlidingWindowRateLimiter | None = None) -> None`

| Parameter | Exact type | Default |
| --- | --- | --- |
| `settings` | `config.Settings` | `cfg` (module-level singleton) |
| `transport` | `httpx.AsyncBaseTransport \| None` | `None` |
| `rate_limiter` | `rate_limiter.SlidingWindowRateLimiter \| None` | `None`; when omitted, one is constructed from `settings.rest_rate_limit_enabled`, `rest_rate_limit_requests`, `rest_rate_limit_period_seconds` |

Constructed inside `__init__`: `self._sem = asyncio.Semaphore(settings.rest_concurrency)`, `self._client = None`.

Module-level constant: `EASTERN = ZoneInfo("America/New_York")`.

### 3. Method Signatures

```python
def __init__(self, settings: Settings = cfg, *, transport: httpx.AsyncBaseTransport | None = None, rate_limiter: SlidingWindowRateLimiter | None = None) -> None
async def _ensure_client(self) -> httpx.AsyncClient
async def close(self) -> None
async def _get_json(self, url: str, params: dict[str, Any] | None = None) -> Any
async def fetch_market_holidays(self) -> list[MarketHoliday]
async def fetch_previous_bar(self, ticker: str) -> DailyBar
async def fetch_option_contracts(self, *, underlying_ticker: str, expiration_date: str, as_of: str, strike_gte: float, strike_lte: float) -> list[OptionContract]
```

### 4. Global State Interactions

**Concurrent collections / async primitives**

| Field | Declared type |
| --- | --- |
| `_sem` | `asyncio.Semaphore` |
| `rate_limiter` | `SlidingWindowRateLimiter` (holds a `deque` and an `asyncio.Lock`) |
| `_client` | `httpx.AsyncClient \| None` (mutable shared connection pool) |

| Method | Interaction |
| --- | --- |
| `_ensure_client()` | Reads `settings.massive_api_key`; mutates `self._client` (creates a new `httpx.AsyncClient` when absent or closed) |
| `close()` | Reads and closes `self._client` |
| `_get_json(...)` | Awaits `self.rate_limiter.acquire()` once per HTTP attempt (mutating the limiter's deque); enters `async with self._sem`; reads `settings.rest_max_retries` |
| `fetch_market_holidays()` | Calls `_get_json`; builds a local `list[MarketHoliday]` |
| `fetch_previous_bar(str)` | Calls `_get_json`; reads `EASTERN` |
| `fetch_option_contracts(...)` | Calls `_get_json` in a pagination loop; builds a local `list[OptionContract]` |

**Centralized state objects**

Reads the module-level `cfg` singleton through the default `settings` argument. Holds no reference to the proxy's `app.state` or the `runtime_by_ticker` dictionary in `main.py`.

---

## `MassiveWebSocket`

`class MassiveWebSocket`

### 1. Class/Interface Responsibilities

Maintains a single Massive options WebSocket connection with auth-on-connect, a tracked `set` of active subscriptions, quote/trade handler dispatch, and a reconnect loop with capped exponential backoff.

### 2. Injected Dependencies

`def __init__(self, on_quote: QuoteHandler | None = None, on_trade: TradeHandler | None = None) -> None`

| Parameter | Exact type | Default |
| --- | --- | --- |
| `on_quote` | `QuoteHandler \| None`, where `QuoteHandler = Callable[[dict[str, Any]], Awaitable[None]]` | `None` |
| `on_trade` | `TradeHandler \| None`, where `TradeHandler = Callable[[dict[str, Any]], Awaitable[None]]` | `None` |

Instance fields initialised in `__init__`: `_ws` (`Any`), `_active_subs` (`set[str]`), `_running` (`bool`), `_reconnect_task` (`asyncio.Task[None] | None`), `_last_msg_time` (`float`).

Module-level: `logger = logging.getLogger(__name__)`.

### 3. Method Signatures

```python
def __init__(self, on_quote: QuoteHandler | None = None, on_trade: TradeHandler | None = None) -> None

async def start(self) -> None
async def stop(self) -> None

@property
def active_subscriptions(self) -> set[str]

async def _connection_loop(self) -> None
async def _connect_and_listen(self) -> None
async def _dispatch(self, msg: dict[str, Any]) -> None

async def subscribe(self, contract_symbols: list[str]) -> None
async def unsubscribe(self, contract_symbols: list[str]) -> None
async def replace_subscriptions(self, old_symbols: list[str], new_symbols: list[str]) -> None

async def _send_subscribe(self, symbols: list[str]) -> None
async def _send_unsubscribe(self, symbols: list[str]) -> None
```

### 4. Global State Interactions

**Concurrent collections / async primitives**

| Field | Declared type |
| --- | --- |
| `_active_subs` | `set[str]` (plain set; no lock) |
| `_reconnect_task` | `asyncio.Task[None] \| None` |

| Method | Interaction |
| --- | --- |
| `start()` | Mutates `_running`; mutates `_reconnect_task` via `asyncio.create_task(self._connection_loop())` |
| `stop()` | Mutates `_running`; closes `_ws`; cancels and awaits `_reconnect_task` |
| `active_subscriptions` | Reads `_active_subs`, returning a copy |
| `_connection_loop()` | Reads `_running`; reads `cfg.ws_reconnect_base_s` and `cfg.ws_reconnect_max_s` |
| `_connect_and_listen()` | Mutates `_ws` and `_last_msg_time`; reads `cfg.massive_ws_url` and `cfg.massive_api_key`; reads `_active_subs` to resubscribe |
| `subscribe(list[str])` | Reads `_active_subs` and `cfg.max_ws_subscriptions`; mutates `_active_subs` (`update`) |
| `unsubscribe(list[str])` | Reads and mutates `_active_subs` (`-=`) |
| `replace_subscriptions(...)` | Calls `unsubscribe` then `subscribe` |
| `_dispatch(dict)` | Reads `_on_quote` / `_on_trade` and awaits the matching handler |
| `_send_subscribe`, `_send_unsubscribe` | Read `_ws` and send a JSON frame |

**Centralized state objects**

Reads the module-level `cfg` singleton directly (not injected). This class is not constructed by `main.py`; `config.py` records its settings as retained for a parked WebSocket client.
