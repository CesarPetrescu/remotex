"""Structured JSON logging for the relay.

Each line emitted by ``logging`` becomes one JSON object with stable
fields: ``ts`` (ISO8601), ``level``, ``logger``, ``msg``, plus any
keyword fields passed via ``extra=``. Consumers (docker logs, Loki,
Grafana) can grep on ``logger=audit`` or ``level=ERROR`` deterministically.

Audit events (``logger=audit``) are emitted alongside ordinary logs.
They cover authentication, host attach/detach, and session lifecycle.
"""
from __future__ import annotations

import json
import logging
import logging.handlers
import time
from typing import Any


_RESERVED = {
    "name", "msg", "args", "levelname", "levelno", "pathname", "filename",
    "module", "exc_info", "exc_text", "stack_info", "lineno", "funcName",
    "created", "msecs", "relativeCreated", "thread", "threadName",
    "processName", "process", "taskName", "asctime", "message",
}


class _JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, Any] = {
            "ts": time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(record.created))
            + f".{int(record.msecs):03d}Z",
            "level": record.levelname,
            "logger": record.name,
            "msg": record.getMessage(),
        }
        if record.exc_info:
            payload["exc"] = self.formatException(record.exc_info)
        # Pass-through any extras attached via `log.info("x", extra={"foo": 1})`.
        for key, value in record.__dict__.items():
            if key in _RESERVED or key.startswith("_"):
                continue
            try:
                json.dumps(value)
            except TypeError:
                value = repr(value)
            payload[key] = value
        return json.dumps(payload, ensure_ascii=False)


def configure_json_logging(level: int = logging.INFO) -> None:
    """Replace the root logger handlers with one that emits JSON lines.

    Idempotent — safe to call from main() and pytest fixtures alike.
    """
    handler = logging.StreamHandler()
    handler.setFormatter(_JsonFormatter())
    root = logging.getLogger()
    root.handlers = [handler]
    root.setLevel(level)
    # aiohttp.access can spam with one line per request; keep it but at WARNING
    # so health checks don't flood. Override with --verbose if you need it.
    logging.getLogger("aiohttp.access").setLevel(logging.WARNING)


def audit(event: str, **fields: Any) -> None:
    """Emit one structured audit log line.

    ``event`` becomes the message; ``fields`` are passed through verbatim
    as extra keys on the JSON object.
    """
    logging.getLogger("audit").info(event, extra=fields)
