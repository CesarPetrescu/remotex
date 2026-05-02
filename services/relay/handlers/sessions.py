"""Session reservation: POST /api/sessions allocates the id; ws_client opens it."""
from __future__ import annotations

from aiohttp import web

from ..auth import require_user
from ..hub import Hub
from ..store import Store


_VALID_KINDS = {"codex", "orchestrator"}


async def open_session(request: web.Request) -> web.Response:
    """Reserve a session id. The daemon is not notified until the client
    attaches via /ws/client — otherwise the session-started event would be
    emitted into the void before the client could observe it.

    If a session is already in flight for this (host, thread), return its
    id so the caller reattaches rather than spawning a parallel codex.
    Lets the user disconnect mid-turn (phone sleep, browser close) and
    rejoin the same in-flight session by tapping the same thread again.

    Orchestrator sessions are a separate kind: ``kind="orchestrator"``
    plus a ``task`` body field. The relay still routes them like any
    other session — the daemon side decides what to spawn."""
    user = await require_user(request)
    body = await request.json()
    host_id = body.get("host_id")
    resume_thread_id = (body.get("thread_id") or "").strip() or None
    cwd = (body.get("cwd") or "").strip() or None
    kind = (body.get("kind") or "codex").strip().lower() or "codex"
    if kind not in _VALID_KINDS:
        raise web.HTTPBadRequest(reason=f"unknown kind: {kind!r}")
    orchestrator_task = (body.get("task") or "").strip() if kind == "orchestrator" else None
    approval_policy = (body.get("approval_policy") or "").strip() or None
    permissions = (body.get("permissions") or "").strip() or None
    orchestrator_model = (body.get("model") or "").strip() or None
    orchestrator_effort = (body.get("effort") or "").strip() or None
    if kind == "orchestrator" and not orchestrator_task:
        raise web.HTTPBadRequest(reason="orchestrator session requires a `task`")
    store: Store = request.app["store"]
    hub: Hub = request.app["hub"]
    if not host_id or await store.host_owner(host_id) != user["token"]:
        raise web.HTTPNotFound(reason="host not found")
    if hub.daemon_for(host_id) is None:
        raise web.HTTPBadGateway(reason="host offline")
    if resume_thread_id and kind == "codex":
        existing = hub.active_session_for_thread(host_id, resume_thread_id)
        if existing:
            return web.json_response({
                "session_id": existing,
                "host_id": host_id,
                "thread_id": resume_thread_id,
                "cwd": cwd,
                "reused": True,
            })
    sid = await store.open_session(host_id, user["token"], kind=kind)
    session = await store.session_info(sid)
    if session and kind == "codex":
        request.app["search"].capture_session_opened(session)
    # Stash per-session overrides so ws_client can thread them into the
    # session-open frame it later sends to the daemon.
    overrides: dict = {"kind": kind}
    if resume_thread_id:
        overrides["thread_id"] = resume_thread_id
    if cwd:
        overrides["cwd"] = cwd
    if kind == "orchestrator":
        overrides["task"] = orchestrator_task
        if approval_policy:
            overrides["approval_policy"] = approval_policy
        if permissions:
            overrides["permissions"] = permissions
        if orchestrator_model:
            overrides["model"] = orchestrator_model
        if orchestrator_effort:
            overrides["effort"] = orchestrator_effort
    request.app.setdefault("session_open_overrides", {})[sid] = overrides
    if resume_thread_id and kind == "codex":
        # Reserve the (host, thread) mapping immediately. Otherwise two
        # clients opening the same saved chat at the same time can both
        # miss the index before the daemon emits session-started.
        await hub.remember_session_thread(sid, host_id, resume_thread_id)
    return web.json_response({
        "session_id": sid,
        "host_id": host_id,
        "thread_id": resume_thread_id,
        "cwd": cwd,
        "kind": kind,
    }, status=201)


async def get_session_plan(request: web.Request) -> web.Response:
    """Return the orchestrator plan + step statuses for an orchestrator session."""
    user = await require_user(request)
    sid = request.match_info["session_id"]
    store: Store = request.app["store"]
    info = await store.session_info(sid)
    if not info or info["owner_token"] != user["token"]:
        raise web.HTTPNotFound(reason="session not found")
    if info.get("kind") != "orchestrator":
        raise web.HTTPBadRequest(reason="not an orchestrator session")
    steps = await store.list_orchestrator_steps(sid)
    return web.json_response({
        "session_id": sid,
        "kind": info.get("kind"),
        "steps": steps,
    })
