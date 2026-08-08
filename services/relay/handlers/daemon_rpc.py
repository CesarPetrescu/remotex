"""REST → daemon request/response plumbing shared by the admin routes.

Every read-only host route (threads, fs, models) follows the same shape:
check the caller owns the host, mint a request id, push the frame down the
daemon socket, await the matching response frame. The future is registered
under ``(host_id, request_id)`` so only the daemon that was asked can
resolve it.
"""
from __future__ import annotations

import asyncio
import uuid

from aiohttp import web

from ..auth import require_user
from ..hub import Hub
from ..store import Store


async def await_daemon_request(
    request: web.Request, host_id: str, frame: dict, *, timeout: float = 30.0,
) -> dict:
    """Ownership check, send to the daemon, await the response payload.
    Raises HTTP errors directly so handlers stay flat."""
    user = await require_user(request)
    store: Store = request.app["store"]
    hub: Hub = request.app["hub"]
    if await store.host_owner(host_id) != user["token"]:
        raise web.HTTPNotFound(reason="host not found")
    req_id = f"req_{uuid.uuid4().hex[:12]}"
    fut = hub.register_admin_request(host_id, req_id)
    try:
        if not await hub.forward_to_daemon(host_id, {**frame, "request_id": req_id}):
            raise web.HTTPBadGateway(reason="host offline")
        try:
            payload = await asyncio.wait_for(fut, timeout=timeout)
        except asyncio.TimeoutError as exc:
            raise web.HTTPGatewayTimeout(reason="daemon did not respond in time") from exc
    finally:
        hub.discard_admin_request(host_id, req_id)
    if "error" in payload:
        raise web.HTTPBadGateway(reason=_reason(f"daemon error: {payload['error']}"))
    return payload


def _reason(text: str) -> str:
    """HTTP reason phrases go on the status line, so a daemon-supplied
    error containing CR/LF makes aiohttp raise instead of respond (a 500
    where a 502 was meant). Flatten and bound it."""
    return "".join(c if c.isprintable() else " " for c in text)[:200]
