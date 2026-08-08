"""Model listing.

``GET /api/models`` — the static fallback list. No auth required; it is
the same for every user, and clients fetch it once on first paint.

``GET /api/hosts/{host_id}/models`` — what the host's codex actually
offers. Falls back to the static list (``source: "fallback"``) whenever
the host is offline, errors, or doesn't answer in time, so the picker is
never empty.
"""
from __future__ import annotations

import logging

from aiohttp import web

from ..models import ALL_EFFORTS, MODEL_OPTIONS
from .daemon_rpc import await_daemon_request


log = logging.getLogger("relay.models")

# Short — a model list is not worth stalling the picker for, and the
# static list is a perfectly good answer.
_HOST_MODELS_TIMEOUT = 10.0


# The list is rendered in a picker, so a host answering with thousands of
# entries or novel-length labels only hurts its own owner — but bound it
# anyway rather than passing whatever arrives straight to a browser.
_MAX_MODELS = 200
_MAX_EFFORTS = 32
_MAX_TEXT = 200


def _normalize(models: object) -> list[dict]:
    """Keep the wire shape {id, label, hint, efforts} whatever the host
    sends; drop anything that can't be coerced into it."""
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
    return web.json_response({
        "models": MODEL_OPTIONS,
        "efforts": ALL_EFFORTS,
    })


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
        # Host offline, daemon error, or too slow — the static list still
        # answers the question. Ownership failures (404) propagate.
        log.info("host models unavailable, serving fallback",
                 extra={"host_id": host_id, "reason": exc.reason})
        models = []
    if not models:
        return web.json_response({
            "host_id": host_id,
            "models": MODEL_OPTIONS,
            "efforts": ALL_EFFORTS,
            "source": "fallback",
        })
    return web.json_response({
        "host_id": host_id,
        "models": models,
        "efforts": ALL_EFFORTS,
        "source": "host",
    })
