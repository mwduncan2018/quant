---
trigger: always_on
---

# Project: Java IBKR TWS API Trading Bot

## Background
- Environment: Google Cloud (Northern Virginia).
- Capital: $160,000 (SCHD), $40,000 (USD).
- Strategy: Intraday only (all positions closed by 4 PM ET).
- Execution: Margin utilized for trades.

## Data Sources
- IBKR TWS API (Primary execution/account data).
- Polygon (Tick data).
- Unusual Whales (API integrations).
- Unusual Whales Query tool documentation.

## Stock Universe
- The bot tracks 75 pre-defined stocks appropriate for these strategies.
- Constraint: A stock can only be used for one strategy at a time.
- Constraint: A stock cannot be reused for the *same* strategy twice in one day.

## Strategy Roadmap
- Current Live Strategy: StrategyC (Long mean reversion based on static daily implied moves).
- Future/Considered Strategies:
    1. Short mean reversion (static daily implied moves).
    2. Long/Short mean reversion (dynamic implied moves/VWAP).
    3. Long/Short mean reversion (put/call walls).
    4. Long/Short momentum (break of walls).
    5. Long/Short trend following.
    6. Pinning to option strikes.
    7. Using Charms / Vanna.