import pytest

from config import Settings


def _settings(tickers: str) -> Settings:
    return Settings(_env_file=None, massive_api_key="test", tickers=tickers)


def test_ticker_symbols_are_normalized_and_deduplicated() -> None:
    settings = _settings(" spy , aapl,SPY ,msft,AAPL ")

    assert settings.ticker_symbols == ("SPY", "AAPL", "MSFT")


def test_ticker_symbols_ignore_blank_entries() -> None:
    settings = _settings("AAPL,,  ,MSFT,")

    assert settings.ticker_symbols == ("AAPL", "MSFT")


def test_single_ticker_is_supported() -> None:
    assert _settings("SPY").ticker_symbols == ("SPY",)


@pytest.mark.parametrize("tickers", ["", "   ", ",", " , , "])
def test_empty_universe_is_rejected(tickers: str) -> None:
    with pytest.raises(ValueError, match="at least one ticker"):
        _settings(tickers).ticker_symbols
