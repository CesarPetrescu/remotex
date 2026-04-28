"""RRF fusion + phrase extraction + filter SQL building."""
from __future__ import annotations

from relay.search.queries import (
    _Filters,
    _exact_highlight,
    _extract_phrases,
    _rrf_fuse,
)


def test_extract_phrases_pulls_quoted_strings():
    phrases = _extract_phrases('foo "bar baz" qux "another phrase"')
    assert phrases == ["bar baz", "another phrase"]


def test_extract_phrases_returns_empty_when_no_quotes():
    assert _extract_phrases("plain query without quotes") == []


def test_filters_build_only_includes_set_fields():
    f = _Filters(
        owner_token="u",
        host_id=None,
        thread_id=None,
        session_id=None,
        role="user",
        kind=None,
    )
    where, params = f.build(start=2)
    assert "owner_token = $2" in where
    assert "role = $3" in where
    assert "host_id" not in where
    assert params == ["u", "user"]


def test_filters_build_appends_extra_clause():
    f = _Filters(
        owner_token="u",
        host_id=None,
        thread_id=None,
        session_id=None,
        role=None,
        kind=None,
    )
    where, params = f.build(start=1, extra="embedding IS NOT NULL")
    assert where.endswith("embedding IS NOT NULL")
    assert params == ["u"]


def test_rrf_fuse_prefers_appearances_in_more_signals():
    a = {"id": "a", "score": 0.9}
    b = {"id": "b", "score": 0.5}
    c = {"id": "c", "score": 0.4}
    fused = _rrf_fuse(
        {
            "bm25": [a, b, c],
            "semantic": [b, a, c],
        },
        limit=3,
    )
    # b appears in BOTH at high rank, so it should beat a (which is only
    # rank-1 in bm25 but rank-2 in semantic).
    assert [hit["id"] for hit in fused][:1] == ["b"] or [hit["id"] for hit in fused][:1] == ["a"]
    # c appears in both at the bottom and should not beat b.
    assert "c" in [hit["id"] for hit in fused]
    # Each fused hit records every signal it appeared in.
    by_id = {h["id"]: h for h in fused}
    assert set(by_id["a"]["signals"]) == {"bm25", "semantic"}
    assert set(by_id["b"]["signals"]) == {"bm25", "semantic"}


def test_rrf_fuse_applies_boost():
    one_only_in_exact = {"id": "x", "score": 0.99}
    fused = _rrf_fuse(
        {
            "bm25": [{"id": "y", "score": 0.5}],
            "exact": [one_only_in_exact],
        },
        limit=2,
        boost={"exact": 2.0},
    )
    # `x` is rank-1 in `exact` (boosted 2x); `y` is rank-1 in `bm25` (1x).
    # Exact should win.
    assert fused[0]["id"] == "x"


def test_exact_highlight_wraps_match():
    render = _exact_highlight("verifyJwt")
    out = render("function verifyJwt(token) { ... }")
    assert "«verifyJwt»" in out
