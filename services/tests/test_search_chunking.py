"""Chunk extraction from a turn's items."""
from __future__ import annotations

from relay.search.chunking import _chunks_from_items


def _identity_split(text: str) -> list[str]:
    return [text]


def test_user_acks_are_dropped():
    chunks = _chunks_from_items(
        items=[{"item_type": "user_message", "text": "ok"}],
        turn_user_text=None,
        split=_identity_split,
        min_tokens=1,
    )
    assert chunks == []


def test_user_message_is_emitted_with_prefix():
    chunks = _chunks_from_items(
        items=[
            {
                "item_type": "user_message",
                "text": "Refactor the relay store to use asyncpg",
            }
        ],
        turn_user_text=None,
        split=_identity_split,
        min_tokens=1,
    )
    assert len(chunks) == 1
    kind, role, text = chunks[0]
    assert kind == "user_message"
    assert role == "user"
    assert text.startswith("User: ")


def test_synthesizes_user_chunk_from_turn_user_text_when_missing():
    chunks = _chunks_from_items(
        items=[
            {"item_type": "agent_message", "text": "I will look into the routing layer."}
        ],
        turn_user_text="please investigate slow turns",
        split=_identity_split,
        min_tokens=1,
    )
    roles = [role for _, role, _ in chunks]
    assert "user" in roles
    assert "assistant" in roles


def test_short_text_below_min_tokens_is_dropped():
    chunks = _chunks_from_items(
        items=[{"item_type": "agent_message", "text": "hi"}],
        turn_user_text=None,
        split=_identity_split,
        min_tokens=100,
    )
    assert chunks == []
