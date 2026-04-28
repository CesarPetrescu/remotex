"""GET /api/models — single source of truth for the model picker.

No auth required — the model list is the same for every user. Web and
Android clients fetch this once on first paint and cache it; if the
fetch fails they fall back to an embedded copy.
"""
from __future__ import annotations

from aiohttp import web

from ..models import ALL_EFFORTS, MODEL_OPTIONS


async def get_models(request: web.Request) -> web.Response:
    return web.json_response({
        "models": MODEL_OPTIONS,
        "efforts": ALL_EFFORTS,
    })
