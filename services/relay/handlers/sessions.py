"""Session reservation: POST /api/sessions allocates the id; ws_client opens it."""
from __future__ import annotations

from aiohttp import web

from ..auth import require_user
from ..hub import Hub
from ..store import Store


async def open_session(request: web.Request) -> web.Response:
    """Reserve a session id. The daemon is not notified until the client
    attaches via /ws/client — otherwise the session-started event would be
    emitted into the void before the client could observe it.

    If a session is already in flight for this (host, thread), return its
    id so the caller reattaches rather than spawning a parallel codex.
    Lets the user disconnect mid-turn (phone sleep, browser close) and
    rejoin the same in-flight session by tapping the same thread again."""
    user = await require_user(request)
    body = await request.json()
    host_id = body.get("host_id")
    resume_thread_id = (body.get("thread_id") or "").strip() or None
    cwd = (body.get("cwd") or "").strip() or None
    store: Store = request.app["store"]
    hub: Hub = request.app["hub"]
    if not host_id or await store.host_owner(host_id) != user["token"]:
        raise web.HTTPNotFound(reason="host not found")
    if hub.daemon_for(host_id) is None:
        raise web.HTTPBadGateway(reason="host offline")
    if resume_thread_id:
        existing = hub.active_session_for_thread(host_id, resume_thread_id)
        if existing:
            return web.json_response({
                "session_id": existing,
                "host_id": host_id,
                "thread_id": resume_thread_id,
                "cwd": cwd,
                "reused": True,
            })
    sid = await store.open_session(host_id, user["token"])
    session = await store.session_info(sid)
    if session:
        request.app["search"].capture_session_opened(session)
    # Stash per-session overrides so ws_client can thread them into the
    # session-open frame it later sends to the daemon.
    overrides: dict = {}
    if resume_thread_id:
        overrides["thread_id"] = resume_thread_id
    if cwd:
        overrides["cwd"] = cwd
    if overrides:
        request.app.setdefault("session_open_overrides", {})[sid] = overrides
    if resume_thread_id:
        # Reserve the (host, thread) mapping immediately. Otherwise two
        # clients opening the same saved chat at the same time can both
        # miss the index before the daemon emits session-started.
        await hub.remember_session_thread(sid, host_id, resume_thread_id)
    return web.json_response({
        "session_id": sid,
        "host_id": host_id,
        "thread_id": resume_thread_id,
        "cwd": cwd,
    }, status=201)
