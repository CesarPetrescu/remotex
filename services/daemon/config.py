"""TOML config loader for the Remotex daemon.

Config lives at ~/.remotex/config.toml on Linux/macOS and
%APPDATA%\\Remotex\\config.toml on Windows by default. The CLI's `init`
command writes this file; `run` reads it.
"""
from __future__ import annotations

import os
import platform
import socket
import sys
import tomllib
from dataclasses import dataclass
from pathlib import Path


def default_config_path() -> Path:
    if sys.platform.startswith("win"):
        base = os.environ.get("APPDATA") or str(Path.home() / "AppData" / "Roaming")
        return Path(base) / "Remotex" / "config.toml"
    return Path.home() / ".remotex" / "config.toml"


@dataclass
class Config:
    relay_url: str
    bridge_token: str
    nickname: str
    mode: str = "stdio"          # "mock" | "stdio"
    codex_binary: str = "codex"  # only used when mode == "stdio"
    default_cwd: str = ""        # workspace dir Codex runs turns in; empty → $HOME

    @property
    def hostname(self) -> str:
        return socket.gethostname()

    @property
    def platform_string(self) -> str:
        return f"{platform.system()} {platform.release()} / {platform.machine()}"

    @classmethod
    def load(cls, path: Path) -> "Config":
        with path.open("rb") as fh:
            data = tomllib.load(fh)
        daemon = data.get("daemon", {})
        required = ("relay_url", "bridge_token", "nickname")
        missing = [k for k in required if not daemon.get(k)]
        if missing:
            raise ValueError(f"config missing required keys: {', '.join(missing)}")
        return cls(
            relay_url=daemon["relay_url"],
            bridge_token=daemon["bridge_token"],
            nickname=daemon["nickname"],
            mode=daemon.get("mode", "stdio"),
            codex_binary=daemon.get("codex_binary", "codex"),
            default_cwd=daemon.get("default_cwd", ""),
        )

    def dump(self) -> str:
        # Minimal hand-rolled TOML writer — keeps us stdlib-only.
        lines = [
            "# Remotex daemon config",
            "[daemon]",
            f'relay_url    = "{self.relay_url}"',
            f'bridge_token = "{self.bridge_token}"',
            f'nickname     = "{self.nickname}"',
            f'mode         = "{self.mode}"',
            f'codex_binary = "{self.codex_binary}"',
            f'default_cwd  = "{self.default_cwd}"',
            "",
        ]
        return "\n".join(lines)

    def write(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(self.dump(), encoding="utf-8")
        if not sys.platform.startswith("win"):
            try:
                os.chmod(path, 0o600)
            except OSError:
                pass
