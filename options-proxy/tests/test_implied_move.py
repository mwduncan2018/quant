import asyncio
import math
from datetime import UTC, date, datetime

import pytest

from config import Settings
from implied_move import (
    ImpliedMoveError,
    StaticDailyImpliedMoveCalculator,
    select_atm_pair,
)
from market_calendar import MarketCalendar
from models import DailyBar, OptionContract


def contract(strike: float, contract_type: str, *, standard: bool = True) -> OptionContract:
    side = "C" if contract_type == "call" else "P"
    return OptionContract(
        ticker=f"O:TEST260724{side}{int(strike * 1000):08d}",
        underlying_ticker="TEST",
        expiration_date=date(2026, 7, 24),
        strike_price=strike,
        contract_type=contract_type,
        shares_per_contract=100 if standard else 50,
    )


class FakeClient:
    def __init__(self, *, option_date: date = date(2026, 7, 20)) -> None:
        self.option_date = option_date
        self.contract_kwargs: dict | None = None
        self.contracts = [
            contract(99, "call"),
            contract(99, "put"),
            contract(100, "call"),
            contract(100, "put"),
            contract(101, "call"),
            contract(101, "put"),
        ]

    async def fetch_previous_bar(self, ticker: str) -> DailyBar:
        if ticker == "TEST":
            return DailyBar("TEST", date(2026, 7, 20), 100.20, 1)
        close = 3.0 if "C" in ticker.rsplit("260724", 1)[1] else 2.0
        return DailyBar(ticker, self.option_date, close, 1)

    async def fetch_option_contracts(self, **kwargs) -> list[OptionContract]:
        self.contract_kwargs = kwargs
        return self.contracts


def settings() -> Settings:
    return Settings(
        _env_file=None,
        massive_api_key="test",
        tickers="TEST",
        implied_move_multiplier=0.85,
    )


def test_select_atm_pair_requires_a_standard_call_and_put() -> None:
    contracts = [
        contract(99, "call"),
        contract(99, "put", standard=False),
        contract(101, "call"),
        contract(101, "put"),
    ]
    call, put = select_atm_pair(contracts, 100.0)
    assert call.strike_price == 101
    assert put.strike_price == 101


def test_static_daily_implied_move_uses_prior_closes_and_sqrt_time(tmp_path) -> None:
    client = FakeClient()
    calendar = MarketCalendar(tmp_path / "holidays.json")
    calculator = StaticDailyImpliedMoveCalculator(client, calendar, settings())

    result = asyncio.run(
        calculator.calculate("TEST", now=datetime(2026, 7, 21, 12, tzinfo=UTC))
    )

    assert result.trading_date == date(2026, 7, 21)
    assert result.source_session_date == date(2026, 7, 20)
    assert result.expiration_date == date(2026, 7, 24)
    assert result.atm_strike == 100
    assert result.remaining_trading_sessions == 4
    assert result.expiration_implied_move == pytest.approx(0.85 * (3.0 + 2.0))
    assert result.static_daily_implied_move == pytest.approx(4.25 * math.sqrt(1 / 4))
    assert client.contract_kwargs == {
        "underlying_ticker": "TEST",
        "expiration_date": "2026-07-24",
        "as_of": "2026-07-20",
        "strike_gte": pytest.approx(90.18),
        "strike_lte": pytest.approx(110.22),
    }


def test_option_closes_must_match_underlying_source_session(tmp_path) -> None:
    client = FakeClient(option_date=date(2026, 7, 17))
    calculator = StaticDailyImpliedMoveCalculator(
        client,
        MarketCalendar(tmp_path / "holidays.json"),
        settings(),
    )

    with pytest.raises(ImpliedMoveError, match="not 2026-07-20"):
        asyncio.run(calculator.calculate("TEST", now=datetime(2026, 7, 21, 12, tzinfo=UTC)))
