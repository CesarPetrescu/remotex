"""Small Codex config helpers used before spawning `codex app-server`."""
from __future__ import annotations

import os
import re
import tomllib
from pathlib import Path

_FEATURES_HEADER_RE = re.compile(r"(?m)^\s*\[features\]\s*(?:#.*)?$")
_TABLE_HEADER_RE = re.compile(r"(?m)^\s*\[[^\]]+\]\s*(?:#.*)?$")
_GOALS_RE = re.compile(r"(?m)^(\s*goals\s*=\s*)(true|false)(\s*(?:#.*)?$)")


def codex_config_path() -> Path:
    """Return the config.toml path Codex will normally read."""
    codex_home = os.environ.get("CODEX_HOME")
    if codex_home:
        return Path(codex_home).expanduser() / "config.toml"
    return Path.home() / ".codex" / "config.toml"


def ensure_codex_goals_feature_enabled(path: Path | None = None) -> bool:
    """Ensure `[features] goals = true` exists in Codex config.toml.

    Returns true when the file was created or changed.
    """
    config_path = path or codex_config_path()
    if config_path.exists():
        text = config_path.read_text(encoding="utf-8")
        if _goals_enabled(text):
            return False
        next_text = _with_goals_enabled(text)
    else:
        next_text = "[features]\ngoals = true\n"

    config_path.parent.mkdir(parents=True, exist_ok=True)
    config_path.write_text(next_text, encoding="utf-8")
    try:
        os.chmod(config_path, 0o600)
    except OSError:
        pass
    return True


def _goals_enabled(text: str) -> bool:
    try:
        data = tomllib.loads(text or "")
    except tomllib.TOMLDecodeError:
        return False
    features = data.get("features")
    return isinstance(features, dict) and features.get("goals") is True


def _with_goals_enabled(text: str) -> str:
    if text and not text.endswith("\n"):
        text += "\n"

    features = _FEATURES_HEADER_RE.search(text)
    if not features:
        prefix = "" if not text else "\n"
        return f"{text}{prefix}[features]\ngoals = true\n"

    section_start = features.end()
    next_table = _TABLE_HEADER_RE.search(text, section_start)
    section_end = next_table.start() if next_table else len(text)
    section = text[section_start:section_end]

    goal = _GOALS_RE.search(section)
    if goal:
        replacement = f"{goal.group(1)}true{goal.group(3)}"
        next_section = section[:goal.start()] + replacement + section[goal.end():]
        return text[:section_start] + next_section + text[section_end:]

    return text[:section_start] + "\ngoals = true" + text[section_start:]
