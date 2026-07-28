---
paths:
  - "options-proxy/**/*"
---

# `implied_move.py` and `manual_gamma_flip.py`

Sources:
- `options-proxy/implied_move.py`
- `options-proxy/manual_gamma_flip.py`

Python has no access modifiers. Names are recorded as written; a leading underscore marks a module- or class-private name by convention.

---

## `ImpliedMoveError`

`class ImpliedMoveError(RuntimeError)`

### 1. Class/Interface Responsibilities

Exception type raised when the previous session cannot produce a usable implied move.

### 2. Injected Dependencies

None declared; inherits `RuntimeError.__init__`.

### 3. Method Signatures

None declared.

### 4. Global State Interactions

None.

---

## `select_atm_pair` (module-level function)

### 1. Class/Interface Responsibilities

Module-level function that groups `OptionContract` objects by strike and returns the nearest standard strike having both a call and a put.

### 2. Injected Dependencies

Not a class; parameters are:

| Parameter | Exact type |
| --- | --- |
| `contracts` | `list[OptionContract]` |
| `underlying_close` | `float` |

### 3. Method Signatures

```python
def select_atm_pair(contracts: list[OptionContract], underlying_close: float) -> tuple[OptionContract, OptionContract]
```

### 4. Global State Interactions

None. Builds a local `dict[float, dict[str, OptionContract]]` and returns; raises `ImpliedMoveError` when no paired strike exists.

---

## `StaticDailyImpliedMoveCalculator`

`class StaticDailyImpliedMoveCalculator`

### 1. Class/Interface Responsibilities

Fetches the previous underlying bar, resolves the trading and expiration dates through `MarketCalendar`, fetches the ATM contract window and the two option bars from `MassiveRestClient`, and returns a `StaticDailyImpliedMove`.

### 2. Injected Dependencies

`def __init__(self, client: MassiveRestClient, calendar: MarketCalendar, settings: Settings = cfg) -> None`

| Parameter | Exact type | Default |
| --- | --- | --- |
| `client` | `massive_client.MassiveRestClient` | required |
| `calendar` | `market_calendar.MarketCalendar` | required |
| `settings` | `config.Settings` | `cfg` (module-level singleton) |

Module-level constant: `EASTERN = ZoneInfo("America/New_York")`.

### 3. Method Signatures

```python
def __init__(self, client: MassiveRestClient, calendar: MarketCalendar, settings: Settings = cfg) -> None
async def calculate(self, ticker: str, *, now: datetime | None = None) -> StaticDailyImpliedMove
```

### 4. Global State Interactions

**Concurrent collections / async primitives**

Holds none. `calculate` uses `asyncio.gather` to fetch the call and put bars concurrently; both requests pass through the `asyncio.Semaphore` and `SlidingWindowRateLimiter` owned by the injected `MassiveRestClient`.

**Centralized state objects**

| Method | Interaction |
| --- | --- |
| `calculate(str, *, datetime \| None)` | Reads `self.client.fetch_previous_bar(...)` and `self.client.fetch_option_contracts(...)`; reads `self.calendar.next_session_on_or_after(...)`, `next_session_after(...)`, `nominal_friday(...)`, `previous_session_on_or_before(...)`, `sessions_between(...)`; reads `self.settings.atm_strike_window_pct` and `self.settings.implied_move_multiplier` |

Mutates no shared state; the calculated value is returned to the caller, which in `main.py` stores it on the module-level `runtime_by_ticker` entry.

---

## `ManualGammaFlipError`

`class ManualGammaFlipError(ValueError)`

### 1. Class/Interface Responsibilities

Exception type raised when the manual gamma-flip file cannot be accepted.

### 2. Injected Dependencies

None declared; inherits `ValueError.__init__`.

### 3. Method Signatures

None declared.

### 4. Global State Interactions

None.

---

## `ManualGammaFlipProvider`

`class ManualGammaFlipProvider`

### 1. Class/Interface Responsibilities

Polls a JSON file by `(st_mtime_ns, st_size)` signature, parses it into a `SpyGammaFlip`, retains the last accepted value, and exposes the value scoped to its trading date plus diagnostic status.

### 2. Injected Dependencies

`def __init__(self, path: Path, ticker: str = "SPY") -> None`

| Parameter | Exact type | Default |
| --- | --- | --- |
| `path` | `pathlib.Path` | required (stored as `Path(path)`) |
| `ticker` | `str` | `"SPY"` (stored trimmed and upper-cased) |

Instance fields initialised in `__init__`: `value` (`SpyGammaFlip | None`), `last_error` (`str | None`), `last_checked_at` (`datetime | None`), `last_loaded_at` (`datetime | None`), `_accepted_signature` (`tuple[int, int] | None`).

### 3. Method Signatures

```python
def __init__(self, path: Path, ticker: str = "SPY") -> None
def poll(self, now: datetime | None = None) -> SpyGammaFlip | None
def current_for(self, trading_date: date) -> SpyGammaFlip | None
def status(self, trading_date: date | None) -> dict[str, Any]
```

Module-level helper functions:

```python
def _parse_value(payload: Any, ticker: str, now: datetime) -> SpyGammaFlip
def _utc_now(value: datetime | None) -> datetime
```

### 4. Global State Interactions

**Concurrent collections / async primitives**

None. All state is plain instance attributes with no lock; `poll` is synchronous and is called from a single asyncio task in `main.py` (`_gamma_flip_loop`) plus once during startup.

**Centralized state objects**

| Method | Interaction |
| --- | --- |
| `poll(datetime \| None)` | Mutates `last_checked_at`; on failure mutates `last_error`; on success mutates `value`, `last_error`, `last_loaded_at`, `_accepted_signature`; reads and stats `self.path` |
| `current_for(date)` | Reads `self.value` |
| `status(date \| None)` | Reads `self.value`, `self.last_checked_at`, `self.last_loaded_at`, `self.last_error`, `self.path`, `self.ticker`; calls `current_for` |

The single instance is created in the FastAPI `lifespan` and stored on `app.state.gamma_flip_provider`, from which `/indicator`, `/health`, and `_broadcast_loop` read it.
