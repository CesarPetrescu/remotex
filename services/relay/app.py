"""Remotex relay — central rendezvous for daemons and clients.

Prototype scope:
  * SQLite-backed user, host, bridge-key inventory
  * Outbound-WSS daemons on /ws/daemon
  * Web/mobile clients on /ws/client
  * Per-session pipes routed in-memory by session_id
  * Token auth (demo tokens seeded on first run)

This is the transport layer. The payload is opaque to the relay — Codex
App Server JSON-RPC frames ride inside `session-event` / `turn-start`
envelopes untouched. See docs/architecture.md.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
import secrets
import sqlite3
import time
import uuid
from pathlib import Path

from aiohttp import WSMsgType, web

log = logging.getLogger("relay")

DEMO_USER_TOKEN = "demo-user-token"
DEMO_BRIDGE_TOKEN = "demo-bridge-token"

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
  token       TEXT PRIMARY KEY,
  email       TEXT NOT NULL,
  created_at  INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS hosts (
  id           TEXT PRIMARY KEY,
  owner_token  TEXT NOT NULL,
  nickname     TEXT NOT NULL,
  hostname     TEXT,
  platform     TEXT,
  online       INTEGER NOT NULL DEFAULT 0,
  last_seen    INTEGER,
  created_at   INTEGER NOT NULL,
  FOREIGN KEY (owner_token) REFERENCES users(token) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS bridge_keys (
  token       TEXT PRIMARY KEY,
  host_id     TEXT NOT NULL,
  created_at  INTEGER NOT NULL,
  revoked_at  INTEGER,
  FOREIGN KEY (host_id) REFERENCES hosts(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS sessions (
  id           TEXT PRIMARY KEY,
  host_id      TEXT NOT NULL,
  owner_token  TEXT NOT NULL,
  opened_at    INTEGER NOT NULL,
  closed_at    INTEGER
);
"""


def _now() -> int:
    return int(time.time())


def _new_id(prefix: str) -> str:
    return f"{prefix}_{uuid.uuid4().hex[:16]}"


# ---------------------------------------------------------------------------
# Storage
# ---------------------------------------------------------------------------

class Store:
    """Tiny synchronous SQLite wrapper. The relay is not write-heavy; this is
    fine at prototype scale and keeps the dependency surface small."""

    def __init__(self, path: str):
        self.path = path
        self.conn = sqlite3.connect(path, check_same_thread=False)
        self.conn.row_factory = sqlite3.Row
        self.conn.executescript(SCHEMA)
        self._seed_demo()

    def _seed_demo(self) -> None:
        cur = self.conn.execute("SELECT 1 FROM users WHERE token = ?", (DEMO_USER_TOKEN,))
        if cur.fetchone():
            return
        now = _now()
        self.conn.execute(
            "INSERT INTO users(token, email, created_at) VALUES (?,?,?)",
            (DEMO_USER_TOKEN, "demo@local", now),
        )
        host_id = _new_id("host")
        self.conn.execute(
            "INSERT INTO hosts(id, owner_token, nickname, created_at) VALUES (?,?,?,?)",
            (host_id, DEMO_USER_TOKEN, "demo-host", now),
        )
        self.conn.execute(
            "INSERT INTO bridge_keys(token, host_id, created_at) VALUES (?,?,?)",
            (DEMO_BRIDGE_TOKEN, host_id, now),
        )
        self.conn.commit()
        log.info("seeded demo user (%s) + host %s + bridge token (%s)",
                 DEMO_USER_TOKEN, host_id, DEMO_BRIDGE_TOKEN)

    # --- users ---

    def user_for_token(self, token: str) -> dict | None:
        row = self.conn.execute(
            "SELECT token, email FROM users WHERE token = ?", (token,)
        ).fetchone()
        return dict(row) if row else None

    # --- hosts ---

    def list_hosts(self, owner_token: str) -> list[dict]:
        rows = self.conn.execute(
            "SELECT id, nickname, hostname, platform, online, last_seen, created_at "
            "FROM hosts WHERE owner_token = ? ORDER BY created_at DESC",
            (owner_token,),
        ).fetchall()
        # SQLite stores booleans as INTEGER; normalize before the JSON hits
        # clients (Kotlin/serialization rejects 1/0 for Boolean fields).
        out = []
        for r in rows:
            d = dict(r)
            d["online"] = bool(d["online"])
            out.append(d)
        return out

    def create_host(self, owner_token: str, nickname: str) -> str:
        hid = _new_id("host")
        self.conn.execute(
            "INSERT INTO hosts(id, owner_token, nickname, created_at) VALUES (?,?,?,?)",
            (hid, owner_token, nickname, _now()),
        )
        self.conn.commit()
        return hid

    def host_owner(self, host_id: str) -> str | None:
        row = self.conn.execute(
            "SELECT owner_token FROM hosts WHERE id = ?", (host_id,)
        ).fetchone()
        return row["owner_token"] if row else None

    def update_host_identity(self, host_id: str, hostname: str, platform: str) -> None:
        self.conn.execute(
            "UPDATE hosts SET hostname = ?, platform = ? WHERE id = ?",
            (hostname, platform, host_id),
        )
        self.conn.commit()

    def mark_host(self, host_id: str, online: bool) -> None:
        self.conn.execute(
            "UPDATE hosts SET online = ?, last_seen = ? WHERE id = ?",
            (1 if online else 0, _now(), host_id),
        )
        self.conn.commit()

    # --- bridge keys ---

    def issue_bridge_key(self, host_id: str) -> str:
        token = f"brg_live_{secrets.token_urlsafe(24)}"
        self.conn.execute(
            "INSERT INTO bridge_keys(token, host_id, created_at) VALUES (?,?,?)",
            (token, host_id, _now()),
        )
        self.conn.commit()
        return token

    def resolve_bridge_key(self, token: str) -> str | None:
        row = self.conn.execute(
            "SELECT host_id FROM bridge_keys WHERE token = ? AND revoked_at IS NULL",
            (token,),
        ).fetchone()
        return row["host_id"] if row else None

    # --- sessions ---

    def open_session(self, host_id: str, owner_token: str) -> str:
        sid = _new_id("sess")
        self.conn.execute(
            "INSERT INTO sessions(id, host_id, owner_token, opened_at) VALUES (?,?,?,?)",
            (sid, host_id, owner_token, _now()),
        )
        self.conn.commit()
        return sid

    def close_session(self, session_id: str) -> None:
        self.conn.execute(
            "UPDATE sessions SET closed_at = ? WHERE id = ? AND closed_at IS NULL",
            (_now(), session_id),
        )
        self.conn.commit()


# ---------------------------------------------------------------------------
# In-memory routing
# ---------------------------------------------------------------------------

class Hub:
    """Keeps track of live daemon sockets and live client sockets per session."""

    def __init__(self) -> None:
        self.daemons: dict[str, web.WebSocketResponse] = {}
        self.client_sessions: dict[str, web.WebSocketResponse] = {}
        self.session_host: dict[str, str] = {}
        self._lock = asyncio.Lock()
        # request_id → Future awaiting the daemon's response frame
        self.pending_admin: dict[str, asyncio.Future] = {}

    async def attach_daemon(self, host_id: str, ws: web.WebSocketResponse) -> None:
        async with self._lock:
            self.daemons[host_id] = ws

    async def detach_daemon(self, host_id: str) -> None:
        async with self._lock:
            self.daemons.pop(host_id, None)
            dead = [sid for sid, hid in self.session_host.items() if hid == host_id]
            for sid in dead:
                self.session_host.pop(sid, None)

    async def attach_client(self, session_id: str, host_id: str, ws: web.WebSocketResponse) -> None:
        async with self._lock:
            self.client_sessions[session_id] = ws
            self.session_host[session_id] = host_id

    async def detach_client(self, session_id: str) -> None:
        async with self._lock:
            self.client_sessions.pop(session_id, None)
            self.session_host.pop(session_id, None)

    def daemon_for(self, host_id: str) -> web.WebSocketResponse | None:
        return self.daemons.get(host_id)

    def client_for(self, session_id: str) -> web.WebSocketResponse | None:
        return self.client_sessions.get(session_id)

    def host_for_session(self, session_id: str) -> str | None:
        return self.session_host.get(session_id)


# ---------------------------------------------------------------------------
# HTTP handlers
# ---------------------------------------------------------------------------

def _bearer(request: web.Request) -> str | None:
    auth = request.headers.get("Authorization", "")
    if auth.startswith("Bearer "):
        return auth[len("Bearer "):].strip() or None
    return request.query.get("token")


async def require_user(request: web.Request) -> dict:
    token = _bearer(request)
    if not token:
        raise web.HTTPUnauthorized(reason="missing bearer token")
    store: Store = request.app["store"]
    user = store.user_for_token(token)
    if not user:
        raise web.HTTPUnauthorized(reason="unknown token")
    return user


async def list_hosts(request: web.Request) -> web.Response:
    user = await require_user(request)
    hosts = request.app["store"].list_hosts(user["token"])
    return web.json_response({"hosts": hosts})


async def register_host(request: web.Request) -> web.Response:
    user = await require_user(request)
    body = await request.json()
    nickname = (body.get("nickname") or "").strip()
    if not nickname:
        raise web.HTTPBadRequest(reason="nickname required")
    hid = request.app["store"].create_host(user["token"], nickname)
    return web.json_response({"id": hid, "nickname": nickname}, status=201)


async def issue_api_key(request: web.Request) -> web.Response:
    user = await require_user(request)
    host_id = request.match_info["host_id"]
    store: Store = request.app["store"]
    owner = store.host_owner(host_id)
    if owner is None:
        raise web.HTTPNotFound(reason="host not found")
    if owner != user["token"]:
        raise web.HTTPForbidden(reason="not your host")
    token = store.issue_bridge_key(host_id)
    return web.json_response({"host_id": host_id, "token": token}, status=201)


async def list_host_fs(request: web.Request) -> web.Response:
    """Forward an fs/readDirectory to the daemon; return the entries as
    JSON. Clients use this to browse the daemon's filesystem before
    they pick a cwd for a new session."""
    user = await require_user(request)
    host_id = request.match_info["host_id"]
    store: Store = request.app["store"]
    hub: Hub = request.app["hub"]
    if store.host_owner(host_id) != user["token"]:
        raise web.HTTPNotFound(reason="host not found")
    daemon_ws = hub.daemon_for(host_id)
    if daemon_ws is None or daemon_ws.closed:
        raise web.HTTPBadGateway(reason="host offline")
    path = request.query.get("path") or ""
    if not path:
        raise web.HTTPBadRequest(reason="path query parameter required")
    req_id = f"req_{uuid.uuid4().hex[:12]}"
    loop = asyncio.get_running_loop()
    fut: asyncio.Future = loop.create_future()
    hub.pending_admin[req_id] = fut
    try:
        await daemon_ws.send_json({
            "type": "fs-read-request",
            "request_id": req_id,
            "path": path,
        })
        try:
            payload = await asyncio.wait_for(fut, timeout=15.0)
        except asyncio.TimeoutError as exc:
            raise web.HTTPGatewayTimeout(reason="daemon did not respond in time") from exc
    finally:
        hub.pending_admin.pop(req_id, None)
    if "error" in payload:
        raise web.HTTPBadGateway(reason=f"daemon error: {payload['error']}")
    return web.json_response({
        "host_id": host_id,
        "path": payload.get("path", path),
        "entries": payload.get("entries", []),
    })


async def list_host_threads(request: web.Request) -> web.Response:
    """Forward a `thread/list` request to the daemon, await the response."""
    user = await require_user(request)
    host_id = request.match_info["host_id"]
    store: Store = request.app["store"]
    hub: Hub = request.app["hub"]
    if store.host_owner(host_id) != user["token"]:
        raise web.HTTPNotFound(reason="host not found")
    daemon_ws = hub.daemon_for(host_id)
    if daemon_ws is None or daemon_ws.closed:
        raise web.HTTPBadGateway(reason="host offline")
    req_id = f"req_{uuid.uuid4().hex[:12]}"
    limit = int(request.query.get("limit") or 20)
    loop = asyncio.get_running_loop()
    fut: asyncio.Future = loop.create_future()
    hub.pending_admin[req_id] = fut
    try:
        await daemon_ws.send_json({
            "type": "threads-list-request",
            "request_id": req_id,
            "limit": limit,
        })
        try:
            payload = await asyncio.wait_for(fut, timeout=15.0)
        except asyncio.TimeoutError as exc:
            raise web.HTTPGatewayTimeout(reason="daemon did not respond in time") from exc
    finally:
        hub.pending_admin.pop(req_id, None)
    if "error" in payload:
        raise web.HTTPBadGateway(reason=f"daemon error: {payload['error']}")
    threads = payload.get("threads") or []
    # Reshape to a compact payload for clients (keep only what the UI needs).
    summarized = [
        {
            "id": t.get("id"),
            "preview": t.get("preview") or "",
            "created_at": t.get("createdAt"),
            "updated_at": t.get("updatedAt"),
            "cwd": t.get("cwd"),
            "ephemeral": bool(t.get("ephemeral")),
        }
        for t in threads
        if t.get("id") and not t.get("ephemeral")
    ]
    return web.json_response({
        "host_id": host_id,
        "threads": summarized,
        "next_cursor": payload.get("next_cursor"),
    })


async def open_session(request: web.Request) -> web.Response:
    """Reserve a session id. The daemon is not notified until the client
    attaches via /ws/client — otherwise the session-started event would be
    emitted into the void before the client could observe it."""
    user = await require_user(request)
    body = await request.json()
    host_id = body.get("host_id")
    resume_thread_id = (body.get("thread_id") or "").strip() or None
    cwd = (body.get("cwd") or "").strip() or None
    store: Store = request.app["store"]
    hub: Hub = request.app["hub"]
    if not host_id or store.host_owner(host_id) != user["token"]:
        raise web.HTTPNotFound(reason="host not found")
    if hub.daemon_for(host_id) is None:
        raise web.HTTPBadGateway(reason="host offline")
    sid = store.open_session(host_id, user["token"])
    # Stash per-session overrides so ws_client can thread them into the
    # session-open frame it later sends to the daemon.
    overrides: dict = {}
    if resume_thread_id:
        overrides["thread_id"] = resume_thread_id
    if cwd:
        overrides["cwd"] = cwd
    if overrides:
        request.app.setdefault("session_open_overrides", {})[sid] = overrides
    return web.json_response({
        "session_id": sid,
        "host_id": host_id,
        "thread_id": resume_thread_id,
        "cwd": cwd,
    }, status=201)


async def index(request: web.Request) -> web.Response:
    root = request.app["static_root"]
    return web.FileResponse(root / "index.html")


# ---------------------------------------------------------------------------
# WebSocket handlers
# ---------------------------------------------------------------------------

async def ws_daemon(request: web.Request) -> web.WebSocketResponse:
    ws = web.WebSocketResponse(heartbeat=20)
    await ws.prepare(request)
    store: Store = request.app["store"]
    hub: Hub = request.app["hub"]

    host_id: str | None = None
    try:
        first = await asyncio.wait_for(ws.receive(), timeout=10)
        if first.type != WSMsgType.TEXT:
            await ws.close(code=1008, message=b"expected hello")
            return ws
        hello = json.loads(first.data)
        if hello.get("type") != "hello":
            await ws.close(code=1008, message=b"expected hello")
            return ws
        token = hello.get("token", "")
        host_id = store.resolve_bridge_key(token)
        if not host_id:
            await ws.send_json({"type": "error", "error": "invalid bridge token"})
            await ws.close(code=4401, message=b"invalid token")
            return ws

        store.update_host_identity(
            host_id,
            hello.get("hostname", "") or "",
            hello.get("platform", "") or "",
        )
        store.mark_host(host_id, True)
        await hub.attach_daemon(host_id, ws)
        await ws.send_json({"type": "welcome", "host_id": host_id})
        log.info("daemon %s online (hostname=%s platform=%s)",
                 host_id, hello.get("hostname"), hello.get("platform"))

        async for msg in ws:
            if msg.type != WSMsgType.TEXT:
                continue
            try:
                frame = json.loads(msg.data)
            except json.JSONDecodeError:
                continue
            ftype = frame.get("type")
            sid = frame.get("session_id")
            if ftype in {"session-event", "session-closed"} and sid:
                client_ws = hub.client_for(sid)
                if client_ws is not None and not client_ws.closed:
                    await client_ws.send_json(frame)
                if ftype == "session-closed":
                    store.close_session(sid)
            elif ftype in ("threads-list-response", "fs-read-response"):
                req_id = frame.get("request_id")
                fut = hub.pending_admin.get(req_id) if req_id else None
                if fut is not None and not fut.done():
                    fut.set_result(frame)
            elif ftype == "ping":
                await ws.send_json({"type": "pong"})
    except asyncio.TimeoutError:
        await ws.close(code=4408, message=b"hello timeout")
    except Exception as exc:  # noqa: BLE001
        log.exception("daemon ws error: %s", exc)
    finally:
        if host_id:
            store.mark_host(host_id, False)
            await hub.detach_daemon(host_id)
            log.info("daemon %s offline", host_id)
    return ws


async def ws_client(request: web.Request) -> web.WebSocketResponse:
    ws = web.WebSocketResponse(heartbeat=20)
    await ws.prepare(request)
    store: Store = request.app["store"]
    hub: Hub = request.app["hub"]

    session_id: str | None = None
    try:
        hello = await asyncio.wait_for(ws.receive(), timeout=10)
        if hello.type != WSMsgType.TEXT:
            await ws.close(code=1008, message=b"expected hello")
            return ws
        data = json.loads(hello.data)
        token = data.get("token", "")
        user = store.user_for_token(token)
        if not user:
            await ws.send_json({"type": "error", "error": "invalid user token"})
            await ws.close(code=4401, message=b"invalid token")
            return ws
        session_id = data.get("session_id")
        if not session_id:
            await ws.send_json({"type": "error", "error": "session_id required"})
            await ws.close(code=1008, message=b"no session")
            return ws
        host_id = hub.host_for_session(session_id)
        if host_id is None:
            # session opened via REST but we need the host id; look it up.
            row = store.conn.execute(
                "SELECT host_id, owner_token FROM sessions WHERE id = ?",
                (session_id,),
            ).fetchone()
            if row is None or row["owner_token"] != user["token"]:
                await ws.send_json({"type": "error", "error": "session not found"})
                await ws.close(code=1008, message=b"bad session")
                return ws
            host_id = row["host_id"]
        await hub.attach_client(session_id, host_id, ws)
        await ws.send_json({"type": "attached", "session_id": session_id, "host_id": host_id})
        # Now that the client is attached, ask the daemon to start the session.
        daemon_ws = hub.daemon_for(host_id)
        if daemon_ws is None or daemon_ws.closed:
            await ws.send_json({"type": "error", "error": "host offline"})
            await ws.close(code=4503, message=b"host offline")
            return ws
        override_map: dict = request.app.setdefault("session_open_overrides", {})
        overrides = override_map.pop(session_id, {}) or {}
        open_frame: dict = {"type": "session-open", "session_id": session_id}
        if overrides.get("thread_id"):
            open_frame["resume_thread_id"] = overrides["thread_id"]
        if overrides.get("cwd"):
            open_frame["cwd"] = overrides["cwd"]
        await daemon_ws.send_json(open_frame)

        async for msg in ws:
            if msg.type != WSMsgType.TEXT:
                continue
            try:
                frame = json.loads(msg.data)
            except json.JSONDecodeError:
                continue
            # Forward client-originated frames to the daemon untouched.
            frame["session_id"] = session_id
            daemon_ws = hub.daemon_for(host_id)
            if daemon_ws is None or daemon_ws.closed:
                await ws.send_json({"type": "error", "error": "host offline"})
                continue
            await daemon_ws.send_json(frame)
    except asyncio.TimeoutError:
        await ws.close(code=4408, message=b"hello timeout")
    except Exception as exc:  # noqa: BLE001
        log.exception("client ws error: %s", exc)
    finally:
        if session_id:
            await hub.detach_client(session_id)
    return ws


# ---------------------------------------------------------------------------
# App wiring
# ---------------------------------------------------------------------------

def make_app(db_path: str, static_root: Path) -> web.Application:
    app = web.Application()
    app["store"] = Store(db_path)
    app["hub"] = Hub()
    app["static_root"] = static_root

    app.router.add_get("/", index)
    app.router.add_get("/api/hosts", list_hosts)
    app.router.add_post("/api/hosts", register_host)
    app.router.add_post("/api/hosts/{host_id}/api-key", issue_api_key)
    app.router.add_get("/api/hosts/{host_id}/threads", list_host_threads)
    app.router.add_get("/api/hosts/{host_id}/fs", list_host_fs)
    app.router.add_post("/api/sessions", open_session)
    app.router.add_get("/ws/daemon", ws_daemon)
    app.router.add_get("/ws/client", ws_client)
    app.router.add_static("/assets", str(static_root), show_index=False)
    return app


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--db", default="relay.db")
    ap.add_argument(
        "--web-root",
        default=str(Path(__file__).resolve().parents[1] / "web"),
        help="directory containing index.html",
    )
    args = ap.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")
    app = make_app(args.db, Path(args.web_root))
    log.info("relay listening on http://%s:%d", args.host, args.port)
    web.run_app(app, host=args.host, port=args.port, print=None)


if __name__ == "__main__":
    main()
