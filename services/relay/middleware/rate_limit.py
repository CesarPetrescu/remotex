"""REST rate limit, token-bucket, in-process.

Every request takes from a **per-remote** bucket (120 burst / 40 per
second). A request that carries a credential additionally takes from a
per-credential bucket (30 burst / 10 per second). Both have to allow it.
Returns HTTP 429 with a ``Retry-After`` header on overflow.

The per-remote bucket is the one that matters for abuse: the credential
is caller-controlled, so bucketing only by it means an attacker who
rotates the ``Authorization`` header mints a fresh full bucket per
request and is never limited at all — which is exactly the shape of a
token brute-force. The per-credential bucket stays because it keeps one
misbehaving *client* from monopolizing the relay for the others behind
its address.

Both maps are bounded: idle buckets are swept and, past the cap, the
least-recently-used are dropped. A full idle bucket is indistinguishable
from a fresh one, so evicting it gives nothing away.

WebSocket endpoints (``/ws/daemon``, ``/ws/client``) bypass the REST
buckets — their lifecycle is managed by aiohttp's heartbeat, not by
request count — but they are not unlimited: the handlers call
``allow_ws_connection()`` for a per-remote cap on connection attempts.

``request.remote`` is the TCP peer, which is the reverse proxy when the
relay is deployed behind one (the Caddy profile in
``deploy/docker-compose.yml`` is exactly that) — every caller then shares
one bucket. Set ``RELAY_TRUST_PROXY=1`` there so the client address is
read from ``X-Forwarded-For`` instead. Leave it off when the relay is
exposed directly: the header is caller-supplied and trusting it without a
proxy in front hands every attacker an unlimited supply of buckets.
"""
from __future__ import annotations

import os
import time
from collections import OrderedDict

from aiohttp import web

from ..auth import _bearer

_BUCKET_BURST = 30
_REFILL_PER_SECOND = 10
_REMOTE_BURST = int(os.getenv("RELAY_RATE_LIMIT_REMOTE_BURST", "120"))
_REMOTE_PER_SECOND = float(os.getenv("RELAY_RATE_LIMIT_REMOTE_PER_SECOND", "40"))
# Skip rate limiting for WS upgrade requests and the static SPA fallback.
_BYPASS_PREFIXES = ("/ws/", "/assets/")
_TRUSTED_PROXY = (os.getenv("RELAY_TRUST_PROXY", "").strip().lower()
                  in {"1", "true", "yes"})

# Bucket-map bound. Above the cap a sweep runs; anything still over is
# trimmed LRU-first. Idle is set well above the burst refill time
# (30 tokens / 10 per s = 3s) so eviction only ever drops a bucket that
# was already back to full.
_MAX_BUCKETS = int(os.getenv("RELAY_RATE_LIMIT_MAX_BUCKETS", "10000"))
_IDLE_EVICT_SECONDS = float(os.getenv("RELAY_RATE_LIMIT_IDLE_SECONDS", "300"))

# WebSocket connection attempts per remote address. Deliberately generous:
# every client of one household shares a NAT address, and a reverse proxy
# in front of the relay collapses every user onto one `request.remote`.
# It only has to stop an unauthenticated peer from opening sockets faster
# than we can drop them — tune with the env vars if you front the relay
# with a proxy that doesn't preserve the peer address.
_WS_CONNECT_BURST = int(os.getenv("RELAY_WS_CONNECT_BURST", "60"))
_WS_CONNECT_PER_SECOND = float(os.getenv("RELAY_WS_CONNECT_PER_SECOND", "5"))


class _TokenBucket:
    __slots__ = ("tokens", "last")

    def __init__(self, tokens: float, last: float) -> None:
        self.tokens = tokens
        self.last = last


_buckets: OrderedDict[str, _TokenBucket] = OrderedDict()
_remote_buckets: OrderedDict[str, _TokenBucket] = OrderedDict()
_ws_buckets: OrderedDict[str, _TokenBucket] = OrderedDict()


def client_remote(request: web.Request) -> str:
    """The caller's address for rate-limiting purposes.

    ``X-Forwarded-For`` is only consulted when the operator has declared a
    proxy in front of the relay — otherwise it is just another header the
    attacker controls.
    """
    if _TRUSTED_PROXY:
        forwarded = request.headers.get("X-Forwarded-For", "")
        first = forwarded.split(",")[0].strip()
        if first:
            return first
    return request.remote or "unknown"


def _sweep(buckets: OrderedDict[str, _TokenBucket], now: float) -> None:
    """Drop idle buckets, then LRU-trim to 90% of the cap. Only runs when
    the map is at the cap, so it stays O(n) and rare."""
    for key, bucket in list(buckets.items()):
        if now - bucket.last > _IDLE_EVICT_SECONDS:
            buckets.pop(key, None)
    target = max(1, (_MAX_BUCKETS * 9) // 10)
    while len(buckets) > target:
        buckets.popitem(last=False)


def _take_from(
    buckets: OrderedDict[str, _TokenBucket],
    key: str,
    burst: float,
    refill_per_second: float,
) -> tuple[bool, float]:
    now = time.monotonic()
    bucket = buckets.get(key)
    if bucket is None:
        if len(buckets) >= _MAX_BUCKETS:
            _sweep(buckets, now)
        bucket = _TokenBucket(tokens=burst, last=now)
        buckets[key] = bucket
    else:
        buckets.move_to_end(key)
    elapsed = now - bucket.last
    bucket.tokens = min(burst, bucket.tokens + elapsed * refill_per_second)
    bucket.last = now
    if bucket.tokens >= 1.0:
        bucket.tokens -= 1.0
        return True, 0.0
    deficit = 1.0 - bucket.tokens
    return False, deficit / refill_per_second


def _take(key: str) -> tuple[bool, float]:
    """Take one token from ``key``'s bucket. Returns (allowed, retry_after_s)."""
    return _take_from(_buckets, key, float(_BUCKET_BURST), float(_REFILL_PER_SECOND))


def allow_ws_connection(remote: str | None) -> tuple[bool, float]:
    """Per-remote cap on websocket connection attempts. Called by the WS
    handlers before ``prepare()``, since the REST bucket skips ``/ws/``."""
    return _take_from(
        _ws_buckets,
        f"ws:{remote or 'unknown'}",
        float(_WS_CONNECT_BURST),
        _WS_CONNECT_PER_SECOND,
    )


@web.middleware
async def rate_limit_middleware(request: web.Request, handler):
    path = request.path
    if any(path.startswith(p) for p in _BYPASS_PREFIXES):
        return await handler(request)
    # The per-remote bucket is charged first and always: it is the only one
    # a caller cannot escape by presenting a different credential.
    allowed, retry_after = _take_from(
        _remote_buckets,
        f"ip:{client_remote(request)}",
        float(_REMOTE_BURST),
        _REMOTE_PER_SECOND,
    )
    token = _bearer(request)
    if allowed and token:
        allowed, retry_after = _take(token)
    if allowed:
        return await handler(request)
    raise web.HTTPTooManyRequests(
        reason="rate limit exceeded",
        headers={"Retry-After": f"{max(1, int(retry_after))}"},
    )
