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
# At least one frame: a maxlen-0 deque would make every eviction check
# index an empty buffer, and every session frame would raise.
_SESSION_REPLAY_LIMIT = max(1, int(os.getenv("RELAY_SESSION_REPLAY_LIMIT", "1000")))

# Outcome of one bounded send. "unknown" is the timeout case: aiohttp
# writes the frame to the transport *before* awaiting the drain, so a
# timeout does not mean the peer never got it — callers that would
# otherwise undo their side of the exchange must not.
SEND_OK = "sent"
SEND_FAILED = "unsent"
SEND_UNKNOWN = "unknown"


@dataclass
class ClientConnection:
    client_id: str
    ws: web.WebSocketResponse
    name: str
    connected_at: float


@dataclass
class PendingPrompt:
    """An unanswered approval / user-input request.

    ``order`` is a global monotonic counter so clients can render the
    prompts as a queue (oldest first) — and so a claim that has to be
    handed back after a failed forward returns to its original position
    instead of jumping to the tail.

    ``gen`` is the session's prompt generation at the time the prompt was
    recorded. A restore whose generation has moved on (the turn completed,
    the session closed) is dropped rather than resurrecting a prompt codex
    has already discarded.
    """
    prompt_id: str
    session_id: str
    data: dict
    order: int
    gen: int = 0


async def _send_status(ws: web.WebSocketResponse, frame: dict, *, role: str) -> str:
    """Send a JSON frame with a timeout, reporting what we know about
    delivery. Closed/raising sockets are ``SEND_FAILED`` (nothing left);
    a timeout is ``SEND_UNKNOWN`` — the frame is already on the transport
    and may still arrive, so it must not be treated as undone."""
    if ws.closed:
        return SEND_FAILED
    try:
        await asyncio.wait_for(ws.send_json(frame), timeout=_SEND_TIMEOUT_SECONDS)
        return SEND_OK
    except asyncio.TimeoutError:
        log.warning("ws send timed out", extra={"role": role})
        try:
            await ws.close(code=1013, message=b"slow consumer")
        except Exception:  # noqa: BLE001
            pass
        return SEND_UNKNOWN
    except Exception as exc:  # noqa: BLE001
        log.debug("ws send failed", extra={"role": role, "error": str(exc)})
        return SEND_FAILED


async def _bounded_send(ws: web.WebSocketResponse, frame: dict, *, role: str) -> bool:
    return await _send_status(ws, frame, role=role) == SEND_OK


class Hub:
    """Keeps track of live daemon sockets and live client sockets per session."""

    def __init__(self) -> None:
        self.daemons: dict[str, web.WebSocketResponse] = {}
        # Socket identity -> daemon Codex mode.  The mode belongs to the
        # connection, not just the host: a replacement handshake installs the
        # new socket before the old handler finishes unwinding.
        self._daemon_modes: dict[int, str] = {}
        # A freshly authenticated daemon is installed as the current socket
        # before the old one is closed, but it must not receive client/admin
        # frames until welcome + every cached session-open frame are queued.
        # Key by host *and* socket identity so overlapping handshakes cannot
        # mark a newer connection ready by mistake.
        self._unready_daemons: dict[str, web.WebSocketResponse] = {}
        self.session_clients: dict[str, dict[str, ClientConnection]] = {}
        self.session_host: dict[str, str] = {}
        self.session_open_frames: dict[str, dict] = {}
        self.client_close_tasks: dict[str, asyncio.Task] = {}
        self.session_seq: dict[str, int] = {}
        self.session_replay: dict[str, deque[dict]] = {}
        # session_id → highest seq that fell out of the replay deque, so a
        # reconnecting client can be told what it will never receive.
        self.session_replay_evicted: dict[str, int] = {}
        self._lock = asyncio.Lock()
        # (host_id, request_id) → Future awaiting the daemon's response
        # frame. Keyed by host so a daemon can only answer its own host's
        # in-flight REST requests.
        self.pending_admin: dict[tuple[str, str], asyncio.Future] = {}
        # Latest telemetry snapshot per host (with relay-side receive ts)
        self.host_telemetry: dict[str, dict] = {}
        # Short rolling history (~30s) per host so a freshly-connected client
        # gets a full graph immediately instead of drawing in over 30 seconds.
        self.host_telemetry_log: dict[str, deque] = {}
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
        # (session_id, approval_id) → the unanswered prompt. First response
        # wins; later responses are ignored by ws_client. Keyed by session
        # for the same reason pending_admin is keyed by host: the id comes
        # off a daemon frame, so a flat namespace would let one user's
        # daemon clobber (and permanently wedge) another user's prompt.
        self.pending_approvals: dict[tuple[str, str], PendingPrompt] = {}
        # (session_id, call_id) → prompt for Codex request_user_input.
        # Mirrors pending_approvals so replayed/stale plan-mode dialogs
        # cannot be answered twice by different clients.
        self.pending_user_inputs: dict[tuple[str, str], PendingPrompt] = {}
        # A Codex-side `serverRequest/resolved` can arrive while a client has
        # temporarily claimed a prompt and is forwarding its answer.  Keep a
        # per-prompt tombstone so a failed send cannot restore that now-dead
        # request. Separate sets avoid invalidating unrelated prompts in the
        # same turn.
        self._invalidated_approvals: set[tuple[str, str]] = set()
        self._invalidated_user_inputs: set[tuple[str, str]] = set()
        # session_id → prompt generation, bumped whenever a session's
        # prompts are invalidated wholesale (turn completed, session gone).
        self.session_prompt_gen: dict[str, int] = {}
        self._prompt_counter = 0

    async def attach_daemon(
        self,
        host_id: str,
        ws: web.WebSocketResponse,
        *,
        ready: bool = True,
        mode: str = "stdio",
    ) -> web.WebSocketResponse | None:
        async with self._lock:
            old = self.daemons.get(host_id)
            self.daemons[host_id] = ws
            self._daemon_modes[id(ws)] = mode
            if ready:
                self._unready_daemons.pop(host_id, None)
            else:
                self._unready_daemons[host_id] = ws
            return old if old is not ws else None

    async def mark_daemon_ready(
        self,
        host_id: str,
        ws: web.WebSocketResponse,
    ) -> bool:
        """Publish a handshaking daemon only if it is still the newest."""
        async with self._lock:
            if self.daemons.get(host_id) is not ws:
                return False
            if self._unready_daemons.get(host_id) is ws:
                self._unready_daemons.pop(host_id, None)
            return True

    async def detach_daemon(self, host_id: str, ws: web.WebSocketResponse | None = None) -> bool:
        async with self._lock:
            if ws is not None and self.daemons.get(host_id) is not ws:
                self._daemon_modes.pop(id(ws), None)
                return False
            removed = self.daemons.pop(host_id, None)
            if removed is not None:
                self._daemon_modes.pop(id(removed), None)
            if ws is None or self._unready_daemons.get(host_id) is ws:
                self._unready_daemons.pop(host_id, None)
            return True

    def daemon_mode_for(self, ws: web.WebSocketResponse | None) -> str:
        """Return the mode advertised by this exact daemon socket."""
        return self._daemon_modes.get(id(ws), "stdio") if ws is not None else "stdio"

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

    def daemon_for(
        self,
        host_id: str,
        *,
        include_unready: bool = False,
    ) -> web.WebSocketResponse | None:
        ws = self.daemons.get(host_id)
        if (
            not include_unready
            and ws is not None
            and self._unready_daemons.get(host_id) is ws
        ):
            return None
        return ws

    def client_for(self, session_id: str) -> web.WebSocketResponse | None:
        for conn in self.session_clients.get(session_id, {}).values():
            if not conn.ws.closed:
                return conn.ws
        return None

    def host_for_session(self, session_id: str) -> str | None:
        return self.session_host.get(session_id)

    def record_telemetry(self, host_id: str, received_at: float, data: dict) -> None:
        # maxlen 20 ~= 60s at the daemon's 3s cadence; reads window to 30s.
        self.host_telemetry_log.setdefault(host_id, deque(maxlen=20)).append((received_at, data))

    def recent_telemetry(self, host_id: str, now: float, window_s: float = 30.0) -> list[dict]:
        """Oldest-first samples from the last `window_s` seconds, each tagged
        with its age so the client can place it on a clock-skew-proof axis."""
        log = self.host_telemetry_log.get(host_id)
        if not log:
            return []
        return [
            {"age_ms": int(max(0.0, now - ts) * 1000), "data": d}
            for ts, d in log
            if now - ts <= window_s
        ]

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

        Session overrides (kind, resume thread, cwd) ride along on the
        same session-open frame.
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
            for k in ("kind",):
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
            if replay and replay.maxlen is not None and len(replay) == replay.maxlen:
                # This append evicts the oldest frame; remember how far the
                # buffer has been truncated so replay_gap() can report it.
                self.session_replay_evicted[session_id] = int(replay[0].get("seq") or 0)
            replay.append(copy.deepcopy(out))
            return out

    async def replay_since(self, session_id: str, last_seq: int) -> list[dict]:
        async with self._lock:
            return [
                copy.deepcopy(frame)
                for frame in self.session_replay.get(session_id, ())
                if int(frame.get("seq") or 0) > last_seq
            ]

    async def current_seq(self, session_id: str) -> int:
        """Highest seq this Hub has stamped on the session, 0 if none.

        ``seq`` is minted per Hub instance, so a client that stored a
        cursor before a relay restart can present one far ahead of the new
        counter. Callers use this to detect that and reset to 0 instead of
        silently replaying nothing.
        """
        async with self._lock:
            return self.session_seq.get(session_id, 0)

    async def replay_gap(self, session_id: str, last_seq: int) -> tuple[int, int] | None:
        """Frames the client asked for that the buffer no longer holds.

        Returns ``(missed_from, missed_to)`` inclusive, or None when the
        replay is complete. ``last_seq <= 0`` still reports a gap when the
        buffer has evicted anything: a client asking for everything from
        the start gets the tail, and a truncated transcript must never
        render as a complete one (contract C).
        """
        async with self._lock:
            evicted = self.session_replay_evicted.get(session_id, 0)
        if evicted <= max(last_seq, 0):
            return None
        return max(last_seq, 0) + 1, evicted

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

    async def broadcast_to_host_clients(self, host_id: str, frame: dict) -> bool:
        """Send a host-scoped frame (telemetry) to every client attached to
        any session on that host. Same bounded-send policy as
        ``broadcast_to_session``: a slow consumer gets closed, it never
        stalls the daemon's frame loop. Not sequenced — host frames are
        top-level, not session events."""
        async with self._lock:
            targets: dict[int, web.WebSocketResponse] = {}
            for sid, clients in self.session_clients.items():
                if self.session_host.get(sid) != host_id:
                    continue
                for conn in clients.values():
                    if not conn.ws.closed:
                        targets[id(conn.ws)] = conn.ws
        if not targets:
            return False
        results = await asyncio.gather(
            *(_bounded_send(ws, frame, role="client") for ws in targets.values()),
            return_exceptions=False,
        )
        return any(results)

    async def forward_to_daemon_status(self, host_id: str, frame: dict) -> str:
        """Forward to a host's daemon, reporting delivery as one of
        ``SEND_OK`` / ``SEND_FAILED`` / ``SEND_UNKNOWN``. Callers that would
        undo state on failure need the distinction — only ``SEND_FAILED``
        means the frame provably never left this process."""
        ws = self.daemon_for(host_id)
        if ws is None or ws.closed:
            return SEND_FAILED
        return await _send_status(ws, frame, role="daemon")

    async def forward_to_daemon(self, host_id: str, frame: dict) -> bool:
        return await self.forward_to_daemon_status(host_id, frame) == SEND_OK

    def register_admin_request(self, host_id: str, request_id: str) -> asyncio.Future:
        """Register a REST→daemon request and return the future its response
        will resolve. Keyed by (host, request_id) so only the daemon that
        was asked can answer."""
        fut: asyncio.Future = asyncio.get_running_loop().create_future()
        self.pending_admin[(host_id, request_id)] = fut
        return fut

    def discard_admin_request(self, host_id: str, request_id: str) -> None:
        self.pending_admin.pop((host_id, request_id), None)

    def resolve_admin_request(self, host_id: str, request_id: str, frame: dict) -> bool:
        fut = self.pending_admin.get((host_id, request_id))
        if fut is None or fut.done():
            return False
        fut.set_result(frame)
        return True

    async def forget_session(self, session_id: str) -> None:
        current = asyncio.current_task()
        async with self._lock:
            self.session_clients.pop(session_id, None)
            self.session_host.pop(session_id, None)
            self.session_open_frames.pop(session_id, None)
            close_task = self.client_close_tasks.pop(session_id, None)
            self.session_seq.pop(session_id, None)
            self.session_replay.pop(session_id, None)
            self.session_replay_evicted.pop(session_id, None)
            self.turn_in_flight.pop(session_id, None)
            self.session_activity.pop(session_id, None)
            self._drop_session_prompts(session_id)
            self._drop_session_prompt_tombstones(session_id)
            self.session_prompt_gen.pop(session_id, None)
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

    async def abort_host_turns(self, host_id: str) -> list[str]:
        """Clear turns that cannot survive a daemon connection change.

        An isolated stdio/mock adapter loses its Codex turn when the relay
        socket closes. The relay keeps the session-open frames so a reconnect
        can resume each thread, but the old *turn* and its prompts are gone.
        Shared adapters deliberately do not call this method; their managed
        app-server survives and a later resume snapshot reconciles the lock.

        Return the session ids whose clients need a synthetic
        ``turn-completed`` event.  State is cleared under one lock so a prompt
        response cannot race with the connection handoff and resurrect a
        request owned by the dead Codex process.
        """
        now = time.monotonic()
        async with self._lock:
            session_ids = [
                sid for sid, bound_host in self.session_host.items()
                if bound_host == host_id
            ]
            prompt_sessions = {
                sid for sid, _prompt_id in self.pending_approvals
            } | {
                sid for sid, _prompt_id in self.pending_user_inputs
            }
            interrupted: list[str] = []
            for sid in session_ids:
                if self.turn_in_flight.get(sid, False) or sid in prompt_sessions:
                    interrupted.append(sid)
                self.turn_in_flight[sid] = False
                self.session_activity[sid] = now
                self._drop_session_prompts(sid)
                self.session_prompt_gen[sid] = (
                    self.session_prompt_gen.get(sid, 0) + 1
                )
                self._drop_session_prompt_tombstones(sid)
            return interrupted

    def _drop_session_prompts(self, session_id: str) -> None:
        """Forget every prompt for a session. Caller holds the lock."""
        for key in [k for k in self.pending_approvals if k[0] == session_id]:
            self.pending_approvals.pop(key, None)
        for key in [k for k in self.pending_user_inputs if k[0] == session_id]:
            self.pending_user_inputs.pop(key, None)

    def _drop_session_prompt_tombstones(self, session_id: str) -> None:
        """Forget per-prompt invalidations after a generation change."""
        self._invalidated_approvals = {
            key for key in self._invalidated_approvals if key[0] != session_id
        }
        self._invalidated_user_inputs = {
            key for key in self._invalidated_user_inputs if key[0] != session_id
        }

    async def clear_session_prompts(self, session_id: str) -> None:
        """Invalidate a session's prompts (the turn ended, or codex is gone).

        Bumping the generation is what stops an in-flight ``restore_*`` from
        resurrecting one afterwards: codex has already discarded the
        request, so a restored prompt could never be answered.
        """
        async with self._lock:
            self._drop_session_prompts(session_id)
            self.session_prompt_gen[session_id] = (
                self.session_prompt_gen.get(session_id, 0) + 1
            )
            self._drop_session_prompt_tombstones(session_id)

    async def invalidate_host_prompts(self, host_id: str) -> list[str]:
        """Drop adapter-owned prompt ids while preserving shared turns.

        A server request can survive in Codex's managed app-server, but the
        Remotex approval/call id belongs to the adapter connection that just
        disappeared. The resumed adapter receives Codex's replay and assigns a
        fresh id, so retaining the old prompt would make it unanswerable and
        duplicate the replayed request.

        Return sessions whose authoritative prompt snapshot must be cleared
        in attached clients.
        """
        async with self._lock:
            session_ids = {
                sid
                for sid, bound_host in self.session_host.items()
                if bound_host == host_id
            }
            prompt_sessions = {
                sid for sid, _prompt_id in self.pending_approvals
            } | {
                sid for sid, _prompt_id in self.pending_user_inputs
            }
            affected = sorted(session_ids & prompt_sessions)
            for sid in affected:
                self._drop_session_prompts(sid)
                self.session_prompt_gen[sid] = (
                    self.session_prompt_gen.get(sid, 0) + 1
                )
                self._drop_session_prompt_tombstones(sid)
            return affected

    async def try_begin_turn(self, session_id: str) -> bool:
        """Reserve the single active turn slot for a session."""
        async with self._lock:
            if self.turn_in_flight.get(session_id, False):
                return False
            self.turn_in_flight[session_id] = True
            self.session_activity[session_id] = time.monotonic()
            return True

    def _note_prompt(
        self,
        pending: dict[tuple[str, str], PendingPrompt],
        invalidated: set[tuple[str, str]],
        session_id: str,
        prompt_id: str,
        data: dict | None,
    ) -> None:
        """Record an unanswered prompt. Caller holds the lock."""
        key = (session_id, prompt_id)
        # IDs should be unique, but if Codex deliberately reuses one, this
        # new request supersedes the tombstone for the old request.
        invalidated.discard(key)
        self._prompt_counter += 1
        pending[key] = PendingPrompt(
            prompt_id=prompt_id,
            session_id=session_id,
            data=copy.deepcopy(data or {}),
            order=self._prompt_counter,
            gen=self.session_prompt_gen.setdefault(session_id, 0),
        )

    def _restore_prompt(
        self,
        pending: dict[tuple[str, str], PendingPrompt],
        invalidated: set[tuple[str, str]],
        prompt: PendingPrompt,
    ) -> None:
        """Put a claim back. Caller holds the lock.

        Dropped when the session's prompts have been invalidated since the
        claim was taken — the generation moved (turn completed) or the
        session was forgotten entirely (no generation at all). Re-inserting
        then would leave a prompt nobody can ever answer, and for a dead
        session it would leak past every cleanup path.
        """
        key = (prompt.session_id, prompt.prompt_id)
        if (
            key in invalidated
            or self.session_prompt_gen.get(prompt.session_id) != prompt.gen
        ):
            return
        pending.setdefault(key, prompt)

    async def note_approval_request(
        self,
        session_id: str,
        approval_id: str,
        data: dict | None = None,
    ) -> None:
        async with self._lock:
            self._note_prompt(
                self.pending_approvals,
                self._invalidated_approvals,
                session_id,
                approval_id,
                data,
            )

    async def resolve_approval(self, session_id: str, approval_id: str) -> PendingPrompt | None:
        """Claim an approval. Returns the stored prompt, or None if another
        client got there first. Hand the claim back with
        ``restore_approval`` if the forward to the daemon fails."""
        async with self._lock:
            return self.pending_approvals.pop((session_id, approval_id), None)

    async def restore_approval(self, prompt: PendingPrompt) -> None:
        """Un-claim an approval whose response never reached the daemon, so
        any client can answer it again. Keeps its original queue position."""
        async with self._lock:
            self._restore_prompt(
                self.pending_approvals,
                self._invalidated_approvals,
                prompt,
            )

    async def invalidate_approval(self, session_id: str, approval_id: str) -> None:
        """Prevent a Codex-resolved approval from being restored by a race."""
        async with self._lock:
            key = (session_id, approval_id)
            self.pending_approvals.pop(key, None)
            self._invalidated_approvals.add(key)

    async def note_user_input_request(
        self,
        session_id: str,
        call_id: str,
        data: dict | None = None,
    ) -> None:
        async with self._lock:
            self._note_prompt(
                self.pending_user_inputs,
                self._invalidated_user_inputs,
                session_id,
                call_id,
                data,
            )

    async def resolve_user_input(self, session_id: str, call_id: str) -> PendingPrompt | None:
        """Claim a request_user_input prompt. First response wins."""
        async with self._lock:
            return self.pending_user_inputs.pop((session_id, call_id), None)

    async def restore_user_input(self, prompt: PendingPrompt) -> None:
        """Un-claim a user-input prompt whose response never reached the
        daemon. Keeps its original queue position."""
        async with self._lock:
            self._restore_prompt(
                self.pending_user_inputs,
                self._invalidated_user_inputs,
                prompt,
            )

    async def invalidate_user_input(self, session_id: str, call_id: str) -> None:
        """Prevent a Codex-resolved input request from racing back alive."""
        async with self._lock:
            key = (session_id, call_id)
            self.pending_user_inputs.pop(key, None)
            self._invalidated_user_inputs.add(key)

    async def pending_prompt_snapshot(self, session_id: str) -> dict:
        """Return current unresolved prompt requests for an attaching client.

        Replay is sequence-based, so a browser refresh after seeing a prompt
        would otherwise skip the old request while losing its React state.
        This synthetic frame is not recorded and does not advance seq.

        Both lists are queues, oldest first: clients render the head and a
        second concurrent prompt must never hide the first.
        """
        async with self._lock:
            pending_approvals = [
                p for key, p in self.pending_approvals.items() if key[0] == session_id
            ]
            pending_inputs = [
                p for key, p in self.pending_user_inputs.items() if key[0] == session_id
            ]
            approvals: list[dict] = []
            user_inputs: list[dict] = []
            for prompt in sorted(pending_approvals, key=lambda p: p.order):
                data = copy.deepcopy(prompt.data)
                data.setdefault("approval_id", prompt.prompt_id)
                data["replayed"] = True
                # The relay's arrival order travels with the queue: a claim
                # handed back after a failed forward has to land back in its
                # old slot on every client, not at the tail (contract F).
                data["order"] = prompt.order
                approvals.append(data)
            for prompt in sorted(pending_inputs, key=lambda p: p.order):
                data = copy.deepcopy(prompt.data)
                data.setdefault("call_id", prompt.prompt_id)
                data["replayed"] = True
                data["order"] = prompt.order
                user_inputs.append(data)
            return {
                "type": "pending-prompts",
                "session_id": session_id,
                "approvals": approvals,
                "user_inputs": user_inputs,
            }

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
