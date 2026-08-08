"""Outbound WSS client — connects to the relay, runs per-session adapters."""
from __future__ import annotations

import asyncio
import json
import logging
import os
import random
import time
from typing import Awaitable, Callable

import aiohttp

from .adapters import (
    AdminCodex,
    SessionAdapter,
    build_adapter,
    model_options_from_codex,
)
from .config import Config
from .limits import MAX_FILE_BYTES, WS_MAX_MSG_SIZE
from .telemetry import TelemetryCollector, telemetry_loop

log = logging.getLogger("daemon.client")

# Bound on model/list pagination: enough for any real catalog, and a
# hostile or looping cursor can't spin the daemon forever.
_MODEL_LIST_MAX_PAGES = 10


def _oversize_error_frame(frame: dict, size: int) -> dict:
    """Replace a frame too big for the websocket with an error of the same
    shape, so the peer still gets an answer on the socket it is waiting on.

    Contract (A): oversize must produce an explicit error, never a silent
    socket drop. The routing fields (type, session_id, request_id, event
    kind) are kept so the relay and the clients route it as they would the
    real thing; only the payload is dropped.
    """
    error = (
        f"payload is {size} bytes; over the {WS_MAX_MSG_SIZE} byte websocket "
        "ceiling (REMOTEX_MAX_FILE_BYTES)"
    )
    out: dict = {"type": frame.get("type"), "error": error}
    for key in ("session_id", "request_id", "path"):
        if frame.get(key):
            out[key] = frame[key]
    if frame.get("type") == "session-event":
        event = frame.get("event") or {}
        out["event"] = {
            "kind": event.get("kind"),
            "data": {"error": error},
            "ts": event.get("ts") or time.time(),
        }
    return out


class DaemonClient:
    def __init__(self, config: Config) -> None:
        self.config = config
        self._sessions: dict[str, _SessionRunner] = {}
        # Persistent admin codex for read-only ops (thread/list). Lazy
        # spawn on first use; kept alive between calls so we don't eat
        # node startup cost on every thread-list request.
        self._admin = AdminCodex(codex_binary=config.codex_binary)
        self._telemetry = TelemetryCollector()

    async def run(self) -> None:
        backoff = 1.0
        while True:
            try:
                await self._run_once()
                backoff = 1.0
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001
                # Some aiohttp WS errors have empty str(); log the class
                # too so crashes aren't silent in systemd journals.
                msg = str(exc) or "<no message>"
                log.warning(
                    "connection lost: %s: %s (retry in %.1fs)",
                    type(exc).__name__, msg, backoff,
                )
                await asyncio.sleep(backoff + random.uniform(0, min(1.0, backoff * 0.25)))
                backoff = min(backoff * 2, 30.0)

    async def _run_once(self) -> None:
        timeout = aiohttp.ClientTimeout(total=None, sock_connect=15)
        async with aiohttp.ClientSession(timeout=timeout) as session:
            async with session.ws_connect(
                self.config.relay_url,
                heartbeat=20,
                max_msg_size=WS_MAX_MSG_SIZE,
            ) as ws:
                await ws.send_json({
                    "type": "hello",
                    "token": self.config.bridge_token,
                    "hostname": self.config.hostname,
                    "platform": self.config.platform_string,
                    "nickname": self.config.nickname,
                    "os_user": self.config.os_user,
                    "home_dir": os.path.expanduser("~"),
                    "default_cwd": self.config.default_cwd,
                })
                welcome = await ws.receive()
                if welcome.type != aiohttp.WSMsgType.TEXT:
                    raise RuntimeError(f"unexpected welcome frame type {welcome.type}")
                data = json.loads(welcome.data)
                if data.get("type") == "error":
                    raise RuntimeError(f"relay rejected hello: {data.get('error')}")
                if data.get("type") != "welcome":
                    raise RuntimeError(f"expected welcome, got {data}")
                log.info("attached to relay as %s", data.get("host_id"))

                send_lock = asyncio.Lock()

                async def send(frame: dict) -> None:
                    # Size is enforced here, on the sending side, because
                    # the receiving side cannot report it: aiohttp closes
                    # the socket before the handler sees the oversize
                    # frame, and that takes every session on this host with
                    # it. Substituting an error keeps the socket alive.
                    payload = json.dumps(frame)
                    if len(payload) > WS_MAX_MSG_SIZE:
                        log.warning(
                            "frame %s is %d bytes; over the %d byte ceiling",
                            frame.get("type"), len(payload), WS_MAX_MSG_SIZE,
                        )
                        payload = json.dumps(_oversize_error_frame(frame, len(payload)))
                    async with send_lock:
                        await ws.send_str(payload)

                telemetry_task = asyncio.create_task(
                    telemetry_loop(self._telemetry, send),
                    name="daemon-telemetry",
                )
                try:
                    async for msg in ws:
                        if msg.type == aiohttp.WSMsgType.ERROR:
                            # Almost always an oversize relay→daemon frame.
                            # Raise so run() logs a reason and backs off,
                            # instead of returning as if the relay had
                            # closed cleanly.
                            raise RuntimeError(f"relay frame rejected: {msg.data}")
                        if msg.type != aiohttp.WSMsgType.TEXT:
                            continue
                        try:
                            frame = json.loads(msg.data)
                        except json.JSONDecodeError:
                            continue
                        await self._dispatch(frame, send)
                finally:
                    # Always tear down sessions when the WS ends, whether
                    # it ended cleanly or by exception. Leaving them alive
                    # leaks codex app-server subprocesses because their
                    # adapter.stop() is what terminates the child.
                    telemetry_task.cancel()
                    try:
                        await telemetry_task
                    except (asyncio.CancelledError, Exception):  # noqa: BLE001
                        pass
                    await self._close_all_sessions()

    async def _close_all_sessions(self) -> None:
        runners = list(self._sessions.values())
        self._sessions.clear()
        for runner in runners:
            try:
                await asyncio.wait_for(runner.stop(), timeout=5.0)
            except Exception as exc:  # noqa: BLE001
                log.warning(
                    "session %s: stop failed (%s: %s)",
                    runner.session_id, type(exc).__name__, exc,
                )

    async def _dispatch(self, frame: dict, send: Callable[[dict], Awaitable[None]]) -> None:
        ftype = frame.get("type")
        sid = frame.get("session_id")
        if ftype == "threads-list-request":
            asyncio.create_task(self._handle_threads_list(frame, send))
            return
        if ftype == "models-list-request":
            asyncio.create_task(self._handle_models_list(frame, send))
            return
        if ftype == "fs-read-request":
            asyncio.create_task(self._handle_fs_read(frame, send))
            return
        if ftype == "fs-mkdir-request":
            asyncio.create_task(self._handle_fs_mkdir(frame, send))
            return
        if ftype == "fs-readfile-request":
            asyncio.create_task(self._handle_fs_readfile(frame, send))
            return
        if ftype == "fs-delete-request":
            asyncio.create_task(self._handle_fs_delete(frame, send))
            return
        if ftype == "fs-rename-request":
            asyncio.create_task(self._handle_fs_rename(frame, send))
            return
        if ftype == "fs-write-request":
            asyncio.create_task(self._handle_fs_write(frame, send))
            return
        if ftype == "session-open" and sid:
            if sid in self._sessions:
                return
            await self._open_session(sid, frame, send)
        elif ftype == "session-close" and sid:
            runner = self._sessions.pop(sid, None)
            if runner:
                asyncio.create_task(runner.stop())
        elif sid and sid in self._sessions:
            runner = self._sessions[sid]
            try:
                await runner.handle(frame)
            except Exception as exc:  # noqa: BLE001
                # Contain the blast radius to this session: an adapter
                # raising here used to escape the ws read loop and take
                # every other session on the host down with it.
                log.warning(
                    "session %s: frame %s failed (%s: %s)",
                    sid, ftype, type(exc).__name__, exc,
                )
                self._sessions.pop(sid, None)
                await self._send_session_error(sid, send, exc)
                # runner.stop() ends the pump, whose finally emits the
                # terminal session-closed frame for this session.
                asyncio.create_task(runner.stop())
        else:
            log.debug("ignoring frame %s", frame)

    async def _open_session(
        self,
        sid: str,
        frame: dict,
        send: Callable[[dict], Awaitable[None]],
    ) -> None:
        """Build + start one session's adapter, keeping any failure local.

        A bad cwd, a missing codex binary or a codex that never answers
        `initialize` must fail this session only — never the websocket
        loop, and never the other sessions on this host.
        """
        try:
            adapter = build_adapter(
                self.config.mode,
                self.config.codex_binary,
                # session-open can carry a per-session override; fall back
                # to the daemon's config default.
                default_cwd=frame.get("cwd") or self.config.default_cwd,
                resume_thread_id=frame.get("resume_thread_id") or None,
                kind=(frame.get("kind") or "codex"),
            )
        except Exception as exc:  # noqa: BLE001
            log.warning("session %s: adapter build failed (%s: %s)", sid, type(exc).__name__, exc)
            await self._send_session_error(sid, send, exc, closed=True)
            return

        runner = _SessionRunner(sid, adapter, send, on_exit=lambda: self._sessions.pop(sid, None))
        try:
            await runner.start()
        except Exception as exc:  # noqa: BLE001
            log.warning("session %s: start failed (%s: %s)", sid, type(exc).__name__, exc)
            # start() failed before the pump existed, so nothing else will
            # emit session-closed — and a half-spawned codex child would
            # otherwise be left running.
            try:
                await asyncio.wait_for(adapter.stop(), timeout=5.0)
            except Exception as stop_exc:  # noqa: BLE001
                log.debug("session %s: cleanup after failed start: %s", sid, stop_exc)
            await self._send_session_error(sid, send, exc, closed=True)
            return
        # Only track a runner that actually started; a failed start must
        # not leave a zombie entry that swallows later session-opens.
        self._sessions[sid] = runner

    async def _send_session_error(
        self,
        sid: str,
        send: Callable[[dict], Awaitable[None]],
        exc: BaseException,
        *,
        closed: bool = False,
    ) -> None:
        """Tell the client why its session died.

        turn-completed is the kind every client already renders (and it
        releases the relay's in-flight turn slot), so the error rides on
        that rather than a kind older clients would drop. ``closed``
        adds the terminal session-closed frame for the paths where no
        pump exists to emit it.
        """
        detail = str(exc) or type(exc).__name__
        frames: list[dict] = [{
            "type": "session-event",
            "session_id": sid,
            "event": {
                "kind": "turn-completed",
                "data": {"error": f"session failed: {detail}"},
                "ts": time.time(),
            },
        }]
        if closed:
            frames.append({"type": "session-closed", "session_id": sid})
        for frame in frames:
            try:
                await send(frame)
            except Exception as send_exc:  # noqa: BLE001 — ws teardown race
                log.debug("session %s: could not report error: %s", sid, send_exc)
                return

    async def _handle_fs_read(
        self,
        frame: dict,
        send: Callable[[dict], Awaitable[None]],
    ) -> None:
        request_id = frame.get("request_id")
        path = frame.get("path") or ""
        try:
            result = await self._admin.read_directory(path)
            await send({
                "type": "fs-read-response",
                "request_id": request_id,
                "path": path,
                "entries": result.get("entries", []),
            })
        except Exception as exc:  # noqa: BLE001
            log.warning("fs/readDirectory failed for %s: %s", path, exc)
            await send({
                "type": "fs-read-response",
                "request_id": request_id,
                "path": path,
                "error": str(exc),
            })

    async def _handle_fs_mkdir(
        self,
        frame: dict,
        send: Callable[[dict], Awaitable[None]],
    ) -> None:
        """Create a directory. We do this directly with os.makedirs rather
        than going through codex, since the app-server doesn't expose a
        mkdir RPC. Path is expected to be absolute; the daemon trusts the
        relay to have authenticated the caller."""
        import os

        request_id = frame.get("request_id")
        parent = frame.get("path") or ""
        name = (frame.get("name") or "").strip()

        def _abs_join(p: str, n: str) -> str:
            # Guard against path traversal in the `name`; keep it to a
            # single segment of safe characters. Slashes/.. would allow
            # writing outside the chosen parent.
            if not n or "/" in n or n in (".", ".."):
                raise ValueError("invalid folder name")
            return os.path.join(p, n)

        try:
            target = _abs_join(parent, name)
            os.makedirs(target, exist_ok=False)
            await send({
                "type": "fs-mkdir-response",
                "request_id": request_id,
                "path": target,
            })
        except FileExistsError:
            await send({
                "type": "fs-mkdir-response",
                "request_id": request_id,
                "error": "folder already exists",
            })
        except Exception as exc:  # noqa: BLE001
            log.warning("fs/mkdir failed for %s/%s: %s", parent, name, exc)
            await send({
                "type": "fs-mkdir-response",
                "request_id": request_id,
                "error": str(exc),
            })

    async def _handle_fs_readfile(
        self,
        frame: dict,
        send: Callable[[dict], Awaitable[None]],
    ) -> None:
        """Read a file off the daemon's local filesystem and return it
        base64-encoded so the relay can hand it to a client. Capped at
        REMOTEX_MAX_FILE_BYTES (25MB default); bigger files come back
        with an error rather than being truncated, so the client can
        decide whether to chunk or skip. The cap is what keeps the
        base64 payload inside the websocket message ceiling — a bigger
        frame would be dropped by aiohttp, killing the connection and
        every session on this host with it."""
        import base64
        import mimetypes
        import os

        request_id = frame.get("request_id")
        path = frame.get("path") or ""
        requested = int(frame.get("max_bytes") or MAX_FILE_BYTES)
        max_bytes = min(requested, MAX_FILE_BYTES)
        try:
            if not path or not os.path.isfile(path):
                raise FileNotFoundError(path or "<empty path>")
            size = os.path.getsize(path)
            if size > max_bytes:
                raise ValueError(
                    f"file is {size} bytes; max is {max_bytes}. "
                    "Use download or chunked read."
                )
            with open(path, "rb") as fh:
                data = fh.read()
            mime = mimetypes.guess_type(path)[0] or "application/octet-stream"
            await send({
                "type": "fs-readfile-response",
                "request_id": request_id,
                "path": path,
                "name": os.path.basename(path),
                "mime": mime,
                "size": size,
                "base64": base64.b64encode(data).decode("ascii"),
            })
        except Exception as exc:  # noqa: BLE001
            log.warning("fs/readfile failed for %s: %s", path, exc)
            await send({
                "type": "fs-readfile-response",
                "request_id": request_id,
                "path": path,
                "error": str(exc),
            })

    async def _handle_fs_delete(
        self,
        frame: dict,
        send: Callable[[dict], Awaitable[None]],
    ) -> None:
        """Delete a single FILE (not a directory). Refuses recursive
        directory removal in v1 — too easy to footgun. Symlinks are
        unlinked, not followed."""
        import os

        request_id = frame.get("request_id")
        path = frame.get("path") or ""
        try:
            if not path:
                raise ValueError("path is required")
            if os.path.isdir(path) and not os.path.islink(path):
                raise IsADirectoryError(
                    "directory deletion is disabled (use a terminal for that)"
                )
            os.unlink(path)
            await send({
                "type": "fs-delete-response",
                "request_id": request_id,
                "path": path,
            })
        except Exception as exc:  # noqa: BLE001
            log.warning("fs/delete failed for %s: %s", path, exc)
            await send({
                "type": "fs-delete-response",
                "request_id": request_id,
                "path": path,
                "error": str(exc),
            })

    async def _handle_fs_rename(
        self,
        frame: dict,
        send: Callable[[dict], Awaitable[None]],
    ) -> None:
        """Move/rename a file. Refuses to overwrite an existing target —
        the client should confirm and re-issue with a different name."""
        import os

        request_id = frame.get("request_id")
        src = frame.get("from") or ""
        dst = frame.get("to") or ""
        try:
            if not src or not dst:
                raise ValueError("from and to are required")
            if os.path.exists(dst):
                raise FileExistsError(f"{dst} already exists")
            os.rename(src, dst)
            await send({
                "type": "fs-rename-response",
                "request_id": request_id,
                "from": src,
                "to": dst,
            })
        except Exception as exc:  # noqa: BLE001
            log.warning("fs/rename failed for %s -> %s: %s", src, dst, exc)
            await send({
                "type": "fs-rename-response",
                "request_id": request_id,
                "from": src,
                "to": dst,
                "error": str(exc),
            })

    async def _handle_fs_write(
        self,
        frame: dict,
        send: Callable[[dict], Awaitable[None]],
    ) -> None:
        """Write a file to disk from a base64 payload. Used by the
        client's "+ Add file" upload action — distinct from image
        attachment, which is per-turn context, not workspace files."""
        import base64
        import os

        request_id = frame.get("request_id")
        path = frame.get("path") or ""
        b64 = frame.get("base64") or ""
        try:
            if not path:
                raise ValueError("path is required")
            # base64 decodes to ~3/4 of its length; reject before we
            # allocate the decoded copy.
            if len(b64) // 4 * 3 > MAX_FILE_BYTES:
                raise ValueError(
                    f"upload exceeds the {MAX_FILE_BYTES} byte limit "
                    "(REMOTEX_MAX_FILE_BYTES)"
                )
            parent = os.path.dirname(path) or "."
            if not os.path.isdir(parent):
                raise FileNotFoundError(f"parent directory does not exist: {parent}")
            data = base64.b64decode(b64, validate=False)
            if len(data) > MAX_FILE_BYTES:
                raise ValueError(
                    f"upload is {len(data)} bytes; max is {MAX_FILE_BYTES} "
                    "(REMOTEX_MAX_FILE_BYTES)"
                )
            # Atomic-ish write: write to .partial, fsync, rename.
            tmp = path + ".partial"
            with open(tmp, "wb") as fh:
                fh.write(data)
                fh.flush()
                try:
                    os.fsync(fh.fileno())
                except OSError:
                    pass
            os.replace(tmp, path)
            await send({
                "type": "fs-write-response",
                "request_id": request_id,
                "path": path,
                "size": len(data),
            })
        except Exception as exc:  # noqa: BLE001
            log.warning("fs/write failed for %s: %s", path, exc)
            await send({
                "type": "fs-write-response",
                "request_id": request_id,
                "path": path,
                "error": str(exc),
            })

    async def _handle_threads_list(
        self,
        frame: dict,
        send: Callable[[dict], Awaitable[None]],
    ) -> None:
        request_id = frame.get("request_id")
        limit = int(frame.get("limit") or 20)
        cursor = frame.get("cursor")
        try:
            result = await self._admin.list_threads(limit=limit, cursor=cursor)
            await send({
                "type": "threads-list-response",
                "request_id": request_id,
                "threads": result.get("data", []),
                "next_cursor": result.get("nextCursor"),
            })
        except Exception as exc:  # noqa: BLE001
            log.exception("thread/list failed")
            error = str(exc) or type(exc).__name__
            await send({
                "type": "threads-list-response",
                "request_id": request_id,
                "error": error,
            })

    async def _handle_models_list(
        self,
        frame: dict,
        send: Callable[[dict], Awaitable[None]],
    ) -> None:
        """Answer the relay's models-list-request from this host's codex.

        The relay keeps its static list as the fallback, so an error here
        is not fatal — it just means the picker shows the built-in list.
        """
        request_id = frame.get("request_id")
        try:
            # codex pages model/list with an opaque nextCursor and picks its
            # own page size; stopping at page one would serve a silently
            # truncated list as if it were the host's whole catalog.
            data: list = []
            cursor: str | None = None
            for _ in range(_MODEL_LIST_MAX_PAGES):
                page = await self._admin.list_models(cursor=cursor)
                data.extend(page.get("data") or [])
                cursor = page.get("nextCursor") or None
                if not cursor:
                    break
            models = model_options_from_codex({"data": data})
            if len(models) <= 1:
                # Only the "codex picks" sentinel came back — nothing
                # worth overriding the relay's static list with.
                raise RuntimeError("codex returned no models")
            await send({
                "type": "models-list-response",
                "request_id": request_id,
                "models": models,
            })
        except Exception as exc:  # noqa: BLE001
            log.warning("model/list failed: %s", exc)
            await send({
                "type": "models-list-response",
                "request_id": request_id,
                "error": str(exc) or type(exc).__name__,
            })


class _SessionRunner:
    def __init__(
        self,
        session_id: str,
        adapter: SessionAdapter,
        send: Callable[[dict], Awaitable[None]],
        on_exit: Callable[[], None],
    ) -> None:
        self.session_id = session_id
        self.adapter = adapter
        self._send = send
        self._on_exit = on_exit
        self._task: asyncio.Task | None = None

    async def start(self) -> None:
        await self.adapter.start()
        self._task = asyncio.create_task(self._pump())

    async def handle(self, frame: dict) -> None:
        await self.adapter.handle(frame)

    async def stop(self) -> None:
        await self.adapter.stop()
        if self._task:
            try:
                await self._task
            except asyncio.CancelledError:
                pass

    async def _pump(self) -> None:
        try:
            async for ev in self.adapter.events():
                await self._send(ev.to_frame(self.session_id))
        except asyncio.CancelledError:
            pass
        except Exception as exc:  # noqa: BLE001
            # Nothing awaits this task, so an escaping exception would only
            # ever surface as a GC-time warning — and the finally below
            # would drop the runner while codex kept running.
            log.warning(
                "session %s: event pump failed (%s: %s)",
                self.session_id, type(exc).__name__, exc,
            )
        finally:
            # stop() is the only thing that terminates the codex child, and
            # on the exception path nobody else will call it: the runner is
            # about to be dropped from _sessions, so _close_all_sessions
            # would never see it.
            try:
                await asyncio.wait_for(self.adapter.stop(), timeout=5.0)
            except Exception as stop_exc:  # noqa: BLE001
                log.debug("session %s: adapter stop failed: %s", self.session_id, stop_exc)
            try:
                await self._send({"type": "session-closed", "session_id": self.session_id})
            except Exception:  # noqa: BLE001 — shutdown race with the ws is expected
                pass
            self._on_exit()
