"""Daemon frames are bound to the host the bridge key authenticated as.

A valid bridge key for one host must not be able to write into, close, or
poison another host's sessions.
"""
from __future__ import annotations

import asyncio
from unittest.mock import AsyncMock, MagicMock

import pytest
from aiohttp import web

from relay.handlers.ws_daemon import (
    _bring_daemon_online,
    _take_daemon_offline,
    ws_daemon,
)
from relay.hub import Hub


class FakeStore:
    """Just the surface ws_daemon touches."""

    def __init__(self, sessions: dict[str, dict]) -> None:
        self.sessions = sessions
        self.closed: list[str] = []
        self.resumed: list[tuple[str, str | None, str | None]] = []
        self.host_marks: list[tuple[str, bool]] = []

    async def resolve_bridge_key(self, token: str) -> str | None:
        return {"key-a": "host_a", "key-b": "host_b"}.get(token)

    async def host_owner(self, host_id: str) -> str | None:
        return {"host_a": "owner-a", "host_b": "owner-b"}.get(host_id)

    async def update_host_identity(self, *args, **kwargs) -> None:
        return None

    async def mark_host(self, host_id: str, online: bool) -> None:
        self.host_marks.append((host_id, online))

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
async def test_thread_and_host_changes_reach_owner_inventory(aiohttp_client):
    store = FakeStore({})
    hub = Hub()
    inventory = _client_ws_mock()
    await hub.attach_inventory_client("owner-a", "browser", inventory)
    client = await _client_for(aiohttp_client, store, hub)

    ws = await client.ws_connect("/ws/daemon")
    await ws.send_json({"type": "hello", "token": "key-a"})
    assert (await ws.receive_json())["type"] == "welcome"
    await _wait_until(lambda: inventory.send_json.await_count == 2)
    initial = [call.args[0] for call in inventory.send_json.await_args_list]
    assert initial == [
        {
            "type": "hosts-changed",
            "host_id": "host_a",
            "reason": "daemon-online",
        },
        {
            "type": "threads-changed",
            "host_id": "host_a",
            "reason": "daemon-connected",
        },
    ]

    inventory.send_json.reset_mock()
    await ws.send_json({
        "type": "threads-changed",
        "method": "thread/name/updated",
        "thread_id": "thr_1",
    })
    await ws.send_json({"type": "ping"})
    assert (await ws.receive_json())["type"] == "pong"
    inventory.send_json.assert_awaited_once_with({
        "type": "threads-changed",
        "host_id": "host_a",
        "method": "thread/name/updated",
        "thread_id": "thr_1",
    })

    inventory.send_json.reset_mock()
    connection = ws._response.connection  # type: ignore[attr-defined]
    assert connection is not None and connection.transport is not None
    connection.transport.abort()
    await _wait_until(lambda: inventory.send_json.await_count == 1)
    inventory.send_json.assert_awaited_once_with({
        "type": "hosts-changed",
        "host_id": "host_a",
        "reason": "daemon-offline",
    })


@pytest.mark.asyncio
async def test_offline_cleanup_finishes_before_fresh_daemon_goes_online():
    store = FakeStore({})
    hub = Hub()
    old = _client_ws_mock()
    fresh = _client_ws_mock()
    await hub.attach_daemon("host_a", old, mode="shared")

    offline_mark_started = asyncio.Event()
    allow_offline_mark = asyncio.Event()
    original_mark_host = store.mark_host

    async def blocked_mark_host(host_id: str, online: bool) -> None:
        if not online:
            offline_mark_started.set()
            await allow_offline_mark.wait()
        await original_mark_host(host_id, online)

    store.mark_host = blocked_mark_host  # type: ignore[method-assign]
    taking_offline = asyncio.create_task(_take_daemon_offline(
        store, hub, old, "host_a", "owner-a", "shared",
    ))
    await asyncio.wait_for(offline_mark_started.wait(), timeout=1.0)

    bringing_online = asyncio.create_task(_bring_daemon_online(
        store,
        hub,
        fresh,
        "host_a",
        "owner-a",
        "shared",
        {"hostname": "new-host", "platform": "linux"},
    ))
    await asyncio.sleep(0)
    assert bringing_online.done() is False

    allow_offline_mark.set()
    assert await taking_offline is True
    assert await bringing_online is True
    assert store.host_marks[-2:] == [("host_a", False), ("host_a", True)]
    assert hub.daemon_for("host_a") is fresh


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


async def _wait_until(predicate, *, timeout: float = 1.0) -> None:
    async with asyncio.timeout(timeout):
        while not predicate():
            await asyncio.sleep(0.01)


@pytest.mark.asyncio
async def test_daemon_disconnect_aborts_turn_then_reopens_session(aiohttp_client):
    session = _victim_session()
    session["host_id"] = "host_a"
    store = FakeStore({"sess_victim": session})
    hub = Hub()
    watcher = _client_ws_mock()
    await hub.attach_client("sess_victim", "host_a", "web", watcher)
    await hub.ensure_session_open_frame("sess_victim", "host_a")
    client = await _client_for(aiohttp_client, store, hub)

    ws = await client.ws_connect("/ws/daemon")
    await ws.send_json({"type": "hello", "token": "key-a"})
    assert (await ws.receive_json())["type"] == "welcome"
    assert (await ws.receive_json())["type"] == "session-open"

    hub.mark_turn_started("sess_victim")
    await hub.note_approval_request(
        "sess_victim", "appr_1", {"approval_id": "appr_1"},
    )
    # Abort the transport rather than completing a close handshake: this is
    # the network-loss path that used to leave the turn slot wedged.
    connection = ws._response.connection  # type: ignore[attr-defined]
    assert connection is not None and connection.transport is not None
    connection.transport.abort()
    await _wait_until(lambda: watcher.send_json.await_count == 1)

    watcher.send_json.assert_awaited_once()
    completion = watcher.send_json.await_args.args[0]
    assert completion["type"] == "session-event"
    assert completion["event"]["kind"] == "turn-completed"
    assert completion["event"]["data"]["status"] == "failed"
    assert completion["seq"] == 1
    assert hub.turn_in_flight["sess_victim"] is False
    prompts = await hub.pending_prompt_snapshot("sess_victim")
    assert prompts["approvals"] == []

    # The daemon reconnect gets the saved session-open frame, and the relay
    # no longer rejects a fresh turn as if the destroyed one were running.
    async with client.ws_connect("/ws/daemon") as reconnected:
        await reconnected.send_json({"type": "hello", "token": "key-a"})
        assert (await reconnected.receive_json())["type"] == "welcome"
        assert (await reconnected.receive_json())["type"] == "session-open"
        assert await hub.try_begin_turn("sess_victim") is True
        hub.mark_turn_completed("sess_victim")


@pytest.mark.asyncio
async def test_shared_disconnect_preserves_turn_until_resume_reconciles(aiohttp_client):
    session = _victim_session()
    session["host_id"] = "host_a"
    store = FakeStore({"sess_victim": session})
    hub = Hub()
    watcher = _client_ws_mock()
    await hub.attach_client("sess_victim", "host_a", "web", watcher)
    await hub.ensure_session_open_frame("sess_victim", "host_a")
    client = await _client_for(aiohttp_client, store, hub)

    ws = await client.ws_connect("/ws/daemon")
    await ws.send_json({"type": "hello", "token": "key-a", "mode": "shared"})
    assert (await ws.receive_json())["type"] == "welcome"
    assert (await ws.receive_json())["type"] == "session-open"
    hub.mark_turn_started("sess_victim")
    await hub.note_approval_request(
        "sess_victim", "appr_1", {"approval_id": "appr_1"},
    )

    connection = ws._response.connection  # type: ignore[attr-defined]
    assert connection is not None and connection.transport is not None
    connection.transport.abort()
    await _wait_until(
        lambda: (
            hub.daemon_for("host_a", include_unready=True) is None
            and watcher.send_json.await_count == 1
        ),
    )

    prompt_reset = watcher.send_json.await_args.args[0]
    assert prompt_reset["type"] == "pending-prompts"
    assert prompt_reset["approvals"] == []
    assert hub.turn_in_flight["sess_victim"] is True
    assert (await hub.pending_prompt_snapshot("sess_victim"))["approvals"] == []

    async with client.ws_connect("/ws/daemon") as reconnected:
        await reconnected.send_json({
            "type": "hello", "token": "key-a", "mode": "shared",
        })
        assert (await reconnected.receive_json())["type"] == "welcome"
        assert (await reconnected.receive_json())["type"] == "session-open"
        await reconnected.send_json({
            "type": "session-event",
            "session_id": "sess_victim",
            "event": {
                "kind": "thread-status",
                "data": {
                    "status": "resumed",
                    "shared_turn_in_flight": False,
                },
            },
        })
        await reconnected.send_json({"type": "ping"})
        assert (await reconnected.receive_json())["type"] == "pong"

    assert hub.turn_in_flight["sess_victim"] is False
    assert (await hub.pending_prompt_snapshot("sess_victim"))["approvals"] == []
    sent = watcher.send_json.await_args_list
    assert len(sent) == 2
    assert sent[1].args[0]["event"]["kind"] == "thread-status"
    assert not any(
        (frame.get("event") or {}).get("kind") == "turn-completed"
        for frame in await hub.replay_since("sess_victim", 0)
    )


@pytest.mark.asyncio
async def test_replacement_daemon_aborts_old_active_turn(aiohttp_client):
    session = _victim_session()
    session["host_id"] = "host_a"
    store = FakeStore({"sess_victim": session})
    hub = Hub()
    watcher = _client_ws_mock()
    await hub.attach_client("sess_victim", "host_a", "web", watcher)
    await hub.ensure_session_open_frame("sess_victim", "host_a")
    client = await _client_for(aiohttp_client, store, hub)

    first = await client.ws_connect("/ws/daemon")
    await first.send_json({"type": "hello", "token": "key-a"})
    assert (await first.receive_json())["type"] == "welcome"
    assert (await first.receive_json())["type"] == "session-open"
    hub.mark_turn_started("sess_victim")

    async with client.ws_connect("/ws/daemon") as replacement:
        await replacement.send_json({"type": "hello", "token": "key-a"})
        assert (await replacement.receive_json())["type"] == "welcome"
        assert (await replacement.receive_json())["type"] == "session-open"
        await _wait_until(lambda: watcher.send_json.await_count == 1)
        closed = await first.receive()
        assert closed.type.name in {"CLOSE", "CLOSED"}
        assert closed.data == 4000
        assert closed.extra == "daemon-replaced"
        assert hub.turn_in_flight["sess_victim"] is False

    completion = watcher.send_json.await_args.args[0]
    assert completion["event"]["kind"] == "turn-completed"


@pytest.mark.asyncio
async def test_shared_replacement_preserves_old_active_turn(aiohttp_client):
    session = _victim_session()
    session["host_id"] = "host_a"
    store = FakeStore({"sess_victim": session})
    hub = Hub()
    watcher = _client_ws_mock()
    await hub.attach_client("sess_victim", "host_a", "web", watcher)
    await hub.ensure_session_open_frame("sess_victim", "host_a")
    client = await _client_for(aiohttp_client, store, hub)

    first = await client.ws_connect("/ws/daemon")
    await first.send_json({"type": "hello", "token": "key-a", "mode": "shared"})
    assert (await first.receive_json())["type"] == "welcome"
    assert (await first.receive_json())["type"] == "session-open"
    hub.mark_turn_started("sess_victim")

    async with client.ws_connect("/ws/daemon") as replacement:
        await replacement.send_json({
            "type": "hello", "token": "key-a", "mode": "shared",
        })
        assert (await replacement.receive_json())["type"] == "welcome"
        assert (await replacement.receive_json())["type"] == "session-open"
        closed = await first.receive()
        assert closed.type.name in {"CLOSE", "CLOSED"}
        assert closed.data == 4000
        assert hub.turn_in_flight["sess_victim"] is True

    watcher.send_json.assert_not_awaited()


@pytest.mark.asyncio
async def test_replacement_is_not_forwardable_until_welcome_and_replay(aiohttp_client):
    session = _victim_session()
    session["host_id"] = "host_a"
    store = FakeStore({"sess_victim": session})
    hub = Hub()
    await hub.ensure_session_open_frame("sess_victim", "host_a")

    close_started = asyncio.Event()
    allow_close = asyncio.Event()
    old = _client_ws_mock()

    async def blocked_close(**_kwargs) -> None:
        close_started.set()
        await allow_close.wait()

    old.close = AsyncMock(side_effect=blocked_close)
    await hub.attach_daemon("host_a", old)
    client = await _client_for(aiohttp_client, store, hub)

    async with client.ws_connect("/ws/daemon") as replacement:
        await replacement.send_json({"type": "hello", "token": "key-a"})
        await _wait_until(close_started.is_set)

        # The replacement is current but deliberately unready. A client or
        # REST proxy cannot overtake welcome/session-open on its socket.
        assert hub.daemon_for("host_a") is None
        assert await hub.forward_to_daemon("host_a", {"type": "probe"}) is False

        allow_close.set()
        assert (await replacement.receive_json())["type"] == "welcome"
        assert (await replacement.receive_json())["type"] == "session-open"
        await _wait_until(lambda: hub.daemon_for("host_a") is not None)

        assert await hub.forward_to_daemon("host_a", {"type": "probe"}) is True
        assert await replacement.receive_json() == {"type": "probe"}


@pytest.mark.asyncio
async def test_replacement_drops_old_frame_waiting_on_session_lookup(aiohttp_client):
    session = _victim_session()
    session["host_id"] = "host_a"
    store = FakeStore({"sess_victim": session})
    hub = Hub()
    lookup_started = asyncio.Event()
    allow_lookup = asyncio.Event()
    original_session_info = store.session_info

    async def blocked_session_info(session_id: str) -> dict | None:
        lookup_started.set()
        await allow_lookup.wait()
        return await original_session_info(session_id)

    store.session_info = blocked_session_info  # type: ignore[method-assign]
    client = await _client_for(aiohttp_client, store, hub)

    first = await client.ws_connect("/ws/daemon")
    await first.send_json({"type": "hello", "token": "key-a"})
    assert (await first.receive_json())["type"] == "welcome"
    old_server_ws = hub.daemon_for("host_a", include_unready=True)
    assert old_server_ws is not None

    # Keep the old frame inside the only awaited ownership lookup while the
    # replacement becomes current. Without the post-lookup identity gate the
    # stale event lands in replay after the handoff.
    await first.send_json({
        "type": "session-event",
        "session_id": "sess_victim",
        "event": {
            "kind": "item-completed",
            "data": {"text": "STALE"},
        },
    })
    await _wait_until(lookup_started.is_set)

    replacement = await client.ws_connect("/ws/daemon")
    await replacement.send_json({"type": "hello", "token": "key-a"})
    await _wait_until(
        lambda: hub.daemon_for("host_a", include_unready=True) is not old_server_ws,
    )
    allow_lookup.set()

    assert (await replacement.receive_json())["type"] == "welcome"
    await _wait_until(lambda: old_server_ws.closed)
    assert await hub.replay_since("sess_victim", 0) == []
    assert store.resumed == []
    await replacement.close()
