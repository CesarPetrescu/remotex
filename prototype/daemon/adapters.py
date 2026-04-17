"""Session adapters that produce Codex-App-Server-shaped events.

For the prototype, MockCodexAdapter is the default — it lets you drive
the entire daemon → relay → client path without needing a local Codex
install. StdioCodexAdapter is a skeleton for the real integration: it
spawns `codex app-server`, speaks JSON-RPC over stdin/stdout, and
forwards notifications upstream. The stdio adapter is intentionally
minimal; the full protocol surface (approvals, diffs, thread/fork) is
documented in docs/codex_app_server_protocol.md.
"""
from __future__ import annotations

import asyncio
import json
import shlex
import time
import uuid
from dataclasses import dataclass
from typing import AsyncIterator, Callable


@dataclass
class SessionEvent:
    """Envelope kind wrapped by the daemon before shipping to the relay."""
    kind: str
    data: dict

    def to_frame(self, session_id: str) -> dict:
        return {
            "type": "session-event",
            "session_id": session_id,
            "event": {"kind": self.kind, "data": self.data, "ts": time.time()},
        }


class SessionAdapter:
    async def start(self) -> None: ...
    async def stop(self) -> None: ...
    async def handle(self, frame: dict) -> None: ...
    def events(self) -> AsyncIterator[SessionEvent]: ...


# ---------------------------------------------------------------------------
# Mock adapter — deterministic, no external dependencies
# ---------------------------------------------------------------------------

class MockCodexAdapter(SessionAdapter):
    """Simulates a Codex session.

    `turn-start` → emits a scripted sequence: reasoning, a tool call, a
    streamed agent message with deltas, and completion. This matches the
    item types documented in the real protocol (item/started,
    item/agentMessage/delta, item/completed) so the client UI can be
    exercised against real shapes.
    """

    def __init__(self) -> None:
        self._queue: asyncio.Queue[SessionEvent | None] = asyncio.Queue()
        self._tasks: list[asyncio.Task] = []
        self._running = True

    async def start(self) -> None:
        await self._queue.put(SessionEvent("session-started", {
            "model": "gpt-5-codex",
            "cwd": "/home/demo/repo",
            "sandbox": "workspace-write",
        }))

    async def stop(self) -> None:
        self._running = False
        for t in self._tasks:
            t.cancel()
        await self._queue.put(None)

    async def handle(self, frame: dict) -> None:
        if frame.get("type") == "turn-start":
            self._tasks.append(asyncio.create_task(self._run_turn(frame.get("input", ""))))

    async def _run_turn(self, user_input: str) -> None:
        turn_id = f"turn_{uuid.uuid4().hex[:8]}"
        await self._queue.put(SessionEvent("turn-started", {"turn_id": turn_id, "input": user_input}))

        reasoning_id = f"itm_{uuid.uuid4().hex[:8]}"
        await self._queue.put(SessionEvent("item-started", {
            "turn_id": turn_id, "item_id": reasoning_id, "item_type": "agent_reasoning",
        }))
        await asyncio.sleep(0.2)
        await self._queue.put(SessionEvent("item-completed", {
            "turn_id": turn_id, "item_id": reasoning_id, "item_type": "agent_reasoning",
            "text": "Plan: search the repo for the requested symbol, then read the top match.",
        }))

        tool_id = f"itm_{uuid.uuid4().hex[:8]}"
        await self._queue.put(SessionEvent("item-started", {
            "turn_id": turn_id, "item_id": tool_id, "item_type": "tool_call",
            "tool": "shell", "args": {"command": "rg -n 'verifyJwt' src/"},
        }))
        await asyncio.sleep(0.3)
        await self._queue.put(SessionEvent("item-completed", {
            "turn_id": turn_id, "item_id": tool_id, "item_type": "tool_call",
            "exit_code": 0, "output": "src/auth/verify.ts:12:export function verifyJwt(",
        }))

        msg_id = f"itm_{uuid.uuid4().hex[:8]}"
        await self._queue.put(SessionEvent("item-started", {
            "turn_id": turn_id, "item_id": msg_id, "item_type": "agent_message",
        }))
        chunks = _scripted_reply(user_input)
        for chunk in chunks:
            if not self._running:
                return
            await asyncio.sleep(0.05)
            await self._queue.put(SessionEvent("item-delta", {
                "turn_id": turn_id, "item_id": msg_id, "item_type": "agent_message",
                "delta": chunk,
            }))
        full = "".join(chunks)
        await self._queue.put(SessionEvent("item-completed", {
            "turn_id": turn_id, "item_id": msg_id, "item_type": "agent_message",
            "text": full,
        }))
        await self._queue.put(SessionEvent("turn-completed", {"turn_id": turn_id}))

    async def events(self) -> AsyncIterator[SessionEvent]:
        while True:
            ev = await self._queue.get()
            if ev is None:
                return
            yield ev


def _scripted_reply(user_input: str) -> list[str]:
    user_input = user_input.strip() or "your request"
    preface = f"Looked up matches for `{user_input}`. "
    body = (
        "Found the symbol in src/auth/verify.ts — it's exported from a single "
        "file, used by three callers. Extracting it into its own module is "
        "safe; I'd suggest src/auth/verify/index.ts so the import surface "
        "stays small."
    )
    text = preface + body
    # emit token-ish chunks
    size = 12
    return [text[i : i + size] for i in range(0, len(text), size)]


# ---------------------------------------------------------------------------
# Real stdio adapter (skeleton)
# ---------------------------------------------------------------------------

class StdioCodexAdapter(SessionAdapter):
    """Spawns `codex app-server` and bridges JSON-RPC frames.

    The full implementation needs:
      * initialize / initialized handshake
      * thread/start for new sessions, thread/resume on reconnect
      * turn/start when the client sends `turn-start`
      * translation of item/* notifications into SessionEvent envelopes
      * approval response routing (client → daemon → app-server)

    This class is a skeleton that wires up the subprocess + line reader.
    Production should swap it in once the approval + diff surfaces have
    been mapped onto client envelopes.
    """

    def __init__(self, codex_binary: str = "codex") -> None:
        self.binary = codex_binary
        self._proc: asyncio.subprocess.Process | None = None
        self._queue: asyncio.Queue[SessionEvent | None] = asyncio.Queue()
        self._reader_task: asyncio.Task | None = None
        self._next_id = 0

    async def start(self) -> None:
        cmd = shlex.split(self.binary) + ["app-server"]
        self._proc = await asyncio.create_subprocess_exec(
            *cmd,
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        self._reader_task = asyncio.create_task(self._read_loop())
        await self._rpc("initialize", {
            "clientInfo": {"name": "remotex-daemon", "version": "0.1"},
        })
        await self._notify("initialized", {})
        await self._queue.put(SessionEvent("session-started", {"transport": "stdio"}))

    async def stop(self) -> None:
        if self._proc and self._proc.returncode is None:
            try:
                self._proc.terminate()
            except ProcessLookupError:
                pass
        if self._reader_task:
            self._reader_task.cancel()
        await self._queue.put(None)

    async def handle(self, frame: dict) -> None:
        ftype = frame.get("type")
        if ftype == "turn-start":
            await self._rpc("turn/start", {"input": frame.get("input", "")})
        elif ftype == "approval":
            await self._rpc("approval/resolve", {
                "id": frame.get("approval_id"),
                "decision": frame.get("decision", "deny"),
            })

    async def events(self) -> AsyncIterator[SessionEvent]:
        while True:
            ev = await self._queue.get()
            if ev is None:
                return
            yield ev

    # --- internals ---

    async def _rpc(self, method: str, params: dict) -> None:
        self._next_id += 1
        await self._send({"id": self._next_id, "method": method, "params": params})

    async def _notify(self, method: str, params: dict) -> None:
        await self._send({"method": method, "params": params})

    async def _send(self, obj: dict) -> None:
        assert self._proc and self._proc.stdin
        line = json.dumps(obj) + "\n"
        self._proc.stdin.write(line.encode())
        await self._proc.stdin.drain()

    async def _read_loop(self) -> None:
        assert self._proc and self._proc.stdout
        while True:
            line = await self._proc.stdout.readline()
            if not line:
                return
            try:
                frame = json.loads(line)
            except json.JSONDecodeError:
                continue
            # Server notifications -> SessionEvent; responses are ignored
            # at this skeleton stage (no outstanding callers rely on them).
            method = frame.get("method")
            if method and method.startswith("item/"):
                await self._queue.put(SessionEvent(method, frame.get("params") or {}))


def build_adapter(mode: str, codex_binary: str) -> SessionAdapter:
    mode = (mode or "mock").lower()
    if mode == "mock":
        return MockCodexAdapter()
    if mode == "stdio":
        return StdioCodexAdapter(codex_binary=codex_binary)
    raise ValueError(f"unknown adapter mode: {mode!r}")
