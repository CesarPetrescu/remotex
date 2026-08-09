"""Attach-time authorization for /ws/client.

The hub is a cache of the live host binding, never the authority on who
may attach — these tests pin that down: a session that is live in the hub
is still checked against the store, and a closed session cannot be
resurrected into a fresh codex thread under its old id.
"""
from __future__ import annotations

import pytest
from aiohttp import web

from relay.handlers.ws_client import ws_client
from relay.hub import Hub
from relay.store import hash_token


ALICE = hash_token("alice-token")
BOB = hash_token("bob-token")


class FakeStore:
    """Just the surface ws_client touches."""

    def __init__(self, sessions: dict[str, dict]) -> None:
        self.sessions = sessions
        self.closed: list[str] = []

    async def user_for_token(self, token: str) -> dict | None:
        known = {"alice-token": ALICE, "bob-token": BOB}
        hashed = known.get(token)
        return {"token": hashed, "email": f"{token}@local"} if hashed else None

    async def session_info(self, session_id: str) -> dict | None:
        return self.sessions.get(session_id)

    async def close_session(self, session_id: str) -> None:
        self.closed.append(session_id)


def _session(owner: str, *, closed_at: int | None = None) -> dict:
    return {
        "id": "sess_1",
        "host_id": "host_x",
        "owner_token": owner,
        "opened_at": 0,
        "closed_at": closed_at,
        "kind": "codex",
        "thread_id": None,
        "cwd": None,
    }


async def _client_for(aiohttp_client, store: FakeStore, hub: Hub):
    app = web.Application()
    app["store"] = store
    app["hub"] = hub
    app["session_open_overrides"] = {}
    app.router.add_get("/ws/client", ws_client)
    return await aiohttp_client(app)


@pytest.mark.asyncio
async def test_attach_rejected_for_another_users_session(aiohttp_client):
    store = FakeStore({"sess_1": _session(ALICE)})
    hub = Hub()
    client = await _client_for(aiohttp_client, store, hub)

    async with client.ws_connect("/ws/client") as ws:
        await ws.send_json({"token": "bob-token", "session_id": "sess_1"})
        frame = await ws.receive_json()

    assert frame == {"type": "error", "error": "session not found", "fatal": True}


@pytest.mark.asyncio
async def test_live_session_in_hub_still_checks_ownership(aiohttp_client):
    """The bypass: a session cached in the hub used to skip the DB check
    entirely, handing any token holder full read/write on it."""
    store = FakeStore({"sess_1": _session(ALICE)})
    hub = Hub()
    # Session is live: the hub knows its host, as it would mid-turn.
    await hub.remember_session_thread("sess_1", "host_x", "thr_1")
    client = await _client_for(aiohttp_client, store, hub)

    async with client.ws_connect("/ws/client") as ws:
        await ws.send_json({"token": "bob-token", "session_id": "sess_1"})
        frame = await ws.receive_json()

    assert frame == {"type": "error", "error": "session not found", "fatal": True}
    assert hub.client_for("sess_1") is None


@pytest.mark.asyncio
async def test_attach_rejected_for_closed_session(aiohttp_client):
    store = FakeStore({"sess_1": _session(ALICE, closed_at=1234)})
    hub = Hub()
    client = await _client_for(aiohttp_client, store, hub)

    async with client.ws_connect("/ws/client") as ws:
        await ws.send_json({"token": "alice-token", "session_id": "sess_1"})
        frame = await ws.receive_json()

    # fatal: retrying a closed id is an infinite reconnect loop.
    assert frame == {"type": "error", "error": "session closed", "fatal": True}


@pytest.mark.asyncio
async def test_owner_attaches_and_gets_replay_gap_marker(aiohttp_client):
    """Owner attaches; because the buffer evicted what its cursor asks
    for, the gap marker precedes the replayed frames (contract C)."""
    store = FakeStore({"sess_1": _session(ALICE)})
    hub = Hub()
    hub.session_replay_evicted["sess_1"] = 40
    await hub.record_session_frame("sess_1", {"type": "session-event", "seq_probe": True})
    hub.session_seq["sess_1"] = 41
    client = await _client_for(aiohttp_client, store, hub)

    async with client.ws_connect("/ws/client") as ws:
        await ws.send_json({
            "token": "alice-token",
            "session_id": "sess_1",
            "last_seq": 10,
        })
        attached = await ws.receive_json()
        prompts = await ws.receive_json()
        gap = await ws.receive_json()

    assert attached["type"] == "attached"
    assert attached["host_id"] == "host_x"
    assert attached["replay_from"] == 10
    assert attached["turn_in_flight"] is False
    assert prompts["type"] == "pending-prompts"
    assert gap == {
        "type": "replay-gap",
        "session_id": "sess_1",
        "missed_from": 11,
        "missed_to": 40,
    }


@pytest.mark.asyncio
async def test_attached_reports_turn_already_in_flight(aiohttp_client):
    store = FakeStore({"sess_1": _session(ALICE)})
    hub = Hub()
    hub.mark_turn_started("sess_1")
    client = await _client_for(aiohttp_client, store, hub)

    async with client.ws_connect("/ws/client") as ws:
        await ws.send_json({
            "token": "alice-token",
            "session_id": "sess_1",
            "last_seq": 0,
        })
        attached = await ws.receive_json()

    assert attached["type"] == "attached"
    assert attached["replay_from"] == 0
    assert attached["turn_in_flight"] is True


@pytest.mark.asyncio
async def test_cursor_from_a_previous_relay_lifetime_replays_everything(aiohttp_client):
    """seq is minted per relay process. A client reconnecting after a
    redeploy holds a cursor way past the new counter — replaying nothing
    would silently drop the whole new stream."""
    store = FakeStore({"sess_1": _session(ALICE)})
    hub = Hub()
    for _ in range(3):
        await hub.record_session_frame("sess_1", {"type": "session-event"})
    client = await _client_for(aiohttp_client, store, hub)

    async with client.ws_connect("/ws/client") as ws:
        await ws.send_json({
            "token": "alice-token",
            "session_id": "sess_1",
            "last_seq": 400,
        })
        await ws.receive_json()   # attached
        await ws.receive_json()   # pending-prompts
        replayed = [(await ws.receive_json())["seq"] for _ in range(3)]

    assert replayed == [1, 2, 3]
