---
paths:
  - "trading-engine/**/*"
---

# Options-Indicator Ingestion Flow (UDP)

Traces an `IndicatorFrame` datagram from the options proxy through the UDP
receiver, into `OptionsIndicatorStore`, and out to the strategy threads and the
Swing monitor.

## 1. Workflow Components

| Component | Role in this flow |
| --- | --- |
| `mwd.trading.app.Main` | Constructs the store and receiver, supplies the accepted-frame listener, starts the receiver, and stops it from the shutdown hook. |
| `mwd.trading.config.Config` | Supplies `isOptionsProxyEnabled()`, `getOptionsProxyBindHost()`, `getOptionsProxyUdpPort()`, `getOptionsProxyFrameMaxAgeMs()`. |
| `mwd.trading.optionsproxy.OptionsIndicatorFrameReceiver` | Binds the `DatagramSocket`, runs the receive loop, parses each datagram, and forwards accepted frames to the listener. |
| `mwd.trading.optionsproxy.OptionsIndicatorFrameReceiver.AcceptedFrameListener` | Functional interface with `onAccepted(IndicatorFrame)`, invoked on the receiver thread. |
| `mwd.trading.optionsproxy.proto.IndicatorFrame` | Generated protobuf message carrying `sequence`, `emitted_at_unix_ms`, `ticker`, `static_daily_implied_move(+_valid)`, `spy_gamma_flip(+_valid)`, `trading_date`. |
| `mwd.trading.optionsproxy.proto.MarketStateProto` | Generated descriptor holder for the `IndicatorFrame` message. |
| `mwd.trading.optionsproxy.OptionsIndicatorStore` | Validates each frame and holds per-ticker `TickerState` plus the shared `GammaFlip`. |
| `mwd.trading.optionsproxy.OptionsIndicatorStore.ImpliedMove` | Record `(String ticker, double value, LocalDate tradingDate, long sequence, long emittedAtUnixMs, long receivedAtUnixMs)`. |
| `mwd.trading.optionsproxy.OptionsIndicatorStore.GammaFlip` | Record `(double level, boolean valid, LocalDate tradingDate, long sequence, long emittedAtUnixMs, long receivedAtUnixMs)`. |
| `java.net.DatagramSocket` / `java.net.DatagramPacket` | Transport objects owned by the receiver. |
| `com.google.protobuf.InvalidProtocolBufferException` | Signals a malformed datagram, which is counted and dropped. |
| `mwd.trading.state.Blackboard` | Reached only by the monitor-mirroring listener, via `getStock(String)`. |
| `mwd.trading.domain.Stock` | Receives `setDailyImpliedMove(double)` and `setGammaFlip(double)` for display only. |
| `mwd.trading.strategy.TwoSigmaDownsideMeanReversionStrategy` | Reads `impliedMoveForNewEntry`, `gammaFlipForNewEntry`, and `lastKnownImpliedMove`. |
| `mwd.trading.strategy.OneSigmaDownsideMeanReversionStrategy` | Reads `impliedMoveForNewEntry` and `lastKnownImpliedMove`. |
| `mwd.trading.strategy.OneSigmaUpsideMeanReversionStrategy` | Reads `impliedMoveForNewEntry` and `lastKnownImpliedMove`. |
| `mwd.trading.ui.BlackboardMonitor` | Renders the mirrored `Stock` fields. |

## 2. Execution Path

### Receiver startup (main thread)

1. **Initiating Component:** `Main.main(String[])`
   **Method Invocation:** `new OptionsIndicatorStore(Set.copyOf(marketDataSymbols), config.getOptionsProxyFrameMaxAgeMs())`
   **Receiving Component:** `OptionsIndicatorStore`

2. **Initiating Component:** `Main.main(String[])`
   **Method Invocation:** `new OptionsIndicatorFrameReceiver(optionsIndicatorStore, config.getOptionsProxyBindHost(), config.getOptionsProxyUdpPort(), frame -> mirrorFrameForMonitor(blackboard, frame))`, guarded by `config.isOptionsProxyEnabled()`
   **Receiving Component:** `OptionsIndicatorFrameReceiver`

3. **Initiating Component:** `Main.main(String[])`
   **Method Invocation:** `optionsIndicatorFrameReceiver.start()`
   **Receiving Component:** `OptionsIndicatorFrameReceiver`

4. **Initiating Component:** `OptionsIndicatorFrameReceiver.start()`
   **Method Invocation:** `new DatagramSocket(null)`, `setReuseAddress(true)`, `bind(new InetSocketAddress(bindHost, bindPort))`, then `new Thread(this::receiveLoop, "Options-Proxy-UDP-Receiver")` with `setDaemon(true)` and `start()`
   **Receiving Component:** `DatagramSocket`, `Thread`

### Frame reception (Options-Proxy-UDP-Receiver thread)

5. **Initiating Component:** `OptionsIndicatorFrameReceiver.receiveLoop()`
   **Method Invocation:** `openSocket.receive(DatagramPacket)` into a reused 8192-byte buffer
   **Receiving Component:** `DatagramSocket`

6. **Initiating Component:** `OptionsIndicatorFrameReceiver.receiveLoop()`
   **Method Invocation:** `System.currentTimeMillis()`, `receivedDatagrams.incrementAndGet()`, assignment of `lastDatagramAtUnixMs`
   **Receiving Component:** `OptionsIndicatorFrameReceiver`

7. **Initiating Component:** `OptionsIndicatorFrameReceiver.receiveLoop()`
   **Method Invocation:** `IndicatorFrame.parseFrom(ByteBuffer.wrap(packet.getData(), packet.getOffset(), packet.getLength()))`; an `InvalidProtocolBufferException` increments `malformedDatagrams` and continues
   **Receiving Component:** `IndicatorFrame`

8. **Initiating Component:** `OptionsIndicatorFrameReceiver.receiveLoop()`
   **Method Invocation:** `store.accept(IndicatorFrame frame, long receivedAtUnixMs)`
   **Receiving Component:** `OptionsIndicatorStore`

### Store validation (same thread)

9. **Initiating Component:** `OptionsIndicatorStore.accept(IndicatorFrame, long)`
   **Method Invocation:** `frame.getTicker()` normalized and tested against `knownTickers`; `parseTradingDate(frame.getTradingDate())` requiring exactly 10 characters; `frame.getEmittedAtUnixMs()` > 0 and not more than `frameMaxAgeMs` ahead of reception; `frame.getSequence()` > 0
   **Receiving Component:** `OptionsIndicatorStore.reject(String)` on any failure

10. **Initiating Component:** `OptionsIndicatorStore.accept(IndicatorFrame, long)`
    **Method Invocation:** `frame.getStaticDailyImpliedMoveValid()` / `getStaticDailyImpliedMove()` and `frame.getSpyGammaFlipValid()` / `getSpyGammaFlip()` tested with `isPositiveFinite(double)` when flagged valid
    **Receiving Component:** `OptionsIndicatorStore`

11. **Initiating Component:** `OptionsIndicatorStore.accept(IndicatorFrame, long)`
    **Method Invocation:** compares `sequence` against the previous `TickerState.sequence()`; a non-advancing sequence is rejected unless the previous reading is already older than `frameMaxAgeMs`
    **Receiving Component:** `ConcurrentMap<String, TickerState>`

12. **Initiating Component:** `OptionsIndicatorStore.accept(IndicatorFrame, long)`
    **Method Invocation:** `stateByTicker.put(ticker, new TickerState(...))` and `gammaFlip.set(new GammaFlip(...))`, then `acceptedFrames.incrementAndGet()` and `return true`
    **Receiving Component:** `ConcurrentMap`, `AtomicReference<GammaFlip>`

13. **Initiating Component:** `OptionsIndicatorFrameReceiver.receiveLoop()`
    **Method Invocation:** on `true` and a non-null listener, `acceptedFrameListener.onAccepted(frame)`; a `RuntimeException` from the listener is caught and logged
    **Receiving Component:** the `Main` lambda

14. **Initiating Component:** `Main.mirrorFrameForMonitor(Blackboard, IndicatorFrame)`
    **Method Invocation:** `blackboard.getStock(frame.getTicker()).setDailyImpliedMove(frame.getStaticDailyImpliedMove())` when the implied-move flag is set; `blackboard.getStock("SPY").setGammaFlip(frame.getSpyGammaFlip())` when the gamma-flip flag is set
    **Receiving Component:** `Blackboard`, `Stock`

### Strategy consumption (strategy threads)

15. **Initiating Component:** a concrete strategy's `isEntryConditionMet(Stock)`
    **Method Invocation:** `optionsIndicatorStore.impliedMoveForNewEntry(String ticker, LocalDate tradingDate, long nowUnixMs)`
    **Receiving Component:** `OptionsIndicatorStore`

16. **Initiating Component:** `TwoSigmaDownsideMeanReversionStrategy.isEntryConditionMet(Stock)`
    **Method Invocation:** `optionsIndicatorStore.gammaFlipForNewEntry(LocalDate tradingDate, long nowUnixMs)`, then compares `blackboard.getStock("SPY").getLastPrice()` against the returned level after checking `marketDataFreshness.isFresh("SPY", MarketDataInput.LAST_PRICE)`
    **Receiving Component:** `OptionsIndicatorStore`, `Blackboard`, `MarketDataInputStore`

17. **Initiating Component:** a concrete strategy's `calculateEntryPrice(Stock)` and `calculateSliceIntents(Stock, double)`
    **Method Invocation:** `optionsIndicatorStore.lastKnownImpliedMove(String ticker)`; an empty result yields `Double.NaN` or an empty intent list
    **Receiving Component:** `OptionsIndicatorStore`

18. **Initiating Component:** a concrete strategy's `manageOpenPosition(Stock)`
    **Method Invocation:** `optionsIndicatorStore.lastKnownImpliedMove(String ticker)`, which ignores freshness so an open position can still be managed
    **Receiving Component:** `OptionsIndicatorStore`

### Shutdown

19. **Initiating Component:** `Trading-Engine-Shutdown` hook thread
    **Method Invocation:** `receiverForShutdown.stop()` → `socket.close()` then `thread.join(2000)`
    **Receiving Component:** `OptionsIndicatorFrameReceiver`, `DatagramSocket`

## 3. Data Payloads and State Handoffs

### Objects passed

- Wire payload: a serialized `options_proxy.IndicatorFrame` protobuf message, produced by the Python proxy and capped at `UDP_MTU` there; the receiver allocates an 8192-byte buffer so an oversized datagram fails parsing instead of being truncated into something parseable.
- `IndicatorFrame` — the immutable decoded message, passed to `OptionsIndicatorStore.accept` and then, if accepted, to `AcceptedFrameListener.onAccepted`.
- `long receivedAtUnixMs` — captured with `System.currentTimeMillis()` in the receive loop and stored on both `TickerState` and `GammaFlip`; it is the basis of every freshness test.
- `OptionsIndicatorStore.TickerState` — private record `(sequence, emittedAtUnixMs, receivedAtUnixMs, tradingDate, impliedMoveValid, lastValidImpliedMove)`; the retained `ImpliedMove` survives frames that carry an invalid value.
- `OptionsIndicatorStore.GammaFlip` — single shared value replaced by every accepted frame regardless of ticker.
- `OptionalDouble` — the read-side payload returned to strategies by `impliedMoveForNewEntry`, `gammaFlipForNewEntry`, and `lastKnownImpliedMove`.
- `Stock.dailyImpliedMove` / `Stock.gammaFlip` — display-only copies written by the listener; they carry no session or validity information and are not read by strategies.

### Thread handoffs

| Handoff | Detail |
| --- | --- |
| Options-proxy process → trading engine | Cross-process UDP datagram from the Python `UdpBroadcaster` to the bound `DatagramSocket`. |
| Main thread → `Options-Proxy-UDP-Receiver` | `start()` creates and starts the daemon receive thread. |
| `Options-Proxy-UDP-Receiver` → itself | Parsing, `store.accept`, and `onAccepted` all run inline in the receive loop; a slow listener delays the next `receive`. |
| `Options-Proxy-UDP-Receiver` → `<Strategy>-Thread` | Strategy reads cross the thread boundary through `ConcurrentHashMap<String, TickerState>` and `AtomicReference<GammaFlip>`, both holding immutable records. |
| `Options-Proxy-UDP-Receiver` → Swing EDT | The listener writes `volatile` `Stock` fields; `BlackboardMonitor` reads them on the event-dispatch thread. |
| Shutdown hook thread → `Options-Proxy-UDP-Receiver` | `stop()` closes the socket to break the blocking `receive()` and then joins for up to 2000 ms; `start()` and `stop()` are `synchronized` on the receiver. |

Counters (`receivedDatagrams`, `malformedDatagrams`, `acceptedFrames`,
`rejectedFrames`) are `AtomicLong`; `lastDatagramAtUnixMs`, `socket`,
`receiverThread`, `running`, and `lastRejectionReason` are `volatile`.
