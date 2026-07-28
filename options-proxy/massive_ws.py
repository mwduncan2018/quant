"""
options-proxy Massive WebSocket client.

Maintains a persistent connection to ``wss://socket.massive.com/options``,
handles authentication, dynamic subscribe/unsubscribe for up to ~50
active contracts, routes inbound ``Q.`` and ``T.`` events to the
appropriate handlers, and auto-reconnects with exponential backoff.
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
from typing import Any, Awaitable, Callable

import websockets
import websockets.exceptions

from config import cfg

logger = logging.getLogger(__name__)

# Type alias for message handlers
QuoteHandler = Callable[[dict[str, Any]], Awaitable[None]]
TradeHandler = Callable[[dict[str, Any]], Awaitable[None]]


class MassiveWebSocket:
    """
    Manages a single Massive Options WebSocket connection.

    Capabilities:
      • Auth on connect
      • Dynamic subscribe / unsubscribe (tracks active set)
      • Routes Q. and T. events to registered handlers
      • Auto-reconnect with capped exponential backoff
      • Heartbeat monitoring
    """

    def __init__(
        self,
        on_quote: QuoteHandler | None = None,
        on_trade: TradeHandler | None = None,
    ) -> None:
        self._ws: Any = None
        self._active_subs: set[str] = set()   # e.g. {"O:SPY250725C00550000", ...}
        self._on_quote = on_quote
        self._on_trade = on_trade
        self._running = False
        self._reconnect_task: asyncio.Task[None] | None = None
        self._last_msg_time: float = 0.0

    # ────────────────────────────────────────────────────────────────
    #  Lifecycle
    # ────────────────────────────────────────────────────────────────

    async def start(self) -> None:
        """Start the WebSocket listener loop in the background."""
        self._running = True
        self._reconnect_task = asyncio.create_task(self._connection_loop())

    async def stop(self) -> None:
        """Gracefully shut down."""
        self._running = False
        if self._ws:
            await self._ws.close()
        if self._reconnect_task:
            self._reconnect_task.cancel()
            try:
                await self._reconnect_task
            except asyncio.CancelledError:
                pass

    @property
    def active_subscriptions(self) -> set[str]:
        return set(self._active_subs)

    # ────────────────────────────────────────────────────────────────
    #  Connection loop with auto-reconnect
    # ────────────────────────────────────────────────────────────────

    async def _connection_loop(self) -> None:
        backoff = cfg.ws_reconnect_base_s
        while self._running:
            try:
                await self._connect_and_listen()
            except (
                websockets.exceptions.ConnectionClosed,
                websockets.exceptions.WebSocketException,
                OSError,
            ) as exc:
                if not self._running:
                    return
                logger.warning(
                    "WebSocket disconnected (%s), reconnecting in %.1fs",
                    exc, backoff,
                )
                await asyncio.sleep(backoff)
                backoff = min(backoff * 2, cfg.ws_reconnect_max_s)
            except asyncio.CancelledError:
                return
            except Exception:
                if not self._running:
                    return
                logger.exception("Unexpected WS error, reconnecting in %.1fs", backoff)
                await asyncio.sleep(backoff)
                backoff = min(backoff * 2, cfg.ws_reconnect_max_s)
            else:
                backoff = cfg.ws_reconnect_base_s  # reset on clean disconnect

    async def _connect_and_listen(self) -> None:
        logger.info("Connecting to %s", cfg.massive_ws_url)

        async with websockets.connect(
            cfg.massive_ws_url,
            ping_interval=20,
            ping_timeout=10,
            close_timeout=5,
            max_size=2**20,  # 1 MiB max frame
        ) as ws:
            self._ws = ws
            self._last_msg_time = time.monotonic()

            # ── Auth ────────────────────────────────────────────────
            auth_msg = json.dumps({"action": "auth", "params": cfg.massive_api_key})
            await ws.send(auth_msg)
            auth_resp = await ws.recv()
            logger.info("WS auth response: %s", str(auth_resp)[:200])

            # ── Re-subscribe to anything we had before reconnect ────
            if self._active_subs:
                await self._send_subscribe(list(self._active_subs))

            # ── Listen loop ─────────────────────────────────────────
            async for raw in ws:
                self._last_msg_time = time.monotonic()
                try:
                    messages = json.loads(raw)
                    if isinstance(messages, dict):
                        messages = [messages]
                    for msg in messages:
                        await self._dispatch(msg)
                except json.JSONDecodeError:
                    logger.warning("WS non-JSON message: %s", str(raw)[:100])

    # ────────────────────────────────────────────────────────────────
    #  Message dispatch
    # ────────────────────────────────────────────────────────────────

    async def _dispatch(self, msg: dict[str, Any]) -> None:
        ev = msg.get("ev", "")
        if ev == "Q" and self._on_quote:
            await self._on_quote(msg)
        elif ev == "T" and self._on_trade:
            await self._on_trade(msg)
        elif ev == "status":
            logger.debug("WS status: %s", msg.get("message", ""))
        elif ev == "AM" or ev == "A":
            pass  # aggregate bars — not used
        else:
            logger.debug("WS unhandled event: %s", ev)

    # ────────────────────────────────────────────────────────────────
    #  Dynamic Subscription Management
    # ────────────────────────────────────────────────────────────────

    async def subscribe(self, contract_symbols: list[str]) -> None:
        """
        Subscribe to quotes and trades for the given contract symbols.
        Symbols should be in Massive option format: ``O:SPY250725C00550000``
        """
        new_symbols = [s for s in contract_symbols if s not in self._active_subs]
        if not new_symbols:
            return

        # Enforce max subscription limit
        available_slots = cfg.max_ws_subscriptions - len(self._active_subs)
        if available_slots <= 0:
            logger.warning(
                "WS subscription limit (%d) reached, cannot add %d symbols",
                cfg.max_ws_subscriptions, len(new_symbols),
            )
            return

        to_add = new_symbols[:available_slots]
        await self._send_subscribe(to_add)
        self._active_subs.update(to_add)
        logger.info(
            "Subscribed to %d contracts (total active: %d)",
            len(to_add), len(self._active_subs),
        )

    async def unsubscribe(self, contract_symbols: list[str]) -> None:
        """Unsubscribe from the given contract symbols."""
        to_remove = [s for s in contract_symbols if s in self._active_subs]
        if not to_remove:
            return

        await self._send_unsubscribe(to_remove)
        self._active_subs -= set(to_remove)
        logger.info(
            "Unsubscribed from %d contracts (total active: %d)",
            len(to_remove), len(self._active_subs),
        )

    async def replace_subscriptions(
        self,
        old_symbols: list[str],
        new_symbols: list[str],
    ) -> None:
        """
        Atomic swap: unsubscribe old, subscribe new.
        Used during re-centering to rotate the core strike set.
        """
        await self.unsubscribe(old_symbols)
        await self.subscribe(new_symbols)

    # ────────────────────────────────────────────────────────────────
    #  Low-level send helpers
    # ────────────────────────────────────────────────────────────────

    async def _send_subscribe(self, symbols: list[str]) -> None:
        if not self._ws or not symbols:
            return
        # Build combined channel list:  "T.O:SYM1,Q.O:SYM1,T.O:SYM2,Q.O:SYM2,..."
        channels = []
        for sym in symbols:
            channels.append(f"T.{sym}")
            channels.append(f"Q.{sym}")
        msg = json.dumps({"action": "subscribe", "params": ",".join(channels)})
        await self._ws.send(msg)
        logger.debug("WS subscribe sent for %d symbols", len(symbols))

    async def _send_unsubscribe(self, symbols: list[str]) -> None:
        if not self._ws or not symbols:
            return
        channels = []
        for sym in symbols:
            channels.append(f"T.{sym}")
            channels.append(f"Q.{sym}")
        msg = json.dumps({"action": "unsubscribe", "params": ",".join(channels)})
        await self._ws.send(msg)
        logger.debug("WS unsubscribe sent for %d symbols", len(symbols))
