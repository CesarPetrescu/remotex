"""Semantic chat search storage and embedding pipeline for the relay.

The relay still routes live Codex frames without understanding most of the
payload. This module is the narrow exception: it observes completed chat
events, stores searchable text in Postgres + pgvector, and embeds chunks with
an OpenAI-compatible embeddings API.
"""
from __future__ import annotations

import asyncio
import contextlib
import logging
import os
import re
import time
import uuid
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from typing import Any

from aiohttp import ClientSession, ClientTimeout

log = logging.getLogger("relay.search")


def _now() -> int:
    return int(time.time())


def _new_id(prefix: str) -> str:
    return f"{prefix}_{uuid.uuid4().hex[:16]}"


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
                12000,
                minimum=512,
                maximum=30000,
            ),
            title_max_attempts=_int_env("CHAT_TITLE_MAX_ATTEMPTS", 8, minimum=1, maximum=50),
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


class SearchUnavailable(RuntimeError):
    """Raised when search is called without storage or embedding config."""


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


class SearchService:
    def __init__(self, config: SearchConfig):
        self.config = config
        self._asyncpg: Any = None
        self._pool: Any = None
        self._http: ClientSession | None = None
        self._event_queue: asyncio.Queue[dict[str, Any] | None] = asyncio.Queue(maxsize=2000)
        self._event_task: asyncio.Task | None = None
        self._embed_task: asyncio.Task | None = None
        self._embed_wake = asyncio.Event()
        self._title_queue: asyncio.Queue[str | None] = asyncio.Queue(maxsize=1000)
        self._title_task: asyncio.Task | None = None
        self._title_inflight: set[str] = set()
        self._pending_inputs: dict[str, list[str]] = {}
        self._latest_turn: dict[str, str] = {}

    @property
    def storage_enabled(self) -> bool:
        return self._pool is not None

    @property
    def enabled(self) -> bool:
        return self.storage_enabled and self.config.embedding_configured and self._http is not None

    async def start(self) -> None:
        if not self.config.database_url:
            log.info("semantic search disabled: SEARCH_DATABASE_URL is not set")
            return
        try:
            import asyncpg  # type: ignore[import-not-found]
        except ImportError:
            log.warning("semantic search disabled: asyncpg is not installed")
            return

        self._asyncpg = asyncpg
        self._pool = await asyncpg.create_pool(
            dsn=self.config.database_url,
            min_size=1,
            max_size=5,
            command_timeout=60,
        )
        await self._init_schema()
        self._event_task = asyncio.create_task(self._event_loop(), name="search-events")

        if (
            self.config.embedding_configured
            or self.config.reranker_configured
            or self.config.main_model_configured
        ):
            self._http = ClientSession(timeout=ClientTimeout(total=90))

        if self.config.main_model_configured:
            self._title_task = asyncio.create_task(self._title_loop(), name="chat-titles")

        if self.config.embedding_configured:
            if self._http is None:
                self._http = ClientSession(timeout=ClientTimeout(total=90))
            self._embed_task = asyncio.create_task(self._embedding_loop(), name="search-embeddings")
            self._embed_wake.set()
        else:
            log.info("semantic search storage enabled, embeddings disabled: missing API base URL or key")

    async def stop(self) -> None:
        if self._event_task:
            await self._event_queue.put(None)
            with contextlib.suppress(asyncio.CancelledError):
                await self._event_task
        if self._embed_task:
            self._embed_task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._embed_task
        if self._title_task:
            await self._title_queue.put(None)
            with contextlib.suppress(asyncio.CancelledError):
                await self._title_task
        if self._http:
            await self._http.close()
        if self._pool:
            await self._pool.close()

    def config_payload(self) -> dict[str, Any]:
        return {
            "storage_enabled": self.storage_enabled,
            "enabled": self.enabled,
            "api_base_url": self.config.embedding_api_base_url,
            "api_key_configured": bool(self.config.embedding_api_key),
            "model": self.config.embedding_model,
            "dimensions": self.config.embedding_dimensions,
            "max_context_tokens": self.config.embedding_max_context_tokens,
            "batch_size": self.config.embedding_batch_size,
            "reranker_enabled": (
                self.config.reranker_configured and self._http is not None
            ),
            "reranker_model": self.config.reranker_model,
            "reranker_pool": self.config.reranker_candidate_pool,
            "reranker_top_n": self.config.reranker_top_n,
            "title_generation_enabled": (
                self.config.main_model_configured and self._http is not None
            ),
            "title_model": self.config.main_model,
        }

    def capture_session_opened(self, session: dict[str, Any]) -> None:
        self._enqueue({"type": "session-opened", "session": session})

    def capture_session_closed(self, session: dict[str, Any]) -> None:
        self._enqueue({"type": "session-closed", "session": session})

    def capture_client_turn(self, session: dict[str, Any], frame: dict[str, Any]) -> None:
        self._enqueue({"type": "client-turn", "session": session, "frame": frame})

    def capture_session_event(self, session: dict[str, Any], frame: dict[str, Any]) -> None:
        self._enqueue({"type": "session-event", "session": session, "frame": frame})

    def _enqueue(self, item: dict[str, Any]) -> None:
        if not self.storage_enabled and self._event_task is None:
            return
        try:
            self._event_queue.put_nowait(item)
        except asyncio.QueueFull:
            log.warning("semantic search event queue full; dropping %s", item.get("type"))

    async def search(
        self,
        *,
        owner_token: str,
        query: str,
        limit: int = 20,
        host_id: str | None = None,
        thread_id: str | None = None,
        session_id: str | None = None,
        role: str | None = None,
        kind: str | None = None,
        mode: str = "hybrid",
        rerank: bool | None = None,
    ) -> dict[str, Any]:
        if not self.storage_enabled:
            raise SearchUnavailable("semantic search storage is not configured")
        cleaned = query.strip()
        if not cleaned:
            return {"results": [], "mode": mode, "signals": [], "reranked": False}
        limit = max(1, min(50, limit))
        mode = (mode or "hybrid").lower()
        if mode not in ("hybrid", "semantic", "bm25", "exact"):
            raise SearchUnavailable(f"unknown search mode: {mode}")
        if mode == "semantic" and not self.enabled:
            raise SearchUnavailable("semantic search is not configured (embedding API missing)")

        # Default: rerank hybrid queries if the reranker is reachable.
        # Single-signal modes don't benefit as much (the signal already
        # agrees with itself) so we keep the default off there unless the
        # caller explicitly opts in.
        rerank_requested = rerank if rerank is not None else (mode == "hybrid")
        rerank_active = rerank_requested and self.config.reranker_configured and self._http is not None

        filters = _Filters(
            owner_token=owner_token,
            host_id=host_id,
            thread_id=thread_id,
            session_id=session_id,
            role=role,
            kind=kind,
        )

        phrases = _extract_phrases(cleaned)
        # When reranking we need a bigger candidate pool than the final limit.
        pool = self.config.reranker_candidate_pool if rerank_active else limit
        fetch_limit = max(pool, limit)

        if mode == "semantic":
            hits = await self._search_semantic(cleaned, filters, fetch_limit)
            signals = ["semantic"]
        elif mode == "bm25":
            hits = await self._search_bm25(cleaned, filters, fetch_limit)
            signals = ["bm25"]
        elif mode == "exact":
            needle = phrases[0] if phrases else cleaned
            hits = await self._search_exact(needle, filters, fetch_limit)
            signals = ["exact"]
        else:
            # hybrid: run all enabled signals concurrently, fuse with RRF.
            per_signal_limit = fetch_limit * 2 if rerank_active else fetch_limit
            tasks: dict[str, Any] = {
                "bm25": self._search_bm25(cleaned, filters, per_signal_limit),
            }
            if self.enabled:
                tasks["semantic"] = self._search_semantic(cleaned, filters, per_signal_limit)
            if phrases:
                tasks["exact"] = self._search_exact(phrases[0], filters, per_signal_limit)
            names = list(tasks.keys())
            per_signal: dict[str, list[dict[str, Any]]] = {}
            for name, coro in zip(
                names, await asyncio.gather(*tasks.values(), return_exceptions=True), strict=True
            ):
                if isinstance(coro, Exception):
                    log.warning("hybrid search signal %s failed: %s", name, coro)
                    per_signal[name] = []
                else:
                    per_signal[name] = coro
            hits = _rrf_fuse(per_signal, fetch_limit, boost={"exact": 2.0})
            signals = [n for n, rs in per_signal.items() if rs]

        reranked = False
        if rerank_active and hits:
            try:
                hits = await self._rerank(cleaned, hits)
                reranked = True
            except Exception as exc:  # noqa: BLE001
                log.warning("rerank failed; falling back to RRF order: %s", exc)

        hits = hits[:limit]
        return {
            "results": hits,
            "mode": mode,
            "signals": signals,
            "reranked": reranked,
        }

    async def search_stream(
        self,
        emit,
        *,
        owner_token: str,
        query: str,
        limit: int = 20,
        host_id: str | None = None,
        thread_id: str | None = None,
        session_id: str | None = None,
        role: str | None = None,
        kind: str | None = None,
        mode: str = "hybrid",
        rerank: bool | None = None,
    ) -> None:
        """Run a search and emit NDJSON events as each stage completes.

        Event shapes:
          {"type": "signal", "name": "bm25", "results": [...], "elapsed_ms": N}
          {"type": "fused",  "results": [...], "signals": [...]}
          {"type": "rerank_start"}
          {"type": "rerank", "results": [...], "elapsed_ms": N}
          {"type": "done", "mode": "...", "signals": [...], "reranked": bool}
          {"type": "error", "message": "..."}

        The frontend maintains a single "latest results" list and replaces it
        with each new event, so the list smoothly re-orders as better signals
        and then the reranker finish.
        """
        if not self.storage_enabled:
            await emit({"type": "error", "message": "semantic search storage is not configured"})
            return
        cleaned = query.strip()
        if not cleaned:
            await emit({"type": "done", "mode": mode, "signals": [], "reranked": False})
            return
        limit = max(1, min(50, limit))
        mode = (mode or "hybrid").lower()
        if mode not in ("hybrid", "semantic", "bm25", "exact"):
            await emit({"type": "error", "message": f"unknown search mode: {mode}"})
            return
        if mode == "semantic" and not self.enabled:
            await emit({"type": "error", "message": "semantic search is not configured"})
            return

        rerank_requested = rerank if rerank is not None else (mode == "hybrid")
        rerank_active = rerank_requested and self.config.reranker_configured and self._http is not None

        filters = _Filters(
            owner_token=owner_token,
            host_id=host_id,
            thread_id=thread_id,
            session_id=session_id,
            role=role,
            kind=kind,
        )
        phrases = _extract_phrases(cleaned)

        pool = self.config.reranker_candidate_pool if rerank_active else limit
        fetch_limit = max(pool, limit)
        per_signal_limit = fetch_limit * 2 if rerank_active else fetch_limit

        # Figure out which signals will actually run for the requested mode.
        # "available" is announced up front so the UI can render the pipeline
        # with placeholders before any signal finishes.
        if mode == "semantic":
            planned = ["semantic"]
        elif mode == "bm25":
            planned = ["bm25"]
        elif mode == "exact":
            planned = ["exact"]
        else:
            planned = ["bm25"]
            if self.enabled:
                planned.append("semantic")
            if phrases:
                planned.append("exact")
        stages = list(planned)
        if rerank_active:
            stages.append("rerank")
        await emit({"type": "plan", "stages": stages})

        # Kick every signal off concurrently and yield results as each completes.
        started_at = time.monotonic()
        pending: set[asyncio.Task] = set()
        for name in planned:
            if name == "bm25":
                coro = self._search_bm25(cleaned, filters, per_signal_limit)
            elif name == "semantic":
                coro = self._search_semantic(cleaned, filters, per_signal_limit)
            else:  # exact
                coro = self._search_exact(phrases[0] if phrases else cleaned, filters, per_signal_limit)
            pending.add(asyncio.create_task(coro, name=f"search-{name}"))

        per_signal: dict[str, list[dict[str, Any]]] = {}
        while pending:
            done, pending = await asyncio.wait(pending, return_when=asyncio.FIRST_COMPLETED)
            for task in done:
                name = task.get_name().removeprefix("search-")
                try:
                    hits = task.result()
                except Exception as exc:  # noqa: BLE001
                    log.warning("search signal %s failed: %s", name, exc)
                    hits = []
                elapsed = int((time.monotonic() - started_at) * 1000)
                per_signal[name] = hits
                await emit(
                    {
                        "type": "signal",
                        "name": name,
                        "results": hits[:limit],
                        "elapsed_ms": elapsed,
                        "count": len(hits),
                    }
                )

        # Fuse once every signal has landed. Single-signal modes don't need
        # fusion but we still emit a "fused" event so the UI has a stable
        # pre-rerank snapshot to fall back to.
        if mode == "hybrid":
            fused = _rrf_fuse(per_signal, fetch_limit, boost={"exact": 2.0})
        else:
            fused = per_signal.get(planned[0], [])
        signals = [n for n, rs in per_signal.items() if rs]
        await emit(
            {
                "type": "fused",
                "results": fused[:limit],
                "signals": signals,
                "count": len(fused),
            }
        )

        reranked = False
        if rerank_active and fused:
            rerank_started = time.monotonic()
            await emit({"type": "rerank_start", "candidates": min(len(fused), self.config.reranker_candidate_pool)})
            try:
                reranked_hits = await self._rerank(cleaned, fused)
                reranked = True
                rerank_elapsed = int((time.monotonic() - rerank_started) * 1000)
                await emit(
                    {
                        "type": "rerank",
                        "results": reranked_hits[:limit],
                        "elapsed_ms": rerank_elapsed,
                    }
                )
                fused = reranked_hits
            except Exception as exc:  # noqa: BLE001
                log.warning("rerank failed; keeping fused order: %s", exc)
                await emit({"type": "rerank_error", "message": str(exc)})

        await emit(
            {
                "type": "done",
                "mode": mode,
                "signals": signals,
                "reranked": reranked,
            }
        )

    async def _rerank(
        self, query: str, hits: list[dict[str, Any]]
    ) -> list[dict[str, Any]]:
        if not self._http or not self.config.reranker_configured:
            return hits
        # Cap how many candidates we send. The reranker's cost is roughly
        # O(n * doc_length) so blowing past the configured pool size buys
        # little on chat-sized corpora.
        pool = hits[: self.config.reranker_candidate_pool]
        documents = [hit["text"] for hit in pool]
        payload = {
            "model": self.config.reranker_model,
            "query": query,
            "documents": documents,
            "top_n": min(self.config.reranker_top_n, len(documents)),
            "return_documents": False,
        }
        url = _rerank_url(self.config.reranker_api_base_url)
        headers = {
            "Authorization": f"Bearer {self.config.reranker_api_key}",
            "Content-Type": "application/json",
        }
        async with self._http.post(url, json=payload, headers=headers) as resp:
            body = await resp.json(content_type=None)
            if resp.status >= 400:
                raise RuntimeError(f"rerank API {resp.status}: {body}")
        results = body.get("results")
        if not isinstance(results, list):
            raise RuntimeError("rerank API response missing results list")

        # Map reranker scores back onto the candidate hits. Anything not
        # returned by the reranker (out of top_n) keeps RRF order at the tail.
        scored: dict[int, float] = {}
        for entry in results:
            if not isinstance(entry, dict):
                continue
            idx = entry.get("index")
            score = entry.get("relevance_score") or entry.get("score")
            if isinstance(idx, int) and isinstance(score, (int, float)):
                scored[idx] = float(score)

        reordered: list[dict[str, Any]] = []
        top_idxs = sorted(scored.keys(), key=lambda i: scored[i], reverse=True)
        for new_rank, idx in enumerate(top_idxs):
            if idx < 0 or idx >= len(pool):
                continue
            hit = dict(pool[idx])
            hit["rerank_score"] = scored[idx]
            hit["fusion_score"] = hit.get("score")
            hit["score"] = scored[idx]
            sigs = list(hit.get("signals") or ([hit["signal"]] if hit.get("signal") else []))
            if "rerank" not in sigs:
                sigs.append("rerank")
            hit["signals"] = sigs
            reordered.append(hit)
        # Append anything the reranker didn't rank at the tail, preserving fusion order.
        for idx, hit in enumerate(pool):
            if idx in scored:
                continue
            reordered.append(hit)
        return reordered

    async def _search_semantic(
        self, query: str, filters: "_Filters", limit: int
    ) -> list[dict[str, Any]]:
        if not self.enabled:
            return []
        vector = await self._embed_texts([query])
        vector_literal = _vector_literal(vector[0])
        where, params = filters.build(start=2, extra="embedding IS NOT NULL")
        params = [vector_literal] + params
        params.append(limit)
        sql = f"""
            SELECT {_CHUNK_COLUMNS},
                   1 - (embedding <=> $1::vector) AS score
            FROM search_chunks
            WHERE {where}
            ORDER BY embedding <=> $1::vector
            LIMIT ${len(params)}
        """
        async with self._pool.acquire() as conn:
            rows = await conn.fetch(sql, *params)
        return [_row_to_hit(row, "semantic") for row in rows]

    async def _search_bm25(
        self, query: str, filters: "_Filters", limit: int
    ) -> list[dict[str, Any]]:
        where, params = filters.build(start=2)
        params = [query] + params
        params.append(limit)
        # websearch_to_tsquery accepts user-friendly syntax:
        #   "exact phrase"  → phrase match
        #   -word           → negation
        #   word OR word    → disjunction
        # ts_rank_cd weights term proximity and cover density (good for chat
        # transcripts where matches should cluster) and is the closest Postgres
        # built-in to BM25 semantics.
        sql = f"""
            SELECT {_CHUNK_COLUMNS},
                   ts_rank_cd(text_tsv, q, 32) AS score,
                   ts_headline('simple', text, q,
                               'StartSel=«,StopSel=»,MaxFragments=2,'
                               'FragmentDelimiter=" … ",MaxWords=18,MinWords=6'
                   ) AS highlight
            FROM search_chunks, websearch_to_tsquery('simple', $1) AS q
            WHERE {where} AND text_tsv @@ q
            ORDER BY score DESC, created_at DESC
            LIMIT ${len(params)}
        """
        async with self._pool.acquire() as conn:
            rows = await conn.fetch(sql, *params)
        return [_row_to_hit(row, "bm25", highlight=row.get("highlight")) for row in rows]

    async def _search_exact(
        self, phrase: str, filters: "_Filters", limit: int
    ) -> list[dict[str, Any]]:
        needle = phrase.strip()
        if not needle:
            return []
        where, params = filters.build(start=2)
        pattern = "%" + needle.replace("%", r"\%").replace("_", r"\_") + "%"
        params = [pattern] + params
        params.append(limit)
        # pg_trgm's GIN index makes ILIKE fast even on millions of rows. We
        # also surface a tiny highlight by wrapping the matched substring.
        sql = f"""
            SELECT {_CHUNK_COLUMNS},
                   1.0 AS score
            FROM search_chunks
            WHERE {where} AND text ILIKE $1
            ORDER BY created_at DESC
            LIMIT ${len(params)}
        """
        async with self._pool.acquire() as conn:
            rows = await conn.fetch(sql, *params)
        highlight = _exact_highlight(needle)
        return [_row_to_hit(row, "exact", highlight=highlight(row["text"])) for row in rows]

    async def reindex(self, owner_token: str, host_id: str | None = None) -> int:
        if not self.storage_enabled:
            raise SearchUnavailable("semantic search storage is not configured")
        params: list[Any] = [owner_token]
        clauses = ["owner_token = $1"]
        if host_id:
            params.append(host_id)
            clauses.append(f"host_id = ${len(params)}")
        where_sql = " AND ".join(clauses)
        async with self._pool.acquire() as conn:
            await conn.execute(f"DELETE FROM search_chunks WHERE {where_sql}", *params)
            rows = await conn.fetch(
                f"""
                SELECT DISTINCT session_id, turn_id
                FROM search_items
                WHERE {where_sql} AND turn_id IS NOT NULL
                """,
                *params,
            )
        count = 0
        for row in rows:
            count += await self._build_turn_chunks(row["session_id"], row["turn_id"])
        return count

    async def _init_schema(self) -> None:
        dims = self.config.embedding_dimensions
        async with self._pool.acquire() as conn:
            await conn.execute("CREATE EXTENSION IF NOT EXISTS vector")
            await conn.execute(
                """
                CREATE TABLE IF NOT EXISTS search_sessions (
                  session_id  TEXT PRIMARY KEY,
                  owner_token TEXT NOT NULL,
                  host_id     TEXT NOT NULL,
                  thread_id   TEXT,
                  cwd         TEXT,
                  model       TEXT,
                  title       TEXT,
                  description TEXT,
                  title_is_generic BOOLEAN NOT NULL DEFAULT TRUE,
                  title_attempts INTEGER NOT NULL DEFAULT 0,
                  title_turn_count INTEGER NOT NULL DEFAULT 0,
                  title_generated_at BIGINT,
                  title_model TEXT,
                  title_last_error TEXT,
                  opened_at   BIGINT NOT NULL,
                  updated_at  BIGINT NOT NULL,
                  closed_at   BIGINT
                )
                """
            )
            for ddl in (
                "ALTER TABLE search_sessions ADD COLUMN IF NOT EXISTS title TEXT",
                "ALTER TABLE search_sessions ADD COLUMN IF NOT EXISTS description TEXT",
                (
                    "ALTER TABLE search_sessions ADD COLUMN IF NOT EXISTS "
                    "title_is_generic BOOLEAN NOT NULL DEFAULT TRUE"
                ),
                (
                    "ALTER TABLE search_sessions ADD COLUMN IF NOT EXISTS "
                    "title_attempts INTEGER NOT NULL DEFAULT 0"
                ),
                (
                    "ALTER TABLE search_sessions ADD COLUMN IF NOT EXISTS "
                    "title_turn_count INTEGER NOT NULL DEFAULT 0"
                ),
                "ALTER TABLE search_sessions ADD COLUMN IF NOT EXISTS title_generated_at BIGINT",
                "ALTER TABLE search_sessions ADD COLUMN IF NOT EXISTS title_model TEXT",
                "ALTER TABLE search_sessions ADD COLUMN IF NOT EXISTS title_last_error TEXT",
            ):
                await conn.execute(ddl)
            await conn.execute(
                """
                CREATE TABLE IF NOT EXISTS search_turns (
                  session_id  TEXT NOT NULL,
                  turn_id     TEXT NOT NULL,
                  owner_token TEXT NOT NULL,
                  host_id     TEXT NOT NULL,
                  user_text   TEXT,
                  started_at  BIGINT NOT NULL,
                  completed_at BIGINT,
                  PRIMARY KEY (session_id, turn_id)
                )
                """
            )
            await conn.execute(
                """
                CREATE TABLE IF NOT EXISTS search_items (
                  item_key    TEXT PRIMARY KEY,
                  session_id  TEXT NOT NULL,
                  turn_id     TEXT,
                  owner_token TEXT NOT NULL,
                  host_id     TEXT NOT NULL,
                  item_id     TEXT NOT NULL,
                  item_type   TEXT NOT NULL,
                  text        TEXT NOT NULL,
                  created_at  BIGINT NOT NULL
                )
                """
            )
            await conn.execute(
                f"""
                CREATE TABLE IF NOT EXISTS search_chunks (
                  id             TEXT PRIMARY KEY,
                  owner_token    TEXT NOT NULL,
                  host_id        TEXT NOT NULL,
                  session_id     TEXT NOT NULL,
                  thread_id      TEXT,
                  turn_id        TEXT,
                  chunk_kind     TEXT NOT NULL,
                  role           TEXT NOT NULL,
                  text           TEXT NOT NULL,
                  snippet        TEXT NOT NULL,
                  token_estimate INTEGER NOT NULL,
                  cwd            TEXT,
                  model          TEXT,
                  created_at     BIGINT NOT NULL,
                  embedded_at    BIGINT,
                  embedding      vector({dims}),
                  attempts       INTEGER NOT NULL DEFAULT 0,
                  last_error     TEXT
                )
                """
            )
            await conn.execute(
                "CREATE INDEX IF NOT EXISTS search_chunks_owner_idx "
                "ON search_chunks(owner_token, host_id, created_at DESC)"
            )
            await conn.execute(
                "CREATE INDEX IF NOT EXISTS search_sessions_thread_title_idx "
                "ON search_sessions(owner_token, host_id, thread_id, updated_at DESC)"
            )
            await conn.execute(
                "CREATE INDEX IF NOT EXISTS search_chunks_pending_idx "
                "ON search_chunks(created_at) WHERE embedding IS NULL AND attempts < 5"
            )
            # Lexical search. A generated tsvector column keeps BM25-style
            # ranking always in sync with text edits, and pg_trgm powers
            # fast case-insensitive substring lookup for exact phrases.
            await conn.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm")
            await conn.execute(
                "ALTER TABLE search_chunks "
                "ADD COLUMN IF NOT EXISTS text_tsv tsvector "
                "GENERATED ALWAYS AS (to_tsvector('simple', text)) STORED"
            )
            await conn.execute(
                "CREATE INDEX IF NOT EXISTS search_chunks_tsv_idx "
                "ON search_chunks USING GIN (text_tsv)"
            )
            await conn.execute(
                "CREATE INDEX IF NOT EXISTS search_chunks_trgm_idx "
                "ON search_chunks USING GIN (text gin_trgm_ops)"
            )

    async def _event_loop(self) -> None:
        while True:
            item = await self._event_queue.get()
            if item is None:
                return
            try:
                await self._process_event(item)
            except Exception as exc:  # noqa: BLE001
                log.exception("failed to process search event %s: %s", item.get("type"), exc)

    async def _process_event(self, item: dict[str, Any]) -> None:
        session = item.get("session") or {}
        session_id = session.get("id") or session.get("session_id")
        owner_token = session.get("owner_token")
        host_id = session.get("host_id")
        if not session_id or not owner_token or not host_id:
            return

        kind = item.get("type")
        if kind == "session-opened":
            await self._upsert_session(session)
            return
        if kind == "session-closed":
            await self._close_session(str(session_id))
            return
        if kind == "client-turn":
            frame = item.get("frame") or {}
            text = frame.get("input")
            if isinstance(text, str) and text.strip():
                self._pending_inputs.setdefault(str(session_id), []).append(text.strip())
            return
        if kind != "session-event":
            return

        frame = item.get("frame") or {}
        event = frame.get("event") or {}
        event_kind = event.get("kind")
        data = event.get("data") or {}
        if not isinstance(data, dict):
            return

        await self._upsert_session(session)
        if event_kind == "session-started":
            await self._mark_session_started(str(session_id), data)
        elif event_kind == "turn-started":
            await self._record_turn_started(str(session_id), str(owner_token), str(host_id), data)
        elif event_kind == "item-completed":
            await self._record_item_completed(str(session_id), str(owner_token), str(host_id), data)
        elif event_kind == "turn-completed":
            turn_id = data.get("turn_id") or self._latest_turn.get(str(session_id))
            if isinstance(turn_id, str) and turn_id:
                await self._mark_turn_completed(str(session_id), turn_id)
                await self._build_turn_chunks(str(session_id), turn_id)
                self._enqueue_title(str(session_id))
        elif event_kind == "history-end":
            await self._build_unindexed_session(str(session_id))
            self._enqueue_title(str(session_id))

    async def _upsert_session(self, session: dict[str, Any]) -> None:
        session_id = session.get("id") or session.get("session_id")
        opened_at = session.get("opened_at") or _now()
        async with self._pool.acquire() as conn:
            await conn.execute(
                """
                INSERT INTO search_sessions(session_id, owner_token, host_id, opened_at, updated_at)
                VALUES ($1, $2, $3, $4, $5)
                ON CONFLICT (session_id) DO UPDATE SET
                  owner_token = EXCLUDED.owner_token,
                  host_id = EXCLUDED.host_id,
                  updated_at = EXCLUDED.updated_at
                """,
                session_id,
                session["owner_token"],
                session["host_id"],
                opened_at,
                _now(),
            )

    async def _close_session(self, session_id: str) -> None:
        async with self._pool.acquire() as conn:
            await conn.execute(
                "UPDATE search_sessions SET closed_at = $2, updated_at = $2 WHERE session_id = $1",
                session_id,
                _now(),
            )

    async def _mark_session_started(self, session_id: str, data: dict[str, Any]) -> None:
        thread_id = _str_or_none(data.get("thread_id"))
        cwd = _str_or_none(data.get("cwd"))
        model = _str_or_none(data.get("model"))
        async with self._pool.acquire() as conn:
            await conn.execute(
                """
                UPDATE search_sessions
                SET thread_id = COALESCE($2, thread_id),
                    cwd = COALESCE($3, cwd),
                    model = COALESCE($4, model),
                    updated_at = $5
                WHERE session_id = $1
                """,
                session_id,
                thread_id,
                cwd,
                model,
                _now(),
            )
            await conn.execute(
                """
                UPDATE search_chunks
                SET thread_id = COALESCE(thread_id, $2),
                    cwd = COALESCE(cwd, $3),
                    model = COALESCE(model, $4)
                WHERE session_id = $1
                """,
                session_id,
                thread_id,
                cwd,
                model,
            )

    async def _record_turn_started(
        self,
        session_id: str,
        owner_token: str,
        host_id: str,
        data: dict[str, Any],
    ) -> None:
        turn_id = _str_or_none(data.get("turn_id")) or _new_id("turn")
        self._latest_turn[session_id] = turn_id
        user_text = _str_or_none(data.get("input"))
        if not user_text:
            pending = self._pending_inputs.get(session_id) or []
            user_text = pending.pop(0) if pending else None
        async with self._pool.acquire() as conn:
            await conn.execute(
                """
                INSERT INTO search_turns(session_id, turn_id, owner_token, host_id, user_text, started_at)
                VALUES ($1, $2, $3, $4, $5, $6)
                ON CONFLICT (session_id, turn_id) DO UPDATE SET
                  user_text = COALESCE(EXCLUDED.user_text, search_turns.user_text),
                  started_at = LEAST(search_turns.started_at, EXCLUDED.started_at)
                """,
                session_id,
                turn_id,
                owner_token,
                host_id,
                user_text,
                _now(),
            )

    async def _record_item_completed(
        self,
        session_id: str,
        owner_token: str,
        host_id: str,
        data: dict[str, Any],
    ) -> None:
        item_type = _str_or_none(data.get("item_type"))
        if item_type not in {"user_message", "agent_message", "agent_reasoning"}:
            return
        text = _str_or_none(data.get("text")) or _str_or_none(data.get("output"))
        if not text:
            return
        item_id = _str_or_none(data.get("item_id")) or _new_id("item")
        turn_id = _str_or_none(data.get("turn_id")) or self._latest_turn.get(session_id)
        if not turn_id:
            turn_id = f"turn_for_{item_id}"
        self._latest_turn[session_id] = turn_id
        async with self._pool.acquire() as conn:
            await conn.execute(
                """
                INSERT INTO search_turns(session_id, turn_id, owner_token, host_id, started_at)
                VALUES ($1, $2, $3, $4, $5)
                ON CONFLICT (session_id, turn_id) DO NOTHING
                """,
                session_id,
                turn_id,
                owner_token,
                host_id,
                _now(),
            )
            await conn.execute(
                """
                INSERT INTO search_items(
                  item_key, session_id, turn_id, owner_token, host_id, item_id, item_type, text, created_at
                )
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
                ON CONFLICT (item_key) DO UPDATE SET
                  turn_id = EXCLUDED.turn_id,
                  item_type = EXCLUDED.item_type,
                  text = EXCLUDED.text,
                  created_at = EXCLUDED.created_at
                """,
                f"{session_id}:{item_id}",
                session_id,
                turn_id,
                owner_token,
                host_id,
                item_id,
                item_type,
                text,
                _now(),
            )
            if item_type == "user_message":
                await conn.execute(
                    """
                    UPDATE search_turns
                    SET user_text = COALESCE(user_text, $3)
                    WHERE session_id = $1 AND turn_id = $2
                    """,
                    session_id,
                    turn_id,
                    text,
                )

    async def _mark_turn_completed(self, session_id: str, turn_id: str) -> None:
        async with self._pool.acquire() as conn:
            await conn.execute(
                """
                UPDATE search_turns
                SET completed_at = $3
                WHERE session_id = $1 AND turn_id = $2
                """,
                session_id,
                turn_id,
                _now(),
            )

    async def _build_unindexed_session(self, session_id: str) -> None:
        async with self._pool.acquire() as conn:
            rows = await conn.fetch(
                """
                SELECT DISTINCT i.turn_id
                FROM search_items i
                LEFT JOIN search_chunks c
                  ON c.session_id = i.session_id AND c.turn_id = i.turn_id
                WHERE i.session_id = $1 AND i.turn_id IS NOT NULL AND c.id IS NULL
                """,
                session_id,
            )
        for row in rows:
            await self._build_turn_chunks(session_id, row["turn_id"])

    async def _build_turn_chunks(self, session_id: str, turn_id: str) -> int:
        async with self._pool.acquire() as conn:
            session = await conn.fetchrow(
                "SELECT * FROM search_sessions WHERE session_id = $1",
                session_id,
            )
            turn = await conn.fetchrow(
                "SELECT * FROM search_turns WHERE session_id = $1 AND turn_id = $2",
                session_id,
                turn_id,
            )
            items = await conn.fetch(
                """
                SELECT item_type, text, created_at
                FROM search_items
                WHERE session_id = $1 AND turn_id = $2
                ORDER BY created_at ASC
                """,
                session_id,
                turn_id,
            )
            if not session or not turn:
                return 0
            await conn.execute(
                "DELETE FROM search_chunks WHERE session_id = $1 AND turn_id = $2",
                session_id,
                turn_id,
            )

            chunks = _chunks_from_items(
                items=items,
                turn_user_text=turn["user_text"],
                split=self._split_text,
                min_tokens=self.config.chunk_min_tokens,
            )

            now = _now()
            for chunk_kind, role, text in chunks:
                await conn.execute(
                    """
                    INSERT INTO search_chunks(
                      id, owner_token, host_id, session_id, thread_id, turn_id,
                      chunk_kind, role, text, snippet, token_estimate, cwd, model, created_at
                    )
                    VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14)
                    """,
                    _new_id("chunk"),
                    session["owner_token"],
                    session["host_id"],
                    session_id,
                    session["thread_id"],
                    turn_id,
                    chunk_kind,
                    role,
                    text,
                    _snippet(text),
                    _estimate_tokens(text),
                    session["cwd"],
                    session["model"],
                    now,
                )
        if chunks:
            self._embed_wake.set()
        return len(chunks)

    async def thread_metadata(
        self,
        *,
        owner_token: str,
        host_id: str,
        thread_ids: list[str],
    ) -> dict[str, dict[str, Any]]:
        if not self.storage_enabled or not thread_ids:
            return {}
        async with self._pool.acquire() as conn:
            rows = await conn.fetch(
                """
                SELECT DISTINCT ON (thread_id)
                       thread_id, title, description, title_is_generic,
                       title_generated_at, title_model
                FROM search_sessions
                WHERE owner_token = $1
                  AND host_id = $2
                  AND thread_id = ANY($3::text[])
                  AND thread_id IS NOT NULL
                ORDER BY thread_id,
                         title_is_generic ASC,
                         title_generated_at DESC NULLS LAST,
                         updated_at DESC
                """,
                owner_token,
                host_id,
                thread_ids,
            )
        out: dict[str, dict[str, Any]] = {}
        for row in rows:
            title = _str_or_none(row["title"])
            description = _str_or_none(row["description"])
            if not title and not description:
                continue
            out[row["thread_id"]] = {
                "title": title,
                "description": description,
                "title_is_generic": bool(row["title_is_generic"]),
                "title_generated_at": row["title_generated_at"],
                "title_model": row["title_model"],
            }
        return out

    def _enqueue_title(self, session_id: str) -> None:
        if not (
            self.storage_enabled
            and self.config.main_model_configured
            and self._title_task is not None
        ):
            return
        if session_id in self._title_inflight:
            return
        try:
            self._title_queue.put_nowait(session_id)
            self._title_inflight.add(session_id)
        except asyncio.QueueFull:
            log.warning("chat title queue full; dropping %s", session_id)

    async def _title_loop(self) -> None:
        while True:
            session_id = await self._title_queue.get()
            if session_id is None:
                return
            try:
                await self._generate_title_for_session(session_id)
            except Exception as exc:  # noqa: BLE001
                log.warning("chat title generation failed for %s: %s", session_id, exc)
                await self._mark_title_failed(session_id, str(exc))
            finally:
                self._title_inflight.discard(session_id)

    async def _generate_title_for_session(self, session_id: str) -> None:
        if not self._http or not self.config.main_model_configured:
            return

        session, turns = await self._title_context(session_id)
        if not session or not turns:
            return
        if not bool(session["title_is_generic"]):
            return
        attempts = int(session["title_attempts"] or 0)
        if attempts >= self.config.title_max_attempts:
            return

        desired_turns = min(len(turns), attempts + 1)
        title_turn_count = int(session["title_turn_count"] or 0)
        if desired_turns <= title_turn_count and _str_or_none(session["title"]):
            return

        selected = turns[:desired_turns]
        transcript = _format_title_transcript(
            selected,
            max_tokens=min(
                self.config.title_max_input_tokens,
                max(512, self.config.main_model_context_tokens - 1200),
            ),
        )
        if not transcript:
            return

        parsed = await self._call_title_model(session, transcript)
        title = _clean_title(parsed["title"])
        description = _clean_description(parsed["description"])
        is_generic = bool(parsed["is_generic"]) or _looks_generic_title(title)
        now = _now()
        async with self._pool.acquire() as conn:
            await conn.execute(
                """
                UPDATE search_sessions
                SET title = $2,
                    description = $3,
                    title_is_generic = $4,
                    title_attempts = title_attempts + 1,
                    title_turn_count = GREATEST(title_turn_count, $5),
                    title_generated_at = $6,
                    title_model = $7,
                    title_last_error = NULL,
                    updated_at = $6
                WHERE session_id = $1
                """,
                session_id,
                title,
                description,
                is_generic,
                desired_turns,
                now,
                self.config.main_model,
            )

    async def _title_context(self, session_id: str):
        async with self._pool.acquire() as conn:
            session = await conn.fetchrow(
                "SELECT * FROM search_sessions WHERE session_id = $1",
                session_id,
            )
            if not session:
                return None, []
            turn_rows = await conn.fetch(
                """
                SELECT turn_id, user_text, started_at, completed_at
                FROM search_turns
                WHERE session_id = $1
                  AND EXISTS (
                    SELECT 1
                    FROM search_items i
                    WHERE i.session_id = search_turns.session_id
                      AND i.turn_id = search_turns.turn_id
                  )
                ORDER BY started_at ASC
                LIMIT $2
                """,
                session_id,
                self.config.title_max_attempts,
            )
            turns: list[dict[str, Any]] = []
            for turn in turn_rows:
                items = await conn.fetch(
                    """
                    SELECT item_type, text
                    FROM search_items
                    WHERE session_id = $1
                      AND turn_id = $2
                      AND item_type IN ('user_message', 'agent_message')
                    ORDER BY created_at ASC
                    """,
                    session_id,
                    turn["turn_id"],
                )
                turns.append({
                    "turn_id": turn["turn_id"],
                    "user_text": turn["user_text"],
                    "items": items,
                })
        return session, turns

    async def _call_title_model(self, session, transcript: str) -> dict[str, Any]:
        assert self._http and self.config.main_model_api_base_url and self.config.main_model_api_key
        payload: dict[str, Any] = {
            "model": self.config.main_model,
            "messages": [
                {
                    "role": "system",
                    "content": _TITLE_SYSTEM_PROMPT,
                },
                {
                    "role": "user",
                    "content": _title_user_prompt(session, transcript),
                },
            ],
            "max_tokens": 512,
            "temperature": 0.7,
            "top_p": 0.8,
            "presence_penalty": 1.5,
            "top_k": 20,
        }
        style = self.config.main_model_disable_thinking_style.lower()
        if style in {"enable_thinking", "alibaba"}:
            payload["enable_thinking"] = False
        else:
            payload["chat_template_kwargs"] = {"enable_thinking": False}
        headers = {
            "Authorization": f"Bearer {self.config.main_model_api_key}",
            "Content-Type": "application/json",
        }
        async with self._http.post(
            _chat_completion_url(self.config.main_model_api_base_url),
            json=payload,
            headers=headers,
        ) as resp:
            body = await resp.json(content_type=None)
            if resp.status >= 400:
                raise RuntimeError(f"MainModel API {resp.status}: {body}")
        choices = body.get("choices")
        if not isinstance(choices, list) or not choices:
            raise RuntimeError("MainModel response missing choices")
        message = choices[0].get("message") if isinstance(choices[0], dict) else None
        content = message.get("content") if isinstance(message, dict) else None
        if not isinstance(content, str) or not content.strip():
            raise RuntimeError("MainModel response missing message content")
        return _parse_title_xml(content)

    async def _mark_title_failed(self, session_id: str, error: str) -> None:
        if not self.storage_enabled:
            return
        async with self._pool.acquire() as conn:
            await conn.execute(
                """
                UPDATE search_sessions
                SET title_last_error = $2,
                    updated_at = $3
                WHERE session_id = $1
                """,
                session_id,
                error[:500],
                _now(),
            )

    def _split_text(self, text: str) -> list[str]:
        cleaned = _normalize_space(text)
        if not cleaned:
            return []

        # chunk_max_tokens governs vector granularity; embedding_max_context_tokens
        # is just a hard ceiling imposed by the model. Never exceed either.
        chunk_cap = min(self.config.chunk_max_tokens, self.config.embedding_max_context_tokens)
        max_chars = max(512, chunk_cap * 4)
        if _estimate_tokens(cleaned) <= chunk_cap and len(cleaned) <= max_chars:
            return [cleaned]
        overlap = min(1000, max_chars // 10)
        out: list[str] = []
        start = 0
        while start < len(cleaned):
            end = min(len(cleaned), start + max_chars)
            cut = end
            if end < len(cleaned):
                lower_bound = start + int(max_chars * 0.6)
                candidates = [
                    cleaned.rfind("\n\n", start, end),
                    cleaned.rfind("\n", start, end),
                    cleaned.rfind(". ", start, end),
                    cleaned.rfind("? ", start, end),
                    cleaned.rfind("! ", start, end),
                ]
                candidates = [pos for pos in candidates if pos > lower_bound]
                if candidates:
                    cut = max(candidates) + 1
            piece = cleaned[start:cut].strip()
            if piece:
                out.append(piece)
            if cut >= len(cleaned):
                break
            start = max(cut - overlap, start + 1)
        return out

    async def _embedding_loop(self) -> None:
        while True:
            try:
                await asyncio.wait_for(self._embed_wake.wait(), timeout=10)
            except asyncio.TimeoutError:
                pass
            self._embed_wake.clear()
            while await self._embed_once():
                await asyncio.sleep(0)

    async def _embed_once(self) -> int:
        async with self._pool.acquire() as conn:
            rows = await conn.fetch(
                """
                SELECT id, text
                FROM search_chunks
                WHERE embedding IS NULL AND attempts < 5
                ORDER BY created_at ASC
                LIMIT $1
                """,
                self.config.embedding_batch_size,
            )
        if not rows:
            return 0

        ids = [row["id"] for row in rows]
        try:
            vectors = await self._embed_texts([row["text"] for row in rows])
        except Exception as exc:  # noqa: BLE001
            await self._mark_embedding_failed(ids, str(exc))
            return 0

        async with self._pool.acquire() as conn:
            for row, vector in zip(rows, vectors, strict=True):
                if len(vector) != self.config.embedding_dimensions:
                    await conn.execute(
                        """
                        UPDATE search_chunks
                        SET attempts = attempts + 1, last_error = $2
                        WHERE id = $1
                        """,
                        row["id"],
                        (
                            f"embedding dimension {len(vector)} does not match "
                            f"{self.config.embedding_dimensions}"
                        ),
                    )
                    continue
                await conn.execute(
                    """
                    UPDATE search_chunks
                    SET embedding = $2::vector,
                        embedded_at = $3,
                        last_error = NULL
                    WHERE id = $1
                    """,
                    row["id"],
                    _vector_literal(vector),
                    _now(),
                )
        return len(rows)

    async def _mark_embedding_failed(self, ids: list[str], error: str) -> None:
        async with self._pool.acquire() as conn:
            await conn.execute(
                """
                UPDATE search_chunks
                SET attempts = attempts + 1, last_error = $2
                WHERE id = ANY($1::text[])
                """,
                ids,
                error[:500],
            )

    async def _embed_texts(self, texts: list[str]) -> list[list[float]]:
        if not self._http or not self.config.embedding_api_base_url or not self.config.embedding_api_key:
            raise SearchUnavailable("embedding API is not configured")
        url = _embedding_url(self.config.embedding_api_base_url)
        # LiteLLM / OpenAI-compat proxies for Qwen3 reject the `dimensions`
        # field (UnsupportedParamsError). The model already returns vectors
        # at its native dimension, so we don't need to negotiate it.
        payload = {
            "model": self.config.embedding_model,
            "input": texts,
        }
        headers = {
            "Authorization": f"Bearer {self.config.embedding_api_key}",
            "Content-Type": "application/json",
        }
        async with self._http.post(url, json=payload, headers=headers) as resp:
            body = await resp.json(content_type=None)
            if resp.status >= 400:
                raise RuntimeError(f"embedding API {resp.status}: {body}")
        data = body.get("data")
        if not isinstance(data, list):
            raise RuntimeError("embedding API response missing data list")
        vectors: list[list[float]] = []
        for item in data:
            embedding = item.get("embedding") if isinstance(item, dict) else None
            if not isinstance(embedding, list):
                raise RuntimeError("embedding API response missing embedding")
            vectors.append([float(x) for x in embedding])
        if len(vectors) != len(texts):
            raise RuntimeError("embedding API returned wrong vector count")
        return vectors


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


_TITLE_SYSTEM_PROMPT = """You generate compact metadata for Codex chat sessions.

Return exactly one XML function call and nothing else:

<function_calls>
  <invoke name="set_chat_metadata">
    <parameter name="title">Short specific title</parameter>
    <parameter name="description">One sentence description.</parameter>
    <parameter name="isGeneric">true</parameter>
  </invoke>
</function_calls>

Rules:
- Do not include markdown, JSON, explanations, or <think> blocks.
- Title must be 3-9 words.
- Title must name the actual task, product, file, bug, or decision when possible.
- Description must be one sentence, max 180 characters.
- Set isGeneric=true if the transcript is too vague, only a greeting, only setup,
  or the best title would be generic.
- Set isGeneric=false only when the title is specific enough that a user could
  recognize the chat later.
- Never use generic titles like: New Chat, General Help, Code Help, App Review,
  App Discussion, Greeting, Follow Up, Question, Help Request.
"""


def _title_user_prompt(session, transcript: str) -> str:
    cwd = _str_or_none(session["cwd"]) or "unknown"
    model = _str_or_none(session["model"]) or "unknown"
    thread_id = _str_or_none(session["thread_id"]) or "unknown"
    return f"""Session:
- thread_id: {thread_id}
- cwd: {cwd}
- model: {model}

Transcript:
{transcript}
"""


def _format_title_transcript(turns: list[dict[str, Any]], max_tokens: int) -> str:
    blocks: list[str] = []
    used = 0
    for idx, turn in enumerate(turns, start=1):
        turn_blocks: list[str] = [f"<turn index=\"{idx}\">"]
        saw_user = False
        for item in turn.get("items") or []:
            item_type = _field(item, "item_type")
            raw_text = _field(item, "text")
            text = _normalize_space(raw_text) if isinstance(raw_text, str) else ""
            if not text:
                continue
            if item_type == "user_message":
                saw_user = True
                role = "user"
            elif item_type == "agent_message":
                role = "assistant"
            else:
                continue
            turn_blocks.append(f"<message role=\"{role}\">{_xml_escape(_title_clip(text))}</message>")
        user_text = turn.get("user_text")
        if not saw_user and isinstance(user_text, str) and user_text.strip():
            text = _xml_escape(_title_clip(_normalize_space(user_text)))
            turn_blocks.insert(1, f"<message role=\"user\">{text}</message>")
        turn_blocks.append("</turn>")
        block = "\n".join(turn_blocks)
        tokens = _estimate_tokens(block)
        if used and used + tokens > max_tokens:
            break
        if tokens > max_tokens:
            block = _truncate_to_token_estimate(block, max_tokens)
            tokens = _estimate_tokens(block)
        blocks.append(block)
        used += tokens
        if used >= max_tokens:
            break
    return "\n\n".join(blocks).strip()


def _title_clip(text: str, limit: int = 2200) -> str:
    if len(text) <= limit:
        return text
    return text[:limit].rstrip() + f"\n[trimmed {len(text) - limit} chars]"


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


def _parse_title_xml(content: str) -> dict[str, Any]:
    cleaned = re.sub(r"<think\b[^>]*>.*?</think>", "", content, flags=re.IGNORECASE | re.DOTALL)
    match = re.search(
        r"<function_calls\b[^>]*>.*?</function_calls>",
        cleaned,
        flags=re.IGNORECASE | re.DOTALL,
    )
    if not match:
        raise RuntimeError("MainModel title response missing function_calls XML")
    try:
        root = ET.fromstring(match.group(0))
    except ET.ParseError as exc:
        raise RuntimeError(f"MainModel title XML parse failed: {exc}") from exc
    invoke = None
    for candidate in root.iter("invoke"):
        if candidate.attrib.get("name") == "set_chat_metadata":
            invoke = candidate
            break
    if invoke is None:
        raise RuntimeError("MainModel title XML missing set_chat_metadata invoke")
    params: dict[str, str] = {}
    for param in invoke.findall("parameter"):
        name = param.attrib.get("name")
        if not name:
            continue
        params[name] = "".join(param.itertext()).strip()
    title = params.get("title") or ""
    description = params.get("description") or ""
    is_generic = (params.get("isGeneric") or "true").strip().lower() not in {
        "false",
        "0",
        "no",
    }
    return {"title": title, "description": description, "is_generic": is_generic}


def _clean_title(value: str) -> str:
    title = re.sub(r"\s+", " ", (value or "").strip().strip("\"'`")).strip()
    if len(title) > 90:
        title = title[:87].rstrip() + "..."
    return title or "Untitled chat"


def _clean_description(value: str) -> str:
    description = re.sub(r"\s+", " ", (value or "").strip()).strip()
    if len(description) > 180:
        description = description[:177].rstrip() + "..."
    return description


_GENERIC_TITLES = {
    "new chat",
    "general help",
    "code help",
    "app review",
    "app discussion",
    "greeting",
    "follow up",
    "question",
    "help request",
    "untitled chat",
}


def _looks_generic_title(title: str) -> bool:
    cleaned = re.sub(r"\s+", " ", title.strip().lower())
    if not cleaned or cleaned in _GENERIC_TITLES:
        return True
    words = re.findall(r"[a-z0-9]+", cleaned)
    return len(words) < 2


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


def _field(row: Any, key: str) -> Any:
    try:
        return row[key]
    except (KeyError, TypeError):
        return getattr(row, key, None)


_CHUNK_COLUMNS = (
    "id, host_id, session_id, thread_id, turn_id, chunk_kind, role, "
    "snippet, text, cwd, model, created_at"
)


@dataclass(frozen=True)
class _Filters:
    owner_token: str
    host_id: str | None
    thread_id: str | None
    session_id: str | None
    role: str | None
    kind: str | None

    def build(self, *, start: int, extra: str | None = None) -> tuple[str, list[Any]]:
        clauses: list[str] = []
        params: list[Any] = []
        idx = start

        def add(column: str, value: Any) -> None:
            nonlocal idx
            clauses.append(f"{column} = ${idx}")
            params.append(value)
            idx += 1

        add("owner_token", self.owner_token)
        if self.host_id:
            add("host_id", self.host_id)
        if self.thread_id:
            add("thread_id", self.thread_id)
        if self.session_id:
            add("session_id", self.session_id)
        if self.role:
            add("role", self.role)
        if self.kind:
            add("chunk_kind", self.kind)
        if extra:
            clauses.append(extra)
        return " AND ".join(clauses), params


def _row_to_hit(row: Any, signal: str, *, highlight: str | None = None) -> dict[str, Any]:
    score = row["score"]
    return {
        "id": row["id"],
        "host_id": row["host_id"],
        "session_id": row["session_id"],
        "thread_id": row["thread_id"],
        "turn_id": row["turn_id"],
        "kind": row["chunk_kind"],
        "role": row["role"],
        "snippet": row["snippet"],
        "text": row["text"],
        "cwd": row["cwd"],
        "model": row["model"],
        "created_at": row["created_at"],
        "score": float(score) if score is not None else 0.0,
        "signal": signal,
        "highlight": highlight,
    }


_RRF_K = 60  # standard RRF constant; higher = flatter score distribution.


def _rrf_fuse(
    per_signal: dict[str, list[dict[str, Any]]],
    limit: int,
    *,
    boost: dict[str, float] | None = None,
) -> list[dict[str, Any]]:
    """Reciprocal Rank Fusion across ranked lists.

    For each chunk id we sum 1/(k+rank) over every signal it appears in,
    optionally scaled by a per-signal boost (so `exact` hits outrank pure
    semantic matches on the same phrase).
    """
    boost = boost or {}
    fused: dict[str, dict[str, Any]] = {}
    for signal, hits in per_signal.items():
        weight = boost.get(signal, 1.0)
        for rank, hit in enumerate(hits):
            key = hit["id"]
            contribution = weight / (_RRF_K + rank + 1)
            if key not in fused:
                record = dict(hit)
                record["score"] = contribution
                record["signals"] = [signal]
                record["signal_scores"] = {signal: hit["score"]}
                # keep whichever highlight arrives first (prefer exact > bm25)
                fused[key] = record
            else:
                record = fused[key]
                record["score"] += contribution
                if signal not in record["signals"]:
                    record["signals"].append(signal)
                record["signal_scores"][signal] = hit["score"]
                if not record.get("highlight") and hit.get("highlight"):
                    record["highlight"] = hit["highlight"]
    ranked = sorted(fused.values(), key=lambda r: r["score"], reverse=True)
    return ranked[:limit]


def _wrap(results: list[dict[str, Any]], mode: str, signals: list[str]) -> dict[str, Any]:
    return {"results": results, "mode": mode, "signals": signals}


_PHRASE_RE = re.compile(r'"([^"]{2,})"')


def _extract_phrases(query: str) -> list[str]:
    """Pull out quoted exact-match phrases from a user query."""
    return [m.group(1).strip() for m in _PHRASE_RE.finditer(query) if m.group(1).strip()]


def _exact_highlight(needle: str):
    pattern = re.compile(re.escape(needle), re.IGNORECASE)

    def render(text: str) -> str:
        m = pattern.search(text)
        if not m:
            return _snippet(text)
        start = max(0, m.start() - 60)
        end = min(len(text), m.end() + 60)
        fragment = text[start:end]
        highlighted = pattern.sub(lambda mm: f"«{mm.group(0)}»", fragment)
        prefix = "…" if start > 0 else ""
        suffix = "…" if end < len(text) else ""
        return prefix + highlighted.strip() + suffix

    return render


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
