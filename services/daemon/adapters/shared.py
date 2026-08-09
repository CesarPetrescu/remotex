"""One JSON-RPC connection to Codex's shared Unix control socket.

The socket carries ordinary WebSocket frames over a Unix-domain stream.  One
connection is shared by every active Remotex session on the host; responses are
routed by request id, while notifications and server requests are routed by
``threadId``.
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import stat
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path
from typing import Awaitable, Callable

import aiohttp

log = logging.getLogger("daemon.adapters.shared")

MessageHandler = Callable[[dict], Awaitable[None]]
DisconnectHandler = Callable[[BaseException], Awaitable[None]]

_RESUME_BUFFER_LIMIT = 10_000


@dataclass
class _ThreadListener:
    handler: MessageHandler
    root_thread_id: str
    paused: bool = False
    buffered: deque[dict] = field(default_factory=deque)


class SharedCodexConnection:
    """Multiplex JSON-RPC over Codex's WebSocket-over-UDS transport."""

    def __init__(
        self,
        socket_path: Path,
        *,
        start_command: list[str] | None = None,
    ) -> None:
        self.socket_path = Path(socket_path)
        self._start_command = list(start_command) if start_command else None
        self.user_agent = "codex app-server"
        self._session: aiohttp.ClientSession | None = None
        self._ws: aiohttp.ClientWebSocketResponse | None = None
        self._reader_task: asyncio.Task | None = None
        self._pending: dict[int, asyncio.Future] = {}
        self._pending_thread_starts: dict[int, MessageHandler] = {}
        self._listeners: dict[str, _ThreadListener] = {}
        self._orphan_tasks: set[asyncio.Task] = set()
        self._next_id = 0
        self._send_lock = asyncio.Lock()
        self._closing = False
        self._on_disconnect: DisconnectHandler | None = None
        self._failure: BaseException | None = None

    @property
    def is_open(self) -> bool:
        return (
            self._ws is not None
            and not self._ws.closed
            and self._reader_task is not None
            and not self._reader_task.done()
        )

    def set_disconnect_handler(self, handler: DisconnectHandler | None) -> None:
        self._on_disconnect = handler
        if handler is not None and self._failure is not None:
            asyncio.create_task(handler(self._failure))

    async def start(self) -> None:
        if self.is_open:
            return
        if os.name != "posix":
            raise RuntimeError("shared Codex mode requires a Unix host")
        await self._ensure_socket()
        mode = self.socket_path.stat().st_mode
        if not stat.S_ISSOCK(mode):
            raise RuntimeError(f"Codex control path is not a socket: {self.socket_path}")

        self._closing = False
        self._failure = None
        connector = aiohttp.UnixConnector(path=str(self.socket_path))
        timeout = aiohttp.ClientTimeout(total=None, sock_connect=10)
        self._session = aiohttp.ClientSession(connector=connector, timeout=timeout)
        try:
            # A resume response can inline a very large rollout.  The socket
            # is local and owner-protected, so disable aiohttp's message cap
            # just as the stdio transport uses an unbounded line reader.
            self._ws = await self._session.ws_connect(
                "http://localhost/rpc",
                heartbeat=20,
                max_msg_size=0,
            )
            self._reader_task = asyncio.create_task(
                self._read_loop(), name="codex-shared-reader"
            )
            init = await self.request("initialize", {
                "clientInfo": {
                    "name": "remotex-daemon",
                    "title": "Remotex",
                    "version": "0.1.0",
                },
                "capabilities": {"experimentalApi": True},
            }, timeout=15.0)
            self.user_agent = str(init.get("userAgent") or self.user_agent)
            await self.notify("initialized", {})
            log.info("connected to shared Codex socket %s", self.socket_path)
        except BaseException:
            await self.close()
            raise

    async def _ensure_socket(self) -> None:
        try:
            self.socket_path.stat()
            return
        except FileNotFoundError:
            pass
        if self._start_command is None:
            raise RuntimeError(
                f"Codex control socket not found at {self.socket_path}; "
                "run `codex app-server daemon start` first"
            )
        log.info("starting Codex app-server daemon for shared mode")
        try:
            process = await asyncio.create_subprocess_exec(
                *self._start_command,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
            stdout, stderr = await asyncio.wait_for(
                process.communicate(), timeout=20.0
            )
        except asyncio.TimeoutError as exc:
            process.terminate()
            try:
                await asyncio.wait_for(process.wait(), timeout=2.0)
            except asyncio.TimeoutError:
                process.kill()
                await process.wait()
            raise RuntimeError("timed out starting the Codex app-server daemon") from exc
        if process.returncode != 0:
            detail = (stderr or stdout).decode(errors="replace").strip()
            raise RuntimeError(
                "could not start Codex app-server daemon"
                + (f": {detail[-1000:]}" if detail else "")
            )
        try:
            mode = self.socket_path.stat().st_mode
        except FileNotFoundError as exc:
            raise RuntimeError(
                f"Codex daemon reported success but {self.socket_path} is missing"
            ) from exc
        if not stat.S_ISSOCK(mode):
            raise RuntimeError(f"Codex control path is not a socket: {self.socket_path}")

    async def close(self) -> None:
        self._closing = True
        self._listeners.clear()
        for task in self._orphan_tasks:
            task.cancel()
        self._orphan_tasks.clear()
        self._fail_pending(RuntimeError("shared Codex connection closed"))

        ws, self._ws = self._ws, None
        if ws is not None and not ws.closed:
            await ws.close()

        reader, self._reader_task = self._reader_task, None
        if reader is not None and reader is not asyncio.current_task():
            reader.cancel()
            try:
                await reader
            except (asyncio.CancelledError, Exception):  # noqa: BLE001
                pass

        session, self._session = self._session, None
        if session is not None:
            await session.close()

    async def request(self, method: str, params: dict, *, timeout: float = 60.0) -> dict:
        return await self._request(method, params, timeout=timeout)

    async def start_thread(self, params: dict, handler: MessageHandler) -> dict:
        """Start a thread and claim its id before later frames are routed."""
        return await self._request(
            "thread/start", params, timeout=60.0, thread_handler=handler
        )

    async def _request(
        self,
        method: str,
        params: dict,
        *,
        timeout: float,
        thread_handler: MessageHandler | None = None,
    ) -> dict:
        if not self.is_open:
            raise RuntimeError("shared Codex connection is not open")
        self._next_id += 1
        request_id = self._next_id
        future = asyncio.get_running_loop().create_future()
        self._pending[request_id] = future
        if thread_handler is not None:
            self._pending_thread_starts[request_id] = thread_handler
        try:
            await self.send({"id": request_id, "method": method, "params": params})
            return await asyncio.wait_for(future, timeout=timeout)
        finally:
            self._pending.pop(request_id, None)
            self._pending_thread_starts.pop(request_id, None)

    async def notify(self, method: str, params: dict) -> None:
        await self.send({"method": method, "params": params})

    async def send(self, message: dict) -> None:
        ws = self._ws
        if ws is None or ws.closed:
            raise RuntimeError("shared Codex connection is not open")
        payload = json.dumps(message, separators=(",", ":"))
        async with self._send_lock:
            await ws.send_str(payload)

    async def prepare_resume(self, thread_id: str, handler: MessageHandler) -> None:
        """Reset any implicit subscription, then pause routing for hydrate.

        Codex automatically subscribes every initialized connection to newly
        created threads.  Unsubscribing first gives ``thread/resume`` a clean
        snapshot boundary: frames queued before the response cannot be
        duplicated when the adapter hydrates the active turn.
        """
        if thread_id in self._listeners:
            raise RuntimeError(f"Codex thread is already attached: {thread_id}")
        await self.request("thread/unsubscribe", {"threadId": thread_id}, timeout=15.0)
        self.register(thread_id, handler, paused=True)

    def register(
        self,
        thread_id: str,
        handler: MessageHandler,
        *,
        paused: bool = False,
    ) -> None:
        current = self._listeners.get(thread_id)
        if current is not None and current.handler != handler:
            raise RuntimeError(f"Codex thread is already attached: {thread_id}")
        self._listeners[thread_id] = _ThreadListener(
            handler=handler,
            root_thread_id=thread_id,
            paused=paused,
        )

    async def activate(self, thread_id: str, handler: MessageHandler) -> None:
        """Release notifications buffered behind a resume snapshot."""
        listener = self._listeners.get(thread_id)
        if listener is None or listener.handler != handler:
            raise RuntimeError(f"Codex thread is not attached: {thread_id}")
        while listener.buffered:
            message = listener.buffered.popleft()
            await self._deliver(listener.handler, message)
        # No await between observing an empty queue and flipping this flag,
        # so the reader cannot slip a frame into the buffer after the drain.
        listener.paused = False

    async def unregister(self, thread_id: str, handler: MessageHandler) -> None:
        listener = self._listeners.get(thread_id)
        if listener is None or listener.handler != handler:
            return
        thread_ids = [
            candidate
            for candidate, current in self._listeners.items()
            if current.root_thread_id == thread_id and current.handler == handler
        ]
        for candidate in thread_ids:
            self._listeners.pop(candidate, None)
        if not self.is_open:
            return
        await asyncio.gather(
            *(self._unsubscribe(candidate) for candidate in thread_ids),
            return_exceptions=True,
        )

    async def _read_loop(self) -> None:
        failure: BaseException | None = None
        try:
            assert self._ws is not None
            async for frame in self._ws:
                if frame.type == aiohttp.WSMsgType.TEXT:
                    try:
                        message = json.loads(frame.data)
                    except json.JSONDecodeError:
                        log.debug("malformed shared Codex frame: %r", frame.data[:200])
                        continue
                    await self._route(message)
                elif frame.type == aiohttp.WSMsgType.ERROR:
                    raise RuntimeError(f"shared Codex websocket error: {frame.data}")
            if not self._closing:
                raise RuntimeError(
                    f"shared Codex websocket closed (code={self._ws.close_code})"
                )
        except asyncio.CancelledError:
            if not self._closing:
                failure = RuntimeError("shared Codex reader was cancelled")
        except BaseException as exc:  # noqa: BLE001 — propagated through callback/futures
            failure = exc
            log.warning("shared Codex connection failed: %s", exc)
        finally:
            if failure is not None:
                self._failure = failure
                self._fail_pending(failure)
                handler = self._on_disconnect
                if handler is not None:
                    try:
                        await handler(failure)
                    except Exception as exc:  # noqa: BLE001
                        log.debug("shared disconnect handler failed: %s", exc)

    async def _route(self, message: dict) -> None:
        if "id" in message and ("result" in message or "error" in message):
            future = self._pending.get(message["id"])
            if future is not None and not future.done():
                if "error" in message:
                    error = message.get("error") or {}
                    future.set_exception(RuntimeError(
                        f"{error.get('code')}: {error.get('message')}"
                    ))
                else:
                    result = message.get("result") or {}
                    handler = self._pending_thread_starts.get(message["id"])
                    if handler is not None:
                        thread = result.get("thread") or {}
                        thread_id = thread.get("id")
                        if not isinstance(thread_id, str) or not thread_id:
                            future.set_exception(RuntimeError(
                                "thread/start response did not include a thread id"
                            ))
                            return
                        try:
                            self.register(thread_id, handler)
                        except Exception as exc:  # noqa: BLE001
                            future.set_exception(exc)
                            return
                    future.set_result(result)
            return

        thread_id = self._thread_id(message)
        if message.get("method") == "thread/started" and thread_id:
            thread = (message.get("params") or {}).get("thread") or {}
            parent_id = thread.get("parentThreadId")
            parent = self._listeners.get(parent_id) if parent_id else None
            if parent is not None and thread_id not in self._listeners:
                # Native Codex subagent notifications belong on the parent
                # transcript.  Share one listener/buffer so resume ordering is
                # preserved across parent and child frames.
                self._listeners[thread_id] = parent
            elif thread_id not in self._listeners:
                self._schedule_orphan_unsubscribe(thread_id)
        listener = self._listeners.get(thread_id) if thread_id else None
        if listener is None:
            # New local TUI threads are implicitly subscribed by Codex.  Keep
            # their traffic private until a Remotex session explicitly claims
            # the thread; importantly, do not answer their server requests.
            return
        if listener.paused:
            if len(listener.buffered) >= _RESUME_BUFFER_LIMIT:
                raise RuntimeError(
                    f"resume buffer overflow for Codex thread {thread_id}"
                )
            listener.buffered.append(message)
            return
        await self._deliver(listener.handler, message)

    def _schedule_orphan_unsubscribe(self, thread_id: str) -> None:
        async def unsubscribe_if_unclaimed() -> None:
            # A Remotex thread/start response normally precedes its
            # thread/started notification, but wait for all in-flight starts
            # so an unusual ordering cannot unsubscribe the thread we own.
            while self._pending_thread_starts and self.is_open:
                await asyncio.sleep(0.05)
            if thread_id not in self._listeners and self.is_open:
                await self._unsubscribe(thread_id)

        task = asyncio.create_task(
            unsubscribe_if_unclaimed(), name=f"codex-unsubscribe-{thread_id}"
        )
        self._orphan_tasks.add(task)
        task.add_done_callback(self._orphan_tasks.discard)

    async def _unsubscribe(self, thread_id: str) -> None:
        try:
            await self.request(
                "thread/unsubscribe", {"threadId": thread_id}, timeout=3.0
            )
        except Exception as exc:  # noqa: BLE001 — cleanup is best-effort
            log.debug("thread/unsubscribe failed for %s: %s", thread_id, exc)

    @staticmethod
    def _thread_id(message: dict) -> str | None:
        params = message.get("params") or {}
        thread_id = params.get("threadId") or params.get("thread_id")
        if isinstance(thread_id, str) and thread_id:
            return thread_id
        thread = params.get("thread")
        if isinstance(thread, dict) and isinstance(thread.get("id"), str):
            return thread["id"]
        return None

    @staticmethod
    async def _deliver(handler: MessageHandler, message: dict) -> None:
        try:
            await handler(message)
        except Exception as exc:  # noqa: BLE001 — one session cannot kill all threads
            log.warning("shared Codex thread handler failed: %s", exc)

    def _fail_pending(self, exc: BaseException) -> None:
        for future in self._pending.values():
            if not future.done():
                future.set_exception(exc)
        self._pending.clear()
        self._pending_thread_starts.clear()
