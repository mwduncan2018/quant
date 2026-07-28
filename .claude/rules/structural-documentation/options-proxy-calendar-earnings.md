---
paths:
  - "options-proxy/**/*"
---

# `market_calendar.py` and `earnings.py`

Sources:
- `options-proxy/market_calendar.py`
- `options-proxy/earnings.py`

Python has no access modifiers. Names are recorded as written; a leading underscore marks a module- or class-private name by convention.

---

## `HolidayCacheError`

`class HolidayCacheError(RuntimeError)`

### 1. Class/Interface Responsibilities

Exception type raised when a holiday cache file exists but cannot be read safely.

### 2. Injected Dependencies

None declared; inherits `RuntimeError.__init__`.

### 3. Method Signatures

None declared.

### 4. Global State Interactions

None.

---

## `MarketCalendar`

`class MarketCalendar`

### 1. Class/Interface Responsibilities

Holds Massive holiday records keyed by `(date, exchange)` in a plain `dict`, loads and atomically saves them to a JSON cache file, and answers session, status, hours, coverage, and adjacent-session queries.

### 2. Injected Dependencies

`def __init__(self, cache_path: Path) -> None`

| Parameter | Exact type |
| --- | --- |
| `cache_path` | `pathlib.Path` |

Instance fields initialised in `__init__`: `_records` (`dict[tuple[date, str], MarketHoliday]`), `fetched_at` (`datetime | None`), `load_error` (`str | None`). The constructor calls `self.load()` and captures a `HolidayCacheError` into `load_error`.

Module-level constants: `RELEVANT_EXCHANGES = frozenset({"NYSE", "NASDAQ"})`, `EASTERN = ZoneInfo("America/New_York")`, `STANDARD_OPEN = time(9, 30)`, `STANDARD_CLOSE = time(16, 0)`.

### 3. Method Signatures

```python
def __init__(self, cache_path: Path) -> None

@property
def has_cache(self) -> bool
@property
def records(self) -> tuple[MarketHoliday, ...]

def load(self) -> None
def merge_and_save(self, records: Iterable[MarketHoliday], *, fetched_at: datetime | None = None) -> None
def is_session(self, candidate: date) -> bool
def status_on(self, candidate: date) -> str
def recorded_window(self, candidate: date) -> tuple[str | None, str | None]
def session_hours(self, candidate: date) -> tuple[str | None, str | None, str]
def coverage(self) -> tuple[date, date] | None
def merged_records(self) -> list[dict[str, object]]
def next_session_on_or_after(self, candidate: date) -> date
def next_session_after(self, candidate: date) -> date
def previous_session_on_or_before(self, candidate: date) -> date
def sessions_between(self, start_exclusive: date, end_inclusive: date) -> list[date]

@staticmethod
def nominal_friday(session_date: date) -> date
```

Module-level helper: `def _eastern_instant(session_date: date, local_time: time) -> str`.

### 4. Global State Interactions

**Concurrent collections**

None. `_records` is a plain `dict` with no lock. All access is synchronous and occurs on the asyncio event-loop thread.

| Method | Interaction |
| --- | --- |
| `load()` | Mutates `_records` (`__setitem__`) and `fetched_at` |
| `merge_and_save(...)` | Mutates `_records` (`__setitem__`) and `fetched_at`; writes the cache file |
| `has_cache`, `records`, `is_session`, `status_on`, `recorded_window`, `coverage`, `merged_records` | Read `_records` |
| `session_hours`, `next_session_on_or_after`, `next_session_after`, `previous_session_on_or_before`, `sessions_between` | Read `_records` via `is_session` / `recorded_window` |
| `nominal_friday(date)` | `staticmethod`; no instance state |

**Centralized state objects**

The single instance is created in the FastAPI `lifespan` and stored on `app.state.calendar`, from which `_refresh_loop`, `_broadcast_loop`, `_earnings_loop`, `/indicator`, `/calendar`, `/earnings`, and `/health` read it. `_refresh_calendar` in `main.py` is the only caller that mutates it, via `merge_and_save`.

**Filesystem state**

`merge_and_save` writes through `tempfile.mkstemp` in the cache directory, `flush`, `os.fsync`, then `os.replace`, unlinking the temporary file on failure.

---

## `EarningsCacheError`

`class EarningsCacheError(RuntimeError)`

### 1. Class/Interface Responsibilities

Exception type raised when an earnings cache file exists but cannot be read safely.

### 2. Injected Dependencies

None declared; inherits `RuntimeError.__init__`.

### 3. Method Signatures

None declared.

### 4. Global State Interactions

None.

---

## `EarningsProvider`

`class EarningsProvider`

### 1. Class/Interface Responsibilities

Fetches per-ticker earnings dates through a yfinance-backed ticker factory, merges and expires observed dates in two plain `dict` fields, atomically saves them to a JSON cache, and serves a JSON-compatible snapshot with per-ticker validity and coverage counts.

### 2. Injected Dependencies

`def __init__(self, cache_path: Path, tickers: Sequence[str], *, ticker_factory: TickerFactory | None = None, request_spacing_seconds: float = 0.0, retention_days: int = 30) -> None`

| Parameter | Exact type | Default |
| --- | --- | --- |
| `cache_path` | `pathlib.Path` | required (stored as `Path(cache_path)`) |
| `tickers` | `Sequence[str]` | required (deduplicated, trimmed, upper-cased into a `tuple`; empty raises `ValueError`) |
| `ticker_factory` | `TickerFactory \| None`, where `TickerFactory = Callable[[str], Any]` | `None` (keyword-only) |
| `request_spacing_seconds` | `float` | `0.0` (keyword-only, clamped to `>= 0.0`) |
| `retention_days` | `int` | `30` (keyword-only; `<= 0` raises `ValueError`) |

Instance fields initialised in `__init__`: `_observed` (`dict[str, tuple[date, ...]]`), `_fetched_at_by_ticker` (`dict[str, datetime]`), `_errors` (`dict[str, str]`), `fetched_at`, `last_attempt_at`, `last_success_at`, `last_error`, `load_error`, `refreshed_for_date`. The constructor calls `self.load()` and captures an `EarningsCacheError` into `load_error`.

Module-level: `logger = logging.getLogger(__name__)`.

### 3. Method Signatures

```python
def __init__(self, cache_path: Path, tickers: Sequence[str], *, ticker_factory: TickerFactory | None = None, request_spacing_seconds: float = 0.0, retention_days: int = 30) -> None

@property
def has_cache(self) -> bool

def dates_for(self, ticker: str) -> EarningsDates | None
def missing_tickers(self) -> list[str]
def needs_refresh(self, trading_date: date) -> bool
def snapshot(self, trading_date: date | None) -> dict[str, Any]

async def refresh(self, trading_date: date) -> int
def _record(self, ticker: str, observed: date, today: date) -> None
def _expire(self, today: date) -> None
def _fetch_one(self, ticker: str) -> date | None
def _ticker_handle(self, ticker: str) -> Any

def load(self) -> None
def save(self) -> None
```

Module-level helper functions:

```python
def _parse_calendar(calendar: Any) -> date | None
def _coerce_date(value: Any) -> date | None
```

### 4. Global State Interactions

**Concurrent collections / async primitives**

None of the collections are concurrent; `_observed`, `_fetched_at_by_ticker`, and `_errors` are plain `dict` fields with no lock. `refresh` is a coroutine that awaits `asyncio.sleep` between tickers and runs each blocking lookup through `asyncio.to_thread(self._fetch_one, ticker)`; the dictionaries themselves are only mutated back on the event-loop thread after every lookup completes.

| Method | Interaction |
| --- | --- |
| `has_cache` | Reads `_observed` |
| `dates_for(str)` | Reads `_observed` and `_fetched_at_by_ticker` |
| `missing_tickers()` | Reads `_observed` and `self.tickers` |
| `needs_refresh(date)` | Reads `refreshed_for_date` |
| `snapshot(date \| None)` | Reads `self.tickers`, `_observed`, `_fetched_at_by_ticker`, `_errors`, `fetched_at`, `last_attempt_at`, `last_success_at`, `last_error`, `load_error`, `refreshed_for_date` |
| `refresh(date)` | Mutates `last_attempt_at`; calls `_record` and `_expire`; mutates `_fetched_at_by_ticker`, `_errors`, `refreshed_for_date`, `fetched_at`, `last_success_at`, `last_error`; calls `save()` |
| `_record(str, date, date)` | Reads and mutates `_observed` |
| `_expire(date)` | Mutates `_observed` (`__setitem__`, `__delitem__`) and `_fetched_at_by_ticker` (`pop`) |
| `_fetch_one(str)` | Calls `_ticker_handle`; reads the handle's `calendar` attribute |
| `_ticker_handle(str)` | Reads `_ticker_factory`; lazily imports `yfinance` when it is `None` |
| `load()` | Mutates `_observed`, `_fetched_at_by_ticker`, `fetched_at`; clears both dicts before raising `EarningsCacheError` |
| `save()` | Reads `_observed`, `_fetched_at_by_ticker`, `fetched_at`; writes the cache file |

**Centralized state objects**

The single instance is created in the FastAPI `lifespan` when `cfg.earnings_enabled` is true and stored on `app.state.earnings_provider`, from which `_earnings_loop`, `/earnings`, and `/health` read it. `_earnings_loop` is the only caller of `refresh`.

**Filesystem state**

`save()` writes through `tempfile.mkstemp` in the cache directory, `flush`, `os.fsync`, then `os.replace`, unlinking the temporary file on failure.
