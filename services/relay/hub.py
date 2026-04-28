"""In-memory routing hub for daemon and client websockets.

The Hub owns three transient maps: which daemon socket serves which
host, which client socket serves which session, and the cached
``session-open`` frame for each session (used to replay state to a
reattaching client and to push state to a freshly-connected daemon).

``forward_to_client`` and ``forward_to_daemon`` are non-blocking,
bounded send wrappers — they enforce backpressure by closing a socket
that can't drain its outbound queue fast enough (rather than letting
the relay's event loop wedge behind a slow consumer).
"""
from __future__ import annotations

import asyncio
import logging

from aiohttp import web

log = logging.getLogger("relay.hub")

# Per-socket send timeout. If we can't push a frame within this window
# the socket is closed with code 1013 (try-again-later) and the
# offending side is expected to reconnect.
_SEND_TIMEOUT_SECONDS = 5.0


async def _bounded_send(ws: web.WebSocketResponse, frame: dict, *, role: str) -> bool:
    """Send a JSON frame with a timeout. On timeout/closed, close the
    socket and return False so the caller can drop further sends."""
    if ws.closed:
        return False
    try:
        await asyncio.wait_for(ws.send_json(frame), timeout=_SEND_TIMEOUT_SECONDS)
        return True
    except asyncio.TimeoutError:
        log.warning("ws send timed out", extra={"role": role})
        try:
            await ws.close(code=1013, message=b"slow consumer")
        except Exception:  # noqa: BLE001
            pass
        return False
    except Exception as exc:  # noqa: BLE001
        log.debug("ws send failed", extra={"role": role, "error": str(exc)})
        return False


class Hub:
    """Keeps track of live daemon sockets and live client sockets per session."""

    def __init__(self) -> None:
        self.daemons: dict[str, web.WebSocketResponse] = {}
        self.client_sessions: dict[str, web.WebSocketResponse] = {}
        self.session_host: dict[str, str] = {}
        self.session_open_frames: dict[str, dict] = {}
        self.client_close_tasks: dict[str, asyncio.Task] = {}
        self._lock = asyncio.Lock()
        # request_id → Future awaiting the daemon's response frame
        self.pending_admin: dict[str, asyncio.Future] = {}
        # Latest telemetry snapshot per host (with relay-side receive ts)
        self.host_telemetry: dict[str, dict] = {}

    async def attach_daemon(self, host_id: str, ws: web.WebSocketResponse) -> web.WebSocketResponse | None:
        async with self._lock:
            old = self.daemons.get(host_id)
            self.daemons[host_id] = ws
            return old if old is not ws else None

    async def detach_daemon(self, host_id: str, ws: web.WebSocketResponse | None = None) -> bool:
        async with self._lock:
            if ws is not None and self.daemons.get(host_id) is not ws:
                return False
            self.daemons.pop(host_id, None)
            return True

    async def attach_client(self, session_id: str, host_id: str, ws: web.WebSocketResponse) -> None:
        old: web.WebSocketResponse | None = None
        close_task: asyncio.Task | None = None
        async with self._lock:
            old = self.client_sessions.get(session_id)
            close_task = self.client_close_tasks.pop(session_id, None)
            self.client_sessions[session_id] = ws
            self.session_host[session_id] = host_id
        if close_task:
            close_task.cancel()
        if old is not None and old is not ws and not old.closed:
            await old.close(code=4000, message=b"replaced")

    async def detach_client(
        self,
        session_id: str,
        ws: web.WebSocketResponse | None = None,
    ) -> bool:
        async with self._lock:
            if ws is not None and self.client_sessions.get(session_id) is not ws:
                return False
            self.client_sessions.pop(session_id, None)
            return True

    def daemon_for(self, host_id: str) -> web.WebSocketResponse | None:
        return self.daemons.get(host_id)

    def client_for(self, session_id: str) -> web.WebSocketResponse | None:
        return self.client_sessions.get(session_id)

    def host_for_session(self, session_id: str) -> str | None:
        return self.session_host.get(session_id)

    def clients_for_host(self, host_id: str) -> list[web.WebSocketResponse]:
        """All client sockets currently attached to a session on this host."""
        return [
            ws
            for sid, ws in self.client_sessions.items()
            if self.session_host.get(sid) == host_id and not ws.closed
        ]

    async def remember_session_open(self, session_id: str, host_id: str, frame: dict) -> None:
        async with self._lock:
            self.session_host[session_id] = host_id
            self.session_open_frames[session_id] = dict(frame)

    async def update_session_resume(
        self,
        session_id: str,
        *,
        thread_id: str | None,
        cwd: str | None,
    ) -> None:
        async with self._lock:
            frame = self.session_open_frames.get(session_id)
            if not frame:
                return
            if thread_id:
                frame["resume_thread_id"] = thread_id
            if cwd:
                frame["cwd"] = cwd

    async def session_open_frame(self, session_id: str) -> dict | None:
        async with self._lock:
            frame = self.session_open_frames.get(session_id)
            return dict(frame) if frame else None

    async def session_open_frames_for_host(self, host_id: str) -> list[dict]:
        async with self._lock:
            return [
                dict(frame)
                for sid, frame in self.session_open_frames.items()
                if self.session_host.get(sid) == host_id
            ]

    async def schedule_session_close(self, session_id: str, task: asyncio.Task) -> None:
        async with self._lock:
            old = self.client_close_tasks.pop(session_id, None)
            self.client_close_tasks[session_id] = task
        if old and old is not task:
            old.cancel()

    async def forward_to_client(self, session_id: str, frame: dict) -> bool:
        """Send a frame to the client bound to ``session_id``. Returns
        False if the client is gone or the send timed out (the socket is
        closed in that case)."""
        ws = self.client_sessions.get(session_id)
        if ws is None or ws.closed:
            return False
        return await _bounded_send(ws, frame, role="client")

    async def forward_to_daemon(self, host_id: str, frame: dict) -> bool:
        ws = self.daemons.get(host_id)
        if ws is None or ws.closed:
            return False
        return await _bounded_send(ws, frame, role="daemon")

    async def forget_session(self, session_id: str) -> None:
        current = asyncio.current_task()
        async with self._lock:
            self.client_sessions.pop(session_id, None)
            self.session_host.pop(session_id, None)
            self.session_open_frames.pop(session_id, None)
            close_task = self.client_close_tasks.pop(session_id, None)
        if close_task and close_task is not current:
            close_task.cancel()
