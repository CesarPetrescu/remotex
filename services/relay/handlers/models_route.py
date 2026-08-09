"""Hostless default and host-derived Codex model picker endpoints."""
from __future__ import annotations

import logging

from aiohttp import web

from ..models import ALL_EFFORTS, MODEL_OPTIONS
from .daemon_rpc import await_daemon_request


log = logging.getLogger("relay.models")

_HOST_MODELS_TIMEOUT = 10.0
_MAX_MODELS = 200
_MAX_EFFORTS = 32
_MAX_TEXT = 200


def _normalize(models: object) -> list[dict]:
    """Bound the daemon-supplied picker payload at the relay boundary."""
    if not isinstance(models, list):
        return []
    out: list[dict] = []
    for entry in models[:_MAX_MODELS]:
        if not isinstance(entry, dict) or "id" not in entry:
            continue
        model_id = str(entry.get("id") or "")[:_MAX_TEXT]
        efforts = entry.get("efforts")
        out.append({
            "id": model_id,
            "label": str(entry.get("label") or model_id or "default")[:_MAX_TEXT],
            "hint": str(entry.get("hint") or "")[:_MAX_TEXT],
            "efforts": (
                [str(e)[:_MAX_TEXT] for e in efforts[:_MAX_EFFORTS]]
                if isinstance(efforts, list)
                else list(ALL_EFFORTS)
            ),
        })
    return out


async def get_models(request: web.Request) -> web.Response:
    return web.json_response({"models": MODEL_OPTIONS, "efforts": ALL_EFFORTS})


async def get_host_models(request: web.Request) -> web.Response:
    host_id = request.match_info["host_id"]
    try:
        payload = await await_daemon_request(
            request,
            host_id,
            {"type": "models-list-request"},
            timeout=_HOST_MODELS_TIMEOUT,
        )
        models = _normalize(payload.get("models"))
    except (web.HTTPBadGateway, web.HTTPGatewayTimeout) as exc:
        log.info(
            "host models unavailable, serving fallback",
            extra={"host_id": host_id, "reason": exc.reason},
        )
        models = []
    if not models:
        return web.json_response({
            "host_id": host_id,
            "models": MODEL_OPTIONS,
            "efforts": ALL_EFFORTS,
            "source": "fallback",
        })
    efforts = models[0]["efforts"] if models[0]["id"] == "" else ALL_EFFORTS
    return web.json_response({
        "host_id": host_id,
        "models": models,
        "efforts": efforts,
        "source": "host",
    })
