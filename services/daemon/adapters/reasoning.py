"""Reasoning content summarization."""
from __future__ import annotations


def _summarize_reasoning(content) -> str:
    if isinstance(content, list):
        parts = []
        for c in content:
            if isinstance(c, dict):
                parts.append(c.get("text") or c.get("summary") or "")
            elif isinstance(c, str):
                parts.append(c)
        return " ".join(p for p in parts if p)
    if isinstance(content, str):
        return content
    return ""
