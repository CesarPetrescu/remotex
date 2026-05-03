"""SessionAdapter that runs the orchestrator brain as a real Codex
session whose MCP toolbelt is wired back into our daemon.

How it fits together:

    +-------------------+     stdin/stdout JSON-RPC     +----------------+
    |   brain  codex    | <----------------------------> |   mcp_shim.py  |
    +-------------------+                                +--------+-------+
              ^                                                  |
              |  thread/start.config.mcp_servers = orchestrator   | unix
              |                                                  | socket
              v                                                  v
    +-------------------+                                +----------------+
    | StdioCodexAdapter |                                |  BridgeServer  |
    +-------------------+                                +--------+-------+
              ^                                                  |
              |                                                  v
              |                                        +----------------+
              |                                        |OrchestratorRun-|
              +------- SessionEvents (UI) ----+        | time           |
                                              |        +-------+--------+
                                              v                |
                                        relay/clients   spawns child codexes
                                                        per plan step

The brain is just a normal codex with one extra MCP server registered;
it issues `tools/call orchestrator.submit_plan` etc. instead of writing
free-text commands. We watch its events for two reasons only:

1. Forward them upstream so the operator sees the brain's reasoning live.
2. Detect when the brain's session ends (turn-completed with error or no
   further tool calls + finish() called → emit orchestrator-finished).
"""
from __future__ import annotations

import asyncio
import logging
import shutil
import sys
import uuid
from pathlib import Path
from typing import AsyncIterator

from ..adapters.base import SessionAdapter, SessionEvent
from ..adapters.stdio import StdioCodexAdapter
from .bridge import BridgeServer, socket_path_for
from .collab import COLLABORATION_MODE, merge_thread_config, native_subagent_thread_config
from .prompts import ORCHESTRATOR_SYSTEM_PROMPT, ORCHESTRATOR_TURN_REMINDER
from .runtime import OrchestratorRuntime, _record_collab_agent_links

log = logging.getLogger("daemon.orchestrator.adapter")


# Cap on how many turns the brain can chew through. A runaway brain
# that never calls finish() shouldn't be able to burn an unbounded
# number of API calls.
MAX_BRAIN_TURNS = 80


def _shim_path() -> str:
    """Absolute path to mcp_shim.py — codex spawns it as a subprocess."""
    return str((Path(__file__).resolve().parent / "mcp_shim.py"))


def _python_executable() -> str:
    """Interpreter codex should use to run the shim. We reuse our own
    sys.executable so the shim runs under the same venv as the daemon
    (no need to install anything inside the brain's environment)."""
    return sys.executable or shutil.which("python3") or "python3"


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
        self._session_id = uuid.uuid4().hex[:12]
        self._socket_path = socket_path_for(self._session_id)
        self._brain_model = model
        self._brain_effort = effort
        # Note: `permissions` is intentionally not stored on the brain;
        # see `_brain_turn_frame` for why the brain hard-codes "full".
        # The runtime forwards the operator's choice to children.
        self._approval_policy = approval_policy

        self._brain = StdioCodexAdapter(
            codex_binary=codex_binary,
            default_cwd=cwd or None,
            extra_thread_config=self._brain_mcp_config(),
            # Surface "orchestrator" in the brain's session-started so
            # the client's KIND chip reflects what the user actually
            # opened (the brain itself is a regular codex under the
            # hood, but to the user this is the orchestrator session).
            session_kind="orchestrator",
            forced_collaboration_mode=COLLABORATION_MODE,
        )
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
        self._bridge = BridgeServer(self._session_id, self._build_handlers())
        self._pump_task: asyncio.Task | None = None
        self._finished = False
        self._brain_turns = 0
        self._initial_input_sent = False

    # --- adapter API ----------------------------------------------------

    async def start(self) -> None:
        # Bridge first — codex spawns the shim as soon as thread/start
        # completes, and the shim immediately tries to connect.
        await self._bridge.start()
        try:
            await self._brain.start()
        except Exception:
            await self._bridge.stop()
            raise
        self._pump_task = asyncio.create_task(self._pump_brain(), name="orch-brain-pump")

    async def stop(self) -> None:
        if self._pump_task:
            self._pump_task.cancel()
            try:
                await self._pump_task
            except asyncio.CancelledError:
                pass
        await self._runtime.shutdown()
        try:
            await self._brain.stop()
        finally:
            await self._bridge.stop()
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
        # Approval responses, slash commands, user-input answers — pass
        # through unchanged so existing UI plumbing works on the brain.
        await self._brain.handle(frame)

    async def events(self) -> AsyncIterator[SessionEvent]:
        while True:
            ev = await self._queue.get()
            if ev is None:
                return
            yield ev

    # --- internals ------------------------------------------------------

    def _brain_mcp_config(self) -> dict:
        """The `thread/start.config` blob that registers our MCP server
        on the brain's session. Codex's McpServerConfig is a flat dict
        per server: {command, args, env?, enabled?}."""
        mcp_config = {
            "mcp_servers": {
                "orchestrator": {
                    "command": _python_executable(),
                    "args": [_shim_path(), self._socket_path],
                    "enabled": True,
                },
            },
        }
        return merge_thread_config(native_subagent_thread_config(), mcp_config)

    def _build_handlers(self) -> dict:
        """Map MCP tool name → async runtime call. Each handler must
        return a JSON-serialisable dict; raised exceptions become the
        bridge's error reply (which becomes an MCP isError result)."""

        async def submit_plan(params: dict) -> dict:
            ok, err = await self._runtime.submit_plan(params.get("steps") or [])
            if not ok:
                raise RuntimeError(err)
            return {"steps": self._runtime.list_steps()}

        async def spawn_step(params: dict) -> dict:
            sid = str(params.get("step_id") or "")
            ok, err = await self._runtime.spawn_step(sid)
            if not ok:
                raise RuntimeError(err)
            return {"step_id": sid, "status": "running"}

        async def await_steps(params: dict) -> dict:
            ids = params.get("step_ids") or []
            ok, err, status_map = await self._runtime.await_steps(ids)
            if not ok:
                raise RuntimeError(err)
            return {"steps": status_map}

        async def cancel_step(params: dict) -> dict:
            sid = str(params.get("step_id") or "")
            ok, err = await self._runtime.cancel_step(sid)
            if not ok:
                raise RuntimeError(err)
            return {"step_id": sid, "status": "cancelled"}

        async def list_steps(_params: dict) -> dict:
            return {"steps": self._runtime.list_steps()}

        async def finish(params: dict) -> dict:
            summary = str(params.get("summary") or "")
            if not self._runtime.has_steps():
                raise RuntimeError("finish: no plan has been submitted")
            nonterminal = self._runtime.nonterminal_steps()
            if nonterminal:
                detail = ", ".join(
                    f"{s.get('step_id')}={s.get('status')}" for s in nonterminal
                )
                raise RuntimeError(
                    "finish: non-terminal steps remain; "
                    f"await or cancel them before finishing ({detail})"
                )
            self._finished = True
            await self._emit(SessionEvent("orchestrator-finished", {
                "ok": True,
                "summary": summary,
                "steps": self._runtime.list_steps(),
            }))
            return {"ok": True}

        return {
            "submit_plan": submit_plan,
            "spawn_step": spawn_step,
            "await_steps": await_steps,
            "cancel_step": cancel_step,
            "list_steps": list_steps,
            "finish": finish,
        }

    def _brain_turn_frame(self, text: str, *, include_role_reminder: bool = True) -> dict:
        input_text = text
        if include_role_reminder:
            input_text = (
                ORCHESTRATOR_TURN_REMINDER
                + "\n\nUser update/task:\n"
                + (text or "(no text supplied)")
            )
        frame: dict = {"type": "turn-start", "input": input_text}
        if self._brain_model:
            frame["model"] = self._brain_model
        if self._brain_effort:
            frame["effort"] = self._brain_effort
        # Force "full" so codex auto-approves the brain's MCP tool
        # calls instead of popping a permission prompt the user can't
        # see. Codex only auto-approves MCP tools when approvalPolicy
        # is `never` AND the sandbox has full-disk-write access; any
        # other combination blocks the brain on a RequestUserInput
        # round-trip. The brain itself is forbidden by its system
        # prompt from running shell or editing files — it only ever
        # invokes orchestrator.* tools — so handing it dangerFullAccess
        # is safe in practice. Children spawned by the runtime get the
        # operator's chosen permissions; this override is brain-only.
        frame["permissions"] = "full"
        return frame

    async def _emit(self, ev: SessionEvent) -> None:
        await self._queue.put(ev)

    async def _pump_brain(self) -> None:
        try:
            session_started_seen = False
            root_thread_id: str | None = None
            thread_parents: dict[str, str] = {}
            thread_depths: dict[str, int] = {}
            async for ev in self._brain.events():
                # Forward every brain event upstream so the existing UI
                # sees the brain's reasoning + agent messages live.
                tagged = SessionEvent(ev.kind, {**(ev.data or {}), "_orchestrator_role": "brain"})
                await self._queue.put(tagged)
                kind = ev.kind
                data = ev.data or {}
                if root_thread_id is None and data.get("thread_id"):
                    root_thread_id = data.get("thread_id")
                    thread_depths[root_thread_id] = 0
                _record_collab_agent_links(data, "brain", thread_parents, thread_depths)
                agent_event = _build_brain_agent_event(
                    kind,
                    data,
                    root_thread_id=root_thread_id,
                    thread_parents=thread_parents,
                    thread_depths=thread_depths,
                )
                if agent_event is not None:
                    await self._queue.put(SessionEvent("orchestrator-agent-event", agent_event))
                if kind == "session-started" and not session_started_seen:
                    session_started_seen = True
                    if data.get("thread_id"):
                        root_thread_id = data.get("thread_id")
                        thread_depths[root_thread_id] = 0
                    if not self._initial_input_sent:
                        self._initial_input_sent = True
                        prompt = (
                            ORCHESTRATOR_SYSTEM_PROMPT
                            + "\n\n---\nUser task:\n"
                            + (self._task or "(no task supplied)")
                        )
                        try:
                            await self._brain.handle(
                                self._brain_turn_frame(prompt, include_role_reminder=False)
                            )
                        except Exception as exc:  # noqa: BLE001
                            log.exception("brain initial turn failed: %s", exc)
                            await self._emit(SessionEvent("orchestrator-finished", {
                                "ok": False,
                                "error": f"failed to start brain: {exc}",
                            }))
                            return
                elif kind == "turn-completed":
                    if self._finished:
                        return
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
                    # The brain runs its own loop — turn ends each time
                    # it stops calling tools / emits a final message. If
                    # it ended without calling finish() we just wait for
                    # the next user input or the next tool invocation.
                    # No artificial "nudge" needed; codex's tool-use
                    # convergence is the loop driver.
        except asyncio.CancelledError:
            pass
        except Exception as exc:  # noqa: BLE001
            log.exception("brain pump crashed: %s", exc)
            await self._emit(SessionEvent("orchestrator-finished", {
                "ok": False,
                "error": f"orchestrator pump crashed: {exc}",
            }))


def _build_brain_agent_event(
    kind: str,
    data: dict,
    *,
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
        agent_id = "brain"
        parent_agent_id = None
        depth = 0
        label = "brain"
    else:
        agent_id = str(thread_id)
        parent_agent_id = thread_parents.get(agent_id, "brain")
        depth = thread_depths.get(agent_id, 1)
        label = f"native agent {agent_id[:8]}"
    return {
        **data,
        "agent_id": agent_id,
        "parent_agent_id": parent_agent_id,
        "step_id": None,
        "thread_id": thread_id,
        "depth": depth,
        "label": label,
        "kind": kind,
    }
