import asyncio
from datetime import date, datetime
from zoneinfo import ZoneInfo

import httpx

from config import Settings
from massive_client import MassiveRestClient


class CountingLimiter:
    def __init__(self) -> None:
        self.acquisitions = 0

    async def acquire(self) -> None:
        self.acquisitions += 1


def test_massive_client_parses_required_responses() -> None:
    eastern = ZoneInfo("America/New_York")
    timestamp_ms = int(datetime(2026, 7, 20, tzinfo=eastern).timestamp() * 1000)
    seen_authorization: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen_authorization.append(request.headers.get("Authorization", ""))
        if request.url.path.endswith("/marketstatus/upcoming"):
            return httpx.Response(
                200,
                json=[
                    {
                        "date": "2026-09-07",
                        "exchange": "NYSE",
                        "name": "Labor Day",
                        "status": "closed",
                    }
                ],
            )
        if request.url.path.endswith("/prev"):
            return httpx.Response(
                200,
                json={
                    "ticker": "TEST",
                    "results": [{"c": 100.25, "t": timestamp_ms}],
                },
            )
        if request.url.path.endswith("/reference/options/contracts"):
            return httpx.Response(
                200,
                json={
                    "results": [
                        {
                            "ticker": "O:TEST260724C00100000",
                            "underlying_ticker": "TEST",
                            "expiration_date": "2026-07-24",
                            "strike_price": 100,
                            "contract_type": "call",
                            "shares_per_contract": 100,
                        }
                    ]
                },
            )
        return httpx.Response(404, json={"error": "unexpected path"})

    async def run() -> None:
        limiter = CountingLimiter()
        client = MassiveRestClient(
            Settings(_env_file=None, massive_api_key="secret"),
            transport=httpx.MockTransport(handler),
            rate_limiter=limiter,  # type: ignore[arg-type]
        )
        try:
            holidays = await client.fetch_market_holidays()
            bar = await client.fetch_previous_bar("TEST")
            contracts = await client.fetch_option_contracts(
                underlying_ticker="TEST",
                expiration_date="2026-07-24",
                as_of="2026-07-20",
                strike_gte=90,
                strike_lte=110,
            )
        finally:
            await client.close()

        assert holidays[0].date == date(2026, 9, 7)
        assert bar.session_date == date(2026, 7, 20)
        assert bar.close == 100.25
        assert contracts[0].ticker == "O:TEST260724C00100000"
        assert seen_authorization == ["Bearer secret"] * 3
        assert limiter.acquisitions == 3

    asyncio.run(run())
