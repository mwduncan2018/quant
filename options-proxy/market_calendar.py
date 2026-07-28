"""Massive-backed US equity-market holiday cache and session arithmetic."""

from __future__ import annotations

import json
import os
import tempfile
from datetime import UTC, date, datetime, time, timedelta
from pathlib import Path
from typing import Iterable
from zoneinfo import ZoneInfo

from models import MarketHoliday


RELEVANT_EXCHANGES = frozenset({"NYSE", "NASDAQ"})

EASTERN = ZoneInfo("America/New_York")
STANDARD_OPEN = time(9, 30)
STANDARD_CLOSE = time(16, 0)


def _eastern_instant(session_date: date, local_time: time) -> str:
    """Render an Eastern wall-clock time as the UTC instant Massive's format uses."""

    moment = datetime.combine(session_date, local_time, tzinfo=EASTERN)
    return (
        moment.astimezone(UTC)
        .isoformat(timespec="milliseconds")
        .replace("+00:00", "Z")
    )


class HolidayCacheError(RuntimeError):
    """Raised when a holiday cache exists but cannot be read safely."""


class MarketCalendar:
    """Weekday calendar overlaid with Massive holiday records."""

    def __init__(self, cache_path: Path) -> None:
        self.cache_path = cache_path
        self._records: dict[tuple[date, str], MarketHoliday] = {}
        self.fetched_at: datetime | None = None
        self.load_error: str | None = None
        try:
            self.load()
        except HolidayCacheError as exc:
            # A fresh Massive response can safely replace a corrupt cache. If
            # Massive is also unavailable, main.py refuses to calculate.
            self.load_error = str(exc)

    @property
    def has_cache(self) -> bool:
        return bool(self._records)

    @property
    def records(self) -> tuple[MarketHoliday, ...]:
        return tuple(
            sorted(self._records.values(), key=lambda item: (item.date, item.exchange))
        )

    def load(self) -> None:
        if not self.cache_path.exists():
            return

        try:
            payload = json.loads(self.cache_path.read_text(encoding="utf-8"))
            fetched_at = payload.get("fetched_at")
            if fetched_at:
                self.fetched_at = datetime.fromisoformat(fetched_at)
            for item in payload.get("holidays", []):
                record = MarketHoliday(
                    date=date.fromisoformat(item["date"]),
                    exchange=str(item["exchange"]).upper(),
                    status=str(item["status"]).lower(),
                    name=str(item.get("name", "")),
                    open_at=item.get("open_at"),
                    close_at=item.get("close_at"),
                )
                if record.exchange in RELEVANT_EXCHANGES:
                    self._records[(record.date, record.exchange)] = record
        except (OSError, ValueError, TypeError, KeyError) as exc:
            raise HolidayCacheError(f"Invalid holiday cache {self.cache_path}: {exc}") from exc

    def merge_and_save(
        self,
        records: Iterable[MarketHoliday],
        *,
        fetched_at: datetime | None = None,
    ) -> None:
        for record in records:
            if record.exchange in RELEVANT_EXCHANGES:
                self._records[(record.date, record.exchange)] = record

        self.fetched_at = fetched_at or datetime.now(UTC)
        payload = {
            "fetched_at": self.fetched_at.isoformat(),
            "holidays": [
                {
                    "date": record.date.isoformat(),
                    "exchange": record.exchange,
                    "status": record.status,
                    "name": record.name,
                    "open_at": record.open_at,
                    "close_at": record.close_at,
                }
                for record in self.records
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

    def is_session(self, candidate: date) -> bool:
        if candidate.weekday() >= 5:
            return False
        return not any(
            record.date == candidate and record.status == "closed"
            for record in self._records.values()
        )

    def status_on(self, candidate: date) -> str:
        """Return ``closed``, ``early-close``, or ``open`` for one date.

        ``open`` means no exception is on record, which for a weekday is the
        standard session. Massive's feed is forward-looking, so a date older
        than the cache reports ``open`` because nothing contradicts it, not
        because the market was verified to be open.
        """

        if candidate.weekday() >= 5:
            return "closed"
        statuses = {
            record.status
            for record in self._records.values()
            if record.date == candidate
        }
        if "closed" in statuses:
            return "closed"
        if "early-close" in statuses:
            return "early-close"
        return "open"

    def recorded_window(self, candidate: date) -> tuple[str | None, str | None]:
        """Return the ``(open, close)`` instants the feed carried for a date.

        Both are ISO-8601 UTC strings exactly as Massive supplies them, and
        both are ``None`` unless the feed had an explicit window, which it only
        does for early-close dates.
        """

        for record in self._records.values():
            if record.date == candidate and (record.open_at or record.close_at):
                return record.open_at, record.close_at
        return None, None

    def session_hours(self, candidate: date) -> tuple[str | None, str | None, str]:
        """Return ``(open, close, source)`` for a date, always resolved.

        A consumer must never have to encode market hours itself, so the
        standard 09:30-16:00 Eastern session is filled in when the feed carried
        no override. ``source`` says which it was, so an early close is visible
        rather than inferred from the times.

        Returns ``(None, None, "closed")`` when the date is not a session.
        """

        if not self.is_session(candidate):
            return None, None, "closed"

        opens_at, closes_at = self.recorded_window(candidate)
        if opens_at and closes_at:
            return opens_at, closes_at, "early-close-record"

        return (
            _eastern_instant(candidate, STANDARD_OPEN),
            _eastern_instant(candidate, STANDARD_CLOSE),
            "standard",
        )

    def coverage(self) -> tuple[date, date] | None:
        """Return the first and last dates the cache holds, if any.

        Massive returns only upcoming records, so the cache is a window rather
        than a complete calendar. Exposing its bounds lets a consumer tell
        "no holiday on that date" from "that date predates what we know".
        """

        if not self._records:
            return None
        dates = [record.date for record in self._records.values()]
        return min(dates), max(dates)

    def merged_records(self) -> list[dict[str, object]]:
        """Return one entry per date, collapsing the per-exchange duplicates.

        NYSE and NASDAQ agree on every record Massive has returned so far. If
        they ever disagree, the more restrictive status wins, because trading
        against the exchange that is actually closed is the costly mistake.
        """

        ranked = {"open": 0, "early-close": 1, "closed": 2}
        by_date: dict[date, dict[str, object]] = {}
        for record in sorted(self._records.values(), key=lambda item: item.date):
            existing = by_date.get(record.date)
            if existing is None or ranked.get(record.status, 0) > ranked.get(
                str(existing["status"]), 0
            ):
                by_date[record.date] = {
                    "date": record.date,
                    "status": record.status,
                    "name": record.name,
                    "opens_at": record.open_at,
                    "closes_at": record.close_at,
                }
        return [by_date[key] for key in sorted(by_date)]

    def next_session_on_or_after(self, candidate: date) -> date:
        current = candidate
        while not self.is_session(current):
            current += timedelta(days=1)
        return current

    def next_session_after(self, candidate: date) -> date:
        return self.next_session_on_or_after(candidate + timedelta(days=1))

    def previous_session_on_or_before(self, candidate: date) -> date:
        current = candidate
        while not self.is_session(current):
            current -= timedelta(days=1)
        return current

    def sessions_between(self, start_exclusive: date, end_inclusive: date) -> list[date]:
        if end_inclusive <= start_exclusive:
            return []
        sessions: list[date] = []
        current = start_exclusive + timedelta(days=1)
        while current <= end_inclusive:
            if self.is_session(current):
                sessions.append(current)
            current += timedelta(days=1)
        return sessions

    @staticmethod
    def nominal_friday(session_date: date) -> date:
        return session_date + timedelta(days=4 - session_date.weekday())
