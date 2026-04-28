"""Per-message chunking from a turn's items."""
from __future__ import annotations

from typing import Any

from .queries import _field
from .text import _estimate_tokens, _is_stoplist, _normalize_space


_ITEM_TO_CHUNK: dict[str, tuple[str, str, str]] = {
    # item_type -> (chunk_kind, role, prefix)
    "user_message": ("user_message", "user", "User: "),
    "agent_message": ("agent_message", "assistant", "Codex: "),
    "agent_reasoning": ("reasoning", "reasoning", "Reasoning: "),
}


def _chunks_from_items(
    *,
    items: list[Any],
    turn_user_text: str | None,
    split,
    min_tokens: int,
) -> list[tuple[str, str, str]]:
    """Build per-message chunks from a turn's items.

    Each user/agent/reasoning item becomes its own chunk (or several, if long).
    Short user acks and filler commands are dropped via the stoplist so they
    don't pollute nearest-neighbor results. If the turn has no captured user
    message item but `search_turns.user_text` is set (from turn/start), that
    text is synthesized into a user chunk.
    """
    out: list[tuple[str, str, str]] = []
    saw_user = False
    for row in items:
        item_type = _field(row, "item_type")
        text = _field(row, "text") or ""
        if item_type == "user_message":
            saw_user = True
        mapping = _ITEM_TO_CHUNK.get(item_type)
        if not mapping:
            continue
        chunk_kind, role, prefix = mapping
        out.extend(_emit_chunks(chunk_kind, role, prefix, text, split, min_tokens))

    if not saw_user and turn_user_text:
        chunk_kind, role, prefix = _ITEM_TO_CHUNK["user_message"]
        out.extend(_emit_chunks(chunk_kind, role, prefix, turn_user_text, split, min_tokens))
    return out


def _emit_chunks(
    chunk_kind: str,
    role: str,
    prefix: str,
    text: str,
    split,
    min_tokens: int,
) -> list[tuple[str, str, str]]:
    if not text:
        return []
    cleaned = _normalize_space(text)
    if not cleaned:
        return []
    if role == "user" and _is_stoplist(cleaned):
        return []
    if _estimate_tokens(cleaned) < min_tokens:
        return []
    pieces = split(f"{prefix}{cleaned}") or [f"{prefix}{cleaned}"]
    return [(chunk_kind, role, piece) for piece in pieces if piece]
