"""Codex item-type translation and field flattening."""
from __future__ import annotations

from .reasoning import _summarize_reasoning


# codex item `type` (camelCase or bare) → our relay item_type (snake_case)
_ITEM_TYPE_MAP = {
    "agentMessage": "agent_message",
    "reasoning": "agent_reasoning",         # codex emits `type: "reasoning"`
    "agentReasoning": "agent_reasoning",    # older name, keep for compat
    "commandExecution": "tool_call",
    "fileChange": "file_change",
    "userMessage": "user_message",  # echoed; we drop these client-side
}


def _snake_item_type(codex_type: str) -> str:
    return _ITEM_TYPE_MAP.get(codex_type, codex_type)


def _join_input(items) -> str:
    if not isinstance(items, list):
        return ""
    out = []
    for part in items:
        if isinstance(part, dict) and part.get("type") == "text":
            out.append(part.get("text", ""))
    return "".join(out)


def _item_extras(item: dict) -> dict:
    """Flatten the fields the web client cares about out of the item payload."""
    t = item.get("type", "")
    extras: dict = {}
    if t == "agentMessage":
        if "text" in item:
            extras["text"] = item["text"]
        if "phase" in item:
            extras["phase"] = item["phase"]
    elif t in ("reasoning", "agentReasoning"):
        # Final reasoning object has `summary: [text, ...]` and `content: []`.
        # We've been streaming summary deltas into the client already, so
        # the final text matches what the client already rendered.
        summary = item.get("summary")
        if isinstance(summary, list) and summary:
            extras["text"] = "\n\n".join(s for s in summary if isinstance(s, str))
        elif "text" in item:
            extras["text"] = item["text"]
        elif "content" in item:
            extras["text"] = _summarize_reasoning(item["content"])
    elif t == "commandExecution":
        cmd = item.get("command") or item.get("commandLine")
        if cmd:
            extras["tool"] = "shell"
            extras["args"] = {"command": cmd}
        output = item.get("output") or item.get("stdout") or item.get("aggregatedOutput")
        if output:
            extras["output"] = output
        if "exitCode" in item:
            extras["exit_code"] = item["exitCode"]
    elif t == "fileChange":
        if "changes" in item:
            extras["changes"] = item["changes"]
    return extras
