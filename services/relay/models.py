"""Fallback list of Codex models.

Web and Android clients fetch ``GET /api/hosts/{host_id}/models``, which
asks the host's codex what it actually offers. This list is what the
relay serves when there is no host to ask (``GET /api/models``) or when
the host doesn't answer — so it should track the codex catalog, but it is
never the authority.

Bumping a model is a one-line edit here. Never duplicate this list in a
client again.
"""
from __future__ import annotations

EFFORT_DEFAULT = ""
ALL_EFFORTS = [EFFORT_DEFAULT, "low", "medium", "high", "xhigh"]
# gpt-5.6 accepts two deeper levels than the 5.x line does.
DEEP_EFFORTS = [*ALL_EFFORTS, "max", "ultra"]
MAX_EFFORTS = [*ALL_EFFORTS, "max"]


# Mirrors the bundled catalog in codex-rs/models-manager/models.json
# (slug / description / supported_reasoning_levels). Codex rejects an
# effort a given model doesn't accept, so the per-model lists matter.
MODEL_OPTIONS: list[dict] = [
    {"id": "", "label": "default", "hint": "codex picks", "efforts": ALL_EFFORTS},
    {"id": "gpt-5.6-sol", "label": "gpt-5.6 · sol", "hint": "latest frontier agentic coding",
     "efforts": DEEP_EFFORTS},
    {"id": "gpt-5.6-terra", "label": "gpt-5.6 · terra", "hint": "balanced everyday work",
     "efforts": DEEP_EFFORTS},
    {"id": "gpt-5.6-luna", "label": "gpt-5.6 · luna", "hint": "fast and affordable",
     "efforts": MAX_EFFORTS},
    {"id": "gpt-5.5", "label": "gpt-5.5", "hint": "frontier",
     "efforts": ALL_EFFORTS},
    {"id": "gpt-5.2", "label": "gpt-5.2", "hint": "long-running agents",
     "efforts": ALL_EFFORTS},
]
