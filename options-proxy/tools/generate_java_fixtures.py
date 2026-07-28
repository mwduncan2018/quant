"""Emit golden IndicatorFrame payloads for the Java trading engine's tests.

The bytes are produced by the same ``UdpBroadcaster.serialize`` the proxy uses
on the wire, so the Java side is proven against real proxy output rather than
against a Java-authored re-encoding of the contract.

Usage::

    .\\.venv\\Scripts\\python.exe tools/generate_java_fixtures.py \\
        --output ../trading-engine/trading-engine/src/test/resources/optionsproxy
"""

from __future__ import annotations

import argparse
import sys
from datetime import UTC, date, datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from config import Settings  # noqa: E402
from models import SpyGammaFlip, StaticDailyImpliedMove  # noqa: E402
from udp_broadcaster import UdpBroadcaster  # noqa: E402

TRADING_DATE = date(2026, 7, 27)
TICKER = "AAPL"
IMPLIED_MOVE = 6.272
GAMMA_FLIP = 601.25


def _implied_move() -> StaticDailyImpliedMove:
    return StaticDailyImpliedMove(
        ticker=TICKER,
        trading_date=TRADING_DATE,
        source_session_date=date(2026, 7, 24),
        expiration_date=date(2026, 7, 31),
        underlying_close=238.93,
        atm_strike=239.0,
        call_ticker="O:AAPL260731C00239000",
        put_ticker="O:AAPL260731P00239000",
        call_close=8.0,
        put_close=8.5,
        expiration_implied_move=14.025,
        remaining_trading_sessions=5,
        static_daily_implied_move=IMPLIED_MOVE,
        calculated_at=datetime(2026, 7, 27, 12, 0, tzinfo=UTC),
    )


def _gamma_flip() -> SpyGammaFlip:
    return SpyGammaFlip(
        ticker="SPY",
        spy_gamma_flip=GAMMA_FLIP,
        trading_date=TRADING_DATE,
        loaded_at=datetime(2026, 7, 27, 12, 45, tzinfo=UTC),
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output",
        type=Path,
        required=True,
        help="Directory that receives the .bin fixtures",
    )
    arguments = parser.parse_args()
    output_directory: Path = arguments.output.resolve()
    output_directory.mkdir(parents=True, exist_ok=True)

    broadcaster = UdpBroadcaster(
        Settings(_env_file=None, massive_api_key="fixture", udp_mtu=1400)
    )

    fixtures = {
        "python-indicator-frame.bin": broadcaster.serialize(
            TICKER, TRADING_DATE, _implied_move(), _gamma_flip()
        ),
        "python-indicator-frame-no-implied-move.bin": broadcaster.serialize(
            TICKER, TRADING_DATE, None, _gamma_flip()
        ),
    }

    for name, payload in fixtures.items():
        destination = output_directory / name
        destination.write_bytes(payload)
        print(f"Wrote {len(payload)} bytes to {destination}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
