"""Session status, early-close windows, and cache coverage.

The fixtures mirror the shape Massive actually returns: both exchanges carry
identical records, and only early-close dates include an open/close window.
"""

from __future__ import annotations

from datetime import date
from pathlib import Path

import pytest

from market_calendar import MarketCalendar
from models import MarketHoliday

# 2026-11-26 Thanksgiving (closed), 2026-11-27 early close at 13:00 EST.
THANKSGIVING = date(2026, 11, 26)
BLACK_FRIDAY = date(2026, 11, 27)
EARLY_CLOSE_UTC = "2026-11-27T18:00:00.000Z"
EARLY_OPEN_UTC = "2026-11-27T14:30:00.000Z"


def _calendar(tmp_path: Path) -> MarketCalendar:
    calendar = MarketCalendar(tmp_path / "market_holidays.json")
    calendar.merge_and_save(
        [
            MarketHoliday(THANKSGIVING, exchange, "closed", "Thanksgiving")
            for exchange in ("NYSE", "NASDAQ")
        ]
        + [
            MarketHoliday(
                BLACK_FRIDAY,
                exchange,
                "early-close",
                "Thanksgiving",
                open_at=EARLY_OPEN_UTC,
                close_at=EARLY_CLOSE_UTC,
            )
            for exchange in ("NYSE", "NASDAQ")
        ]
    )
    return calendar


def test_a_full_closure_reports_closed(tmp_path: Path) -> None:
    calendar = _calendar(tmp_path)

    assert calendar.status_on(THANKSGIVING) == "closed"
    assert calendar.is_session(THANKSGIVING) is False


def test_an_early_close_is_still_a_session(tmp_path: Path) -> None:
    # The whole reason this endpoint exists: an early-close date passes
    # is_session, so a consumer assuming a 16:00 close schedules exits into a
    # shut market.
    calendar = _calendar(tmp_path)

    assert calendar.is_session(BLACK_FRIDAY) is True
    assert calendar.status_on(BLACK_FRIDAY) == "early-close"
    assert calendar.session_hours(BLACK_FRIDAY) == (
        EARLY_OPEN_UTC,
        EARLY_CLOSE_UTC,
        "early-close-record",
    )


def test_an_ordinary_weekday_resolves_to_standard_hours(tmp_path: Path) -> None:
    # A consumer must never encode market hours itself, so the standard session
    # is filled in rather than reported as an absent window.
    calendar = _calendar(tmp_path)
    ordinary = date(2026, 11, 30)

    opens_at, closes_at, source = calendar.session_hours(ordinary)

    assert source == "standard"
    assert calendar.recorded_window(ordinary) == (None, None)
    # 09:30 and 16:00 Eastern in EST.
    assert opens_at == "2026-11-30T14:30:00.000Z"
    assert closes_at == "2026-11-30T21:00:00.000Z"


def test_standard_hours_follow_daylight_saving(tmp_path: Path) -> None:
    # A hardcoded UTC offset would put every summer session an hour wrong.
    calendar = _calendar(tmp_path)

    _, summer_close, _ = calendar.session_hours(date(2026, 7, 27))
    _, winter_close, _ = calendar.session_hours(date(2026, 11, 30))

    assert summer_close == "2026-07-27T20:00:00.000Z", "16:00 EDT"
    assert winter_close == "2026-11-30T21:00:00.000Z", "16:00 EST"


def test_a_closed_date_resolves_to_no_hours(tmp_path: Path) -> None:
    calendar = _calendar(tmp_path)

    assert calendar.session_hours(THANKSGIVING) == (None, None, "closed")
    assert calendar.session_hours(date(2026, 11, 28)) == (None, None, "closed")


def test_weekends_report_closed_without_a_record(tmp_path: Path) -> None:
    calendar = _calendar(tmp_path)

    assert calendar.status_on(date(2026, 11, 28)) == "closed"
    assert calendar.status_on(date(2026, 11, 29)) == "closed"


def test_coverage_reports_the_window_the_cache_holds(tmp_path: Path) -> None:
    calendar = _calendar(tmp_path)

    assert calendar.coverage() == (THANKSGIVING, BLACK_FRIDAY)


def test_coverage_is_absent_without_a_cache(tmp_path: Path) -> None:
    assert MarketCalendar(tmp_path / "absent.json").coverage() is None


def test_records_collapse_to_one_entry_per_date(tmp_path: Path) -> None:
    calendar = _calendar(tmp_path)

    records = calendar.merged_records()

    assert [item["date"] for item in records] == [THANKSGIVING, BLACK_FRIDAY]
    assert records[1]["closes_at"] == EARLY_CLOSE_UTC
    assert records[0]["closes_at"] is None


def test_the_more_restrictive_status_wins_a_disagreement(tmp_path: Path) -> None:
    # Both exchanges have agreed on every record Massive has returned. If they
    # ever diverge, trading against the one that is shut is the costly error.
    calendar = MarketCalendar(tmp_path / "market_holidays.json")
    calendar.merge_and_save(
        [
            MarketHoliday(BLACK_FRIDAY, "NYSE", "early-close", "x", close_at=EARLY_CLOSE_UTC),
            MarketHoliday(BLACK_FRIDAY, "NASDAQ", "closed", "x"),
        ]
    )

    assert calendar.status_on(BLACK_FRIDAY) == "closed"
    assert calendar.merged_records()[0]["status"] == "closed"


@pytest.mark.parametrize(
    ("candidate", "expected"),
    [
        (date(2026, 11, 25), date(2026, 11, 25)),
        (THANKSGIVING, date(2026, 11, 25)),
        (date(2026, 11, 29), BLACK_FRIDAY),
    ],
    ids=["ordinary", "holiday-walks-back", "weekend-walks-back"],
)
def test_previous_session_skips_closures(
    tmp_path: Path, candidate: date, expected: date
) -> None:
    assert _calendar(tmp_path).previous_session_on_or_before(candidate) == expected
