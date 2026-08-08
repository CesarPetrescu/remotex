"""Thread listing — forwards to the daemon and reshapes the response."""
from __future__ import annotations

from aiohttp import web

from .daemon_rpc import await_daemon_request


async def list_host_threads(request: web.Request) -> web.Response:
    """Forward a `thread/list` request to the daemon, await the response."""
    host_id = request.match_info["host_id"]
    limit = int(request.query.get("limit") or 20)
    payload = await await_daemon_request(
        request,
        host_id,
        {"type": "threads-list-request", "limit": limit},
        timeout=30.0,
    )
    threads = payload.get("threads") or []
    # Reshape to a compact payload for clients (keep only what the UI needs).
    summarized = [
        {
            "id": t.get("id"),
            "preview": t.get("preview") or "",
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
