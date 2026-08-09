"""Browser security headers added just before aiohttp sends a response."""
from __future__ import annotations

from aiohttp import web


_CSP = (
    "default-src 'self'; "
    "base-uri 'none'; "
    "connect-src 'self' ws: wss:; "
    "font-src 'self' https://fonts.gstatic.com; "
    "form-action 'self'; "
    "frame-ancestors 'none'; "
    "frame-src 'none'; "
    "img-src 'self' data:; "
    "manifest-src 'self'; "
    "object-src 'none'; "
    "script-src 'self'; "
    "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com"
)

_HEADERS = {
    "Content-Security-Policy": _CSP,
    "Permissions-Policy": "camera=(), geolocation=(), microphone=()",
    "Referrer-Policy": "no-referrer",
    "Strict-Transport-Security": "max-age=31536000",
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
}


async def add_security_headers(
    request: web.Request,
    response: web.StreamResponse,
) -> None:
    """Apply to normal, error, static-file, and WebSocket responses."""
    for name, value in _HEADERS.items():
        response.headers.setdefault(name, value)
    if request.path.startswith("/api/"):
        response.headers["Cache-Control"] = "no-store"
    response.headers.pop("Server", None)
