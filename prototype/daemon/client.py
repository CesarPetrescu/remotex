"""Outbound WSS client — connects to the relay, runs per-session adapters."""
from __future__ import annotations

import asyncio
import json
import logging
from typing import Awaitable, Callable

import aiohttp

from .adapters import SessionAdapter, build_adapter
from .config import Config

log = logging.getLogger("daemon.client")


class DaemonClient:
    def __init__(self, config: Config) -> None:
        self.config = config
        self._sessions: dict[str, _SessionRunner] = {}

    async def run(self) -> None:
        backoff = 1.0
        while True:
            try:
                await self._run_once()
                backoff = 1.0
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001
                log.warning("connection lost: %s (retry in %.1fs)", exc, backoff)
                await asyncio.sleep(backoff)
                backoff = min(backoff * 2, 30.0)

    async def _run_once(self) -> None:
        async with aiohttp.ClientSession() as session:
            async with session.ws_connect(self.config.relay_url, heartbeat=20) as ws:
                await ws.send_json({
                    "type": "hello",
                    "token": self.config.bridge_token,
                    "hostname": self.config.hostname,
                    "platform": self.config.platform_string,
                    "nickname": self.config.nickname,
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

                async for msg in ws:
                    if msg.type != aiohttp.WSMsgType.TEXT:
                        continue
                    try:
                        frame = json.loads(msg.data)
                    except json.JSONDecodeError:
                        continue
                    await self._dispatch(frame, send)

                # clean up any lingering sessions
                for runner in list(self._sessions.values()):
                    await runner.stop()
                self._sessions.clear()

    async def _dispatch(self, frame: dict, send: Callable[[dict], Awaitable[None]]) -> None:
        ftype = frame.get("type")
        sid = frame.get("session_id")
        if ftype == "session-open" and sid:
            if sid in self._sessions:
                return
            adapter = build_adapter(self.config.mode, self.config.codex_binary)
            runner = _SessionRunner(sid, adapter, send, on_exit=lambda: self._sessions.pop(sid, None))
            self._sessions[sid] = runner
            await runner.start()
        elif sid and sid in self._sessions:
            await self._sessions[sid].handle(frame)
        else:
            log.debug("ignoring frame %s", frame)


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
