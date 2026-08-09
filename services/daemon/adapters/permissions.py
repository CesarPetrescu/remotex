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


_SANDBOX_TO_PERMISSIONS = {
    "dangerfullaccess": "full",
    "readonly": "readonly",
    "workspacewrite": "default",
}


def _permissions_from_codex(sandbox: object) -> str | None:
    """Inverse of ``_permissions_to_codex``: which UI button does this codex
    sandbox policy correspond to?

    Returns ``None`` when codex reports a policy we have no button for. The
    caller must leave the control alone in that case — showing "default" for a
    policy we cannot name is how the UI ended up claiming default permissions
    on a ``dangerFullAccess`` thread.
    """
    kind = sandbox.get("type") if isinstance(sandbox, dict) else sandbox
    if not isinstance(kind, str):
        return None
    key = kind.replace("-", "").replace("_", "").lower()
    return _SANDBOX_TO_PERMISSIONS.get(key)


def resolved_settings(payload: object) -> dict:
    """UI-shaped view of a thread's *actual* configuration.

    Accepts either a ``thread/start`` / ``thread/resume`` response or the
    ``threadSettings`` object from a ``thread/settings/updated`` notification.
    Those two spell the same things differently — ``reasoningEffort`` vs
    ``effort``, ``sandbox`` vs ``sandboxPolicy`` — verified by probing codex
    0.147.0 directly, so both spellings are read here.

    Only keys codex actually reported are present; absent means "unknown", not
    "default".
    """
    if not isinstance(payload, dict):
        return {}
    out: dict = {}

    model = payload.get("model")
    if isinstance(model, str) and model.strip():
        out["model"] = model.strip()

    effort = payload.get("reasoningEffort")
    if effort is None:
        effort = payload.get("effort")
    if isinstance(effort, str) and effort.strip():
        out["effort"] = effort.strip()

    sandbox = payload.get("sandbox")
    if sandbox is None:
        sandbox = payload.get("sandboxPolicy")
    perms = _permissions_from_codex(sandbox)
    if perms:
        out["permissions"] = perms

    policy = payload.get("approvalPolicy")
    if isinstance(policy, str) and policy.strip():
        out["approval_policy"] = policy.strip()

    profile = payload.get("activePermissionProfile")
    if isinstance(profile, dict):
        pid = profile.get("id") or profile.get("name")
        if isinstance(pid, str) and pid.strip():
            out["permission_profile"] = pid.strip()
    elif isinstance(profile, str) and profile.strip():
        out["permission_profile"] = profile.strip()

    return out


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
