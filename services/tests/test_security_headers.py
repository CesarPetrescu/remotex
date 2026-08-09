"""Production browser response headers."""
from __future__ import annotations

import pytest
from aiohttp import web

from relay.middleware import add_security_headers


@pytest.mark.asyncio
async def test_security_headers_cover_success_errors_and_api_data(aiohttp_client):
    app = web.Application()
    app.on_response_prepare.append(add_security_headers)

    async def index(request: web.Request) -> web.Response:
        return web.Response(text="ok")

    async def private(request: web.Request) -> web.Response:
        return web.json_response({"ok": True})

    async def websocket(request: web.Request) -> web.WebSocketResponse:
        response = web.WebSocketResponse()
        await response.prepare(request)
        async for _ in response:
            pass
        return response

    app.router.add_get("/", index)
    app.router.add_get("/api/private", private)
    app.router.add_get("/ws", websocket)
    client = await aiohttp_client(app)

    for path, status in (("/", 200), ("/missing", 404)):
        response = await client.get(path)
        assert response.status == status
        assert response.headers["X-Content-Type-Options"] == "nosniff"
        assert response.headers["X-Frame-Options"] == "DENY"
        assert response.headers["Referrer-Policy"] == "no-referrer"
        assert response.headers["Strict-Transport-Security"] == "max-age=31536000"
        assert response.headers.get("Server") is None
        policy = response.headers["Content-Security-Policy"]
        assert "script-src 'self'" in policy
        assert "frame-ancestors 'none'" in policy

    response = await client.get("/api/private")
    assert response.status == 200
    assert response.headers["Cache-Control"] == "no-store"

    socket = await client.ws_connect("/ws")
    assert socket._response.headers["X-Frame-Options"] == "DENY"
    assert socket._response.headers.get("Server") is None
    await socket.close()
