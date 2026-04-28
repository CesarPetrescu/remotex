"""Title generation: prompt construction, XML parsing, cleanup."""
from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from typing import Any

from .queries import _field
from .text import (
    _estimate_tokens,
    _normalize_space,
    _str_or_none,
    _truncate_to_token_estimate,
    _xml_escape,
)

_TITLE_SYSTEM_PROMPT = """You generate compact metadata for Codex chat sessions.

Return exactly one XML function call and nothing else:

<function_calls>
  <invoke name="set_chat_metadata">
    <parameter name="title">Short specific title</parameter>
    <parameter name="description">One sentence description.</parameter>
    <parameter name="isGeneric">true</parameter>
  </invoke>
</function_calls>

Rules:
- Do not include markdown, JSON, explanations, or <think> blocks.
- Title must be 3-9 words.
- Title must name the actual task, product, file, bug, or decision when possible.
- Description must summarize the last 10 turns in 2-3 concise sentences
  (max ~400 characters). Capture the current focus, key decisions, and
  anything in-flight. Prefer the most recent turns over the oldest.
- Set isGeneric=true if the transcript is too vague, only a greeting, only setup,
  or the best title would be generic.
- Set isGeneric=false only when the title is specific enough that a user could
  recognize the chat later.
- Never use generic titles like: New Chat, General Help, Code Help, App Review,
  App Discussion, Greeting, Follow Up, Question, Help Request.
"""


_GENERIC_TITLES = {
    "new chat",
    "general help",
    "code help",
    "app review",
    "app discussion",
    "greeting",
    "follow up",
    "question",
    "help request",
    "untitled chat",
}


def _title_user_prompt(session, transcript: str) -> str:
    cwd = _str_or_none(session["cwd"]) or "unknown"
    model = _str_or_none(session["model"]) or "unknown"
    thread_id = _str_or_none(session["thread_id"]) or "unknown"
    return f"""Session:
- thread_id: {thread_id}
- cwd: {cwd}
- model: {model}

Transcript:
{transcript}
"""


def _format_title_transcript(turns: list[dict[str, Any]], max_tokens: int) -> str:
    blocks: list[str] = []
    used = 0
    for idx, turn in enumerate(turns, start=1):
        turn_blocks: list[str] = [f"<turn index=\"{idx}\">"]
        saw_user = False
        for item in turn.get("items") or []:
            item_type = _field(item, "item_type")
            raw_text = _field(item, "text")
            text = _normalize_space(raw_text) if isinstance(raw_text, str) else ""
            if not text:
                continue
            if item_type == "user_message":
                saw_user = True
                role = "user"
            elif item_type == "agent_message":
                role = "assistant"
            else:
                continue
            turn_blocks.append(f"<message role=\"{role}\">{_xml_escape(_title_clip(text))}</message>")
        user_text = turn.get("user_text")
        if not saw_user and isinstance(user_text, str) and user_text.strip():
            text = _xml_escape(_title_clip(_normalize_space(user_text)))
            turn_blocks.insert(1, f"<message role=\"user\">{text}</message>")
        turn_blocks.append("</turn>")
        block = "\n".join(turn_blocks)
        tokens = _estimate_tokens(block)
        if used and used + tokens > max_tokens:
            break
        if tokens > max_tokens:
            block = _truncate_to_token_estimate(block, max_tokens)
            tokens = _estimate_tokens(block)
        blocks.append(block)
        used += tokens
        if used >= max_tokens:
            break
    return "\n\n".join(blocks).strip()


def _title_clip(text: str, limit: int = 2200) -> str:
    if len(text) <= limit:
        return text
    return text[:limit].rstrip() + f"\n[trimmed {len(text) - limit} chars]"


def _parse_title_xml(content: str) -> dict[str, Any]:
    cleaned = re.sub(r"<think\b[^>]*>.*?</think>", "", content, flags=re.IGNORECASE | re.DOTALL)
    match = re.search(
        r"<function_calls\b[^>]*>.*?</function_calls>",
        cleaned,
        flags=re.IGNORECASE | re.DOTALL,
    )
    if not match:
        raise RuntimeError("MainModel title response missing function_calls XML")
    try:
        root = ET.fromstring(match.group(0))
    except ET.ParseError as exc:
        raise RuntimeError(f"MainModel title XML parse failed: {exc}") from exc
    invoke = None
    for candidate in root.iter("invoke"):
        if candidate.attrib.get("name") == "set_chat_metadata":
            invoke = candidate
            break
    if invoke is None:
        raise RuntimeError("MainModel title XML missing set_chat_metadata invoke")
    params: dict[str, str] = {}
    for param in invoke.findall("parameter"):
        name = param.attrib.get("name")
        if not name:
            continue
        params[name] = "".join(param.itertext()).strip()
    title = params.get("title") or ""
    description = params.get("description") or ""
    is_generic = (params.get("isGeneric") or "true").strip().lower() not in {
        "false",
        "0",
        "no",
    }
    return {"title": title, "description": description, "is_generic": is_generic}


def _clean_title(value: str) -> str:
    title = re.sub(r"\s+", " ", (value or "").strip().strip("\"'`")).strip()
    if len(title) > 90:
        title = title[:87].rstrip() + "..."
    return title or "Untitled chat"


def _clean_description(value: str) -> str:
    description = re.sub(r"\s+", " ", (value or "").strip()).strip()
    if len(description) > 400:
        description = description[:397].rstrip() + "..."
    return description


def _looks_generic_title(title: str) -> bool:
    cleaned = re.sub(r"\s+", " ", title.strip().lower())
    if not cleaned or cleaned in _GENERIC_TITLES:
        return True
    words = re.findall(r"[a-z0-9]+", cleaned)
    return len(words) < 2
