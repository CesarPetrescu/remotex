"""Pure helpers in the daemon adapter package."""
from __future__ import annotations

import asyncio

import pytest

from daemon.adapters.admin import AdminCodex, _read_line_unbounded
from daemon.adapters.items import _item_extras, _join_input, _snake_item_type
from daemon.adapters.permissions import (
    _approval_policy_to_codex,
    _image_suffix,
    _permissions_to_codex,
)
from daemon.adapters.reasoning import _summarize_reasoning
from daemon.adapters.codex_config import ensure_codex_goals_feature_enabled
from daemon.adapters.stdio import (
    StdioCodexAdapter,
    _codex_thread_config,
    _format_goal_message,
    _normalize_goal,
    _normalize_goal_status,
)


def test_snake_item_type_translates_known_codex_names():
    assert _snake_item_type("agentMessage") == "agent_message"
    assert _snake_item_type("reasoning") == "agent_reasoning"
    assert _snake_item_type("commandExecution") == "tool_call"
    assert _snake_item_type("mcpToolCall") == "mcp_tool_call"
    assert _snake_item_type("dynamicToolCall") == "dynamic_tool_call"
    assert _snake_item_type("collabAgentToolCall") == "collab_agent_tool_call"
    # Unknown types pass through unchanged.
    assert _snake_item_type("widgetThing") == "widgetThing"


def test_thread_start_enables_codex_goals_feature():
    adapter = StdioCodexAdapter(default_cwd="/tmp")

    params = adapter._thread_start_params()

    assert params["config"]["features.goals"] is True
    assert _codex_thread_config()["features.goals"] is True


def test_codex_config_goals_feature_is_created(tmp_path):
    path = tmp_path / "config.toml"

    changed = ensure_codex_goals_feature_enabled(path)

    assert changed is True
    assert path.read_text(encoding="utf-8") == "[features]\ngoals = true\n"


def test_codex_config_goals_feature_is_added_to_existing_features(tmp_path):
    path = tmp_path / "config.toml"
    path.write_text('model = "gpt-5.5"\n\n[features]\npersonality = true\n', encoding="utf-8")

    changed = ensure_codex_goals_feature_enabled(path)

    assert changed is True
    assert "[features]\ngoals = true\npersonality = true\n" in path.read_text(encoding="utf-8")


def test_codex_config_goals_feature_flips_false_to_true(tmp_path):
    path = tmp_path / "config.toml"
    path.write_text("[features]\ngoals = false\n", encoding="utf-8")

    changed = ensure_codex_goals_feature_enabled(path)

    assert changed is True
    assert path.read_text(encoding="utf-8") == "[features]\ngoals = true\n"


def test_codex_config_goals_feature_noops_when_enabled(tmp_path):
    path = tmp_path / "config.toml"
    original = 'model = "gpt-5.5"\n\n[features]\ngoals = true\n'
    path.write_text(original, encoding="utf-8")

    changed = ensure_codex_goals_feature_enabled(path)

    assert changed is False
    assert path.read_text(encoding="utf-8") == original


@pytest.mark.asyncio
async def test_admin_read_line_unbounded_handles_large_json_frames():
    stream = asyncio.StreamReader(limit=32)
    payload = b'{"id":1,"result":"' + (b"x" * 100_000) + b'"}\n'

    stream.feed_data(payload)
    stream.feed_eof()

    assert await _read_line_unbounded(stream) == payload


@pytest.mark.asyncio
async def test_admin_codex_tears_down_after_request_timeout(monkeypatch):
    admin = AdminCodex()
    torn_down = False

    async def ensure_running():
        return None

    async def slow_request(_method, _params):
        await asyncio.sleep(10)

    async def tear_down():
        nonlocal torn_down
        torn_down = True

    monkeypatch.setattr(admin, "_ensure_running", ensure_running)
    monkeypatch.setattr(admin, "_request", slow_request)
    monkeypatch.setattr(admin, "_tear_down", tear_down)

    with pytest.raises(TimeoutError, match=r"thread/list timed out after 0.01s"):
        await admin._call("thread/list", {}, timeout=0.01)

    assert torn_down is True


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
        "server": "demo",
        "tool": "lookup",
        "arguments": {"q": "remotex"},
        "status": "completed",
        "result": {"content": [{"type": "text", "text": "ok"}]},
        "error": {"message": "bad"},
        "durationMs": 42,
        "mcpAppResourceUri": "mcp://resource",
    })
    assert extras == {
        "server": "demo",
        "tool": "lookup",
        "arguments": {"q": "remotex"},
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


def test_goal_normalization_accepts_codex_camel_case_shape():
    goal = _normalize_goal({
        "threadId": "thread-1",
        "objective": "ship native goals",
        "status": "budget_limited",
        "tokenBudget": 12000,
        "tokensUsed": "3456",
        "timeUsedSeconds": "90",
        "createdAt": 10,
        "updatedAt": 20,
    })
    assert goal == {
        "thread_id": "thread-1",
        "objective": "ship native goals",
        "status": "budgetLimited",
        "token_budget": 12000,
        "tokens_used": 3456,
        "time_used_seconds": 90,
        "created_at": 10,
        "updated_at": 20,
    }
    assert _normalize_goal_status("budget-limited") == "budgetLimited"
    assert _format_goal_message(goal) == "goal budgetLimited (3.5K / 12K) - ship native goals"
