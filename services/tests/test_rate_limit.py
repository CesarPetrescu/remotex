"""Rate-limit middleware behaviour (in-process token bucket)."""
from __future__ import annotations

import pytest
from aiohttp import web

from relay.middleware.rate_limit import (
    _BUCKET_BURST,
    _buckets,
    _take,
    allow_ws_connection,
)


def test_bucket_allows_burst_and_then_throttles():
    key = "test-bucket-allows-burst"
    # Drain a fresh bucket: every take should pass for the burst quota.
    for i in range(_BUCKET_BURST):
        ok, _ = _take(key)
        assert ok, f"take {i} unexpectedly throttled"
    ok, retry = _take(key)
    assert not ok
    assert retry > 0


def test_bucket_map_is_bounded_against_arbitrary_keys(monkeypatch):
    """The key is caller-controlled (any Authorization value mints one),
    so the map must not grow without limit."""
    import relay.middleware.rate_limit as rl

    monkeypatch.setattr(rl, "_MAX_BUCKETS", 50)
    monkeypatch.setattr(rl, "_IDLE_EVICT_SECONDS", 1e9)  # nothing is idle yet
    _buckets.clear()
    try:
        for i in range(500):
            _take(f"junk-token-{i}")
        assert len(_buckets) <= 50
        # The most recent keys survive; the oldest were dropped LRU-first.
        assert "junk-token-499" in _buckets
        assert "junk-token-0" not in _buckets
    finally:
        _buckets.clear()


def test_idle_buckets_are_swept_before_lru_trim(monkeypatch):
    import relay.middleware.rate_limit as rl

    monkeypatch.setattr(rl, "_MAX_BUCKETS", 10)
    monkeypatch.setattr(rl, "_IDLE_EVICT_SECONDS", -1.0)  # everything is idle
    _buckets.clear()
    try:
        for i in range(40):
            _take(f"idle-token-{i}")
        # Each sweep clears everything stale, so the map stays tiny.
        assert len(_buckets) <= 10
    finally:
        _buckets.clear()


def test_ws_connections_are_capped_per_remote():
    """/ws/* bypasses the REST bucket, so the handlers cap connection
    attempts themselves — otherwise the sockets are unlimited."""
    import relay.middleware.rate_limit as rl

    allowed = sum(
        1 for _ in range(rl._WS_CONNECT_BURST + 5)
        if allow_ws_connection("203.0.113.9")[0]
    )
    assert allowed <= rl._WS_CONNECT_BURST
    ok, retry = allow_ws_connection("203.0.113.9")
    assert not ok
    assert retry > 0
    # A different peer is unaffected.
    assert allow_ws_connection("203.0.113.10")[0] is True


@pytest.mark.asyncio
async def test_rotating_the_credential_does_not_mint_a_fresh_quota(aiohttp_client):
    """The bucket key used to be the caller-supplied bearer, so an
    attacker rotating the header was never throttled at all — which is
    exactly the shape of a token brute-force."""
    import relay.middleware.rate_limit as rl
    from relay.middleware import rate_limit_middleware

    app = web.Application(middlewares=[rate_limit_middleware])

    async def hello(request: web.Request) -> web.Response:
        return web.json_response({"ok": True})

    app.router.add_get("/api/hello", hello)
    client = await aiohttp_client(app)

    statuses: list[int] = []
    for i in range(rl._REMOTE_BURST + 20):
        resp = await client.get(
            "/api/hello", headers={"Authorization": f"Bearer guess-{i}"},
        )
        statuses.append(resp.status)
        await resp.read()
    assert 429 in statuses


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
