from datetime import UTC, date, datetime

from market_calendar import MarketCalendar
from models import MarketHoliday


def test_cache_round_trip_and_session_rules(tmp_path) -> None:
    cache_path = tmp_path / "market_holidays.json"
    calendar = MarketCalendar(cache_path)
    calendar.merge_and_save(
        [
            MarketHoliday(
                date=date(2026, 7, 3),
                exchange="NYSE",
                status="closed",
                name="Independence Day",
            ),
            MarketHoliday(
                date=date(2026, 11, 27),
                exchange="NASDAQ",
                status="early-close",
                name="Thanksgiving",
            ),
        ],
        fetched_at=datetime(2026, 1, 1, tzinfo=UTC),
    )

    reloaded = MarketCalendar(cache_path)
    assert not reloaded.is_session(date(2026, 7, 3))
    assert not reloaded.is_session(date(2026, 7, 4))
    assert reloaded.is_session(date(2026, 11, 27))
    assert reloaded.fetched_at == datetime(2026, 1, 1, tzinfo=UTC)


def test_session_count_excludes_weekends_and_closed_holidays(tmp_path) -> None:
    calendar = MarketCalendar(tmp_path / "holidays.json")
    calendar.merge_and_save(
        [MarketHoliday(date(2026, 7, 23), "NYSE", "closed", "Test holiday")]
    )

    sessions = calendar.sessions_between(date(2026, 7, 20), date(2026, 7, 24))
    assert sessions == [
        date(2026, 7, 21),
        date(2026, 7, 22),
        date(2026, 7, 24),
    ]


def test_closed_friday_moves_expiration_session_to_thursday(tmp_path) -> None:
    calendar = MarketCalendar(tmp_path / "holidays.json")
    calendar.merge_and_save(
        [MarketHoliday(date(2026, 4, 3), "NYSE", "closed", "Good Friday")]
    )

    nominal_friday = calendar.nominal_friday(date(2026, 4, 2))
    assert nominal_friday == date(2026, 4, 3)
    assert calendar.previous_session_on_or_before(nominal_friday) == date(2026, 4, 2)


def test_corrupt_cache_can_be_replaced_by_a_fresh_response(tmp_path) -> None:
    cache_path = tmp_path / "holidays.json"
    cache_path.write_text("not-json", encoding="utf-8")

    calendar = MarketCalendar(cache_path)
    assert calendar.load_error is not None
    assert not calendar.has_cache

    calendar.merge_and_save(
        [MarketHoliday(date(2026, 12, 25), "NYSE", "closed", "Christmas")]
    )
    assert MarketCalendar(cache_path).load_error is None
