"""Deterministic scripted adapter for tests + offline demos."""
from __future__ import annotations

import asyncio
import uuid
from typing import AsyncIterator

from .base import SessionAdapter, SessionEvent


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
    size = 12
    return [text[i : i + size] for i in range(0, len(text), size)]
