"""Outbound WSS client — connects to the relay, runs per-session adapters."""
from __future__ import annotations

import asyncio
import json
import logging
import random
from typing import Awaitable, Callable

import aiohttp

from .adapters import AdminCodex, SessionAdapter, build_adapter
from .config import Config
from .telemetry import TelemetryCollector, telemetry_loop

log = logging.getLogger("daemon.client")


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
            async with session.ws_connect(self.config.relay_url, heartbeat=20) as ws:
                await ws.send_json({
                    "type": "hello",
                    "token": self.config.bridge_token,
                    "hostname": self.config.hostname,
                    "platform": self.config.platform_string,
                    "nickname": self.config.nickname,
                    "os_user": self.config.os_user,
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
                    async with send_lock:
                        await ws.send_json(frame)

                telemetry_task = asyncio.create_task(
                    telemetry_loop(self._telemetry, send),
                    name="daemon-telemetry",
                )
                try:
                    async for msg in ws:
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

    async def close(self) -> None:
        """Shut the admin codex down on graceful exit."""
        await self._admin.close()

    async def _dispatch(self, frame: dict, send: Callable[[dict], Awaitable[None]]) -> None:
        ftype = frame.get("type")
        sid = frame.get("session_id")
        if ftype == "threads-list-request":
            asyncio.create_task(self._handle_threads_list(frame, send))
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
            adapter = build_adapter(
                self.config.mode,
                self.config.codex_binary,
                # session-open can carry a per-session override; fall back
                # to the daemon's config default.
                default_cwd=frame.get("cwd") or self.config.default_cwd,
                resume_thread_id=frame.get("resume_thread_id") or None,
            )
            runner = _SessionRunner(sid, adapter, send, on_exit=lambda: self._sessions.pop(sid, None))
            self._sessions[sid] = runner
            await runner.start()
        elif ftype == "session-close" and sid:
            runner = self._sessions.pop(sid, None)
            if runner:
                asyncio.create_task(runner.stop())
        elif sid and sid in self._sessions:
            await self._sessions[sid].handle(frame)
        else:
            log.debug("ignoring frame %s", frame)

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
        50MB; bigger files come back with an error rather than being
        truncated, so the client can decide whether to chunk or skip."""
        import base64
        import mimetypes
        import os

        request_id = frame.get("request_id")
        path = frame.get("path") or ""
        max_bytes = int(frame.get("max_bytes") or 50 * 1024 * 1024)
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
            parent = os.path.dirname(path) or "."
            if not os.path.isdir(parent):
                raise FileNotFoundError(f"parent directory does not exist: {parent}")
            data = base64.b64decode(b64, validate=False)
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
            await send({
                "type": "threads-list-response",
                "request_id": request_id,
                "error": str(exc),
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
        finally:
            try:
                await self._send({"type": "session-closed", "session_id": self.session_id})
            except Exception:  # noqa: BLE001 — shutdown race with the ws is expected
                pass
            self._on_exit()
