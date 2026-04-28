"""Text manipulation helpers used across the search pipeline."""
from __future__ import annotations

import re
from typing import Any


# Short user utterances that carry no retrieval signal. Matched after
# _normalize_space + lowercase against the full message, so multi-word
# acks still need to be the whole message to be dropped.
_USER_STOPLIST: frozenset[str] = frozenset({
    "hi", "hello", "hey", "yo", "sup",
    "ok", "okay", "k", "kk", "cool", "nice", "great", "sure",
    "thanks", "ty", "thx", "thank you",
    "yes", "y", "yep", "yeah",
    "no", "n", "nope",
    "/compact", "/plan", "/default", "/collab", "/pwd",
})


def _is_stoplist(text: str) -> bool:
    return text.strip().lower() in _USER_STOPLIST


def _str_or_none(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    stripped = value.strip()
    return stripped or None


def _normalize_space(text: str) -> str:
    return re.sub(r"\n{3,}", "\n\n", text.replace("\r\n", "\n")).strip()


def _snippet(text: str) -> str:
    compact = re.sub(r"\s+", " ", text).strip()
    if len(compact) <= 320:
        return compact
    return compact[:317].rstrip() + "..."


def _estimate_tokens(text: str) -> int:
    # Qwen tokenizer is not available in the relay image. This conservative
    # estimate keeps chunks below the configured context without adding a
    # heavyweight model dependency to the API server.
    wordish = re.findall(r"\w+|[^\w\s]", text, flags=re.UNICODE)
    return max(1, int(len(wordish) * 1.3))


def _truncate_to_token_estimate(text: str, max_tokens: int) -> str:
    if _estimate_tokens(text) <= max_tokens:
        return text
    max_chars = max(256, max_tokens * 4)
    return text[:max_chars].rstrip() + "\n[trimmed for title context]"


def _xml_escape(text: str) -> str:
    return (
        text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
    )
