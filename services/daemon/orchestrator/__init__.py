"""Orchestrator: a long-horizon agent that wraps a brain Codex session
and spawns short-lived child Codex sessions per planned step.

The brain Codex emits structured ``@@ORCH`` commands inside its agent
messages; the daemon-side ``OrchestratorRuntime`` parses, validates, and
executes those commands by driving real :class:`StdioCodexAdapter`
instances for each child step. Child sessions run on the same daemon as
the parent.
"""
from __future__ import annotations

from .adapter import OrchestratorAdapter
from .protocol import ORCH_TAG, parse_commands

__all__ = ["OrchestratorAdapter", "ORCH_TAG", "parse_commands"]
