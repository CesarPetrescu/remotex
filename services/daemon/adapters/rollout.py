"""Codex rollout file loaders for thread resume / history replay."""
from __future__ import annotations

import datetime as dt
import json
import logging
from pathlib import Path

log = logging.getLogger("daemon.adapters.rollout")


def _sort_turns_chronological(turns: list[dict]) -> list[dict]:
    def key(turn: dict) -> tuple[int, str]:
        ts = _timestamp_value(turn.get("startedAt") or turn.get("completedAt"))
        return (int(ts), str(turn.get("id") or ""))

    return sorted((t for t in turns if isinstance(t, dict)), key=key)


def _load_rollout_history(thread_id: str, max_items: int = 500) -> list[dict]:
    path = _find_rollout_path(thread_id)
    if not path:
        return []

    turns: list[dict] = []
    current_turn: dict | None = None

    def ensure_turn(turn_id: str | None, ts: int, line_no: int) -> dict:
        nonlocal current_turn
        tid = (
            turn_id
            or (current_turn.get("id") if current_turn else None)
            or f"hist_turn_{line_no}"
        )
        if current_turn is not None and current_turn.get("id") == tid:
            return current_turn
        current_turn = {
            "id": tid,
            "startedAt": ts,
            "items": [],
        }
        turns.append(current_turn)
        return current_turn

    try:
        with path.open(encoding="utf-8") as fh:
            for line_no, line in enumerate(fh, start=1):
                line = line.strip()
                if not line:
                    continue
                try:
                    row = json.loads(line)
                except json.JSONDecodeError:
                    continue

                if row.get("type") != "event_msg":
                    continue
                payload = row.get("payload") or {}
                if not isinstance(payload, dict):
                    continue

                ev = payload.get("type")
                ts = int(_timestamp_value(row.get("timestamp")) or line_no)
                turn_id = payload.get("turn_id")

                if ev == "task_started":
                    current_turn = {
                        "id": turn_id or f"hist_turn_{line_no}",
                        "startedAt": payload.get("started_at") or ts,
                        "items": [],
                    }
                    turns.append(current_turn)
                    continue
                if ev == "task_complete":
                    if current_turn is not None:
                        current_turn["completedAt"] = payload.get("completed_at") or ts
                    current_turn = None
                    continue

                turn = ensure_turn(turn_id, ts, line_no)
                item = _rollout_item(ev, payload, line_no)
                if item:
                    turn["items"].append(item)
    except OSError as exc:
        log.warning("failed to read rollout history %s: %s", path, exc)
        return []

    turns = [t for t in turns if t.get("items")]
    if not turns:
        return []
    return _trim_history_items(turns, max_items)


def _load_rollout_metadata(thread_id: str) -> dict:
    path = _find_rollout_path(thread_id)
    if not path:
        return {}
    meta: dict = {}
    try:
        with path.open(encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                try:
                    row = json.loads(line)
                except json.JSONDecodeError:
                    continue
                payload = row.get("payload") or {}
                if not isinstance(payload, dict):
                    continue
                if row.get("type") == "session_meta":
                    if payload.get("cwd"):
                        meta["cwd"] = payload["cwd"]
                    if payload.get("model"):
                        meta["model"] = payload["model"]
                elif row.get("type") == "turn_context":
                    if payload.get("cwd"):
                        meta["cwd"] = payload["cwd"]
                    if payload.get("model"):
                        meta["model"] = payload["model"]
    except OSError as exc:
        log.warning("failed to read rollout metadata %s: %s", path, exc)
    return meta


def _find_rollout_path(thread_id: str) -> Path | None:
    codex_home = Path.home() / ".codex"
    matches: list[Path] = []
    for root in (codex_home / "sessions", codex_home / "archived_sessions"):
        if not root.is_dir():
            continue
        try:
            matches.extend(root.rglob(f"rollout-*{thread_id}.jsonl"))
        except OSError:
            continue
    if not matches:
        return None
    return max(matches, key=lambda p: p.stat().st_mtime)


def _rollout_item(ev: str | None, payload: dict, line_no: int) -> dict | None:
    if ev == "user_message":
        text = _trim_history_text(payload.get("message") or payload.get("text"))
        if not text:
            return None
        return {
            "id": f"hist_user_{line_no}",
            "type": "userMessage",
            "content": [{"type": "text", "text": text}],
        }
    if ev == "agent_message":
        text = _trim_history_text(payload.get("message") or payload.get("text"))
        if not text:
            return None
        return {
            "id": f"hist_agent_{line_no}",
            "type": "agentMessage",
            "text": text,
            "phase": payload.get("phase"),
        }
    if ev == "agent_reasoning":
        text = _trim_history_text(payload.get("message") or payload.get("text"))
        if not text:
            return None
        return {
            "id": f"hist_reasoning_{line_no}",
            "type": "agentReasoning",
            "text": text,
        }
    if ev == "exec_command_end":
        command = _format_rollout_command(payload.get("command"))
        output = _trim_history_text(
            payload.get("aggregated_output")
            or payload.get("aggregatedOutput")
            or payload.get("stdout"),
            limit=12000,
        )
        if not command and not output:
            return None
        item = {
            "id": payload.get("call_id") or f"hist_tool_{line_no}",
            "type": "commandExecution",
        }
        if command:
            item["command"] = command
        if output:
            item["aggregatedOutput"] = output
        if "exit_code" in payload:
            item["exitCode"] = payload.get("exit_code")
        elif "exitCode" in payload:
            item["exitCode"] = payload.get("exitCode")
        return item
    return None


def _trim_history_items(turns: list[dict], max_items: int) -> list[dict]:
    kept: list[dict] = []
    total = 0
    for turn in reversed(turns):
        items = list(turn.get("items") or [])
        if not items:
            continue
        if total >= max_items:
            break
        take = max_items - total
        if len(items) > take:
            items = items[-take:]
        new_turn = {**turn, "items": items}
        kept.append(new_turn)
        total += len(items)
    return list(reversed(kept))


def _trim_history_text(value, limit: int = 30000) -> str:
    if not isinstance(value, str):
        return ""
    text = value.strip()
    if len(text) <= limit:
        return text
    return f"{text[:limit]}\n\n[trimmed {len(text) - limit} chars]"


def _format_rollout_command(command) -> str:
    if isinstance(command, list):
        return " ".join(str(part) for part in command)
    if isinstance(command, str):
        return command
    return ""


def _timestamp_value(value) -> float:
    if isinstance(value, (int, float)):
        return float(value)
    if isinstance(value, str) and value:
        try:
            return dt.datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()
        except ValueError:
            return 0.0
    return 0.0
