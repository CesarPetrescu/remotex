"""UI permissions, approval policy, and image MIME helpers."""
from __future__ import annotations


_APPROVAL_POLICY_ALIASES = {
    "on_failure": "on-failure",
    "onfailure": "on-failure",
    "on_request": "on-request",
    "onrequest": "on-request",
    "unless_trusted": "untrusted",
    "unless-trusted": "untrusted",
    "unlesstrusted": "untrusted",
}
_APPROVAL_POLICIES = {"never", "on-request", "on-failure", "untrusted"}


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


def _approval_policy_to_codex(policy: object) -> str | dict | None:
    """Validate a UI-level approval policy for Codex app-server.

    Codex v2 accepts string policies plus a granular object. Remotex's
    current clients send strings, but accepting a granular dict keeps
    the adapter compatible with future callers without interpreting it.
    """
    if policy is None:
        return None
    if isinstance(policy, dict) and isinstance(policy.get("granular"), dict):
        return policy
    if not isinstance(policy, str):
        return None
    raw = policy.strip()
    if not raw:
        return None
    normalized = _APPROVAL_POLICY_ALIASES.get(raw.lower(), raw.lower())
    if normalized in _APPROVAL_POLICIES:
        return normalized
    return None


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
