"""Per-orchestrator-session Unix socket server.

The MCP shim (mcp_shim.py) is a child process spawned by codex. When
codex calls a tool (e.g. ``submit_plan``), the shim forwards a JSON-RPC
request over a Unix socket to this server, which dispatches to the
OrchestratorRuntime and returns the result.

One BridgeServer per orchestrator session — the socket path embeds the
session id so multiple orchestrators on the same daemon don't collide.
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
from pathlib import Path
from typing import Awaitable, Callable

log = logging.getLogger("daemon.orchestrator.bridge")

# Where we put the per-session sockets. Falls back to /tmp when the
# user runtime dir isn't available (root usually doesn't have one).
def _socket_dir() -> Path:
    base = os.environ.get("XDG_RUNTIME_DIR")
    if base and os.path.isdir(base):
        return Path(base)
    return Path("/tmp")


def socket_path_for(session_id: str) -> str:
    return str(_socket_dir() / f"remotex-orch-{session_id}.sock")


# Shape of the runtime methods we can call from MCP. Each takes a
# params dict and returns a JSON-serialisable result dict.
HandlerFn = Callable[[dict], Awaitable[dict]]


class BridgeServer:
    """Listens on a Unix socket; routes JSON-RPC method calls to the
    handler dispatch table."""

    def __init__(self, session_id: str, handlers: dict[str, HandlerFn]) -> None:
        self._session_id = session_id
        self._handlers = handlers
        self._path = socket_path_for(session_id)
        self._server: asyncio.AbstractServer | None = None

    @property
    def socket_path(self) -> str:
        return self._path

    async def start(self) -> None:
        # Clean stale socket from a previous crashed run.
        try:
            os.unlink(self._path)
        except FileNotFoundError:
            pass
        self._server = await asyncio.start_unix_server(
            self._handle_client, path=self._path,
        )
        # Tighten perms — only the daemon's user should be able to talk
        # to the runtime. Both daemon + brain codex (its child) have
        # the same uid in our deployment so 0600 is fine.
        try:
            os.chmod(self._path, 0o600)
        except OSError as exc:
            log.warning("could not chmod %s: %s", self._path, exc)

    async def stop(self) -> None:
        if self._server is not None:
            self._server.close()
            try:
                await self._server.wait_closed()
            except Exception:  # noqa: BLE001
                pass
            self._server = None
        try:
            os.unlink(self._path)
        except FileNotFoundError:
            pass

    async def _handle_client(
        self,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
    ) -> None:
        try:
            line = await reader.readline()
            if not line:
                return
            try:
                msg = json.loads(line.decode("utf-8", errors="replace"))
            except json.JSONDecodeError as exc:
                await self._reply(writer, {"id": None, "error": f"bad json: {exc}"})
                return
            rid = msg.get("id")
            method = msg.get("method") or ""
            params = msg.get("params") or {}
            handler = self._handlers.get(method)
            if handler is None:
                await self._reply(writer, {"id": rid, "error": f"unknown method: {method}"})
                return
            try:
                result = await handler(params)
                await self._reply(writer, {"id": rid, "result": result})
            except Exception as exc:  # noqa: BLE001
                await self._reply(writer, {"id": rid, "error": str(exc)})
        finally:
            try:
                writer.close()
                await writer.wait_closed()
            except Exception:  # noqa: BLE001
                pass

    async def _reply(self, writer: asyncio.StreamWriter, msg: dict) -> None:
        writer.write((json.dumps(msg) + "\n").encode("utf-8"))
        await writer.drain()
