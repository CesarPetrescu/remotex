"""Transfer size ceilings shared with the relay.

The relay reads the same ``REMOTEX_MAX_FILE_BYTES`` env var and derives
the same websocket message ceiling, so both ends of a file transfer
agree on what fits. Anything larger must be answered with an explicit
error frame — an oversized websocket message is a silent socket kill,
which takes every session on the host down with it.
"""
from __future__ import annotations

import os

DEFAULT_MAX_FILE_BYTES = 25 * 1024 * 1024


def _env_bytes(name: str, default: int) -> int:
    raw = (os.environ.get(name) or "").strip()
    if not raw:
        return default
    try:
        value = int(raw)
    except ValueError:
        return default
    return value if value > 0 else default


MAX_FILE_BYTES = _env_bytes("REMOTEX_MAX_FILE_BYTES", DEFAULT_MAX_FILE_BYTES)

# base64 inflates by 4/3 (rounded up); the JSON envelope around it needs
# slack of its own.
WS_MAX_MSG_SIZE = -(-MAX_FILE_BYTES * 4 // 3) + 4 * 1024 * 1024
