"""SPA index, fallback, and root-level static files."""
from __future__ import annotations

from aiohttp import web


# Known root-level static files shipped by the Vite build (apps/web/dist)
# and by the legacy services/web tree. Served through the same handler so
# the relay doesn't need a separate route per filename or a wildcard match
# that would also swallow /api/* and /ws/* paths.
ROOT_STATIC_FILES = (
    "favicon.ico",
    "favicon-32.png",
    "favicon-192.png",
    "apple-touch-icon.png",
    "site.webmanifest",
    "robots.txt",
)


async def index(request: web.Request) -> web.Response:
    root = request.app["static_root"]
    return web.FileResponse(root / "index.html")


async def spa_fallback(request: web.Request) -> web.Response:
    # Any GET that didn't match an API/WS/asset route falls through to the
    # SPA. Lets the client-side router handle URLs like `/search?q=foo`,
    # `/host/abc`, or `/host/abc/files` without the server needing to know
    # about every screen. Paths with an extension (e.g. .png, .js) are not
    # served from here — they'd be served by the specific static routes
    # already registered, and if missing they should 404 rather than
    # serving HTML for a missing image.
    path = request.match_info.get("tail", "")
    if "." in path.rsplit("/", 1)[-1]:
        raise web.HTTPNotFound()
    root = request.app["static_root"]
    return web.FileResponse(root / "index.html")


def make_root_static(filename: str):
    async def handler(request: web.Request) -> web.FileResponse:
        root = request.app["static_root"]
        path = root / filename
        if not path.is_file():
            raise web.HTTPNotFound()
        return web.FileResponse(path)
    return handler
