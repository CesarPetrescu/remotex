"""Remotex relay — central rendezvous for daemons and clients.

Production scope:
  * Postgres-backed user, host, bridge-key, session inventory.
  * Outbound-WSS daemons on /ws/daemon
  * Web/mobile clients on /ws/client
  * Per-session pipes routed in-memory by session_id
  * Token auth, hashed at rest (demo tokens only when RELAY_SEED_DEMO is
    set; OIDC is a follow-up)
  * REST rate limit (token bucket, 30 burst / 10 per second) + a per-IP
    cap on websocket connection attempts
  * Structured JSON logs + audit events on logger=audit

This module is intentionally small — every responsibility lives in a
sibling module:
  * ``store.py``     — async asyncpg inventory store
  * ``hub.py``       — in-memory routing maps
  * ``auth.py``      — bearer token middleware
  * ``limits.py``    — transfer size ceilings (HTTP body + WS frame)
  * ``models.py``    — hostless default sentinel served at /api/models
  * ``logging.py``   — JSON formatter + audit() helper
  * ``middleware/``  — rate limit middleware
  * ``handlers/``    — REST + WS handlers
"""
from __future__ import annotations

import argparse
import asyncio
import contextlib
import logging
import os
from pathlib import Path

from aiohttp import web

from .handlers import fs as fs_h
from .handlers import hosts as hosts_h
from .handlers import models_route as models_h
from .handlers import sessions as sessions_h
from .handlers import static as static_h
from .handlers import threads as threads_h
from .handlers.ws_client import ws_client
from .handlers.ws_daemon import ws_daemon
from .hub import Hub
from .limits import HTTP_MAX_BODY_BYTES
from .logging import configure_json_logging
from .middleware import add_security_headers, rate_limit_middleware
from .store import Store

log = logging.getLogger("relay")


def make_app(database_url: str | None, static_root: Path) -> web.Application:
    app = web.Application(
        middlewares=[rate_limit_middleware],
        # File uploads ride the request body; the WS legs are sized to
        # match in limits.py.
        client_max_size=HTTP_MAX_BODY_BYTES,
    )
    app["store"] = Store(database_url)
    app["hub"] = Hub()
    app["static_root"] = static_root
    # session_id → session-open overrides awaiting a client attach, and
    # session_id → reservation timestamp for the sweeper. Created up front:
    # aiohttp deprecates adding app keys after startup.
    app["session_open_overrides"] = {}
    app["session_reservations"] = {}
    # Background task handles live in here for the same reason: aiohttp
    # freezes the Application before on_startup runs, so assigning a new
    # app key from _start_services is deprecated.
    app["tasks"] = {}
    # The prepare signal also covers aiohttp-generated errors, FileResponse,
    # and WebSocket handshakes; a normal response middleware does not.
    app.on_response_prepare.append(add_security_headers)
    app.on_startup.append(_start_services)
    app.on_cleanup.append(_stop_services)

    app.router.add_get("/", static_h.index)
    app.router.add_get("/api/models", models_h.get_models)
    app.router.add_get("/api/hosts", hosts_h.list_hosts)
    app.router.add_post("/api/hosts", hosts_h.register_host)
    app.router.add_get("/api/hosts/{host_id}/ping", hosts_h.ping_host)
    app.router.add_post("/api/hosts/{host_id}/api-key", hosts_h.issue_api_key)
    app.router.add_get("/api/hosts/{host_id}/api-key", hosts_h.list_api_keys)
    app.router.add_post("/api/hosts/{host_id}/api-key/revoke", hosts_h.revoke_api_key)
    app.router.add_get("/api/hosts/{host_id}/models", models_h.get_host_models)
    app.router.add_get("/api/hosts/{host_id}/threads", threads_h.list_host_threads)
    app.router.add_get(
        "/api/hosts/{host_id}/threads/{thread_id}/preview",
        threads_h.get_thread_preview,
    )
    app.router.add_get("/api/hosts/{host_id}/fs", fs_h.list_host_fs)
    app.router.add_post("/api/hosts/{host_id}/fs/mkdir", fs_h.mkdir_host_fs)
    app.router.add_get("/api/hosts/{host_id}/fs/read", fs_h.read_host_file)
    app.router.add_post("/api/hosts/{host_id}/fs/delete", fs_h.delete_host_file)
    app.router.add_post("/api/hosts/{host_id}/fs/rename", fs_h.rename_host_file)
    app.router.add_post("/api/hosts/{host_id}/fs/upload", fs_h.upload_host_file)
    app.router.add_get("/api/hosts/{host_id}/telemetry", hosts_h.get_host_telemetry)
    app.router.add_post("/api/sessions", sessions_h.open_session)
    app.router.add_get("/ws/daemon", ws_daemon)
    app.router.add_get("/ws/client", ws_client)
    # Vite drops hashed bundles into static_root/assets; the legacy
    # single-file demo has no /assets dir. Only mount the route when the
    # directory exists so the old tree still serves.
    assets_dir = static_root / "assets"
    if assets_dir.is_dir():
        app.router.add_static("/assets", str(assets_dir), show_index=False)
    for name in static_h.ROOT_STATIC_FILES:
        app.router.add_get(f"/{name}", static_h.make_root_static(name))
    # SPA fallback — must be registered LAST so every specific route above
    # gets first pick. Any remaining GET under `/` returns index.html so
    # the client router can own its own URLs.
    app.router.add_get("/{tail:.*}", static_h.spa_fallback)
    return app


async def _start_services(app: web.Application) -> None:
    await app["store"].start()
    app["tasks"]["reservation_sweeper"] = asyncio.create_task(
        sessions_h.reservation_sweeper(app), name="session-reservation-sweeper",
    )


async def _stop_services(app: web.Application) -> None:
    task = app["tasks"].pop("reservation_sweeper", None)
    if task is not None:
        task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await task
    await app["store"].stop()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument(
        "--database-url",
        default=os.getenv("RELAY_DATABASE_URL"),
        help="Postgres DSN for the inventory store (defaults to RELAY_DATABASE_URL)",
    )
    ap.add_argument(
        "--web-root",
        default=str(Path(__file__).resolve().parents[1] / "web"),
        help="directory containing index.html",
    )
    args = ap.parse_args()
    configure_json_logging()
    app = make_app(args.database_url, Path(args.web_root))
    log.info("relay listening", extra={"host": args.host, "port": args.port})
    web.run_app(app, host=args.host, port=args.port, print=None)


if __name__ == "__main__":
    main()
