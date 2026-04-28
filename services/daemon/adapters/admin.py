"""Persistent admin codex used for read-only ops (thread/list, fs/read)."""
from __future__ import annotations

import asyncio
import json
import logging
import shlex

log = logging.getLogger("daemon.adapters.admin")


class AdminCodex:
    """Long-running codex app-server that answers thread/list quickly.

    Each admin_list_threads() call previously cold-spawned codex (2-5s
    on node startup). When the box was already spawning codex for a
    live session, overlapping spawns occasionally pushed the daemon's
    event loop past the relay's 15s HTTP timeout, producing 504s and
    eventually WS heartbeat loss. Keeping one codex resident collapses
    the hot-path to a single JSON-RPC round-trip.
    """

    def __init__(self, codex_binary: str = "codex") -> None:
        self._codex_binary = codex_binary
        self._proc: asyncio.subprocess.Process | None = None
        self._reader_task: asyncio.Task | None = None
        self._pending: dict[int, asyncio.Future] = {}
        self._next_id = 0
        self._lock = asyncio.Lock()

    async def close(self) -> None:
        async with self._lock:
            await self._tear_down()

    async def list_threads(self, limit: int = 20, cursor: str | None = None) -> dict:
        """Return codex's raw thread/list result (the {data, nextCursor,
        backwardsCursor} body). Lazily starts the codex process and
        re-spawns if the previous one died."""
        return await self._call("thread/list", self._build_list_params(limit, cursor))

    async def read_directory(self, path: str) -> dict:
        """Return codex's fs/readDirectory result ({entries: [...]} body).
        Used by the relay to serve GET /api/hosts/<id>/fs."""
        return await self._call("fs/readDirectory", {"path": path})

    def _build_list_params(self, limit: int, cursor: str | None) -> dict:
        params: dict = {"limit": limit}
        if cursor:
            params["cursor"] = cursor
        return params

    async def _call(self, method: str, params: dict) -> dict:
        async with self._lock:
            await self._ensure_running()
            try:
                return await asyncio.wait_for(
                    self._request(method, params), timeout=10.0
                )
            except Exception:
                # The subprocess might be hosed; kill it so the next call
                # gets a fresh one instead of timing out again.
                await self._tear_down()
                raise

    # --- internals ----------------------------------------------------

    async def _ensure_running(self) -> None:
        if self._proc is not None and self._proc.returncode is None:
            return
        cmd = shlex.split(self._codex_binary) + ["app-server"]
        log.info("spawning persistent admin codex (%s)", " ".join(cmd))
        self._proc = await asyncio.create_subprocess_exec(
            *cmd,
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        self._reader_task = asyncio.create_task(self._read_loop())
        # Perform the init/initialized handshake once per spawn.
        await self._request("initialize", {
            "clientInfo": {
                "name": "remotex-daemon-admin",
                "title": "Remotex",
                "version": "0.1",
            },
            "capabilities": {"experimentalApi": True},
        })
        await self._send({"method": "initialized", "params": {}})

    async def _tear_down(self) -> None:
        if self._reader_task:
            self._reader_task.cancel()
            self._reader_task = None
        for fut in self._pending.values():
            if not fut.done():
                fut.set_exception(RuntimeError("admin codex torn down"))
        self._pending.clear()
        if self._proc and self._proc.returncode is None:
            try:
                self._proc.terminate()
            except ProcessLookupError:
                pass
            try:
                await asyncio.wait_for(self._proc.wait(), timeout=2.0)
            except asyncio.TimeoutError:
                self._proc.kill()
        self._proc = None

    async def _request(self, method: str, params: dict) -> dict:
        assert self._proc
        self._next_id += 1
        req_id = self._next_id
        loop = asyncio.get_running_loop()
        fut: asyncio.Future = loop.create_future()
        self._pending[req_id] = fut
        await self._send({"id": req_id, "method": method, "params": params})
        try:
            return await fut
        finally:
            self._pending.pop(req_id, None)

    async def _send(self, obj: dict) -> None:
        assert self._proc and self._proc.stdin
        line = json.dumps(obj) + "\n"
        self._proc.stdin.write(line.encode())
        await self._proc.stdin.drain()

    async def _read_loop(self) -> None:
        assert self._proc and self._proc.stdout
        try:
            while True:
                line = await self._proc.stdout.readline()
                if not line:
                    # codex exited. Fail any waiters; next call respawns.
                    for fut in self._pending.values():
                        if not fut.done():
                            fut.set_exception(RuntimeError("admin codex exited"))
                    self._pending.clear()
                    return
                try:
                    msg = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if "id" in msg and ("result" in msg or "error" in msg):
                    fut = self._pending.get(msg["id"])
                    if fut and not fut.done():
                        if "error" in msg:
                            fut.set_exception(RuntimeError(
                                f"{msg['error'].get('code')}: {msg['error'].get('message')}"
                            ))
                        else:
                            fut.set_result(msg.get("result") or {})
        except asyncio.CancelledError:
            pass


# Backwards-compatible module-level helper: the DaemonClient owns a
# single AdminCodex instance and routes threads-list-request through it.
# Kept this function available in case callers that used the old API
# are still around; new callers should go through DaemonClient.
async def admin_list_threads(
    codex_binary: str = "codex",
    limit: int = 20,
    cursor: str | None = None,
) -> dict:
    """Ad-hoc helper: spin up one admin codex, ask, tear down.

    Kept for compatibility. The daemon itself now uses a persistent
    AdminCodex per connection, which avoids the cold-spawn penalty on
    every list call.
    """
    admin = AdminCodex(codex_binary=codex_binary)
    try:
        return await admin.list_threads(limit=limit, cursor=cursor)
    finally:
        await admin.close()
