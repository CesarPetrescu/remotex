"""Tiny line-oriented control protocol for the orchestrator brain.

The brain Codex emits commands inside its ``agentMessage`` text. We
chose plain-text delimiters over MCP / function-calling because it
works with any Codex version the daemon ships against, requires no
dependency on Codex MCP support, and is easy for a human to inspect in
the event stream.

Format per command, on its own line::

    @@ORCH <action> <json_args>

Recognized actions (validated downstream by the runtime):

- ``submit_plan``  ``{"steps": [{"step_id": str, "title": str, "prompt": str, "deps": [step_id]}]}``
- ``spawn_step``   ``{"step_id": str}``
- ``await_steps``  ``{"step_ids": [step_id]}``  (waits for those steps to terminate)
- ``cancel_step``  ``{"step_id": str}``
- ``finish``       ``{"summary": str}``

Multiple commands may appear in a single agent message; they're parsed
and executed in document order. ``await_steps`` is a barrier — anything
after it in the same turn runs only after the await completes.

Lines outside the prefix are ignored by the parser, so the brain can
still narrate to the user in prose."""
from __future__ import annotations

import json
import re
from dataclasses import dataclass

ORCH_TAG = "@@ORCH"

_LINE_RE = re.compile(r"^\s*@@ORCH\s+(?P<action>[a-z_]+)\s*(?P<rest>.*?)\s*$")


@dataclass
class OrchCommand:
    action: str
    args: dict
    raw: str


def parse_commands(text: str) -> tuple[list[OrchCommand], list[str]]:
    """Pull all ``@@ORCH`` commands from a message body.

    Returns (commands, errors). Errors are human-readable strings,
    surfaced back to the brain as a turn input so it can correct itself
    rather than silently producing nothing."""
    cmds: list[OrchCommand] = []
    errors: list[str] = []
    if not text:
        return cmds, errors
    for line in text.splitlines():
        m = _LINE_RE.match(line)
        if not m:
            continue
        action = m.group("action")
        rest = (m.group("rest") or "").strip()
        if not rest:
            args: dict = {}
        else:
            try:
                parsed = json.loads(rest)
            except json.JSONDecodeError as exc:
                errors.append(
                    f"failed to parse args for @@ORCH {action}: {exc}"
                    f" (raw: {rest[:120]})"
                )
                continue
            if not isinstance(parsed, dict):
                errors.append(
                    f"@@ORCH {action} args must be a JSON object, got {type(parsed).__name__}"
                )
                continue
            args = parsed
        cmds.append(OrchCommand(action=action, args=args, raw=line.strip()))
    return cmds, errors
