"""Per-bearer-token REST rate limit, token-bucket, in-process.

Single instance per relay process. Sustains 10 req/s with a 30-token
burst. Returns HTTP 429 with a ``Retry-After`` header on overflow.
WebSocket endpoints (``/ws/daemon``, ``/ws/client``) bypass the bucket
because their lifecycle is managed by aiohttp's heartbeat.
"""
from __future__ import annotations

import time
from collections import defaultdict

from aiohttp import web

from ..auth import _bearer

_BUCKET_BURST = 30
_REFILL_PER_SECOND = 10
# Skip rate limiting for WS upgrade requests and the static SPA fallback.
_BYPASS_PREFIXES = ("/ws/", "/assets/")


class _TokenBucket:
    __slots__ = ("tokens", "last")

    def __init__(self, tokens: float, last: float) -> None:
        self.tokens = tokens
        self.last = last


_buckets: dict[str, _TokenBucket] = defaultdict(
    lambda: _TokenBucket(tokens=float(_BUCKET_BURST), last=time.monotonic())
)


def _take(key: str) -> tuple[bool, float]:
    """Take one token from ``key``'s bucket. Returns (allowed, retry_after_s)."""
    now = time.monotonic()
    bucket = _buckets[key]
    elapsed = now - bucket.last
    bucket.tokens = min(_BUCKET_BURST, bucket.tokens + elapsed * _REFILL_PER_SECOND)
    bucket.last = now
    if bucket.tokens >= 1.0:
        bucket.tokens -= 1.0
        return True, 0.0
    deficit = 1.0 - bucket.tokens
    return False, deficit / _REFILL_PER_SECOND


@web.middleware
async def rate_limit_middleware(request: web.Request, handler):
    path = request.path
    if any(path.startswith(p) for p in _BYPASS_PREFIXES):
        return await handler(request)
    # Anonymous requests share one bucket per remote address. Authenticated
    # requests are bucketed by token. Either is enough to keep one
    # misbehaving caller from monopolizing the relay.
    token = _bearer(request) or f"anon:{request.remote or 'unknown'}"
    allowed, retry_after = _take(token)
    if allowed:
        return await handler(request)
    raise web.HTTPTooManyRequests(
        reason="rate limit exceeded",
        headers={"Retry-After": f"{max(1, int(retry_after))}"},
    )
