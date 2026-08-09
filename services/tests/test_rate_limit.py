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
async def test_spoofed_forwarded_ips_share_quota_when_proxy_is_untrusted(
    aiohttp_client, monkeypatch,
):
    """SparkTunnel preserves caller-supplied forwarding headers, so its
    safe profile must ignore them and throttle invalid auth on the TCP peer."""
    import relay.middleware.rate_limit as rl
    from relay.handlers.hosts import list_hosts
    from relay.middleware import rate_limit_middleware

    class RejectingStore:
        async def user_for_token(self, token: str) -> None:
            return None

    monkeypatch.setattr(rl, "_TRUSTED_PROXY", False)
    monkeypatch.setattr(rl, "_REMOTE_BURST", 5)
    monkeypatch.setattr(rl, "_REMOTE_PER_SECOND", 1e-9)
    app = web.Application(middlewares=[rate_limit_middleware])
    app["store"] = RejectingStore()
    app.router.add_get("/api/hosts", list_hosts)
    client = await aiohttp_client(app)

    statuses: list[int] = []
    for i in range(rl._REMOTE_BURST + 2):
        resp = await client.get(
            "/api/hosts",
            headers={
                "Authorization": f"Bearer guess-{i}",
                "X-Forwarded-For": f"198.51.100.{i + 1}",
            },
        )
        statuses.append(resp.status)
        await resp.read()
    assert statuses == [401] * rl._REMOTE_BURST + [429, 429]


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
