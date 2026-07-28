"""Earnings dates with an atomically replaced on-disk cache.

The dates come from yfinance, which costs no Massive quota but is an
unofficial API that rate-limits by address and fails unevenly across symbols.
Two rules follow from that:

* One symbol's failure must never discard the other symbols' results.
* A failed refresh must leave the previous good answer in place rather than
  emptying the cache.

A ticker keeps more than one date. The upstream field is the *next* report, and
what it does once a company reports is inconsistent: some tickers roll straight
to the following quarter, others keep showing the date that just passed for
days. A strategy avoiding the day after a report needs that date either way, so
every observed date is retained until it is a month old.

Distinguishing the two ways a date can change matters:

* The held date has already passed and the source now reports a later one. The
  report happened, so the old date is kept.
* The held date is still in the future and the source reports a different one.
  It was rescheduled, or the estimate was corrected. Nothing happened on the old
  date, so keeping it would create a blackout around an ordinary trading day.
"""

from __future__ import annotations

import asyncio
import json
import logging
import math
import os
import tempfile
from datetime import UTC, date, datetime, timedelta
from pathlib import Path
from typing import Any, Callable, Sequence

from models import EarningsDates

logger = logging.getLogger(__name__)

# yfinance is imported lazily so the module can be imported, and the whole
# proxy tested, on a machine that has never installed it.
TickerFactory = Callable[[str], Any]


class EarningsCacheError(RuntimeError):
    """Raised when an earnings cache exists but cannot be read safely."""


class EarningsProvider:
    """Fetch, cache, and serve earnings dates for the ticker universe."""

    def __init__(
        self,
        cache_path: Path,
        tickers: Sequence[str],
        *,
        ticker_factory: TickerFactory | None = None,
        request_spacing_seconds: float = 0.0,
        retention_days: int = 30,
    ) -> None:
        self.cache_path = Path(cache_path)
        self.tickers = tuple(dict.fromkeys(t.strip().upper() for t in tickers if t.strip()))
        if not self.tickers:
            raise ValueError("An earnings provider needs at least one ticker")
        if retention_days <= 0:
            raise ValueError("Earnings retention must be at least one day")
        self._ticker_factory = ticker_factory
        self._request_spacing_seconds = max(request_spacing_seconds, 0.0)
        self.retention_days = retention_days

        self._observed: dict[str, tuple[date, ...]] = {}
        self._fetched_at_by_ticker: dict[str, datetime] = {}
        self._errors: dict[str, str] = {}
        self.fetched_at: datetime | None = None
        self.last_attempt_at: datetime | None = None
        self.last_success_at: datetime | None = None
        self.last_error: str | None = None
        self.load_error: str | None = None
        self.refreshed_for_date: date | None = None

        try:
            self.load()
        except EarningsCacheError as exc:
            # A fresh fetch can safely replace a corrupt cache. Until one
            # succeeds, every ticker simply reports as unavailable.
            self.load_error = str(exc)

    # ------------------------------------------------------------------
    # Reading
    # ------------------------------------------------------------------

    @property
    def has_cache(self) -> bool:
        return bool(self._observed)

    def dates_for(self, ticker: str) -> EarningsDates | None:
        """Return every date held for one ticker, or None."""

        normalized = ticker.strip().upper()
        observed = self._observed.get(normalized)
        if not observed:
            return None
        return EarningsDates(
            ticker=normalized,
            observed=observed,
            fetched_at=self._fetched_at_by_ticker.get(normalized, datetime.now(UTC)),
        )

    def missing_tickers(self) -> list[str]:
        return [ticker for ticker in self.tickers if not self._observed.get(ticker)]

    def needs_refresh(self, trading_date: date) -> bool:
        """Whether this session has yet to complete a refresh attempt cycle."""

        return self.refreshed_for_date != trading_date

    def snapshot(self, trading_date: date | None) -> dict[str, Any]:
        """Return the JSON-compatible view served by the HTTP endpoint.

        Every ticker in the universe appears, so a consumer can tell an absent
        date from a symbol it forgot to configure. ``valid`` is the field to
        read: it says whether any date is held at all, which is what separates
        "this company has no report scheduled" from "we could not find out".
        Those are opposite answers for a strategy avoiding earnings.
        """

        today = trading_date or datetime.now(UTC).date()
        stocks: dict[str, Any] = {}
        for ticker in self.tickers:
            held = self.dates_for(ticker)
            stocks[ticker] = {
                "ticker": ticker,
                "next_earnings_date": held.next_on_or_after(today) if held else None,
                # Retained so a consumer can act on the day after a report even
                # when the upstream source has already rolled forward.
                "last_earnings_date": held.last_before(today) if held else None,
                "valid": held is not None,
                "fetched_at": held.fetched_at if held else None,
                "last_error": self._errors.get(ticker),
            }

        missing = self.missing_tickers()
        return {
            "trading_date": trading_date,
            "source": "yfinance",
            "retention_days": self.retention_days,
            "stocks": stocks,
            "coverage": {
                "required": len(self.tickers),
                "valid": len(self.tickers) - len(missing),
                "missing_tickers": missing,
            },
            "cache_path": str(self.cache_path),
            "fetched_at": self.fetched_at,
            "last_attempt_at": self.last_attempt_at,
            "last_success_at": self.last_success_at,
            "last_error": self.last_error or self.load_error,
            "refreshed_for_trading_date": self.refreshed_for_date,
            "retaining_cached_values": self.has_cache,
        }

    # ------------------------------------------------------------------
    # Refreshing
    # ------------------------------------------------------------------

    async def refresh(self, trading_date: date) -> int:
        """Fetch every ticker, retaining prior dates for any that fail.

        Returns the number of tickers newly resolved. The session is marked
        refreshed as long as *something* resolved, so a permanently delisted
        symbol cannot drive an endless retry loop while a total failure, which
        usually means the network rather than the symbols, still gets retried.
        """

        self.last_attempt_at = datetime.now(UTC)
        resolved: dict[str, date] = {}
        errors: dict[str, str] = {}

        for index, ticker in enumerate(self.tickers):
            if index and self._request_spacing_seconds:
                # yfinance rate-limits by address. This runs once per session
                # for the whole universe, so pacing costs nothing that matters.
                await asyncio.sleep(self._request_spacing_seconds)
            try:
                observed = await asyncio.to_thread(self._fetch_one, ticker)
            except Exception as exc:  # noqa: BLE001 - yfinance raises broadly
                errors[ticker] = f"{type(exc).__name__}: {exc}"
                logger.warning("Earnings lookup failed for %s: %s", ticker, exc)
                continue

            if observed is None:
                errors[ticker] = "No earnings date was reported"
                continue
            resolved[ticker] = observed

        now = datetime.now(UTC)
        for ticker, observed in resolved.items():
            self._record(ticker, observed, trading_date)
            self._fetched_at_by_ticker[ticker] = now
        # Expire everything, including tickers that failed this cycle, so a
        # stale date cannot outlive its window just because its lookup broke.
        self._expire(trading_date)
        self._errors = errors

        if resolved:
            self.refreshed_for_date = trading_date
            self.fetched_at = now
            self.last_success_at = now
            self.last_error = None if not errors else f"{len(errors)} ticker(s) unresolved"
            try:
                self.save()
            except OSError as exc:
                self.last_error = f"Earnings cache could not be written: {exc}"
                logger.error(self.last_error)
        else:
            self.last_error = "No earnings dates could be resolved"
            logger.error(
                "Earnings refresh resolved nothing for %d tickers; %s",
                len(self.tickers),
                "retaining the cached values" if self.has_cache else "no cache is available",
            )

        return len(resolved)

    def _record(self, ticker: str, observed: date, today: date) -> None:
        """Merge one observation into the dates held for a ticker."""

        held = set(self._observed.get(ticker, ()))

        # A future date the source no longer reports was rescheduled or
        # corrected. Nothing happened on it, so it must not survive; a past date
        # is kept because the report did happen.
        held = {item for item in held if item < today or item == observed}
        held.add(observed)

        self._observed[ticker] = tuple(sorted(held))

    def _expire(self, today: date) -> None:
        """Drop dates older than the retention window."""

        cutoff = today - timedelta(days=self.retention_days)
        for ticker, held in list(self._observed.items()):
            retained = tuple(item for item in held if item >= cutoff)
            if retained:
                self._observed[ticker] = retained
            else:
                del self._observed[ticker]
                self._fetched_at_by_ticker.pop(ticker, None)

    def _fetch_one(self, ticker: str) -> date | None:
        """Blocking yfinance lookup for one ticker, run off the event loop."""

        handle = self._ticker_handle(ticker)
        return _parse_calendar(getattr(handle, "calendar", None))

    def _ticker_handle(self, ticker: str) -> Any:
        if self._ticker_factory is not None:
            return self._ticker_factory(ticker)
        import yfinance  # noqa: PLC0415 - deliberately lazy

        return yfinance.Ticker(ticker)

    # ------------------------------------------------------------------
    # Cache
    # ------------------------------------------------------------------

    def load(self) -> None:
        if not self.cache_path.exists():
            return

        try:
            payload = json.loads(self.cache_path.read_text(encoding="utf-8"))
            fetched_at = payload.get("fetched_at")
            if fetched_at:
                self.fetched_at = datetime.fromisoformat(fetched_at)
            for item in payload.get("stocks", []):
                ticker = str(item["ticker"]).strip().upper()
                raw_dates = item.get("dates")
                if raw_dates is None:
                    # Tolerate a cache written before dates were retained.
                    raw_dates = [item["next_earnings_date"]]
                self._observed[ticker] = tuple(
                    sorted(date.fromisoformat(value) for value in raw_dates)
                )
                self._fetched_at_by_ticker[ticker] = datetime.fromisoformat(
                    item["fetched_at"]
                )
        except (OSError, ValueError, TypeError, KeyError) as exc:
            self._observed.clear()
            self._fetched_at_by_ticker.clear()
            raise EarningsCacheError(
                f"Invalid earnings cache {self.cache_path}: {exc}"
            ) from exc

    def save(self) -> None:
        """Write the cache through a temporary file and an atomic replace."""

        payload = {
            "fetched_at": (self.fetched_at or datetime.now(UTC)).isoformat(),
            "stocks": [
                {
                    "ticker": ticker,
                    "dates": [item.isoformat() for item in self._observed[ticker]],
                    "fetched_at": self._fetched_at_by_ticker.get(
                        ticker, datetime.now(UTC)
                    ).isoformat(),
                }
                for ticker in sorted(self._observed)
            ],
        }

        self.cache_path.parent.mkdir(parents=True, exist_ok=True)
        descriptor, temporary_name = tempfile.mkstemp(
            prefix=f".{self.cache_path.name}.",
            suffix=".tmp",
            dir=self.cache_path.parent,
            text=True,
        )
        try:
            with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as handle:
                json.dump(payload, handle, indent=2, sort_keys=True)
                handle.write("\n")
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temporary_name, self.cache_path)
        except Exception:
            try:
                os.unlink(temporary_name)
            except FileNotFoundError:
                pass
            raise


def _parse_calendar(calendar: Any) -> date | None:
    """Extract the soonest earnings date from a yfinance calendar.

    yfinance returns several shapes across versions and symbols: a dict whose
    ``Earnings Date`` is a list of dates, a single date, or a DataFrame-like
    object. Anything unrecognized is treated as no data rather than guessed at.

    When a range is returned, the earliest date is taken, so a consumer
    avoiding a report errs early rather than late.
    """

    if calendar is None:
        return None

    raw = None
    if isinstance(calendar, dict):
        raw = calendar.get("Earnings Date")
    else:
        getter = getattr(calendar, "get", None)
        if callable(getter):
            raw = getter("Earnings Date")

    if raw is None:
        return None

    candidates = raw if isinstance(raw, (list, tuple)) else [raw]
    parsed = [item for item in (_coerce_date(entry) for entry in candidates) if item]
    return min(parsed) if parsed else None


def _coerce_date(value: Any) -> date | None:
    if isinstance(value, datetime):
        return value.date()
    if isinstance(value, date):
        return value
    if isinstance(value, str):
        text = value.strip()
        if not text:
            return None
        try:
            return datetime.fromisoformat(text).date()
        except ValueError:
            return None
    if isinstance(value, float) and math.isnan(value):
        return None
    # pandas Timestamps and similar expose date(); anything else is ignored.
    to_date = getattr(value, "date", None)
    if callable(to_date):
        try:
            result = to_date()
        except Exception:  # noqa: BLE001 - unknown third-party object
            return None
        return result if isinstance(result, date) else None
    return None
