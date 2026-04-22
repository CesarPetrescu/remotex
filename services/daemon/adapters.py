"""Session adapters that produce Codex-App-Server-shaped events.

Two adapters ship:

- ``MockCodexAdapter`` — deterministic scripted events, kept for tests
  and the no-API demo. The e2e test drives this.
- ``StdioCodexAdapter`` — spawns ``codex app-server`` and bridges real
  JSON-RPC frames. This is the default daemon mode now.

Protocol crib:
  initialize ──▶ result {userAgent, codexHome, platform*}
  initialized (notification, no response)
  thread/start ──▶ result {thread: {id, ...}, model, cwd, ...}
                   + thread/started notification
  turn/start   ──▶ result (with the turn id)
                   + turn/started, item/started, item/<type>/delta,
                     item/completed, turn/completed notifications

Item types observed: ``userMessage``, ``agentMessage``, ``agentReasoning``,
``commandExecution`` (tool call). We translate these to the snake_case
item_type strings the relay + web client already use.
"""
from __future__ import annotations

import asyncio
import base64
import json
import logging
import os
import shlex
import tempfile
import time
import uuid
from dataclasses import dataclass
from typing import AsyncIterator

log = logging.getLogger("daemon.adapters")


@dataclass
class SessionEvent:
    """Envelope kind wrapped by the daemon before shipping to the relay."""
    kind: str
    data: dict

    def to_frame(self, session_id: str) -> dict:
        return {
            "type": "session-event",
            "session_id": session_id,
            "event": {"kind": self.kind, "data": self.data, "ts": time.time()},
        }


class SessionAdapter:
    async def start(self) -> None: ...
    async def stop(self) -> None: ...
    async def handle(self, frame: dict) -> None: ...
    def events(self) -> AsyncIterator[SessionEvent]: ...


# ---------------------------------------------------------------------------
# Mock adapter — deterministic, no external dependencies
# ---------------------------------------------------------------------------

class MockCodexAdapter(SessionAdapter):
    """Simulates a Codex session.

    `turn-start` → emits a scripted sequence: reasoning, a tool call, a
    streamed agent message with deltas, and completion. This matches the
    item types documented in the real protocol (item/started,
    item/agentMessage/delta, item/completed) so the client UI can be
    exercised against real shapes.
    """

    def __init__(self) -> None:
        self._queue: asyncio.Queue[SessionEvent | None] = asyncio.Queue()
        self._tasks: list[asyncio.Task] = []
        self._running = True

    async def start(self) -> None:
        await self._queue.put(SessionEvent("session-started", {
            "model": "gpt-5-codex",
            "cwd": "/home/demo/repo",
            "sandbox": "workspace-write",
        }))

    async def stop(self) -> None:
        self._running = False
        for t in self._tasks:
            t.cancel()
        await self._queue.put(None)

    async def handle(self, frame: dict) -> None:
        if frame.get("type") == "turn-start":
            self._tasks.append(asyncio.create_task(self._run_turn(frame.get("input", ""))))

    async def _run_turn(self, user_input: str) -> None:
        turn_id = f"turn_{uuid.uuid4().hex[:8]}"
        await self._queue.put(SessionEvent("turn-started", {"turn_id": turn_id, "input": user_input}))

        reasoning_id = f"itm_{uuid.uuid4().hex[:8]}"
        await self._queue.put(SessionEvent("item-started", {
            "turn_id": turn_id, "item_id": reasoning_id, "item_type": "agent_reasoning",
        }))
        await asyncio.sleep(0.2)
        await self._queue.put(SessionEvent("item-completed", {
            "turn_id": turn_id, "item_id": reasoning_id, "item_type": "agent_reasoning",
            "text": "Plan: search the repo for the requested symbol, then read the top match.",
        }))

        tool_id = f"itm_{uuid.uuid4().hex[:8]}"
        await self._queue.put(SessionEvent("item-started", {
            "turn_id": turn_id, "item_id": tool_id, "item_type": "tool_call",
            "tool": "shell", "args": {"command": "rg -n 'verifyJwt' src/"},
        }))
        await asyncio.sleep(0.3)
        await self._queue.put(SessionEvent("item-completed", {
            "turn_id": turn_id, "item_id": tool_id, "item_type": "tool_call",
            "exit_code": 0, "output": "src/auth/verify.ts:12:export function verifyJwt(",
        }))

        msg_id = f"itm_{uuid.uuid4().hex[:8]}"
        await self._queue.put(SessionEvent("item-started", {
            "turn_id": turn_id, "item_id": msg_id, "item_type": "agent_message",
        }))
        chunks = _scripted_reply(user_input)
        for chunk in chunks:
            if not self._running:
                return
            await asyncio.sleep(0.05)
            await self._queue.put(SessionEvent("item-delta", {
                "turn_id": turn_id, "item_id": msg_id, "item_type": "agent_message",
                "delta": chunk,
            }))
        full = "".join(chunks)
        await self._queue.put(SessionEvent("item-completed", {
            "turn_id": turn_id, "item_id": msg_id, "item_type": "agent_message",
            "text": full,
        }))
        await self._queue.put(SessionEvent("turn-completed", {"turn_id": turn_id}))

    async def events(self) -> AsyncIterator[SessionEvent]:
        while True:
            ev = await self._queue.get()
            if ev is None:
                return
            yield ev


def _scripted_reply(user_input: str) -> list[str]:
    user_input = user_input.strip() or "your request"
    preface = f"Looked up matches for `{user_input}`. "
    body = (
        "Found the symbol in src/auth/verify.ts — it's exported from a single "
        "file, used by three callers. Extracting it into its own module is "
        "safe; I'd suggest src/auth/verify/index.ts so the import surface "
        "stays small."
    )
    text = preface + body
    size = 12
    return [text[i : i + size] for i in range(0, len(text), size)]


# ---------------------------------------------------------------------------
# Real stdio adapter — spawns `codex app-server` and bridges JSON-RPC
# ---------------------------------------------------------------------------

# codex item `type` (camelCase or bare) → our relay item_type (snake_case)
_ITEM_TYPE_MAP = {
    "agentMessage": "agent_message",
    "reasoning": "agent_reasoning",         # codex emits `type: "reasoning"`
    "agentReasoning": "agent_reasoning",    # older name, keep for compat
    "commandExecution": "tool_call",
    "fileChange": "file_change",
    "userMessage": "user_message",  # echoed; we drop these client-side
}


def _snake_item_type(codex_type: str) -> str:
    return _ITEM_TYPE_MAP.get(codex_type, codex_type)


class StdioCodexAdapter(SessionAdapter):
    """Bridges `codex app-server` stdio JSON-RPC onto relay SessionEvents.

    Lifetime = one relay session = one Codex thread. The adapter performs
    the initialize → initialized → thread/start (or thread/resume) on
    ``start()``, then stays resident; each client ``turn-start`` frame
    maps to one ``turn/start`` request and the resulting notification
    stream is forwarded until ``turn/completed`` arrives.
    """

    def __init__(
        self,
        codex_binary: str = "codex",
        default_cwd: str | None = None,
        resume_thread_id: str | None = None,
    ) -> None:
        self.binary = codex_binary
        self._cwd = default_cwd or os.path.expanduser("~")
        self._resume_thread_id = resume_thread_id
        self._proc: asyncio.subprocess.Process | None = None
        self._queue: asyncio.Queue[SessionEvent | None] = asyncio.Queue()
        self._reader_task: asyncio.Task | None = None
        self._stderr_task: asyncio.Task | None = None
        self._send_lock = asyncio.Lock()
        self._pending: dict[int, asyncio.Future] = {}
        self._next_id = 0
        self._thread_id: str | None = None
        # Live turn id — set on turn/started, cleared on turn/completed.
        # Needed because turn/interrupt wants turnId, not threadId.
        self._turn_id: str | None = None
        # Temp files we wrote for localImage inputs on the current turn;
        # cleared on turn-completed.
        self._turn_tmp_files: list[str] = []
        # Approval requests from codex are JSON-RPC requests (not notifs).
        # We record their rpc ids so we can respond after the client answers.
        self._pending_approvals: dict[str, int] = {}
        # Slash-command state: if the user sends `/plan`, the next turn
        # uses collaborationMode; `/default` clears it.
        self._next_collab_mode: dict | None = None

    # --- lifecycle -------------------------------------------------------

    async def start(self) -> None:
        cmd = shlex.split(self.binary) + ["app-server"]
        log.info("spawning %s (cwd=%s)", " ".join(cmd), self._cwd)
        self._proc = await asyncio.create_subprocess_exec(
            *cmd,
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        self._reader_task = asyncio.create_task(self._read_loop())
        self._stderr_task = asyncio.create_task(self._drain_stderr())

        init = await self._request("initialize", {
            "clientInfo": {
                "name": "remotex-daemon",
                "title": "Remotex",
                "version": "0.1.0",
            },
            "capabilities": {"experimentalApi": True},
        })
        model_hint = init.get("userAgent", "codex app-server")
        await self._notify("initialized", {})

        if self._resume_thread_id:
            # Reattach to an existing thread from disk so prior turns are
            # in codex's context window.
            resp = await self._request("thread/resume", {
                "threadId": self._resume_thread_id,
            })
            thread = {"thread": resp.get("thread", {"id": self._resume_thread_id}),
                      "model": resp.get("model"),
                      "cwd": resp.get("cwd", self._cwd)}
        else:
            thread = await self._request("thread/start", {
                "cwd": self._cwd,
                "ephemeral": False,
            })
        self._thread_id = thread["thread"].get("id", self._resume_thread_id)
        # Enable streamed reasoning summaries for every turn by default.
        self._reasoning_summary = "auto"
        # Replay the prior turn history for resumed threads so the client
        # shows what was said before. Done after session-started so the UI
        # can tell "live" from "replayed".
        if self._resume_thread_id:
            try:
                await self._replay_history()
            except Exception as exc:  # noqa: BLE001
                log.warning("history replay failed: %s", exc)
        await self._queue.put(SessionEvent("session-started", {
            "model": thread.get("model") or model_hint,
            "cwd": thread.get("cwd", self._cwd),
            "thread_id": self._thread_id,
            "transport": "stdio",
        }))

    async def stop(self) -> None:
        if self._thread_id and self._proc and self._proc.returncode is None:
            # Best-effort archive; ignore failures during shutdown.
            try:
                await asyncio.wait_for(
                    self._request("thread/archive", {"threadId": self._thread_id}),
                    timeout=1.0,
                )
            except Exception:  # noqa: BLE001
                pass
        if self._proc and self._proc.returncode is None:
            try:
                self._proc.terminate()
            except ProcessLookupError:
                pass
            try:
                await asyncio.wait_for(self._proc.wait(), timeout=3.0)
            except asyncio.TimeoutError:
                self._proc.kill()
        for task in (self._reader_task, self._stderr_task):
            if task:
                task.cancel()
        # Fail any outstanding requests.
        for fut in self._pending.values():
            if not fut.done():
                fut.set_exception(RuntimeError("codex app-server exited"))
        self._pending.clear()
        await self._queue.put(None)

    # --- inbound frames from the relay ----------------------------------

    async def handle(self, frame: dict) -> None:
        ftype = frame.get("type")
        if ftype == "approval-response":
            await self._resolve_approval(frame)
            return
        if ftype == "slash-command":
            await self._handle_slash(frame)
            return
        if ftype == "turn-start":
            if not self._thread_id:
                log.warning("turn-start before thread ready; dropping")
                return
            text = frame.get("input", "")
            codex_input: list[dict] = []
            if text:
                codex_input.append({
                    "type": "text",
                    "text": text,
                    "text_elements": [],
                })
            # Optional image attachments — each `{data: base64, mime: ...}`
            # becomes a temp file that codex reads as a localImage input.
            images = frame.get("images")
            if isinstance(images, list):
                for img in images:
                    if not isinstance(img, dict):
                        continue
                    data = img.get("data")
                    if not isinstance(data, str) or not data:
                        continue
                    suffix = _image_suffix(img.get("mime"))
                    try:
                        raw = base64.b64decode(data, validate=False)
                    except Exception as exc:  # noqa: BLE001
                        log.warning("failed to decode image: %s", exc)
                        continue
                    fd, path = tempfile.mkstemp(prefix="remotex-img-", suffix=suffix)
                    try:
                        with os.fdopen(fd, "wb") as fh:
                            fh.write(raw)
                    except Exception as exc:  # noqa: BLE001
                        log.warning("failed to write image temp: %s", exc)
                        continue
                    self._turn_tmp_files.append(path)
                    codex_input.append({"type": "localImage", "path": path})
            if not codex_input:
                # Shouldn't happen but codex rejects empty input.
                codex_input.append({
                    "type": "text",
                    "text": "",
                    "text_elements": [],
                })
            params: dict = {
                "threadId": self._thread_id,
                "input": codex_input,
                # Ask codex to stream reasoning summaries as deltas rather
                # than only emitting them with item/completed at the end.
                "summary": self._reasoning_summary,
            }
            # Optional per-turn overrides. Codex applies these to this turn
            # and every subsequent turn on the thread.
            model = frame.get("model")
            if isinstance(model, str) and model.strip():
                params["model"] = model.strip()
            effort = frame.get("effort")
            if isinstance(effort, str) and effort.strip():
                params["effort"] = effort.strip()
            perms = frame.get("permissions")
            if isinstance(perms, str) and perms:
                sandbox, approval = _permissions_to_codex(perms, self._cwd)
                if sandbox:
                    params["sandboxPolicy"] = sandbox
                if approval:
                    params["approvalPolicy"] = approval
            if self._next_collab_mode is not None:
                params["collaborationMode"] = self._next_collab_mode
            try:
                await self._request("turn/start", params)
            except Exception as exc:  # noqa: BLE001
                log.exception("turn/start failed: %s", exc)
                await self._queue.put(SessionEvent("turn-completed", {
                    "error": str(exc),
                }))
        elif ftype == "turn-interrupt":
            if not self._turn_id:
                log.info("turn-interrupt but no turn in flight; ignoring")
                return
            try:
                await self._request("turn/interrupt", {"turnId": self._turn_id})
            except Exception as exc:  # noqa: BLE001
                log.warning("turn/interrupt failed: %s", exc)
        elif ftype == "approval":
            # TODO: wire elicitation / approval response. The protocol
            # supports thread/increment_elicitation + resolve variants;
            # leaving this as a follow-up once the Codex docs settle.
            log.info("approval frame received; resolver not yet wired: %s", frame)

    async def events(self) -> AsyncIterator[SessionEvent]:
        while True:
            ev = await self._queue.get()
            if ev is None:
                return
            yield ev

    # --- slash commands + approvals ------------------------------------

    async def _handle_slash(self, frame: dict) -> None:
        command = (frame.get("command") or "").lower().strip()
        if command == "compact":
            try:
                await self._request("thread/compact/start", {"threadId": self._thread_id})
            except Exception as exc:  # noqa: BLE001
                log.warning("thread/compact/start failed: %s", exc)
                await self._queue.put(SessionEvent("slash-ack", {
                    "command": command,
                    "ok": False,
                    "error": str(exc),
                }))
                return
            await self._queue.put(SessionEvent("slash-ack", {"command": command, "ok": True}))
        elif command in ("plan", "default"):
            if command == "plan":
                self._next_collab_mode = {
                    "mode": "plan",
                    # Null developer_instructions means "use mode's built-in".
                    "settings": {
                        "model": "",
                        "reasoning_effort": None,
                        "developer_instructions": None,
                    },
                }
            else:
                self._next_collab_mode = None
            await self._queue.put(SessionEvent("slash-ack", {
                "command": command,
                "ok": True,
                "message": (
                    "next turn will use plan mode"
                    if command == "plan"
                    else "collab mode cleared"
                ),
            }))
        elif command == "collab":
            try:
                resp = await self._request("collaborationMode/list", {})
                modes = resp.get("data", [])
            except Exception as exc:  # noqa: BLE001
                await self._queue.put(SessionEvent("slash-ack", {
                    "command": command, "ok": False, "error": str(exc),
                }))
                return
            await self._queue.put(SessionEvent("collab-modes", {"modes": modes}))
        else:
            await self._queue.put(SessionEvent("slash-ack", {
                "command": command,
                "ok": False,
                "error": f"unknown slash command: /{command}",
            }))

    async def _resolve_approval(self, frame: dict) -> None:
        approval_id = frame.get("approval_id")
        if not approval_id:
            return
        rpc_id = self._pending_approvals.pop(approval_id, None)
        if rpc_id is None:
            log.info("approval-response for unknown id %s; ignoring", approval_id)
            return
        decision = frame.get("decision") or "decline"
        result = {"decision": decision}
        await self._send({"id": rpc_id, "result": result})

    # --- history replay -------------------------------------------------

    async def _replay_history(self) -> None:
        """Fetch prior turns and emit them as completed session-events."""
        assert self._thread_id
        resp = await self._request("thread/turns/list", {
            "threadId": self._thread_id,
            "limit": 50,
        })
        turns = resp.get("data", [])
        if not turns:
            return
        await self._queue.put(SessionEvent("history-begin", {"turns": len(turns)}))
        # Codex returns newest → oldest; reverse so the UI shows them in
        # chronological order.
        for turn in reversed(turns):
            turn_id = turn.get("id")
            for item in turn.get("items", []):
                codex_type = item.get("type", "")
                snake = _snake_item_type(codex_type)
                item_id = item.get("id") or f"hist_{uuid.uuid4().hex[:8]}"
                started_payload: dict = {
                    "turn_id": turn_id,
                    "item_id": item_id,
                    "item_type": snake,
                    "replayed": True,
                }
                if codex_type == "userMessage":
                    # Flatten content array: keep text, list any image paths.
                    text_parts: list[str] = []
                    for c in item.get("content", []) or []:
                        if isinstance(c, dict) and c.get("type") == "text":
                            text_parts.append(c.get("text", ""))
                        elif isinstance(c, dict) and c.get("type") == "localImage":
                            text_parts.append(f"[image: {os.path.basename(str(c.get('path', '')))}]")
                    started_payload["text"] = "\n".join(p for p in text_parts if p)
                elif codex_type in ("reasoning", "agentReasoning"):
                    started_payload.update(_item_extras(item))
                elif codex_type == "agentMessage":
                    started_payload["text"] = item.get("text", "")
                else:
                    started_payload.update(_item_extras(item))
                await self._queue.put(SessionEvent("item-started", started_payload))
                completed_payload: dict = {
                    "turn_id": turn_id,
                    "item_id": item_id,
                    "item_type": snake,
                    "replayed": True,
                    **started_payload,
                }
                await self._queue.put(SessionEvent("item-completed", completed_payload))
        await self._queue.put(SessionEvent("history-end", {}))

    # --- JSON-RPC plumbing ----------------------------------------------

    async def _request(self, method: str, params: dict) -> dict:
        self._next_id += 1
        req_id = self._next_id
        loop = asyncio.get_running_loop()
        fut: asyncio.Future = loop.create_future()
        self._pending[req_id] = fut
        await self._send({"id": req_id, "method": method, "params": params})
        try:
            return await asyncio.wait_for(fut, timeout=60.0)
        finally:
            self._pending.pop(req_id, None)

    async def _notify(self, method: str, params: dict) -> None:
        await self._send({"method": method, "params": params})

    async def _send(self, obj: dict) -> None:
        assert self._proc and self._proc.stdin
        line = json.dumps(obj) + "\n"
        async with self._send_lock:
            self._proc.stdin.write(line.encode())
            await self._proc.stdin.drain()

    async def _read_loop(self) -> None:
        assert self._proc and self._proc.stdout
        try:
            while True:
                line = await self._proc.stdout.readline()
                if not line:
                    return
                try:
                    msg = json.loads(line)
                except json.JSONDecodeError:
                    log.debug("malformed frame from codex: %r", line)
                    continue
                await self._dispatch(msg)
        except asyncio.CancelledError:
            pass

    async def _drain_stderr(self) -> None:
        assert self._proc and self._proc.stderr
        try:
            while True:
                line = await self._proc.stderr.readline()
                if not line:
                    return
                log.info("codex stderr: %s", line.decode(errors="replace").rstrip())
        except asyncio.CancelledError:
            pass

    async def _dispatch(self, msg: dict) -> None:
        # Response to one of our requests?
        if "id" in msg and ("result" in msg or "error" in msg):
            fut = self._pending.get(msg["id"])
            if fut and not fut.done():
                if "error" in msg:
                    fut.set_exception(RuntimeError(
                        f"{msg['error'].get('code')}: {msg['error'].get('message')}"
                    ))
                else:
                    fut.set_result(msg.get("result") or {})
            return

        method = msg.get("method")
        params = msg.get("params") or {}
        if not method:
            return

        # Server-initiated request (approvals, tool prompts). These carry
        # an `id` and expect a response — we forward to the client and
        # record the rpc id so approval-response frames can reply.
        if "id" in msg and method in (
            "item/commandExecution/requestApproval",
            "item/fileChange/requestApproval",
        ):
            approval_id = f"appr_{uuid.uuid4().hex[:8]}"
            self._pending_approvals[approval_id] = msg["id"]
            kind = "command" if method == "item/commandExecution/requestApproval" else "file_change"
            payload: dict = {
                "approval_id": approval_id,
                "kind": kind,
                "thread_id": params.get("threadId"),
                "turn_id": params.get("turnId"),
                "item_id": params.get("itemId"),
                "reason": params.get("reason"),
            }
            if kind == "command":
                payload["command"] = params.get("command")
                payload["cwd"] = params.get("cwd")
                decisions = params.get("availableDecisions") or [
                    "accept", "acceptForSession", "decline", "cancel"
                ]
                payload["decisions"] = [
                    d if isinstance(d, str) else (list(d.keys())[0] if isinstance(d, dict) else str(d))
                    for d in decisions
                ]
            else:
                payload["decisions"] = ["accept", "acceptForSession", "decline", "cancel"]
            await self._queue.put(SessionEvent("approval-request", payload))
            return

        if method == "turn/started":
            turn = params.get("turn", {})
            self._turn_id = turn.get("id")
            await self._queue.put(SessionEvent("turn-started", {
                "turn_id": turn.get("id"),
                "input": _join_input(params.get("input")),
            }))
        elif method == "item/started":
            item = params.get("item") or {}
            codex_type = item.get("type", "")
            if codex_type == "userMessage":
                # We echo user input client-side already.
                return
            await self._queue.put(SessionEvent("item-started", {
                "turn_id": params.get("turnId"),
                "item_id": item.get("id"),
                "item_type": _snake_item_type(codex_type),
                **_item_extras(item),
            }))
        elif method.startswith("item/") and method.endswith("/delta"):
            # e.g. item/agentMessage/delta → codex_type = "agentMessage"
            codex_type = method.split("/")[1]
            await self._queue.put(SessionEvent("item-delta", {
                "turn_id": params.get("turnId"),
                "item_id": params.get("itemId"),
                "item_type": _snake_item_type(codex_type),
                "delta": params.get("delta", ""),
            }))
        elif method == "item/reasoning/summaryTextDelta":
            # Reasoning streams under a different method family than
            # agentMessage; normalize here so the client sees the same
            # item-delta envelope.
            await self._queue.put(SessionEvent("item-delta", {
                "turn_id": params.get("turnId"),
                "item_id": params.get("itemId"),
                "item_type": "agent_reasoning",
                "delta": params.get("delta", ""),
            }))
        elif method == "item/reasoning/summaryPartAdded":
            # A new summary "part" starts — use a double-newline so
            # consecutive reasoning summaries render as separate blocks.
            await self._queue.put(SessionEvent("item-delta", {
                "turn_id": params.get("turnId"),
                "item_id": params.get("itemId"),
                "item_type": "agent_reasoning",
                "delta": "\n\n" if params.get("summaryIndex", 0) > 0 else "",
            }))
        elif method == "item/completed":
            item = params.get("item") or {}
            codex_type = item.get("type", "")
            if codex_type == "userMessage":
                return
            await self._queue.put(SessionEvent("item-completed", {
                "turn_id": params.get("turnId"),
                "item_id": item.get("id"),
                "item_type": _snake_item_type(codex_type),
                **_item_extras(item),
            }))
        elif method == "turn/completed":
            turn = params.get("turn", {})
            self._turn_id = None
            # Clean up any image temp files we wrote for this turn.
            for path in self._turn_tmp_files:
                try:
                    os.unlink(path)
                except OSError:
                    pass
            self._turn_tmp_files.clear()
            await self._queue.put(SessionEvent("turn-completed", {
                "turn_id": turn.get("id"),
                "duration_ms": turn.get("durationMs"),
                "status": turn.get("status"),
            }))
        elif method == "thread/status/changed":
            # Informational; surface as a session-event so clients can log it.
            status = params.get("status")
            if status:
                await self._queue.put(SessionEvent("thread-status", {"status": status}))
        else:
            # Drop mcpServer/*, account/*, thread/tokenUsage/*, etc.
            log.debug("ignored codex notification: %s", method)


def _join_input(items) -> str:
    if not isinstance(items, list):
        return ""
    out = []
    for part in items:
        if isinstance(part, dict) and part.get("type") == "text":
            out.append(part.get("text", ""))
    return "".join(out)


def _item_extras(item: dict) -> dict:
    """Flatten the fields the web client cares about out of the item payload."""
    t = item.get("type", "")
    extras: dict = {}
    if t == "agentMessage":
        if "text" in item:
            extras["text"] = item["text"]
        if "phase" in item:
            extras["phase"] = item["phase"]
    elif t in ("reasoning", "agentReasoning"):
        # Final reasoning object has `summary: [text, ...]` and `content: []`.
        # We've been streaming summary deltas into the client already, so
        # the final text matches what the client already rendered.
        summary = item.get("summary")
        if isinstance(summary, list) and summary:
            extras["text"] = "\n\n".join(s for s in summary if isinstance(s, str))
        elif "text" in item:
            extras["text"] = item["text"]
        elif "content" in item:
            extras["text"] = _summarize_reasoning(item["content"])
    elif t == "commandExecution":
        cmd = item.get("command") or item.get("commandLine")
        if cmd:
            extras["tool"] = "shell"
            extras["args"] = {"command": cmd}
        output = item.get("output") or item.get("stdout")
        if output:
            extras["output"] = output
        if "exitCode" in item:
            extras["exit_code"] = item["exitCode"]
    elif t == "fileChange":
        if "changes" in item:
            extras["changes"] = item["changes"]
    return extras


def _permissions_to_codex(perms: str, cwd: str) -> tuple[dict | None, str | None]:
    """Map UI-level permissions buttons to codex sandboxPolicy / approvalPolicy."""
    perms = perms.lower()
    if perms in ("full", "full-access", "dangerfullaccess"):
        return {"type": "dangerFullAccess"}, "never"
    if perms in ("readonly", "read-only"):
        return {
            "type": "readOnly",
            "access": {"type": "fullAccess"},
            "networkAccess": False,
        }, "on-request"
    # Default: write inside cwd, ask for anything else.
    return {
        "type": "workspaceWrite",
        "writableRoots": [cwd] if cwd else [],
        "readOnlyAccess": {"type": "fullAccess"},
        "networkAccess": False,
        "excludeTmpdirEnvVar": False,
        "excludeSlashTmp": False,
    }, "on-request"


def _image_suffix(mime: str | None) -> str:
    if not mime or not isinstance(mime, str):
        return ".png"
    m = mime.lower()
    return {
        "image/png": ".png",
        "image/jpeg": ".jpg",
        "image/jpg": ".jpg",
        "image/webp": ".webp",
        "image/gif": ".gif",
        "image/heic": ".heic",
    }.get(m, ".png")


def _summarize_reasoning(content) -> str:
    if isinstance(content, list):
        parts = []
        for c in content:
            if isinstance(c, dict):
                parts.append(c.get("text") or c.get("summary") or "")
            elif isinstance(c, str):
                parts.append(c)
        return " ".join(p for p in parts if p)
    if isinstance(content, str):
        return content
    return ""


def build_adapter(
    mode: str,
    codex_binary: str,
    default_cwd: str = "",
    resume_thread_id: str | None = None,
) -> SessionAdapter:
    mode = (mode or "stdio").lower()
    if mode == "mock":
        return MockCodexAdapter()
    if mode == "stdio":
        return StdioCodexAdapter(
            codex_binary=codex_binary,
            default_cwd=default_cwd or None,
            resume_thread_id=resume_thread_id,
        )
    raise ValueError(f"unknown adapter mode: {mode!r}")


# ---------------------------------------------------------------------------
# Admin codex — one persistent codex app-server used for quick read-only
# calls (thread/list, eventually model/list etc.). Per-session adapters
# still spawn their own codex for the actual turn flow; mixing admin
# calls into a live session would fight over the JSON-RPC id space.
# ---------------------------------------------------------------------------

class AdminCodex:
    """Long-running codex app-server that answers thread/list quickly.

    Each admin_list_threads() call previously cold-spawned codex (2-5s
    on node startup). When the box was already spawning codex for a
    live session, overlapping spawns occasionally pushed the daemon's
    event loop past the relay's 15s HTTP timeout, producing 504s and
    eventually WS heartbeat loss. Keeping one codex resident collapses
    the hot-path to a single JSON-RPC round-trip.
    """

    def __init__(self, codex_binary: str = "codex") -> None:
        self._codex_binary = codex_binary
        self._proc: asyncio.subprocess.Process | None = None
        self._reader_task: asyncio.Task | None = None
        self._pending: dict[int, asyncio.Future] = {}
        self._next_id = 0
        self._lock = asyncio.Lock()

    async def close(self) -> None:
        async with self._lock:
            await self._tear_down()

    async def list_threads(self, limit: int = 20, cursor: str | None = None) -> dict:
        """Return codex's raw thread/list result (the {data, nextCursor,
        backwardsCursor} body). Lazily starts the codex process and
        re-spawns if the previous one died."""
        async with self._lock:
            await self._ensure_running()
            params: dict = {"limit": limit}
            if cursor:
                params["cursor"] = cursor
            try:
                resp = await asyncio.wait_for(
                    self._request("thread/list", params), timeout=10.0
                )
                return resp
            except Exception:
                # The subprocess might be hosed; kill it so the next call
                # gets a fresh one instead of timing out again.
                await self._tear_down()
                raise

    # --- internals ----------------------------------------------------

    async def _ensure_running(self) -> None:
        if self._proc is not None and self._proc.returncode is None:
            return
        cmd = shlex.split(self._codex_binary) + ["app-server"]
        log.info("spawning persistent admin codex (%s)", " ".join(cmd))
        self._proc = await asyncio.create_subprocess_exec(
            *cmd,
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        self._reader_task = asyncio.create_task(self._read_loop())
        # Perform the init/initialized handshake once per spawn.
        await self._request("initialize", {
            "clientInfo": {
                "name": "remotex-daemon-admin",
                "title": "Remotex",
                "version": "0.1",
            },
            "capabilities": {"experimentalApi": True},
        })
        await self._send({"method": "initialized", "params": {}})

    async def _tear_down(self) -> None:
        if self._reader_task:
            self._reader_task.cancel()
            self._reader_task = None
        for fut in self._pending.values():
            if not fut.done():
                fut.set_exception(RuntimeError("admin codex torn down"))
        self._pending.clear()
        if self._proc and self._proc.returncode is None:
            try:
                self._proc.terminate()
            except ProcessLookupError:
                pass
            try:
                await asyncio.wait_for(self._proc.wait(), timeout=2.0)
            except asyncio.TimeoutError:
                self._proc.kill()
        self._proc = None

    async def _request(self, method: str, params: dict) -> dict:
        assert self._proc
        self._next_id += 1
        req_id = self._next_id
        loop = asyncio.get_running_loop()
        fut: asyncio.Future = loop.create_future()
        self._pending[req_id] = fut
        await self._send({"id": req_id, "method": method, "params": params})
        try:
            return await fut
        finally:
            self._pending.pop(req_id, None)

    async def _send(self, obj: dict) -> None:
        assert self._proc and self._proc.stdin
        line = json.dumps(obj) + "\n"
        self._proc.stdin.write(line.encode())
        await self._proc.stdin.drain()

    async def _read_loop(self) -> None:
        assert self._proc and self._proc.stdout
        try:
            while True:
                line = await self._proc.stdout.readline()
                if not line:
                    # codex exited. Fail any waiters; next call respawns.
                    for fut in self._pending.values():
                        if not fut.done():
                            fut.set_exception(RuntimeError("admin codex exited"))
                    self._pending.clear()
                    return
                try:
                    msg = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if "id" in msg and ("result" in msg or "error" in msg):
                    fut = self._pending.get(msg["id"])
                    if fut and not fut.done():
                        if "error" in msg:
                            fut.set_exception(RuntimeError(
                                f"{msg['error'].get('code')}: {msg['error'].get('message')}"
                            ))
                        else:
                            fut.set_result(msg.get("result") or {})
        except asyncio.CancelledError:
            pass


# Backwards-compatible module-level helper: the DaemonClient owns a
# single AdminCodex instance and routes threads-list-request through it.
# Kept this function available in case callers that used the old API
# are still around; new callers should go through DaemonClient.
async def admin_list_threads(
    codex_binary: str = "codex",
    limit: int = 20,
    cursor: str | None = None,
) -> dict:
    """Ad-hoc helper: spin up one admin codex, ask, tear down.

    Kept for compatibility. The daemon itself now uses a persistent
    AdminCodex per connection, which avoids the cold-spawn penalty on
    every list call.
    """
    admin = AdminCodex(codex_binary=codex_binary)
    try:
        return await admin.list_threads(limit=limit, cursor=cursor)
    finally:
        await admin.close()
