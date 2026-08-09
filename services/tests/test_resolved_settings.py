"""Codex's resolved thread settings -> UI-shaped values.

Both frames below are verbatim from probing a real `codex app-server` 0.147.0
(initialize -> initialized -> thread/start, then thread/settings/update). The
two sources spell the same things differently, which is the whole reason this
mapping exists:

    thread/start            reasoningEffort   sandbox
    thread/settings/updated effort            sandboxPolicy
"""
from __future__ import annotations

from daemon.adapters.permissions import (
    _permissions_from_codex,
    _permissions_to_codex,
    resolved_settings,
)

# Real thread/start response (trimmed to the fields we read).
THREAD_START = {
    "model": "gpt-5.6-sol",
    "modelProvider": "openai",
    "serviceTier": "default",
    "reasoningEffort": "high",
    "approvalPolicy": "never",
    "approvalsReviewer": "user",
    "sandbox": {"type": "dangerFullAccess"},
    "activePermissionProfile": None,
    "thread": {"id": "019fe7d1-73e8-71e1-a835-f796c3da5e29"},
}

# Real thread/settings/updated -> threadSettings payload.
THREAD_SETTINGS = {
    "cwd": "/tmp",
    "approvalPolicy": "never",
    "approvalsReviewer": "user",
    "sandboxPolicy": {"type": "dangerFullAccess"},
    "activePermissionProfile": None,
    "model": "gpt-5.6-sol",
    "modelProvider": "openai",
    "serviceTier": "default",
    "effort": "low",
    "summary": None,
    "collaborationMode": {"mode": "default"},
    "multiAgentMode": "explicitRequestOnly",
    "personality": "pragmatic",
}


def test_thread_start_shape():
    assert resolved_settings(THREAD_START) == {
        "model": "gpt-5.6-sol",
        "effort": "high",
        "permissions": "full",
        "approval_policy": "never",
    }


def test_settings_updated_shape_uses_the_other_key_names():
    got = resolved_settings(THREAD_SETTINGS)
    assert got["effort"] == "low"          # `effort`, not `reasoningEffort`
    assert got["permissions"] == "full"    # `sandboxPolicy`, not `sandbox`
    assert got["model"] == "gpt-5.6-sol"


def test_every_ui_permission_round_trips():
    # Whatever we send codex must map back to the button that produced it,
    # or the composer will show one thing and codex will do another.
    for perms in ("default", "full", "readonly"):
        sandbox, _policy = _permissions_to_codex(perms, "/work")
        assert _permissions_from_codex(sandbox) == perms


def test_unknown_policy_is_unknown_not_default():
    # The bug this guards: reporting "default" for a policy we cannot name
    # would tell the user they are sandboxed when they are not.
    assert _permissions_from_codex({"type": "someFuturePolicy"}) is None
    assert _permissions_from_codex(None) is None
    assert "permissions" not in resolved_settings({"sandbox": {"type": "wat"}})


def test_absent_fields_are_omitted_entirely():
    assert resolved_settings({}) == {}
    assert resolved_settings(None) == {}
    # A blank model must not overwrite a known one downstream.
    assert resolved_settings({"model": "   "}) == {}


def test_permission_profile_is_carried_when_named():
    got = resolved_settings({**THREAD_START, "activePermissionProfile": {"id": "trusted"}})
    assert got["permission_profile"] == "trusted"
