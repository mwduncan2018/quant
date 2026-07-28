"""UDP transport for the options-proxy Protocol Buffer frame."""

from __future__ import annotations

import asyncio
import socket
import time
from datetime import date

from config import Settings, cfg
from models import SpyGammaFlip, StaticDailyImpliedMove
from proto.market_state_pb2 import IndicatorFrame


class UdpBroadcaster:
    """Serialize and send independently valid options indicators."""

    def __init__(self, settings: Settings = cfg) -> None:
        self.settings = settings
        self._socket: socket.socket | None = None
        self._sequence = 0
        self._sent = 0
        self._errors = 0

    def open(self) -> None:
        if self._socket is None:
            self._socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self._socket.setblocking(False)

    def close(self) -> None:
        if self._socket is not None:
            self._socket.close()
            self._socket = None

    def serialize(
        self,
        ticker: str,
        trading_date: date,
        implied_move: StaticDailyImpliedMove | None,
        gamma_flip: SpyGammaFlip | None,
    ) -> bytes:
        ticker = ticker.strip().upper()
        if not ticker:
            raise ValueError("ticker must not be blank")
        if implied_move is not None and (
            implied_move.ticker != ticker
            or implied_move.trading_date != trading_date
        ):
            raise ValueError("Static Daily Implied Move does not match the frame")
        if gamma_flip is not None and gamma_flip.trading_date != trading_date:
            raise ValueError("SPY Gamma Flip does not match the frame trading date")

        self._sequence += 1
        message = IndicatorFrame(
            sequence=self._sequence,
            emitted_at_unix_ms=time.time_ns() // 1_000_000,
            ticker=ticker,
            trading_date=trading_date.isoformat(),
            static_daily_implied_move=(
                implied_move.static_daily_implied_move
                if implied_move is not None
                else 0.0
            ),
            static_daily_implied_move_valid=implied_move is not None,
            spy_gamma_flip=(
                gamma_flip.spy_gamma_flip if gamma_flip is not None else 0.0
            ),
            spy_gamma_flip_valid=gamma_flip is not None,
        )
        payload = message.SerializeToString()
        if len(payload) > self.settings.udp_mtu:
            raise ValueError(
                f"Serialized IndicatorFrame is {len(payload)} bytes; "
                f"UDP_MTU is {self.settings.udp_mtu}"
            )
        return payload

    async def broadcast(
        self,
        ticker: str,
        trading_date: date,
        implied_move: StaticDailyImpliedMove | None,
        gamma_flip: SpyGammaFlip | None,
    ) -> None:
        if self._socket is None:
            self.open()
        assert self._socket is not None
        payload = self.serialize(ticker, trading_date, implied_move, gamma_flip)
        try:
            loop = asyncio.get_running_loop()
            await loop.sock_sendto(
                self._socket,
                payload,
                (self.settings.udp_host, self.settings.udp_port),
            )
            self._sent += 1
        except OSError:
            self._errors += 1
            raise

    def stats(self) -> dict[str, int]:
        return {
            "sent": self._sent,
            "errors": self._errors,
            "sequence": self._sequence,
        }
