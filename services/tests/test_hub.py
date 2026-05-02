"""Hub attach/detach and session-frame caching."""
from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock

import pytest

from relay.hub import Hub


def _ws_mock():
    ws = MagicMock()
    ws.closed = False
    ws.send_json = AsyncMock()
    ws.close = AsyncMock()
    return ws


@pytest.mark.asyncio
async def test_attach_daemon_returns_old_ws_on_replacement():
    hub = Hub()
    first = _ws_mock()
    old = await hub.attach_daemon("host_a", first)
    assert old is None
    second = _ws_mock()
    replaced = await hub.attach_daemon("host_a", second)
    assert replaced is first


@pytest.mark.asyncio
async def test_detach_daemon_only_removes_matching_ws():
    hub = Hub()
    a = _ws_mock()
    b = _ws_mock()
    await hub.attach_daemon("host", a)
    # Trying to detach with a stale reference must not blow away the live one.
    detached = await hub.detach_daemon("host", b)
    assert detached is False
    assert hub.daemon_for("host") is a
    detached = await hub.detach_daemon("host", a)
    assert detached is True
    assert hub.daemon_for("host") is None


@pytest.mark.asyncio
async def test_session_open_frame_replay_for_host():
    hub = Hub()
    await hub.remember_session_open(
        "sess_1", "host_x", {"type": "session-open", "session_id": "sess_1"}
    )
    await hub.remember_session_open(
        "sess_2", "host_x", {"type": "session-open", "session_id": "sess_2"}
    )
    await hub.remember_session_open(
        "sess_3", "host_y", {"type": "session-open", "session_id": "sess_3"}
    )
    frames = await hub.session_open_frames_for_host("host_x")
    sids = sorted(f["session_id"] for f in frames)
    assert sids == ["sess_1", "sess_2"]


@pytest.mark.asyncio
async def test_update_session_resume_modifies_cached_frame():
    hub = Hub()
    await hub.remember_session_open(
        "sess_1", "host_x", {"type": "session-open", "session_id": "sess_1"}
    )
    await hub.update_session_resume("sess_1", thread_id="thr_42", cwd="/work")
    frame = await hub.session_open_frame("sess_1")
    assert frame["resume_thread_id"] == "thr_42"
    assert frame["cwd"] == "/work"


@pytest.mark.asyncio
async def test_multiple_clients_attach_to_same_session_without_replacement():
    hub = Hub()
    web = _ws_mock()
    android = _ws_mock()

    assert await hub.attach_client("sess_1", "host_x", "web", web) == 1
    assert await hub.attach_client("sess_1", "host_x", "android", android) == 2

    web.close.assert_not_awaited()
    android.close.assert_not_awaited()
    assert set(hub.clients_for_host("host_x")) == {web, android}

    assert await hub.detach_client("sess_1", "web", web) is False
    assert hub.client_for("sess_1") is android
    assert await hub.detach_client("sess_1", "android", android) is True
    assert hub.client_for("sess_1") is None


@pytest.mark.asyncio
async def test_same_client_id_replaces_stale_socket_only():
    hub = Hub()
    old = _ws_mock()
    new = _ws_mock()

    await hub.attach_client("sess_1", "host_x", "web", old)
    assert await hub.attach_client("sess_1", "host_x", "web", new) == 1

    old.close.assert_awaited_once()
    new.close.assert_not_awaited()
    assert hub.client_for("sess_1") is new


@pytest.mark.asyncio
async def test_broadcast_records_sequence_and_replays_to_late_client():
    hub = Hub()
    web = _ws_mock()
    android = _ws_mock()
    await hub.attach_client("sess_1", "host_x", "web", web)
    await hub.attach_client("sess_1", "host_x", "android", android)

    delivered = await hub.broadcast_to_session(
        "sess_1",
        {"type": "session-event", "event": {"kind": "turn-started", "data": {}}},
    )

    assert delivered is True
    sent = web.send_json.await_args.args[0]
    assert sent["seq"] == 1
    assert sent["session_id"] == "sess_1"
    android.send_json.assert_awaited_once()

    replay = await hub.replay_since("sess_1", 0)
    assert len(replay) == 1
    assert replay[0]["seq"] == 1
    assert await hub.replay_since("sess_1", 1) == []


@pytest.mark.asyncio
async def test_turn_slot_is_single_writer_until_completed():
    hub = Hub()

    assert await hub.try_begin_turn("sess_1") is True
    assert await hub.try_begin_turn("sess_1") is False
    hub.mark_turn_completed("sess_1")
    assert await hub.try_begin_turn("sess_1") is True


@pytest.mark.asyncio
async def test_approval_resolution_is_first_writer_wins():
    hub = Hub()

    await hub.note_approval_request("sess_1", "appr_1")
    assert await hub.resolve_approval("sess_1", "appr_1") is True
    assert await hub.resolve_approval("sess_1", "appr_1") is False
