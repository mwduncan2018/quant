---
paths:
  - "options-proxy/**/*"
---

# `config.py` and `models.py`

Sources:
- `options-proxy/config.py`
- `options-proxy/models.py`

Python has no access modifiers. Names are recorded as written; a leading underscore marks a module- or class-private name by convention.

---

## `Settings`

`class Settings(BaseSettings)`

### 1. Class/Interface Responsibilities

Pydantic settings model declaring every proxy configuration field — Massive credentials and REST limits, holiday/gamma-flip/earnings cache paths and intervals, implied-move parameters, UDP transport settings, HTTP host/port/log level, and the retained WebSocket settings — loaded from environment variables or `.env`.

### 2. Injected Dependencies

No explicit `__init__`; construction is `pydantic_settings.BaseSettings.__init__`, configured by `model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", case_sensitive=False, extra="ignore")`.

Declared fields with their exact annotations and defaults:

| Field | Type | Default |
| --- | --- | --- |
| `massive_api_key` | `str` | `""` |
| `massive_base_url` | `str` | `"https://api.massive.com"` |
| `tickers` | `str` | `"SPY"` |
| `rest_concurrency` | `int` | `5` |
| `rest_max_retries` | `int` | `2` |
| `rest_rate_limit_enabled` | `bool` | `True` |
| `rest_rate_limit_requests` | `int` | `5` |
| `rest_rate_limit_period_seconds` | `float` | `60.0` |
| `holiday_cache_path` | `Path` | `Path("data/market_holidays.json")` |
| `manual_gamma_flip_path` | `Path` | `Path("data/manual-options-inputs.json")` |
| `manual_gamma_flip_poll_interval_seconds` | `float` | `1.0` |
| `earnings_enabled` | `bool` | `True` |
| `earnings_cache_path` | `Path` | `Path("data/earnings-dates.json")` |
| `earnings_check_interval_seconds` | `float` | `300.0` |
| `earnings_request_spacing_seconds` | `float` | `0.5` |
| `earnings_retention_days` | `int` | `30` |
| `implied_move_multiplier` | `float` | `0.85` |
| `atm_strike_window_pct` | `float` | `0.10` |
| `calculation_check_interval_seconds` | `float` | `60.0` |
| `udp_host` | `str` | `"127.0.0.1"` |
| `udp_port` | `int` | `5005` |
| `udp_mtu` | `int` | `1400` |
| `broadcast_interval_ms` | `int` | `1000` |
| `host` | `str` | `"0.0.0.0"` |
| `port` | `int` | `8000` |
| `log_level` | `str` | `"info"` |
| `massive_ws_url` | `str` | `"wss://socket.massive.com/options"` |
| `max_ws_subscriptions` | `int` | `1000` |
| `ws_reconnect_base_s` | `float` | `1.0` |
| `ws_reconnect_max_s` | `float` | `30.0` |

### 3. Method Signatures

```python
@property
def ticker_symbols(self) -> tuple[str, ...]
```

Module-level binding: `cfg = Settings()`.

### 4. Global State Interactions

**Concurrent collections**

None. `Settings` holds no concurrent collection, lock, or async primitive.

**Centralized state objects**

`cfg` is a module-level singleton imported by `main.py`, `massive_client.py`, `massive_ws.py`, and `udp_broadcaster.py`, and used as the default argument of `MassiveRestClient.__init__`, `StaticDailyImpliedMoveCalculator.__init__`, and `UdpBroadcaster.__init__`. Reading `cfg` mutates nothing; `ticker_symbols` recomputes from `self.tickers` on every access.

**Process-level state read**

Pydantic reads the process environment and `.env` at `Settings()` construction, which happens once at module import.

---

## `DailyBar`

`@dataclass(frozen=True, slots=True) class DailyBar`

### 1. Class/Interface Responsibilities

Immutable value object for one Massive qualifying-trade daily bar.

### 2. Injected Dependencies

Generated dataclass `__init__` fields:

| Field | Type |
| --- | --- |
| `ticker` | `str` |
| `session_date` | `datetime.date` |
| `close` | `float` |
| `timestamp_ms` | `int` |

### 3. Method Signatures

None beyond the dataclass-generated `__init__`, `__repr__`, and `__eq__`.

### 4. Global State Interactions

None. Frozen dataclass.

---

## `OptionContract`

`@dataclass(frozen=True, slots=True) class OptionContract`

### 1. Class/Interface Responsibilities

Immutable value object holding the reference fields that identify one option contract.

### 2. Injected Dependencies

| Field | Type | Default |
| --- | --- | --- |
| `ticker` | `str` | — |
| `underlying_ticker` | `str` | — |
| `expiration_date` | `datetime.date` | — |
| `strike_price` | `float` | — |
| `contract_type` | `str` | — |
| `shares_per_contract` | `float` | — |
| `has_additional_underlyings` | `bool` | `False` |

### 3. Method Signatures

```python
@property
def is_standard(self) -> bool
```

### 4. Global State Interactions

None. Frozen dataclass.

---

## `MarketHoliday`

`@dataclass(frozen=True, slots=True) class MarketHoliday`

### 1. Class/Interface Responsibilities

Immutable value object for one Massive holiday or shortened-session record.

### 2. Injected Dependencies

| Field | Type | Default |
| --- | --- | --- |
| `date` | `datetime.date` | — |
| `exchange` | `str` | — |
| `status` | `str` | — |
| `name` | `str` | `""` |
| `open_at` | `str \| None` | `None` |
| `close_at` | `str \| None` | `None` |

### 3. Method Signatures

None beyond the dataclass-generated methods.

### 4. Global State Interactions

None. Frozen dataclass.

---

## `StaticDailyImpliedMove`

`@dataclass(frozen=True, slots=True) class StaticDailyImpliedMove`

### 1. Class/Interface Responsibilities

Immutable value object holding one calculated daily implied move and every input that produced it.

### 2. Injected Dependencies

| Field | Type |
| --- | --- |
| `ticker` | `str` |
| `trading_date` | `datetime.date` |
| `source_session_date` | `datetime.date` |
| `expiration_date` | `datetime.date` |
| `underlying_close` | `float` |
| `atm_strike` | `float` |
| `call_ticker` | `str` |
| `put_ticker` | `str` |
| `call_close` | `float` |
| `put_close` | `float` |
| `expiration_implied_move` | `float` |
| `remaining_trading_sessions` | `int` |
| `static_daily_implied_move` | `float` |
| `calculated_at` | `datetime.datetime` |

### 3. Method Signatures

None beyond the dataclass-generated methods.

### 4. Global State Interactions

None. Frozen dataclass.

---

## `SpyGammaFlip`

`@dataclass(frozen=True, slots=True) class SpyGammaFlip`

### 1. Class/Interface Responsibilities

Immutable value object for one manually supplied SPY gamma-flip level and the session it applies to.

### 2. Injected Dependencies

| Field | Type |
| --- | --- |
| `ticker` | `str` |
| `spy_gamma_flip` | `float` |
| `trading_date` | `datetime.date` |
| `loaded_at` | `datetime.datetime` |

### 3. Method Signatures

None beyond the dataclass-generated methods.

### 4. Global State Interactions

None. Frozen dataclass.

---

## `EarningsDates`

`@dataclass(frozen=True, slots=True) class EarningsDates`

### 1. Class/Interface Responsibilities

Immutable value object holding every retained earnings date for one ticker plus its fetch timestamp.

### 2. Injected Dependencies

| Field | Type |
| --- | --- |
| `ticker` | `str` |
| `observed` | `tuple[datetime.date, ...]` |
| `fetched_at` | `datetime.datetime` |

### 3. Method Signatures

```python
def next_on_or_after(self, today: date) -> date | None
def last_before(self, today: date) -> date | None
```

### 4. Global State Interactions

None. Frozen dataclass; both methods filter `self.observed` and return a value.
