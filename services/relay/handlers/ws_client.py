"""Client-side websocket: hello → attach → forward client→daemon frames.

Closing semantics:
- Client sends ``session-close``: the relay closes the backend session
  immediately.
- Client disconnects without ``session-close``: the relay holds the
  backend session open for ``CLIENT_RECONNECT_GRACE_SECONDS`` so the
  client can reconnect.
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import time

from aiohttp import WSMsgType, web

from ..hub import Hub
from ..logging import audit
from ..store import Store


log = logging.getLogger("relay.ws.client")

# Idle sessions (no client AND no recent daemon activity) close after
# this many seconds. Keeps the soft "phone went to sleep" reconnect
# window short.
CLIENT_RECONNECT_GRACE_SECONDS = float(
    os.getenv("RELAY_CLIENT_RECONNECT_GRACE_SECONDS", "75")
)
# Hard ceiling: even a session with a turn nominally in flight gets
# killed after this many seconds of no activity (no daemon frames at
# all). Set high enough that real long runs (hours) survive natural
# pauses; default 2h. Pegged to *last activity*, not to turn start —
# so a job that's still emitting events keeps living.
SESSION_STALL_CEILING_SECONDS = float(
    os.getenv("RELAY_SESSION_STALL_CEILING_SECONDS", str(2 * 3600))
)


async def _close_backend_session(app: web.Application, host_id: str, session_id: str) -> None:
    hub: Hub = app["hub"]
    store: Store = app["store"]
    daemon_ws = hub.daemon_for(host_id)
    if daemon_ws is not None and not daemon_ws.closed:
        try:
            await daemon_ws.send_json({
                "type": "session-close",
                "session_id": session_id,
            })
        except Exception as exc:  # noqa: BLE001
            log.debug("session-close forward failed", extra={"error": str(exc)})
    session = await store.session_info(session_id)
    await store.close_session(session_id)
    if session:
        app["search"].capture_session_closed(session)
    await hub.forget_session(session_id)


async def _schedule_backend_close(
    app: web.Application,
    host_id: str,
    session_id: str,
) -> None:
    """Spin up a background watchdog that decides when (if ever) to close
    a session whose client has gone away. Two competing rules:

      1. If the session is idle (no turn in flight) and the client has
         been gone for CLIENT_RECONNECT_GRACE_SECONDS without any daemon
         activity, close it. This is the "phone went to sleep, never came
         back" case.

      2. If the session has a turn in flight, keep it alive as long as the
         daemon keeps emitting frames. Only close after
         SESSION_STALL_CEILING_SECONDS of complete silence — the run is
         either hung or the agent is genuinely thinking for hours, and
         either way someone needs to reattach to do anything about it.

    Activity is updated by ws_daemon every time a frame for this session
    arrives from the daemon (turn deltas, item completions, etc.).
    """
    hub: Hub = app["hub"]
    poll_interval = 5.0
    # Seed the activity timestamp so a session that disconnects before
    # any daemon frame still gets the soft grace before being reaped.
    hub.session_activity.setdefault(session_id, time.monotonic())

    async def watchdog() -> None:
        try:
            while True:
                await asyncio.sleep(poll_interval)
                if hub.client_for(session_id) is not None:
                    # Client reattached — let detach_client schedule a
                    # fresh watchdog if it leaves again.
                    return
                last = hub.session_activity.get(session_id, time.monotonic())
                silent_for = time.monotonic() - last
                turn_active = hub.turn_in_flight.get(session_id, False)
                if turn_active:
                    if silent_for >= SESSION_STALL_CEILING_SECONDS:
                        log.warning(
                            "session stalled with turn in flight, killing",
                            extra={
                                "session_id": session_id,
                                "silent_s": int(silent_for),
                            },
                        )
                        break
                    # Otherwise keep the session alive — daemon is still
                    # producing output even though no client is watching.
                    continue
                if silent_for >= CLIENT_RECONNECT_GRACE_SECONDS:
                    log.info(
                        "session closed after grace",
                        extra={
                            "session_id": session_id,
                            "grace_s": CLIENT_RECONNECT_GRACE_SECONDS,
                        },
                    )
                    break
            await _close_backend_session(app, host_id, session_id)
        except asyncio.CancelledError:
            pass

    await hub.schedule_session_close(
        session_id,
        asyncio.create_task(watchdog(), name=f"client-grace-{session_id}"),
    )


async def ws_client(request: web.Request) -> web.WebSocketResponse:
    ws = web.WebSocketResponse(heartbeat=20)
    await ws.prepare(request)
    store: Store = request.app["store"]
    hub: Hub = request.app["hub"]

    session_id: str | None = None
    host_id: str | None = None
    explicit_close = False
    try:
        hello = await asyncio.wait_for(ws.receive(), timeout=10)
        if hello.type != WSMsgType.TEXT:
            await ws.close(code=1008, message=b"expected hello")
            return ws
        data = json.loads(hello.data)
        token = data.get("token", "")
        user = await store.user_for_token(token)
        if not user:
            await ws.send_json({"type": "error", "error": "invalid user token"})
            await ws.close(code=4401, message=b"invalid token")
            audit("auth.user.invalid", remote=request.remote)
            return ws
        session_id = data.get("session_id")
        if not session_id:
            await ws.send_json({"type": "error", "error": "session_id required"})
            await ws.close(code=1008, message=b"no session")
            return ws
        host_id = hub.host_for_session(session_id)
        if host_id is None:
            session = await store.session_info(session_id)
            if session is None or session["owner_token"] != user["token"]:
                await ws.send_json({"type": "error", "error": "session not found"})
                await ws.close(code=1008, message=b"bad session")
                return ws
            host_id = session["host_id"]
        await hub.attach_client(session_id, host_id, ws)
        await ws.send_json({"type": "attached", "session_id": session_id, "host_id": host_id})
        audit("session.attached", session_id=session_id, host_id=host_id, user=user["token"])
        # Now that the client is attached, ask the daemon to start the session.
        daemon_ws = hub.daemon_for(host_id)
        if daemon_ws is None or daemon_ws.closed:
            await ws.send_json({"type": "error", "error": "host offline"})
            await ws.close(code=4503, message=b"host offline")
            return ws
        override_map: dict = request.app.setdefault("session_open_overrides", {})
        open_frame = await hub.session_open_frame(session_id)
        if open_frame is None:
            overrides = override_map.pop(session_id, {}) or {}
            open_frame = {"type": "session-open", "session_id": session_id}
            if overrides.get("thread_id"):
                open_frame["resume_thread_id"] = overrides["thread_id"]
            if overrides.get("cwd"):
                open_frame["cwd"] = overrides["cwd"]
            await hub.remember_session_open(session_id, host_id, open_frame)
        await daemon_ws.send_json(open_frame)

        async for msg in ws:
            if msg.type != WSMsgType.TEXT:
                continue
            try:
                frame = json.loads(msg.data)
            except json.JSONDecodeError:
                continue
            # Forward client-originated frames to the daemon untouched.
            frame["session_id"] = session_id
            # Any frame from the client also counts as "session is alive"
            # for the watchdog, so an attached client spamming pings keeps
            # the session warm even if codex is silent.
            hub.bump_activity(session_id)
            if frame.get("type") == "ping":
                await ws.send_json({"type": "pong", "ts": frame.get("ts")})
                continue
            if frame.get("type") == "session-close":
                explicit_close = True
                await ws.close(code=1000, message=b"session closed")
                break
            if frame.get("type") == "turn-start":
                session = await store.session_info(session_id)
                if session:
                    request.app["search"].capture_client_turn(session, frame)
                audit("turn.started", session_id=session_id, host_id=host_id)
            daemon_ws = hub.daemon_for(host_id)
            if daemon_ws is None or daemon_ws.closed:
                await ws.send_json({"type": "error", "error": "host offline"})
                continue
            await daemon_ws.send_json(frame)
    except asyncio.TimeoutError:
        await ws.close(code=4408, message=b"hello timeout")
    except Exception as exc:  # noqa: BLE001
        log.exception("client ws error", extra={"error": str(exc)})
    finally:
        if session_id:
            detached = await hub.detach_client(session_id, ws)
            if detached and host_id:
                if explicit_close:
                    await _close_backend_session(request.app, host_id, session_id)
                else:
                    await _schedule_backend_close(request.app, host_id, session_id)
    return ws
