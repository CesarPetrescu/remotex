"""SessionAdapter that runs the orchestrator loop on top of one
brain Codex stdio session.

Lifecycle:

1. ``start()`` spins up a normal ``StdioCodexAdapter`` for the brain,
   waits for ``session-started``, then immediately submits the
   user-supplied long-horizon ``task`` as the first turn — pre-prompted
   by the orchestrator system instruction set in collaborationMode.
2. The adapter's pump task watches the brain's events. When a turn ends
   it parses ``@@ORCH`` commands out of the accumulated agent message,
   executes them through the runtime, and feeds the result back as the
   next turn's input.
3. The user can still type into the session (those frames are forwarded
   as supplementary turns to the brain) but the typical use is to fire
   the initial task and watch the plan unfold.

Termination conditions:

- Brain emits ``@@ORCH finish`` → emit ``orchestrator-finished``, stop.
- Brain emits no commands for two turns in a row → emit a hint message
  back to it; if it still goes silent, stop with a failure.
- All steps reach a terminal state and brain has been quiet → stop.
"""
from __future__ import annotations

import asyncio
import json
import logging
from typing import AsyncIterator

from ..adapters.base import SessionAdapter, SessionEvent
from ..adapters.stdio import StdioCodexAdapter
from .prompts import ORCHESTRATOR_SYSTEM_PROMPT
from .protocol import OrchCommand, parse_commands
from .runtime import OrchestratorRuntime

log = logging.getLogger("daemon.orchestrator.adapter")


# Cap on how many turns the brain can chew through. A runaway brain
# that keeps emitting submit_plan with no progress shouldn't be able to
# burn an unbounded number of API calls.
MAX_BRAIN_TURNS = 40


class OrchestratorAdapter(SessionAdapter):
    def __init__(
        self,
        codex_binary: str,
        cwd: str,
        task: str,
        *,
        approval_policy: str | None = None,
        permissions: str | None = None,
        model: str | None = None,
        effort: str | None = None,
    ) -> None:
        self._task = task
        self._brain = StdioCodexAdapter(codex_binary=codex_binary, default_cwd=cwd or None)
        self._brain_model = model
        self._brain_effort = effort
        self._brain_permissions = permissions or "readonly"
        self._approval_policy = approval_policy
        self._queue: asyncio.Queue[SessionEvent | None] = asyncio.Queue()
        self._runtime = OrchestratorRuntime(
            codex_binary=codex_binary,
            cwd=cwd,
            emit=self._emit,
            approval_policy=approval_policy,
            permissions=permissions,
            model=model,
            effort=effort,
        )
        self._pump_task: asyncio.Task | None = None
        self._finished = False
        self._brain_turns = 0
        # Accumulator for the brain's agent_message text in the current
        # turn — codex emits item-delta + item-completed; we collect the
        # final text on item-completed.
        self._agent_text_for_turn: list[str] = []
        self._next_input: str | None = None
        self._initial_input_sent = False
        # Detect "brain emitted no @@ORCH commands" two turns in a row.
        self._silent_streak = 0

    # --- adapter API ----------------------------------------------------

    async def start(self) -> None:
        await self._brain.start()
        self._pump_task = asyncio.create_task(self._pump_brain(), name="orch-brain-pump")

    async def stop(self) -> None:
        if self._pump_task:
            self._pump_task.cancel()
            try:
                await self._pump_task
            except asyncio.CancelledError:
                pass
        await self._runtime.shutdown()
        await self._brain.stop()
        await self._queue.put(None)

    async def handle(self, frame: dict) -> None:
        ftype = frame.get("type")
        if ftype == "turn-start":
            # User can nudge the brain mid-run by typing additional
            # context. Forward as a normal turn to the brain.
            await self._brain.handle(self._brain_turn_frame(frame.get("input") or ""))
            return
        if ftype == "turn-interrupt":
            await self._brain.handle({"type": "turn-interrupt"})
            return
        # Approval responses, slash commands, etc. — pass through
        # unchanged so existing UI plumbing works on the brain session.
        await self._brain.handle(frame)

    async def events(self) -> AsyncIterator[SessionEvent]:
        while True:
            ev = await self._queue.get()
            if ev is None:
                return
            yield ev

    # --- internals ------------------------------------------------------

    def _brain_turn_frame(self, text: str) -> dict:
        frame: dict = {"type": "turn-start", "input": text}
        if self._brain_model:
            frame["model"] = self._brain_model
        if self._brain_effort:
            frame["effort"] = self._brain_effort
        # Brain runs read-only by default — its job is to decide, not
        # touch the workspace. Children inherit the operator-set policy.
        if self._brain_permissions:
            frame["permissions"] = self._brain_permissions
        return frame

    async def _emit(self, ev: SessionEvent) -> None:
        await self._queue.put(ev)

    async def _pump_brain(self) -> None:
        try:
            session_started_seen = False
            async for ev in self._brain.events():
                # Forward every brain event upstream so the existing UI
                # sees the brain's reasoning + agent messages live.
                # Tag them so the frontend knows they're the brain's
                # narration, not a child step.
                tagged = SessionEvent(ev.kind, {**(ev.data or {}), "_orchestrator_role": "brain"})
                await self._queue.put(tagged)
                kind = ev.kind
                data = ev.data or {}
                if kind == "session-started" and not session_started_seen:
                    session_started_seen = True
                    # Once the brain is ready, prime it with the user's
                    # task. This is the only "user message" the brain
                    # ever gets unsolicited; everything else after this
                    # is daemon-synthesized turn input.
                    if not self._initial_input_sent:
                        self._initial_input_sent = True
                        prompt = (
                            ORCHESTRATOR_SYSTEM_PROMPT
                            + "\n\n---\nUser task:\n"
                            + (self._task or "(no task supplied)")
                        )
                        try:
                            await self._brain.handle(self._brain_turn_frame(prompt))
                        except Exception as exc:  # noqa: BLE001
                            log.exception("brain initial turn failed: %s", exc)
                            await self._emit(SessionEvent("orchestrator-finished", {
                                "ok": False,
                                "error": f"failed to start brain: {exc}",
                            }))
                            return
                elif kind == "item-completed":
                    if data.get("item_type") == "agent_message":
                        text = data.get("text") or ""
                        if text:
                            self._agent_text_for_turn.append(text)
                elif kind == "turn-completed":
                    if data.get("error"):
                        await self._emit(SessionEvent("orchestrator-finished", {
                            "ok": False,
                            "error": str(data.get("error")),
                        }))
                        return
                    self._brain_turns += 1
                    if self._brain_turns >= MAX_BRAIN_TURNS:
                        await self._emit(SessionEvent("orchestrator-finished", {
                            "ok": False,
                            "error": f"brain turn budget exhausted ({MAX_BRAIN_TURNS})",
                        }))
                        return
                    text = "\n\n".join(self._agent_text_for_turn).strip()
                    self._agent_text_for_turn.clear()
                    cmds, errors = parse_commands(text)
                    if not cmds and not errors:
                        self._silent_streak += 1
                        if self._silent_streak >= 2:
                            await self._emit(SessionEvent("orchestrator-finished", {
                                "ok": False,
                                "error": "brain produced no @@ORCH commands for two turns",
                            }))
                            return
                        # Nudge it.
                        nudge = (
                            "(Reminder) You did not emit any @@ORCH commands."
                            " Emit the next command on its own line, e.g.\n"
                            "@@ORCH submit_plan {\"steps\": [...]}\n"
                            "or @@ORCH finish {\"summary\": \"...\"}"
                        )
                        await self._brain.handle(self._brain_turn_frame(nudge))
                        continue
                    self._silent_streak = 0
                    follow_up = await self._execute_commands(cmds, errors)
                    if self._finished:
                        return
                    if follow_up is not None:
                        await self._brain.handle(self._brain_turn_frame(follow_up))
        except asyncio.CancelledError:
            pass
        except Exception as exc:  # noqa: BLE001
            log.exception("brain pump crashed: %s", exc)
            await self._emit(SessionEvent("orchestrator-finished", {
                "ok": False,
                "error": f"orchestrator pump crashed: {exc}",
            }))

    async def _execute_commands(
        self, cmds: list[OrchCommand], parse_errors: list[str],
    ) -> str | None:
        """Run a turn's commands, return the user-input text for the
        next brain turn (None means don't send another turn — finished
        or waiting on user input)."""
        results: list[dict] = []
        for err in parse_errors:
            results.append({"action": "_parse_error", "error": err})

        for cmd in cmds:
            if cmd.action == "submit_plan":
                ok, err = await self._runtime.submit_plan(cmd.args.get("steps") or [])
                results.append({
                    "action": "submit_plan",
                    "ok": ok,
                    **({"error": err} if not ok else {"steps": self._runtime.list_steps()}),
                })
            elif cmd.action == "spawn_step":
                ok, err = await self._runtime.spawn_step(str(cmd.args.get("step_id") or ""))
                results.append({
                    "action": "spawn_step",
                    "step_id": cmd.args.get("step_id"),
                    "ok": ok,
                    **({"error": err} if not ok else {}),
                })
            elif cmd.action == "await_steps":
                step_ids = cmd.args.get("step_ids") or []
                ok, err, status_map = await self._runtime.await_steps(step_ids)
                results.append({
                    "action": "await_steps",
                    "step_ids": step_ids,
                    "ok": ok,
                    **({"error": err} if not ok else {"steps": status_map}),
                })
            elif cmd.action == "cancel_step":
                ok, err = await self._runtime.cancel_step(str(cmd.args.get("step_id") or ""))
                results.append({
                    "action": "cancel_step",
                    "step_id": cmd.args.get("step_id"),
                    "ok": ok,
                    **({"error": err} if not ok else {}),
                })
            elif cmd.action == "finish":
                summary = str(cmd.args.get("summary") or "")
                self._finished = True
                await self._emit(SessionEvent("orchestrator-finished", {
                    "ok": True,
                    "summary": summary,
                    "steps": self._runtime.list_steps(),
                }))
                return None
            else:
                results.append({
                    "action": cmd.action,
                    "ok": False,
                    "error": f"unknown @@ORCH action {cmd.action!r}",
                })

        text = (
            "@@ORCH_RESULT\n"
            + json.dumps(results, indent=2, sort_keys=True)
            + "\n\nDecide the next step. Emit @@ORCH commands."
        )
        return text
