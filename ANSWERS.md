# Trading Engine Configuration and Policy

This is the final agreed configuration and policy source. Implementation proceeds only when separately authorized and must follow the dependency-ordered milestones in `PLAN.md`.

## Current and future deployment

- The current deployment phase is PAPER only.
- PAPER and LIVE are expected to run simultaneously in a later phase.
- Both will run on the same Windows 11 computer.
- When simultaneous operation is introduced, PAPER and LIVE must retain separate broker sessions/endpoints as required, account selections, API client IDs, trading-state journals, logs, proxy receiver configuration, and LIVE arming state.
- The exact simultaneous PAPER/LIVE topology can be selected when that deployment phase begins. It is not required for the current PAPER phase.

## Configuration

```properties
MAX_GROSS_LEVERAGE=2.0
MAX_INITIAL_MARGIN_UTILIZATION_PCT=50

MIN_EXCESS_LIQUIDITY_USD=90000
MIN_MARGIN_CUSHION_PCT=30
MIN_LOOKAHEAD_AVAILABLE_FUNDS_USD=150000
MIN_LOOKAHEAD_EXCESS_LIQUIDITY_USD=90000

MAX_PER_TRADE_STOP_RISK_PCT=1.0
MAX_AGGREGATE_STOP_RISK_PCT=2.0

MAX_DAILY_REALIZED_LOSS_PCT=3.0
MAX_DAILY_UNREALIZED_LOSS_PCT=3.0
MAX_DAILY_COMBINED_LOSS_PCT=3.0
MAX_INTRADAY_DRAWDOWN_PCT=3.0

DAILY_LOSS_BREACH_ACTION=BLOCK_CANCEL_AND_FLATTEN
ACTIVE_MARGIN_BREACH_ACTION=BLOCK_CANCEL_AND_FLATTEN
GROSS_LEVERAGE_BREACH_ACTION=BLOCK_NEW_ENTRIES
STOP_RISK_BREACH_ACTION=BLOCK_NEW_ENTRIES
CONCENTRATION_BREACH_ACTION=BLOCK_NEW_ENTRIES

MAX_TICKER_EXPOSURE_PCT=75
MAX_SECTOR_EXPOSURE_PCT=100
MAX_ACTIVE_POSITIONS=5

EMERGENCY_CLOSEOUT_BEFORE_CLOSE_MIN=10
EMERGENCY_MARKET_FALLBACK_BEFORE_CLOSE_MIN=6
HARD_FLAT_CONFIRMATION_BEFORE_CLOSE_MIN=5

EMERGENCY_CLOSEOUT_ORDER_TYPE=MARKETABLE_LIMIT
EMERGENCY_LIMIT_OFFSET_BPS=10
EMERGENCY_REPRICE_INTERVAL_SECONDS=10
EMERGENCY_QUOTE_MAX_AGE_MS=5000
EMERGENCY_UNFILLED_ACTION=MARKET

IBKR_HOST=127.0.0.1
IBKR_PORT=7496
IBKR_PRODUCTION_HOST=TWS
IBKR_TWS_VERSION=10.49.1c
IBKR_API_VERSION=10.39.1
UNCERTIFIED_TWS_VERSION_ACTION=WARN_ONLY

MARGIN_METHODOLOGY=REG_T

LIVE_TRADING_ARMING_MODE=EXPLICIT_CONFIRMATION_EACH_START
```

The TWS and API versions are operational information, not startup certification gates. The engine connects to the configured TWS endpoint and proceeds through its ordinary account, contract, reconciliation, data, session, and risk-readiness checks.

## Risk-calculation rules

- All percentage limits use the current application baseline net liquidation as their denominator.
- The first fresh, valid brokerage account snapshot received after application startup becomes the baseline.
- A same-day application restart intentionally establishes a new baseline from the first fresh snapshot after that restart. The loss budget is therefore scoped to the current application run.
- If the application remains running across the New York trading-date rollover, the old baseline is invalidated and the first fresh snapshot for the new date becomes the new baseline.
- New York date handling follows `America/New_York` and therefore EST/EDT automatically.
- Drawdown enforcement is based on engine-owned P&L only, not changes in whole-account net liquidation caused by manual or unmanaged positions.
- Engine-owned drawdown is the loss represented by this engine's realized plus unrealized P&L relative to the current application baseline.
- Realized, unrealized, combined-loss, and drawdown enforcement covers engine-owned positions and trades only.
- Realized P&L is calculated from this engine's executions.
- Commissions are excluded from the engine-owned realized-P&L calculation.
- The IBKR account-level `RealizedPnL` tag is not used for daily-loss enforcement.
- Combined loss is engine-owned realized loss plus engine-owned unrealized loss.
- All account positions, including manual or unmanaged positions, count toward gross, ticker, sector, initial-margin, maintenance-margin, excess-liquidity, cushion, and look-ahead exposure checks.
- Aggregate and per-trade stop risk apply to engine-owned trades because the engine cannot know the intended stop for unmanaged positions.

## Breach actions

### Daily-loss breach

1. Block new entries.
2. Cancel unfilled engine entry orders.
3. Flatten every engine-owned open position.
4. Preserve unmanaged or manually owned positions.
5. Require broker confirmation that engine-owned positions are flat.
6. Escalate to manual intervention and a critical alert if flattening cannot be confirmed.

### Active-margin breach

An active-margin breach is a violation of initial-margin utilization, minimum excess liquidity, minimum margin cushion, or either look-ahead margin threshold.

1. Block new entries.
2. Cancel unfilled engine entry orders.
3. Flatten every engine-owned open position.
4. Preserve unmanaged or manually owned positions.
5. Require broker confirmation that engine-owned positions are flat.
6. Escalate to manual intervention and a critical alert if flattening cannot be confirmed.

### Admission-only breaches

Gross leverage, per-trade stop risk, aggregate stop risk, ticker exposure, sector exposure, and active-position-count breaches block new entries. They do not initiate flattening.

## Strategy and global session behavior

- Strategies own their entry rules, holding-period rules, and normal time exits.
- There is no configurable 60-minute global entry cutoff.
- There is no global normal-exit time that replaces a strategy's own time exit.
- The global closeout system is a final safety mechanism for positions a strategy failed to close because of a strategy or lifecycle defect.
- When emergency closeout begins ten minutes before the session close, the closeout coordinator blocks all new entries before cancelling or flattening anything. This is closeout-state behavior, not a normal strategy entry rule.
- The engine may start and initialize at any time during a trading session.
- It loads enough historical data to construct every required indicator.
- It qualifies contracts, obtains fresh account state, and reconciles broker state.
- Trading begins only after every readiness requirement passes.
- Starting late in the session is permitted. Strategy entry rules and the global emergency-closeout state determine whether an entry is still allowed.
- Early-close sessions move all emergency closeout and flat-confirmation deadlines forward automatically.
- Future LIVE trading must be deliberately armed through the UI after every application start.

## Emergency closeout behavior

- Emergency closeout begins ten minutes before the session closes.
- The closeout coordinator blocks all new entries when it begins.
- It cancels unfilled engine entry orders and handles any fill received while cancellation is pending.
- Initial closeout orders are marketable-limit orders.
- For a long position, the SELL limit is the bid minus 10 basis points.
- For a short position, the BUY limit is the ask plus 10 basis points.
- A bid or ask older than five seconds is stale for emergency marketable-limit construction or repricing.
- Missing or stale quotes trigger manual intervention and a critical alert. They do not independently trigger an early MARKET order.
- An existing order must be confirmed cancelled before a replacement is submitted.
- Unfilled marketable-limit orders are repriced every ten seconds.
- Six minutes before close, remaining emergency orders fall back to MARKET orders according to the scheduled fallback policy.
- A rejected or unresolved MARKET order triggers manual intervention and a critical alert.
- IBKR must confirm all engine-owned positions flat and relevant working orders resolved no later than five minutes before close.
- Unmanaged or manual positions are preserved.

## TWS deployment decisions

Confirmed for the current environment:

- Host computer: the same Windows 11 computer as the trading engine.
- Network endpoint: `127.0.0.1:7496`.
- Host application: TWS.
- TWS application version: `10.49.1c`.
- TWS API library version: `10.39.1`.
- The exact TWS build is informational. An uncertified version produces a warning and does not automatically block trading.
- The engine assumes TWS is already authenticated and its configured API socket is reachable. It connects and then relies on application-level readiness checks.

Runtime-local values such as the selected account and unique API client ID do not need to be embedded in this policy document. They must be supplied correctly to the running deployment. Distinct values will be required when PAPER and LIVE run simultaneously.

Other TWS operating choices, such as automatic restart, weekly authentication handling, order resubmission, message pacing behavior, trusted IPs, and precautionary settings, may be documented during the applicable operational milestone. They are not prerequisites for writing the connection logic.

## Implementation status

- M0 (strategy activation and deployment firewall) is implemented in the current worktree and its automated Java tests pass; controlled PAPER verification is still pending.
- M1 through M12 are not implemented by the M0 changes.
- A setting in this document is not evidence that the application enforces it unless the corresponding milestone is implemented, tested, documented, and PAPER-verified.
- Each future milestone must implement, test in PAPER, document, and independently verify its applicable rules before the next milestone begins.
- `JavaClient` must never be edited.
