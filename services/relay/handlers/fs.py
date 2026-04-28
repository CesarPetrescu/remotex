"""Filesystem proxy: forward fs/readDirectory and fs/mkdir requests to the daemon."""
from __future__ import annotations

import asyncio
import uuid

from aiohttp import web

from ..auth import require_user
from ..hub import Hub
from ..store import Store


async def list_host_fs(request: web.Request) -> web.Response:
    """Forward an fs/readDirectory to the daemon; return the entries as
    JSON. Clients use this to browse the daemon's filesystem before
    they pick a cwd for a new session."""
    user = await require_user(request)
    host_id = request.match_info["host_id"]
    store: Store = request.app["store"]
    hub: Hub = request.app["hub"]
    if await store.host_owner(host_id) != user["token"]:
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


async def mkdir_host_fs(request: web.Request) -> web.Response:
    """Ask the daemon to create a new directory under a given parent.
    Body: {"path": "<parent>", "name": "<single-segment>"}."""
    user = await require_user(request)
    host_id = request.match_info["host_id"]
    store: Store = request.app["store"]
    hub: Hub = request.app["hub"]
    if await store.host_owner(host_id) != user["token"]:
        raise web.HTTPNotFound(reason="host not found")
    daemon_ws = hub.daemon_for(host_id)
    if daemon_ws is None or daemon_ws.closed:
        raise web.HTTPBadGateway(reason="host offline")
    try:
        body = await request.json()
    except Exception as exc:
        raise web.HTTPBadRequest(reason="invalid json") from exc
    parent = (body.get("path") or "").strip()
    name = (body.get("name") or "").strip()
    if not parent:
        raise web.HTTPBadRequest(reason="path is required")
    if not name or "/" in name or name in (".", ".."):
        raise web.HTTPBadRequest(reason="invalid folder name")
    req_id = f"req_{uuid.uuid4().hex[:12]}"
    loop = asyncio.get_running_loop()
    fut: asyncio.Future = loop.create_future()
    hub.pending_admin[req_id] = fut
    try:
        await daemon_ws.send_json({
            "type": "fs-mkdir-request",
            "request_id": req_id,
            "path": parent,
            "name": name,
        })
        try:
            payload = await asyncio.wait_for(fut, timeout=10.0)
        except asyncio.TimeoutError as exc:
            raise web.HTTPGatewayTimeout(reason="daemon did not respond in time") from exc
    finally:
        hub.pending_admin.pop(req_id, None)
    if "error" in payload:
        raise web.HTTPBadGateway(reason=f"daemon error: {payload['error']}")
    return web.json_response({
        "host_id": host_id,
        "path": payload.get("path"),
    }, status=201)
