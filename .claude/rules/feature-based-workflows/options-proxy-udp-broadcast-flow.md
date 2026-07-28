---
paths:
  - "options-proxy/**/*"
---

# UDP Indicator Broadcast Flow

Traces the two loops that produce the outbound datagram stream: the manual
gamma-flip file poll and the per-ticker broadcast loop that serializes an
`IndicatorFrame` and sends it to the trading engine.

## 1. Workflow Components

| Component | Role in this flow |
| --- | --- |
| `main.lifespan` | Constructs `ManualGammaFlipProvider` and `UdpBroadcaster`, performs the initial `poll()`, calls `broadcaster.open()`, and creates both loop tasks. |
| `main._gamma_flip_loop` | Coroutine that polls the manual JSON file on a fixed interval and logs newly accepted values. |
| `main._broadcast_loop` | Coroutine that resolves the expected session and the current gamma flip, then broadcasts one frame per configured ticker. |
| `main._expected_trading_date` | Resolves the session stamped on every frame. |
| `main._current_implied_move` | Returns the stored `StaticDailyImpliedMove` only when its `trading_date` matches the expected session; otherwise `None`. |
| `main.runtime_by_ticker` | Module-level dict of `Runtime` objects the broadcast loop reads. |
| `manual_gamma_flip.ManualGammaFlipProvider` | Stats and reads the JSON file, validates it, retains the last accepted value, and answers `current_for(date)` / `status(date)`. |
| `manual_gamma_flip.ManualGammaFlipError` | Raised for any unacceptable file content. |
| `manual_gamma_flip._parse_value` | Validates `spy_gamma_flip` as a finite positive number and `trading_date` as a strict `YYYY-MM-DD` string. |
| `udp_broadcaster.UdpBroadcaster` | Owns the non-blocking `socket.socket`, the monotonically increasing sequence, and the sent/error counters. |
| `models.SpyGammaFlip` | Frozen dataclass `(ticker, spy_gamma_flip, trading_date, loaded_at)`. |
| `models.StaticDailyImpliedMove` | Frozen dataclass supplying `static_daily_implied_move`, `ticker`, and `trading_date`. |
| `proto.market_state_pb2.IndicatorFrame` | Generated protobuf message serialized onto the wire. |
| `proto/market_state.proto` | Schema defining the eight populated fields and the reserved tags. |
| `config.Settings` / `config.cfg` | Supplies `ticker_symbols`, `manual_gamma_flip_path`, `manual_gamma_flip_poll_interval_seconds`, `broadcast_interval_ms`, `udp_host`, `udp_port`, `udp_mtu`. |
| `mwd.trading.optionsproxy.OptionsIndicatorFrameReceiver` (trading engine) | The remote peer that receives each datagram. |

## 2. Execution Path

### Startup

1. **Initiating Component:** `main.lifespan`
   **Method Invocation:** `ManualGammaFlipProvider(cfg.manual_gamma_flip_path, "SPY")` then `gamma_flip_provider.poll()`
   **Receiving Component:** `ManualGammaFlipProvider`

2. **Initiating Component:** `main.lifespan`
   **Method Invocation:** `UdpBroadcaster()` then `broadcaster.open()` → `socket.socket(socket.AF_INET, socket.SOCK_DGRAM)` with `setblocking(False)`
   **Receiving Component:** `UdpBroadcaster`

3. **Initiating Component:** `main.lifespan`
   **Method Invocation:** `asyncio.create_task(_gamma_flip_loop(gamma_flip_provider))` and `asyncio.create_task(_broadcast_loop(broadcaster, calendar, gamma_flip_provider))`
   **Receiving Component:** the event loop

### Manual gamma-flip poll

4. **Initiating Component:** `main._gamma_flip_loop`
   **Method Invocation:** `provider.poll()` every `max(cfg.manual_gamma_flip_poll_interval_seconds, 0.1)` seconds
   **Receiving Component:** `ManualGammaFlipProvider`

5. **Initiating Component:** `ManualGammaFlipProvider.poll`
   **Method Invocation:** `self.path.stat()` producing `(st_mtime_ns, st_size)`; an unchanged signature with no prior error returns `None` without reading
   **Receiving Component:** the filesystem

6. **Initiating Component:** `ManualGammaFlipProvider.poll`
   **Method Invocation:** `self.path.read_text(encoding="utf-8")`, a second `self.path.stat()` compared against the first, `json.loads(raw)`, then `_parse_value(parsed, self.ticker, checked_at)`
   **Receiving Component:** `manual_gamma_flip._parse_value` → `SpyGammaFlip`

7. **Initiating Component:** `ManualGammaFlipProvider.poll`
   **Method Invocation:** on success, assigns `self.value`, clears `self.last_error`, sets `self.last_loaded_at` and `self._accepted_signature`; on failure sets `self.last_error` and leaves `self.value` and `_accepted_signature` untouched
   **Receiving Component:** `ManualGammaFlipProvider`

### Broadcast

8. **Initiating Component:** `main._broadcast_loop`
   **Method Invocation:** `_expected_trading_date(calendar)`
   **Receiving Component:** `MarketCalendar.next_session_on_or_after`

9. **Initiating Component:** `main._broadcast_loop`
   **Method Invocation:** `gamma_flip_provider.current_for(expected_date)`, which returns the retained `SpyGammaFlip` only when its `trading_date` equals the expected date
   **Receiving Component:** `ManualGammaFlipProvider`

10. **Initiating Component:** `main._broadcast_loop`
    **Method Invocation:** `_current_implied_move(calendar, ticker)` for each `cfg.ticker_symbols` entry
    **Receiving Component:** `main.runtime_by_ticker`

11. **Initiating Component:** `main._broadcast_loop`
    **Method Invocation:** `await broadcaster.broadcast(ticker, expected_date, implied_move, gamma_flip)`; an `OSError` is logged with `logger.exception` and the loop continues to the next ticker
    **Receiving Component:** `UdpBroadcaster`

12. **Initiating Component:** `UdpBroadcaster.broadcast`
    **Method Invocation:** `self.open()` when the socket is `None`, then `self.serialize(ticker, trading_date, implied_move, gamma_flip)`
    **Receiving Component:** `UdpBroadcaster.serialize`

13. **Initiating Component:** `UdpBroadcaster.serialize`
    **Method Invocation:** normalizes the ticker; raises `ValueError` when it is blank, when `implied_move.ticker != ticker` or its `trading_date` differs, or when `gamma_flip.trading_date` differs from the frame's
    **Receiving Component:** caller

14. **Initiating Component:** `UdpBroadcaster.serialize`
    **Method Invocation:** `self._sequence += 1` then `IndicatorFrame(sequence=..., emitted_at_unix_ms=time.time_ns() // 1_000_000, ticker=..., trading_date=trading_date.isoformat(), static_daily_implied_move=..., static_daily_implied_move_valid=implied_move is not None, spy_gamma_flip=..., spy_gamma_flip_valid=gamma_flip is not None)`
    **Receiving Component:** `proto.market_state_pb2.IndicatorFrame`

15. **Initiating Component:** `UdpBroadcaster.serialize`
    **Method Invocation:** `message.SerializeToString()`; a payload longer than `settings.udp_mtu` raises `ValueError`
    **Receiving Component:** caller

16. **Initiating Component:** `UdpBroadcaster.broadcast`
    **Method Invocation:** `await asyncio.get_running_loop().sock_sendto(self._socket, payload, (self.settings.udp_host, self.settings.udp_port))`, then `self._sent += 1`; an `OSError` increments `self._errors` and re-raises
    **Receiving Component:** the OS socket → trading engine `OptionsIndicatorFrameReceiver`

17. **Initiating Component:** `main._broadcast_loop`
    **Method Invocation:** `await asyncio.sleep(max(cfg.broadcast_interval_ms / 1000.0, 0.05))`
    **Receiving Component:** the event loop

### Diagnostics and shutdown

18. **Initiating Component:** `main.health`
    **Method Invocation:** `broadcaster.stats()` returning `{"sent": ..., "errors": ..., "sequence": ...}` and `provider.status(expected_date)`
    **Receiving Component:** `UdpBroadcaster`, `ManualGammaFlipProvider`

19. **Initiating Component:** `main.lifespan` `finally` block
    **Method Invocation:** `task.cancel()` for both loops, `await task` under `contextlib.suppress(asyncio.CancelledError)`, then `broadcaster.close()`
    **Receiving Component:** the event loop, `UdpBroadcaster`

## 3. Data Payloads and State Handoffs

### Objects passed

- Manual input file (`cfg.manual_gamma_flip_path`, default `data/manual-options-inputs.json`): a JSON object with `spy_gamma_flip` (number) and `trading_date` (`YYYY-MM-DD`).
- `SpyGammaFlip` — frozen dataclass retained on `ManualGammaFlipProvider.value`; the same instance is handed to every ticker's frame in one broadcast pass.
- `StaticDailyImpliedMove | None` — read from `runtime_by_ticker[ticker].value` through `_current_implied_move`; `None` is the normal payload for an uncovered ticker and produces `static_daily_implied_move=0.0` with `static_daily_implied_move_valid=False`.
- `IndicatorFrame` — the wire message. `sequence` is a single counter shared across all tickers, incremented once per serialized frame; `emitted_at_unix_ms` is captured at serialization time; `trading_date` is the expected session, not the source session.
- `bytes payload` — the serialized frame, bounded by `settings.udp_mtu` (default 1400).
- `(udp_host, udp_port)` — the destination tuple, default `("127.0.0.1", 5005)`.
- `UdpBroadcaster._sequence` / `_sent` / `_errors` — plain instance counters exposed through `stats()`.

Validity flags are the contract: a numeric field is meaningful only when its
paired `*_valid` flag is `True`, and the frame is emitted on every interval
regardless of whether either value is available.

### Task and thread handoffs

| Handoff | Detail |
| --- | --- |
| `lifespan` → `_gamma_flip_loop` and `_broadcast_loop` | `asyncio.create_task`; both run as independent tasks on the uvicorn event loop. |
| `_gamma_flip_loop` → `_broadcast_loop` | No direct call. The poll loop mutates `ManualGammaFlipProvider.value`; the broadcast loop reads it through `current_for(date)`. Both run on the same event loop, so the read never observes a partially assigned value. |
| Implied-move refresh task → `_broadcast_loop` | No direct call; the shared `runtime_by_ticker` dict is the only channel. |
| `ManualGammaFlipProvider.poll` | Performs blocking `stat` and `read_text` calls inline on the event loop; the double-`stat` signature check is what rejects a file that changed mid-read. |
| `_broadcast_loop` → OS socket | `loop.sock_sendto` on a socket set to `setblocking(False)`, so the send is awaited rather than blocking the loop. |
| Options-proxy process → trading-engine process | The UDP datagram itself. There is no acknowledgement, no retry, and no connection; start order between the processes does not matter because the stream is continuous. |
| FastAPI request handlers → these components | `/health` reads `broadcaster.stats()` and `provider.status(...)` on the same event loop, interleaved between loop iterations. |

No OS threads are involved on the proxy side of this flow. On the receiving side
the datagram is consumed by the trading engine's dedicated
`Options-Proxy-UDP-Receiver` thread.
