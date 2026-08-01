---
paths:
  - "trading-engine/**/*"
---

# Package `mwd.trading.ui`

Source: `trading-engine/trading-engine/src/main/java/mwd/trading/ui/BlackboardMonitor.java`

---

## `BlackboardMonitor`

`public class BlackboardMonitor extends JFrame`

### 1. Class/Interface Responsibilities

Swing `JFrame` that renders a 32-column `JTable` of per-`Stock` values plus an account panel, refreshed on a background daemon thread from `Blackboard`, with column-view buttons, a LIVE arming control, a cell-flash renderer backed by a `ConcurrentHashMap` of update timestamps, a header fade timer, and a generated-tone alert thread. The LIVE button is shown only for a LIVE process and requires an explicit warning-dialog confirmation after reconciliation reaches `READY`.

### 2. Injected Dependencies

Constructor: `public BlackboardMonitor(Blackboard blackboard, UniverseReference universeReference, TradingGate tradingGate, boolean liveTrading, String liveAccount)`

| Parameter | Exact type |
| --- | --- |
| `blackboard` | `mwd.trading.state.Blackboard` |
| `universeReference` | `mwd.trading.risk.UniverseReference` — the source of the `L-Margin` and `S-Margin` columns |
| `tradingGate` | `mwd.trading.lifecycle.TradingGate` — LIVE readiness and process-local arming authority |
| `liveTrading` | `boolean` — whether to expose the arming controls |
| `liveAccount` | `String` — required LIVE account shown in the confirmation dialog |

Constructed inside the constructor: `tableModel` (`DefaultTableModel`), `monitorTable` (`JTable`), `headerPanel` (`JPanel`), `haltStatusLabel`/`updateRequiredLabel`/`marketTimeLabel`/`liveTradingStatusLabel` (`JLabel`), `view1Button`/`view2Button`/`viewAllButton`/`liveTradingArmButton` (`JButton`), `systemStatusFadeTimer` (`javax.swing.Timer`, 50 ms), an unnamed repaint `Timer` (33 ms), and the data-refresh daemon thread.

### 3. Method Signatures

Nested types:
- `private enum ViewState { VIEW_1, VIEW_2, VIEW_ALL }`
- `private class FlashCellRenderer extends DefaultTableCellRenderer`

```java
public BlackboardMonitor(Blackboard blackboard, UniverseReference universeReference, TradingGate tradingGate, boolean liveTrading, String liveAccount)

private void confirmAndArmLiveTrading()
private void updateLiveTradingControls()
private void updateView(ViewState targetViewState)
private void applyColumnVisibility()
private void updateButtonStyles()
private void addAccountLabel(JPanel panel, String title, JLabel valueLabel)
private void performSystemStatusFade()
private void startSiren()
private void startDataRefreshThread()
private void updateDashboardData()
private void updatePnLLabel(JLabel label, double value)
private void updateTableCellIfChanged(int rowIndex, int columnIndex, Object newValue)
```

`FlashCellRenderer`:

```java
@Override public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int rowIndex, int columnIndex)
private Color interpolateColor(Color startColor, Color endColor, float ratio)
```

Fields include `private final int refreshRateMilliseconds = 250`, `private static final long serialVersionUID = 1L`, `private static final int AUDIO_SAMPLE_RATE = 44100`, `private final String[] tableColumns` (32 entries), `private final Set<Integer> view1Indices`, `private final Set<Integer> view2Indices`, `private ViewState currentViewState`, `private volatile float currentFadeAlpha`, `private boolean isFadingUp`, `private volatile boolean isSirenStarted`, `private int hoveredRowIndex`.

### 4. Global State Interactions

**Concurrent collections**

| Field | Declared type |
| --- | --- |
| `lastUpdateTimestampMap` | `Map<String, Long>` = `new ConcurrentHashMap<>()` |

| Method | Interaction |
| --- | --- |
| `updateTableCellIfChanged(int, int, Object)` | Mutates `lastUpdateTimestampMap` (`put`, keyed by `ticker + ":" + columnIndex`) |
| `FlashCellRenderer.getTableCellRendererComponent(...)` | Reads `lastUpdateTimestampMap` (`get`) |

**Centralized state objects (`Blackboard`)**

| Method | Interaction |
| --- | --- |
| `performSystemStatusFade()` | Reads `blackboard.getSystemHalted()` and `blackboard.getSystemUpdateRequired()` |
| `updateDashboardData()` | Reads `blackboard.getSystemHalted()`, `blackboard.getSystemUpdateRequired()`, `blackboard.getAccount()` (net liquidation, total cash, settled cash, buying power, available funds, excess margin, realized PnL, unrealized PnL, cushion), `blackboard.getMarketTime()`, and the stock collection via `blackboard.forEachStock(stockList::add)`; reads per-`Stock` price, position, PnL, active `BracketOrder` slices, implied move, gamma flip, VWAP, volumes, ATRs, SMAs, RSI, and next earnings date; the two margin columns read `universeReference.marginRate(ticker, isLong)`, which is configuration rather than a measured figure |
| `startDataRefreshThread()` | Starts a daemon thread that calls `updateDashboardData()` every 250 ms |

`confirmAndArmLiveTrading()` reads `tradingGate.getMode()`, asks the operator to confirm the named LIVE account, and calls `tradingGate.armLiveTrading()` only after a `YES` response. `updateLiveTradingControls()` reads the gate's arming and lifecycle state to render/enable the button. This mutates only the process-local LIVE entry-arm flag; it does not mutate broker, account, stock, or journal state.

All `Blackboard`, `Stock`, `Account`, and `BracketOrder` interactions are read-only.

**Threads**

- One unnamed daemon thread from `startDataRefreshThread()` running the 250 ms refresh loop, marshalling updates onto the EDT via `SwingUtilities.invokeLater`.
- One unnamed daemon thread from `startSiren()` writing to a `SourceDataLine` in an unbounded loop, started once when `isSirenStarted` is false and either `Blackboard` alert flag is set.
- Two Swing `Timer` instances (fade at 50 ms, repaint at 33 ms).
