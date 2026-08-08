"""Session reservation: POST /api/sessions allocates the id; ws_client opens it."""
from __future__ import annotations

import asyncio
import logging
import os
import time

from aiohttp import web

from ..auth import require_user
from ..hub import Hub
from ..store import Store


log = logging.getLogger("relay.sessions")

_VALID_KINDS = {"codex"}

# A reservation that no client ever attaches to would otherwise live
# forever: an open DB row, an override dict, and — for a resumed thread —
# a (host, thread) index entry that makes every later POST hand back the
# same dead id.
RESERVATION_TTL_SECONDS = float(
    os.getenv("RELAY_SESSION_RESERVATION_TTL_SECONDS", str(10 * 60))
)
RESERVATION_SWEEP_INTERVAL_SECONDS = 60.0
# Bound the work one sweep can do, so a burst of reservations can't turn
# the sweeper into its own stall.
_MAX_REAPED_PER_SWEEP = 200


async def open_session(request: web.Request) -> web.Response:
    """Reserve a session id. The daemon is not notified until the client
    attaches via /ws/client — otherwise the session-started event would be
    emitted into the void before the client could observe it.

    If a session is already in flight for this (host, thread), return its
    id so the caller reattaches rather than spawning a parallel codex.
    Lets the user disconnect mid-turn (phone sleep, browser close) and
    rejoin the same in-flight session by tapping the same thread again.

    Only native Codex sessions are supported. Long-running work should
    use Codex's thread goals through the session WebSocket."""
    user = await require_user(request)
    body = await request.json()
    host_id = body.get("host_id")
    resume_thread_id = (body.get("thread_id") or "").strip() or None
    cwd = (body.get("cwd") or "").strip() or None
    kind = (body.get("kind") or "codex").strip().lower() or "codex"
    if kind not in _VALID_KINDS:
        raise web.HTTPBadRequest(reason=f"unknown kind: {kind!r}")
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
    sid = await store.open_session(
        host_id, user["token"], kind=kind, thread_id=resume_thread_id, cwd=cwd,
    )
    # Stash per-session overrides so ws_client can thread them into the
    # session-open frame it later sends to the daemon.
    overrides: dict = {"kind": kind}
    if resume_thread_id:
        overrides["thread_id"] = resume_thread_id
    if cwd:
        overrides["cwd"] = cwd
    request.app.setdefault("session_open_overrides", {})[sid] = overrides
    request.app.setdefault("session_reservations", {})[sid] = time.monotonic()
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


async def sweep_reservations(app: web.Application, now: float | None = None) -> int:
    """Close and forget reservations no client ever attached to.

    A reservation stops being one the moment a client attaches (the hub
    gains a session-open frame or a live client), so the common case just
    drops the bookkeeping entry. Returns how many sessions were reaped.
    """
    reservations: dict = app.setdefault("session_reservations", {})
    if not reservations:
        return 0
    hub: Hub = app["hub"]
    store: Store = app["store"]
    override_map: dict = app.setdefault("session_open_overrides", {})
    now = time.monotonic() if now is None else now
    reaped = 0
    for sid, reserved_at in list(reservations.items()):
        if hub.client_for(sid) is not None or sid in hub.session_open_frames:
            reservations.pop(sid, None)
            continue
        if now - reserved_at < RESERVATION_TTL_SECONDS:
            continue
        reservations.pop(sid, None)
        override_map.pop(sid, None)
        await store.close_session(sid)
        await hub.forget_session(sid)
        reaped += 1
        log.info("reaped unattached session reservation", extra={"session_id": sid})
        if reaped >= _MAX_REAPED_PER_SWEEP:
            break
    return reaped


async def reservation_sweeper(app: web.Application) -> None:
    """Background loop; started/stopped by the application lifecycle."""
    while True:
        await asyncio.sleep(RESERVATION_SWEEP_INTERVAL_SECONDS)
        try:
            await sweep_reservations(app)
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001
            log.warning("reservation sweep failed", extra={"error": str(exc)})
