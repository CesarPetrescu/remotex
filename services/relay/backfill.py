"""Backfill Codex rollout JSONL files into the search_* tables.

Live sessions flow into search via the event stream, so only history from
before search was added needs this path. Reads each rollout, derives a
synthetic session_id/thread_id from the rollout's Codex session UUID, and
writes one search_turns row per task_started+task_complete bracket and one
search_items row per user/agent message. The relay's embedding loop then
picks up the new chunks on its next wake.

Usage (from inside the relay container, which has asyncpg):

    python -m relay.backfill /rollouts --owner demo-user-token \
        --host host_f0396bf3313d4d0f

A one-shot docker run against the compose network is the easiest way:

    docker run --rm \
        --network remotex_remotex \
        -v ~/.codex/sessions:/rollouts:ro \
        -e SEARCH_DATABASE_URL=postgresql://remotex:...@postgres:5432/remotex \
        remotex/relay:local python -m relay.backfill /rollouts \
            --owner demo-user-token --host host_xxxx
"""
from __future__ import annotations

import argparse
import asyncio
import datetime as dt
import json
import logging
import os
import sys
import uuid
from dataclasses import dataclass
from pathlib import Path

import asyncpg  # type: ignore[import-not-found]

from relay.search import (
    SearchConfig,
    _chunks_from_items,
    _estimate_tokens,
    _snippet,
)

log = logging.getLogger("relay.backfill")


@dataclass
class Rollout:
    path: Path
    session_id: str
    opened_at: int
    cwd: str | None
    model: str | None
    thread_id: str
    turns: list["TurnRow"]


@dataclass
class TurnRow:
    turn_id: str
    started_at: int
    completed_at: int | None
    user_text: str | None
    items: list["ItemRow"]


@dataclass
class ItemRow:
    item_id: str
    item_type: str  # user_message | agent_message | agent_reasoning
    text: str
    created_at: int


def _iso_to_epoch(value: str | None) -> int | None:
    if not value:
        return None
    try:
        return int(dt.datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp())
    except ValueError:
        return None


def _short(prefix: str) -> str:
    return f"{prefix}_{uuid.uuid4().hex[:16]}"


def parse_rollout(path: Path) -> Rollout | None:
    meta: dict | None = None
    turn_contexts: dict[str, dict] = {}
    current_turn: TurnRow | None = None
    turns: list[TurnRow] = []

    with path.open() as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            t = row.get("type")
            payload = row.get("payload") or {}
            ts = _iso_to_epoch(row.get("timestamp")) or 0

            if t == "session_meta":
                meta = payload
                continue
            if t == "turn_context":
                turn_id = payload.get("turn_id")
                if turn_id:
                    turn_contexts[turn_id] = payload
                continue
            if t == "event_msg":
                ev = payload.get("type")
                if ev == "task_started":
                    turn_id = payload.get("turn_id") or _short("turn")
                    current_turn = TurnRow(
                        turn_id=turn_id,
                        started_at=payload.get("started_at") or ts,
                        completed_at=None,
                        user_text=None,
                        items=[],
                    )
                    turns.append(current_turn)
                elif ev == "task_complete":
                    if current_turn:
                        current_turn.completed_at = payload.get("completed_at") or ts
                        current_turn = None
                elif ev in ("user_message", "agent_message", "agent_reasoning"):
                    if current_turn is None:
                        current_turn = TurnRow(
                            turn_id=_short("turn"),
                            started_at=ts,
                            completed_at=None,
                            user_text=None,
                            items=[],
                        )
                        turns.append(current_turn)
                    text = payload.get("message") or payload.get("text")
                    if not text:
                        continue
                    text = text.strip()
                    if not text:
                        continue
                    current_turn.items.append(
                        ItemRow(
                            item_id=_short("item"),
                            item_type=ev,
                            text=text,
                            created_at=ts,
                        )
                    )
                    if ev == "user_message" and current_turn.user_text is None:
                        current_turn.user_text = text
                continue

    if not meta:
        return None

    session_uuid = meta.get("id")
    if not session_uuid:
        return None

    opened_at = _iso_to_epoch(meta.get("timestamp")) or 0

    # Prefer the most recent turn_context for cwd/model (they can change mid-session).
    cwd = meta.get("cwd")
    model = None
    for ctx in turn_contexts.values():
        if ctx.get("cwd"):
            cwd = ctx["cwd"]
        if ctx.get("model"):
            model = ctx["model"]

    # Drop empty turns (e.g. task_started with no user input captured).
    turns = [t for t in turns if t.items]
    if not turns:
        return None

    return Rollout(
        path=path,
        session_id=f"rollout_{session_uuid}",
        opened_at=opened_at,
        cwd=cwd,
        model=model,
        thread_id=session_uuid,
        turns=turns,
    )


def _make_splitter(config: SearchConfig):
    """Mirror SearchService._split_text without instantiating the service."""
    import re

    def split(text: str) -> list[str]:
        cleaned = re.sub(r"\n{3,}", "\n\n", (text or "").replace("\r\n", "\n")).strip()
        if not cleaned:
            return []
        chunk_cap = min(config.chunk_max_tokens, config.embedding_max_context_tokens)
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

    return split


async def ingest(
    conn: asyncpg.Connection,
    owner_token: str,
    host_id: str,
    roll: Rollout,
    config: SearchConfig,
    split,
) -> int:
    await conn.execute(
        """
        INSERT INTO search_sessions(session_id, owner_token, host_id, thread_id, cwd, model,
                                    opened_at, updated_at, closed_at)
        VALUES ($1, $2, $3, $4, $5, $6, $7, $7, $7)
        ON CONFLICT (session_id) DO UPDATE SET
          owner_token = EXCLUDED.owner_token,
          host_id = EXCLUDED.host_id,
          thread_id = COALESCE(EXCLUDED.thread_id, search_sessions.thread_id),
          cwd = COALESCE(EXCLUDED.cwd, search_sessions.cwd),
          model = COALESCE(EXCLUDED.model, search_sessions.model)
        """,
        roll.session_id,
        owner_token,
        host_id,
        roll.thread_id,
        roll.cwd,
        roll.model,
        roll.opened_at,
    )

    chunks_inserted = 0
    for turn in roll.turns:
        await conn.execute(
            """
            INSERT INTO search_turns(session_id, turn_id, owner_token, host_id,
                                     user_text, started_at, completed_at)
            VALUES ($1, $2, $3, $4, $5, $6, $7)
            ON CONFLICT (session_id, turn_id) DO UPDATE SET
              user_text = COALESCE(search_turns.user_text, EXCLUDED.user_text),
              completed_at = COALESCE(search_turns.completed_at, EXCLUDED.completed_at)
            """,
            roll.session_id,
            turn.turn_id,
            owner_token,
            host_id,
            turn.user_text,
            turn.started_at,
            turn.completed_at,
        )

        for item in turn.items:
            await conn.execute(
                """
                INSERT INTO search_items(item_key, session_id, turn_id, owner_token, host_id,
                                         item_id, item_type, text, created_at)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
                ON CONFLICT (item_key) DO NOTHING
                """,
                f"{roll.session_id}:{item.item_id}",
                roll.session_id,
                turn.turn_id,
                owner_token,
                host_id,
                item.item_id,
                item.item_type,
                item.text,
                item.created_at,
            )

        await conn.execute(
            "DELETE FROM search_chunks WHERE session_id = $1 AND turn_id = $2",
            roll.session_id,
            turn.turn_id,
        )
        chunks = _chunks_from_items(
            items=turn.items,
            turn_user_text=turn.user_text,
            split=split,
            min_tokens=config.chunk_min_tokens,
        )

        for chunk_kind, role, text in chunks:
            if not text:
                continue
            await conn.execute(
                """
                INSERT INTO search_chunks(
                  id, owner_token, host_id, session_id, thread_id, turn_id,
                  chunk_kind, role, text, snippet, token_estimate, cwd, model, created_at
                )
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14)
                """,
                _short("chunk"),
                owner_token,
                host_id,
                roll.session_id,
                roll.thread_id,
                turn.turn_id,
                chunk_kind,
                role,
                text,
                _snippet(text),
                _estimate_tokens(text),
                roll.cwd,
                roll.model,
                turn.started_at,
            )
            chunks_inserted += 1

    return chunks_inserted


async def run(args: argparse.Namespace) -> None:
    database_url = args.database_url or os.getenv("SEARCH_DATABASE_URL")
    if not database_url:
        raise SystemExit("SEARCH_DATABASE_URL is not set")

    root = Path(args.root)
    rollouts = sorted(root.rglob("*.jsonl"))
    log.info("found %d rollouts under %s", len(rollouts), root)

    config = SearchConfig.from_env()
    split = _make_splitter(config)
    pool = await asyncpg.create_pool(dsn=database_url, min_size=1, max_size=3)
    assert pool is not None
    total_sessions = 0
    total_turns = 0
    total_chunks = 0
    async with pool.acquire() as conn:
        for path in rollouts:
            roll = parse_rollout(path)
            if not roll:
                log.info("skip %s (no usable content)", path.name)
                continue
            chunks = await ingest(conn, args.owner, args.host, roll, config, split)
            total_sessions += 1
            total_turns += len(roll.turns)
            total_chunks += chunks
            log.info("ingested %s turns=%d chunks=%d", path.name, len(roll.turns), chunks)
    await pool.close()
    log.info("done: sessions=%d turns=%d chunks=%d", total_sessions, total_turns, total_chunks)


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s backfill %(levelname)s %(message)s",
        stream=sys.stdout,
    )
    parser = argparse.ArgumentParser()
    parser.add_argument("root", help="Codex sessions directory (e.g. /rollouts)")
    parser.add_argument("--owner", required=True, help="owner token to attach history to")
    parser.add_argument("--host", required=True, help="host_id to attach history to")
    parser.add_argument("--database-url", default=None, help="override SEARCH_DATABASE_URL")
    asyncio.run(run(parser.parse_args()))


if __name__ == "__main__":
    main()
