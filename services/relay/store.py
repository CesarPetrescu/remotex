"""Inventory store: users, hosts, bridge keys, sessions in Postgres.

The relay shares the same pgvector Postgres instance that powers semantic
search. Inventory tables are prefixed with ``inventory_`` so they coexist
with the ``search_*`` tables without collision.

Migrating from the prior SQLite store: see
``services/relay/scripts/import_sqlite_inventory.py`` for a one-shot
copy from ``relay-data/relay.db`` into this Postgres schema.
"""
from __future__ import annotations

import logging
import secrets
import time
import uuid
from typing import Any

log = logging.getLogger("relay.store")

DEMO_USER_TOKEN = "demo-user-token"
DEMO_BRIDGE_TOKEN = "demo-bridge-token"

SCHEMA = """
CREATE TABLE IF NOT EXISTS inventory_users (
  token       TEXT PRIMARY KEY,
  email       TEXT NOT NULL,
  created_at  BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS inventory_hosts (
  id           TEXT PRIMARY KEY,
  owner_token  TEXT NOT NULL REFERENCES inventory_users(token) ON DELETE CASCADE,
  nickname     TEXT NOT NULL,
  hostname     TEXT,
  platform     TEXT,
  online       BOOLEAN NOT NULL DEFAULT FALSE,
  last_seen    BIGINT,
  created_at   BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS inventory_bridge_keys (
  token       TEXT PRIMARY KEY,
  host_id     TEXT NOT NULL REFERENCES inventory_hosts(id) ON DELETE CASCADE,
  created_at  BIGINT NOT NULL,
  revoked_at  BIGINT
);
CREATE TABLE IF NOT EXISTS inventory_sessions (
  id           TEXT PRIMARY KEY,
  host_id      TEXT NOT NULL,
  owner_token  TEXT NOT NULL,
  opened_at    BIGINT NOT NULL,
  closed_at    BIGINT
);
CREATE INDEX IF NOT EXISTS inventory_hosts_owner_idx ON inventory_hosts(owner_token, created_at DESC);
"""


def _now() -> int:
    return int(time.time())


def _new_id(prefix: str) -> str:
    return f"{prefix}_{uuid.uuid4().hex[:16]}"


class Store:
    """Async asyncpg-backed inventory store. The pool is created in
    ``start()`` and closed in ``stop()``; both are wired into the
    aiohttp application's startup / cleanup hooks."""

    def __init__(self, dsn: str | None) -> None:
        self._dsn = dsn
        self._pool: Any = None

    async def start(self) -> None:
        if not self._dsn:
            raise RuntimeError(
                "RELAY_DATABASE_URL (or SEARCH_DATABASE_URL) is required for the inventory store"
            )
        import asyncpg  # type: ignore[import-not-found]
        self._pool = await asyncpg.create_pool(
            dsn=self._dsn,
            min_size=1,
            max_size=5,
            command_timeout=30,
        )
        async with self._pool.acquire() as conn:
            await conn.execute(SCHEMA)
        await self._seed_demo()

    async def stop(self) -> None:
        if self._pool is not None:
            await self._pool.close()
            self._pool = None

    async def _seed_demo(self) -> None:
        async with self._pool.acquire() as conn:
            existing = await conn.fetchrow(
                "SELECT 1 FROM inventory_users WHERE token = $1", DEMO_USER_TOKEN,
            )
            if existing:
                return
            now = _now()
            host_id = _new_id("host")
            async with conn.transaction():
                await conn.execute(
                    "INSERT INTO inventory_users(token, email, created_at) VALUES ($1,$2,$3)",
                    DEMO_USER_TOKEN, "demo@local", now,
                )
                await conn.execute(
                    "INSERT INTO inventory_hosts(id, owner_token, nickname, created_at) VALUES ($1,$2,$3,$4)",
                    host_id, DEMO_USER_TOKEN, "demo-host", now,
                )
                await conn.execute(
                    "INSERT INTO inventory_bridge_keys(token, host_id, created_at) VALUES ($1,$2,$3)",
                    DEMO_BRIDGE_TOKEN, host_id, now,
                )
        log.info(
            "seeded demo user (%s) + host %s + bridge token (%s)",
            DEMO_USER_TOKEN, host_id, DEMO_BRIDGE_TOKEN,
        )

    # --- users ---

    async def user_for_token(self, token: str) -> dict | None:
        async with self._pool.acquire() as conn:
            row = await conn.fetchrow(
                "SELECT token, email FROM inventory_users WHERE token = $1", token,
            )
        return dict(row) if row else None

    # --- hosts ---

    async def list_hosts(self, owner_token: str) -> list[dict]:
        async with self._pool.acquire() as conn:
            rows = await conn.fetch(
                """
                SELECT id, nickname, hostname, platform, online, last_seen, created_at
                FROM inventory_hosts
                WHERE owner_token = $1
                ORDER BY created_at DESC
                """,
                owner_token,
            )
        return [dict(r) for r in rows]

    async def create_host(self, owner_token: str, nickname: str) -> str:
        hid = _new_id("host")
        async with self._pool.acquire() as conn:
            await conn.execute(
                "INSERT INTO inventory_hosts(id, owner_token, nickname, created_at) VALUES ($1,$2,$3,$4)",
                hid, owner_token, nickname, _now(),
            )
        return hid

    async def host_owner(self, host_id: str) -> str | None:
        async with self._pool.acquire() as conn:
            row = await conn.fetchrow(
                "SELECT owner_token FROM inventory_hosts WHERE id = $1", host_id,
            )
        return row["owner_token"] if row else None

    async def update_host_identity(self, host_id: str, hostname: str, platform: str) -> None:
        async with self._pool.acquire() as conn:
            await conn.execute(
                "UPDATE inventory_hosts SET hostname = $2, platform = $3 WHERE id = $1",
                host_id, hostname, platform,
            )

    async def mark_host(self, host_id: str, online: bool) -> None:
        async with self._pool.acquire() as conn:
            await conn.execute(
                "UPDATE inventory_hosts SET online = $2, last_seen = $3 WHERE id = $1",
                host_id, online, _now(),
            )

    # --- bridge keys ---

    async def issue_bridge_key(self, host_id: str) -> str:
        token = f"brg_live_{secrets.token_urlsafe(24)}"
        async with self._pool.acquire() as conn:
            await conn.execute(
                "INSERT INTO inventory_bridge_keys(token, host_id, created_at) VALUES ($1,$2,$3)",
                token, host_id, _now(),
            )
        return token

    async def resolve_bridge_key(self, token: str) -> str | None:
        async with self._pool.acquire() as conn:
            row = await conn.fetchrow(
                "SELECT host_id FROM inventory_bridge_keys WHERE token = $1 AND revoked_at IS NULL",
                token,
            )
        return row["host_id"] if row else None

    # --- sessions ---

    async def open_session(self, host_id: str, owner_token: str) -> str:
        sid = _new_id("sess")
        async with self._pool.acquire() as conn:
            await conn.execute(
                "INSERT INTO inventory_sessions(id, host_id, owner_token, opened_at) VALUES ($1,$2,$3,$4)",
                sid, host_id, owner_token, _now(),
            )
        return sid

    async def close_session(self, session_id: str) -> None:
        async with self._pool.acquire() as conn:
            await conn.execute(
                "UPDATE inventory_sessions SET closed_at = $2 WHERE id = $1 AND closed_at IS NULL",
                session_id, _now(),
            )

    async def session_info(self, session_id: str) -> dict | None:
        async with self._pool.acquire() as conn:
            row = await conn.fetchrow(
                "SELECT id, host_id, owner_token, opened_at, closed_at FROM inventory_sessions WHERE id = $1",
                session_id,
            )
        return dict(row) if row else None
