---
paths:
  - "options-proxy/**/*"
---

# `main.py`, `udp_broadcaster.py`, `tools/generate_java_fixtures.py`

Sources:
- `options-proxy/main.py`
- `options-proxy/udp_broadcaster.py`
- `options-proxy/tools/generate_java_fixtures.py`

The `IndicatorFrame` message is generated from `options-proxy/proto/market_state.proto` into `options-proxy/proto/market_state_pb2.py`.

Python has no access modifiers. Names are recorded as written; a leading underscore marks a module- or class-private name by convention.

---

## `UdpBroadcaster`

`class UdpBroadcaster`

### 1. Class/Interface Responsibilities

Owns a non-blocking UDP `socket`, serialises one `IndicatorFrame` per ticker from an optional `StaticDailyImpliedMove` and an optional `SpyGammaFlip` with a monotonically increasing sequence number, enforces the configured MTU, and sends the payload to the configured host and port.

### 2. Injected Dependencies

`def __init__(self, settings: Settings = cfg) -> None`

| Parameter | Exact type | Default |
| --- | --- | --- |
| `settings` | `config.Settings` | `cfg` (module-level singleton) |

Instance fields initialised in `__init__`: `_socket` (`socket.socket | None`), `_sequence` (`int`), `_sent` (`int`), `_errors` (`int`).

### 3. Method Signatures

```python
def __init__(self, settings: Settings = cfg) -> None
def open(self) -> None
def close(self) -> None
def serialize(self, ticker: str, trading_date: date, implied_move: StaticDailyImpliedMove | None, gamma_flip: SpyGammaFlip | None) -> bytes
async def broadcast(self, ticker: str, trading_date: date, implied_move: StaticDailyImpliedMove | None, gamma_flip: SpyGammaFlip | None) -> None
def stats(self) -> dict[str, int]
```

### 4. Global State Interactions

**Concurrent collections / async primitives**

None. `_sequence`, `_sent`, and `_errors` are plain `int` counters with no lock; `_socket` is a plain attribute. `broadcast` awaits `loop.sock_sendto(...)` on the running event loop.

| Method | Interaction |
| --- | --- |
| `open()` | Mutates `_socket` (creates a non-blocking `AF_INET`/`SOCK_DGRAM` socket) |
| `close()` | Mutates `_socket` (closes and clears) |
| `serialize(...)` | Mutates `_sequence` (`+= 1`); reads `self.settings.udp_mtu`; raises `ValueError` on ticker/date mismatch or oversize payload |
| `broadcast(...)` | Calls `open()` when `_socket` is `None`; calls `serialize(...)`; reads `self.settings.udp_host` and `udp_port`; mutates `_sent` or `_errors` |
| `stats()` | Reads `_sent`, `_errors`, `_sequence` |

**Centralized state objects**

Reads the module-level `cfg` singleton through the default `settings` argument. The single instance is created in the FastAPI `lifespan` and stored on `app.state.broadcaster`, read by `_broadcast_loop` and `/health`.

---

## `Runtime`

`@dataclass(slots=True) class Runtime`

### 1. Class/Interface Responsibilities

Mutable per-ticker holder for the current `StaticDailyImpliedMove` and the last error, attempt, and success timestamps.

### 2. Injected Dependencies

Generated dataclass `__init__` fields:

| Field | Type | Default |
| --- | --- | --- |
| `value` | `StaticDailyImpliedMove \| None` | `None` |
| `last_error` | `str \| None` | `None` |
| `last_attempt_at` | `datetime \| None` | `None` |
| `last_success_at` | `datetime \| None` | `None` |

### 3. Method Signatures

None beyond the dataclass-generated `__init__` and `__repr__`. Not frozen; all four fields are assigned after construction.

### 4. Global State Interactions

Instances live in the module-level `runtime_by_ticker` dictionary described below and are mutated by `_refresh` and `_refresh_calendar`.

---

## `main.py` module-level state and functions

### 1. Class/Interface Responsibilities

FastAPI application module that builds the process-wide `runtime_by_ticker` map, defines the four background loops (`_refresh_loop`, `_gamma_flip_loop`, `_earnings_loop`, `_broadcast_loop`), wires component construction and shutdown into the `lifespan` context manager, and exposes the `/indicator`, `/calendar`, `/earnings`, and `/health` routes.

### 2. Injected Dependencies

Module-level bindings (not constructor parameters):

| Name | Exact type | Initial value |
| --- | --- | --- |
| `logger` | `logging.Logger` | `logging.getLogger(__name__)` |
| `EASTERN` | `zoneinfo.ZoneInfo` | `ZoneInfo("America/New_York")` |
| `runtime_by_ticker` | `dict[str, Runtime]` | `{ticker: Runtime() for ticker in cfg.ticker_symbols}` |
| `refresh_lock` | `asyncio.Lock` | `asyncio.Lock()` |
| `holiday_refresh_date` | `date \| None` | `None` |
| `app` | `fastapi.FastAPI` | `FastAPI(title="Options Proxy", description=..., lifespan=lifespan)` |

Objects constructed inside `lifespan(app)` and stored on `app.state`: `client` (`MassiveRestClient`), `calendar` (`MarketCalendar`), `calculator` (`StaticDailyImpliedMoveCalculator`), `gamma_flip_provider` (`ManualGammaFlipProvider`), `earnings_provider` (`EarningsProvider | None`), `broadcaster` (`UdpBroadcaster`).

### 3. Method Signatures

```python
def _expected_trading_date(calendar: MarketCalendar) -> date
def _current_implied_move(calendar: MarketCalendar, ticker: str) -> StaticDailyImpliedMove | None

async def _refresh(ticker: str, calculator: StaticDailyImpliedMoveCalculator) -> None
async def _refresh_calendar(client: MassiveRestClient, calendar: MarketCalendar, expected_date: date) -> bool
async def _refresh_loop(client: MassiveRestClient, calendar: MarketCalendar, calculator: StaticDailyImpliedMoveCalculator) -> None
async def _gamma_flip_loop(provider: ManualGammaFlipProvider) -> None
async def _earnings_loop(provider: EarningsProvider, calendar: MarketCalendar) -> None
async def _broadcast_loop(broadcaster: UdpBroadcaster, calendar: MarketCalendar, gamma_flip_provider: ManualGammaFlipProvider) -> None

@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]

@app.get("/indicator")
async def indicator() -> dict

@app.get("/calendar")
async def calendar_view() -> dict

@app.get("/earnings")
async def earnings() -> dict

@app.get("/health")
async def health() -> dict
```

### 4. Global State Interactions

**Concurrent collections / async primitives**

| Name | Declared type |
| --- | --- |
| `runtime_by_ticker` | `dict[str, Runtime]` (plain dict; keys fixed at import, values mutated in place) |
| `refresh_lock` | `asyncio.Lock` |
| `holiday_refresh_date` | module-level `date \| None`, rebound through `global` |

| Function | Interaction |
| --- | --- |
| `_current_implied_move(...)` | Reads `runtime_by_ticker[ticker].value` |
| `_refresh(...)` | Acquires `refresh_lock`; mutates `runtime_by_ticker[ticker]` fields `last_attempt_at`, `value`, `last_error`, `last_success_at` |
| `_refresh_calendar(...)` | Reads and rebinds the module-level `holiday_refresh_date` via `global`; mutates every `runtime_by_ticker` entry's `last_error` when the fetch fails and no cache exists; mutates `calendar.merge_and_save(...)` |
| `_refresh_loop(...)` | Reads `runtime_by_ticker` through `_current_implied_move`; calls `_refresh_calendar` and `_refresh` |
| `_gamma_flip_loop(...)` | Mutates `provider.poll()` state; reads `provider.last_error` |
| `_earnings_loop(...)` | Reads `provider.needs_refresh(...)` and `provider.missing_tickers()`; mutates `provider.refresh(...)` |
| `_broadcast_loop(...)` | Reads `gamma_flip_provider.current_for(...)` and `runtime_by_ticker` through `_current_implied_move`; mutates `broadcaster` counters through `broadcast(...)` |
| `lifespan(app)` | Constructs every component; mutates `app.state`; creates and later cancels the background `asyncio.Task` objects; calls `broadcaster.close()` and `await client.close()` |
| `indicator()` | Reads `app.state.calendar`, `app.state.gamma_flip_provider`, `runtime_by_ticker`, `cfg.ticker_symbols` |
| `calendar_view()` | Reads `app.state.calendar` (`session_hours`, `status_on`, `is_session`, `coverage`, `merged_records`, `has_cache`, `cache_path`, `fetched_at`, `load_error`, `previous_session_on_or_before`, `next_session_after`) |
| `earnings()` | Reads `app.state.calendar` and `app.state.earnings_provider`; calls `provider.snapshot(...)` |
| `health()` | Reads `app.state.calendar`, `app.state.gamma_flip_provider`, `app.state.earnings_provider`, `app.state.broadcaster`, `app.state.client.rate_limiter.status()`, and every `runtime_by_ticker` entry |

**Centralized state objects**

`app.state` is the process-wide container for the six components; `runtime_by_ticker` is the process-wide per-ticker implied-move state. Both are shared across the four background tasks and the four HTTP routes on a single asyncio event loop; only `_refresh` takes `refresh_lock`.

**Entry point**

The `if __name__ == "__main__":` block configures `logging.basicConfig(...)` from `cfg.log_level` and calls `uvicorn.run(app, host=cfg.host, port=cfg.port, log_level=cfg.log_level)`.

---

## `tools/generate_java_fixtures.py`

### 1. Class/Interface Responsibilities

Command-line script that writes two golden `IndicatorFrame` payloads to a target directory using `UdpBroadcaster.serialize`, for consumption by the Java trading engine's tests.

### 2. Injected Dependencies

No classes are defined. Module-level constants: `TRADING_DATE = date(2026, 7, 27)`, `TICKER = "AAPL"`, `IMPLIED_MOVE = 6.272`, `GAMMA_FLIP = 601.25`.

`main()` constructs `UdpBroadcaster(Settings(_env_file=None, massive_api_key="fixture", udp_mtu=1400))`.

### 3. Method Signatures

```python
def _implied_move() -> StaticDailyImpliedMove
def _gamma_flip() -> SpyGammaFlip
def main() -> int
```

The script appends its parent directory to `sys.path` at import time and ends with `raise SystemExit(main())` under `if __name__ == "__main__":`.

### 4. Global State Interactions

**Concurrent collections**

None.

**Centralized state objects**

Does not read the `cfg` singleton; it builds an isolated `Settings(_env_file=None, ...)` instance. `main()` mutates the `_sequence` counter of the local `UdpBroadcaster` twice via `serialize` and writes two `.bin` files to the directory given by `--output`.
