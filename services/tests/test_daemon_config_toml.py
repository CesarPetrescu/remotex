"""Config.dump() must emit TOML that tomllib can read back verbatim."""
from __future__ import annotations

from pathlib import Path
import tomllib

import pytest

from daemon.config import Config


def _round_trip(**overrides) -> Config:
    fields = {
        "relay_url": "wss://relay.example/ws/daemon",
        "bridge_token": "brg_live_abc",
        "nickname": "workstation",
        **overrides,
    }
    cfg = Config(**fields)
    data = tomllib.loads(cfg.dump())["daemon"]
    return Config(
        relay_url=data["relay_url"],
        bridge_token=data["bridge_token"],
        nickname=data["nickname"],
        mode=data["mode"],
        codex_binary=data["codex_binary"],
        codex_socket_path=data["codex_socket_path"],
        default_cwd=data["default_cwd"],
        allow_insecure=data["allow_insecure"],
    )


@pytest.mark.parametrize("value", [
    r"C:\Users\me",                 # Windows path: \U is a unicode escape in TOML
    "C:\\Users\\me\\codex projects",
    "/home/user/some dir",
    'quote " inside',
    'both " and \\ inside',
    "line\nbreak",
    "tab\there",
    "café — naïve",                 # non-ASCII must survive
    "box 😀",                       # astral: JSON surrogate pairs are illegal TOML
    "del\x7fchar",                  # TOML forbids a raw U+007F
])
def test_default_cwd_round_trips(value):
    assert _round_trip(default_cwd=value).default_cwd == value


@pytest.mark.parametrize("value", ['my "box"', "back\\slash", "naïve 😀"])
def test_nickname_round_trips(value):
    assert _round_trip(nickname=value).nickname == value


def test_allow_insecure_round_trips():
    assert _round_trip(allow_insecure=True).allow_insecure is True
    assert _round_trip().allow_insecure is False


def test_shared_socket_path_round_trips():
    assert _round_trip(
        mode="shared",
        codex_socket_path="/run/user/1000/codex.sock",
    ).codex_socket_path == "/run/user/1000/codex.sock"


def test_shared_socket_defaults_to_codex_home(monkeypatch, tmp_path):
    monkeypatch.setenv("CODEX_HOME", str(tmp_path))
    cfg = Config(relay_url="wss://relay.example/ws/daemon", bridge_token="t", nickname="n")
    assert cfg.resolved_codex_socket_path == (
        tmp_path / "app-server-control" / "app-server-control.sock"
    )


def test_shared_socket_explicit_path_wins(monkeypatch, tmp_path):
    monkeypatch.setenv("CODEX_HOME", str(tmp_path / "ignored"))
    cfg = Config(
        relay_url="wss://relay.example/ws/daemon",
        bridge_token="t",
        nickname="n",
        codex_socket_path="~/custom/codex.sock",
    )
    assert cfg.resolved_codex_socket_path == Path.home() / "custom" / "codex.sock"


def test_dump_is_parseable_toml_with_hostile_values():
    cfg = Config(
        relay_url='ws://relay/"weird"',
        bridge_token="brg_live_\\x",
        nickname='he said "hi"\nthen left',
        default_cwd=r"C:\Users\me",
    )
    parsed = tomllib.loads(cfg.dump())["daemon"]
    assert parsed["relay_url"] == cfg.relay_url
    assert parsed["bridge_token"] == cfg.bridge_token
    assert parsed["nickname"] == cfg.nickname
    assert parsed["default_cwd"] == cfg.default_cwd


def test_load_reads_back_what_write_wrote(tmp_path):
    cfg = Config(
        relay_url="wss://relay.example/ws/daemon",
        bridge_token="brg_live_abc",
        nickname='desk "one"',
        default_cwd=r"C:\Users\me",
        allow_insecure=True,
    )
    path = tmp_path / "config.toml"
    cfg.write(path)
    loaded = Config.load(path)
    assert loaded == cfg


@pytest.mark.parametrize("url", [
    "wss://relay.example/ws/daemon",
    "ws://127.0.0.1:18080/ws/daemon",
    "ws://localhost:18080/ws/daemon",
    "ws://[::1]:18080/ws/daemon",
])
def test_secure_or_loopback_relay_urls_are_accepted(url):
    cfg = Config(relay_url=url, bridge_token="t", nickname="n")
    assert cfg.insecure_relay_reason() is None


@pytest.mark.parametrize("url", [
    "ws://192.168.1.10:18080/ws/daemon",
    "ws://relay.example/ws/daemon",
])
def test_cleartext_non_loopback_relay_urls_are_flagged(url):
    cfg = Config(relay_url=url, bridge_token="t", nickname="n")
    reason = cfg.insecure_relay_reason()
    assert reason and "cleartext" in reason
