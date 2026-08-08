"""A prompt claim survives a failed forward to the daemon.

Claiming an approval destroys it in the hub, so if the response never
reaches the daemon the turn hangs forever: codex still holds the request
and no client can answer it again. The claim has to come back.
"""
from __future__ import annotations

import pytest
from aiohttp import web

from relay.handlers.ws_client import ws_client
from relay.hub import Hub

from .test_ws_client_attach import ALICE, FakeStore, _session


async def _attached_ws(aiohttp_client, store: FakeStore, hub: Hub):
    app = web.Application()
    app["store"] = store
    app["hub"] = hub
    app["session_open_overrides"] = {}
    app.router.add_get("/ws/client", ws_client)
    return await aiohttp_client(app)


@pytest.mark.asyncio
async def test_user_input_claim_restored_when_daemon_forward_fails(aiohttp_client):
    store = FakeStore({"sess_1": _session(ALICE)})
    hub = Hub()
    hub.daemons["host_x"] = _dead_daemon()
    await hub.note_user_input_request("sess_1", "call_1", {"call_id": "call_1"})
    client = await _attached_ws(aiohttp_client, store, hub)

    async with client.ws_connect("/ws/client") as ws:
        await ws.send_json({"token": "alice-token", "session_id": "sess_1"})
        await ws.receive_json()   # attached
        await ws.receive_json()   # pending-prompts
        await ws.send_json({
            "type": "user-input-response",
            "call_id": "call_1",
            "answers": {"q1": {"answers": ["yes"]}},
        })
        assert (await ws.receive_json())["type"] == "error"
        requeued = await ws.receive_json()

    assert [u["call_id"] for u in requeued["user_inputs"]] == ["call_1"]
    assert await hub.resolve_user_input("sess_1", "call_1") is not None


@pytest.mark.asyncio
async def test_claim_is_handed_back_and_resent_to_the_answering_client(aiohttp_client):
    """The relay pushes a fresh pending-prompts queue after a restore, so
    a client that optimistically hid the dialog re-renders it."""
    store = FakeStore({"sess_1": _session(ALICE)})
    hub = Hub()
    hub.daemons["host_x"] = _dead_daemon()
    await hub.note_approval_request("sess_1", "appr_1", {"approval_id": "appr_1"})
    client = await _attached_ws(aiohttp_client, store, hub)

    async with client.ws_connect("/ws/client") as ws:
        await ws.send_json({"token": "alice-token", "session_id": "sess_1"})
        await ws.receive_json()   # attached
        await ws.receive_json()   # pending-prompts
        await ws.send_json({
            "type": "approval-response",
            "approval_id": "appr_1",
            "decision": "accept",
        })
        error = await ws.receive_json()
        requeued = await ws.receive_json()

    assert error == {"type": "error", "error": "host offline"}
    assert [a["approval_id"] for a in requeued["approvals"]] == ["appr_1"]
    assert await hub.resolve_approval("sess_1", "appr_1") is not None


def _dead_daemon():
    """A daemon socket that looks live at attach time and fails to send —
    the case where the claim is destroyed but the frame never lands."""
    from unittest.mock import AsyncMock, MagicMock

    ws = MagicMock()
    ws.closed = False
    ws.send_json = AsyncMock(side_effect=ConnectionResetError("gone"))
    ws.close = AsyncMock()
    return ws
