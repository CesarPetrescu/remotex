from __future__ import annotations

import asyncio
import json
import uuid
from collections.abc import Awaitable, Callable

import pytest

from daemon.adapters.base import SessionEvent
from daemon.adapters.stdio import StdioCodexAdapter
from daemon.orchestrator import mcp_shim
from daemon.orchestrator.adapter import OrchestratorAdapter
from daemon.orchestrator.bridge import BridgeServer
from daemon.orchestrator.collab import (
    AGENT_MAX_DEPTH,
    AGENT_MAX_THREADS,
    COLLABORATION_MODE,
    native_subagent_thread_config,
)
from daemon.orchestrator.runtime import OrchestratorRuntime


class FakeChildAdapter:
    def __init__(
        self,
        on_turn: Callable[["FakeChildAdapter", dict], Awaitable[None]] | None = None,
    ) -> None:
        self.on_turn = on_turn or complete_turn
        self.handled: list[dict] = []
        self.approval_responses: list[dict] = []
        self.queue: asyncio.Queue[SessionEvent | None] = asyncio.Queue()
        self.started = False
        self.stop_calls = 0
        self.turn_started = asyncio.Event()
        self.stopped = asyncio.Event()

    async def start(self) -> None:
        self.started = True

    async def stop(self) -> None:
        self.stop_calls += 1
        self.stopped.set()
        await self.queue.put(None)

    async def handle(self, frame: dict) -> None:
        self.handled.append(frame)
        if frame.get("type") == "turn-start":
            self.turn_started.set()
            await self.on_turn(self, frame)
        elif frame.get("type") == "approval-response":
            self.approval_responses.append(frame)
            await self.queue.put(SessionEvent("turn-completed", {}))

    async def events(self):
        while True:
            ev = await self.queue.get()
            if ev is None:
                return
            yield ev


async def complete_turn(adapter: FakeChildAdapter, _frame: dict) -> None:
    await adapter.queue.put(SessionEvent(
        "item-completed",
        {"item_type": "agent_message", "text": "child summary"},
    ))
    await adapter.queue.put(SessionEvent("turn-completed", {}))


async def request_approval(adapter: FakeChildAdapter, _frame: dict) -> None:
    await adapter.queue.put(SessionEvent("approval-request", {"approval_id": "appr_1"}))


async def block_turn(_adapter: FakeChildAdapter, _frame: dict) -> None:
    return


async def nested_subagent_turn(adapter: FakeChildAdapter, _frame: dict) -> None:
    await adapter.queue.put(SessionEvent("turn-started", {"turn_id": "root"}))
    await adapter.queue.put(SessionEvent(
        "item-completed",
        {"turn_id": "nested", "item_type": "agent_message", "text": "nested summary"},
    ))
    await adapter.queue.put(SessionEvent("turn-completed", {"turn_id": "nested"}))
    await adapter.queue.put(SessionEvent(
        "item-completed",
        {
            "turn_id": "root",
            "item_id": "wait_1",
            "item_type": "collab_agent_tool_call",
            "tool": "wait",
            "status": "completed",
        },
    ))
    await adapter.queue.put(SessionEvent(
        "item-completed",
        {"turn_id": "root", "item_type": "agent_message", "text": "root summary"},
    ))
    await adapter.queue.put(SessionEvent("turn-completed", {"turn_id": "root"}))


async def mcp_child_turn(adapter: FakeChildAdapter, _frame: dict) -> None:
    await adapter.queue.put(SessionEvent("turn-started", {
        "turn_id": "root",
        "thread_id": "child-thread",
    }))
    await adapter.queue.put(SessionEvent("item-started", {
        "turn_id": "root",
        "thread_id": "child-thread",
        "item_id": "mcp_1",
        "item_type": "mcp_tool_call",
        "server": "orchestrator",
        "tool": "list_steps",
        "arguments": {},
        "status": "inProgress",
    }))
    await adapter.queue.put(SessionEvent("item-completed", {
        "turn_id": "root",
        "thread_id": "child-thread",
        "item_id": "mcp_1",
        "item_type": "mcp_tool_call",
        "server": "orchestrator",
        "tool": "list_steps",
        "arguments": {},
        "status": "completed",
        "duration_ms": 12,
        "result": {"content": [{"type": "text", "text": "ok"}]},
    }))
    await adapter.queue.put(SessionEvent(
        "item-completed",
        {
            "turn_id": "root",
            "thread_id": "child-thread",
            "item_type": "agent_message",
            "text": "mcp summary",
        },
    ))
    await adapter.queue.put(SessionEvent("turn-completed", {
        "turn_id": "root",
        "thread_id": "child-thread",
    }))


class AdapterFactory:
    def __init__(self, *adapters: FakeChildAdapter) -> None:
        self.adapters = list(adapters)
        self.created: list[FakeChildAdapter] = []

    def __call__(self) -> FakeChildAdapter:
        adapter = self.adapters.pop(0)
        self.created.append(adapter)
        return adapter


def _runtime(
    emit_events: list[SessionEvent],
    factory: AdapterFactory,
    **kwargs,
) -> OrchestratorRuntime:
    async def emit(ev: SessionEvent) -> None:
        emit_events.append(ev)

    return OrchestratorRuntime(
        codex_binary="codex",
        cwd="/work",
        emit=emit,
        adapter_factory=factory,
        **kwargs,
    )


@pytest.mark.asyncio
async def test_runtime_declines_child_approval_without_waiting():
    events: list[SessionEvent] = []
    child = FakeChildAdapter(request_approval)
    runtime = _runtime(events, AdapterFactory(child))

    ok, err = await runtime.submit_plan([{"step_id": "a", "prompt": "do it"}])
    assert ok, err
    ok, err = await runtime.spawn_step("a")
    assert ok, err

    ok, err, statuses = await runtime.await_steps(["a"])
    assert ok, err
    assert child.approval_responses == [{
        "type": "approval-response",
        "approval_id": "appr_1",
        "decision": "decline",
    }]
    assert statuses["a"]["status"] == "completed"


@pytest.mark.asyncio
async def test_runtime_forwards_child_approval_policy_to_turn_start():
    events: list[SessionEvent] = []
    child = FakeChildAdapter()
    runtime = _runtime(
        events,
        AdapterFactory(child),
        permissions="readonly",
        approval_policy="on-failure",
        model="gpt-test",
        effort="high",
    )

    await runtime.submit_plan([{"step_id": "a", "prompt": "inspect"}])
    ok, err = await runtime.spawn_step("a")
    assert ok, err
    await runtime.await_steps(["a"])

    turn_start = child.handled[0]
    assert turn_start["type"] == "turn-start"
    assert turn_start["permissions"] == "readonly"
    assert turn_start["approvalPolicy"] == "on-failure"
    assert turn_start["model"] == "gpt-test"
    assert turn_start["effort"] == "high"
    assert turn_start["input"].startswith("You are a Remotex child Codex agent")


@pytest.mark.asyncio
async def test_runtime_ignores_nested_subagent_turn_completed_for_step_lifecycle():
    events: list[SessionEvent] = []
    child = FakeChildAdapter(nested_subagent_turn)
    runtime = _runtime(events, AdapterFactory(child))

    await runtime.submit_plan([{"step_id": "a", "prompt": "nested"}])
    ok, err = await runtime.spawn_step("a")
    assert ok, err
    ok, err, statuses = await runtime.await_steps(["a"])
    assert ok, err

    assert statuses["a"]["status"] == "completed"
    assert statuses["a"]["summary"] == "root summary"
    labels = [
        ev.data.get("label")
        for ev in events
        if ev.kind == "orchestrator-step-event"
    ]
    assert "wait agents" in labels
    agent_events = [
        ev.data
        for ev in events
        if ev.kind == "orchestrator-agent-event"
    ]
    assert any(e.get("agent_id") == "step:a" for e in agent_events)
    assert any(e.get("item_id") == "wait_1" for e in agent_events)


@pytest.mark.asyncio
async def test_runtime_forwards_mcp_tool_calls_to_step_and_agent_streams():
    events: list[SessionEvent] = []
    child = FakeChildAdapter(mcp_child_turn)
    runtime = _runtime(events, AdapterFactory(child))

    await runtime.submit_plan([{"step_id": "a", "prompt": "mcp"}])
    ok, err = await runtime.spawn_step("a")
    assert ok, err
    ok, err, statuses = await runtime.await_steps(["a"])
    assert ok, err

    assert statuses["a"]["summary"] == "mcp summary"
    step_events = [
        ev.data
        for ev in events
        if ev.kind == "orchestrator-step-event"
    ]
    assert any(
        e.get("item_type") == "mcp_tool_call"
        and e.get("label") == "mcp: orchestrator.list_steps"
        and e.get("duration_ms") == 12
        for e in step_events
    )
    agent_events = [
        ev.data
        for ev in events
        if ev.kind == "orchestrator-agent-event"
    ]
    assert any(
        e.get("agent_id") == "step:a"
        and e.get("item_type") == "mcp_tool_call"
        and e.get("result") == {"content": [{"type": "text", "text": "ok"}]}
        for e in agent_events
    )


def test_orchestrator_brain_config_enables_mcp_and_native_subagents():
    adapter = OrchestratorAdapter(codex_binary="codex", cwd="/work", task="task")
    config = adapter._brain_mcp_config()

    assert config["features"]["multi_agent"] is True
    assert config["agents"] == {
        "max_depth": AGENT_MAX_DEPTH,
        "max_threads": AGENT_MAX_THREADS,
    }
    assert config["mcp_servers"]["orchestrator"]["enabled"] is True
    assert adapter._brain._forced_collaboration_mode == COLLABORATION_MODE


def test_orchestrator_child_factory_enables_native_subagents():
    async def emit(_ev: SessionEvent) -> None:
        return None

    runtime = OrchestratorRuntime(codex_binary="codex", cwd="/work", emit=emit)
    child = runtime._adapter_factory()

    assert child._extra_thread_config == native_subagent_thread_config()
    assert child._forced_collaboration_mode == COLLABORATION_MODE


@pytest.mark.asyncio
async def test_runtime_cancel_step_awaits_child_cleanup():
    events: list[SessionEvent] = []
    child = FakeChildAdapter(block_turn)
    runtime = _runtime(events, AdapterFactory(child))

    await runtime.submit_plan([{"step_id": "a", "prompt": "block"}])
    ok, err = await runtime.spawn_step("a")
    assert ok, err
    await asyncio.wait_for(child.turn_started.wait(), timeout=1.0)

    ok, err = await runtime.cancel_step("a")
    assert ok, err
    assert child.stopped.is_set()
    assert child.stop_calls == 1
    assert runtime.list_steps()[0]["status"] == "cancelled"


@pytest.mark.asyncio
async def test_runtime_plan_replacement_awaits_old_child_cleanup():
    events: list[SessionEvent] = []
    child = FakeChildAdapter(block_turn)
    runtime = _runtime(events, AdapterFactory(child))

    await runtime.submit_plan([{"step_id": "old", "prompt": "block"}])
    ok, err = await runtime.spawn_step("old")
    assert ok, err
    await asyncio.wait_for(child.turn_started.wait(), timeout=1.0)

    ok, err = await runtime.submit_plan([{"step_id": "new", "prompt": "next"}])
    assert ok, err
    assert child.stopped.is_set()
    assert runtime.list_steps()[0]["step_id"] == "new"
    assert runtime.list_steps()[0]["status"] == "pending"


@pytest.mark.asyncio
async def test_orchestrator_finish_requires_terminal_steps():
    adapter = OrchestratorAdapter(codex_binary="codex", cwd="/work", task="task")
    handlers = adapter._build_handlers()

    await adapter._runtime.submit_plan([{"step_id": "a", "prompt": "do it"}])
    with pytest.raises(RuntimeError, match="non-terminal steps remain"):
        await handlers["finish"]({"summary": "done"})

    ok, err = await adapter._runtime.cancel_step("a")
    assert ok, err
    result = await handlers["finish"]({"summary": "done"})
    assert result == {"ok": True}
    assert adapter._finished is True


@pytest.mark.asyncio
async def test_stdio_turn_start_approval_policy_overrides_permission_default():
    adapter = StdioCodexAdapter(default_cwd="/work")
    adapter._thread_id = "thread_1"
    adapter._ready = True
    adapter._current_cwd = "/work"
    calls: list[tuple[str, dict]] = []

    async def fake_request(method: str, params: dict, **_kwargs):
        calls.append((method, params))
        return {}

    adapter._request = fake_request
    await adapter.handle({
        "type": "turn-start",
        "input": "hi",
        "permissions": "readonly",
        "approvalPolicy": "on-failure",
    })

    assert calls[0][0] == "turn/start"
    params = calls[0][1]
    assert params["sandboxPolicy"]["type"] == "readOnly"
    assert params["approvalPolicy"] == "on-failure"


@pytest.mark.asyncio
async def test_stdio_plan_mode_uses_current_model():
    adapter = StdioCodexAdapter(default_cwd="/work")
    adapter._thread_id = "thread_1"
    adapter._ready = True
    adapter._current_cwd = "/work"
    adapter._current_model = "gpt-5.5"
    adapter._current_effort = "high"
    calls: list[tuple[str, dict]] = []

    async def fake_request(method: str, params: dict, **_kwargs):
        calls.append((method, params))
        return {}

    adapter._request = fake_request
    await adapter.handle({"type": "slash-command", "command": "plan"})
    ack = await asyncio.wait_for(adapter._queue.get(), timeout=1.0)
    assert ack.kind == "slash-ack"
    assert ack.data["message"] == "next turn will use plan mode"

    await adapter.handle({"type": "turn-start", "input": "look deeper"})

    assert calls[0][0] == "turn/start"
    params = calls[0][1]
    assert params["collaborationMode"] == {
        "mode": "plan",
        "settings": {
            "model": "gpt-5.5",
            "reasoning_effort": "medium",
            "developer_instructions": None,
        },
    }
    assert adapter._current_model == "gpt-5.5"
    assert adapter._current_effort == "medium"


@pytest.mark.asyncio
async def test_stdio_rejects_unknown_server_request_instead_of_hanging():
    adapter = StdioCodexAdapter(default_cwd="/work")
    sent: list[dict] = []

    async def fake_send(obj: dict) -> None:
        sent.append(obj)

    adapter._send = fake_send

    await adapter._dispatch({
        "id": 99,
        "method": "mcpServer/elicitation/request",
        "params": {},
    })

    assert sent == [{
        "id": 99,
        "error": {
            "code": -32601,
            "message": "unsupported Codex server request: mcpServer/elicitation/request",
        },
    }]
    ev = await asyncio.wait_for(adapter._queue.get(), timeout=1.0)
    assert ev == SessionEvent(
        "turn-completed",
        {"error": "unsupported Codex server request: mcpServer/elicitation/request"},
    )


@pytest.mark.asyncio
async def test_stdio_default_mode_sends_explicit_collaboration_mode():
    adapter = StdioCodexAdapter(default_cwd="/work")
    adapter._thread_id = "thread_1"
    adapter._ready = True
    adapter._current_cwd = "/work"
    adapter._current_model = "gpt-5.5"
    adapter._current_effort = "high"
    adapter._next_collab_mode = "plan"
    calls: list[tuple[str, dict]] = []

    async def fake_request(method: str, params: dict, **_kwargs):
        calls.append((method, params))
        return {}

    adapter._request = fake_request
    await adapter.handle({"type": "slash-command", "command": "default"})
    ack = await asyncio.wait_for(adapter._queue.get(), timeout=1.0)
    assert ack.kind == "slash-ack"
    assert ack.data["message"] == "next turn will use default mode"

    await adapter.handle({"type": "turn-start", "input": "go"})

    assert calls[0][0] == "turn/start"
    params = calls[0][1]
    assert params["collaborationMode"] == {
        "mode": "default",
        "settings": {
            "model": "gpt-5.5",
            "reasoning_effort": "high",
            "developer_instructions": None,
        },
    }
    assert adapter._next_collab_mode == "default"


@pytest.mark.asyncio
async def test_stdio_forced_collaboration_mode_applies_without_slash():
    adapter = StdioCodexAdapter(
        default_cwd="/work",
        forced_collaboration_mode="default",
    )
    adapter._thread_id = "thread_1"
    adapter._ready = True
    adapter._current_cwd = "/work"
    adapter._current_model = "gpt-5.5"
    adapter._current_effort = "high"
    calls: list[tuple[str, dict]] = []

    async def fake_request(method: str, params: dict, **_kwargs):
        calls.append((method, params))
        return {}

    adapter._request = fake_request
    await adapter.handle({"type": "turn-start", "input": "go"})

    assert calls[0][0] == "turn/start"
    assert calls[0][1]["collaborationMode"] == {
        "mode": "default",
        "settings": {
            "model": "gpt-5.5",
            "reasoning_effort": "high",
            "developer_instructions": None,
        },
    }


@pytest.mark.asyncio
async def test_stdio_dispatches_collab_agent_tool_call_item():
    adapter = StdioCodexAdapter(default_cwd="/work")

    await adapter._dispatch({
        "method": "item/started",
        "params": {
            "turnId": "turn_1",
            "item": {
                "id": "spawn-call-1",
                "type": "collabAgentToolCall",
                "tool": "spawnAgent",
                "status": "inProgress",
                "senderThreadId": "parent",
                "receiverThreadIds": [],
                "prompt": "inspect auth",
                "model": "gpt-test",
                "reasoningEffort": "low",
                "agentsStates": {},
            },
        },
    })

    ev = await asyncio.wait_for(adapter._queue.get(), timeout=1.0)
    assert ev.kind == "item-started"
    assert ev.data == {
        "turn_id": "turn_1",
        "item_id": "spawn-call-1",
        "item_type": "collab_agent_tool_call",
        "tool": "spawnAgent",
        "status": "inProgress",
        "sender_thread_id": "parent",
        "receiver_thread_ids": [],
        "prompt": "inspect auth",
        "model": "gpt-test",
        "reasoning_effort": "low",
        "agents_states": {},
    }


def test_runtime_labels_collab_agent_step_events():
    payload = OrchestratorRuntime._build_step_event_payload(
        "a",
        "item-started",
        {
            "item_id": "spawn-call-1",
            "item_type": "collab_agent_tool_call",
            "tool": "spawnAgent",
            "status": "inProgress",
            "sender_thread_id": "parent",
            "receiver_thread_ids": [],
            "prompt": "inspect the authentication routing and summarize the ownership",
            "model": "gpt-test",
            "reasoning_effort": "low",
            "agents_states": {},
        },
    )

    assert payload is not None
    assert payload["label"].startswith("spawn agent: inspect the authentication")
    assert payload["tool"] == "spawnAgent"
    assert payload["status"] == "inProgress"


@pytest.mark.asyncio
async def test_stdio_ignores_nested_subagent_turn_lifecycle():
    adapter = StdioCodexAdapter(default_cwd="/work")

    await adapter._dispatch({
        "method": "turn/started",
        "params": {
            "turn": {"id": "root"},
            "input": [{"type": "text", "text": "root", "text_elements": []}],
        },
    })
    root_started = await asyncio.wait_for(adapter._queue.get(), timeout=1.0)
    assert root_started == SessionEvent(
        "turn-started",
        {"turn_id": "root", "input": "root"},
    )

    await adapter._dispatch({
        "method": "turn/started",
        "params": {
            "turn": {"id": "nested"},
            "input": [{"type": "text", "text": "nested", "text_elements": []}],
        },
    })
    assert adapter._turn_id == "root"

    await adapter._dispatch({
        "method": "turn/completed",
        "params": {"turn": {"id": "nested", "status": "completed"}},
    })
    assert adapter._turn_id == "root"

    await adapter._dispatch({
        "method": "turn/completed",
        "params": {
            "turn": {
                "id": "root",
                "status": "completed",
                "durationMs": 12,
            },
        },
    })
    root_completed = await asyncio.wait_for(adapter._queue.get(), timeout=1.0)
    assert root_completed == SessionEvent(
        "turn-completed",
        {"turn_id": "root", "duration_ms": 12, "status": "completed"},
    )
    assert adapter._turn_id is None


@pytest.mark.asyncio
async def test_stdio_plan_mode_rejects_missing_model():
    adapter = StdioCodexAdapter(default_cwd="/work")
    adapter._thread_id = "thread_1"
    adapter._ready = True
    calls: list[tuple[str, dict]] = []

    async def fake_request(method: str, params: dict, **_kwargs):
        calls.append((method, params))
        return {}

    adapter._request = fake_request
    await adapter.handle({"type": "slash-command", "command": "plan"})
    await asyncio.wait_for(adapter._queue.get(), timeout=1.0)

    await adapter.handle({"type": "turn-start", "input": "look deeper"})

    ev = await asyncio.wait_for(adapter._queue.get(), timeout=1.0)
    assert ev.kind == "turn-completed"
    assert "Codex did not report a current model" in ev.data["error"]
    assert calls == []


@pytest.mark.asyncio
async def test_stdio_permissions_approval_request_round_trips_accept():
    adapter = StdioCodexAdapter(default_cwd="/work")
    sent: list[dict] = []

    async def fake_send(obj: dict):
        sent.append(obj)

    adapter._send = fake_send
    await adapter._dispatch({
        "id": 77,
        "method": "item/permissions/requestApproval",
        "params": {
            "threadId": "thread_1",
            "turnId": "turn_1",
            "itemId": "call_1",
            "cwd": "/work",
            "reason": "Need host-level read access",
            "permissions": {
                "network": {"enabled": True},
                "fileSystem": {"read": ["/sys"]},
            },
        },
    })

    ev = await asyncio.wait_for(adapter._queue.get(), timeout=1.0)
    assert ev.kind == "approval-request"
    assert ev.data["kind"] == "permissions"
    assert ev.data["cwd"] == "/work"
    assert ev.data["permissions"]["network"] == {"enabled": True}

    await adapter.handle({
        "type": "approval-response",
        "approval_id": ev.data["approval_id"],
        "decision": "acceptForSession",
    })

    assert sent == [{
        "id": 77,
        "result": {
            "permissions": {
                "network": {"enabled": True},
                "fileSystem": {"read": ["/sys"]},
            },
            "scope": "session",
        },
    }]


@pytest.mark.asyncio
async def test_stdio_permissions_approval_request_round_trips_decline():
    adapter = StdioCodexAdapter(default_cwd="/work")
    sent: list[dict] = []

    async def fake_send(obj: dict):
        sent.append(obj)

    adapter._send = fake_send
    await adapter._dispatch({
        "id": 78,
        "method": "item/permissions/requestApproval",
        "params": {
            "threadId": "thread_1",
            "turnId": "turn_1",
            "itemId": "call_1",
            "cwd": "/work",
            "permissions": {"network": {"enabled": True}},
        },
    })

    ev = await asyncio.wait_for(adapter._queue.get(), timeout=1.0)
    await adapter.handle({
        "type": "approval-response",
        "approval_id": ev.data["approval_id"],
        "decision": "decline",
    })

    assert sent == [{
        "id": 78,
        "result": {
            "permissions": {},
            "scope": "turn",
        },
    }]


@pytest.mark.asyncio
async def test_stdio_rejects_unknown_approval_policy():
    adapter = StdioCodexAdapter(default_cwd="/work")
    adapter._thread_id = "thread_1"
    adapter._ready = True

    await adapter.handle({
        "type": "turn-start",
        "input": "hi",
        "approvalPolicy": "bogus",
    })
    ev = await asyncio.wait_for(adapter._queue.get(), timeout=1.0)
    assert ev.kind == "turn-completed"
    assert "unsupported approvalPolicy" in ev.data["error"]


@pytest.mark.asyncio
async def test_bridge_server_dispatches_json_rpc_over_unix_socket():
    async def echo(params: dict) -> dict:
        return {"echo": params["value"]}

    server = BridgeServer(uuid.uuid4().hex[:12], {"echo": echo})
    await server.start()
    try:
        reader, writer = await asyncio.open_unix_connection(server.socket_path)
        writer.write(json.dumps({
            "id": 7,
            "method": "echo",
            "params": {"value": "ok"},
        }).encode() + b"\n")
        await writer.drain()
        line = await asyncio.wait_for(reader.readline(), timeout=1.0)
        assert json.loads(line) == {"id": 7, "result": {"echo": "ok"}}
        writer.close()
        await writer.wait_closed()
    finally:
        await server.stop()


@pytest.mark.asyncio
async def test_mcp_shim_lists_tools_and_forwards_tool_calls(monkeypatch):
    async def fake_bridge_call(socket_path: str, method: str, params: dict) -> dict:
        assert socket_path == "/tmp/orch.sock"
        assert method == "spawn_step"
        assert params == {"step_id": "a"}
        return {"step_id": "a", "status": "running"}

    monkeypatch.setattr(mcp_shim, "_bridge_call", fake_bridge_call)

    listed = await mcp_shim._handle_request(
        "/tmp/orch.sock",
        {"jsonrpc": "2.0", "id": 1, "method": "tools/list"},
    )
    assert listed["result"]["tools"]

    reply = await mcp_shim._handle_request(
        "/tmp/orch.sock",
        {
            "jsonrpc": "2.0",
            "id": 2,
            "method": "tools/call",
            "params": {"name": "spawn_step", "arguments": {"step_id": "a"}},
        },
    )
    assert reply["result"]["isError"] is False
    assert json.loads(reply["result"]["content"][0]["text"]) == {
        "step_id": "a",
        "status": "running",
    }
