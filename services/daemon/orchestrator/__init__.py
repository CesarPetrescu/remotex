"""Orchestrator: a long-horizon agent that wraps a brain Codex session
and spawns short-lived child Codex sessions per planned step.

The brain runs as a normal Codex with a single MCP server registered
on its session — ``orchestrator.*`` — whose tools call back into this
package's :class:`OrchestratorRuntime` to manage the plan DAG and
child sessions. See ``adapter.py`` for the wiring.
"""
from __future__ import annotations

from .adapter import OrchestratorAdapter

__all__ = ["OrchestratorAdapter"]
