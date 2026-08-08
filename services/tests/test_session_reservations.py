"""Unattached session reservations are reaped.

POST /api/sessions writes a DB row, an override, and (for a resumed
thread) a (host, thread) index entry. Nothing cleans those up unless a
client attaches and later leaves, so a client that never attaches used to
leave a session that could never be reopened.
"""
from __future__ import annotations

import time
from unittest.mock import AsyncMock, MagicMock

import pytest
from aiohttp import web

from relay.handlers.sessions import RESERVATION_TTL_SECONDS, sweep_reservations
from relay.hub import Hub


class FakeStore:
    def __init__(self) -> None:
        self.closed: list[str] = []

    async def close_session(self, session_id: str) -> None:
        self.closed.append(session_id)


def _app(hub: Hub, store: FakeStore, reservations: dict) -> web.Application:
    app = web.Application()
    app["hub"] = hub
    app["store"] = store
    app["session_open_overrides"] = {sid: {"kind": "codex"} for sid in reservations}
    app["session_reservations"] = reservations
    return app


@pytest.mark.asyncio
async def test_expired_unattached_reservation_is_closed_and_forgotten():
    hub = Hub()
    store = FakeStore()
    await hub.remember_session_thread("sess_1", "host_x", "thr_1")
    now = time.monotonic()
    app = _app(hub, store, {"sess_1": now - RESERVATION_TTL_SECONDS - 1})

    assert await sweep_reservations(app, now=now) == 1

    assert store.closed == ["sess_1"]
    assert app["session_open_overrides"] == {}
    assert app["session_reservations"] == {}
    # The (host, thread) index no longer hands this dead id to the next caller.
    assert hub.active_session_for_thread("host_x", "thr_1") is None


@pytest.mark.asyncio
async def test_fresh_reservation_is_left_alone():
    hub = Hub()
    store = FakeStore()
    now = time.monotonic()
    app = _app(hub, store, {"sess_1": now})

    assert await sweep_reservations(app, now=now) == 0
    assert store.closed == []
    assert "sess_1" in app["session_reservations"]


@pytest.mark.asyncio
async def test_attached_session_stops_being_a_reservation():
    hub = Hub()
    store = FakeStore()
    client = MagicMock()
    client.closed = False
    client.send_json = AsyncMock()
    client.close = AsyncMock()
    await hub.attach_client("sess_1", "host_x", "web", client)
    now = time.monotonic()
    app = _app(hub, store, {"sess_1": now - RESERVATION_TTL_SECONDS - 1})

    assert await sweep_reservations(app, now=now) == 0

    assert store.closed == []
    assert app["session_reservations"] == {}
    assert hub.client_for("sess_1") is client
