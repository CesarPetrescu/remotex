"""StdioCodexAdapter — bridges `codex app-server` stdio JSON-RPC onto SessionEvents."""
from __future__ import annotations

import asyncio
import base64
import json
import logging
import os
import shlex
import tempfile
import uuid
from typing import AsyncIterator

from .base import SessionAdapter, SessionEvent
from .items import _item_extras, _join_input, _snake_item_type
from .permissions import _image_suffix, _permissions_to_codex
from .rollout import (
    _load_rollout_history,
    _load_rollout_metadata,
    _sort_turns_chronological,
)

log = logging.getLogger("daemon.adapters.stdio")


async def _read_line_unbounded(stream: asyncio.StreamReader) -> bytes:
    """Like StreamReader.readline() but with no 64KB ceiling.

    Codex's `thread/resume` reply inlines the full conversation as ONE
    JSON-RPC frame on a single line. For multi-MB rollouts that line
    blows past the asyncio stream's default LIMIT_BUFFER (~64KB) and
    the built-in readline() raises ValueError, killing the read task
    and stranding any pending RPC. `readuntil` raises LimitOverrunError
    instead of consuming the buffer, so we drain what's there with
    readexactly(e.consumed) and keep going until we find the newline.
    """
    parts: list[bytes] = []
    while True:
        try:
            tail = await stream.readuntil(b"\n")
            return b"".join(parts) + tail
        except asyncio.LimitOverrunError as exc:
            chunk = await stream.readexactly(exc.consumed)
            parts.append(chunk)
        except asyncio.IncompleteReadError as exc:
            return b"".join(parts) + exc.partial


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
        # Mutable working directory: thread/start kicks off in
        # `default_cwd`, /cd swaps it, every turn/start rides with it.
        self._current_cwd = self._cwd
        self._proc: asyncio.subprocess.Process | None = None
        self._queue: asyncio.Queue[SessionEvent | None] = asyncio.Queue()
        self._reader_task: asyncio.Task | None = None
        self._stderr_task: asyncio.Task | None = None
        self._send_lock = asyncio.Lock()
        self._pending: dict[int, asyncio.Future] = {}
        self._next_id = 0
        self._thread_id: str | None = None
        self._ready = False
        self._resume_task: asyncio.Task | None = None
        self._resume_error: str | None = None
        self._history_replayed = False
        self._reasoning_summary = "auto"
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
        resume_meta: dict = {}
        if self._resume_thread_id:
            resume_meta = _load_rollout_metadata(self._resume_thread_id)
            if resume_meta.get("cwd"):
                self._current_cwd = resume_meta["cwd"]

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
            self._thread_id = self._resume_thread_id
            await self._queue.put(SessionEvent("session-started", {
                "model": resume_meta.get("model") or model_hint,
                "cwd": self._current_cwd,
                "thread_id": self._thread_id,
                "transport": "stdio",
                "resuming": True,
            }))
            try:
                local_turns = _load_rollout_history(self._resume_thread_id)
                if local_turns:
                    await self._replay_history(local_turns)
                    self._history_replayed = True
            except Exception as exc:  # noqa: BLE001
                log.warning("local history replay failed: %s", exc)

            # Codex can be slow or get stuck loading large/active rollout
            # files. Show local transcript immediately and finish the live
            # attach in the background so clicking a saved chat is responsive.
            self._resume_task = asyncio.create_task(
                self._finish_resume(resume_meta.get("model") or model_hint)
            )
            return

        thread = await self._request("thread/start", {
            "cwd": self._cwd,
            "ephemeral": False,
        })
        self._thread_id = thread["thread"].get("id", self._resume_thread_id)
        self._ready = True
        await self._queue.put(SessionEvent("session-started", {
            "model": thread.get("model") or model_hint,
            "cwd": thread.get("cwd", self._cwd),
            "thread_id": self._thread_id,
            "transport": "stdio",
        }))

    async def stop(self) -> None:
        if self._resume_task:
            self._resume_task.cancel()
            try:
                await self._resume_task
            except asyncio.CancelledError:
                pass
            self._resume_task = None
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
            if not self._ready and self._resume_task and not self._resume_task.done():
                try:
                    await asyncio.wait_for(asyncio.shield(self._resume_task), timeout=20.0)
                except (asyncio.TimeoutError, Exception):  # noqa: BLE001
                    pass
            if not self._thread_id or not self._ready:
                log.info("turn-start before thread ready; rejecting")
                message = (
                    "history is visible, but Codex could not resume this saved chat yet"
                    if self._resume_error
                    else "Codex is still resuming this saved chat; try again in a moment"
                )
                await self._queue.put(SessionEvent("turn-completed", {
                    "error": message,
                }))
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
                # Apply the latest /cd target on every turn. Codex
                # documents `cwd` as "override for this turn and
                # subsequent turns," so passing it every time keeps
                # the daemon and codex in sync even on resumed threads.
                "cwd": self._current_cwd,
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
            if not self._turn_id or not self._thread_id:
                log.info("turn-interrupt but no turn in flight; ignoring")
                return
            try:
                # Codex requires BOTH threadId and turnId on turn/interrupt,
                # not just one or the other. Documented at:
                # https://developers.openai.com/codex/app-server
                await self._request("turn/interrupt", {
                    "threadId": self._thread_id,
                    "turnId": self._turn_id,
                })
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

    async def _finish_resume(self, model_hint: str) -> None:
        assert self._resume_thread_id
        # Tell the client we've started talking to codex so the UI can
        # show a "resuming…" indicator. Without this, the user just
        # sees the transcript with no signal that more is happening
        # in the background.
        await self._queue.put(SessionEvent("thread-status", {
            "status": "resuming",
            "thread_id": self._resume_thread_id,
        }))
        try:
            # Codex re-hydrates the entire saved JSONL rollout on
            # thread/resume, which can take well over a minute on long
            # multi-turn threads. The local transcript is already
            # visible to the user (see the comment in start()), so this
            # task only needs to finish before the user sends a NEW
            # turn — which is gated by a separate 20s wait in handle().
            # 10 minutes is the upper bound for "codex is genuinely
            # working" vs "codex is wedged."
            resp = await self._request("thread/resume", {
                "threadId": self._resume_thread_id,
            }, timeout=600.0)
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001
            self._resume_error = str(exc) or type(exc).__name__
            log.warning(
                "thread/resume failed for %s: %s",
                self._resume_thread_id,
                self._resume_error,
            )
            await self._queue.put(SessionEvent("thread-status", {
                "status": "resume-failed",
                "error": self._resume_error,
            }))
            return

        thread_obj = resp.get("thread") or {"id": self._resume_thread_id}
        self._thread_id = thread_obj.get("id") or self._resume_thread_id
        self._current_cwd = resp.get("cwd") or thread_obj.get("cwd") or self._current_cwd
        self._ready = True
        self._resume_error = None
        resume_turns = (
            thread_obj.get("turns") if isinstance(thread_obj.get("turns"), list) else None
        )
        if resume_turns and not self._history_replayed:
            try:
                await self._replay_history(resume_turns)
                self._history_replayed = True
            except Exception as exc:  # noqa: BLE001
                log.warning("history replay failed: %s", exc)
        await self._queue.put(SessionEvent("thread-status", {
            "status": "resumed",
            "model": resp.get("model") or model_hint,
            "cwd": self._current_cwd,
            "thread_id": self._thread_id,
        }))

    # --- slash commands + approvals ------------------------------------

    async def _handle_slash(self, frame: dict) -> None:
        command = (frame.get("command") or "").lower().strip()
        args = (frame.get("args") or "").strip()
        if command == "cd":
            await self._handle_cd(args)
            return
        if command == "pwd":
            await self._queue.put(SessionEvent("slash-ack", {
                "command": "pwd",
                "ok": True,
                "message": self._current_cwd or "(unset)",
            }))
            return
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

    async def _handle_cd(self, args: str) -> None:
        if not args:
            await self._queue.put(SessionEvent("slash-ack", {
                "command": "cd", "ok": False,
                "error": "usage: /cd <absolute path>",
            }))
            return
        # Expand ~ and $HOME; anchor relative paths to the current cwd
        # so `/cd subdir` is intuitive once you're already inside a repo.
        expanded = os.path.expanduser(os.path.expandvars(args))
        if not os.path.isabs(expanded):
            expanded = os.path.normpath(os.path.join(self._current_cwd, expanded))
        if not os.path.isdir(expanded):
            await self._queue.put(SessionEvent("slash-ack", {
                "command": "cd", "ok": False,
                "error": f"not a directory: {expanded}",
            }))
            return
        self._current_cwd = expanded
        await self._queue.put(SessionEvent("slash-ack", {
            "command": "cd", "ok": True,
            "message": f"cwd → {expanded}",
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

    async def _replay_history(self, turns: list[dict] | None = None) -> None:
        """Fetch prior turns and emit them as completed session-events."""
        assert self._thread_id
        if turns is None:
            resp = await self._request("thread/turns/list", {
                "threadId": self._thread_id,
                "limit": 50,
                "sortDirection": "asc",
            })
            turns = resp.get("data", [])
        if not turns:
            return
        turns = _sort_turns_chronological(turns)
        await self._queue.put(SessionEvent("history-begin", {"turns": len(turns)}))
        for turn in turns:
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

    async def _request(self, method: str, params: dict, timeout: float = 60.0) -> dict:
        self._next_id += 1
        req_id = self._next_id
        loop = asyncio.get_running_loop()
        fut: asyncio.Future = loop.create_future()
        self._pending[req_id] = fut
        await self._send({"id": req_id, "method": method, "params": params})
        try:
            return await asyncio.wait_for(fut, timeout=timeout)
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
                try:
                    line = await _read_line_unbounded(self._proc.stdout)
                except Exception as exc:  # noqa: BLE001
                    # Anything other than CancelledError here means the
                    # subprocess pipe is in a bad state (rare, e.g. broken
                    # encoding from codex). Treat it like a process exit
                    # so we don't sit silently on pending RPCs.
                    await self._handle_codex_exit(f"read error: {exc}")
                    return
                if not line:
                    # stdout closed = codex exited. Fail every pending RPC
                    # so callers stop blocking forever (the resume task in
                    # particular used to sit on its 600s timeout).
                    await self._handle_codex_exit("stdout closed")
                    return
                try:
                    msg = json.loads(line)
                except json.JSONDecodeError:
                    log.debug("malformed frame from codex: %r", line[:200])
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

    async def _handle_codex_exit(self, reason: str) -> None:
        """Codex died on us. Fail pending RPCs and tell the client."""
        rc = self._proc.returncode if self._proc else None
        if rc is None and self._proc is not None:
            try:
                rc = await asyncio.wait_for(self._proc.wait(), timeout=1.0)
            except asyncio.TimeoutError:
                rc = None
        msg = f"codex app-server exited unexpectedly (rc={rc}, {reason})"
        log.warning(msg)
        # Reject anything still waiting on a JSON-RPC response.
        for fut in list(self._pending.values()):
            if not fut.done():
                fut.set_exception(RuntimeError(msg))
        self._pending.clear()
        # If a resume was in flight, surface the failure as resume-failed
        # so the client banner clears with a real error instead of waiting
        # the full 600s _request timeout.
        if self._resume_task and not self._resume_task.done():
            self._resume_error = msg
            await self._queue.put(SessionEvent("thread-status", {
                "status": "resume-failed",
                "error": msg,
            }))
        # Mark the session as broken so any subsequent turn-start gets
        # rejected immediately rather than hanging.
        self._ready = False

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
        elif method == "thread/tokenUsage/updated":
            # Codex's payload (codex 0.128) nests as:
            #   {threadId, turnId, tokenUsage:{total:{...}, last:{...}, modelContextWindow}}
            # Flatten `total` into a stable shape clients can render
            # directly. Keep raw_total for forward-compat in case codex
            # adds new fields like cache_creation_tokens.
            usage = params.get("tokenUsage") or {}
            total = usage.get("total") or {}
            await self._queue.put(SessionEvent("token-usage", {
                "thread_id": params.get("threadId"),
                "turn_id": params.get("turnId"),
                "input": total.get("inputTokens", 0),
                "output": total.get("outputTokens", 0),
                "cached_input": total.get("cachedInputTokens", 0),
                "reasoning_output": total.get("reasoningOutputTokens", 0),
                "total": total.get("totalTokens", 0),
                "context_window": usage.get("modelContextWindow"),
                "raw_total": total,
            }))
        else:
            # Drop mcpServer/*, account/*, etc.
            log.debug("ignored codex notification: %s", method)
