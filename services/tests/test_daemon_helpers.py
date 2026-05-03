"""Pure helpers in the daemon adapter package."""
from __future__ import annotations

from daemon.adapters.items import _item_extras, _join_input, _snake_item_type
from daemon.adapters.permissions import (
    _approval_policy_to_codex,
    _image_suffix,
    _permissions_to_codex,
)
from daemon.adapters.reasoning import _summarize_reasoning


def test_snake_item_type_translates_known_codex_names():
    assert _snake_item_type("agentMessage") == "agent_message"
    assert _snake_item_type("reasoning") == "agent_reasoning"
    assert _snake_item_type("commandExecution") == "tool_call"
    assert _snake_item_type("mcpToolCall") == "mcp_tool_call"
    assert _snake_item_type("dynamicToolCall") == "dynamic_tool_call"
    assert _snake_item_type("collabAgentToolCall") == "collab_agent_tool_call"
    # Unknown types pass through unchanged.
    assert _snake_item_type("widgetThing") == "widgetThing"


def test_join_input_concatenates_text_parts_only():
    out = _join_input(
        [
            {"type": "text", "text": "hello "},
            {"type": "localImage", "path": "/tmp/x.png"},
            {"type": "text", "text": "world"},
        ]
    )
    assert out == "hello world"


def test_join_input_handles_non_list():
    assert _join_input(None) == ""
    assert _join_input("not a list") == ""


def test_item_extras_for_command_execution():
    extras = _item_extras({
        "type": "commandExecution",
        "command": "rg foo",
        "stdout": "hit",
        "exitCode": 0,
    })
    assert extras["tool"] == "shell"
    assert extras["args"] == {"command": "rg foo"}
    assert extras["output"] == "hit"
    assert extras["exit_code"] == 0


def test_item_extras_for_collab_agent_tool_call():
    extras = _item_extras({
        "type": "collabAgentToolCall",
        "tool": "spawnAgent",
        "status": "completed",
        "senderThreadId": "parent",
        "receiverThreadIds": ["child"],
        "prompt": "inspect auth",
        "model": "gpt-test",
        "reasoningEffort": "high",
        "agentsStates": {"child": {"status": "running"}},
    })
    assert extras == {
        "tool": "spawnAgent",
        "status": "completed",
        "sender_thread_id": "parent",
        "receiver_thread_ids": ["child"],
        "prompt": "inspect auth",
        "model": "gpt-test",
        "reasoning_effort": "high",
        "agents_states": {"child": {"status": "running"}},
    }


def test_item_extras_for_mcp_tool_call():
    extras = _item_extras({
        "type": "mcpToolCall",
        "server": "orchestrator",
        "tool": "submit_plan",
        "arguments": {"steps": [{"step_id": "a"}]},
        "status": "completed",
        "result": {"content": [{"type": "text", "text": "ok"}]},
        "error": {"message": "bad"},
        "durationMs": 42,
        "mcpAppResourceUri": "mcp://resource",
    })
    assert extras == {
        "server": "orchestrator",
        "tool": "submit_plan",
        "arguments": {"steps": [{"step_id": "a"}]},
        "status": "completed",
        "result": {"content": [{"type": "text", "text": "ok"}]},
        "error": "bad",
        "duration_ms": 42,
        "mcp_app_resource_uri": "mcp://resource",
    }


def test_item_extras_for_dynamic_tool_call():
    extras = _item_extras({
        "type": "dynamicToolCall",
        "namespace": "web",
        "tool": "search",
        "arguments": {"q": "remotex"},
        "status": "completed",
        "success": True,
        "contentItems": [{"type": "input_text", "text": "done"}],
        "durationMs": 7,
    })
    assert extras == {
        "namespace": "web",
        "tool": "search",
        "arguments": {"q": "remotex"},
        "status": "completed",
        "success": True,
        "content_items": [{"type": "input_text", "text": "done"}],
        "duration_ms": 7,
    }


def test_permissions_full_returns_danger_full_access():
    sandbox, approval = _permissions_to_codex("full", "/cwd")
    assert sandbox == {"type": "dangerFullAccess"}
    assert approval == "never"


def test_permissions_default_writes_inside_cwd():
    sandbox, approval = _permissions_to_codex("default", "/work")
    assert sandbox["type"] == "workspaceWrite"
    assert sandbox["writableRoots"] == ["/work"]
    assert approval == "on-request"


def test_approval_policy_validation_accepts_codex_values():
    assert _approval_policy_to_codex("on-failure") == "on-failure"
    assert _approval_policy_to_codex("on_request") == "on-request"
    assert _approval_policy_to_codex("never") == "never"
    assert _approval_policy_to_codex("untrusted") == "untrusted"
    assert _approval_policy_to_codex({"granular": {"sandbox_approval": True}}) == {
        "granular": {"sandbox_approval": True}
    }
    assert _approval_policy_to_codex("bogus") is None


def test_image_suffix_falls_back_to_png():
    assert _image_suffix(None) == ".png"
    assert _image_suffix("image/jpeg") == ".jpg"
    assert _image_suffix("application/pdf") == ".png"


def test_summarize_reasoning_joins_strings_and_dicts():
    assert _summarize_reasoning(["a", {"text": "b"}, {"summary": "c"}]) == "a b c"
    assert _summarize_reasoning("plain") == "plain"
    assert _summarize_reasoning(42) == ""
