"""Model listing endpoints — contract tests (no DB required)."""
from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock

import pytest
from aiohttp import web

from relay.handlers.models_route import get_host_models, get_models
from relay.hub import Hub
from relay.models import MODEL_OPTIONS
from relay.store import hash_token


@pytest.fixture
def app() -> web.Application:
    inner = web.Application()
    inner.router.add_get("/api/models", get_models)
    return inner


@pytest.mark.asyncio
async def test_returns_models_and_efforts(aiohttp_client, app):
    client = await aiohttp_client(app)
    resp = await client.get("/api/models")
    assert resp.status == 200
    data = await resp.json()
    assert "models" in data
    assert "efforts" in data
    ids = [m["id"] for m in data["models"]]
    # The default sentinel and at least one current frontier model
    # must always be in the list.
    assert "" in ids
    assert any(i.startswith("gpt-") for i in ids)


@pytest.mark.asyncio
async def test_each_model_has_an_efforts_list(aiohttp_client, app):
    client = await aiohttp_client(app)
    data = await (await client.get("/api/models")).json()
    for model in data["models"]:
        assert isinstance(model["efforts"], list)
        assert len(model["efforts"]) >= 1


OWNER = hash_token("owner-token")


class FakeStore:
    async def user_for_token(self, token: str) -> dict | None:
        return {"token": OWNER} if token == "owner-token" else None

    async def host_owner(self, host_id: str) -> str | None:
        return OWNER if host_id == "host_x" else None


@pytest.fixture
def host_app() -> web.Application:
    inner = web.Application()
    inner["store"] = FakeStore()
    inner["hub"] = Hub()
    inner.router.add_get("/api/hosts/{host_id}/models", get_host_models)
    return inner


@pytest.mark.asyncio
async def test_host_models_proxies_the_daemon(aiohttp_client, host_app):
    hub: Hub = host_app["hub"]
    ws = MagicMock()
    ws.closed = False
    ws.close = AsyncMock()

    async def answer(frame):
        hub.resolve_admin_request("host_x", frame["request_id"], {
            "type": "models-list-response",
            "request_id": frame["request_id"],
            "models": [{"id": "gpt-host", "label": "host model", "efforts": ["low"]}],
        })

    ws.send_json = AsyncMock(side_effect=answer)
    hub.daemons["host_x"] = ws
    client = await aiohttp_client(host_app)

    resp = await client.get(
        "/api/hosts/host_x/models",
        headers={"Authorization": "Bearer owner-token"},
    )
    data = await resp.json()

    assert resp.status == 200
    assert data["source"] == "host"
    assert data["models"] == [
        {"id": "gpt-host", "label": "host model", "hint": "", "efforts": ["low"]},
    ]


@pytest.mark.asyncio
async def test_host_models_falls_back_when_host_is_offline(aiohttp_client, host_app):
    client = await aiohttp_client(host_app)

    resp = await client.get(
        "/api/hosts/host_x/models",
        headers={"Authorization": "Bearer owner-token"},
    )
    data = await resp.json()

    assert resp.status == 200
    assert data["source"] == "fallback"
    assert data["models"] == MODEL_OPTIONS


@pytest.mark.asyncio
async def test_host_models_404s_for_someone_elses_host(aiohttp_client, host_app):
    client = await aiohttp_client(host_app)

    resp = await client.get(
        "/api/hosts/host_other/models",
        headers={"Authorization": "Bearer owner-token"},
    )

    assert resp.status == 404
