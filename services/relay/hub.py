"""In-memory routing hub for daemon and client websockets.

The Hub owns transient routing state: which daemon socket serves which
host, which client sockets are attached to each session, and the cached
``session-open`` frame for each session (used to push state to a freshly
connected daemon).

Session events are sequenced and kept in a small replay buffer so a
second client, or a reconnecting client, can catch up without replacing
the existing viewer. Bounded send wrappers enforce backpressure by
closing only the slow socket, rather than letting the relay's event loop
wedge behind one consumer.
"""
from __future__ import annotations

import asyncio
import copy
import logging
import os
import time
from collections import deque
from dataclasses import dataclass

from aiohttp import web

log = logging.getLogger("relay.hub")

# Per-socket send timeout. If we can't push a frame within this window
# the socket is closed with code 1013 (try-again-later) and the
# offending side is expected to reconnect.
_SEND_TIMEOUT_SECONDS = 5.0
_SESSION_REPLAY_LIMIT = int(os.getenv("RELAY_SESSION_REPLAY_LIMIT", "1000"))


@dataclass
class ClientConnection:
    client_id: str
    ws: web.WebSocketResponse
    name: str
    connected_at: float


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
        self.session_clients: dict[str, dict[str, ClientConnection]] = {}
        self.session_host: dict[str, str] = {}
        self.session_open_frames: dict[str, dict] = {}
        self.client_close_tasks: dict[str, asyncio.Task] = {}
        self.session_seq: dict[str, int] = {}
        self.session_replay: dict[str, deque[dict]] = {}
        self._lock = asyncio.Lock()
        # request_id → Future awaiting the daemon's response frame
        self.pending_admin: dict[str, asyncio.Future] = {}
        # Latest telemetry snapshot per host (with relay-side receive ts)
        self.host_telemetry: dict[str, dict] = {}
        # session_id → True while a turn is running on the daemon side.
        # Updated by ws_daemon as it forwards turn-started / turn-completed.
        self.turn_in_flight: dict[str, bool] = {}
        # session_id → time.monotonic() of last activity (any daemon frame
        # or any client frame). Lets the grace loop distinguish "idle" from
        # "actively producing output" — so a long-running turn that's
        # streaming events stays alive past the soft 75s reconnect grace.
        self.session_activity: dict[str, float] = {}
        # (host_id, thread_id) → session_id, for "is there already a
        # session in flight for this thread?" lookup. The session stays
        # in this index until forget_session() is called.
        self.thread_session_index: dict[tuple[str, str], str] = {}
        # session_id → (host_id, thread_id) reverse so forget_session can
        # clean up the index efficiently.
        self.session_thread: dict[str, tuple[str, str]] = {}
        # approval_id → session_id while an approval is still answerable.
        # First response wins; later responses are ignored by ws_client.
        self.pending_approvals: dict[str, str] = {}

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

    async def attach_client(
        self,
        session_id: str,
        host_id: str,
        client_id: str,
        ws: web.WebSocketResponse,
        *,
        name: str = "client",
    ) -> int:
        old: ClientConnection | None = None
        close_task: asyncio.Task | None = None
        async with self._lock:
            clients = self.session_clients.setdefault(session_id, {})
            old = clients.get(client_id)
            clients[client_id] = ClientConnection(
                client_id=client_id,
                ws=ws,
                name=name,
                connected_at=time.time(),
            )
            close_task = self.client_close_tasks.pop(session_id, None)
            self.session_host[session_id] = host_id
            peer_count = sum(1 for conn in clients.values() if not conn.ws.closed)
        if close_task:
            close_task.cancel()
        if old is not None and old.ws is not ws and not old.ws.closed:
            await old.ws.close(code=4000, message=b"replaced")
        return peer_count

    async def detach_client(
        self,
        session_id: str,
        client_id: str | None = None,
        ws: web.WebSocketResponse | None = None,
    ) -> bool:
        """Detach one client. Returns True when no live clients remain."""
        async with self._lock:
            clients = self.session_clients.get(session_id)
            if not clients:
                return True
            remove_id: str | None = None
            if client_id and client_id in clients:
                conn = clients[client_id]
                if ws is not None and conn.ws is not ws:
                    return False
                remove_id = client_id
            elif ws is not None:
                for cid, conn in clients.items():
                    if conn.ws is ws:
                        remove_id = cid
                        break
            elif client_id is None:
                clients.clear()
                self.session_clients.pop(session_id, None)
                return True
            if remove_id is None:
                return False
            clients.pop(remove_id, None)
            if not clients:
                self.session_clients.pop(session_id, None)
                return True
            return not any(not conn.ws.closed for conn in clients.values())

    def daemon_for(self, host_id: str) -> web.WebSocketResponse | None:
        return self.daemons.get(host_id)

    def client_for(self, session_id: str) -> web.WebSocketResponse | None:
        for conn in self.session_clients.get(session_id, {}).values():
            if not conn.ws.closed:
                return conn.ws
        return None

    def client_count(self, session_id: str) -> int:
        return sum(
            1
            for conn in self.session_clients.get(session_id, {}).values()
            if not conn.ws.closed
        )

    def host_for_session(self, session_id: str) -> str | None:
        return self.session_host.get(session_id)

    def clients_for_host(self, host_id: str) -> list[web.WebSocketResponse]:
        """All client sockets currently attached to a session on this host."""
        return [
            conn.ws
            for sid, clients in self.session_clients.items()
            for conn in clients.values()
            if self.session_host.get(sid) == host_id and not conn.ws.closed
        ]

    async def remember_session_open(self, session_id: str, host_id: str, frame: dict) -> None:
        async with self._lock:
            self.session_host[session_id] = host_id
            self.session_open_frames[session_id] = dict(frame)

    async def ensure_session_open_frame(
        self,
        session_id: str,
        host_id: str,
        overrides: dict | None = None,
    ) -> tuple[dict, bool]:
        """Return the cached session-open frame, creating it atomically.

        The boolean is True only for the first caller; ws_client uses it
        to avoid sending duplicate session-open frames when multiple
        clients attach to the same newly-reserved session at once.

        Orchestrator-session overrides (kind, task, approval_policy,
        permissions, model, effort) ride along on the same session-open
        frame. The daemon side reads these to pick the right adapter.
        """
        async with self._lock:
            frame = self.session_open_frames.get(session_id)
            if frame is not None:
                return dict(frame), False
            frame = {"type": "session-open", "session_id": session_id}
            overrides = overrides or {}
            if overrides.get("thread_id"):
                frame["resume_thread_id"] = overrides["thread_id"]
            if overrides.get("cwd"):
                frame["cwd"] = overrides["cwd"]
            for k in ("kind", "task", "approval_policy", "permissions",
                      "model", "effort"):
                v = overrides.get(k)
                if v:
                    frame[k] = v
            self.session_host[session_id] = host_id
            self.session_open_frames[session_id] = dict(frame)
            return dict(frame), True

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

    async def record_session_frame(self, session_id: str, frame: dict) -> dict:
        """Attach a session-local sequence number and store replay state."""
        async with self._lock:
            seq = self.session_seq.get(session_id, 0) + 1
            self.session_seq[session_id] = seq
            out = copy.deepcopy(frame)
            out.setdefault("session_id", session_id)
            out["seq"] = seq
            replay = self.session_replay.setdefault(
                session_id,
                deque(maxlen=_SESSION_REPLAY_LIMIT),
            )
            replay.append(copy.deepcopy(out))
            return out

    async def replay_since(self, session_id: str, last_seq: int) -> list[dict]:
        async with self._lock:
            return [
                copy.deepcopy(frame)
                for frame in self.session_replay.get(session_id, ())
                if int(frame.get("seq") or 0) > last_seq
            ]

    def session_seq_value(self, session_id: str) -> int:
        return self.session_seq.get(session_id, 0)

    async def broadcast_to_session(
        self,
        session_id: str,
        frame: dict,
        *,
        record: bool = True,
    ) -> bool:
        """Send a frame to every live client attached to ``session_id``."""
        out = await self.record_session_frame(session_id, frame) if record else frame
        async with self._lock:
            clients = [
                conn.ws
                for conn in self.session_clients.get(session_id, {}).values()
                if not conn.ws.closed
            ]
        if not clients:
            return False
        results = await asyncio.gather(
            *(_bounded_send(ws, out, role="client") for ws in clients),
            return_exceptions=False,
        )
        return any(results)

    async def forward_to_daemon(self, host_id: str, frame: dict) -> bool:
        ws = self.daemons.get(host_id)
        if ws is None or ws.closed:
            return False
        return await _bounded_send(ws, frame, role="daemon")

    async def forget_session(self, session_id: str) -> None:
        current = asyncio.current_task()
        async with self._lock:
            self.session_clients.pop(session_id, None)
            self.session_host.pop(session_id, None)
            self.session_open_frames.pop(session_id, None)
            close_task = self.client_close_tasks.pop(session_id, None)
            self.session_seq.pop(session_id, None)
            self.session_replay.pop(session_id, None)
            self.turn_in_flight.pop(session_id, None)
            self.session_activity.pop(session_id, None)
            for approval_id, sid in list(self.pending_approvals.items()):
                if sid == session_id:
                    self.pending_approvals.pop(approval_id, None)
            key = self.session_thread.pop(session_id, None)
            if key is not None:
                # Only drop the index entry if it still points at us;
                # a fresh session for the same thread may have replaced it.
                if self.thread_session_index.get(key) == session_id:
                    self.thread_session_index.pop(key, None)
        if close_task and close_task is not current:
            close_task.cancel()

    def bump_activity(self, session_id: str) -> None:
        """Record that something happened on this session (daemon frame
        or client frame). Used by the grace loop to keep long-running
        turns alive while they're actively producing output."""
        self.session_activity[session_id] = time.monotonic()

    def mark_turn_started(self, session_id: str) -> None:
        self.turn_in_flight[session_id] = True
        self.bump_activity(session_id)

    def mark_turn_completed(self, session_id: str) -> None:
        self.turn_in_flight[session_id] = False
        self.bump_activity(session_id)

    async def try_begin_turn(self, session_id: str) -> bool:
        """Reserve the single active turn slot for a session."""
        async with self._lock:
            if self.turn_in_flight.get(session_id, False):
                return False
            self.turn_in_flight[session_id] = True
            self.session_activity[session_id] = time.monotonic()
            return True

    async def note_approval_request(self, session_id: str, approval_id: str) -> None:
        async with self._lock:
            self.pending_approvals[approval_id] = session_id

    async def resolve_approval(self, session_id: str, approval_id: str) -> bool:
        """Claim an approval. Returns False if another client got there first."""
        async with self._lock:
            if self.pending_approvals.get(approval_id) != session_id:
                return False
            self.pending_approvals.pop(approval_id, None)
            return True

    async def remember_session_thread(
        self,
        session_id: str,
        host_id: str,
        thread_id: str,
    ) -> None:
        """Register that this session is hosting a particular codex thread.
        The (host, thread) → session reverse index lets a reattaching client
        find the existing in-flight session instead of starting a new one."""
        async with self._lock:
            key = (host_id, thread_id)
            self.session_host[session_id] = host_id
            self.session_thread[session_id] = key
            self.thread_session_index[key] = session_id

    def active_session_for_thread(self, host_id: str, thread_id: str) -> str | None:
        """Return the live session_id hosting (host, thread), if any."""
        sid = self.thread_session_index.get((host_id, thread_id))
        if sid is None:
            return None
        # Sanity: it must still be tracked. (forget_session cleans up the
        # index, but we double-check in case of races.)
        if sid not in self.session_host:
            self.thread_session_index.pop((host_id, thread_id), None)
            return None
        return sid
