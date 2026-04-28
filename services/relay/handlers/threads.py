"""Thread listing — forwards to the daemon and joins with search titles."""
from __future__ import annotations

import asyncio
import uuid

from aiohttp import web

from ..auth import require_user
from ..hub import Hub
from ..store import Store


async def list_host_threads(request: web.Request) -> web.Response:
    """Forward a `thread/list` request to the daemon, await the response."""
    user = await require_user(request)
    host_id = request.match_info["host_id"]
    store: Store = request.app["store"]
    hub: Hub = request.app["hub"]
    if await store.host_owner(host_id) != user["token"]:
        raise web.HTTPNotFound(reason="host not found")
    daemon_ws = hub.daemon_for(host_id)
    if daemon_ws is None or daemon_ws.closed:
        raise web.HTTPBadGateway(reason="host offline")
    req_id = f"req_{uuid.uuid4().hex[:12]}"
    limit = int(request.query.get("limit") or 20)
    loop = asyncio.get_running_loop()
    fut: asyncio.Future = loop.create_future()
    hub.pending_admin[req_id] = fut
    try:
        await daemon_ws.send_json({
            "type": "threads-list-request",
            "request_id": req_id,
            "limit": limit,
        })
        try:
            payload = await asyncio.wait_for(fut, timeout=15.0)
        except asyncio.TimeoutError as exc:
            raise web.HTTPGatewayTimeout(reason="daemon did not respond in time") from exc
    finally:
        hub.pending_admin.pop(req_id, None)
    if "error" in payload:
        raise web.HTTPBadGateway(reason=f"daemon error: {payload['error']}")
    threads = payload.get("threads") or []
    thread_ids = [t.get("id") for t in threads if t.get("id") and not t.get("ephemeral")]
    metadata = await request.app["search"].thread_metadata(
        owner_token=user["token"],
        host_id=host_id,
        thread_ids=thread_ids,
    )
    # Reshape to a compact payload for clients (keep only what the UI needs).
    summarized = [
        {
            "id": t.get("id"),
            "preview": t.get("preview") or "",
            "title": (metadata.get(t.get("id")) or {}).get("title"),
            "description": (metadata.get(t.get("id")) or {}).get("description"),
            "title_is_generic": (metadata.get(t.get("id")) or {}).get("title_is_generic", True),
            "created_at": t.get("createdAt"),
            "updated_at": t.get("updatedAt"),
            "cwd": t.get("cwd"),
            "ephemeral": bool(t.get("ephemeral")),
        }
        for t in threads
        if t.get("id") and not t.get("ephemeral")
    ]
    return web.json_response({
        "host_id": host_id,
        "threads": summarized,
        "next_cursor": payload.get("next_cursor"),
    })
