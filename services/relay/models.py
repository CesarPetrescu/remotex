"""Single source of truth for the available Codex models.

Web and Android clients fetch this list from ``GET /api/models`` and fall
back to an embedded copy if the relay can't be reached. iOS doesn't keep
a local list — it just renders whatever the relay sent on the session.

Bumping a model is a one-line edit here. Never duplicate this list in a
client again.
"""
from __future__ import annotations

EFFORT_DEFAULT = ""
ALL_EFFORTS = [EFFORT_DEFAULT, "low", "medium", "high", "xhigh"]


# Models visible in `codex 0.122.0`. Effort list is per-model (codex
# rejects efforts a given model doesn't accept).
MODEL_OPTIONS: list[dict] = [
    {"id": "", "label": "default", "hint": "codex picks", "efforts": ALL_EFFORTS},
    {"id": "gpt-5.5", "label": "gpt-5.5", "hint": "newest frontier",
     "efforts": ALL_EFFORTS},
    {"id": "gpt-5.4", "label": "gpt-5.4", "hint": "frontier",
     "efforts": ALL_EFFORTS},
    {"id": "gpt-5.3-codex-spark", "label": "gpt-5.3 · codex spark", "hint": "ultra-fast coding",
     "efforts": ALL_EFFORTS},
]
