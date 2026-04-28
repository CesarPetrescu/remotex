"""Search endpoints: config probe, sync search, NDJSON streaming, reindex."""
from __future__ import annotations

import json

from aiohttp import web

from ..auth import require_user
from ..search import SearchService, SearchUnavailable


async def search_config(request: web.Request) -> web.Response:
    await require_user(request)
    search: SearchService = request.app["search"]
    return web.json_response(search.config_payload())


def _parse_search_params(request: web.Request, user: dict) -> dict:
    query = (request.query.get("q") or "").strip()
    if not query:
        raise web.HTTPBadRequest(reason="q query parameter required")
    host_id = (request.query.get("host_id") or "").strip() or None
    rerank_param = (request.query.get("rerank") or "").strip().lower()
    if rerank_param in ("", "auto"):
        rerank: bool | None = None
    elif rerank_param in ("1", "true", "yes", "on"):
        rerank = True
    elif rerank_param in ("0", "false", "no", "off"):
        rerank = False
    else:
        raise web.HTTPBadRequest(reason="rerank must be auto/true/false")
    try:
        limit = int(request.query.get("limit") or 20)
    except ValueError as exc:
        raise web.HTTPBadRequest(reason="limit must be an integer") from exc
    return dict(
        owner_token=user["token"],
        query=query,
        limit=limit,
        host_id=host_id,
        thread_id=(request.query.get("thread_id") or "").strip() or None,
        session_id=(request.query.get("session_id") or "").strip() or None,
        role=(request.query.get("role") or "").strip() or None,
        kind=(request.query.get("kind") or "").strip() or None,
        mode=(request.query.get("mode") or "hybrid").strip().lower() or "hybrid",
        rerank=rerank,
    )


async def search_chats(request: web.Request) -> web.Response:
    user = await require_user(request)
    params = _parse_search_params(request, user)
    if params["host_id"] and (
        await request.app["store"].host_owner(params["host_id"]) != user["token"]
    ):
        raise web.HTTPNotFound(reason="host not found")
    search: SearchService = request.app["search"]
    try:
        payload = await search.search(**params)
    except SearchUnavailable as exc:
        raise web.HTTPServiceUnavailable(reason=str(exc)) from exc
    return web.json_response(payload)


async def search_chats_stream(request: web.Request) -> web.StreamResponse:
    user = await require_user(request)
    params = _parse_search_params(request, user)
    if params["host_id"] and (
        await request.app["store"].host_owner(params["host_id"]) != user["token"]
    ):
        raise web.HTTPNotFound(reason="host not found")
    search: SearchService = request.app["search"]

    response = web.StreamResponse(
        status=200,
        headers={
            "Content-Type": "application/x-ndjson",
            # Tell proxies not to buffer this stream — each event should land
            # at the browser the instant a signal finishes.
            "X-Accel-Buffering": "no",
            "Cache-Control": "no-cache",
        },
    )
    await response.prepare(request)

    async def emit(event: dict) -> None:
        await response.write(json.dumps(event).encode("utf-8") + b"\n")
        await response.drain()

    try:
        await search.search_stream(emit, **params)
    except SearchUnavailable as exc:
        await emit({"type": "error", "message": str(exc)})
    await response.write_eof()
    return response


async def reindex_search(request: web.Request) -> web.Response:
    user = await require_user(request)
    try:
        body = await request.json() if request.can_read_body else {}
    except json.JSONDecodeError:
        body = {}
    host_id = (body.get("host_id") or request.query.get("host_id") or "").strip() or None
    if host_id and await request.app["store"].host_owner(host_id) != user["token"]:
        raise web.HTTPNotFound(reason="host not found")
    search: SearchService = request.app["search"]
    try:
        chunks = await search.reindex(user["token"], host_id=host_id)
    except SearchUnavailable as exc:
        raise web.HTTPServiceUnavailable(reason=str(exc)) from exc
    return web.json_response({"chunks": chunks})
