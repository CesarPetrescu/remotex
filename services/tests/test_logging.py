"""JSON log formatter sanity check."""
from __future__ import annotations

import io
import json
import logging

from relay.logging import _JsonFormatter, audit, configure_json_logging


def _capture_one_record(name: str, msg: str, **extra) -> dict:
    formatter = _JsonFormatter()
    record = logging.LogRecord(
        name=name,
        level=logging.INFO,
        pathname=__file__,
        lineno=1,
        msg=msg,
        args=(),
        exc_info=None,
    )
    for k, v in extra.items():
        setattr(record, k, v)
    return json.loads(formatter.format(record))


def test_json_format_has_stable_fields():
    payload = _capture_one_record("relay", "hello", host_id="host_x")
    assert payload["msg"] == "hello"
    assert payload["logger"] == "relay"
    assert payload["level"] == "INFO"
    assert payload["host_id"] == "host_x"
    assert "ts" in payload


def test_audit_emits_via_audit_logger():
    buf = io.StringIO()
    configure_json_logging(level=logging.INFO)
    handler = logging.StreamHandler(buf)
    handler.setFormatter(_JsonFormatter())
    audit_logger = logging.getLogger("audit")
    audit_logger.addHandler(handler)
    try:
        audit("session.opened", session_id="sess_test", host_id="h_test")
    finally:
        audit_logger.removeHandler(handler)
    output = buf.getvalue().strip()
    payload = json.loads(output)
    assert payload["logger"] == "audit"
    assert payload["msg"] == "session.opened"
    assert payload["session_id"] == "sess_test"
