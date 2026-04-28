"""Semantic chat search storage and embedding pipeline for the relay.

The relay still routes live Codex frames without understanding most of the
payload. This package is the narrow exception: it observes completed chat
events, stores searchable text in Postgres + pgvector, and embeds chunks with
an OpenAI-compatible embeddings API.
"""
from .config import SearchConfig
from .errors import SearchUnavailable
from .service import SearchService

__all__ = ["SearchConfig", "SearchService", "SearchUnavailable"]
