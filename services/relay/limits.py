"""Transfer size ceilings — one env read, both transports.

``REMOTEX_MAX_FILE_BYTES`` is the single knob: it caps a file read/write
proxied through the relay. Everything else is derived from it, because a
file only ever reaches a client after being base64'd (4/3 inflation) into
a JSON envelope, and a ceiling the transport can't carry is a socket drop
rather than an error message.

The daemon reads the same env var for its own ``ws_connect`` limit; keep
the default in sync if you change it here.
"""
from __future__ import annotations

import os

DEFAULT_MAX_FILE_BYTES = 25 * 1024 * 1024


def _env_bytes(name: str, default: int) -> int:
    """A bad value must not stop the relay from starting — fall back."""
    raw = (os.environ.get(name) or "").strip()
    if not raw:
        return default
    try:
        value = int(raw)
    except ValueError:
        return default
    return value if value > 0 else default


# Largest file body the relay will proxy in either direction.
MAX_FILE_BYTES = _env_bytes("REMOTEX_MAX_FILE_BYTES", DEFAULT_MAX_FILE_BYTES)

# aiohttp Application body cap: the file plus room for multipart/JSON framing.
HTTP_MAX_BODY_BYTES = MAX_FILE_BYTES + 1024 * 1024

# WebSocket frame cap: base64 inflates by 4/3 (rounded up), plus slack for
# the JSON envelope. Must match services/daemon/limits.py.
WS_MAX_MSG_SIZE = -(-MAX_FILE_BYTES * 4 // 3) + 4 * 1024 * 1024
