"""GET /api/models — endpoint contract test (no DB required)."""
from __future__ import annotations

import pytest
from aiohttp import web

from relay.handlers.models_route import get_models


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
