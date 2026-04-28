"""Adapter factory: pick mock vs stdio by daemon config mode."""
from __future__ import annotations

from .base import SessionAdapter
from .mock import MockCodexAdapter
from .stdio import StdioCodexAdapter


def build_adapter(
    mode: str,
    codex_binary: str,
    default_cwd: str = "",
    resume_thread_id: str | None = None,
) -> SessionAdapter:
    mode = (mode or "stdio").lower()
    if mode == "mock":
        return MockCodexAdapter()
    if mode == "stdio":
        return StdioCodexAdapter(
            codex_binary=codex_binary,
            default_cwd=default_cwd or None,
            resume_thread_id=resume_thread_id,
        )
    raise ValueError(f"unknown adapter mode: {mode!r}")
