import asyncio

import pytest

from rate_limiter import SlidingWindowRateLimiter


class FakeTime:
    def __init__(self) -> None:
        self.now = 0.0
        self.sleeps: list[float] = []

    def __call__(self) -> float:
        return self.now

    async def sleep(self, seconds: float) -> None:
        self.sleeps.append(seconds)
        self.now += seconds


def test_enabled_limiter_waits_for_rolling_window() -> None:
    fake_time = FakeTime()
    limiter = SlidingWindowRateLimiter(
        enabled=True,
        max_requests=2,
        period_seconds=60,
        clock=fake_time,
        sleep=fake_time.sleep,
    )

    async def run() -> None:
        await limiter.acquire()
        await limiter.acquire()
        await limiter.acquire()

    asyncio.run(run())
    assert fake_time.sleeps == [60.0]
    assert fake_time.now == 60.0


def test_disabled_limiter_never_waits() -> None:
    fake_time = FakeTime()
    limiter = SlidingWindowRateLimiter(
        enabled=False,
        max_requests=1,
        period_seconds=60,
        clock=fake_time,
        sleep=fake_time.sleep,
    )

    async def run() -> None:
        for _ in range(10):
            await limiter.acquire()

    asyncio.run(run())
    assert fake_time.sleeps == []
    assert fake_time.now == 0.0


@pytest.mark.parametrize(
    ("max_requests", "period_seconds"),
    [(0, 60), (5, 0), (5, -1)],
)
def test_invalid_limiter_configuration_is_rejected(
    max_requests: int,
    period_seconds: float,
) -> None:
    with pytest.raises(ValueError):
        SlidingWindowRateLimiter(
            enabled=True,
            max_requests=max_requests,
            period_seconds=period_seconds,
        )
