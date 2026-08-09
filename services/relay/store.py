"""Inventory store: users, hosts, bridge keys, sessions in Postgres.

Inventory tables are prefixed with ``inventory_``.
"""
from __future__ import annotations

import hashlib
import logging
import os
import secrets
import time
import uuid
from typing import Any

log = logging.getLogger("relay.store")

DEMO_USER_TOKEN = "demo-user-token"
DEMO_BRIDGE_TOKEN = "demo-bridge-token"

_TRUTHY = {"1", "true", "yes"}


def seed_demo_enabled() -> bool:
    """Demo credentials are opt-in. The tokens are public (they're in the
    repo), so a relay that seeds them by default is a relay anyone owns."""
    return os.getenv("RELAY_SEED_DEMO", "").strip().lower() in _TRUTHY


def hash_token(token: str) -> str:
    """Tokens are stored hashed. Plaintext only ever exists in the request
    that presented it and in the one response that issued it."""
    return hashlib.sha256((token or "").encode()).hexdigest()


def key_id(token: str) -> str:
    """Short, non-secret handle for a bridge key — the first 12 chars of
    its stored hash. Safe to show in a UI and to revoke by."""
    return hash_token(token)[:12]


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
  os_user      TEXT,
  online       BOOLEAN NOT NULL DEFAULT FALSE,
  last_seen    BIGINT,
  created_at   BIGINT NOT NULL
);
ALTER TABLE inventory_hosts ADD COLUMN IF NOT EXISTS os_user TEXT;
ALTER TABLE inventory_hosts ADD COLUMN IF NOT EXISTS home_dir TEXT;
ALTER TABLE inventory_hosts ADD COLUMN IF NOT EXISTS default_cwd TEXT;
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
ALTER TABLE inventory_sessions ADD COLUMN IF NOT EXISTS kind TEXT NOT NULL DEFAULT 'codex';
-- Resume state. Without these a relay restart loses the codex thread a
-- session was bound to, and a reconnecting client silently gets a fresh
-- thread under its old session id.
ALTER TABLE inventory_sessions ADD COLUMN IF NOT EXISTS thread_id TEXT;
ALTER TABLE inventory_sessions ADD COLUMN IF NOT EXISTS cwd TEXT;
CREATE INDEX IF NOT EXISTS inventory_hosts_owner_idx ON inventory_hosts(owner_token, created_at DESC);
-- Presence is in-memory; after a relay restart every daemon must reconnect.
UPDATE inventory_hosts SET online = FALSE WHERE online;
-- Tokens are hashed at rest. The owner_token FK has to cascade before the
-- users PK can be rewritten in place; sessions.owner_token has no FK, so it
-- is rewritten explicitly. The 64-hex guard makes each UPDATE idempotent —
-- note it would skip a plaintext token that is itself 64 lowercase hex
-- chars, which nothing in this repo issues.
ALTER TABLE inventory_hosts DROP CONSTRAINT IF EXISTS inventory_hosts_owner_token_fkey;
ALTER TABLE inventory_hosts ADD CONSTRAINT inventory_hosts_owner_token_fkey
  FOREIGN KEY (owner_token) REFERENCES inventory_users(token)
  ON DELETE CASCADE ON UPDATE CASCADE;
UPDATE inventory_users       SET token       = encode(sha256(token::bytea), 'hex')
  WHERE token       !~ '^[0-9a-f]{64}$';
UPDATE inventory_bridge_keys SET token       = encode(sha256(token::bytea), 'hex')
  WHERE token       !~ '^[0-9a-f]{64}$';
UPDATE inventory_sessions    SET owner_token = encode(sha256(owner_token::bytea), 'hex')
  WHERE owner_token !~ '^[0-9a-f]{64}$';
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
                "RELAY_DATABASE_URL is required for the inventory store"
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
        if seed_demo_enabled():
            await self._seed_demo()

    async def stop(self) -> None:
        if self._pool is not None:
            await self._pool.close()
            self._pool = None

    async def _seed_demo(self) -> None:
        async with self._pool.acquire() as conn:
            existing = await conn.fetchrow(
                "SELECT 1 FROM inventory_users WHERE token = $1", hash_token(DEMO_USER_TOKEN),
            )
            if existing:
                return
            now = _now()
            host_id = _new_id("host")
            async with conn.transaction():
                await conn.execute(
                    "INSERT INTO inventory_users(token, email, created_at) VALUES ($1,$2,$3)",
                    hash_token(DEMO_USER_TOKEN), "demo@local", now,
                )
                await conn.execute(
                    "INSERT INTO inventory_hosts(id, owner_token, nickname, created_at) VALUES ($1,$2,$3,$4)",
                    host_id, hash_token(DEMO_USER_TOKEN), "demo-host", now,
                )
                await conn.execute(
                    "INSERT INTO inventory_bridge_keys(token, host_id, created_at) VALUES ($1,$2,$3)",
                    hash_token(DEMO_BRIDGE_TOKEN), host_id, now,
                )
        # Never log the token values — RELAY_SEED_DEMO is documentation enough.
        log.info("seeded demo user + host %s (RELAY_SEED_DEMO)", host_id)

    # --- users ---

    async def user_for_token(self, token: str) -> dict | None:
        async with self._pool.acquire() as conn:
            row = await conn.fetchrow(
                "SELECT token, email FROM inventory_users WHERE token = $1",
                hash_token(token),
            )
        return dict(row) if row else None

    # --- hosts ---

    async def list_hosts(self, owner_token: str) -> list[dict]:
        async with self._pool.acquire() as conn:
            rows = await conn.fetch(
                """
                SELECT id, nickname, hostname, platform, os_user, home_dir, default_cwd,
                       online, last_seen, created_at
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

    async def update_host_identity(
        self,
        host_id: str,
        hostname: str,
        platform: str,
        os_user: str = "",
        home_dir: str = "",
        default_cwd: str = "",
    ) -> None:
        async with self._pool.acquire() as conn:
            await conn.execute(
                "UPDATE inventory_hosts "
                "SET hostname = $2, platform = $3, os_user = $4, home_dir = $5, default_cwd = $6 "
                "WHERE id = $1",
                host_id, hostname, platform, os_user or None, home_dir or None, default_cwd or None,
            )

    async def mark_host(self, host_id: str, online: bool) -> None:
        async with self._pool.acquire() as conn:
            await conn.execute(
                "UPDATE inventory_hosts SET online = $2, last_seen = $3 WHERE id = $1",
                host_id, online, _now(),
            )

    # --- bridge keys ---

    async def issue_bridge_key(self, host_id: str) -> str:
        """Mint a bridge key. The plaintext is returned exactly once —
        only its hash is stored, so a lost key must be reissued."""
        token = f"brg_live_{secrets.token_urlsafe(24)}"
        async with self._pool.acquire() as conn:
            await conn.execute(
                "INSERT INTO inventory_bridge_keys(token, host_id, created_at) VALUES ($1,$2,$3)",
                hash_token(token), host_id, _now(),
            )
        return token

    async def resolve_bridge_key(self, token: str) -> str | None:
        async with self._pool.acquire() as conn:
            row = await conn.fetchrow(
                "SELECT host_id FROM inventory_bridge_keys WHERE token = $1 AND revoked_at IS NULL",
                hash_token(token),
            )
        return row["host_id"] if row else None

    async def list_bridge_keys(self, host_id: str) -> list[dict]:
        """Non-revoked keys for a host, identified by their key id (the
        first 12 chars of the stored hash) — never by anything usable."""
        async with self._pool.acquire() as conn:
            rows = await conn.fetch(
                "SELECT substr(token, 1, 12) AS key_id, created_at"
                " FROM inventory_bridge_keys"
                " WHERE host_id = $1 AND revoked_at IS NULL"
                " ORDER BY created_at DESC",
                host_id,
            )
        return [dict(r) for r in rows]

    async def revoke_bridge_key(
        self,
        host_id: str,
        *,
        token: str | None = None,
        key_id: str | None = None,
    ) -> int:
        """Revoke one of a host's bridge keys, by plaintext token or by key
        id. Returns the number of keys revoked (0 if already revoked or not
        this host's)."""
        if token:
            match_col, match_val = "token", hash_token(token)
        elif key_id:
            match_col, match_val = "substr(token, 1, 12)", key_id
        else:
            return 0
        async with self._pool.acquire() as conn:
            result = await conn.execute(
                "UPDATE inventory_bridge_keys SET revoked_at = $1"
                f" WHERE host_id = $2 AND {match_col} = $3 AND revoked_at IS NULL",
                _now(), host_id, match_val,
            )
        # asyncpg returns the command tag, e.g. "UPDATE 1".
        return int(result.rsplit(" ", 1)[-1]) if result else 0

    # --- sessions ---

    async def open_session(
        self,
        host_id: str,
        owner_token: str,
        *,
        kind: str = "codex",
        thread_id: str | None = None,
        cwd: str | None = None,
    ) -> str:
        sid = _new_id("sess")
        async with self._pool.acquire() as conn:
            await conn.execute(
                "INSERT INTO inventory_sessions(id, host_id, owner_token, opened_at, kind, thread_id, cwd)"
                " VALUES ($1,$2,$3,$4,$5,$6,$7)",
                sid, host_id, owner_token, _now(), kind, thread_id, cwd,
            )
        return sid

    async def update_session_resume(
        self,
        session_id: str,
        *,
        thread_id: str | None,
        cwd: str | None,
    ) -> None:
        """Persist the live codex thread/cwd so a relay restart can rebuild
        the session-open frame instead of starting a fresh thread."""
        if not thread_id and not cwd:
            return
        async with self._pool.acquire() as conn:
            await conn.execute(
                "UPDATE inventory_sessions"
                " SET thread_id = COALESCE($2, thread_id), cwd = COALESCE($3, cwd)"
                " WHERE id = $1",
                session_id, thread_id, cwd,
            )

    async def close_session(self, session_id: str) -> None:
        async with self._pool.acquire() as conn:
            await conn.execute(
                "UPDATE inventory_sessions SET closed_at = $2 WHERE id = $1 AND closed_at IS NULL",
                session_id, _now(),
            )

    async def session_info(self, session_id: str) -> dict | None:
        async with self._pool.acquire() as conn:
            row = await conn.fetchrow(
                "SELECT id, host_id, owner_token, opened_at, closed_at, kind, thread_id, cwd"
                " FROM inventory_sessions WHERE id = $1",
                session_id,
            )
        return dict(row) if row else None
