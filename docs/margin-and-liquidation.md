# Margin regime and distance from liquidation

Design note. Records decisions and the reasoning behind them, and marks the
settings still to be chosen. Not extracted from the source — that is what
`.claude/rules/**` is for.

## The goal

Avoid **IBKR liquidating the account**. Not a margin call: IBKR does not issue
one in the retail sense. There is no notice and no grace period to wire funds —
positions are closed in real time, at whatever the market is doing at that moment.

So the engine has two jobs. Keep a distance from that trigger, and never let its
own trading be what closes it.

## Why the regime decides the design

| | Reg-T | Portfolio Margin |
|---|---|---|
| Liquidation when | equity < maintenance requirement | `ExcessLiquidity` < 0 |
| Requirement is | a fixed fraction of position value | a TIMS figure IBKR recomputes |
| Which moves when | prices move | prices move **or** IBKR raises stress factors |
| Can the engine compute its own distance? | Yes, deterministically | No — the model belongs to IBKR |

That asymmetry is the whole design. Under Reg-T the engine can predict where it
stands, so a stress test is meaningful: a 10% adverse move produces a requirement
it can calculate. Under Portfolio Margin it can only observe, because TIMS applies
simulated valuation sweeps (typically ±15%) and IBKR raises the stress factors
during broad volatility — **the requirement can rise with position prices flat**.

Consequences the code has to respect:

- A Reg-T rate table is a *rule*. 50% initial on long equity is federal regulation
  and does not move.
- A Portfolio Margin rate table is a *snapshot of a model*, taken on whatever day
  it was collected. It understates the requirement precisely on the day it matters.
- Under Portfolio Margin, per-symbol requirements are only obtainable from what-if
  orders, which this engine deliberately does not send. That is acceptable:
  per-symbol precision does not prevent a liquidation. Portfolio-level
  `ExcessLiquidity` is what the trigger reads, so that is what the engine gates on.

## Decisions taken

**The regime must be declared.** `MARGIN_METHODOLOGY` is `REG_T` or `PORTFOLIO`,
and the engine refuses to start without it. No default and no inference. A default
would make a missing key indistinguishable from a deliberate choice, and getting
it wrong toward `PORTFOLIO` is silent over-leverage — sizing against roughly a
third while IBKR charges a half.

**An unconfirmed regime does not trade.** Not a safer assumption, not a warning —
no trading. The reasoning behind assuming the heavier regime still holds for any
figure the engine must estimate, but the regime itself is not estimated: it is
declared, confirmed, or refused. See the section below.

**No automated liquidation.** Every position already carries a stop and a time
exit as resting OCA orders. A de-risking path would have to cancel those brackets
and send market orders, which means the engine fighting its own exits. The engine
refuses new risk and escalates; a human closes.

## The four layers

1. **Know the regime.** Config declares it; IBKR's own figures confirm it.
   Everything below keys off this.
2. **Bound what can be taken on.** Position count, per-ticker, per-sector, and
   gross exposure. These bound the worst book the engine can construct.
3. **Reserve at entry.** Not "can I afford this" but "after this, am I still far
   enough from the trigger". This is the buffer.
4. **Watch continuously.** Under Portfolio Margin the distance shrinks with no
   trade and no price move, so a check that only runs at entry is insufficient.
   Warn, then stop new entries, then escalate.

Layers 2 and 3 stop the engine creating the problem. Layer 4 notices when the
market creates it.

## Where the code stands

| Layer | State |
|---|---|
| 1 — declare the regime | **done**: `MARGIN_METHODOLOGY` required, no default |
| 1 — confirm it against IBKR | not built; decided, and blocked on knowing which tags arrive |
| 2 — position count, per-ticker, per-sector | **done**: `MAX_ACTIVE_POSITIONS`, `ConcentrationLimits` |
| 2 — gross exposure ceiling | not built (currently implied by `3 × ticker%`) |
| 3 — reserve at entry | not built |
| 4 — continuous watch | not built |

Note on where the caps already put you: with `3 × 30%`, gross exposure is bounded
at 90% of net liquidation. Below 1:1, a Reg-T maintenance breach is arithmetically
unreachable — equity falls with the loss, the requirement falls with it, and the
gap never closes. The buffer work matters for raising those caps, and for
Portfolio Margin, where the arithmetic does not transfer.

## Account values the engine reads

`onUpdateAccountValue` matches IBKR tags by exact string, so a name IBKR never
sends is indistinguishable from a value that never arrives — the field stays at
zero for the life of the process. `ExcessLiquidity` was read as `ExcessMargin`,
which is not an IBKR tag, and was therefore always `0.00`. It cost nothing while
the figure only drew a label. It would have cost a great deal as the sensor for
layer 4.

Today only `NetLiquidation` and `AvailableFunds` affect a decision. The rest draw
labels on the monitor.

The authoritative list is `com.ib.controller.AccountSummaryTag` in the TWS API
source. Three entries there are worth more than anything the engine would compute
for itself:

| Tag | IBKR's own comment |
|---|---|
| `HighestSeverity` | "A measure of how close the account is to liquidation" |
| `Leverage` | `GrossPositionValue / NetLiquidation` |
| `GrossPositionValue` | "The sum of the absolute value of all stock and equity option positions" |

`HighestSeverity` answers the question this note exists to ask, directly, from the
party that performs the liquidation. `Leverage` and `GrossPositionValue` are the
gross-exposure figures layer 2 would otherwise derive from `ConcentrationLimits`.
Prefer IBKR's numbers to our own wherever both exist.

The enum also groups margin into three sets: current (`InitMarginReq`,
`MaintMarginReq`, `AvailableFunds`, `ExcessLiquidity`), overnight (the `Full*`
prefix), and look-ahead (`LookAhead*`, with `LookAheadNextChange` giving the time
the new figures take effect).

**Caveat.** That enum is for `reqAccountSummary`. This engine subscribes with
`reqAccountUpdates`, which delivers `updateAccountValue`, and the two sets overlap
without being identical. Which tags actually arrive is a question only a live
session answers, so `AccountEventHandler` now logs each unread tag once at DEBUG.
One run enumerates what is really available before anything is built on top of it.

Nothing beyond `ExcessLiquidity` is captured yet. Fields arrive with their
consumer, not ahead of it.

`LookAheadNextChange` deserves a note of its own: IBKR publishes when the
requirement will next change, normally the intraday-to-overnight step at the
close. Under Portfolio Margin that is the difference between comfortable at 15:30
and liquidated at 15:50. The time exits already close positions before the bell,
which covers most of it, but reading the look-ahead figures is the instrument that
actually measures it.

## Still to decide

All configuration, none of it hardcoded:

| Setting | Value | Note |
|---|---|---|
| Gross exposure ceiling | _tbd_ | Total notional as a percent of net liquidation |
| Entry reserve | _tbd_ | `ExcessLiquidity` that must survive the trade |
| Stress move | _tbd_ | Adverse percentage the book is tested against at entry |
| Watchdog: warn | _tbd_ | Cushion or `ExcessLiquidity` level |
| Watchdog: block new entries | _tbd_ | |
| Watchdog: escalate | _tbd_ | |
| Portfolio-margin stress multiplier | _tbd_ | Applied to table rates, since TIMS rises on its own |
| Whether any of the above differ per regime | _tbd_ | They probably should |

## Unconfirmed regime: do not trade, and say so

**Decided.** If the engine cannot confirm the declared regime against IBKR's own
figures, it does not trade and logs an error. No fallback, no assumption, no
warning-and-continue.

That splits into two mechanisms, because the two checks happen at different times.

**Declaration is checked before the process starts.** `MARGIN_METHODOLOGY` absent
or unrecognised throws from the `EnvPropConfig` constructor, before `Main` builds
anything or opens a socket. This is literally "does not start", and it is built.

**Confirmation cannot be, and must not pretend to be.** The confirming figures
arrive from `reqAccountUpdates`, which is issued in `onManagedAccounts` after the
API handshake — by which time the process is up and the strategy threads are
already polling. Refusing to boot is not available; the information does not exist
yet at boot.

The faithful form is therefore: **`TradingGate` never reaches `READY`.** Every
entry is gated on `allowsNewEntries()`, so a gate that stays out of `READY` is a
process that runs, connects, reconciles, streams market data, and never sends an
order. `ReconciliationManager` already works exactly this way, and the regime
check is the same shape — a post-connect condition that must pass before trading
is permitted.

Three ways confirmation can fail, all with the same outcome:

| Case | Resolution |
|---|---|
| The figures never arrive within a timeout | `requireManualIntervention`, naming the tags that did not appear |
| They arrive and contradict the declared regime | `requireManualIntervention`, reporting both the declared and the observed |
| They arrive but cannot settle the question | `requireManualIntervention`. Silence is not confirmation |

The third case is why this is not built yet. Confirmation compares what IBKR
charges against `RegTMargin` — a requirement materially below Reg-T means the
account is on Portfolio Margin — and whether `RegTMargin` reaches this engine at
all is unresolved. It is an `AccountSummaryTag`, and the engine subscribes with
`reqAccountUpdates`. The DEBUG logging of unread tags exists to settle that in one
live session. Building the comparison before then would be writing a check against
a tag that may never arrive, which is the mistake `ExcessMargin` already made once.

**Sequencing:** run one session with DEBUG on, read which tags arrive, then build
the confirmation against tags known to exist.
