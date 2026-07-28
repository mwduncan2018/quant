"""Small domain models used by the options-indicator pipelines."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime


@dataclass(frozen=True, slots=True)
class DailyBar:
    """A qualifying-trade daily bar returned by Massive."""

    ticker: str
    session_date: date
    close: float
    timestamp_ms: int


@dataclass(frozen=True, slots=True)
class OptionContract:
    """The reference fields needed to identify an ATM call/put pair."""

    ticker: str
    underlying_ticker: str
    expiration_date: date
    strike_price: float
    contract_type: str
    shares_per_contract: float
    has_additional_underlyings: bool = False

    @property
    def is_standard(self) -> bool:
        return self.shares_per_contract == 100 and not self.has_additional_underlyings


@dataclass(frozen=True, slots=True)
class MarketHoliday:
    """A Massive holiday or shortened-session record."""

    date: date
    exchange: str
    status: str
    name: str = ""
    open_at: str | None = None
    close_at: str | None = None


@dataclass(frozen=True, slots=True)
class StaticDailyImpliedMove:
    """A frozen dollar move calculated from the previous trading session."""

    ticker: str
    trading_date: date
    source_session_date: date
    expiration_date: date
    underlying_close: float
    atm_strike: float
    call_ticker: str
    put_ticker: str
    call_close: float
    put_close: float
    expiration_implied_move: float
    remaining_trading_sessions: int
    static_daily_implied_move: float
    calculated_at: datetime


@dataclass(frozen=True, slots=True)
class SpyGammaFlip:
    """A manually supplied SPY gamma-flip level."""

    ticker: str
    spy_gamma_flip: float
    trading_date: date
    loaded_at: datetime


@dataclass(frozen=True, slots=True)
class EarningsDates:
    """Every earnings date currently held for one ticker.

    A ticker keeps more than one because a strategy avoiding the day after a
    report needs the date that just passed, and the upstream source stops
    reporting it: some tickers roll straight to next quarter the moment they
    report, others linger for days. Retaining what was observed removes that
    inconsistency.

    These are facts, not decisions. How many sessions either side of a report a
    strategy refuses to trade is that strategy's own policy; different
    strategies want different windows, and one may want to trade earnings
    rather than avoid them.
    """

    ticker: str
    observed: tuple[date, ...]
    fetched_at: datetime

    def next_on_or_after(self, today: date) -> date | None:
        """The soonest held date that has not passed."""

        upcoming = [item for item in self.observed if item >= today]
        return min(upcoming) if upcoming else None

    def last_before(self, today: date) -> date | None:
        """The most recent held date that has passed."""

        past = [item for item in self.observed if item < today]
        return max(past) if past else None
