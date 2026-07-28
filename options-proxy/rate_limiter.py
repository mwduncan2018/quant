"""Configurable asynchronous sliding-window request limiter."""

from __future__ import annotations

import asyncio
import time
from collections import deque
from collections.abc import Awaitable, Callable


class SlidingWindowRateLimiter:
    """Guarantee at most ``max_requests`` starts in any rolling period."""

    def __init__(
        self,
        *,
        enabled: bool,
        max_requests: int,
        period_seconds: float,
        clock: Callable[[], float] = time.monotonic,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
    ) -> None:
        if max_requests < 1:
            raise ValueError("max_requests must be at least 1")
        if period_seconds <= 0:
            raise ValueError("period_seconds must be positive")

        self.enabled = enabled
        self.max_requests = max_requests
        self.period_seconds = period_seconds
        self._clock = clock
        self._sleep = sleep
        self._starts: deque[float] = deque()
        self._lock = asyncio.Lock()

    async def acquire(self) -> None:
        """Wait until one request may start, then reserve that start."""

        while self.enabled:
            async with self._lock:
                now = self._clock()
                cutoff = now - self.period_seconds
                while self._starts and self._starts[0] <= cutoff:
                    self._starts.popleft()

                if len(self._starts) < self.max_requests:
                    self._starts.append(now)
                    return

                wait_seconds = self.period_seconds - (now - self._starts[0])

            await self._sleep(max(wait_seconds, 0.0))

    def status(self) -> dict[str, bool | int | float]:
        return {
            "enabled": self.enabled,
            "max_requests": self.max_requests,
            "period_seconds": self.period_seconds,
        }
