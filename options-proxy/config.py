"""Application configuration for the options proxy."""

from __future__ import annotations

from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Configuration loaded from environment variables or ``.env``."""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    massive_api_key: str = ""
    massive_base_url: str = "https://api.massive.com"
    tickers: str = "SPY"

    rest_concurrency: int = 5
    rest_max_retries: int = 2
    rest_rate_limit_enabled: bool = True
    rest_rate_limit_requests: int = 5
    rest_rate_limit_period_seconds: float = 60.0
    holiday_cache_path: Path = Path("data/market_holidays.json")
    manual_gamma_flip_path: Path = Path("data/manual-options-inputs.json")
    manual_gamma_flip_poll_interval_seconds: float = 1.0

    # Earnings dates come from yfinance and consume no Massive quota. The
    # universe is refreshed once per trading session; the check interval only
    # governs how soon a failed attempt is retried.
    earnings_enabled: bool = True
    earnings_cache_path: Path = Path("data/earnings-dates.json")
    earnings_check_interval_seconds: float = 300.0
    # yfinance rate-limits by address. One pass over the universe happens once
    # a session, so spacing the calls costs nothing that matters.
    earnings_request_spacing_seconds: float = 0.5
    # How long a report date is kept after it passes. A strategy avoiding the
    # day after a report needs it, and the upstream source stops reporting it
    # inconsistently. One market day would do; a month costs nothing and makes
    # a skipped entry explainable afterwards.
    earnings_retention_days: int = 30

    implied_move_multiplier: float = 0.85
    atm_strike_window_pct: float = 0.10
    calculation_check_interval_seconds: float = 60.0

    udp_host: str = "127.0.0.1"
    udp_port: int = 5005
    udp_mtu: int = 1400
    broadcast_interval_ms: int = 1000

    host: str = "0.0.0.0"
    port: int = 8000
    log_level: str = "info"

    # Retained for the parked WebSocket client. The Static Daily Implied
    # Move does not start or depend on a WebSocket connection.
    massive_ws_url: str = "wss://socket.massive.com/options"
    max_ws_subscriptions: int = 1000
    ws_reconnect_base_s: float = 1.0
    ws_reconnect_max_s: float = 30.0

    @property
    def ticker_symbols(self) -> tuple[str, ...]:
        """Return the configured ticker universe in stable, normalized order."""

        symbols = tuple(
            dict.fromkeys(
                symbol.strip().upper()
                for symbol in self.tickers.split(",")
                if symbol.strip()
            )
        )
        if not symbols:
            raise ValueError("TICKERS must contain at least one ticker")
        return symbols


cfg = Settings()
