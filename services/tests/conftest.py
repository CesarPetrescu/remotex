"""Shared pytest config: make ``services/`` importable so tests can
``from relay…`` and ``from daemon…`` without setting PYTHONPATH."""
from __future__ import annotations

import sys
from pathlib import Path

_SERVICES_ROOT = Path(__file__).resolve().parents[1]
if str(_SERVICES_ROOT) not in sys.path:
    sys.path.insert(0, str(_SERVICES_ROOT))
