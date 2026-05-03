"""DAG state + child-session lifecycle for an orchestrator session."""
from __future__ import annotations

import asyncio
import logging
import time
import uuid
from dataclasses import dataclass, field
from typing import Awaitable, Callable

from ..adapters.base import SessionEvent
from ..adapters.stdio import StdioCodexAdapter

log = logging.getLogger("daemon.orchestrator.runtime")


# Hard cap on the number of child Codex processes a single orchestrator
# may have alive at once. Prevents a runaway plan from forking dozens of
# subprocesses that crush the host.
MAX_CONCURRENT_CHILDREN = 4
# Hard cap on a single child step's wall time. Long enough for a real
# subtask, short enough that a wedged step doesn't block the whole run.
CHILD_STEP_TIMEOUT_SECONDS = 30 * 60.0


@dataclass
class _Step:
    step_id: str
    title: str
    prompt: str
    deps: list[str] = field(default_factory=list)
    status: str = "pending"  # pending | running | completed | failed | cancelled
    child_session_id: str | None = None
    summary: str = ""
    started_at: float | None = None
    completed_at: float | None = None
    _task: asyncio.Task | None = None
    _adapter: StdioCodexAdapter | None = None


# Callback the runtime uses to ship events back up to the orchestrator
# adapter (which then queues them to the daemon-side ``SessionEvent``
# pipe so they reach the relay + client UI).
EmitFn = Callable[[SessionEvent], Awaitable[None]]


class OrchestratorRuntime:
    """Owns the plan and the running child Codex sessions for one
    orchestrator session.

    Children are spawned as throwaway ``StdioCodexAdapter`` instances —
    each runs exactly one turn (the step's prompt) and is killed as soon
    as that turn completes. Their final ``agentMessage`` is the step's
    summary, fed back to the brain as part of the ``await_steps`` reply.
    """

    def __init__(
        self,
        codex_binary: str,
        cwd: str,
        emit: EmitFn,
        *,
        approval_policy: str | None = None,
        permissions: str | None = None,
        model: str | None = None,
        effort: str | None = None,
    ) -> None:
        self._binary = codex_binary
        self._cwd = cwd
        self._emit = emit
        self._approval_policy = approval_policy
        self._permissions = permissions
        self._model = model
        self._effort = effort
        self._steps: dict[str, _Step] = {}
        self._step_order: list[str] = []  # plan order for stable display
        self._children_sema = asyncio.Semaphore(MAX_CONCURRENT_CHILDREN)
        self._lock = asyncio.Lock()

    # --- public API used by the OrchestratorAdapter ---------------------

    def has_steps(self) -> bool:
        return bool(self._steps)

    def list_steps(self) -> list[dict]:
        return [self._step_dict(s) for s in (self._steps[sid] for sid in self._step_order)]

    def step_summary_dict(self) -> dict:
        return {sid: self._step_dict(s) for sid, s in self._steps.items()}

    def all_terminal(self) -> bool:
        return bool(self._steps) and all(
            s.status in ("completed", "failed", "cancelled")
            for s in self._steps.values()
        )

    async def submit_plan(self, raw_steps: list[dict]) -> tuple[bool, str]:
        """Replace the plan. Validates step ids unique and deps acyclic.

        Returns (ok, error). On error nothing is mutated."""
        if not isinstance(raw_steps, list) or not raw_steps:
            return False, "submit_plan: `steps` must be a non-empty list"
        seen: set[str] = set()
        prepared: list[_Step] = []
        for raw in raw_steps:
            if not isinstance(raw, dict):
                return False, "submit_plan: each step must be an object"
            sid = str(raw.get("step_id") or raw.get("id") or "").strip()
            if not sid:
                return False, "submit_plan: step missing step_id"
            if sid in seen:
                return False, f"submit_plan: duplicate step_id {sid!r}"
            seen.add(sid)
            title = str(raw.get("title") or sid)
            prompt = str(raw.get("prompt") or "").strip()
            if not prompt:
                return False, f"submit_plan: step {sid!r} missing prompt"
            deps_raw = raw.get("deps") or []
            if not isinstance(deps_raw, list):
                return False, f"submit_plan: step {sid!r} deps must be a list"
            deps = [str(d) for d in deps_raw]
            prepared.append(_Step(step_id=sid, title=title, prompt=prompt, deps=deps))

        ids = {s.step_id for s in prepared}
        for s in prepared:
            for d in s.deps:
                if d not in ids:
                    return False, f"submit_plan: step {s.step_id!r} dep {d!r} unknown"
        if _has_cycle(prepared):
            return False, "submit_plan: dependency graph has a cycle"

        async with self._lock:
            # Cancel any in-flight children before replacing the plan.
            for old in self._steps.values():
                if old._task and not old._task.done():
                    old._task.cancel()
                if old._adapter is not None:
                    asyncio.create_task(old._adapter.stop())
            self._steps = {s.step_id: s for s in prepared}
            self._step_order = [s.step_id for s in prepared]

        await self._emit(SessionEvent("orchestrator-plan", {
            "steps": self.list_steps(),
        }))
        return True, ""

    async def spawn_step(self, step_id: str) -> tuple[bool, str]:
        async with self._lock:
            step = self._steps.get(step_id)
            if step is None:
                return False, f"spawn_step: unknown step_id {step_id!r}"
            if step.status != "pending":
                return False, f"spawn_step: step {step_id!r} already {step.status}"
            for d in step.deps:
                dep = self._steps.get(d)
                if dep is None or dep.status != "completed":
                    return False, (
                        f"spawn_step: step {step_id!r} blocked on dep {d!r}"
                        f" (status={dep.status if dep else 'missing'})"
                    )
            step.status = "running"
            step.started_at = time.time()
            step._task = asyncio.create_task(
                self._run_child(step), name=f"orch-step-{step_id}",
            )
        await self._emit_step_status(step_id)
        return True, ""

    async def cancel_step(self, step_id: str) -> tuple[bool, str]:
        async with self._lock:
            step = self._steps.get(step_id)
            if step is None:
                return False, f"cancel_step: unknown step_id {step_id!r}"
            if step.status not in ("pending", "running"):
                return False, f"cancel_step: step {step_id!r} already {step.status}"
            step.status = "cancelled"
            step.completed_at = time.time()
            if step._task and not step._task.done():
                step._task.cancel()
            if step._adapter is not None:
                asyncio.create_task(step._adapter.stop())
        await self._emit_step_status(step_id)
        return True, ""

    async def await_steps(self, step_ids: list[str]) -> tuple[bool, str, dict]:
        if not isinstance(step_ids, list) or not step_ids:
            return False, "await_steps: `step_ids` must be a non-empty list", {}
        async with self._lock:
            tasks: list[asyncio.Task] = []
            for sid in step_ids:
                step = self._steps.get(str(sid))
                if step is None:
                    return False, f"await_steps: unknown step_id {sid!r}", {}
                if step._task and not step._task.done():
                    tasks.append(step._task)
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        out: dict = {}
        for sid in step_ids:
            step = self._steps.get(str(sid))
            if step is not None:
                out[step.step_id] = self._step_dict(step)
        return True, "", out

    async def shutdown(self) -> None:
        """Tear down every still-running child."""
        async with self._lock:
            adapters = [s._adapter for s in self._steps.values() if s._adapter is not None]
            for s in self._steps.values():
                if s._task and not s._task.done():
                    s._task.cancel()
        for ad in adapters:
            try:
                await asyncio.wait_for(ad.stop(), timeout=3.0)
            except (asyncio.TimeoutError, Exception):  # noqa: BLE001
                pass

    # --- internals ------------------------------------------------------

    def _step_dict(self, s: _Step) -> dict:
        return {
            "step_id": s.step_id,
            "title": s.title,
            "deps": list(s.deps),
            "status": s.status,
            "summary": s.summary,
            "child_session_id": s.child_session_id,
            "started_at": s.started_at,
            "completed_at": s.completed_at,
        }

    async def _emit_step_status(self, step_id: str) -> None:
        step = self._steps.get(step_id)
        if step is None:
            return
        await self._emit(SessionEvent("orchestrator-step-status", self._step_dict(step)))

    async def _run_child(self, step: _Step) -> None:
        """Run one child Codex session for the step, capture its summary,
        kill the subprocess. Reports outcome via orchestrator events."""
        async with self._children_sema:
            adapter = StdioCodexAdapter(
                codex_binary=self._binary,
                default_cwd=self._cwd,
            )
            step._adapter = adapter
            step.child_session_id = f"child_{uuid.uuid4().hex[:12]}"
            await self._emit_step_status(step.step_id)

            try:
                await adapter.start()
            except Exception as exc:  # noqa: BLE001
                log.warning("orch step %s: adapter.start failed: %s", step.step_id, exc)
                step.status = "failed"
                step.summary = f"failed to start child Codex: {exc}"
                step.completed_at = time.time()
                step._adapter = None
                await self._emit_step_status(step.step_id)
                return

            consumer = asyncio.create_task(
                self._consume_child_events(step, adapter),
                name=f"orch-step-events-{step.step_id}",
            )

            turn_frame: dict = {"type": "turn-start", "input": step.prompt}
            if self._model:
                turn_frame["model"] = self._model
            if self._effort:
                turn_frame["effort"] = self._effort
            if self._permissions:
                turn_frame["permissions"] = self._permissions
            try:
                await adapter.handle(turn_frame)
            except Exception as exc:  # noqa: BLE001
                log.warning("orch step %s: turn-start failed: %s", step.step_id, exc)
                step.status = "failed"
                step.summary = f"turn-start failed: {exc}"
                step.completed_at = time.time()
                consumer.cancel()
                await adapter.stop()
                step._adapter = None
                await self._emit_step_status(step.step_id)
                return

            try:
                await asyncio.wait_for(consumer, timeout=CHILD_STEP_TIMEOUT_SECONDS)
            except asyncio.TimeoutError:
                log.warning("orch step %s: timed out", step.step_id)
                step.status = "failed"
                step.summary = (
                    step.summary or
                    f"timed out after {int(CHILD_STEP_TIMEOUT_SECONDS)}s"
                )
                step.completed_at = time.time()
                consumer.cancel()
            except asyncio.CancelledError:
                step.status = "cancelled"
                step.completed_at = time.time()
                step.summary = step.summary or "cancelled"
            finally:
                try:
                    await adapter.stop()
                except Exception:  # noqa: BLE001
                    pass
                step._adapter = None
                await self._emit_step_status(step.step_id)

    @staticmethod
    def _build_step_event_payload(
        step_id: str, kind: str, data: dict,
    ) -> dict | None:
        """Translate one child SessionEvent into the compact shape the
        orchestrator UI consumes. Returns None for events we don't want
        to forward (most chatter)."""
        item_type = data.get("item_type")
        # Streaming text: agent reasoning + agent message deltas. We
        # forward the raw delta — the client appends until item-completed.
        if kind == "item-delta" and item_type in ("agent_message", "agent_reasoning"):
            delta = data.get("delta") or ""
            if not delta:
                return None
            return {
                "step_id": step_id,
                "kind": kind,
                "item_id": data.get("item_id"),
                "item_type": item_type,
                "delta": delta,
            }
        if kind in ("item-started", "item-completed"):
            payload: dict = {
                "step_id": step_id,
                "kind": kind,
                "item_id": data.get("item_id"),
                "item_type": item_type,
            }
            # Pull the most useful descriptor for tool / file change
            # so the client doesn't need to re-implement the mapping.
            if item_type == "tool_call":
                args = data.get("args") or {}
                cmd = args.get("command") if isinstance(args, dict) else None
                if isinstance(cmd, str):
                    payload["label"] = "$ " + cmd
                elif isinstance(cmd, list):
                    payload["label"] = "$ " + " ".join(map(str, cmd))
                if "exit_code" in data:
                    payload["exit_code"] = data["exit_code"]
            elif item_type == "file_change":
                changes = data.get("changes") or []
                if isinstance(changes, list) and changes:
                    paths = [c.get("path") for c in changes if isinstance(c, dict) and c.get("path")]
                    if paths:
                        head = paths[0] + (f" (+{len(paths) - 1})" if len(paths) > 1 else "")
                        payload["label"] = "edit: " + head
            elif item_type == "agent_message" and kind == "item-completed":
                text = data.get("text") or ""
                if text:
                    payload["text"] = text[:400]
            return payload
        if kind == "turn-started":
            return {"step_id": step_id, "kind": kind}
        return None

    async def _consume_child_events(
        self, step: _Step, adapter: StdioCodexAdapter,
    ) -> None:
        """Pull events off the child adapter, surfacing the agent's final
        message as the step summary. Tool/reasoning chatter is forwarded
        as ``orchestrator-step-event`` so the UI can drill in if needed,
        but kept compact (no big payloads) to avoid drowning the log."""
        agent_text_parts: list[str] = []
        async for ev in adapter.events():
            data = ev.data or {}
            if ev.kind == "item-completed":
                if data.get("item_type") == "agent_message":
                    text = data.get("text") or ""
                    if text:
                        agent_text_parts.append(text)
            elif ev.kind == "turn-completed":
                err = data.get("error")
                if err:
                    step.status = "failed"
                    step.summary = f"child turn failed: {err}"
                else:
                    step.status = "completed"
                    step.summary = "\n\n".join(p.strip() for p in agent_text_parts if p.strip()) \
                        or "(child produced no agent message)"
                step.completed_at = time.time()
                # Don't forward more events — runtime drives lifecycle.
                return
            elif ev.kind == "approval-request":
                # The child's policy is set per-turn. Anything that still
                # asks for approval is a signal the operator's policy was
                # too strict for this kind of work; auto-decline so the
                # child doesn't wedge waiting for input.
                step.summary = step.summary or (
                    "child requested approval; declined automatically. "
                    "Use a more permissive approval policy when launching."
                )
            # Forward live progress to the UI so the user can see what
            # each child is actually doing (not just a "running" pill).
            # Three categories:
            #   - item-delta on agent_message / agent_reasoning → text stream
            #   - item-started for tool_call / file_change → "what tool now"
            #   - item-completed for the same → mark the action done
            forwarded = self._build_step_event_payload(step.step_id, ev.kind, data)
            if forwarded is not None:
                await self._emit(SessionEvent("orchestrator-step-event", forwarded))


def _has_cycle(steps: list[_Step]) -> bool:
    """Standard 3-color DFS — returns True if any cycle is detected."""
    color: dict[str, int] = {s.step_id: 0 for s in steps}
    deps_of = {s.step_id: list(s.deps) for s in steps}

    def visit(sid: str) -> bool:
        c = color.get(sid)
        if c == 1:
            return True
        if c == 2:
            return False
        color[sid] = 1
        for d in deps_of.get(sid, ()):
            if visit(d):
                return True
        color[sid] = 2
        return False

    return any(visit(s.step_id) for s in steps)
