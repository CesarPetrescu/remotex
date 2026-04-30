"""Filesystem proxy: forward fs/* requests to the daemon."""
from __future__ import annotations

import asyncio
import base64
import uuid

from aiohttp import web

from ..auth import require_user
from ..hub import Hub
from ..store import Store


# 50 MB cap on read/write — same as the daemon-side guard. Anything
# larger needs explicit chunking, which is out of scope for v1.
_MAX_FILE_BYTES = 50 * 1024 * 1024


async def _await_daemon_request(
    request: web.Request, host_id: str, frame: dict, *, timeout: float = 30.0,
) -> dict:
    """Common helper: ownership check, build a request_id, send to the
    daemon, await the response future, return the parsed payload.
    Raises HTTP errors directly so handlers stay flat."""
    user = await require_user(request)
    store: Store = request.app["store"]
    hub: Hub = request.app["hub"]
    if await store.host_owner(host_id) != user["token"]:
        raise web.HTTPNotFound(reason="host not found")
    daemon_ws = hub.daemon_for(host_id)
    if daemon_ws is None or daemon_ws.closed:
        raise web.HTTPBadGateway(reason="host offline")
    req_id = f"req_{uuid.uuid4().hex[:12]}"
    loop = asyncio.get_running_loop()
    fut: asyncio.Future = loop.create_future()
    hub.pending_admin[req_id] = fut
    try:
        await daemon_ws.send_json({**frame, "request_id": req_id})
        try:
            payload = await asyncio.wait_for(fut, timeout=timeout)
        except asyncio.TimeoutError as exc:
            raise web.HTTPGatewayTimeout(reason="daemon did not respond in time") from exc
    finally:
        hub.pending_admin.pop(req_id, None)
    if "error" in payload:
        raise web.HTTPBadGateway(reason=f"daemon error: {payload['error']}")
    return payload


async def list_host_fs(request: web.Request) -> web.Response:
    """Forward an fs/readDirectory to the daemon; return the entries as
    JSON. Clients use this to browse the daemon's filesystem before
    they pick a cwd for a new session."""
    user = await require_user(request)
    host_id = request.match_info["host_id"]
    store: Store = request.app["store"]
    hub: Hub = request.app["hub"]
    if await store.host_owner(host_id) != user["token"]:
        raise web.HTTPNotFound(reason="host not found")
    daemon_ws = hub.daemon_for(host_id)
    if daemon_ws is None or daemon_ws.closed:
        raise web.HTTPBadGateway(reason="host offline")
    path = request.query.get("path") or ""
    if not path:
        raise web.HTTPBadRequest(reason="path query parameter required")
    req_id = f"req_{uuid.uuid4().hex[:12]}"
    loop = asyncio.get_running_loop()
    fut: asyncio.Future = loop.create_future()
    hub.pending_admin[req_id] = fut
    try:
        await daemon_ws.send_json({
            "type": "fs-read-request",
            "request_id": req_id,
            "path": path,
        })
        try:
            payload = await asyncio.wait_for(fut, timeout=15.0)
        except asyncio.TimeoutError as exc:
            raise web.HTTPGatewayTimeout(reason="daemon did not respond in time") from exc
    finally:
        hub.pending_admin.pop(req_id, None)
    if "error" in payload:
        raise web.HTTPBadGateway(reason=f"daemon error: {payload['error']}")
    return web.json_response({
        "host_id": host_id,
        "path": payload.get("path", path),
        "entries": payload.get("entries", []),
    })


async def mkdir_host_fs(request: web.Request) -> web.Response:
    """Ask the daemon to create a new directory under a given parent.
    Body: {"path": "<parent>", "name": "<single-segment>"}."""
    user = await require_user(request)
    host_id = request.match_info["host_id"]
    store: Store = request.app["store"]
    hub: Hub = request.app["hub"]
    if await store.host_owner(host_id) != user["token"]:
        raise web.HTTPNotFound(reason="host not found")
    daemon_ws = hub.daemon_for(host_id)
    if daemon_ws is None or daemon_ws.closed:
        raise web.HTTPBadGateway(reason="host offline")
    try:
        body = await request.json()
    except Exception as exc:
        raise web.HTTPBadRequest(reason="invalid json") from exc
    parent = (body.get("path") or "").strip()
    name = (body.get("name") or "").strip()
    if not parent:
        raise web.HTTPBadRequest(reason="path is required")
    if not name or "/" in name or name in (".", ".."):
        raise web.HTTPBadRequest(reason="invalid folder name")
    req_id = f"req_{uuid.uuid4().hex[:12]}"
    loop = asyncio.get_running_loop()
    fut: asyncio.Future = loop.create_future()
    hub.pending_admin[req_id] = fut
    try:
        await daemon_ws.send_json({
            "type": "fs-mkdir-request",
            "request_id": req_id,
            "path": parent,
            "name": name,
        })
        try:
            payload = await asyncio.wait_for(fut, timeout=10.0)
        except asyncio.TimeoutError as exc:
            raise web.HTTPGatewayTimeout(reason="daemon did not respond in time") from exc
    finally:
        hub.pending_admin.pop(req_id, None)
    if "error" in payload:
        raise web.HTTPBadGateway(reason=f"daemon error: {payload['error']}")
    return web.json_response({
        "host_id": host_id,
        "path": payload.get("path"),
    }, status=201)


async def read_host_file(request: web.Request) -> web.Response:
    """Read a file from the daemon's filesystem. Streams base64 down
    so the client can either render it inline (text/images) or trigger
    a download (binary). Returns 413 if the file is larger than the
    50MB cap."""
    host_id = request.match_info["host_id"]
    path = (request.query.get("path") or "").strip()
    if not path:
        raise web.HTTPBadRequest(reason="path is required")
    payload = await _await_daemon_request(
        request,
        host_id,
        {"type": "fs-readfile-request", "path": path, "max_bytes": _MAX_FILE_BYTES},
    )
    return web.json_response({
        "host_id": host_id,
        "path": payload.get("path", path),
        "name": payload.get("name"),
        "mime": payload.get("mime"),
        "size": payload.get("size"),
        "base64": payload.get("base64"),
    })


async def delete_host_file(request: web.Request) -> web.Response:
    """Delete a single file. Refuses directories — the daemon enforces
    that too, but we surface a friendlier error here when we can."""
    host_id = request.match_info["host_id"]
    try:
        body = await request.json()
    except Exception as exc:
        raise web.HTTPBadRequest(reason="invalid json") from exc
    path = (body.get("path") or "").strip()
    if not path:
        raise web.HTTPBadRequest(reason="path is required")
    payload = await _await_daemon_request(
        request, host_id, {"type": "fs-delete-request", "path": path},
    )
    return web.json_response({
        "host_id": host_id,
        "path": payload.get("path", path),
    })


async def rename_host_file(request: web.Request) -> web.Response:
    """Move or rename a file. Refuses to overwrite an existing target."""
    host_id = request.match_info["host_id"]
    try:
        body = await request.json()
    except Exception as exc:
        raise web.HTTPBadRequest(reason="invalid json") from exc
    src = (body.get("from") or "").strip()
    dst = (body.get("to") or "").strip()
    if not src or not dst:
        raise web.HTTPBadRequest(reason="both 'from' and 'to' are required")
    payload = await _await_daemon_request(
        request, host_id, {"type": "fs-rename-request", "from": src, "to": dst},
    )
    return web.json_response({
        "host_id": host_id,
        "from": payload.get("from", src),
        "to": payload.get("to", dst),
    })


async def upload_host_file(request: web.Request) -> web.Response:
    """Multipart upload into a workspace path. Distinct from image
    attachment (which adds an image to the next turn's context) — this
    drops a real file onto the host's filesystem so the agent can read
    it like any other file."""
    host_id = request.match_info["host_id"]
    if not request.content_type or "multipart/" not in request.content_type:
        raise web.HTTPBadRequest(reason="multipart/form-data required")
    reader = await request.multipart()
    target_dir: str | None = None
    file_name: str | None = None
    file_bytes: bytes | None = None
    while True:
        part = await reader.next()
        if part is None:
            break
        if part.name == "path":
            target_dir = (await part.text()).strip()
        elif part.name == "file":
            file_name = part.filename or "upload.bin"
            chunks: list[bytes] = []
            total = 0
            while True:
                chunk = await part.read_chunk(64 * 1024)
                if not chunk:
                    break
                total += len(chunk)
                if total > _MAX_FILE_BYTES:
                    raise web.HTTPRequestEntityTooLarge(
                        max_size=_MAX_FILE_BYTES,
                        actual_size=total,
                        text=f"file exceeds {_MAX_FILE_BYTES} bytes",
                    )
                chunks.append(chunk)
            file_bytes = b"".join(chunks)
    if not target_dir:
        raise web.HTTPBadRequest(reason="path form field is required")
    if file_bytes is None or file_name is None:
        raise web.HTTPBadRequest(reason="file part is required")
    # Compose the target path on the daemon's filesystem.
    if target_dir.endswith("/"):
        target_path = target_dir + file_name
    else:
        target_path = f"{target_dir}/{file_name}"
    payload = await _await_daemon_request(
        request,
        host_id,
        {
            "type": "fs-write-request",
            "path": target_path,
            "base64": base64.b64encode(file_bytes).decode("ascii"),
        },
        timeout=120.0,
    )
    return web.json_response({
        "host_id": host_id,
        "path": payload.get("path", target_path),
        "size": payload.get("size", len(file_bytes)),
    }, status=201)
