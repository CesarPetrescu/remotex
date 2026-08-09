"""Owner-authenticated inventory WebSocket behavior."""
from __future__ import annotations

import asyncio

import pytest
from aiohttp import web

from relay.handlers.ws_inventory import ws_inventory
from relay.hub import Hub


class FakeStore:
    async def user_for_token(self, token: str) -> dict | None:
        if token == "user-token":
            return {"token": "hashed-owner-a", "email": "a@example.test"}
        return None


async def _client_for(aiohttp_client, hub: Hub):
    app = web.Application()
    app["store"] = FakeStore()
    app["hub"] = hub
    app.router.add_get("/ws/inventory", ws_inventory)
    return await aiohttp_client(app)


@pytest.mark.asyncio
async def test_inventory_rejects_bad_token_with_fatal_error(aiohttp_client):
    client = await _client_for(aiohttp_client, Hub())

    async with client.ws_connect("/ws/inventory") as ws:
        await ws.send_json({"type": "hello", "token": "bad"})
        assert await ws.receive_json() == {
            "type": "error",
            "error": "invalid user token",
            "fatal": True,
        }
        closed = await ws.receive()
        assert closed.type.name in {"CLOSE", "CLOSED"}


@pytest.mark.asyncio
async def test_inventory_ready_and_ping_pong(aiohttp_client):
    hub = Hub()
    client = await _client_for(aiohttp_client, hub)

    async with client.ws_connect("/ws/inventory") as ws:
        await ws.send_json({
            "type": "hello",
            "token": "user-token",
            "client_id": "browser",
            "client_name": "Web",
        })
        assert await ws.receive_json() == {
            "type": "inventory-ready",
            "client_id": "browser",
        }
        await ws.send_json({"type": "ping", "ts": 42})
        assert await ws.receive_json() == {"type": "pong", "ts": 42}
        assert hub.inventory_clients["hashed-owner-a"]["browser"] is not None


@pytest.mark.asyncio
async def test_same_inventory_client_replaces_old_socket_safely(aiohttp_client):
    hub = Hub()
    client = await _client_for(aiohttp_client, hub)
    hello = {
        "type": "hello",
        "token": "user-token",
        "client_id": "browser",
    }

    first = await client.ws_connect("/ws/inventory")
    await first.send_json(hello)
    assert (await first.receive_json())["type"] == "inventory-ready"

    second = await client.ws_connect("/ws/inventory")
    await second.send_json(hello)
    old_close = asyncio.create_task(first.receive())
    assert (await second.receive_json())["type"] == "inventory-ready"
    closed = await asyncio.wait_for(old_close, timeout=1.0)
    assert closed.type.name in {"CLOSE", "CLOSED"}
    assert closed.data == 4000
    assert hub.inventory_clients["hashed-owner-a"]["browser"] is not None

    await second.send_json({"type": "ping", "ts": "still-live"})
    assert await second.receive_json() == {"type": "pong", "ts": "still-live"}
    await second.close()
