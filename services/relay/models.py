"""Model picker fallback — **not** a model list.

The real list comes from the host: `GET /api/hosts/{host_id}/models` asks
that host's codex via `model/list`, which reports the models it actually
offers, each model's supported reasoning efforts, and which one is the
default. Nothing here is authoritative.

We deliberately do **not** ship model names. A hardcoded list goes stale
silently — this file used to advertise `codex 0.122` names while hosts were
serving `gpt-5.6-*`, and its effort list omitted `max` and `ultra`
entirely, so those were unselectable. See `Issues.md` I-002.

What's left is the single "let codex decide" entry, which needs no model
name, plus the effort names used to render the picker when a host is
offline and there is genuinely nothing to fetch.
"""
from __future__ import annotations

EFFORT_DEFAULT = ""
# Last-resort effort names, used only when no host is reachable. Codex
# reports the real per-model set in `supportedReasoningEfforts`; anything
# a model doesn't accept is rejected server-side.
ALL_EFFORTS = [EFFORT_DEFAULT, "low", "medium", "high", "xhigh"]

# The only entry we can honestly offer without asking a host. `id: ""`
# means "don't send a model override", so codex picks its own default.
MODEL_OPTIONS: list[dict] = [
    {"id": EFFORT_DEFAULT, "label": "default", "hint": "codex picks", "efforts": ALL_EFFORTS},
]
