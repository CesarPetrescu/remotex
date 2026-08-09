"""Bridge-key routes: ownership, issuance, listing, revocation.

Revocation also has to leave the relay's view of the host consistent —
dropping the daemon socket without letting the daemon handler run its own
cleanup used to leave the host advertised as online forever.
"""
from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock

import pytest
from aiohttp import web

from relay.handlers import hosts as hosts_h
from relay.hub import Hub
from relay.store import hash_token, key_id


ALICE = hash_token("alice-token")
BOB = hash_token("bob-token")


class FakeStore:
    """Just the surface the host-key routes touch."""

    def __init__(self, owners: dict[str, str]) -> None:
        self.owners = owners
        self.issued: list[str] = []
        self.revoked: list[tuple[str, str | None, str | None]] = []
        self.revoke_result = 1

    async def user_for_token(self, token: str) -> dict | None:
        known = {"alice-token": ALICE, "bob-token": BOB}
        hashed = known.get(token)
        return {"token": hashed, "email": f"{token}@local"} if hashed else None

    async def host_owner(self, host_id: str) -> str | None:
        return self.owners.get(host_id)

    async def issue_bridge_key(self, host_id: str) -> str:
        token = f"brg_live_fake_{host_id}"
        self.issued.append(token)
        return token

    async def list_bridge_keys(self, host_id: str) -> list[dict]:
        return [{"key_id": "abc123def456", "created_at": 0}]

    async def revoke_bridge_key(self, host_id, *, token=None, key_id=None) -> int:
        self.revoked.append((host_id, token, key_id))
        return self.revoke_result


async def _client(aiohttp_client, store: FakeStore, hub: Hub):
    app = web.Application()
    app["store"] = store
    app["hub"] = hub
    app.router.add_get("/api/hosts/{host_id}/ping", hosts_h.ping_host)
    app.router.add_post("/api/hosts/{host_id}/api-key", hosts_h.issue_api_key)
    app.router.add_get("/api/hosts/{host_id}/api-key", hosts_h.list_api_keys)
    app.router.add_post("/api/hosts/{host_id}/api-key/revoke", hosts_h.revoke_api_key)
    return await aiohttp_client(app)


ALICE_AUTH = {"Authorization": "Bearer alice-token"}
BOB_AUTH = {"Authorization": "Bearer bob-token"}


@pytest.mark.asyncio
async def test_ping_round_trips_through_the_owned_daemon(aiohttp_client):
    store = FakeStore({"host_x": ALICE})
    hub = Hub()
    daemon = MagicMock()
    daemon.closed = False

    async def answer(frame):
        assert frame["type"] == "ping-request"
        hub.resolve_admin_request("host_x", frame["request_id"], {
            "type": "ping-response",
            "request_id": frame["request_id"],
        })

    daemon.send_json = AsyncMock(side_effect=answer)
    hub.daemons["host_x"] = daemon
    client = await _client(aiohttp_client, store, hub)

    resp = await client.get("/api/hosts/host_x/ping", headers=ALICE_AUTH)

    assert resp.status == 200
    assert await resp.json() == {"host_id": "host_x", "ok": True}


@pytest.mark.asyncio
async def test_issue_returns_the_plaintext_once_with_its_key_id(aiohttp_client):
    store = FakeStore({"host_x": ALICE})
    client = await _client(aiohttp_client, store, Hub())

    resp = await client.post("/api/hosts/host_x/api-key", headers=ALICE_AUTH)
    body = await resp.json()

    assert resp.status == 201
    assert body["token"] == store.issued[0]
    assert body["key_id"] == key_id(body["token"])


@pytest.mark.asyncio
async def test_another_users_host_is_indistinguishable_from_a_missing_one(aiohttp_client):
    """404 either way: a 403 would confirm the host id exists."""
    store = FakeStore({"host_x": ALICE})
    client = await _client(aiohttp_client, store, Hub())

    theirs = await client.get("/api/hosts/host_x/api-key", headers=BOB_AUTH)
    missing = await client.get("/api/hosts/host_nope/api-key", headers=BOB_AUTH)

    assert theirs.status == 404
    assert missing.status == 404


@pytest.mark.asyncio
async def test_revoke_drops_the_live_daemon_socket_without_pre_detaching(aiohttp_client):
    """The ws_daemon handler's own cleanup is what marks the host offline;
    detaching here first would turn that cleanup into a no-op."""
    store = FakeStore({"host_x": ALICE})
    hub = Hub()
    daemon = MagicMock()
    daemon.closed = False
    daemon.close = AsyncMock()
    await hub.attach_daemon("host_x", daemon)
    client = await _client(aiohttp_client, store, hub)

    resp = await client.post(
        "/api/hosts/host_x/api-key/revoke",
        headers=ALICE_AUTH,
        json={"key_id": "abc123def456"},
    )

    assert resp.status == 200
    daemon.close.assert_awaited_once()
    assert store.revoked == [("host_x", None, "abc123def456")]
    # Still registered: only the handler that owns the socket detaches it,
    # and that is what also flips the host offline.
    assert hub.daemon_for("host_x") is daemon


@pytest.mark.asyncio
async def test_revoke_requires_a_token_or_key_id_and_404s_on_a_miss(aiohttp_client):
    store = FakeStore({"host_x": ALICE})
    client = await _client(aiohttp_client, store, Hub())

    empty = await client.post(
        "/api/hosts/host_x/api-key/revoke", headers=ALICE_AUTH, json={},
    )
    assert empty.status == 400

    store.revoke_result = 0
    miss = await client.post(
        "/api/hosts/host_x/api-key/revoke",
        headers=ALICE_AUTH,
        json={"key_id": "deadbeefcafe"},
    )
    assert miss.status == 404
