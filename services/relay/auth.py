"""Bearer-token authentication for relay REST + WS endpoints."""
from __future__ import annotations

from aiohttp import web

from .store import Store


def _bearer(request: web.Request) -> str | None:
    auth = request.headers.get("Authorization", "")
    if auth.startswith("Bearer "):
        return auth[len("Bearer "):].strip() or None
    return request.query.get("token")


async def require_user(request: web.Request) -> dict:
    token = _bearer(request)
    if not token:
        raise web.HTTPUnauthorized(reason="missing bearer token")
    store: Store = request.app["store"]
    user = await store.user_for_token(token)
    if not user:
        raise web.HTTPUnauthorized(reason="unknown token")
    return user
