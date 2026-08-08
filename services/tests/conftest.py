"""Shared pytest config: make ``services/`` importable so tests can
``from relay…`` and ``from daemon…`` without setting PYTHONPATH."""
from __future__ import annotations

import sys
from pathlib import Path

import pytest

_SERVICES_ROOT = Path(__file__).resolve().parents[1]
if str(_SERVICES_ROOT) not in sys.path:
    sys.path.insert(0, str(_SERVICES_ROOT))


@pytest.fixture(autouse=True)
def _fresh_remote_rate_limit():
    """Every aiohttp test client dials from 127.0.0.1, so they all share the
    per-remote bucket. Reset it between tests so one test's traffic can't
    throttle the next one's."""
    from relay.middleware.rate_limit import _remote_buckets

    _remote_buckets.clear()
    yield
    _remote_buckets.clear()
