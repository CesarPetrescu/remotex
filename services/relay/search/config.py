"""SearchConfig dataclass + env-derived defaults."""
from __future__ import annotations

import logging
import os
from dataclasses import dataclass

log = logging.getLogger("relay.search.config")


@dataclass(frozen=True)
class SearchConfig:
    database_url: str | None
    embedding_api_base_url: str | None
    embedding_api_key: str | None
    embedding_model: str
    embedding_dimensions: int
    embedding_max_context_tokens: int
    embedding_batch_size: int
    chunk_max_tokens: int
    chunk_min_tokens: int
    reranker_api_base_url: str | None
    reranker_api_key: str | None
    reranker_model: str
    reranker_top_n: int
    reranker_candidate_pool: int
    main_model_api_base_url: str | None
    main_model_api_key: str | None
    main_model: str
    main_model_context_tokens: int
    main_model_disable_thinking_style: str
    title_generation_enabled: bool
    title_max_input_tokens: int
    title_max_attempts: int

    @classmethod
    def from_env(cls) -> "SearchConfig":
        embedding_base = _clean(os.getenv("EMBEDDING_API_BASE_URL"))
        embedding_key = _clean(os.getenv("EMBEDDING_API_KEY"))
        main_base = _clean(os.getenv("MAIN_MODEL_API_BASE_URL")) or embedding_base
        main_key = _clean(os.getenv("MAIN_MODEL_API_KEY")) or embedding_key
        return cls(
            database_url=_clean(os.getenv("SEARCH_DATABASE_URL")),
            embedding_api_base_url=embedding_base,
            embedding_api_key=embedding_key,
            embedding_model=os.getenv("EMBEDDING_MODEL", "qwen3-embedding").strip()
            or "qwen3-embedding",
            embedding_dimensions=_int_env("EMBEDDING_DIMENSIONS", 4096, minimum=32, maximum=4096),
            embedding_max_context_tokens=_int_env(
                "EMBEDDING_MAX_CONTEXT_TOKENS",
                32768,
                minimum=512,
                maximum=32768,
            ),
            embedding_batch_size=_int_env("EMBEDDING_BATCH_SIZE", 16, minimum=1, maximum=128),
            chunk_max_tokens=_int_env("SEARCH_CHUNK_MAX_TOKENS", 512, minimum=64, maximum=8192),
            chunk_min_tokens=_int_env("SEARCH_CHUNK_MIN_TOKENS", 8, minimum=1, maximum=256),
            # Reranker defaults to the same LiteLLM proxy that serves the
            # embedding model, since the same deployment usually hosts both.
            # Any of the three knobs can be overridden independently.
            reranker_api_base_url=_clean(os.getenv("RERANKER_API_BASE_URL")) or embedding_base,
            reranker_api_key=_clean(os.getenv("RERANKER_API_KEY")) or embedding_key,
            reranker_model=os.getenv("RERANKER_MODEL", "qwen3-reranker").strip()
            or "qwen3-reranker",
            # Pull this many fused candidates into the cross-encoder. 50 is
            # the IBM Blended-RAG sweet spot; going past 100 rarely changes
            # the final top-10 but doubles serving cost.
            reranker_candidate_pool=_int_env(
                "SEARCH_RERANK_POOL", 50, minimum=5, maximum=200
            ),
            # The reranker trims the pool down to this many before we
            # slice to the caller's limit. Keeps us honest about latency.
            reranker_top_n=_int_env("SEARCH_RERANK_TOP_N", 20, minimum=1, maximum=100),
            main_model_api_base_url=main_base,
            main_model_api_key=main_key,
            main_model=os.getenv("MAIN_MODEL", "MainModel").strip() or "MainModel",
            main_model_context_tokens=_int_env(
                "MAIN_MODEL_CONTEXT_TOKENS",
                16000,
                minimum=1024,
                maximum=32768,
            ),
            main_model_disable_thinking_style=(
                os.getenv("MAIN_MODEL_DISABLE_THINKING_STYLE", "chat_template_kwargs").strip()
                or "chat_template_kwargs"
            ),
            title_generation_enabled=_bool_env("CHAT_TITLE_ENABLED", True),
            title_max_input_tokens=_int_env(
                "CHAT_TITLE_MAX_INPUT_TOKENS",
                16000,
                minimum=512,
                maximum=30000,
            ),
            title_max_attempts=_int_env("CHAT_TITLE_MAX_ATTEMPTS", 3, minimum=1, maximum=1000000),
        )

    @property
    def embedding_configured(self) -> bool:
        return bool(self.embedding_api_base_url and self.embedding_api_key)

    @property
    def reranker_configured(self) -> bool:
        return bool(self.reranker_api_base_url and self.reranker_api_key)

    @property
    def main_model_configured(self) -> bool:
        return bool(
            self.title_generation_enabled
            and self.main_model_api_base_url
            and self.main_model_api_key
            and self.main_model
        )


def _clean(value: str | None) -> str | None:
    if value is None:
        return None
    stripped = value.strip()
    return stripped or None


def _int_env(name: str, default: int, *, minimum: int, maximum: int) -> int:
    raw = os.getenv(name)
    if not raw:
        return default
    try:
        value = int(raw)
    except ValueError:
        log.warning("%s=%r is not an integer; using %s", name, raw, default)
        return default
    return max(minimum, min(maximum, value))


def _bool_env(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None or raw.strip() == "":
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}
