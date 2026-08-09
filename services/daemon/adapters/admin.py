"""Persistent admin codex used for read-only ops (thread/list, fs/read)."""
from __future__ import annotations

import asyncio
import json
import logging
import shlex

log = logging.getLogger("daemon.adapters.admin")

# The relay's model list uses "" as the "let codex decide" sentinel for
# both the model id and the effort; keep the host list identical in
# shape so clients can swap one for the other.
_EFFORT_DEFAULT = ""


async def _read_line_unbounded(stream: asyncio.StreamReader) -> bytes:
    """Read one newline-delimited JSON-RPC frame without asyncio's 64KB cap."""
    parts: list[bytes] = []
    while True:
        try:
            tail = await stream.readuntil(b"\n")
            return b"".join(parts) + tail
        except asyncio.LimitOverrunError as exc:
            parts.append(await stream.readexactly(exc.consumed))
        except asyncio.IncompleteReadError as exc:
            return b"".join(parts) + exc.partial


def model_options_from_codex(result: dict) -> list[dict]:
    """Map codex's ``model/list`` result onto the relay's model shape.

    Codex returns ``{data: [Model], nextCursor}``; a Model carries
    ``model`` (the slug turn/start wants), ``displayName``,
    ``description`` and ``supportedReasoningEfforts`` — a list of
    ``{reasoningEffort, description}``. We collapse that to the
    ``{id, label, hint, efforts}`` objects the relay serves from
    ``/api/models``, keeping the empty-string "let codex pick" sentinel
    at the head of both the model list and every effort list, exactly
    as the hostless fallback does.
    """
    options: list[dict] = [
        {"id": "", "label": "default", "hint": "codex picks", "efforts": [_EFFORT_DEFAULT]},
    ]
    seen = {""}
    for model in result.get("data") or []:
        if not isinstance(model, dict) or model.get("hidden"):
            continue
        slug = str(model.get("model") or model.get("id") or "").strip()
        if not slug or slug in seen:
            continue
        seen.add(slug)
        efforts = [_EFFORT_DEFAULT]
        for entry in model.get("supportedReasoningEfforts") or []:
            effort = entry.get("reasoningEffort") if isinstance(entry, dict) else entry
            effort = str(effort or "").strip()
            if effort and effort not in efforts:
                efforts.append(effort)
        options.append({
            "id": slug,
            "label": str(model.get("displayName") or slug).strip(),
            "hint": str(model.get("description") or model.get("modelSpecialty") or "").strip(),
            "efforts": efforts,
        })
    options[0]["efforts"] = _merged_efforts(options)
    return options


def _merged_efforts(options: list[dict]) -> list[str]:
    """Every effort any model accepts — the "default" row can't know
    which model codex will pick, so it offers the union."""
    merged = [_EFFORT_DEFAULT]
    for option in options[1:]:
        for effort in option["efforts"]:
            if effort and effort not in merged:
                merged.append(effort)
    return merged


class AdminCodex:
    """Long-running codex app-server that answers thread/list quickly.

    Each list call previously cold-spawned codex (2-5s
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
        self._stderr_task: asyncio.Task | None = None
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
        # A cold `codex app-server` may spend more than 10s enumerating a
        # large rollout directory immediately after daemon restart.
        return await self._call(
            "thread/list",
            self._build_list_params(limit, cursor),
            timeout=25.0,
        )

    async def list_models(self, limit: int | None = None, cursor: str | None = None) -> dict:
        """Return codex's raw model/list result ({data, nextCursor} body).

        codex 0.147's ModelListParams is {cursor, limit, includeHidden};
        all three are sent explicitly (nulls included) to match the wire
        shape in app-server-protocol/src/protocol/common.rs.
        """
        return await self._call(
            "model/list",
            {"cursor": cursor, "limit": limit, "includeHidden": False},
            timeout=25.0,
        )

    async def read_directory(self, path: str) -> dict:
        """Return codex's fs/readDirectory result ({entries: [...]} body).
        Used by the relay to serve GET /api/hosts/<id>/fs."""
        return await self._call("fs/readDirectory", {"path": path})

    def _build_list_params(self, limit: int, cursor: str | None) -> dict:
        params: dict = {"limit": limit}
        if cursor:
            params["cursor"] = cursor
        return params

    async def _call(self, method: str, params: dict, *, timeout: float = 10.0) -> dict:
        async with self._lock:
            await self._ensure_running()
            try:
                return await asyncio.wait_for(
                    self._request(method, params), timeout=timeout
                )
            except asyncio.TimeoutError as exc:
                # TimeoutError stringifies to "", which otherwise becomes
                # a useless "daemon error:" in the UI.
                await self._tear_down()
                raise TimeoutError(f"{method} timed out after {timeout:g}s") from exc
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
        # Nobody else reads stderr, so without this the pipe fills at
        # ~64KB, codex blocks on the write and stops servicing stdin —
        # every admin call then times out until the next tear-down.
        self._stderr_task = asyncio.create_task(self._drain_stderr())
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
        if self._stderr_task:
            self._stderr_task.cancel()
            self._stderr_task = None
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

    async def _drain_stderr(self) -> None:
        assert self._proc and self._proc.stderr
        try:
            while True:
                line = await _read_line_unbounded(self._proc.stderr)
                if not line:
                    return
                log.info("admin codex stderr: %s", line.decode(errors="replace").rstrip())
        except asyncio.CancelledError:
            pass
        except Exception as exc:  # noqa: BLE001 — never let the drain die quietly
            log.warning("admin codex stderr drain failed: %s", exc)

    async def _read_loop(self) -> None:
        assert self._proc and self._proc.stdout
        try:
            while True:
                line = await _read_line_unbounded(self._proc.stdout)
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
        except Exception as exc:  # noqa: BLE001
            log.warning("admin codex read loop failed: %s", exc)
            for fut in self._pending.values():
                if not fut.done():
                    fut.set_exception(RuntimeError(f"admin codex read failed: {exc}"))
            self._pending.clear()
