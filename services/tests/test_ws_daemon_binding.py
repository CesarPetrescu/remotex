"""Daemon frames are bound to the host the bridge key authenticated as.

A valid bridge key for one host must not be able to write into, close, or
poison another host's sessions.
"""
from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock

import pytest
from aiohttp import web

from relay.handlers.ws_daemon import ws_daemon
from relay.hub import Hub


class FakeStore:
    """Just the surface ws_daemon touches."""

    def __init__(self, sessions: dict[str, dict]) -> None:
        self.sessions = sessions
        self.closed: list[str] = []
        self.resumed: list[tuple[str, str | None, str | None]] = []

    async def resolve_bridge_key(self, token: str) -> str | None:
        return {"key-a": "host_a", "key-b": "host_b"}.get(token)

    async def update_host_identity(self, *args, **kwargs) -> None:
        return None

    async def mark_host(self, host_id: str, online: bool) -> None:
        return None

    async def session_info(self, session_id: str) -> dict | None:
        return self.sessions.get(session_id)

    async def close_session(self, session_id: str) -> None:
        self.closed.append(session_id)

    async def update_session_resume(self, session_id, *, thread_id, cwd) -> None:
        self.resumed.append((session_id, thread_id, cwd))


def _victim_session() -> dict:
    return {
        "id": "sess_victim",
        "host_id": "host_b",
        "owner_token": "owner",
        "opened_at": 0,
        "closed_at": None,
        "kind": "codex",
        "thread_id": None,
        "cwd": None,
    }


async def _client_for(aiohttp_client, store: FakeStore, hub: Hub):
    app = web.Application()
    app["store"] = store
    app["hub"] = hub
    app.router.add_get("/ws/daemon", ws_daemon)
    return await aiohttp_client(app)


def _client_ws_mock():
    ws = MagicMock()
    ws.closed = False
    ws.send_json = AsyncMock()
    ws.close = AsyncMock()
    return ws


@pytest.mark.asyncio
async def test_session_event_for_foreign_session_is_dropped(aiohttp_client):
    store = FakeStore({"sess_victim": _victim_session()})
    hub = Hub()
    victim_client = _client_ws_mock()
    await hub.attach_client("sess_victim", "host_b", "web", victim_client)
    client = await _client_for(aiohttp_client, store, hub)

    async with client.ws_connect("/ws/daemon") as ws:
        await ws.send_json({"type": "hello", "token": "key-a"})
        assert (await ws.receive_json())["type"] == "welcome"
        await ws.send_json({
            "type": "session-event",
            "session_id": "sess_victim",
            "event": {"kind": "item-completed", "data": {"text": "injected"}},
        })
        # Round-trip a ping so the frame above has certainly been handled.
        await ws.send_json({"type": "ping"})
        assert (await ws.receive_json())["type"] == "pong"

    victim_client.send_json.assert_not_awaited()
    assert await hub.replay_since("sess_victim", 0) == []


@pytest.mark.asyncio
async def test_session_closed_for_foreign_session_does_not_close_it(aiohttp_client):
    store = FakeStore({"sess_victim": _victim_session()})
    hub = Hub()
    await hub.ensure_session_open_frame("sess_victim", "host_b")
    client = await _client_for(aiohttp_client, store, hub)

    async with client.ws_connect("/ws/daemon") as ws:
        await ws.send_json({"type": "hello", "token": "key-a"})
        await ws.receive_json()
        await ws.send_json({"type": "session-closed", "session_id": "sess_victim"})
        await ws.send_json({"type": "ping"})
        assert (await ws.receive_json())["type"] == "pong"

    assert store.closed == []
    assert hub.host_for_session("sess_victim") == "host_b"


@pytest.mark.asyncio
async def test_own_session_event_is_broadcast_and_resume_persisted(aiohttp_client):
    session = _victim_session()
    session["host_id"] = "host_a"
    store = FakeStore({"sess_victim": session})
    hub = Hub()
    watcher = _client_ws_mock()
    await hub.attach_client("sess_victim", "host_a", "web", watcher)
    client = await _client_for(aiohttp_client, store, hub)

    async with client.ws_connect("/ws/daemon") as ws:
        await ws.send_json({"type": "hello", "token": "key-a"})
        await ws.receive_json()
        await ws.send_json({
            "type": "session-event",
            "session_id": "sess_victim",
            "event": {
                "kind": "session-started",
                "data": {"thread_id": "thr_9", "cwd": "/work"},
            },
        })
        await ws.send_json({"type": "ping"})
        assert (await ws.receive_json())["type"] == "pong"

    watcher.send_json.assert_awaited_once()
    assert store.resumed == [("sess_victim", "thr_9", "/work")]
