"""Daemon-side websocket: hello → welcome → frame loop → cleanup.

Frames the daemon sends:
- ``session-event`` / ``session-closed`` → broadcast to attached clients.
- ``threads-list-response`` / ``fs-*-response`` → resolves a pending REST future.
- ``host-telemetry`` → cached + fanned out to attached clients.
- ``ping`` → ``pong``.
"""
from __future__ import annotations

import asyncio
import json
import logging
import time

from aiohttp import WSMsgType, web

from ..hub import Hub
from ..limits import WS_MAX_MSG_SIZE
from ..logging import audit
from ..middleware.rate_limit import allow_ws_connection, client_remote
from ..store import Store


log = logging.getLogger("relay.ws.daemon")


async def _owns_session(hub: Hub, store: Store, host_id: str, session_id: str) -> bool:
    """Is this session really served by the daemon that just sent a frame?

    A valid bridge key for one host must not let it write into — or close —
    another host's sessions. The hub's live binding answers first; the
    store answers for sessions the hub has not cached (or has forgotten).
    """
    cached = hub.host_for_session(session_id)
    if cached is not None:
        return cached == host_id
    session = await store.session_info(session_id)
    return session is not None and session["host_id"] == host_id


async def ws_daemon(request: web.Request) -> web.WebSocketResponse:
    remote = client_remote(request)
    allowed, retry_after = allow_ws_connection(remote)
    if not allowed:
        raise web.HTTPTooManyRequests(
            reason="too many websocket connections",
            headers={"Retry-After": f"{max(1, int(retry_after))}"},
        )
    ws = web.WebSocketResponse(heartbeat=20, max_msg_size=WS_MAX_MSG_SIZE)
    await ws.prepare(request)
    store: Store = request.app["store"]
    hub: Hub = request.app["hub"]

    host_id: str | None = None
    try:
        first = await asyncio.wait_for(ws.receive(), timeout=10)
        if first.type != WSMsgType.TEXT:
            await ws.close(code=1008, message=b"expected hello")
            return ws
        hello = json.loads(first.data)
        if hello.get("type") != "hello":
            await ws.close(code=1008, message=b"expected hello")
            return ws
        token = hello.get("token", "")
        host_id = await store.resolve_bridge_key(token)
        if not host_id:
            await ws.send_json({"type": "error", "error": "invalid bridge token"})
            await ws.close(code=4401, message=b"invalid token")
            audit("auth.bridge.invalid", remote=remote)
            return ws

        await store.update_host_identity(
            host_id,
            hello.get("hostname", "") or "",
            hello.get("platform", "") or "",
            hello.get("os_user", "") or "",
            hello.get("home_dir", "") or "",
            hello.get("default_cwd", "") or "",
        )
        await store.mark_host(host_id, True)
        old_ws = await hub.attach_daemon(host_id, ws)
        if old_ws is not None and not old_ws.closed:
            await old_ws.close(code=4000, message=b"daemon-replaced")
        await ws.send_json({"type": "welcome", "host_id": host_id})
        log.info("daemon online", extra={
            "host_id": host_id,
            "hostname": hello.get("hostname"),
            "platform": hello.get("platform"),
        })
        audit("daemon.connected", host_id=host_id,
              hostname=hello.get("hostname"), platform=hello.get("platform"))
        for open_frame in await hub.session_open_frames_for_host(host_id):
            await ws.send_json(open_frame)

        async for msg in ws:
            if msg.type == WSMsgType.ERROR:
                # Usually an oversize frame (a file read past
                # WS_MAX_MSG_SIZE). aiohttp closes the socket before we see
                # it; say why on the way out rather than dropping silently.
                log.warning("daemon frame rejected", extra={
                    "host_id": host_id, "error": str(msg.data),
                })
                if not ws.closed:
                    await ws.send_json({
                        "type": "error",
                        "error": f"frame rejected (max {WS_MAX_MSG_SIZE} bytes)",
                    })
                break
            if msg.type != WSMsgType.TEXT:
                continue
            try:
                frame = json.loads(msg.data)
            except json.JSONDecodeError:
                continue
            ftype = frame.get("type")
            sid = frame.get("session_id")
            if ftype in {"session-event", "session-closed"} and sid:
                if not await _owns_session(hub, store, host_id, sid):
                    log.warning("daemon frame for foreign session", extra={
                        "host_id": host_id, "session_id": sid, "frame_type": ftype,
                    })
                    audit("daemon.session.foreign",
                          host_id=host_id, session_id=sid, frame_type=ftype)
                    continue
                # Track turn lifecycle + per-session activity so the
                # client-grace loop knows whether a session is idle or
                # actively producing output. Any daemon frame counts as
                # activity; turn-started/completed flip the in-flight bit.
                if ftype == "session-event":
                    hub.bump_activity(sid)
                    event = frame.get("event") or {}
                    kind = event.get("kind")
                    if kind == "turn-started":
                        hub.mark_turn_started(sid)
                    elif kind == "turn-completed":
                        hub.mark_turn_completed(sid)
                        await hub.clear_session_prompts(sid)
                    elif kind == "approval-request":
                        data = event.get("data") or {}
                        approval_id = data.get("approval_id")
                        if approval_id:
                            await hub.note_approval_request(sid, approval_id, data)
                    elif kind == "user-input-request":
                        data = event.get("data") or {}
                        call_id = data.get("call_id")
                        if call_id:
                            await hub.note_user_input_request(sid, call_id, data)
                elif ftype == "session-closed":
                    hub.mark_turn_completed(sid)
                    await hub.clear_session_prompts(sid)
                # Bounded fanout: close slow clients rather than letting
                # the daemon's event loop stall behind one consumer.
                await hub.broadcast_to_session(sid, frame)
                if ftype == "session-event":
                    event = frame.get("event") or {}
                    data = event.get("data") or {}
                    if event.get("kind") == "session-started" and isinstance(data, dict):
                        await hub.update_session_resume(
                            sid,
                            thread_id=data.get("thread_id"),
                            cwd=data.get("cwd"),
                        )
                        # Durable too: a relay restart must be able to
                        # rebuild the session-open frame from the row
                        # instead of resuming nothing.
                        await store.update_session_resume(
                            sid,
                            thread_id=data.get("thread_id"),
                            cwd=data.get("cwd"),
                        )
                        # Index this session under (host, thread) so a
                        # client tapping the same thread later can rejoin
                        # an in-flight turn instead of starting a new one.
                        thread_id = data.get("thread_id")
                        if thread_id:
                            await hub.remember_session_thread(sid, host_id, thread_id)
                if ftype == "session-closed":
                    await store.close_session(sid)
                    await hub.forget_session(sid)
                    audit("session.closed", session_id=sid, host_id=host_id)
            elif ftype in (
                "threads-list-response",
                "models-list-response",
                "fs-read-response",
                "fs-mkdir-response",
                "fs-readfile-response",
                "fs-delete-response",
                "fs-rename-response",
                "fs-write-response",
            ):
                req_id = frame.get("request_id")
                # Bound to this host: a daemon can only answer requests
                # that were addressed to it.
                if req_id:
                    hub.resolve_admin_request(host_id, req_id, frame)
            elif ftype == "host-telemetry":
                data = frame.get("data") or {}
                snapshot = {
                    "host_id": host_id,
                    "data": data,
                    "received_at": time.time(),
                }
                hub.host_telemetry[host_id] = snapshot
                hub.record_telemetry(host_id, snapshot["received_at"], data)
                # Fan out to any client sessions already attached to this host
                # so the UI updates in real time without having to poll.
                forward = {
                    "type": "host-telemetry",
                    "host_id": host_id,
                    "data": data,
                    "ts": snapshot["received_at"],
                }
                # Bounded + concurrent, same as session fanout: one slow
                # client gets closed instead of stalling this daemon's
                # frame loop behind a 3s telemetry cadence.
                await hub.broadcast_to_host_clients(host_id, forward)
            elif ftype == "ping":
                await ws.send_json({"type": "pong"})
    except asyncio.TimeoutError:
        await ws.close(code=4408, message=b"hello timeout")
    except Exception as exc:  # noqa: BLE001
        log.exception("daemon ws error", extra={"error": str(exc)})
    finally:
        if host_id:
            detached = await hub.detach_daemon(host_id, ws)
            if detached:
                await store.mark_host(host_id, False)
                hub.host_telemetry.pop(host_id, None)
                hub.host_telemetry_log.pop(host_id, None)
                log.info("daemon offline", extra={"host_id": host_id})
                audit("daemon.disconnected", host_id=host_id)
    return ws
