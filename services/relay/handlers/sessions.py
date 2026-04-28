"""Session reservation: POST /api/sessions allocates the id; ws_client opens it."""
from __future__ import annotations

from aiohttp import web

from ..auth import require_user
from ..hub import Hub
from ..store import Store


async def open_session(request: web.Request) -> web.Response:
    """Reserve a session id. The daemon is not notified until the client
    attaches via /ws/client — otherwise the session-started event would be
    emitted into the void before the client could observe it."""
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
    return web.json_response({
        "session_id": sid,
        "host_id": host_id,
        "thread_id": resume_thread_id,
        "cwd": cwd,
    }, status=201)
