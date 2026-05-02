"""Unit tests for the @@ORCH command parser and DAG cycle detector."""
from __future__ import annotations

from daemon.orchestrator.protocol import parse_commands
from daemon.orchestrator.runtime import _has_cycle, _Step


def test_parse_commands_pulls_actions_in_order():
    text = (
        "I'll plan first.\n"
        "@@ORCH submit_plan {\"steps\": [{\"step_id\": \"s1\", \"title\": \"x\", \"prompt\": \"p\"}]}\n"
        "Now spawning.\n"
        "@@ORCH spawn_step {\"step_id\": \"s1\"}\n"
        "@@ORCH await_steps {\"step_ids\": [\"s1\"]}\n"
    )
    cmds, errors = parse_commands(text)
    assert errors == []
    assert [c.action for c in cmds] == ["submit_plan", "spawn_step", "await_steps"]
    assert cmds[1].args == {"step_id": "s1"}


def test_parse_commands_records_json_errors_and_keeps_other_lines():
    text = (
        "@@ORCH submit_plan {bad json\n"
        "@@ORCH finish {\"summary\": \"ok\"}\n"
    )
    cmds, errors = parse_commands(text)
    assert len(cmds) == 1
    assert cmds[0].action == "finish"
    assert any("submit_plan" in e for e in errors)


def test_parse_commands_ignores_unknown_lines():
    cmds, errors = parse_commands("just narration without any commands")
    assert cmds == []
    assert errors == []


def test_dag_cycle_detection():
    a = _Step(step_id="a", title="A", prompt="p", deps=["b"])
    b = _Step(step_id="b", title="B", prompt="p", deps=["a"])
    assert _has_cycle([a, b]) is True

    a2 = _Step(step_id="a", title="A", prompt="p", deps=[])
    b2 = _Step(step_id="b", title="B", prompt="p", deps=["a"])
    c2 = _Step(step_id="c", title="C", prompt="p", deps=["a", "b"])
    assert _has_cycle([a2, b2, c2]) is False
