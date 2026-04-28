"""Query-side helpers: filters, RRF fusion, hit shaping."""
from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any

from .text import _snippet


_CHUNK_COLUMNS = (
    "id, host_id, session_id, thread_id, turn_id, chunk_kind, role, "
    "snippet, text, cwd, model, created_at"
)

_RRF_K = 60  # standard RRF constant; higher = flatter score distribution.
_PHRASE_RE = re.compile(r'"([^"]{2,})"')


def _field(row: Any, key: str) -> Any:
    try:
        return row[key]
    except (KeyError, TypeError):
        return getattr(row, key, None)


@dataclass(frozen=True)
class _Filters:
    owner_token: str
    host_id: str | None
    thread_id: str | None
    session_id: str | None
    role: str | None
    kind: str | None

    def build(self, *, start: int, extra: str | None = None) -> tuple[str, list[Any]]:
        clauses: list[str] = []
        params: list[Any] = []
        idx = start

        def add(column: str, value: Any) -> None:
            nonlocal idx
            clauses.append(f"{column} = ${idx}")
            params.append(value)
            idx += 1

        add("owner_token", self.owner_token)
        if self.host_id:
            add("host_id", self.host_id)
        if self.thread_id:
            add("thread_id", self.thread_id)
        if self.session_id:
            add("session_id", self.session_id)
        if self.role:
            add("role", self.role)
        if self.kind:
            add("chunk_kind", self.kind)
        if extra:
            clauses.append(extra)
        return " AND ".join(clauses), params


def _row_to_hit(row: Any, signal: str, *, highlight: str | None = None) -> dict[str, Any]:
    score = row["score"]
    return {
        "id": row["id"],
        "host_id": row["host_id"],
        "session_id": row["session_id"],
        "thread_id": row["thread_id"],
        "turn_id": row["turn_id"],
        "kind": row["chunk_kind"],
        "role": row["role"],
        "snippet": row["snippet"],
        "text": row["text"],
        "cwd": row["cwd"],
        "model": row["model"],
        "created_at": row["created_at"],
        "score": float(score) if score is not None else 0.0,
        "signal": signal,
        "highlight": highlight,
    }


def _rrf_fuse(
    per_signal: dict[str, list[dict[str, Any]]],
    limit: int,
    *,
    boost: dict[str, float] | None = None,
) -> list[dict[str, Any]]:
    """Reciprocal Rank Fusion across ranked lists.

    For each chunk id we sum 1/(k+rank) over every signal it appears in,
    optionally scaled by a per-signal boost (so `exact` hits outrank pure
    semantic matches on the same phrase).
    """
    boost = boost or {}
    fused: dict[str, dict[str, Any]] = {}
    for signal, hits in per_signal.items():
        weight = boost.get(signal, 1.0)
        for rank, hit in enumerate(hits):
            key = hit["id"]
            contribution = weight / (_RRF_K + rank + 1)
            if key not in fused:
                record = dict(hit)
                record["score"] = contribution
                record["signals"] = [signal]
                record["signal_scores"] = {signal: hit["score"]}
                # keep whichever highlight arrives first (prefer exact > bm25)
                fused[key] = record
            else:
                record = fused[key]
                record["score"] += contribution
                if signal not in record["signals"]:
                    record["signals"].append(signal)
                record["signal_scores"][signal] = hit["score"]
                if not record.get("highlight") and hit.get("highlight"):
                    record["highlight"] = hit["highlight"]
    ranked = sorted(fused.values(), key=lambda r: r["score"], reverse=True)
    return ranked[:limit]


def _wrap(results: list[dict[str, Any]], mode: str, signals: list[str]) -> dict[str, Any]:
    return {"results": results, "mode": mode, "signals": signals}


def _extract_phrases(query: str) -> list[str]:
    """Pull out quoted exact-match phrases from a user query."""
    return [m.group(1).strip() for m in _PHRASE_RE.finditer(query) if m.group(1).strip()]


def _exact_highlight(needle: str):
    pattern = re.compile(re.escape(needle), re.IGNORECASE)

    def render(text: str) -> str:
        m = pattern.search(text)
        if not m:
            return _snippet(text)
        start = max(0, m.start() - 60)
        end = min(len(text), m.end() + 60)
        fragment = text[start:end]
        highlighted = pattern.sub(lambda mm: f"«{mm.group(0)}»", fragment)
        prefix = "…" if start > 0 else ""
        suffix = "…" if end < len(text) else ""
        return prefix + highlighted.strip() + suffix

    return render
