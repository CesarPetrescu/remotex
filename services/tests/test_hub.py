"""Hub attach/detach and session-frame caching."""
from __future__ import annotations

from unittest.mock import MagicMock

import pytest

from relay.hub import Hub


def _ws_mock():
    ws = MagicMock()
    ws.closed = False
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
