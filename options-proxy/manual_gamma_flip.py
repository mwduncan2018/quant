"""Hot-reload a manually supplied SPY gamma-flip level from JSON."""

from __future__ import annotations

import json
import math
from datetime import UTC, date, datetime
from pathlib import Path
from typing import Any

from models import SpyGammaFlip


class ManualGammaFlipError(ValueError):
    """Raised when the manual gamma-flip file cannot be accepted."""


class ManualGammaFlipProvider:
    """Poll a small JSON file while retaining the last accepted value."""

    def __init__(self, path: Path, ticker: str = "SPY") -> None:
        self.path = Path(path)
        self.ticker = ticker.strip().upper()
        self.value: SpyGammaFlip | None = None
        self.last_error: str | None = None
        self.last_checked_at: datetime | None = None
        self.last_loaded_at: datetime | None = None
        self._accepted_signature: tuple[int, int] | None = None

    def poll(self, now: datetime | None = None) -> SpyGammaFlip | None:
        """Read a changed file and return a newly accepted value, if any."""

        checked_at = _utc_now(now)
        self.last_checked_at = checked_at

        try:
            before = self.path.stat()
            signature = (before.st_mtime_ns, before.st_size)
        except OSError as exc:
            self.last_error = f"Manual gamma-flip file is unavailable: {exc}"
            return None

        if signature == self._accepted_signature and self.last_error is None:
            return None

        try:
            raw = self.path.read_text(encoding="utf-8")
            after = self.path.stat()
            after_signature = (after.st_mtime_ns, after.st_size)
            if after_signature != signature:
                raise ManualGammaFlipError(
                    "Manual gamma-flip file changed while it was being read"
                )
            parsed = json.loads(raw)
            value = _parse_value(parsed, self.ticker, checked_at)
        except (OSError, UnicodeError, json.JSONDecodeError, ManualGammaFlipError) as exc:
            # Do not accept the failed signature. The next one-second poll retries it,
            # which safely handles editors that briefly expose a partial write.
            self.last_error = str(exc)
            return None

        self.value = value
        self.last_error = None
        self.last_loaded_at = checked_at
        self._accepted_signature = after_signature
        return value

    def current_for(self, trading_date: date) -> SpyGammaFlip | None:
        """Return the last accepted value only for its trading session."""

        if self.value is None:
            return None
        if self.value.trading_date != trading_date:
            return None
        return self.value

    def status(self, trading_date: date | None) -> dict[str, Any]:
        """Return JSON-compatible diagnostic state."""

        current = self.current_for(trading_date) if trading_date else None
        stale_reason: str | None = None
        if self.value is not None and trading_date is not None and current is None:
            stale_reason = (
                f"Manual gamma flip is for "
                f"{self.value.trading_date}, expected {trading_date}"
            )
        return {
            "path": str(self.path),
            "valid": current is not None,
            "ticker": self.value.ticker if self.value else self.ticker,
            "spy_gamma_flip": current.spy_gamma_flip if current else None,
            "trading_date": current.trading_date if current else None,
            "loaded_at": current.loaded_at if current else None,
            "last_checked_at": self.last_checked_at,
            "last_loaded_at": self.last_loaded_at,
            "last_error": self.last_error or stale_reason,
            "retaining_last_valid_value": self.value is not None,
        }


def _parse_value(payload: Any, ticker: str, now: datetime) -> SpyGammaFlip:
    if not isinstance(payload, dict):
        raise ManualGammaFlipError("Manual gamma-flip JSON must be an object")

    raw_level = payload.get("spy_gamma_flip")
    if isinstance(raw_level, bool) or not isinstance(raw_level, (int, float)):
        raise ManualGammaFlipError("spy_gamma_flip must be a number")
    try:
        level = float(raw_level)
    except OverflowError as exc:
        raise ManualGammaFlipError("spy_gamma_flip is too large") from exc
    if not math.isfinite(level) or level <= 0.0:
        raise ManualGammaFlipError("spy_gamma_flip must be finite and greater than zero")

    raw_trading_date = payload.get("trading_date")
    if not isinstance(raw_trading_date, str) or not raw_trading_date.strip():
        raise ManualGammaFlipError("trading_date must use YYYY-MM-DD format")
    normalized_trading_date = raw_trading_date.strip()
    try:
        trading_date = date.fromisoformat(normalized_trading_date)
    except ValueError as exc:
        raise ManualGammaFlipError(
            "trading_date must use YYYY-MM-DD format"
        ) from exc
    if trading_date.isoformat() != normalized_trading_date:
        raise ManualGammaFlipError("trading_date must use YYYY-MM-DD format")

    return SpyGammaFlip(
        ticker=ticker,
        spy_gamma_flip=level,
        trading_date=trading_date,
        loaded_at=now,
    )


def _utc_now(value: datetime | None) -> datetime:
    if value is None:
        return datetime.now(UTC)
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("now must be timezone-aware")
    return value.astimezone(UTC)
