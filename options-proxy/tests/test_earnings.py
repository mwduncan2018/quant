import asyncio
import json
from datetime import UTC, date, datetime
from pathlib import Path

import pytest

from earnings import EarningsProvider, _parse_calendar

TRADING_DATE = date(2026, 7, 27)
TICKERS = ("AAPL", "MSFT", "NVDA")


class FakeTicker:
    """Stands in for yfinance.Ticker without touching the network."""

    def __init__(self, calendar: object = None, error: Exception | None = None) -> None:
        self._calendar = calendar
        self._error = error

    @property
    def calendar(self) -> object:
        if self._error is not None:
            raise self._error
        return self._calendar


def _factory(responses: dict[str, FakeTicker]):
    def build(ticker: str) -> FakeTicker:
        return responses[ticker]

    return build


def _provider(
    tmp_path: Path,
    responses: dict[str, FakeTicker],
    tickers: tuple[str, ...] = TICKERS,
    retention_days: int = 30,
) -> EarningsProvider:
    return EarningsProvider(
        tmp_path / "earnings-dates.json",
        tickers,
        ticker_factory=_factory(responses),
        retention_days=retention_days,
    )


def _calendar(*dates: date) -> dict[str, list[date]]:
    return {"Earnings Date": list(dates)}


def _refresh(provider: EarningsProvider, trading_date: date = TRADING_DATE) -> int:
    return asyncio.run(provider.refresh(trading_date))


def _entry(provider: EarningsProvider, ticker: str, trading_date: date = TRADING_DATE):
    return provider.snapshot(trading_date)["stocks"][ticker]


# ----------------------------------------------------------------------
# Fetching and resilience
# ----------------------------------------------------------------------


def test_resolves_every_ticker_and_writes_the_cache(tmp_path: Path) -> None:
    provider = _provider(
        tmp_path,
        {
            "AAPL": FakeTicker(_calendar(date(2026, 7, 30))),
            "MSFT": FakeTicker(_calendar(date(2026, 8, 4))),
            "NVDA": FakeTicker(_calendar(date(2026, 8, 26))),
        },
    )

    assert _refresh(provider) == 3
    assert provider.missing_tickers() == []
    assert _entry(provider, "AAPL")["next_earnings_date"] == date(2026, 7, 30)

    payload = json.loads((tmp_path / "earnings-dates.json").read_text(encoding="utf-8"))
    assert [item["ticker"] for item in payload["stocks"]] == ["AAPL", "MSFT", "NVDA"]
    assert payload["stocks"][0]["dates"] == ["2026-07-30"]


def test_one_failing_ticker_does_not_discard_the_others(tmp_path: Path) -> None:
    # The script this replaces called sys.exit(1) on any single failure, so one
    # flaky symbol lost the whole universe.
    provider = _provider(
        tmp_path,
        {
            "AAPL": FakeTicker(_calendar(date(2026, 7, 30))),
            "MSFT": FakeTicker(error=RuntimeError("rate limited")),
            "NVDA": FakeTicker(_calendar(date(2026, 8, 26))),
        },
    )

    assert _refresh(provider) == 2
    assert provider.missing_tickers() == ["MSFT"]

    entry = _entry(provider, "MSFT")
    assert entry["valid"] is False
    assert entry["next_earnings_date"] is None
    assert "rate limited" in entry["last_error"]
    assert provider.snapshot(TRADING_DATE)["coverage"]["valid"] == 2


def test_a_failed_refresh_retains_the_previous_value(tmp_path: Path) -> None:
    responses = {ticker: FakeTicker(_calendar(date(2026, 7, 30))) for ticker in TICKERS}
    provider = _provider(tmp_path, responses)
    _refresh(provider)

    responses["AAPL"] = FakeTicker(error=RuntimeError("upstream 503"))
    _refresh(provider, date(2026, 7, 28))

    assert _entry(provider, "AAPL", date(2026, 7, 28))["next_earnings_date"] == date(
        2026, 7, 30
    )
    assert provider.missing_tickers() == []


def test_a_total_failure_is_retried_next_cycle(tmp_path: Path) -> None:
    responses = {
        ticker: FakeTicker(error=RuntimeError("no route to host")) for ticker in TICKERS
    }
    provider = _provider(tmp_path, responses)

    assert _refresh(provider) == 0
    assert provider.needs_refresh(TRADING_DATE) is True
    assert provider.last_error == "No earnings dates could be resolved"

    for ticker in TICKERS:
        responses[ticker] = FakeTicker(_calendar(date(2026, 8, 4)))
    assert _refresh(provider) == 3
    assert provider.needs_refresh(TRADING_DATE) is False


def test_a_permanently_unresolvable_ticker_does_not_loop_forever(tmp_path: Path) -> None:
    provider = _provider(
        tmp_path,
        {
            "AAPL": FakeTicker(_calendar(date(2026, 7, 30))),
            "MSFT": FakeTicker(_calendar(date(2026, 8, 4))),
            "NVDA": FakeTicker(None),
        },
    )

    _refresh(provider)

    assert provider.needs_refresh(TRADING_DATE) is False
    assert provider.missing_tickers() == ["NVDA"]


# ----------------------------------------------------------------------
# Retention: reported versus rescheduled
# ----------------------------------------------------------------------


def test_a_reported_date_is_retained_when_the_source_rolls_forward(
    tmp_path: Path,
) -> None:
    # The case this exists for. Some tickers jump to next quarter the moment
    # they report, which would otherwise leave a strategy with nothing to
    # measure the day after against.
    responses = {ticker: FakeTicker(_calendar(date(2026, 7, 30))) for ticker in TICKERS}
    provider = _provider(tmp_path, responses)
    _refresh(provider, date(2026, 7, 29))

    # The report happens, then the source rolls to next quarter.
    after = date(2026, 7, 31)
    for ticker in TICKERS:
        responses[ticker] = FakeTicker(_calendar(date(2026, 10, 29)))
    _refresh(provider, after)

    entry = _entry(provider, "AAPL", after)
    assert entry["last_earnings_date"] == date(2026, 7, 30)
    assert entry["next_earnings_date"] == date(2026, 10, 29)


def test_a_rescheduled_future_date_is_discarded(tmp_path: Path) -> None:
    # Nothing happened on the original date, so retaining it would black out an
    # ordinary trading day.
    responses = {ticker: FakeTicker(_calendar(date(2026, 7, 30))) for ticker in TICKERS}
    provider = _provider(tmp_path, responses)
    _refresh(provider)

    for ticker in TICKERS:
        responses[ticker] = FakeTicker(_calendar(date(2026, 8, 6)))
    _refresh(provider, date(2026, 7, 28))

    entry = _entry(provider, "AAPL", date(2026, 7, 28))
    assert entry["next_earnings_date"] == date(2026, 8, 6)
    assert entry["last_earnings_date"] is None, "the moved date never occurred"
    assert provider.dates_for("AAPL").observed == (date(2026, 8, 6),)


def test_a_lingering_past_date_is_reported_as_the_last_one(tmp_path: Path) -> None:
    # Other tickers keep showing the date that just passed. It should read as
    # the last report, not as an upcoming one.
    responses = {ticker: FakeTicker(_calendar(date(2026, 7, 22))) for ticker in TICKERS}
    provider = _provider(tmp_path, responses)

    _refresh(provider, TRADING_DATE)

    entry = _entry(provider, "AAPL")
    assert entry["last_earnings_date"] == date(2026, 7, 22)
    assert entry["next_earnings_date"] is None
    assert entry["valid"] is True, "a known past report is still knowledge"


def test_a_past_date_survives_alongside_a_new_future_one(tmp_path: Path) -> None:
    responses = {ticker: FakeTicker(_calendar(date(2026, 7, 22))) for ticker in TICKERS}
    provider = _provider(tmp_path, responses)
    _refresh(provider)

    for ticker in TICKERS:
        responses[ticker] = FakeTicker(_calendar(date(2026, 10, 21)))
    _refresh(provider, date(2026, 7, 28))

    assert provider.dates_for("AAPL").observed == (date(2026, 7, 22), date(2026, 10, 21))


def test_a_past_date_is_deleted_once_it_passes_the_retention_window(
    tmp_path: Path,
) -> None:
    responses = {ticker: FakeTicker(_calendar(date(2026, 7, 22))) for ticker in TICKERS}
    provider = _provider(tmp_path, responses, retention_days=30)
    _refresh(provider)

    for ticker in TICKERS:
        responses[ticker] = FakeTicker(_calendar(date(2026, 10, 21)))

    # 29 days after the report it is still held.
    _refresh(provider, date(2026, 8, 20))
    assert _entry(provider, "AAPL", date(2026, 8, 20))["last_earnings_date"] == date(
        2026, 7, 22
    )

    # 31 days after, it is gone.
    _refresh(provider, date(2026, 8, 22))
    assert _entry(provider, "AAPL", date(2026, 8, 22))["last_earnings_date"] is None
    assert provider.dates_for("AAPL").observed == (date(2026, 10, 21),)


def test_expiry_applies_to_tickers_that_failed_this_cycle(tmp_path: Path) -> None:
    # A stale date must not outlive its window just because its lookup broke.
    responses = {ticker: FakeTicker(_calendar(date(2026, 7, 22))) for ticker in TICKERS}
    provider = _provider(tmp_path, responses, retention_days=30)
    _refresh(provider)

    responses["AAPL"] = FakeTicker(error=RuntimeError("still failing"))
    _refresh(provider, date(2026, 8, 22))

    assert provider.dates_for("AAPL") is None
    assert _entry(provider, "AAPL", date(2026, 8, 22))["valid"] is False


def test_a_non_positive_retention_window_is_rejected(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="at least one day"):
        EarningsProvider(tmp_path / "e.json", TICKERS, retention_days=0)


# ----------------------------------------------------------------------
# Cache
# ----------------------------------------------------------------------


def test_cached_dates_survive_a_restart(tmp_path: Path) -> None:
    responses = {ticker: FakeTicker(_calendar(date(2026, 7, 30))) for ticker in TICKERS}
    _refresh(_provider(tmp_path, responses))

    restarted = _provider(tmp_path, responses)

    assert restarted.load_error is None
    assert restarted.has_cache is True
    assert restarted.dates_for("MSFT").observed == (date(2026, 7, 30),)
    assert restarted.needs_refresh(TRADING_DATE) is True, (
        "a restart must still re-check the session rather than trust the cache blindly"
    )


def test_a_cache_written_before_retention_still_loads(tmp_path: Path) -> None:
    path = tmp_path / "earnings-dates.json"
    path.write_text(
        json.dumps(
            {
                "fetched_at": datetime.now(UTC).isoformat(),
                "stocks": [
                    {
                        "ticker": "AAPL",
                        "next_earnings_date": "2026-07-30",
                        "fetched_at": datetime.now(UTC).isoformat(),
                    }
                ],
            }
        ),
        encoding="utf-8",
    )

    provider = EarningsProvider(path, TICKERS, ticker_factory=_factory({}))

    assert provider.load_error is None
    assert provider.dates_for("AAPL").observed == (date(2026, 7, 30),)


def test_a_corrupt_cache_is_reported_and_not_partially_applied(tmp_path: Path) -> None:
    path = tmp_path / "earnings-dates.json"
    path.write_text(
        json.dumps(
            {
                "fetched_at": datetime.now(UTC).isoformat(),
                "stocks": [
                    {
                        "ticker": "AAPL",
                        "dates": ["2026-07-30"],
                        "fetched_at": datetime.now(UTC).isoformat(),
                    },
                    {"ticker": "MSFT", "dates": ["not-a-date"]},
                ],
            }
        ),
        encoding="utf-8",
    )

    provider = EarningsProvider(path, TICKERS, ticker_factory=_factory({}))

    assert provider.load_error is not None
    assert provider.has_cache is False, "a half-read cache must not be served"
    assert provider.missing_tickers() == list(TICKERS)


def test_a_missing_cache_is_not_an_error(tmp_path: Path) -> None:
    provider = EarningsProvider(
        tmp_path / "absent.json", TICKERS, ticker_factory=_factory({})
    )

    assert provider.load_error is None
    assert provider.has_cache is False


def test_an_empty_universe_is_rejected(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="at least one ticker"):
        EarningsProvider(tmp_path / "earnings.json", ())


def test_the_snapshot_lists_every_configured_ticker(tmp_path: Path) -> None:
    provider = _provider(tmp_path, {ticker: FakeTicker(None) for ticker in TICKERS})
    _refresh(provider)

    snapshot = provider.snapshot(TRADING_DATE)

    assert set(snapshot["stocks"]) == set(TICKERS)
    assert all(entry["valid"] is False for entry in snapshot["stocks"].values())
    assert snapshot["coverage"]["missing_tickers"] == list(TICKERS)
    assert snapshot["trading_date"] == TRADING_DATE
    assert snapshot["retention_days"] == 30


def test_ticker_casing_is_normalized(tmp_path: Path) -> None:
    provider = _provider(tmp_path, {ticker: FakeTicker(_calendar(date(2026, 7, 30)))
                                    for ticker in TICKERS})
    _refresh(provider)

    assert provider.dates_for("aapl") is not None


# ----------------------------------------------------------------------
# Calendar parsing
# ----------------------------------------------------------------------


class TestCalendarParsing:
    def test_a_single_date_is_read(self) -> None:
        assert _parse_calendar(_calendar(date(2026, 7, 30))) == date(2026, 7, 30)

    def test_a_range_reports_the_earliest(self) -> None:
        # A consumer avoiding a report should err early rather than late.
        assert _parse_calendar(
            _calendar(date(2026, 8, 4), date(2026, 7, 30))
        ) == date(2026, 7, 30)

    def test_datetimes_and_iso_strings_are_accepted(self) -> None:
        assert _parse_calendar(
            {"Earnings Date": [datetime(2026, 7, 30, 12, 0, tzinfo=UTC)]}
        ) == date(2026, 7, 30)
        assert _parse_calendar({"Earnings Date": ["2026-07-30"]}) == date(2026, 7, 30)

    @pytest.mark.parametrize(
        "calendar",
        [None, {}, {"Earnings Date": None}, {"Earnings Date": []}, {"Other": [1]}],
        ids=["none", "empty", "null-date", "empty-list", "wrong-key"],
    )
    def test_unusable_shapes_report_no_data(self, calendar: object) -> None:
        assert _parse_calendar(calendar) is None

    def test_unparseable_entries_are_not_guessed_at(self) -> None:
        assert _parse_calendar({"Earnings Date": ["soon", object()]}) is None
