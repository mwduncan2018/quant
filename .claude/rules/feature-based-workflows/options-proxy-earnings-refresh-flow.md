---
paths:
  - "options-proxy/**/*"
---

# Earnings Refresh and Cache Flow

Traces the once-per-session earnings refresh: the background loop, the blocking
yfinance lookups moved off the event loop, the merge/expire rules, and the atomic
cache write that backs the `/earnings` response.

## 1. Workflow Components

| Component | Role in this flow |
| --- | --- |
| `main.lifespan` | Constructs `EarningsProvider` when `cfg.earnings_enabled`, reports its load state, and creates `_earnings_loop`. |
| `main._earnings_loop` | Coroutine that checks `needs_refresh(expected_date)` and calls `refresh(expected_date)`. |
| `main._expected_trading_date` | Resolves the session the refresh is keyed to. |
| `main.earnings` | FastAPI route returning `provider.snapshot(expected_date)`, or a disabled-state payload when the provider is `None`. |
| `main.health` | Reports the provider's coverage, timestamps, and last error separately from the UDP contract status. |
| `earnings.EarningsProvider` | Owns `_observed`, `_fetched_at_by_ticker`, `_errors`, the refresh timestamps, and the cache file. |
| `earnings.EarningsCacheError` | Raised by `load()` when an existing cache cannot be read. |
| `earnings._parse_calendar` | Extracts the soonest date from a yfinance calendar of any supported shape. |
| `earnings._coerce_date` | Converts a `datetime`, `date`, ISO string, or object exposing `date()` into a `date`. |
| `models.EarningsDates` | Frozen dataclass `(ticker, observed, fetched_at)` with `next_on_or_after(date)` and `last_before(date)`. |
| `market_calendar.MarketCalendar` | Supplies the expected trading date through `next_session_on_or_after`. |
| `config.Settings` / `config.cfg` | Supplies `earnings_enabled`, `earnings_cache_path`, `earnings_check_interval_seconds`, `earnings_request_spacing_seconds`, `earnings_retention_days`, `ticker_symbols`. |
| `yfinance` (lazy import) | Supplies `Ticker(symbol).calendar`. |
| `mwd.trading.earnings.EarningsClient` (trading engine) | The remote consumer of the `/earnings` payload. |

## 2. Execution Path

### Startup and cache load

1. **Initiating Component:** `main.lifespan`
   **Method Invocation:** `EarningsProvider(cfg.earnings_cache_path, cfg.ticker_symbols, request_spacing_seconds=cfg.earnings_request_spacing_seconds, retention_days=cfg.earnings_retention_days)`
   **Receiving Component:** `EarningsProvider`

2. **Initiating Component:** `EarningsProvider.__init__`
   **Method Invocation:** `self.load()`; an `EarningsCacheError` is caught and stored in `self.load_error`
   **Receiving Component:** `EarningsProvider.load`

3. **Initiating Component:** `EarningsProvider.load`
   **Method Invocation:** `json.loads(self.cache_path.read_text(encoding="utf-8"))`, then per stock entry populates `self._observed[ticker]` from `dates` (falling back to `[next_earnings_date]`) and `self._fetched_at_by_ticker[ticker]`
   **Receiving Component:** `EarningsProvider`

4. **Initiating Component:** `main.lifespan`
   **Method Invocation:** `asyncio.create_task(_earnings_loop(earnings_provider, calendar))` when the provider exists
   **Receiving Component:** the event loop

### Refresh cycle

5. **Initiating Component:** `main._earnings_loop`
   **Method Invocation:** `_expected_trading_date(calendar)` then `provider.needs_refresh(expected_date)`, which compares `self.refreshed_for_date`
   **Receiving Component:** `MarketCalendar`, `EarningsProvider`

6. **Initiating Component:** `main._earnings_loop`
   **Method Invocation:** `await provider.refresh(expected_date)` then `provider.missing_tickers()`
   **Receiving Component:** `EarningsProvider`

7. **Initiating Component:** `EarningsProvider.refresh`
   **Method Invocation:** sets `self.last_attempt_at`, then per ticker `await asyncio.sleep(self._request_spacing_seconds)` for all but the first
   **Receiving Component:** the event loop

8. **Initiating Component:** `EarningsProvider.refresh`
   **Method Invocation:** `await asyncio.to_thread(self._fetch_one, ticker)`; any exception is recorded in a per-ticker `errors` dict and the loop continues
   **Receiving Component:** a worker thread from the default `asyncio` executor

9. **Initiating Component:** `EarningsProvider._fetch_one` (worker thread)
   **Method Invocation:** `self._ticker_handle(ticker)` → `yfinance.Ticker(ticker)` unless a `ticker_factory` was injected, then `_parse_calendar(getattr(handle, "calendar", None))`
   **Receiving Component:** `yfinance`, `earnings._parse_calendar`

10. **Initiating Component:** `earnings._parse_calendar` (worker thread)
    **Method Invocation:** reads `"Earnings Date"` from a dict or from an object exposing `get`, coerces every candidate with `_coerce_date`, and returns `min(parsed)`
    **Receiving Component:** caller

11. **Initiating Component:** `EarningsProvider.refresh`
    **Method Invocation:** `self._record(ticker, observed, trading_date)` for each resolved ticker, then `self._fetched_at_by_ticker[ticker] = now`
    **Receiving Component:** `EarningsProvider`

12. **Initiating Component:** `EarningsProvider._record`
    **Method Invocation:** retains held dates satisfying `item < today or item == observed` and adds `observed`, storing a sorted tuple
    **Receiving Component:** `EarningsProvider._observed`

13. **Initiating Component:** `EarningsProvider.refresh`
    **Method Invocation:** `self._expire(trading_date)`, which drops dates older than `today - timedelta(days=self.retention_days)` and removes tickers left with nothing
    **Receiving Component:** `EarningsProvider._observed`, `_fetched_at_by_ticker`

14. **Initiating Component:** `EarningsProvider.refresh`
    **Method Invocation:** when anything resolved — sets `refreshed_for_date`, `fetched_at`, `last_success_at`, `last_error`, then `self.save()`; when nothing resolved — sets `last_error` and leaves the cache and `refreshed_for_date` untouched
    **Receiving Component:** `EarningsProvider`

15. **Initiating Component:** `EarningsProvider.save`
    **Method Invocation:** `self.cache_path.parent.mkdir(parents=True, exist_ok=True)`, `tempfile.mkstemp(...)`, `json.dump(payload, handle, indent=2, sort_keys=True)`, `handle.flush()`, `os.fsync(handle.fileno())`, `os.replace(temporary_name, self.cache_path)`
    **Receiving Component:** the filesystem

16. **Initiating Component:** `main._earnings_loop`
    **Method Invocation:** `await asyncio.sleep(max(cfg.earnings_check_interval_seconds, 1.0))`
    **Receiving Component:** the event loop

### Serving

17. **Initiating Component:** trading engine `EarningsRefresher` → `EarningsClient` → `ProxyJsonFetcher`
    **Method Invocation:** `GET /earnings`
    **Receiving Component:** `main.earnings`

18. **Initiating Component:** `main.earnings`
    **Method Invocation:** `_expected_trading_date(calendar)` then `provider.snapshot(expected_date)`
    **Receiving Component:** `EarningsProvider.snapshot`

19. **Initiating Component:** `EarningsProvider.snapshot`
    **Method Invocation:** per ticker `self.dates_for(ticker)` → `EarningsDates`, then `held.next_on_or_after(today)` and `held.last_before(today)`; assembles `stocks`, `coverage`, and the diagnostic fields
    **Receiving Component:** `models.EarningsDates`, the JSON response

20. **Initiating Component:** `main.health`
    **Method Invocation:** `earnings_provider.missing_tickers()`, `refreshed_for_date`, `fetched_at`, `last_attempt_at`, `last_success_at`, `last_error or load_error`
    **Receiving Component:** `EarningsProvider`

## 3. Data Payloads and State Handoffs

### Objects passed

- `date trading_date` — the expected session; used as the refresh key, the merge cutoff in `_record`, the expiry basis in `_expire`, and the `today` argument in `snapshot`.
- `date | None observed` — one lookup's result; `None` records the per-ticker error `"No earnings date was reported"` rather than clearing held dates.
- `EarningsProvider._observed: dict[str, tuple[date, ...]]` — the retained dates per ticker, replaced wholesale per ticker on each `_record`.
- `EarningsDates` — read-only view constructed on demand by `dates_for`; carries `observed` and the per-ticker `fetched_at`.
- Response payload — `{"trading_date", "source": "yfinance", "retention_days", "stocks": {ticker: {"ticker", "next_earnings_date", "last_earnings_date", "valid", "fetched_at", "last_error"}}, "coverage": {"required", "valid", "missing_tickers"}, "cache_path", "fetched_at", "last_attempt_at", "last_success_at", "last_error", "refreshed_for_trading_date", "retaining_cached_values"}`. `valid` is the field the engine's `EarningsClient` reads before accepting an entry.
- Cache file payload — `{"fetched_at": iso, "stocks": [{"ticker", "dates": [iso, ...], "fetched_at": iso}, ...]}` written sorted and indented.

### Task and thread handoffs

| Handoff | Detail |
| --- | --- |
| `lifespan` → `_earnings_loop` | `asyncio.create_task`; the loop shares the event loop with the request handlers, so `/earnings` always answers from the retained cache rather than blocking behind lookups. |
| `_earnings_loop` → worker thread | `await asyncio.to_thread(self._fetch_one, ticker)` moves each blocking yfinance call onto a thread from the default executor; only one ticker is in flight at a time because the loop awaits each call in sequence. |
| Worker thread → event loop | The returned `date | None` (or the raised exception) is delivered back to the awaiting coroutine; every mutation of `_observed`, `_errors`, and the timestamps happens on the event loop after the await, never on the worker thread. |
| `_earnings_loop` → filesystem | `save()` performs blocking `mkstemp`, `json.dump`, `fsync`, and `os.replace` inline on the event loop; the temp-file-plus-replace sequence is what keeps a concurrent reader from seeing a partial file. |
| Event loop → pacing | `asyncio.sleep(self._request_spacing_seconds)` between tickers yields the loop so request handlers and the other three loops continue running during a refresh pass. |
| Options-proxy process → trading-engine process | The `/earnings` HTTP response, pulled by the engine's `Earnings-Refresher-Thread`; this is a request/response path, unlike the continuously broadcast UDP indicators. |

A refresh that resolves nothing leaves `refreshed_for_date` unchanged, so
`needs_refresh` stays `True` and the next loop iteration retries; a refresh that
resolves at least one ticker marks the session done even if other tickers failed.
