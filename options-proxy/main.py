"""FastAPI process for the options-proxy indicators."""

from __future__ import annotations

import asyncio
import contextlib
import logging
from contextlib import asynccontextmanager
from dataclasses import asdict, dataclass
from datetime import UTC, date, datetime, timedelta
from typing import AsyncIterator
from zoneinfo import ZoneInfo

import uvicorn
from fastapi import FastAPI

from config import cfg
from earnings import EarningsProvider
from implied_move import ImpliedMoveError, StaticDailyImpliedMoveCalculator
from manual_gamma_flip import ManualGammaFlipProvider
from market_calendar import MarketCalendar
from massive_client import MassiveApiError, MassiveRestClient
from models import StaticDailyImpliedMove
from udp_broadcaster import UdpBroadcaster

logger = logging.getLogger(__name__)
EASTERN = ZoneInfo("America/New_York")


@dataclass(slots=True)
class Runtime:
    value: StaticDailyImpliedMove | None = None
    last_error: str | None = None
    last_attempt_at: datetime | None = None
    last_success_at: datetime | None = None


runtime_by_ticker = {
    ticker: Runtime() for ticker in cfg.ticker_symbols
}
refresh_lock = asyncio.Lock()
holiday_refresh_date: date | None = None


def _expected_trading_date(calendar: MarketCalendar) -> date:
    return calendar.next_session_on_or_after(datetime.now(EASTERN).date())


def _current_implied_move(
    calendar: MarketCalendar,
    ticker: str,
) -> StaticDailyImpliedMove | None:
    expected_date = _expected_trading_date(calendar)
    value = runtime_by_ticker[ticker].value
    if value is None or value.trading_date != expected_date:
        return None
    return value


async def _refresh(
    ticker: str,
    calculator: StaticDailyImpliedMoveCalculator,
) -> None:
    async with refresh_lock:
        state = runtime_by_ticker[ticker]
        state.last_attempt_at = datetime.now(UTC)
        try:
            value = await calculator.calculate(ticker)
        except (ImpliedMoveError, OSError) as exc:
            state.last_error = str(exc)
            logger.error(
                "Static Daily Implied Move refresh failed for %s: %s",
                ticker,
                exc,
            )
            return

        state.value = value
        state.last_error = None
        state.last_success_at = datetime.now(UTC)
        logger.info(
            "Static Daily Implied Move %.6f for %s on %s "
            "(source=%s, expiry=%s, ATM=%.2f)",
            value.static_daily_implied_move,
            ticker,
            value.trading_date,
            value.source_session_date,
            value.expiration_date,
            value.atm_strike,
        )


async def _refresh_calendar(
    client: MassiveRestClient,
    calendar: MarketCalendar,
    expected_date: date,
) -> bool:
    global holiday_refresh_date
    if holiday_refresh_date == expected_date:
        return True

    try:
        holidays = await client.fetch_market_holidays()
        calendar.merge_and_save(holidays)
    except (MassiveApiError, OSError) as exc:
        if not calendar.has_cache:
            message = f"Holiday refresh failed and no cache is available: {exc}"
            for state in runtime_by_ticker.values():
                state.last_error = message
            logger.error(message)
            return False
        logger.warning("Holiday refresh failed; using cached calendar: %s", exc)

    holiday_refresh_date = expected_date
    return True


async def _refresh_loop(
    client: MassiveRestClient,
    calendar: MarketCalendar,
    calculator: StaticDailyImpliedMoveCalculator,
) -> None:
    while True:
        expected_date = _expected_trading_date(calendar)
        missing_tickers = [
            ticker
            for ticker in cfg.ticker_symbols
            if _current_implied_move(calendar, ticker) is None
        ]
        if (
            missing_tickers
            and await _refresh_calendar(client, calendar, expected_date)
        ):
            for ticker in missing_tickers:
                await _refresh(ticker, calculator)
        await asyncio.sleep(cfg.calculation_check_interval_seconds)


async def _gamma_flip_loop(provider: ManualGammaFlipProvider) -> None:
    interval = max(cfg.manual_gamma_flip_poll_interval_seconds, 0.1)
    last_reported_error: str | None = None
    while True:
        value = provider.poll()
        if value is not None:
            logger.info(
                "Loaded manual SPY Gamma Flip %.6f "
                "(trading_date=%s, loaded_at=%s)",
                value.spy_gamma_flip,
                value.trading_date,
                value.loaded_at.isoformat(),
            )
            last_reported_error = None
        elif provider.last_error and provider.last_error != last_reported_error:
            logger.warning("Manual SPY Gamma Flip is unavailable: %s", provider.last_error)
            last_reported_error = provider.last_error
        await asyncio.sleep(interval)


async def _earnings_loop(
    provider: EarningsProvider,
    calendar: MarketCalendar,
) -> None:
    """Refresh the earnings universe once per session, retrying total failures.

    This runs in the background so the HTTP endpoint always answers from the
    retained cache instead of blocking a caller behind dozens of yfinance
    lookups.
    """

    interval = max(cfg.earnings_check_interval_seconds, 1.0)
    while True:
        expected_date = _expected_trading_date(calendar)
        if provider.needs_refresh(expected_date):
            resolved = await provider.refresh(expected_date)
            missing = provider.missing_tickers()
            if resolved:
                logger.info(
                    "Earnings dates refreshed for %s: %d of %d tickers resolved%s",
                    expected_date,
                    len(provider.tickers) - len(missing),
                    len(provider.tickers),
                    f", missing {', '.join(missing)}" if missing else "",
                )
        await asyncio.sleep(interval)


async def _broadcast_loop(
    broadcaster: UdpBroadcaster,
    calendar: MarketCalendar,
    gamma_flip_provider: ManualGammaFlipProvider,
) -> None:
    interval = max(cfg.broadcast_interval_ms / 1000.0, 0.05)
    while True:
        expected_date = _expected_trading_date(calendar)
        gamma_flip = gamma_flip_provider.current_for(expected_date)
        for ticker in cfg.ticker_symbols:
            implied_move = _current_implied_move(calendar, ticker)
            try:
                await broadcaster.broadcast(
                    ticker,
                    expected_date,
                    implied_move,
                    gamma_flip,
                )
            except OSError:
                logger.exception("UDP broadcast failed for %s", ticker)
        await asyncio.sleep(interval)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    client = MassiveRestClient()
    calendar = MarketCalendar(cfg.holiday_cache_path)
    if calendar.load_error:
        logger.warning("Ignoring unreadable holiday cache: %s", calendar.load_error)
    calculator = StaticDailyImpliedMoveCalculator(client, calendar)
    gamma_flip_provider = ManualGammaFlipProvider(cfg.manual_gamma_flip_path, "SPY")
    initial_gamma_flip = gamma_flip_provider.poll()
    if initial_gamma_flip is not None:
        logger.info(
            "Loaded manual SPY Gamma Flip %.6f "
            "(trading_date=%s, loaded_at=%s)",
            initial_gamma_flip.spy_gamma_flip,
            initial_gamma_flip.trading_date,
            initial_gamma_flip.loaded_at.isoformat(),
        )

    earnings_provider: EarningsProvider | None = None
    if cfg.earnings_enabled:
        earnings_provider = EarningsProvider(
            cfg.earnings_cache_path,
            cfg.ticker_symbols,
            request_spacing_seconds=cfg.earnings_request_spacing_seconds,
            retention_days=cfg.earnings_retention_days,
        )
        if earnings_provider.load_error:
            logger.warning(
                "Ignoring unreadable earnings cache: %s", earnings_provider.load_error
            )
        elif earnings_provider.has_cache:
            logger.info(
                "Loaded cached earnings dates for %d ticker(s) fetched at %s",
                len(cfg.ticker_symbols) - len(earnings_provider.missing_tickers()),
                earnings_provider.fetched_at,
            )
    else:
        logger.warning("EARNINGS_ENABLED is false; /earnings will report no coverage")

    broadcaster = UdpBroadcaster()
    broadcaster.open()

    app.state.client = client
    app.state.calendar = calendar
    app.state.calculator = calculator
    app.state.gamma_flip_provider = gamma_flip_provider
    app.state.earnings_provider = earnings_provider
    app.state.broadcaster = broadcaster

    tasks = [
        asyncio.create_task(_refresh_loop(client, calendar, calculator)),
        asyncio.create_task(_gamma_flip_loop(gamma_flip_provider)),
        asyncio.create_task(
            _broadcast_loop(broadcaster, calendar, gamma_flip_provider)
        ),
    ]
    if earnings_provider is not None:
        tasks.append(asyncio.create_task(_earnings_loop(earnings_provider, calendar)))
    try:
        yield
    finally:
        for task in tasks:
            task.cancel()
        for task in tasks:
            with contextlib.suppress(asyncio.CancelledError):
                await task
        broadcaster.close()
        await client.close()


app = FastAPI(
    title="Options Proxy",
    description=(
        "Publishes the Static Daily Implied Move and a hot-reloaded manual "
        "SPY Gamma Flip."
    ),
    lifespan=lifespan,
)


@app.get("/indicator")
async def indicator() -> dict:
    calendar: MarketCalendar | None = getattr(app.state, "calendar", None)
    provider: ManualGammaFlipProvider | None = getattr(
        app.state, "gamma_flip_provider", None
    )
    expected_date = _expected_trading_date(calendar) if calendar else None
    implied_moves = {
        ticker: _current_implied_move(calendar, ticker) if calendar else None
        for ticker in cfg.ticker_symbols
    }
    gamma_status = (
        provider.status(expected_date)
        if provider is not None
        else {
            "valid": False,
            "last_error": "Manual gamma-flip provider is not initialized",
        }
    )
    return {
        "tickers": cfg.ticker_symbols,
        "expected_trading_date": expected_date,
        "static_daily_implied_moves": {
            ticker: asdict(value) if value is not None else None
            for ticker, value in implied_moves.items()
        },
        "spy_gamma_flip": gamma_status,
    }


@app.get("/calendar")
async def calendar_view() -> dict:
    """Today's session status and close time, plus the raw holiday records.

    Both are served because neither alone is enough. Raw records force every
    consumer to reimplement session arithmetic, which is how two languages
    drift apart; derived answers alone hide the window the data actually
    covers. ``session.closes_at`` is the field that matters most: an
    early-close date is still a session, so a consumer that assumes a 16:00
    close will schedule exits into a shut market.
    """

    calendar: MarketCalendar | None = getattr(app.state, "calendar", None)
    if calendar is None:
        return {
            "session": None,
            "holidays": [],
            "cache": {"present": False, "last_error": "Calendar is not initialized"},
        }

    expected_date = _expected_trading_date(calendar)
    today = datetime.now(EASTERN).date()
    opens_at, closes_at, hours_source = calendar.session_hours(expected_date)
    covers = calendar.coverage()

    return {
        # The next session on or after today, which is the date the UDP frames
        # are stamped with. On a weekend or full holiday it is not today.
        "trading_date": expected_date,
        "today": today,
        "session": {
            "trading_date": expected_date,
            "status": calendar.status_on(expected_date),
            "is_session": calendar.is_session(expected_date),
            # ISO-8601 UTC, always resolved for a session so a consumer never
            # encodes market hours itself. `hours_source` says whether the
            # times came from an early-close record or the standard schedule.
            "opens_at": opens_at,
            "closes_at": closes_at,
            "hours_source": hours_source,
            "previous_session": calendar.previous_session_on_or_before(
                expected_date - timedelta(days=1)
            ),
            "next_session": calendar.next_session_after(expected_date),
        },
        "holidays": calendar.merged_records(),
        "cache": {
            "present": calendar.has_cache,
            "path": str(calendar.cache_path),
            "fetched_at": calendar.fetched_at,
            # Massive returns upcoming records only, so this is a window rather
            # than a complete calendar. A date outside it is unknown, not open.
            "covers_from": covers[0] if covers else None,
            "covers_to": covers[1] if covers else None,
            "last_error": calendar.load_error,
        },
    }


@app.get("/earnings")
async def earnings() -> dict:
    """Next earnings date per ticker, for the whole configured universe.

    One atomic snapshot rather than a per-ticker route: the payload is small,
    the proxy computes every ticker anyway, and a single response carries one
    ``trading_date`` and one coverage answer that cannot disagree with itself.

    This publishes facts only. How many sessions either side of a report a
    strategy declines to trade is a decision for the consuming strategy.
    """

    calendar: MarketCalendar | None = getattr(app.state, "calendar", None)
    expected_date = _expected_trading_date(calendar) if calendar else None
    provider: EarningsProvider | None = getattr(app.state, "earnings_provider", None)
    if provider is None:
        return {
            "trading_date": expected_date,
            "source": "yfinance",
            "stocks": {},
            "coverage": {
                "required": len(cfg.ticker_symbols),
                "valid": 0,
                "missing_tickers": list(cfg.ticker_symbols),
            },
            "last_error": "Earnings retrieval is disabled",
        }
    return provider.snapshot(expected_date)


@app.get("/health")
async def health() -> dict:
    calendar: MarketCalendar | None = getattr(app.state, "calendar", None)
    expected_date = _expected_trading_date(calendar) if calendar else None
    implied_moves = {
        ticker: _current_implied_move(calendar, ticker) if calendar else None
        for ticker in cfg.ticker_symbols
    }
    missing_tickers = [
        ticker for ticker, value in implied_moves.items() if value is None
    ]
    provider: ManualGammaFlipProvider | None = getattr(
        app.state, "gamma_flip_provider", None
    )
    gamma_status = (
        provider.status(expected_date)
        if provider is not None
        else {
            "valid": False,
            "last_error": "Manual gamma-flip provider is not initialized",
        }
    )
    ready = not missing_tickers and bool(gamma_status["valid"])
    broadcaster: UdpBroadcaster | None = getattr(app.state, "broadcaster", None)
    client: MassiveRestClient | None = getattr(app.state, "client", None)

    # Earnings is reported separately and deliberately does not fold into the
    # top-level status. That status answers "is the UDP contract being fully
    # delivered", and earnings is not on the UDP contract; it is pulled from
    # /earnings by a consumer that applies its own policy. Keeping them apart
    # stops one delisted symbol from making the broadcast look broken, and
    # stops a healthy broadcast from masking an empty earnings cache.
    earnings_provider: EarningsProvider | None = getattr(
        app.state, "earnings_provider", None
    )
    if earnings_provider is None:
        earnings_status = {"status": "disabled"}
    else:
        earnings_missing = earnings_provider.missing_tickers()
        earnings_status = {
            "status": "ready" if not earnings_missing else "degraded",
            "coverage": {
                # Counted from the provider's own universe rather than re-derived
                # from config, so this can never disagree with /earnings.
                "required": len(earnings_provider.tickers),
                "valid": len(earnings_provider.tickers) - len(earnings_missing),
                "missing_tickers": earnings_missing,
            },
            "refreshed_for_trading_date": earnings_provider.refreshed_for_date,
            "fetched_at": earnings_provider.fetched_at,
            "last_attempt_at": earnings_provider.last_attempt_at,
            "last_success_at": earnings_provider.last_success_at,
            "last_error": earnings_provider.last_error or earnings_provider.load_error,
        }

    return {
        "status": "ready" if ready else "degraded",
        "tickers": cfg.ticker_symbols,
        "expected_trading_date": expected_date,
        "static_daily_implied_move_coverage": {
            "required": len(cfg.ticker_symbols),
            "valid": len(cfg.ticker_symbols) - len(missing_tickers),
            "missing_tickers": missing_tickers,
        },
        "manual_spy_gamma_flip": gamma_status,
        "per_ticker_refresh": {
            ticker: {
                "last_attempt_at": state.last_attempt_at,
                "last_success_at": state.last_success_at,
                "last_error": state.last_error,
            }
            for ticker, state in runtime_by_ticker.items()
        },
        "earnings": earnings_status,
        "massive_rate_limit": client.rate_limiter.status() if client else None,
        "udp": broadcaster.stats() if broadcaster else None,
    }


if __name__ == "__main__":
    logging.basicConfig(
        level=getattr(logging, cfg.log_level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    uvicorn.run(app, host=cfg.host, port=cfg.port, log_level=cfg.log_level)
