"""Codex-native collaboration settings for orchestrator-owned sessions."""
from __future__ import annotations

import copy


COLLABORATION_MODE = "default"
AGENT_MAX_DEPTH = 2
AGENT_MAX_THREADS = 6


def native_subagent_thread_config() -> dict:
    """thread/start.config overrides that enable stable Codex subagents."""
    return {
        "features": {
            "multi_agent": True,
        },
        "agents": {
            "max_depth": AGENT_MAX_DEPTH,
            "max_threads": AGENT_MAX_THREADS,
        },
    }


def merge_thread_config(*configs: dict | None) -> dict:
    """Deep-merge small thread/start config fragments."""
    merged: dict = {}
    for config in configs:
        if not config:
            continue
        _deep_merge(merged, copy.deepcopy(config))
    return merged


def _deep_merge(dst: dict, src: dict) -> None:
    for key, value in src.items():
        current = dst.get(key)
        if isinstance(current, dict) and isinstance(value, dict):
            _deep_merge(current, value)
        else:
            dst[key] = value
