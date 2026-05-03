"""DAG state + child-session lifecycle for an orchestrator session."""
from __future__ import annotations

import asyncio
import logging
import time
import uuid
from contextlib import suppress
from dataclasses import dataclass, field
from typing import Awaitable, Callable

from ..adapters.base import SessionEvent
from ..adapters.stdio import StdioCodexAdapter
from .collab import COLLABORATION_MODE, native_subagent_thread_config

log = logging.getLogger("daemon.orchestrator.runtime")


# Hard cap on the number of child Codex processes a single orchestrator
# may have alive at once. Prevents a runaway plan from forking dozens of
# subprocesses that crush the host.
MAX_CONCURRENT_CHILDREN = 4
# Hard cap on a single child step's wall time. Long enough for a real
# subtask, short enough that a wedged step doesn't block the whole run.
CHILD_STEP_TIMEOUT_SECONDS = 30 * 60.0


CHILD_STEP_PROMPT_PREFIX = """\
You are a Remotex child Codex agent executing one delegated step from
an orchestrator. Do the concrete coding, inspection, testing, or
research work requested below. You may use Codex-native subagents when
parallel delegation materially helps, and you must return a concise
final summary of what you did, what changed, and what verification ran.

Delegated step:
"""


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
AdapterFactory = Callable[[], StdioCodexAdapter]


def _is_terminal(status: str) -> bool:
    return status in ("completed", "failed", "cancelled")


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
        adapter_factory: AdapterFactory | None = None,
    ) -> None:
        self._binary = codex_binary
        self._cwd = cwd
        self._emit = emit
        self._approval_policy = approval_policy
        self._permissions = permissions
        self._model = model
        self._effort = effort
        self._adapter_factory = adapter_factory or (
            lambda: StdioCodexAdapter(
                codex_binary=self._binary,
                default_cwd=self._cwd,
                extra_thread_config=native_subagent_thread_config(),
                forced_collaboration_mode=COLLABORATION_MODE,
                ephemeral=True,
            )
        )
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
            _is_terminal(s.status)
            for s in self._steps.values()
        )

    def nonterminal_steps(self) -> list[dict]:
        return [
            self._step_dict(s)
            for s in (self._steps[sid] for sid in self._step_order)
            if not _is_terminal(s.status)
        ]

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
            old_steps = list(self._steps.values())
            # Cancel any in-flight children before replacing the plan.
            for old in old_steps:
                if old.status in ("pending", "running"):
                    old.status = "cancelled"
                    old.summary = old.summary or "cancelled by plan replacement"
                    old.completed_at = time.time()
                if old._task and not old._task.done():
                    old._task.cancel()

        await self._await_step_cleanup(old_steps)

        async with self._lock:
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
        await self._emit_step_status(step)
        return True, ""

    async def cancel_step(self, step_id: str) -> tuple[bool, str]:
        async with self._lock:
            step = self._steps.get(step_id)
            if step is None:
                return False, f"cancel_step: unknown step_id {step_id!r}"
            if step.status not in ("pending", "running"):
                return False, f"cancel_step: step {step_id!r} already {step.status}"
            step.status = "cancelled"
            step.summary = step.summary or "cancelled"
            step.completed_at = time.time()
            if step._task and not step._task.done():
                step._task.cancel()

        await self._await_step_cleanup([step])
        await self._emit_step_status(step)
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
            steps = list(self._steps.values())
            for s in steps:
                if s.status in ("pending", "running"):
                    s.status = "cancelled"
                    s.summary = s.summary or "cancelled"
                    s.completed_at = time.time()
                if s._task and not s._task.done():
                    s._task.cancel()

        await self._await_step_cleanup(steps)

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

    async def _emit_step_status(self, step: _Step) -> None:
        if self._steps.get(step.step_id) is not step:
            return
        await self._emit(SessionEvent("orchestrator-step-status", self._step_dict(step)))

    async def _await_step_cleanup(self, steps: list[_Step]) -> None:
        tasks = [s._task for s in steps if s._task and not s._task.done()]
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)

        adapters = [s._adapter for s in steps if s._adapter is not None]
        for adapter in adapters:
            try:
                await asyncio.wait_for(adapter.stop(), timeout=3.0)
            except Exception:  # noqa: BLE001
                pass
        for step in steps:
            step._adapter = None

    async def _run_child(self, step: _Step) -> None:
        """Run one child Codex session for the step, capture its summary,
        kill the subprocess. Reports outcome via orchestrator events."""
        async with self._children_sema:
            adapter = self._adapter_factory()
            step._adapter = adapter
            step.child_session_id = f"child_{uuid.uuid4().hex[:12]}"
            await self._emit_step_status(step)

            try:
                await adapter.start()
            except Exception as exc:  # noqa: BLE001
                log.warning("orch step %s: adapter.start failed: %s", step.step_id, exc)
                step.status = "failed"
                step.summary = f"failed to start child Codex: {exc}"
                step.completed_at = time.time()
                step._adapter = None
                await self._emit_step_status(step)
                return

            consumer = asyncio.create_task(
                self._consume_child_events(step, adapter),
                name=f"orch-step-events-{step.step_id}",
            )

            turn_frame: dict = {
                "type": "turn-start",
                "input": CHILD_STEP_PROMPT_PREFIX + step.prompt,
            }
            if self._model:
                turn_frame["model"] = self._model
            if self._effort:
                turn_frame["effort"] = self._effort
            if self._permissions:
                turn_frame["permissions"] = self._permissions
            if self._approval_policy is not None:
                turn_frame["approvalPolicy"] = self._approval_policy
            try:
                await adapter.handle(turn_frame)
            except Exception as exc:  # noqa: BLE001
                log.warning("orch step %s: turn-start failed: %s", step.step_id, exc)
                step.status = "failed"
                step.summary = f"turn-start failed: {exc}"
                step.completed_at = time.time()
                consumer.cancel()
                with suppress(asyncio.CancelledError, Exception):
                    await consumer
                await adapter.stop()
                step._adapter = None
                await self._emit_step_status(step)
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
                consumer.cancel()
            except Exception as exc:  # noqa: BLE001
                log.warning("orch step %s: event consumer failed: %s", step.step_id, exc)
                step.status = "failed"
                step.summary = f"child event stream failed: {exc}"
                step.completed_at = time.time()
            finally:
                if not consumer.done():
                    consumer.cancel()
                with suppress(asyncio.CancelledError, Exception):
                    await consumer
                try:
                    await adapter.stop()
                except Exception:  # noqa: BLE001
                    pass
                step._adapter = None
                await self._emit_step_status(step)

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
            elif item_type == "mcp_tool_call":
                server = data.get("server")
                tool = data.get("tool")
                name = ".".join(str(p) for p in (server, tool) if p)
                payload["label"] = "mcp: " + (name or "tool")
                for key in (
                    "server",
                    "tool",
                    "arguments",
                    "status",
                    "result",
                    "error",
                    "duration_ms",
                    "mcp_app_resource_uri",
                ):
                    if key in data:
                        payload[key] = data[key]
            elif item_type == "dynamic_tool_call":
                namespace = data.get("namespace")
                tool = data.get("tool")
                name = ".".join(str(p) for p in (namespace, tool) if p)
                payload["label"] = "tool: " + (name or str(tool or "dynamic"))
                for key in (
                    "namespace",
                    "tool",
                    "arguments",
                    "status",
                    "success",
                    "content_items",
                    "duration_ms",
                ):
                    if key in data:
                        payload[key] = data[key]
            elif item_type == "agent_message" and kind == "item-completed":
                text = data.get("text") or ""
                if text:
                    payload["text"] = text[:400]
            elif item_type == "collab_agent_tool_call":
                _add_collab_step_payload(payload, data)
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
        root_turn_id: str | None = None
        root_thread_id: str | None = None
        root_agent_id = f"step:{step.step_id}"
        thread_parents: dict[str, str] = {}
        thread_depths: dict[str, int] = {}
        async for ev in adapter.events():
            data = ev.data or {}
            if root_thread_id is None and data.get("thread_id"):
                root_thread_id = data.get("thread_id")
                thread_depths[root_thread_id] = 1
            _record_collab_agent_links(data, root_agent_id, thread_parents, thread_depths)
            agent_event = _build_agent_event_payload(
                step=step,
                kind=ev.kind,
                data=data,
                root_agent_id=root_agent_id,
                root_thread_id=root_thread_id,
                thread_parents=thread_parents,
                thread_depths=thread_depths,
            )
            if agent_event is not None:
                await self._emit(SessionEvent("orchestrator-agent-event", agent_event))
            if ev.kind == "item-completed":
                if (
                    data.get("item_type") == "agent_message"
                    and (root_turn_id is None or data.get("turn_id") == root_turn_id)
                ):
                    text = data.get("text") or ""
                    if text:
                        agent_text_parts.append(text)
            elif ev.kind == "turn-started":
                if root_turn_id is None and data.get("turn_id"):
                    root_turn_id = data.get("turn_id")
                    if data.get("thread_id"):
                        root_thread_id = data.get("thread_id")
                        thread_depths[root_thread_id] = 1
            elif ev.kind == "turn-completed":
                if root_turn_id is not None and data.get("turn_id") != root_turn_id:
                    forwarded = self._build_step_event_payload(step.step_id, ev.kind, data)
                    if forwarded is not None:
                        await self._emit(SessionEvent("orchestrator-step-event", forwarded))
                    continue
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
                approval_id = data.get("approval_id")
                if approval_id:
                    try:
                        await adapter.handle({
                            "type": "approval-response",
                            "approval_id": approval_id,
                            "decision": "decline",
                        })
                    except Exception as exc:  # noqa: BLE001
                        step.status = "failed"
                        step.summary = f"failed to decline child approval: {exc}"
                        step.completed_at = time.time()
                        return
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


def _add_collab_step_payload(payload: dict, data: dict) -> None:
    tool = data.get("tool")
    status = data.get("status")
    prompt = data.get("prompt")
    for key in (
        "tool",
        "status",
        "sender_thread_id",
        "receiver_thread_ids",
        "prompt",
        "model",
        "reasoning_effort",
        "agents_states",
    ):
        if key in data:
            payload[key] = data[key]

    label = _collab_label(tool, prompt)
    if status == "failed":
        label += " failed"
    payload["label"] = label


def _record_collab_agent_links(
    data: dict,
    root_agent_id: str,
    thread_parents: dict[str, str],
    thread_depths: dict[str, int],
) -> None:
    """Track native Codex subagent parentage from collab tool call items."""
    if data.get("item_type") != "collab_agent_tool_call":
        return
    receivers = data.get("receiver_thread_ids")
    if not isinstance(receivers, list) or not receivers:
        return
    sender = data.get("sender_thread_id")
    if isinstance(sender, str) and sender:
        parent_id = sender if sender in thread_depths else root_agent_id
        parent_depth = thread_depths.get(sender, 1)
    else:
        parent_id = root_agent_id
        parent_depth = 1
    for receiver in receivers:
        if not isinstance(receiver, str) or not receiver:
            continue
        thread_parents.setdefault(receiver, parent_id)
        thread_depths.setdefault(receiver, min(parent_depth + 1, 8))


def _build_agent_event_payload(
    *,
    step: _Step,
    kind: str,
    data: dict,
    root_agent_id: str,
    root_thread_id: str | None,
    thread_parents: dict[str, str],
    thread_depths: dict[str, int],
) -> dict | None:
    if kind not in (
        "turn-started",
        "turn-completed",
        "item-started",
        "item-delta",
        "item-completed",
        "approval-request",
        "user-input-request",
    ):
        return None
    thread_id = data.get("thread_id")
    is_root = not thread_id or thread_id == root_thread_id
    if is_root:
        agent_id = root_agent_id
        parent_agent_id = None
        depth = 1
        label = step.title or step.step_id
    else:
        agent_id = str(thread_id)
        parent_agent_id = thread_parents.get(agent_id, root_agent_id)
        depth = thread_depths.get(agent_id, 2)
        label = f"native agent {agent_id[:8]}"

    payload = {
        **data,
        "agent_id": agent_id,
        "parent_agent_id": parent_agent_id,
        "step_id": step.step_id,
        "thread_id": thread_id,
        "depth": depth,
        "label": label,
        "kind": kind,
    }
    return payload


def _collab_label(tool: object, prompt: object) -> str:
    labels = {
        "spawnAgent": "spawn agent",
        "sendInput": "send input",
        "resumeAgent": "resume agent",
        "wait": "wait agents",
        "closeAgent": "close agent",
    }
    label = labels.get(str(tool), str(tool or "subagent"))
    if tool == "spawnAgent" and isinstance(prompt, str) and prompt.strip():
        preview = " ".join(prompt.strip().split())
        if len(preview) > 80:
            preview = preview[:77].rstrip() + "..."
        return f"{label}: {preview}"
    return label


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
