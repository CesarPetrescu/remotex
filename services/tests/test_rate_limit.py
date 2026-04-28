"""Rate-limit middleware behaviour (in-process token bucket)."""
from __future__ import annotations

import pytest
from aiohttp import web

from relay.middleware.rate_limit import _BUCKET_BURST, _take


def test_bucket_allows_burst_and_then_throttles():
    key = "test-bucket-allows-burst"
    # Drain a fresh bucket: every take should pass for the burst quota.
    for i in range(_BUCKET_BURST):
        ok, _ = _take(key)
        assert ok, f"take {i} unexpectedly throttled"
    ok, retry = _take(key)
    assert not ok
    assert retry > 0


@pytest.mark.asyncio
async def test_429_on_overflow(aiohttp_client):
    from relay.middleware import rate_limit_middleware

    app = web.Application(middlewares=[rate_limit_middleware])

    async def hello(request: web.Request) -> web.Response:
        return web.json_response({"ok": True})

    app.router.add_get("/api/hello", hello)
    client = await aiohttp_client(app)

    # Use a unique bearer so this test doesn't drain any other bucket.
    headers = {"Authorization": "Bearer test-rl-overflow-token"}
    statuses: list[int] = []
    for _ in range(_BUCKET_BURST + 5):
        resp = await client.get("/api/hello", headers=headers)
        statuses.append(resp.status)
        await resp.read()
    assert 429 in statuses
    assert statuses.count(200) >= _BUCKET_BURST - 1
