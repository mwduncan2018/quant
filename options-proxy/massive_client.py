"""Focused asynchronous REST client for the Massive data used by this proxy."""

from __future__ import annotations

import asyncio
from datetime import datetime
from typing import Any
from urllib.parse import quote
from zoneinfo import ZoneInfo

import httpx

from config import Settings, cfg
from models import DailyBar, MarketHoliday, OptionContract
from rate_limiter import SlidingWindowRateLimiter

EASTERN = ZoneInfo("America/New_York")


class MassiveApiError(RuntimeError):
    """Raised when Massive cannot provide valid data for the calculation."""


class MassiveRestClient:
    """REST client containing only the endpoints required by the proxy."""

    def __init__(
        self,
        settings: Settings = cfg,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
        rate_limiter: SlidingWindowRateLimiter | None = None,
    ) -> None:
        self.settings = settings
        self._transport = transport
        self._sem = asyncio.Semaphore(settings.rest_concurrency)
        self._client: httpx.AsyncClient | None = None
        self.rate_limiter = rate_limiter or SlidingWindowRateLimiter(
            enabled=settings.rest_rate_limit_enabled,
            max_requests=settings.rest_rate_limit_requests,
            period_seconds=settings.rest_rate_limit_period_seconds,
        )

    async def _ensure_client(self) -> httpx.AsyncClient:
        if not self.settings.massive_api_key:
            raise MassiveApiError("MASSIVE_API_KEY is not configured")
        if self._client is None or self._client.is_closed:
            self._client = httpx.AsyncClient(
                base_url=self.settings.massive_base_url,
                timeout=httpx.Timeout(30.0, connect=10.0),
                headers={
                    "Accept": "application/json",
                    "Authorization": f"Bearer {self.settings.massive_api_key}",
                },
                limits=httpx.Limits(max_connections=10, max_keepalive_connections=5),
                transport=self._transport,
            )
        return self._client

    async def close(self) -> None:
        if self._client is not None and not self._client.is_closed:
            await self._client.aclose()

    async def _get_json(
        self,
        url: str,
        params: dict[str, Any] | None = None,
    ) -> Any:
        client = await self._ensure_client()
        backoff = 1.0

        for attempt in range(self.settings.rest_max_retries + 1):
            # Rate-limit every actual HTTP attempt. Retries and pagination
            # therefore consume capacity just like first attempts.
            await self.rate_limiter.acquire()
            async with self._sem:
                try:
                    response = await client.get(url, params=params)
                except httpx.HTTPError as exc:
                    if attempt >= self.settings.rest_max_retries:
                        raise MassiveApiError(f"Massive request failed for {url}: {exc}") from exc
                else:
                    if response.status_code == 200:
                        try:
                            return response.json()
                        except ValueError as exc:
                            raise MassiveApiError(
                                f"Massive returned invalid JSON for {url}"
                            ) from exc

                    if response.status_code != 429 and response.status_code < 500:
                        raise MassiveApiError(
                            f"Massive returned HTTP {response.status_code} for {url}: "
                            f"{response.text[:200]}"
                        )
                    if attempt >= self.settings.rest_max_retries:
                        raise MassiveApiError(
                            f"Massive exhausted retries for {url} (HTTP {response.status_code})"
                        )
                    retry_after = response.headers.get("Retry-After")
                    if retry_after:
                        try:
                            backoff = max(backoff, float(retry_after))
                        except ValueError:
                            pass

            await asyncio.sleep(backoff)
            backoff = min(backoff * 2.0, 30.0)

        raise MassiveApiError(f"Massive request failed for {url}")

    async def fetch_market_holidays(self) -> list[MarketHoliday]:
        data = await self._get_json("/v1/marketstatus/upcoming")
        if not isinstance(data, list):
            raise MassiveApiError("Massive holiday response was not a list")
        if not data:
            raise MassiveApiError("Massive holiday response was empty")

        records: list[MarketHoliday] = []
        for item in data:
            try:
                records.append(
                    MarketHoliday(
                        date=datetime.strptime(item["date"], "%Y-%m-%d").date(),
                        exchange=str(item["exchange"]).upper(),
                        status=str(item["status"]).lower(),
                        name=str(item.get("name", "")),
                        open_at=item.get("open"),
                        close_at=item.get("close"),
                    )
                )
            except (KeyError, TypeError, ValueError) as exc:
                raise MassiveApiError(f"Invalid Massive holiday record: {item!r}") from exc
        return records

    async def fetch_previous_bar(self, ticker: str) -> DailyBar:
        encoded_ticker = quote(ticker, safe=":")
        data = await self._get_json(
            f"/v2/aggs/ticker/{encoded_ticker}/prev",
            {"adjusted": "true"},
        )
        results = data.get("results", []) if isinstance(data, dict) else []
        if not results:
            raise MassiveApiError(f"Massive returned no previous-day bar for {ticker}")

        item = results[0]
        try:
            close = float(item["c"])
            timestamp_ms = int(item["t"])
            session_date = datetime.fromtimestamp(timestamp_ms / 1000, EASTERN).date()
        except (KeyError, TypeError, ValueError, OSError) as exc:
            raise MassiveApiError(f"Invalid previous-day bar for {ticker}: {item!r}") from exc
        if close <= 0:
            raise MassiveApiError(f"Non-positive previous close for {ticker}: {close}")

        return DailyBar(
            ticker=str(data.get("ticker") or ticker),
            session_date=session_date,
            close=close,
            timestamp_ms=timestamp_ms,
        )

    async def fetch_option_contracts(
        self,
        *,
        underlying_ticker: str,
        expiration_date: str,
        as_of: str,
        strike_gte: float,
        strike_lte: float,
    ) -> list[OptionContract]:
        params: dict[str, Any] | None = {
            "underlying_ticker": underlying_ticker,
            "expiration_date": expiration_date,
            "as_of": as_of,
            "strike_price.gte": strike_gte,
            "strike_price.lte": strike_lte,
            "expired": "false",
            "order": "asc",
            "sort": "strike_price",
            "limit": 1000,
        }
        url = "/v3/reference/options/contracts"
        contracts: list[OptionContract] = []

        while url:
            data = await self._get_json(url, params)
            if not isinstance(data, dict):
                raise MassiveApiError("Massive options-contract response was not an object")
            for item in data.get("results", []):
                try:
                    contracts.append(
                        OptionContract(
                            ticker=str(item["ticker"]),
                            underlying_ticker=str(item["underlying_ticker"]),
                            expiration_date=datetime.strptime(
                                item["expiration_date"], "%Y-%m-%d"
                            ).date(),
                            strike_price=float(item["strike_price"]),
                            contract_type=str(item["contract_type"]).lower(),
                            shares_per_contract=float(item.get("shares_per_contract", 100)),
                            has_additional_underlyings=bool(item.get("additional_underlyings")),
                        )
                    )
                except (KeyError, TypeError, ValueError) as exc:
                    raise MassiveApiError(
                        f"Invalid Massive option-contract record: {item!r}"
                    ) from exc

            next_url = data.get("next_url")
            url = str(next_url) if next_url else ""
            params = None

        return contracts
