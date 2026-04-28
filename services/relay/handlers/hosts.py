"""Host CRUD + bridge-key issuance + cached telemetry."""
from __future__ import annotations

from aiohttp import web

from ..auth import require_user
from ..hub import Hub
from ..store import Store


async def list_hosts(request: web.Request) -> web.Response:
    user = await require_user(request)
    hosts = await request.app["store"].list_hosts(user["token"])
    return web.json_response({"hosts": hosts})


async def register_host(request: web.Request) -> web.Response:
    user = await require_user(request)
    body = await request.json()
    nickname = (body.get("nickname") or "").strip()
    if not nickname:
        raise web.HTTPBadRequest(reason="nickname required")
    hid = await request.app["store"].create_host(user["token"], nickname)
    return web.json_response({"id": hid, "nickname": nickname}, status=201)


async def issue_api_key(request: web.Request) -> web.Response:
    user = await require_user(request)
    host_id = request.match_info["host_id"]
    store: Store = request.app["store"]
    owner = await store.host_owner(host_id)
    if owner is None:
        raise web.HTTPNotFound(reason="host not found")
    if owner != user["token"]:
        raise web.HTTPForbidden(reason="not your host")
    token = await store.issue_bridge_key(host_id)
    return web.json_response({"host_id": host_id, "token": token}, status=201)


async def get_host_telemetry(request: web.Request) -> web.Response:
    """Latest host telemetry snapshot cached by the relay.

    Polled by the web client to keep the telemetry sidebar populated
    before (and between) active sessions. Clients with an active session
    on this host also receive push updates over the session WebSocket.
    """
    user = await require_user(request)
    host_id = request.match_info["host_id"]
    store: Store = request.app["store"]
    hub: Hub = request.app["hub"]
    if await store.host_owner(host_id) != user["token"]:
        raise web.HTTPNotFound(reason="host not found")
    snap = hub.host_telemetry.get(host_id)
    if snap is None:
        return web.json_response({"host_id": host_id, "data": None, "ts": None})
    return web.json_response({
        "host_id": host_id,
        "data": snap.get("data"),
        "ts": snap.get("received_at"),
    })
