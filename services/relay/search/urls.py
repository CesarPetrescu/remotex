"""URL builders for OpenAI-compatible API endpoints + pgvector literal."""
from __future__ import annotations


def _vector_literal(vector: list[float]) -> str:
    return "[" + ",".join(f"{value:.8g}" for value in vector) + "]"


def _chat_completion_url(base_url: str) -> str:
    base = base_url.rstrip("/")
    if base.endswith("/chat/completions"):
        return base
    return f"{base}/chat/completions"


def _embedding_url(base_url: str) -> str:
    base = base_url.rstrip("/")
    if base.endswith("/embeddings"):
        return base
    return f"{base}/embeddings"


def _rerank_url(base_url: str) -> str:
    base = base_url.rstrip("/")
    if base.endswith("/rerank"):
        return base
    return f"{base}/rerank"
