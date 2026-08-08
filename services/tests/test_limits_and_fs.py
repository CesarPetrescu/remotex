"""Size ceilings (contract A) and the explicit oversize error."""
from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock

import pytest
from aiohttp import web

from relay.handlers.fs import read_host_file
from relay.hub import Hub
from relay.limits import HTTP_MAX_BODY_BYTES, MAX_FILE_BYTES, WS_MAX_MSG_SIZE
from relay.store import hash_token


OWNER = hash_token("owner-token")


def test_transport_ceilings_can_carry_a_max_size_file():
    """A file at the ceiling has to survive base64 inflation plus the JSON
    envelope, or the ceiling is a socket kill dressed up as a limit."""
    base64_bytes = -(-MAX_FILE_BYTES * 4 // 3)
    assert WS_MAX_MSG_SIZE >= base64_bytes
    assert HTTP_MAX_BODY_BYTES > MAX_FILE_BYTES


def test_max_file_bytes_is_env_tunable(monkeypatch):
    import importlib

    import relay.limits as limits

    monkeypatch.setenv("REMOTEX_MAX_FILE_BYTES", str(5 * 1024 * 1024))
    reloaded = importlib.reload(limits)
    try:
        assert reloaded.MAX_FILE_BYTES == 5 * 1024 * 1024
        # A junk value must not stop the relay from starting.
        monkeypatch.setenv("REMOTEX_MAX_FILE_BYTES", "not-a-number")
        assert importlib.reload(limits).MAX_FILE_BYTES == limits.DEFAULT_MAX_FILE_BYTES
    finally:
        monkeypatch.delenv("REMOTEX_MAX_FILE_BYTES", raising=False)
        importlib.reload(limits)


class FakeStore:
    async def user_for_token(self, token: str) -> dict | None:
        return {"token": OWNER} if token == "owner-token" else None

    async def host_owner(self, host_id: str) -> str | None:
        return OWNER if host_id == "host_x" else None


@pytest.mark.asyncio
async def test_oversize_read_answers_with_json_not_a_dropped_socket(aiohttp_client):
    app = web.Application()
    app["store"] = FakeStore()
    hub = Hub()
    app["hub"] = hub
    app.router.add_get("/api/hosts/{host_id}/fs/read", read_host_file)

    ws = MagicMock()
    ws.closed = False
    ws.close = AsyncMock()

    async def answer(frame):
        # A daemon that ignores max_bytes must not get to blow the
        # transport ceiling; the relay checks the reported size too.
        hub.resolve_admin_request("host_x", frame["request_id"], {
            "type": "fs-readfile-response",
            "request_id": frame["request_id"],
            "path": "/big.bin",
            "size": MAX_FILE_BYTES + 1,
            "base64": "",
        })

    ws.send_json = AsyncMock(side_effect=answer)
    hub.daemons["host_x"] = ws
    client = await aiohttp_client(app)

    resp = await client.get(
        "/api/hosts/host_x/fs/read?path=/big.bin",
        headers={"Authorization": "Bearer owner-token"},
    )
    body = await resp.json()

    assert resp.status == 413
    assert body["error"] == "file too large"
    assert body["max_bytes"] == MAX_FILE_BYTES
