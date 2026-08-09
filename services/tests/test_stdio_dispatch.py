"""_dispatch translation for codex notifications and server requests.

Frames here mirror codex 0.147.0 (`codex app-server generate-json-schema`);
no real `codex` binary is spawned — we stub the subprocess writer.
"""
from __future__ import annotations

import json

import pytest

from daemon.adapters.elicitation import elicitation_questions, elicitation_result
from daemon.adapters.stdio import StdioCodexAdapter


def _adapter() -> tuple[StdioCodexAdapter, list[dict]]:
    """Adapter with `_send` captured instead of written to codex stdin."""
    adapter = StdioCodexAdapter(default_cwd="/tmp")
    sent: list[dict] = []

    async def fake_send(obj: dict) -> None:
        sent.append(json.loads(json.dumps(obj)))

    adapter._send = fake_send  # type: ignore[method-assign]
    return adapter, sent


async def _drain(adapter: StdioCodexAdapter) -> list:
    events = []
    while not adapter._queue.empty():
        events.append(adapter._queue.get_nowait())
    return events


# --- 1. live command output ------------------------------------------------


@pytest.mark.asyncio
async def test_command_execution_output_delta_becomes_item_delta():
    adapter, _ = _adapter()

    await adapter._dispatch({
        "method": "item/commandExecution/outputDelta",
        "params": {
            "threadId": "th_1",
            "turnId": "tu_1",
            "itemId": "it_1",
            "delta": "hello\n",
        },
    })

    (event,) = await _drain(adapter)
    assert event.kind == "item-delta"
    # tool_call, not command_execution: both clients append tool deltas to
    # the item's `output` field and would drop an unknown item_type.
    assert event.data["item_type"] == "tool_call"
    assert event.data["item_id"] == "it_1"
    assert event.data["delta"] == "hello\n"


@pytest.mark.asyncio
async def test_file_change_item_renders_as_a_tool_call():
    """I-006: file edits used to arrive as an item_type no client drew."""
    adapter, _ = _adapter()

    await adapter._dispatch({
        "method": "item/started",
        "params": {"threadId": "th_1", "turnId": "tu_1", "item": {
            "type": "fileChange",
            "id": "fc_1",
            "status": "inProgress",
            "changes": [
                {"path": "/w/notes.txt", "kind": {"type": "add"}, "diff": "hello\n"},
                {"path": "/w/old.txt", "kind": {"type": "update", "move_path": "/w/new.txt"},
                 "diff": "@@ -1 +1 @@\n-a\n+b\n"},
            ],
        }},
    })

    (event,) = await _drain(adapter)
    assert event.data["item_type"] == "tool_call"
    assert event.data["tool"] == "edit"
    assert event.data["args"]["command"] == (
        "add /w/notes.txt\nupdate /w/old.txt → /w/new.txt"
    )
    # Multiple files get path headers so the diff stays readable.
    assert "--- /w/notes.txt" in event.data["output"]
    assert "+b" in event.data["output"]
    # Raw structure is still forwarded for clients that want it.
    assert len(event.data["changes"]) == 2


@pytest.mark.asyncio
async def test_single_file_change_diff_has_no_path_header():
    adapter, _ = _adapter()
    await adapter._dispatch({
        "method": "item/completed",
        "params": {"threadId": "th_1", "item": {
            "type": "fileChange", "id": "fc_2", "status": "completed",
            "changes": [{"path": "/w/a.txt", "kind": {"type": "update"},
                         "diff": "@@ -1 +1 @@\n-a\n+b\n"}],
        }},
    })
    (event,) = await _drain(adapter)
    assert event.data["output"] == "@@ -1 +1 @@\n-a\n+b\n"


@pytest.mark.asyncio
async def test_patch_updated_replaces_the_diff():
    """I-001: full-patch resend, so it must not append like a delta."""
    adapter, _ = _adapter()

    await adapter._dispatch({
        "method": "item/fileChange/patchUpdated",
        "params": {
            "threadId": "th_1", "turnId": "tu_1", "itemId": "fc_1",
            "changes": [{"path": "/w/a.txt", "kind": {"type": "update"},
                         "diff": "@@ -1 +1 @@\n-a\n+b\n"}],
        },
    })

    (event,) = await _drain(adapter)
    assert event.kind == "item-patch"
    assert event.data["item_id"] == "fc_1"
    assert event.data["item_type"] == "tool_call"
    assert event.data["output"] == "@@ -1 +1 @@\n-a\n+b\n"
    assert event.data["args"]["command"] == "update /w/a.txt"


@pytest.mark.asyncio
async def test_terminal_interaction_echoes_stdin():
    adapter, _ = _adapter()
    await adapter._dispatch({
        "method": "item/commandExecution/terminalInteraction",
        "params": {"threadId": "th", "turnId": "tu", "itemId": "it",
                   "processId": "123", "stdin": "yes\n"},
    })
    (event,) = await _drain(adapter)
    assert event.kind == "item-delta"
    assert event.data["delta"] == "yes\n"
    assert event.data["item_type"] == "tool_call"


@pytest.mark.asyncio
async def test_thread_compacted_acknowledges_the_slash_command():
    adapter, _ = _adapter()
    await adapter._dispatch({
        "method": "thread/compacted",
        "params": {"threadId": "th", "turnId": "tu"},
    })
    (event,) = await _drain(adapter)
    assert event.kind == "slash-ack"
    assert event.data == {"command": "compact", "ok": True,
                          "message": "context compacted"}


# --- I-003: codex resolving its own request ------------------------------


@pytest.mark.asyncio
async def test_server_request_resolved_retracts_the_approval():
    adapter, sent = _adapter()

    await adapter._dispatch({
        "id": 42,
        "method": "item/commandExecution/requestApproval",
        "params": {"threadId": "th", "turnId": "tu", "command": "rm -rf /"},
    })
    (request_event,) = await _drain(adapter)
    approval_id = request_event.data["approval_id"]
    assert approval_id in adapter._pending_approvals

    await adapter._dispatch({
        "method": "serverRequest/resolved",
        "params": {"threadId": "th", "requestId": 42},
    })

    (resolved,) = await _drain(adapter)
    assert resolved.kind == "approval-resolved"
    assert resolved.data == {"approval_id": approval_id, "resolved_by": "codex"}
    # Dropped from the pending map, and we never answer an already-resolved
    # request — codex would reject a second reply.
    assert adapter._pending_approvals == {}
    assert sent == []


@pytest.mark.asyncio
async def test_server_request_resolved_retracts_an_elicitation():
    adapter, sent = _adapter()

    await adapter._dispatch({
        "id": 7, "method": "mcpServer/elicitation/request",
        "params": {"serverName": "s", "threadId": "th", "message": "?",
                   "mode": "form",
                   "requestedSchema": {"properties": {"n": {"type": "string"}}}},
    })
    (request_event,) = await _drain(adapter)

    await adapter._dispatch({
        "method": "serverRequest/resolved",
        "params": {"threadId": "th", "requestId": 7},
    })

    (resolved,) = await _drain(adapter)
    assert resolved.kind == "user-input-resolved"
    assert resolved.data["call_id"] == request_event.data["call_id"]
    assert adapter._pending_elicitations == {}
    assert sent == []


@pytest.mark.asyncio
async def test_server_request_resolved_for_unknown_id_is_ignored():
    adapter, _ = _adapter()
    await adapter._dispatch({
        "method": "serverRequest/resolved",
        "params": {"threadId": "th", "requestId": 999},
    })
    assert await _drain(adapter) == []


# --- I-004: turn/steer ---------------------------------------------------


@pytest.mark.asyncio
async def test_steer_sends_the_active_turn_id():
    adapter, _ = _adapter()
    adapter._thread_id = "th_1"
    adapter._ready = True
    adapter._turn_id = "tu_9"
    # Stub the RPC round-trip: the real one would block until codex replies.
    calls: list[dict] = []

    async def fake_request(method, params, timeout=60.0):
        calls.append({"method": method, "params": params})
        return {}

    adapter._request = fake_request  # type: ignore[method-assign]

    await adapter.handle({
        "type": "turn-steer",
        "input": "also update the README",
        "client_message_id": "msg-abc",
    })

    (request,) = calls
    assert request["method"] == "turn/steer"
    assert request["params"] == {
        "threadId": "th_1",
        "expectedTurnId": "tu_9",
        "input": [{"type": "text", "text": "also update the README",
                   "text_elements": []}],
        "clientUserMessageId": "msg-abc",
    }
    # No turn-completed: the turn is still running.
    assert await _drain(adapter) == []


@pytest.mark.asyncio
async def test_steer_without_a_running_turn_is_refused():
    adapter, sent = _adapter()
    adapter._thread_id = "th_1"
    adapter._ready = True
    adapter._turn_id = None

    await adapter.handle({"type": "turn-steer", "input": "hi"})

    assert sent == []
    (event,) = await _drain(adapter)
    assert event.kind == "steer-failed"
    assert "no turn" in event.data["error"]


@pytest.mark.asyncio
async def test_steer_rejection_from_codex_does_not_end_the_turn():
    adapter, _ = _adapter()
    adapter._thread_id = "th_1"
    adapter._ready = True
    adapter._turn_id = "tu_9"

    async def boom(method, params, timeout=60.0):
        raise RuntimeError("-32602: expectedTurnId does not match")

    adapter._request = boom  # type: ignore[method-assign]
    await adapter.handle({"type": "turn-steer", "input": "hi"})

    (event,) = await _drain(adapter)
    assert event.kind == "steer-failed"
    assert "expectedTurnId" in event.data["error"]
    assert adapter._turn_id == "tu_9"


@pytest.mark.asyncio
async def test_generic_delta_suffix_still_works():
    adapter, _ = _adapter()

    await adapter._dispatch({
        "method": "item/agentMessage/delta",
        "params": {"threadId": "th_1", "itemId": "it_2", "delta": "hi"},
    })

    (event,) = await _drain(adapter)
    assert event.data["item_type"] == "agent_message"
    assert event.data["delta"] == "hi"


# --- 2. unsupported server requests must not kill the turn -----------------


@pytest.mark.asyncio
async def test_unsupported_server_request_does_not_fail_the_turn():
    adapter, sent = _adapter()
    adapter._turn_id = "tu_1"

    await adapter._dispatch({
        "id": 7,
        "method": "item/tool/call",
        "params": {"threadId": "th_1"},
    })

    assert sent[0]["id"] == 7
    assert sent[0]["error"]["code"] == -32601
    assert await _drain(adapter) == []
    assert adapter._turn_id == "tu_1"


# --- 2b. MCP elicitation rides the user-input dialog ----------------------


@pytest.mark.asyncio
async def test_elicitation_request_becomes_user_input_and_replies_typed():
    adapter, sent = _adapter()
    adapter._turn_id = "tu_1"

    await adapter._dispatch({
        "id": 9,
        "method": "mcpServer/elicitation/request",
        "params": {
            "serverName": "deploy-bot",
            "threadId": "th_1",
            "message": "Confirm the deploy",
            "mode": "form",
            "requestedSchema": {
                "type": "object",
                "required": ["env"],
                "properties": {
                    "env": {"type": "string", "title": "Environment",
                            "enum": ["staging", "prod"]},
                    "force": {"type": "boolean", "description": "Skip checks"},
                    "replicas": {"type": "integer"},
                },
            },
        },
    })

    (event,) = await _drain(adapter)
    assert event.kind == "user-input-request"
    call_id = event.data["call_id"]
    assert event.data["turn_id"] == "tu_1"
    by_id = {q["id"]: q for q in event.data["questions"]}
    assert [o["label"] for o in by_id["env"]["options"]] == ["staging", "prod"]
    assert [o["label"] for o in by_id["force"]["options"]] == ["true", "false"]
    assert by_id["replicas"]["options"] == []
    assert "(optional)" in by_id["force"]["question"]

    # Nothing sent to codex until the user answers.
    assert sent == []

    await adapter._resolve_user_input({
        "type": "user-input-response",
        "call_id": call_id,
        "answers": {"env": ["prod"], "force": ["true"], "replicas": ["3"]},
    })

    (reply,) = sent
    assert reply["id"] == 9
    assert reply["result"] == {
        "action": "accept",
        "content": {"env": "prod", "force": True, "replicas": 3},
    }


@pytest.mark.asyncio
async def test_elicitation_dismissed_declines():
    adapter, sent = _adapter()

    await adapter._dispatch({
        "id": 11,
        "method": "mcpServer/elicitation/request",
        "params": {"serverName": "s", "threadId": "th", "message": "?",
                   "mode": "form",
                   "requestedSchema": {"properties": {"note": {"type": "string"}}}},
    })
    (event,) = await _drain(adapter)

    await adapter._resolve_user_input({
        "call_id": event.data["call_id"],
        "answers": {},
    })

    assert sent == [{"id": 11, "result": {"action": "decline"}}]


@pytest.mark.asyncio
async def test_plain_request_user_input_still_uses_answers_shape():
    """The elicitation branch must not change the requestUserInput reply."""
    adapter, sent = _adapter()

    await adapter._dispatch({
        "id": 13,
        "method": "item/tool/requestUserInput",
        "params": {
            "callId": "call_abc",
            "turnId": "tu_1",
            "questions": [{"id": "q1", "header": "h", "question": "pick"}],
        },
    })
    await adapter._resolve_user_input({"call_id": "call_abc",
                                       "answers": {"q1": ["yes"]}})

    assert sent == [{"id": 13, "result": {"answers": {"q1": {"answers": ["yes"]}}}}]


# --- elicitation helpers (pure) -------------------------------------------


def test_url_mode_asks_for_an_acknowledgement():
    questions, spec = elicitation_questions({
        "serverName": "authy", "message": "Finish login",
        "mode": "url", "url": "https://example.com/auth", "elicitationId": "e1",
    })
    assert spec == {}
    assert len(questions) == 1
    assert "https://example.com/auth" in questions[0]["question"]

    assert elicitation_result(spec, {"acknowledged": {"answers": ["Continue"]}}) == {
        "action": "accept", "content": {"acknowledged": "Continue"},
    }


def test_titled_enum_sends_the_const_not_the_title():
    questions, _ = elicitation_questions({
        "message": "m", "mode": "form",
        "requestedSchema": {"properties": {"tier": {
            "type": "string",
            "oneOf": [{"const": "t1", "title": "Basic"},
                      {"const": "t2", "title": "Pro"}],
        }}},
    })
    options = questions[0]["options"]
    assert [o["label"] for o in options] == ["t1", "t2"]
    assert [o["description"] for o in options] == ["Basic", "Pro"]


def test_enum_names_become_descriptions():
    questions, _ = elicitation_questions({
        "message": "m", "mode": "form",
        "requestedSchema": {"properties": {"r": {
            "type": "string", "enum": ["a", "b"], "enumNames": ["Alpha", "Beta"],
        }}},
    })
    assert [o["label"] for o in questions[0]["options"]] == ["a", "b"]
    assert [o["description"] for o in questions[0]["options"]] == ["Alpha", "Beta"]


def test_empty_form_falls_back_to_acknowledgement():
    questions, spec = elicitation_questions({
        "serverName": "s", "message": "just so you know", "mode": "form",
        "requestedSchema": {"properties": {}},
    })
    assert spec == {}
    assert questions[0]["id"] == "acknowledged"


def test_unparseable_number_passes_through_instead_of_raising():
    assert elicitation_result(
        {"n": {"type": "integer"}}, {"n": {"answers": ["not-a-number"]}}
    ) == {"action": "accept", "content": {"n": "not-a-number"}}


def test_array_field_keeps_every_answer():
    assert elicitation_result(
        {"tags": {"type": "array"}}, {"tags": {"answers": ["x", "y"]}}
    ) == {"action": "accept", "content": {"tags": ["x", "y"]}}
