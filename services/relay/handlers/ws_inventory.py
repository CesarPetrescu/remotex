"""Authenticated owner-scoped WebSocket for host/thread inventory changes."""
from __future__ import annotations

import asyncio
import json
import logging
import uuid

from aiohttp import WSMsgType, web

from ..hub import Hub
from ..limits import WS_MAX_MSG_SIZE
from ..logging import audit
from ..middleware.rate_limit import allow_ws_connection, client_remote
from ..store import Store


log = logging.getLogger("relay.ws.inventory")


async def ws_inventory(request: web.Request) -> web.WebSocketResponse:
    remote = client_remote(request)
    allowed, retry_after = allow_ws_connection(remote)
    if not allowed:
        raise web.HTTPTooManyRequests(
            reason="too many websocket connections",
            headers={"Retry-After": f"{max(1, int(retry_after))}"},
        )

    ws = web.WebSocketResponse(heartbeat=20, max_msg_size=WS_MAX_MSG_SIZE)
    await ws.prepare(request)
    store: Store = request.app["store"]
    hub: Hub = request.app["hub"]

    owner_token: str | None = None
    client_id: str | None = None
    try:
        first = await asyncio.wait_for(ws.receive(), timeout=10)
        if first.type != WSMsgType.TEXT:
            await ws.close(code=1008, message=b"expected hello")
            return ws
        try:
            hello = json.loads(first.data)
        except (json.JSONDecodeError, TypeError):
            await ws.close(code=1008, message=b"expected hello")
            return ws
        if not isinstance(hello, dict) or hello.get("type") != "hello":
            await ws.close(code=1008, message=b"expected hello")
            return ws

        token = hello.get("token")
        user = await store.user_for_token(token if isinstance(token, str) else "")
        if not user:
            await ws.send_json({
                "type": "error",
                "error": "invalid user token",
                "fatal": True,
            })
            await ws.close(code=4401, message=b"invalid token")
            audit("auth.user.invalid", remote=remote)
            return ws

        owner_token = user["token"]
        raw_client_id = hello.get("client_id")
        client_id = (
            raw_client_id.strip()[:128]
            if isinstance(raw_client_id, str)
            else ""
        ) or f"inventory_{uuid.uuid4().hex[:12]}"
        await hub.attach_inventory_client(owner_token, client_id, ws)
        await ws.send_json({
            "type": "inventory-ready",
            "client_id": client_id,
        })

        async for msg in ws:
            if msg.type == WSMsgType.ERROR:
                break
            if msg.type != WSMsgType.TEXT:
                continue
            try:
                frame = json.loads(msg.data)
            except (json.JSONDecodeError, TypeError):
                continue
            if frame.get("type") == "ping":
                await ws.send_json({"type": "pong", "ts": frame.get("ts")})
    except asyncio.TimeoutError:
        await ws.close(code=4408, message=b"hello timeout")
    except Exception as exc:  # noqa: BLE001
        log.exception("inventory ws error", extra={"error": str(exc)})
    finally:
        if owner_token is not None and client_id is not None:
            await hub.detach_inventory_client(owner_token, client_id, ws)
    return ws
