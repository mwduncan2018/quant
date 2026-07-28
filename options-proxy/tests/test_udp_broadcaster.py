from datetime import UTC, date, datetime

from config import Settings
from models import SpyGammaFlip, StaticDailyImpliedMove
from proto.market_state_pb2 import IndicatorFrame
from udp_broadcaster import UdpBroadcaster


def _implied_move() -> StaticDailyImpliedMove:
    return StaticDailyImpliedMove(
        ticker="SPY",
        trading_date=date(2026, 7, 27),
        source_session_date=date(2026, 7, 24),
        expiration_date=date(2026, 7, 31),
        underlying_close=738.93,
        atm_strike=739.0,
        call_ticker="O:SPY260731C00739000",
        put_ticker="O:SPY260731P00739000",
        call_close=8.0,
        put_close=8.5,
        expiration_implied_move=14.025,
        remaining_trading_sessions=5,
        static_daily_implied_move=6.272,
        calculated_at=datetime(2026, 7, 27, 12, 0, tzinfo=UTC),
    )


def _gamma_flip() -> SpyGammaFlip:
    return SpyGammaFlip(
        ticker="SPY",
        spy_gamma_flip=601.25,
        trading_date=date(2026, 7, 27),
        loaded_at=datetime(2026, 7, 27, 12, 45, tzinfo=UTC),
    )


def _broadcaster() -> UdpBroadcaster:
    return UdpBroadcaster(
        Settings(_env_file=None, massive_api_key="test", udp_mtu=1400)
    )


def test_udp_payload_contains_both_valid_indicators() -> None:
    payload = _broadcaster().serialize(
        "SPY",
        date(2026, 7, 27),
        _implied_move(),
        _gamma_flip(),
    )
    decoded = IndicatorFrame.FromString(payload)

    assert decoded.sequence == 1
    assert decoded.ticker == "SPY"
    assert decoded.trading_date == "2026-07-27"
    assert decoded.static_daily_implied_move_valid is True
    assert decoded.static_daily_implied_move == 6.272
    assert decoded.spy_gamma_flip_valid is True
    assert decoded.spy_gamma_flip == 601.25
    assert decoded.emitted_at_unix_ms > 0
    assert len(payload) < 1400


def test_gamma_flip_can_be_broadcast_when_implied_move_is_unavailable() -> None:
    payload = _broadcaster().serialize(
        "AAPL",
        date(2026, 7, 27),
        None,
        _gamma_flip(),
    )
    decoded = IndicatorFrame.FromString(payload)

    assert decoded.ticker == "AAPL"
    assert decoded.trading_date == "2026-07-27"
    assert decoded.static_daily_implied_move_valid is False
    assert decoded.static_daily_implied_move == 0.0
    assert decoded.spy_gamma_flip_valid is True
    assert decoded.spy_gamma_flip == 601.25


def test_missing_gamma_flip_is_explicitly_invalid() -> None:
    payload = _broadcaster().serialize(
        "SPY",
        date(2026, 7, 27),
        _implied_move(),
        None,
    )
    decoded = IndicatorFrame.FromString(payload)

    assert decoded.static_daily_implied_move_valid is True
    assert decoded.spy_gamma_flip_valid is False
    assert decoded.spy_gamma_flip == 0.0
