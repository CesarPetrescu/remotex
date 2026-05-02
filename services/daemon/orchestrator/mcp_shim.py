#!/usr/bin/env python3
"""
Stdio MCP server that codex (the brain) spawns as a child process. It
relays Model Context Protocol calls to a Unix socket owned by the
remotex daemon, where the OrchestratorRuntime answers them.

Why this exists
---------------
Codex's app-server lets you inject `mcp_servers` per session via the
`thread/start.config` map. We use that to register a single server
named "orchestrator" whose command points at this script. When codex
calls one of our exposed tools (submit_plan, spawn_step, …) the call
arrives here, gets forwarded over the Unix socket to the daemon, and
the daemon's response is shipped back to codex.

The shim itself is intentionally tiny — the runtime owns all state
(plan DAG, child sessions, progress events). This file only handles
the MCP wire protocol + socket transport.

Wire shape over the socket (per request, NDJSON):
  request : {"id": <int>, "method": "<tool_name>", "params": {...}}
  response: {"id": <int>, "result": {...}}   OR
            {"id": <int>, "error": "<message>"}
"""
from __future__ import annotations

import asyncio
import json
import os
import sys
import traceback


PROTOCOL_VERSION = "2024-11-05"
SERVER_NAME = "remotex-orchestrator"
SERVER_VERSION = "0.1"

# Tools we advertise to codex. Schema is a minimal JSON Schema; codex
# uses it for validation + autocomplete inside the brain. Keep aligned
# with the prompts in services/daemon/orchestrator/prompts.py.
TOOLS = [
    {
        "name": "submit_plan",
        "description": (
            "Replace the orchestration plan with a list of subtask "
            "steps. Each step is delegated to a fresh child codex agent "
            "later via spawn_step. Validates step ids unique, deps "
            "acyclic. Call this once at the start; later calls REPLACE "
            "the plan and cancel any in-flight children."
        ),
        "inputSchema": {
            "type": "object",
            "required": ["steps"],
            "properties": {
                "steps": {
                    "type": "array",
                    "minItems": 1,
                    "items": {
                        "type": "object",
                        "required": ["step_id", "prompt"],
                        "properties": {
                            "step_id": {"type": "string"},
                            "title": {"type": "string"},
                            "prompt": {"type": "string"},
                            "deps": {
                                "type": "array",
                                "items": {"type": "string"},
                            },
                        },
                    },
                },
            },
        },
    },
    {
        "name": "spawn_step",
        "description": (
            "Start a child codex agent for the named step. Errors if "
            "deps are unmet or step is already running/done."
        ),
        "inputSchema": {
            "type": "object",
            "required": ["step_id"],
            "properties": {"step_id": {"type": "string"}},
        },
    },
    {
        "name": "await_steps",
        "description": (
            "Block until every named step reaches a terminal state "
            "(completed/failed/cancelled). Returns each step's status "
            "and the child agent's final summary."
        ),
        "inputSchema": {
            "type": "object",
            "required": ["step_ids"],
            "properties": {
                "step_ids": {
                    "type": "array",
                    "minItems": 1,
                    "items": {"type": "string"},
                },
            },
        },
    },
    {
        "name": "cancel_step",
        "description": "Stop a running step's child agent.",
        "inputSchema": {
            "type": "object",
            "required": ["step_id"],
            "properties": {"step_id": {"type": "string"}},
        },
    },
    {
        "name": "list_steps",
        "description": "Return the current plan with each step's status.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "finish",
        "description": (
            "Mark the orchestrator session complete with a final "
            "summary. After calling, no more tool calls will be made."
        ),
        "inputSchema": {
            "type": "object",
            "required": ["summary"],
            "properties": {"summary": {"type": "string"}},
        },
    },
]


async def _bridge_call(socket_path: str, method: str, params: dict) -> dict:
    """One round-trip to the daemon over the Unix socket. Returns the
    parsed `result` dict on success; raises RuntimeError on bridge errors."""
    reader, writer = await asyncio.open_unix_connection(socket_path)
    try:
        req = json.dumps({"id": 1, "method": method, "params": params}) + "\n"
        writer.write(req.encode())
        await writer.drain()
        line = await reader.readline()
        if not line:
            raise RuntimeError("orchestrator bridge closed before reply")
        msg = json.loads(line)
        if "error" in msg:
            raise RuntimeError(str(msg["error"]))
        return msg.get("result") or {}
    finally:
        try:
            writer.close()
            await writer.wait_closed()
        except Exception:  # noqa: BLE001
            pass


def _send_response(msg: dict) -> None:
    sys.stdout.write(json.dumps(msg) + "\n")
    sys.stdout.flush()


async def _handle_request(socket_path: str, msg: dict) -> dict | None:
    """Map an inbound JSON-RPC request to its response. Returns None for
    notifications (no reply expected)."""
    rid = msg.get("id")
    method = msg.get("method")
    params = msg.get("params") or {}

    # --- core MCP handshake ---------------------------------------------
    if method == "initialize":
        return {
            "jsonrpc": "2.0",
            "id": rid,
            "result": {
                "protocolVersion": PROTOCOL_VERSION,
                "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
                "capabilities": {"tools": {}},
            },
        }
    if method == "notifications/initialized":
        return None
    if method == "tools/list":
        return {"jsonrpc": "2.0", "id": rid, "result": {"tools": TOOLS}}

    # --- our tools -------------------------------------------------------
    if method == "tools/call":
        name = params.get("name")
        args = params.get("arguments") or {}
        try:
            result = await _bridge_call(socket_path, name, args)
            text = json.dumps(result, indent=2, sort_keys=True)
            return {
                "jsonrpc": "2.0",
                "id": rid,
                "result": {
                    "content": [{"type": "text", "text": text}],
                    "isError": False,
                },
            }
        except Exception as exc:  # noqa: BLE001
            return {
                "jsonrpc": "2.0",
                "id": rid,
                "result": {
                    "content": [{"type": "text", "text": f"error: {exc}"}],
                    "isError": True,
                },
            }

    # --- ping / unknown --------------------------------------------------
    if method == "ping":
        return {"jsonrpc": "2.0", "id": rid, "result": {}}
    if rid is None:
        return None  # ignore unknown notifications
    return {
        "jsonrpc": "2.0",
        "id": rid,
        "error": {"code": -32601, "message": f"method not found: {method}"},
    }


async def main() -> int:
    if len(sys.argv) < 2:
        sys.stderr.write("usage: mcp_shim.py <unix_socket_path>\n")
        return 2
    socket_path = sys.argv[1]

    loop = asyncio.get_running_loop()
    reader = asyncio.StreamReader()
    protocol = asyncio.StreamReaderProtocol(reader)
    await loop.connect_read_pipe(lambda: protocol, sys.stdin)

    while True:
        line = await reader.readline()
        if not line:
            return 0
        try:
            msg = json.loads(line.decode("utf-8", errors="replace"))
        except json.JSONDecodeError:
            continue
        try:
            reply = await _handle_request(socket_path, msg)
        except Exception:  # noqa: BLE001
            sys.stderr.write(traceback.format_exc())
            reply = {
                "jsonrpc": "2.0",
                "id": msg.get("id"),
                "error": {"code": -32603, "message": "shim internal error"},
            }
        if reply is not None:
            _send_response(reply)


if __name__ == "__main__":
    try:
        sys.exit(asyncio.run(main()))
    except KeyboardInterrupt:
        sys.exit(0)
