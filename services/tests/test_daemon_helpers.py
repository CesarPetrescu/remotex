"""Pure helpers in the daemon adapter package."""
from __future__ import annotations

from daemon.adapters.items import _item_extras, _join_input, _snake_item_type
from daemon.adapters.permissions import _image_suffix, _permissions_to_codex
from daemon.adapters.reasoning import _summarize_reasoning


def test_snake_item_type_translates_known_codex_names():
    assert _snake_item_type("agentMessage") == "agent_message"
    assert _snake_item_type("reasoning") == "agent_reasoning"
    assert _snake_item_type("commandExecution") == "tool_call"
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


def test_permissions_full_returns_danger_full_access():
    sandbox, approval = _permissions_to_codex("full", "/cwd")
    assert sandbox == {"type": "dangerFullAccess"}
    assert approval == "never"


def test_permissions_default_writes_inside_cwd():
    sandbox, approval = _permissions_to_codex("default", "/work")
    assert sandbox["type"] == "workspaceWrite"
    assert sandbox["writableRoots"] == ["/work"]
    assert approval == "on-request"


def test_image_suffix_falls_back_to_png():
    assert _image_suffix(None) == ".png"
    assert _image_suffix("image/jpeg") == ".jpg"
    assert _image_suffix("application/pdf") == ".png"


def test_summarize_reasoning_joins_strings_and_dicts():
    assert _summarize_reasoning(["a", {"text": "b"}, {"summary": "c"}]) == "a b c"
    assert _summarize_reasoning("plain") == "plain"
    assert _summarize_reasoning(42) == ""
