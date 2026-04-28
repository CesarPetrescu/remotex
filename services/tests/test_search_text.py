"""Pure-function checks for the search text helpers."""
from __future__ import annotations

from relay.search.text import (
    _estimate_tokens,
    _is_stoplist,
    _normalize_space,
    _snippet,
    _str_or_none,
    _truncate_to_token_estimate,
    _xml_escape,
)


def test_stoplist_matches_acks():
    assert _is_stoplist("ok")
    assert _is_stoplist("Thanks")
    assert _is_stoplist("/compact")


def test_stoplist_lets_through_real_questions():
    assert not _is_stoplist("explain how the relay routes session frames")


def test_normalize_space_collapses_blank_lines_and_strips():
    assert _normalize_space("a\r\n\n\n\nb\n") == "a\n\nb"


def test_snippet_caps_long_text():
    long = "x" * 1000
    out = _snippet(long)
    assert out.endswith("...")
    assert len(out) <= 320


def test_estimate_tokens_is_positive_for_any_input():
    assert _estimate_tokens("hello there") >= 2
    assert _estimate_tokens("") == 1


def test_str_or_none_strips_and_returns_none_for_blank():
    assert _str_or_none("  hi  ") == "hi"
    assert _str_or_none("   ") is None
    assert _str_or_none(None) is None
    assert _str_or_none(123) is None  # type: ignore[arg-type]


def test_xml_escape_handles_special_chars():
    assert _xml_escape("a<b>&c") == "a&lt;b&gt;&amp;c"


def test_truncate_to_token_estimate_passes_short_text_through():
    text = "short text"
    assert _truncate_to_token_estimate(text, 100) == text
