"""UI permissions and image MIME helpers."""
from __future__ import annotations


def _permissions_to_codex(perms: str, cwd: str) -> tuple[dict | None, str | None]:
    """Map UI-level permissions buttons to codex sandboxPolicy / approvalPolicy."""
    perms = perms.lower()
    if perms in ("full", "full-access", "dangerfullaccess"):
        return {"type": "dangerFullAccess"}, "never"
    if perms in ("readonly", "read-only"):
        return {
            "type": "readOnly",
            "access": {"type": "fullAccess"},
            "networkAccess": False,
        }, "on-request"
    # Default: write inside cwd, ask for anything else.
    return {
        "type": "workspaceWrite",
        "writableRoots": [cwd] if cwd else [],
        "readOnlyAccess": {"type": "fullAccess"},
        "networkAccess": False,
        "excludeTmpdirEnvVar": False,
        "excludeSlashTmp": False,
    }, "on-request"


def _image_suffix(mime: str | None) -> str:
    if not mime or not isinstance(mime, str):
        return ".png"
    m = mime.lower()
    return {
        "image/png": ".png",
        "image/jpeg": ".jpg",
        "image/jpg": ".jpg",
        "image/webp": ".webp",
        "image/gif": ".gif",
        "image/heic": ".heic",
    }.get(m, ".png")
