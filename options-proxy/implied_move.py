"""Static Daily Implied Move selection and calculation."""

from __future__ import annotations

import asyncio
import math
from datetime import UTC, datetime
from zoneinfo import ZoneInfo

from config import Settings, cfg
from market_calendar import MarketCalendar
from massive_client import MassiveApiError, MassiveRestClient
from models import OptionContract, StaticDailyImpliedMove

EASTERN = ZoneInfo("America/New_York")


class ImpliedMoveError(RuntimeError):
    """Raised when the previous session cannot produce a trustworthy value."""


def select_atm_pair(
    contracts: list[OptionContract],
    underlying_close: float,
) -> tuple[OptionContract, OptionContract]:
    """Return the nearest standard strike having both a call and a put."""

    by_strike: dict[float, dict[str, OptionContract]] = {}
    for contract in contracts:
        if not contract.is_standard or contract.contract_type not in {"call", "put"}:
            continue
        by_strike.setdefault(contract.strike_price, {})[contract.contract_type] = contract

    paired_strikes = [
        strike
        for strike, pair in by_strike.items()
        if "call" in pair and "put" in pair
    ]
    if not paired_strikes:
        raise ImpliedMoveError("No standard call/put pair was available")

    # A tie selects the lower strike deterministically.
    strike = min(paired_strikes, key=lambda value: (abs(value - underlying_close), value))
    pair = by_strike[strike]
    return pair["call"], pair["put"]


class StaticDailyImpliedMoveCalculator:
    """Calculate one frozen move from prior-session Massive data."""

    def __init__(
        self,
        client: MassiveRestClient,
        calendar: MarketCalendar,
        settings: Settings = cfg,
    ) -> None:
        self.client = client
        self.calendar = calendar
        self.settings = settings

    async def calculate(
        self,
        ticker: str,
        *,
        now: datetime | None = None,
    ) -> StaticDailyImpliedMove:
        ticker = ticker.strip().upper()
        if not ticker:
            raise ValueError("ticker must not be blank")

        now = now or datetime.now(UTC)
        eastern_now = now.astimezone(EASTERN)

        try:
            underlying_bar = await self.client.fetch_previous_bar(ticker)
        except MassiveApiError as exc:
            raise ImpliedMoveError(str(exc)) from exc

        trading_date = self.calendar.next_session_on_or_after(eastern_now.date())
        if underlying_bar.session_date >= trading_date:
            trading_date = self.calendar.next_session_after(underlying_bar.session_date)

        nominal_friday = self.calendar.nominal_friday(trading_date)
        expiration_date = self.calendar.previous_session_on_or_before(nominal_friday)
        if expiration_date < trading_date:
            raise ImpliedMoveError(
                f"No expiration session remains for trading date {trading_date}"
            )

        strike_window = self.settings.atm_strike_window_pct
        try:
            contracts = await self.client.fetch_option_contracts(
                underlying_ticker=ticker,
                expiration_date=expiration_date.isoformat(),
                as_of=underlying_bar.session_date.isoformat(),
                strike_gte=underlying_bar.close * (1.0 - strike_window),
                strike_lte=underlying_bar.close * (1.0 + strike_window),
            )
        except MassiveApiError as exc:
            raise ImpliedMoveError(str(exc)) from exc

        call, put = select_atm_pair(contracts, underlying_bar.close)
        try:
            call_bar, put_bar = await asyncio.gather(
                self.client.fetch_previous_bar(call.ticker),
                self.client.fetch_previous_bar(put.ticker),
            )
        except MassiveApiError as exc:
            raise ImpliedMoveError(str(exc)) from exc

        for label, option_bar in (("call", call_bar), ("put", put_bar)):
            if option_bar.session_date != underlying_bar.session_date:
                raise ImpliedMoveError(
                    f"ATM {label} close is from {option_bar.session_date}, not "
                    f"{underlying_bar.session_date}"
                )

        sessions = self.calendar.sessions_between(
            underlying_bar.session_date,
            expiration_date,
        )
        remaining_sessions = len(sessions)
        if remaining_sessions < 1 or trading_date not in sessions:
            raise ImpliedMoveError(
                f"Invalid trading-session range from {underlying_bar.session_date} "
                f"through {expiration_date}"
            )

        expiration_move = self.settings.implied_move_multiplier * (
            call_bar.close + put_bar.close
        )
        daily_move = expiration_move * math.sqrt(1.0 / remaining_sessions)

        return StaticDailyImpliedMove(
            ticker=ticker,
            trading_date=trading_date,
            source_session_date=underlying_bar.session_date,
            expiration_date=expiration_date,
            underlying_close=underlying_bar.close,
            atm_strike=call.strike_price,
            call_ticker=call.ticker,
            put_ticker=put.ticker,
            call_close=call_bar.close,
            put_close=put_bar.close,
            expiration_implied_move=expiration_move,
            remaining_trading_sessions=remaining_sessions,
            static_daily_implied_move=daily_move,
            calculated_at=now.astimezone(UTC),
        )
