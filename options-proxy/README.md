# Options Proxy

The proxy publishes two options indicators to the Java trading engines over UDP:

1. **Static Daily Implied Move** — calculated per ticker from Massive prior-close data.
2. **SPY Gamma Flip** — entered manually in a hot-reloaded JSON file until the Massive calculation is implemented.

The indicators are independently valid. A missing Massive calculation does not prevent the proxy from publishing a valid manual gamma flip, and a missing gamma flip is never represented as a valid `0.0`.

It also serves two reference datasets over HTTP: **next earnings dates** at
`GET /earnings`, and the **market calendar** at `GET /calendar`.

## Which transport carries what

| | Transport | Why |
| --- | --- | --- |
| Implied move, gamma flip | UDP protobuf, once per second | Their *validity* changes during the session. A gamma flip can be entered at 09:50; an implied move fills in as the universe works through the rate limiter. Continuous rebroadcast is also the liveness signal a receiver uses to decide the proxy is alive. |
| Earnings dates | HTTP `GET /earnings`, pulled | Fetched once each morning and static for the session. A request/response lifecycle matches that; streaming it every second would be redundancy with no principled stopping point once other reference data follows it onto the frame. |
| Market calendar | HTTP `GET /calendar`, pulled | Holidays are known a year ahead and change almost never. |

The rule for anything added later: if its validity changes continuously, it earns a slot on the UDP frame. If it is reference data that is set once and then read, it belongs on a REST endpoint. Borrow rates, float, sector, and dividend dates would all be REST.

## Ticker universe

`TICKERS` is a comma-separated list covering the strategy universe the engines trade. Symbols are normalized to uppercase and deduplicated, so casing and stray whitespace in `.env` do not matter.

The proxy keeps one Static Daily Implied Move per ticker and emits **one frame per ticker** each broadcast interval. Missing calculations are performed sequentially through the existing Massive rate limiter, so a large universe fills in gradually rather than all at once. A ticker without a current value still broadcasts, with `static_daily_implied_move_valid=false`.

The shared SPY Gamma Flip is carried on **every** frame regardless of ticker, so a receiver does not need a dedicated SPY frame to learn the level.

## Static Daily Implied Move

For trading day `d`, the proxy uses the completed trading session immediately before `d`:

1. Retrieve the underlying's previous qualifying-trade daily close.
2. Select the expiration session at the end of `d`'s trading week.
3. Select the nearest standard strike having both a call and a put.
4. Retrieve the previous daily close for both selected options.
5. Require the stock, call, and put closes to have the same source session.
6. Count actual trading sessions from the source close through expiration.
7. Calculate:

```text
expiration_implied_move = 0.85 * (ATM call close + ATM put close)

static_daily_implied_move =
    expiration_implied_move * sqrt(1 / remaining_trading_sessions)
```

The result is a dollar distance frozen for the trading day. A Java engine can multiply it by two when a strategy needs a two-times-implied-move threshold.

`Static Daily Implied Move` is the system name for this straddle-derived heuristic. It is not represented as a statistically exact standard deviation.

## Manual SPY Gamma Flip

The runtime file defaults to:

```text
data/manual-options-inputs.json
```

This file is ignored by Git. Create it by copying the committed example:

```powershell
Copy-Item .\data\manual-options-inputs.example.json .\data\manual-options-inputs.json
```

Enter the level and the session it applies to before the market opens:

```json
{
  "spy_gamma_flip": 600.0,
  "trading_date": "2026-07-27"
}
```

The proxy checks the file once per second by default. It compares the modification time and size and reads the contents only after they differ from the last accepted version. The load timestamp is recorded automatically, so there is nothing else to type.

Validation rules:

- `spy_gamma_flip` must be a finite number greater than zero.
- `trading_date` must use strict `YYYY-MM-DD` format.
- The value is valid only for the session named by `trading_date`.

If an editor temporarily exposes malformed or partial JSON, the proxy retains the last accepted value and retries the failed file on the next poll. The diagnostic status reports the read error. A retained prior-day value becomes invalid automatically when the expected trading date changes.

Update `trading_date` along with the level every trading day. Do not change only the number while leaving yesterday's date, and do not leave yesterday's number with today's date.

Entering the value after the open is fine. The file is hot-reloaded, so the next frames simply become valid and a waiting engine starts evaluating entries without a restart.

The future Massive implementation can replace the manual provider while preserving the `SpyGammaFlip` model and protobuf fields.

## Earnings dates

`GET /earnings` returns the next scheduled earnings date for every configured
ticker, in one response:

```json
{
  "trading_date": "2026-07-27",
  "source": "yfinance",
  "retention_days": 30,
  "stocks": {
    "AAPL": {
      "ticker": "AAPL",
      "next_earnings_date": "2026-07-30",
      "last_earnings_date": null,
      "valid": true,
      "fetched_at": "2026-07-27T13:02:11.884Z",
      "last_error": null
    },
    "TSLA": {
      "ticker": "TSLA",
      "next_earnings_date": "2026-10-21",
      "last_earnings_date": "2026-07-22",
      "valid": true
    },
    "MU": {
      "ticker": "MU",
      "next_earnings_date": null,
      "last_earnings_date": null,
      "valid": false,
      "last_error": "No earnings date was reported"
    }
  },
  "coverage": { "required": 32, "valid": 31, "missing_tickers": ["MU"] }
}
```

`valid` is the field to read: it says whether **any** date is held. That is what
separates "this company has no report scheduled" from "we could not find out",
which are opposite answers for a strategy avoiding earnings. Every configured
ticker appears whether or not it resolved, so a consumer can also tell an absent
date from a symbol it forgot to configure.

Either date may be null and at least one is present when `valid` is true.

### Why a past date is retained

`last_earnings_date` exists because a strategy avoiding the day *after* a report
needs that date, and the upstream field is the *next* report.

What that field does once a company reports is **inconsistent**. Checked live on
2026-07-27: GOOGL still showed 2026-07-22, five days stale, while TSLA, INTC and
NFLX had already rolled to October. A consumer relying on the upstream value
alone would apply a day-after rule to some tickers and silently skip it for
others, with no way to tell which.

So every observed date is kept until it is `retention_days` old. One market day
would satisfy the rule; a month costs nothing and makes a skipped entry
explainable weeks later.

**Reported and rescheduled are not the same thing.** When a held date changes:

- If the held date has **already passed**, the report happened. Keep it.
- If the held date is **still in the future**, it was rescheduled or the estimate
  was corrected. Nothing occurred on it, so keeping it would black out an
  ordinary trading day. Discard it.

Expiry runs for every ticker each cycle, including those whose lookup failed, so
a stale date cannot outlive its window just because its symbol stopped
resolving.

One limitation: this retains only dates the proxy actually **observed** while
they were still upcoming. A date is typically visible for around 90 days before
the report and the proxy fetches every session, so missing one entirely means
being down for months.

### This endpoint publishes facts, not decisions

The proxy reports the dates. It does **not** report whether a ticker is
tradeable. How many sessions either side of a report a strategy declines to
trade is that strategy's own policy — different strategies want different
windows, and one may want to trade earnings rather than avoid them. Encoding one
strategy's window here would force a proxy change and a redeploy of both
processes every time a new strategy disagreed.

### Whole universe, one endpoint

There is deliberately no per-ticker route. The payload is a few KB, the proxy
computes every ticker anyway, and a single response carries one `trading_date`
and one coverage answer that cannot disagree with itself. Thirty-two separate
responses could straddle a refresh, and a caller would have to assemble coverage
from partial results rather than read it.

### Refresh and caching

Dates come from **yfinance**, which consumes no Massive quota but is an
unofficial API that rate-limits by address and fails unevenly across symbols.
The refresh runs in the background, so the endpoint always answers from the
retained cache instead of blocking a caller behind dozens of lookups.

- One symbol's failure never discards the other symbols' results.
- A failed refresh retains the previous good value; the error is reported
  alongside it in `last_error`.
- The cache at `data/earnings-dates.json` is written through a temporary file
  and an atomic replace, the same as `market_holidays.json`. It is runtime data
  and excluded from Git.
- A refresh happens once per trading session, so a moved or newly published date
  is picked up. If a cycle resolves **nothing** — usually the network rather than
  the symbols — the session is not marked done and the next cycle retries. If it
  resolves something, the session is done, so one permanently delisted symbol
  cannot drive an endless retry loop.

When the source returns a range rather than a single date, the **earliest** is
taken, so a consumer avoiding a report errs early rather than late.

## Market calendar

`GET /calendar` serves today's session status together with the raw holiday
records:

```json
{
  "trading_date": "2026-07-27",
  "session": {
    "trading_date": "2026-07-27",
    "status": "open",
    "is_session": true,
    "opens_at": "2026-07-27T13:30:00.000Z",
    "closes_at": "2026-07-27T20:00:00.000Z",
    "hours_source": "standard",
    "previous_session": "2026-07-24",
    "next_session": "2026-07-28"
  },
  "holidays": [
    { "date": "2026-11-27", "status": "early-close", "name": "Thanksgiving",
      "opens_at": "2026-11-27T14:30:00.000Z", "closes_at": "2026-11-27T18:00:00.000Z" }
  ],
  "cache": { "present": true, "covers_from": "2026-09-07", "covers_to": "2027-07-05" }
}
```

### Why `session.closes_at` matters

**An early-close date is still a session.** It passes `is_session`, so the UDP
frames carry its `trading_date` and every indicator reads valid — a consumer
that assumes a 16:00 close will happily schedule exits into a market that shut
at 13:00.

Full closures do not have this problem. On a closed date the proxy stamps
frames with the *next* session, a consumer comparing against its own date
disagrees, everything reads invalid, and nothing trades. That is the right
outcome reached by accident; early closes get no such protection.

Verified against the live Massive feed: `open` and `close` are populated on
early-close records and absent on full closures. In the next twelve months
there are exactly two, **2026-11-27** and **2026-12-24**, both closing at 13:00
Eastern.

### Reading the response

- `status` is `open`, `early-close`, or `closed`.
- `opens_at` and `closes_at` are ISO-8601 **UTC** and are **always populated for
  a session**, so a consumer never encodes market hours itself. They are null
  only when the date is closed.
- `hours_source` is `early-close-record` when the times came from the feed and
  `standard` when the proxy filled in the regular 09:30-16:00 Eastern session.
  That distinction is reported rather than left to be inferred from the times.
- Standard hours follow daylight saving, so the same 16:00 Eastern close is
  20:00Z in summer and 21:00Z in winter. Convert through `America/New_York`
  rather than assuming an offset.
- Records are collapsed to one entry per date. NYSE and NASDAQ have agreed on
  every record returned so far; if they ever disagree the more restrictive
  status wins.
- Do not use `name` for logic. The 2026-11-27 early close is named
  "Thanksgiving" although Thanksgiving itself is the 26th.

### The cache is a window, not a complete calendar

Massive's `/v1/marketstatus/upcoming` returns **upcoming records only**. Past
holidays are absent until the local cache has accumulated them, so
`cache.covers_from` and `covers_to` are reported: a date outside that range is
*unknown*, not open.

This matters for backward-looking arithmetic. `is_session` treats any date
without a `closed` record as a session, so a past holiday that predates the
cache reads as a trading day. `sessions_between` spanning such a date would
over-count sessions and understate the Static Daily Implied Move. Both current
uses look forward from today, and the cache accumulates as it runs, so this
self-heals — but it is a real limitation of a freshly created cache.

## Trading days and holidays

At startup and whenever a new implied move is needed, the proxy requests Massive's `/v1/marketstatus/upcoming` endpoint. NYSE and NASDAQ holiday records are merged into `data/market_holidays.json` using an atomic file replacement.

- Weekends and records with `status=closed` do not count as trading sessions.
- An `early-close` date still counts as one trading session.
- If the holiday request fails, an existing cache may be used.
- If the request fails and no cache exists, the implied move is invalid.

The cache is runtime data and is excluded from Git.

## Massive data requests

A normal implied-move refresh uses:

1. Upcoming market holidays
2. Previous underlying daily bar
3. Reference contracts for the selected expiration and an ATM strike window
4. Previous daily bar for the ATM call
5. Previous daily bar for the ATM put

The option-contract request uses a 10% strike window by default and a 1,000-row page size.

The Static Daily Implied Move is REST-only. The parked Massive WebSocket client is not started or required.

## Massive request limiter

The REST client defaults to a process-wide sliding-window limit of five request starts in any rolling 60-second period. The limiter is applied immediately before every HTTP attempt, so retries and pagination count too.

```text
REST_RATE_LIMIT_ENABLED=true
REST_RATE_LIMIT_REQUESTS=5
REST_RATE_LIMIT_PERIOD_SECONDS=60
```

To switch it off after moving to an unlimited Massive plan:

```text
REST_RATE_LIMIT_ENABLED=false
```

No code change is required. The limiter coordinates all requests using the same proxy process and REST client. It cannot coordinate separate proxy processes.

## UDP protobuf

The UDP payload is `options_proxy.IndicatorFrame` from `proto/market_state.proto`:

```protobuf
message IndicatorFrame {
  uint64 sequence                              = 1;
  int64  emitted_at_unix_ms                    = 2;
  string ticker                                = 3;
  double static_daily_implied_move             = 4;
  bool   static_daily_implied_move_valid       = 5;
  reserved 6;
  double spy_gamma_flip                        = 7;
  bool   spy_gamma_flip_valid                  = 8;
  reserved 9, 10;
  string trading_date                          = 11;
}
```

`trading_date` is the intended U.S. market session in `YYYY-MM-DD` form and is the field a receiver uses to decide whether the indicators apply to today. `emitted_at_unix_ms` is for transport freshness only. Fields 6, 9, and 10 previously carried per-indicator `as_of` timestamps and a manual `source`; they are reserved so the numbers are never reused.

A frame is emitted at the configured broadcast interval for every configured ticker, even when only one indicator is valid. Receivers must treat each `*_valid` field as authoritative and must never interpret the default numeric `0.0` as a usable indicator. They should also reject stale frames and treat a silent socket as unavailable proxy data.

The Java engine keeps a byte-identical copy of this contract at `trading-engine/trading-engine/src/main/proto/market_state.proto`. **Both copies must be updated together.** After changing the contract, regenerate both sides:

```powershell
uv run python -m grpc_tools.protoc -I. --python_out=. proto/market_state.proto
python tools\generate_java_fixtures.py --output ..\trading-engine\trading-engine\src\test\resources\optionsproxy
```

The second command refreshes the golden payloads the Java `PythonIndicatorFrameInteropTest` decodes, which is what proves the two languages still agree.

## HTTP endpoints

- `GET /health` reports overall readiness, per-ticker implied-move coverage, manual-file errors, per-ticker Massive refresh errors, earnings coverage, rate-limiter state, and UDP counters.
- `GET /indicator` exposes the current indicators for every configured ticker and their validity for local diagnostics.
- `GET /earnings` returns next earnings dates for the whole universe.
- `GET /calendar` returns today's session status and the holiday records.

Overall health is `ready` only when **every** configured ticker has a valid Static Daily Implied Move and the SPY Gamma Flip is valid for the expected trading date. `static_daily_implied_move_coverage.missing_tickers` names the ones still outstanding. The UDP frames still publish whatever is valid while health is degraded.

Earnings is reported under its own `earnings` key with its own `status`, and
deliberately **does not** fold into the top-level status. That status answers
"is the UDP contract being fully delivered", and earnings is not on the UDP
contract. Keeping them apart stops one delisted symbol from making a healthy
broadcast look broken, and stops a healthy broadcast from masking an empty
earnings cache. Check both.

## Configuration

Copy `.env.example` to `.env` and set at least:

```text
MASSIVE_API_KEY=...
TICKERS=AAPL,AMD,AMZN,...,XOM
MANUAL_GAMMA_FLIP_PATH=data/manual-options-inputs.json
MANUAL_GAMMA_FLIP_POLL_INTERVAL_SECONDS=1
UDP_HOST=127.0.0.1
UDP_PORT=5005
```

Earnings settings, all optional:

```text
EARNINGS_ENABLED=true
EARNINGS_CACHE_PATH=data/earnings-dates.json
EARNINGS_CHECK_INTERVAL_SECONDS=300
EARNINGS_REQUEST_SPACING_SECONDS=0.5
EARNINGS_RETENTION_DAYS=30
```

`EARNINGS_CHECK_INTERVAL_SECONDS` governs only how soon a failed attempt is
retried; a successful refresh happens once per session regardless.
`EARNINGS_REQUEST_SPACING_SECONDS` paces the yfinance calls, which rate-limit by
address. One pass over the universe runs once a session, so the spacing costs
nothing that matters. With `EARNINGS_ENABLED=false` the endpoint still answers,
reporting zero coverage and `"Earnings retrieval is disabled"`.

`TICKERS` must match the trading engine's `STRATEGY_TWO_SIGMA_DOWNSIDE_UNIVERSE`. The engine drops frames for any symbol outside its own universe, so a symbol present here but not there is wasted Massive quota.

`MANUAL_GAMMA_FLIP_POLL_INTERVAL_SECONDS=1` is the recommended setting. Polling a small local file at that interval has negligible overhead, and unchanged files are not reread.

`UDP_HOST` currently identifies one receiving Java engine. Multi-destination delivery must be added before both the LIVE engine (Ubuntu laptop, Ethernet address) and the PAPER engine (Windows localhost) can receive the same proxy frames simultaneously. Same-laptop testing needs only the default `127.0.0.1:5005`.

### HTTP binding

`HOST` defaults to `0.0.0.0`, so the HTTP server already listens on **every**
interface, not just loopback. That is what a remote LIVE engine on the Ubuntu
laptop will need in order to pull `/earnings`, but it also means `/health`,
`/indicator`, and `/earnings` are reachable from anything on the LAN today.

Nothing here is authenticated and nothing accepts writes, so the exposure is
read-only diagnostic data. Still worth setting deliberately rather than
inheriting: use `HOST=127.0.0.1` while only the same laptop consumes the
endpoints, and set it to the specific Ethernet address rather than `0.0.0.0`
when the remote engine needs it.

## Run

```powershell
uv sync --group dev
uv run python main.py
```

Then inspect:

```text
http://127.0.0.1:8000/health
http://127.0.0.1:8000/indicator
http://127.0.0.1:8000/earnings
http://127.0.0.1:8000/calendar
```

## Test

```powershell
uv run pytest -q
```

The tests use temporary files and mocked Massive responses. They do not consume Massive API requests, and the earnings tests substitute a fake ticker factory, so they never reach yfinance either.

`yfinance` is imported lazily, inside the one method that builds a ticker
handle. The module imports and the whole suite runs on a machine that has never
installed it.
