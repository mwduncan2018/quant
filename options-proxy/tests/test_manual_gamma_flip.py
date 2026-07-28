import json
from datetime import UTC, date, datetime
from pathlib import Path

import pytest

from manual_gamma_flip import ManualGammaFlipProvider

NOW = datetime(2026, 7, 27, 13, 0, tzinfo=UTC)
TRADING_DATE = date(2026, 7, 27)


def _write(
    path: Path,
    *,
    level: object = 601.25,
    trading_date: object = "2026-07-27",
) -> None:
    path.write_text(
        json.dumps(
            {
                "spy_gamma_flip": level,
                "trading_date": trading_date,
            }
        ),
        encoding="utf-8",
    )


def test_loads_valid_value_and_skips_unchanged_file(tmp_path: Path) -> None:
    path = tmp_path / "manual-options-inputs.json"
    _write(path)
    provider = ManualGammaFlipProvider(path)

    loaded = provider.poll(NOW)

    assert loaded is not None
    assert loaded.ticker == "SPY"
    assert loaded.spy_gamma_flip == 601.25
    assert loaded.trading_date == TRADING_DATE
    assert loaded.loaded_at == NOW
    assert provider.current_for(TRADING_DATE) == loaded
    assert provider.poll(NOW) is None
    assert provider.last_error is None


def test_malformed_update_retains_last_valid_value_and_can_recover(
    tmp_path: Path,
) -> None:
    path = tmp_path / "manual-options-inputs.json"
    _write(path, level=600.0)
    provider = ManualGammaFlipProvider(path)
    original = provider.poll(NOW)
    assert original is not None

    path.write_text("{", encoding="utf-8")
    assert provider.poll(NOW) is None
    assert provider.value == original
    assert provider.current_for(TRADING_DATE) == original
    assert provider.last_error is not None

    # A failed signature is not accepted, so the same partial file is retried.
    assert provider.poll(NOW) is None
    assert provider.last_error is not None

    _write(path, level=602.5)
    replacement = provider.poll(NOW)
    assert replacement is not None
    assert replacement.spy_gamma_flip == 602.5
    assert provider.value == replacement
    assert provider.last_error is None


def test_prior_day_value_is_retained_but_not_current(tmp_path: Path) -> None:
    path = tmp_path / "manual-options-inputs.json"
    _write(path, trading_date="2026-07-24")
    provider = ManualGammaFlipProvider(path)

    loaded = provider.poll(NOW)

    assert loaded is not None
    assert provider.value == loaded
    assert provider.current_for(TRADING_DATE) is None
    status = provider.status(TRADING_DATE)
    assert status["valid"] is False
    assert status["retaining_last_valid_value"] is True
    assert "expected 2026-07-27" in status["last_error"]


@pytest.mark.parametrize(
    ("level", "trading_date", "error_text"),
    [
        (0.0, "2026-07-27", "greater than zero"),
        (True, "2026-07-27", "must be a number"),
        (601.0, "07/27/2026", "YYYY-MM-DD"),
        (601.0, None, "YYYY-MM-DD"),
    ],
)
def test_rejects_invalid_manual_values(
    tmp_path: Path,
    level: object,
    trading_date: object,
    error_text: str,
) -> None:
    path = tmp_path / "manual-options-inputs.json"
    _write(path, level=level, trading_date=trading_date)
    provider = ManualGammaFlipProvider(path)

    assert provider.poll(NOW) is None
    assert provider.value is None
    assert provider.last_error is not None
    assert error_text in provider.last_error
