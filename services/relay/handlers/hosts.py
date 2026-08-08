"""Host CRUD + bridge-key issuance + cached telemetry."""
from __future__ import annotations

import time

from aiohttp import web

from ..auth import require_user
from ..hub import Hub
from ..logging import audit
from ..store import Store, key_id


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


async def _owned_host(request: web.Request) -> tuple[str, Store]:
    """Same ownership contract as every other host route: 404 whether the
    host doesn't exist or isn't yours, so the response can't be used to
    probe which host ids are real."""
    user = await require_user(request)
    host_id = request.match_info["host_id"]
    store: Store = request.app["store"]
    if await store.host_owner(host_id) != user["token"]:
        raise web.HTTPNotFound(reason="host not found")
    return host_id, store


async def issue_api_key(request: web.Request) -> web.Response:
    """Mint a bridge key. The plaintext is shown once and never stored —
    only its hash and the derived key id are kept."""
    host_id, store = await _owned_host(request)
    token = await store.issue_bridge_key(host_id)
    return web.json_response({
        "host_id": host_id,
        "token": token,
        "key_id": key_id(token),
    }, status=201)


async def list_api_keys(request: web.Request) -> web.Response:
    """Non-revoked bridge keys for a host, by key id prefix. There is no
    way to read a key back — reissue instead."""
    host_id, store = await _owned_host(request)
    return web.json_response({"host_id": host_id, "keys": await store.list_bridge_keys(host_id)})


async def revoke_api_key(request: web.Request) -> web.Response:
    """Revoke a bridge key by ``token`` (the plaintext) or ``key_id``.

    The host's live daemon socket is dropped as well: we don't record
    which key a socket authenticated with, and a daemon holding a
    revoked key would otherwise keep its session until it reconnected.
    A daemon whose key is still valid reconnects immediately."""
    host_id, store = await _owned_host(request)
    try:
        body = await request.json()
    except Exception as exc:
        raise web.HTTPBadRequest(reason="invalid json") from exc
    token = (body.get("token") or "").strip() or None
    kid = (body.get("key_id") or "").strip() or None
    if not token and not kid:
        raise web.HTTPBadRequest(reason="token or key_id is required")
    revoked = await store.revoke_bridge_key(host_id, token=token, key_id=kid)
    if not revoked:
        raise web.HTTPNotFound(reason="bridge key not found")
    hub: Hub = request.app["hub"]
    daemon_ws = hub.daemon_for(host_id)
    if daemon_ws is not None and not daemon_ws.closed:
        # Close only — the ws_daemon handler's own cleanup does the detach,
        # and it is also what marks the host offline and drops its cached
        # telemetry. Detaching here first makes that cleanup a no-op and
        # leaves the host advertised as online forever.
        await daemon_ws.close(code=4401, message=b"bridge key revoked")
    audit("bridge_key.revoked", host_id=host_id, key_id=kid or key_id(token or ""))
    return web.json_response({"host_id": host_id, "revoked": revoked})


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
        # ~30s ring so the client opens with a full graph (oldest-first).
        "history": hub.recent_telemetry(host_id, time.time()),
    })
