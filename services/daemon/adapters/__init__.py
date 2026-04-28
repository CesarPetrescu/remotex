"""Session adapters that produce Codex-App-Server-shaped events.

Two adapters ship:

- ``MockCodexAdapter`` — deterministic scripted events, kept for tests
  and the no-API demo. The e2e test drives this.
- ``StdioCodexAdapter`` — spawns ``codex app-server`` and bridges real
  JSON-RPC frames. This is the default daemon mode now.

Protocol crib:
  initialize ──▶ result {userAgent, codexHome, platform*}
  initialized (notification, no response)
  thread/start ──▶ result {thread: {id, ...}, model, cwd, ...}
                   + thread/started notification
  turn/start   ──▶ result (with the turn id)
                   + turn/started, item/started, item/<type>/delta,
                     item/completed, turn/completed notifications

Item types observed: ``userMessage``, ``agentMessage``, ``agentReasoning``,
``commandExecution`` (tool call). We translate these to the snake_case
item_type strings the relay + web client already use.
"""
from __future__ import annotations

from .admin import AdminCodex, admin_list_threads
from .base import SessionAdapter, SessionEvent
from .factory import build_adapter
from .mock import MockCodexAdapter
from .stdio import StdioCodexAdapter

__all__ = [
    "AdminCodex",
    "MockCodexAdapter",
    "SessionAdapter",
    "SessionEvent",
    "StdioCodexAdapter",
    "admin_list_threads",
    "build_adapter",
]
