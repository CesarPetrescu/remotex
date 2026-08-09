"""Hostless default and host-derived model picker contracts."""
from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock

import pytest
from aiohttp import web

from daemon.adapters.admin import model_options_from_codex
from relay.handlers.models_route import get_host_models, get_models
from relay.hub import Hub
from relay.models import MODEL_OPTIONS
from relay.store import hash_token


CODEX_ENTRY = {
    "id": "gpt-host",
    "model": "gpt-host",
    "displayName": "Host model",
    "description": "Available on this host",
    "hidden": False,
    "supportedReasoningEfforts": [
        {"reasoningEffort": "low"},
        {"reasoningEffort": "max"},
        {"reasoningEffort": "ultra"},
    ],
}


@pytest.fixture
def app() -> web.Application:
    inner = web.Application()
    inner.router.add_get("/api/models", get_models)
    return inner


@pytest.mark.asyncio
async def test_fallback_names_no_models(aiohttp_client, app):
    client = await aiohttp_client(app)
    data = await (await client.get("/api/models")).json()
    assert [model["id"] for model in data["models"]] == [""]
    assert data["models"][0]["efforts"]


def test_codex_models_map_to_per_model_efforts():
    models = model_options_from_codex({"data": [
        CODEX_ENTRY,
        {
            "model": "gpt-lean",
            "supportedReasoningEfforts": [{"reasoningEffort": "low"}],
        },
        {**CODEX_ENTRY, "model": "hidden", "hidden": True},
    ]})

    assert [model["id"] for model in models] == ["", "gpt-host", "gpt-lean"]
    assert models[0]["efforts"] == ["", "low", "max", "ultra"]
    assert models[1]["efforts"] == ["", "low", "max", "ultra"]
    assert models[2]["efforts"] == ["", "low"]


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
async def test_host_models_proxy_the_owned_daemon(aiohttp_client, host_app):
    hub: Hub = host_app["hub"]
    ws = MagicMock()
    ws.closed = False
    ws.close = AsyncMock()
    host_models = [
        {"id": "", "label": "default", "hint": "codex picks",
         "efforts": ["", "low", "max", "ultra"]},
        {"id": "gpt-host", "label": "Host model", "hint": "available",
         "efforts": ["", "low", "max", "ultra"]},
    ]

    async def answer(frame):
        hub.resolve_admin_request("host_x", frame["request_id"], {
            "type": "models-list-response",
            "request_id": frame["request_id"],
            "models": host_models,
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
    assert data["models"] == host_models
    assert data["efforts"] == ["", "low", "max", "ultra"]


@pytest.mark.asyncio
async def test_host_models_fall_back_when_host_is_offline(aiohttp_client, host_app):
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
async def test_host_models_hide_foreign_hosts(aiohttp_client, host_app):
    client = await aiohttp_client(host_app)
    resp = await client.get(
        "/api/hosts/host_other/models",
        headers={"Authorization": "Bearer owner-token"},
    )
    assert resp.status == 404
