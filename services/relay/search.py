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

    @classmethod
    def from_env(cls) -> "SearchConfig":
        return cls(
            database_url=_clean(os.getenv("SEARCH_DATABASE_URL")),
            embedding_api_base_url=_clean(os.getenv("EMBEDDING_API_BASE_URL")),
            embedding_api_key=_clean(os.getenv("EMBEDDING_API_KEY")),
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
        )

    @property
    def embedding_configured(self) -> bool:
        return bool(self.embedding_api_base_url and self.embedding_api_key)


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


class SearchUnavailable(RuntimeError):
    """Raised when search is called without storage or embedding config."""


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

        if self.config.embedding_configured:
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
    ) -> list[dict[str, Any]]:
        if not self.enabled:
            raise SearchUnavailable("semantic search is not configured")
        cleaned = query.strip()
        if not cleaned:
            return []
        limit = max(1, min(50, limit))
        vector = await self._embed_texts([cleaned])
        vector_literal = _vector_literal(vector[0])

        params: list[Any] = [vector_literal, owner_token]
        clauses = ["owner_token = $2", "embedding IS NOT NULL"]
        if host_id:
            params.append(host_id)
            clauses.append(f"host_id = ${len(params)}")
        params.append(limit)
        limit_ref = f"${len(params)}"

        sql = f"""
            SELECT
              id,
              host_id,
              session_id,
              thread_id,
              turn_id,
              chunk_kind,
              role,
              snippet,
              text,
              cwd,
              model,
              created_at,
              1 - (embedding <=> $1::vector) AS score
            FROM search_chunks
            WHERE {" AND ".join(clauses)}
            ORDER BY embedding <=> $1::vector
            LIMIT {limit_ref}
        """
        async with self._pool.acquire() as conn:
            rows = await conn.fetch(sql, *params)
        return [
            {
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
                "score": float(row["score"]),
            }
            for row in rows
        ]

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
                  opened_at   BIGINT NOT NULL,
                  updated_at  BIGINT NOT NULL,
                  closed_at   BIGINT
                )
                """
            )
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
                "CREATE INDEX IF NOT EXISTS search_chunks_pending_idx "
                "ON search_chunks(created_at) WHERE embedding IS NULL AND attempts < 5"
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
        elif event_kind == "history-end":
            await self._build_unindexed_session(str(session_id))

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

            user_text = turn["user_text"] or _first_text(items, "user_message")
            agent_text = "\n\n".join(row["text"] for row in items if row["item_type"] == "agent_message")
            reasoning = [row["text"] for row in items if row["item_type"] == "agent_reasoning"]

            chunks: list[tuple[str, str, str]] = []
            exchange_parts = []
            if user_text:
                exchange_parts.append(f"User:\n{user_text}")
            if agent_text:
                exchange_parts.append(f"Codex:\n{agent_text}")
            if exchange_parts:
                for part in self._split_text("\n\n".join(exchange_parts)):
                    chunks.append(("turn_exchange", "exchange", part))
            for text in reasoning:
                for part in self._split_text(f"Reasoning:\n{text}"):
                    chunks.append(("reasoning", "reasoning", part))

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

    def _split_text(self, text: str) -> list[str]:
        cleaned = _normalize_space(text)
        if not cleaned:
            return []

        max_chars = max(2000, self.config.embedding_max_context_tokens * 4)
        if (
            _estimate_tokens(cleaned) <= self.config.embedding_max_context_tokens
            and len(cleaned) <= max_chars
        ):
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
        payload = {
            "model": self.config.embedding_model,
            "input": texts,
            "dimensions": self.config.embedding_dimensions,
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


def _first_text(rows: list[Any], item_type: str) -> str | None:
    for row in rows:
        if row["item_type"] == item_type and row["text"]:
            return row["text"]
    return None


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


def _vector_literal(vector: list[float]) -> str:
    return "[" + ",".join(f"{value:.8g}" for value in vector) + "]"


def _embedding_url(base_url: str) -> str:
    base = base_url.rstrip("/")
    if base.endswith("/embeddings"):
        return base
    return f"{base}/embeddings"
