"""Exceptions raised by the search pipeline."""
from __future__ import annotations


class SearchUnavailable(RuntimeError):
    """Raised when search is called without storage or embedding config."""
