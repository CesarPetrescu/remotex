"""Daemon-side websocket: hello → welcome → frame loop → cleanup.

Frames the daemon sends:
- ``session-event`` / ``session-closed`` → forwarded to the bound client.
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
from ..logging import audit
from ..store import Store


log = logging.getLogger("relay.ws.daemon")

_SEARCH_EVENT_KINDS = {
    "session-started",
    "turn-started",
    "item-completed",
    "turn-completed",
    "history-end",
}


def _search_should_capture(frame: dict) -> bool:
    event = frame.get("event")
    if not isinstance(event, dict):
        return False
    return event.get("kind") in _SEARCH_EVENT_KINDS


async def ws_daemon(request: web.Request) -> web.WebSocketResponse:
    ws = web.WebSocketResponse(heartbeat=20)
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
            audit("auth.bridge.invalid", remote=request.remote)
            return ws

        await store.update_host_identity(
            host_id,
            hello.get("hostname", "") or "",
            hello.get("platform", "") or "",
            hello.get("os_user", "") or "",
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
            if msg.type != WSMsgType.TEXT:
                continue
            try:
                frame = json.loads(msg.data)
            except json.JSONDecodeError:
                continue
            ftype = frame.get("type")
            sid = frame.get("session_id")
            if ftype in {"session-event", "session-closed"} and sid:
                # Bounded send: close slow client rather than letting the
                # daemon's event loop stall behind it.
                await hub.forward_to_client(sid, frame)
                session = await store.session_info(sid)
                if session and ftype == "session-event" and _search_should_capture(frame):
                    request.app["search"].capture_session_event(session, frame)
                    event = frame.get("event") or {}
                    data = event.get("data") or {}
                    if event.get("kind") == "session-started" and isinstance(data, dict):
                        await hub.update_session_resume(
                            sid,
                            thread_id=data.get("thread_id"),
                            cwd=data.get("cwd"),
                        )
                if ftype == "session-closed":
                    await store.close_session(sid)
                    await hub.forget_session(sid)
                    if session:
                        request.app["search"].capture_session_closed(session)
                    audit("session.closed", session_id=sid, host_id=host_id)
            elif ftype in ("threads-list-response", "fs-read-response", "fs-mkdir-response"):
                req_id = frame.get("request_id")
                fut = hub.pending_admin.get(req_id) if req_id else None
                if fut is not None and not fut.done():
                    fut.set_result(frame)
            elif ftype == "host-telemetry":
                data = frame.get("data") or {}
                snapshot = {
                    "host_id": host_id,
                    "data": data,
                    "received_at": time.time(),
                }
                hub.host_telemetry[host_id] = snapshot
                # Fan out to any client sessions already attached to this host
                # so the UI updates in real time without having to poll.
                forward = {
                    "type": "host-telemetry",
                    "host_id": host_id,
                    "data": data,
                    "ts": snapshot["received_at"],
                }
                for client_ws in hub.clients_for_host(host_id):
                    try:
                        await client_ws.send_json(forward)
                    except Exception as exc:  # noqa: BLE001
                        log.debug("telemetry fanout failed", extra={"error": str(exc)})
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
                log.info("daemon offline", extra={"host_id": host_id})
                audit("daemon.disconnected", host_id=host_id)
    return ws
