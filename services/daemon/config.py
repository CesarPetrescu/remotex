"""TOML config loader for the Remotex daemon.

Config lives at ~/.remotex/config.toml on Linux/macOS and
%APPDATA%\\Remotex\\config.toml on Windows by default. The CLI's `init`
command writes this file; `run` reads it.
"""
from __future__ import annotations

import getpass
import ipaddress
import json
import os
import platform
import socket
import sys
import tomllib
import urllib.parse
from dataclasses import dataclass
from pathlib import Path


def default_config_path() -> Path:
    if sys.platform.startswith("win"):
        base = os.environ.get("APPDATA") or str(Path.home() / "AppData" / "Roaming")
        return Path(base) / "Remotex" / "config.toml"
    return Path.home() / ".remotex" / "config.toml"


def _toml_string(value: str) -> str:
    """Quote a value as a TOML basic string.

    TOML basic strings share JSON's escape grammar, so json.dumps is a
    correct stdlib-only escaper — with two adjustments: ensure_ascii is
    off because JSON escapes astral characters (emoji nicknames) as
    surrogate pairs, which TOML rejects; and json leaves U+007F raw,
    which TOML forbids, so that one is escaped by hand.
    """
    return json.dumps(value, ensure_ascii=False).replace("\x7f", "\\u007f")


def _is_loopback_host(host: str) -> bool:
    host = (host or "").strip().strip("[]").lower()
    if not host:
        return False
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return host == "localhost" or host.endswith(".localhost")


@dataclass
class Config:
    relay_url: str
    bridge_token: str
    nickname: str
    mode: str = "stdio"          # "mock" | "stdio" | "shared"
    codex_binary: str = "codex"  # used by stdio and shared setup
    codex_socket_path: str = ""  # shared mode; empty -> $CODEX_HOME default
    default_cwd: str = ""        # workspace dir Codex runs turns in; empty → $HOME
    # Opt-in required before we ship the bridge token over cleartext
    # ws:// to anything but loopback. See insecure_relay_reason().
    allow_insecure: bool = False

    @property
    def hostname(self) -> str:
        return socket.gethostname()

    @property
    def platform_string(self) -> str:
        return f"{platform.system()} {platform.release()} / {platform.machine()}"

    @property
    def os_user(self) -> str:
        # Reported to the relay so clients can show "host (cesar5514)"
        # — matters when one box runs daemons under multiple Linux
        # accounts (each with its own ~/.codex/auth.json).
        try:
            return getpass.getuser()
        except Exception:  # noqa: BLE001
            return os.environ.get("USER") or os.environ.get("USERNAME") or ""

    @property
    def resolved_codex_socket_path(self) -> Path:
        if self.codex_socket_path:
            return Path(self.codex_socket_path).expanduser()
        codex_home = Path(os.environ.get("CODEX_HOME") or Path.home() / ".codex")
        return codex_home.expanduser() / "app-server-control" / "app-server-control.sock"

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
            codex_socket_path=daemon.get("codex_socket_path", ""),
            default_cwd=daemon.get("default_cwd", ""),
            allow_insecure=bool(daemon.get("allow_insecure", False)),
        )

    def insecure_relay_reason(self) -> str | None:
        """Describe why this relay URL is unsafe, or None when it's fine.

        wss:// is always fine; so is cleartext ws:// to loopback (the
        normal local-dev setup). Anything else puts the bridge token and
        every prompt on the wire in the clear.
        """
        parsed = urllib.parse.urlsplit(self.relay_url)
        scheme = (parsed.scheme or "").lower()
        if scheme in ("wss", "https"):
            return None
        host = parsed.hostname or ""
        if _is_loopback_host(host):
            return None
        return (
            f"relay_url is cleartext {scheme or 'ws'}:// to non-loopback host "
            f"{host or '<unknown>'} — the bridge token and every prompt cross "
            "the network unencrypted"
        )

    def dump(self) -> str:
        # Minimal hand-rolled TOML writer — keeps us stdlib-only. Values
        # go through _toml_string so quotes, backslashes (Windows paths)
        # and newlines survive the round-trip through tomllib.
        lines = [
            "# Remotex daemon config",
            "[daemon]",
            f"relay_url    = {_toml_string(self.relay_url)}",
            f"bridge_token = {_toml_string(self.bridge_token)}",
            f"nickname     = {_toml_string(self.nickname)}",
            f"mode         = {_toml_string(self.mode)}",
            f"codex_binary = {_toml_string(self.codex_binary)}",
            f"codex_socket_path = {_toml_string(self.codex_socket_path)}",
            f"default_cwd  = {_toml_string(self.default_cwd)}",
            f"allow_insecure = {'true' if self.allow_insecure else 'false'}",
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
