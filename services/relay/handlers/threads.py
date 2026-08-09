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


_PREVIEW_TIMEOUT = 10.0
_PREVIEW_MAX_TURNS = 5


async def get_thread_preview(request: web.Request) -> web.Response:
    """Compact transcript tail for hover/press prefetch.

    The daemon answers from the rollout file on disk (LRU-cached, no codex
    involved), so clients may call this freely as the pointer sweeps over
    thread rows. `available: false` means no local rollout — the client
    keeps whatever row preview it already has.
    """
    host_id = request.match_info["host_id"]
    thread_id = request.match_info["thread_id"]
    try:
        turns = min(max(int(request.query.get("turns") or 2), 1), _PREVIEW_MAX_TURNS)
    except ValueError:
        turns = 2
    payload = await await_daemon_request(
        request,
        host_id,
        {"type": "thread-preview-request", "thread_id": thread_id, "turns": turns},
        timeout=_PREVIEW_TIMEOUT,
    )
    return web.json_response({
        "host_id": host_id,
        "thread_id": thread_id,
        "available": bool(payload.get("available")),
        "turns": payload.get("turns") or [],
    })
