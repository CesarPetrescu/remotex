"""Adapter factory: pick mock vs stdio vs orchestrator by daemon config + frame."""
from __future__ import annotations

from .base import SessionAdapter
from .mock import MockCodexAdapter
from .stdio import StdioCodexAdapter


def build_adapter(
    mode: str,
    codex_binary: str,
    default_cwd: str = "",
    resume_thread_id: str | None = None,
    *,
    kind: str = "codex",
    task: str | None = None,
    approval_policy: str | None = None,
    permissions: str | None = None,
    model: str | None = None,
    effort: str | None = None,
) -> SessionAdapter:
    if kind == "orchestrator":
        # Imported lazily so the daemon still boots cleanly even if the
        # orchestrator package isn't yet importable (rare, but keeps the
        # blast radius of orchestrator changes contained).
        from ..orchestrator.adapter import OrchestratorAdapter
        return OrchestratorAdapter(
            codex_binary=codex_binary,
            cwd=default_cwd or "",
            task=task or "",
            approval_policy=approval_policy,
            permissions=permissions,
            model=model,
            effort=effort,
        )

    mode = (mode or "stdio").lower()
    if mode == "mock":
        return MockCodexAdapter()
    if mode == "stdio":
        return StdioCodexAdapter(
            codex_binary=codex_binary,
            default_cwd=default_cwd or None,
            resume_thread_id=resume_thread_id,
            # Plain coder sessions tell the client "kind=codex" via
            # session-started so the chip stops echoing the user's
            # preferred-kind chip when they're actually in a coder.
            session_kind=kind,
        )
    raise ValueError(f"unknown adapter mode: {mode!r}")
