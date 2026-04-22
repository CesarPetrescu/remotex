"""CLI entry: `python -m daemon init` / `python -m daemon run`."""
from __future__ import annotations

import argparse
import asyncio
import logging
import sys
from pathlib import Path

from .client import DaemonClient
from .config import Config, default_config_path


def _cmd_init(args: argparse.Namespace) -> int:
    cfg = Config(
        relay_url=args.relay_url,
        bridge_token=args.bridge_token,
        nickname=args.nickname,
        mode=args.mode,
        codex_binary=args.codex_binary,
        default_cwd=args.default_cwd or "",
    )
    path = Path(args.config) if args.config else default_config_path()
    cfg.write(path)
    print(f"wrote config to {path}")
    return 0


def _cmd_run(args: argparse.Namespace) -> int:
    path = Path(args.config) if args.config else default_config_path()
    if not path.exists():
        print(f"config not found at {path} — run `init` first", file=sys.stderr)
        return 2
    cfg = Config.load(path)
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(name)s %(levelname)s %(message)s",
    )
    client = DaemonClient(cfg)
    try:
        asyncio.run(client.run())
    except KeyboardInterrupt:
        return 0
    return 0


def _cmd_status(args: argparse.Namespace) -> int:
    path = Path(args.config) if args.config else default_config_path()
    if not path.exists():
        print("no config")
        return 1
    cfg = Config.load(path)
    print(f"config:     {path}")
    print(f"relay_url:  {cfg.relay_url}")
    print(f"nickname:   {cfg.nickname}")
    print(f"hostname:   {cfg.hostname}")
    print(f"platform:   {cfg.platform_string}")
    print(f"mode:       {cfg.mode}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(prog="python -m daemon")
    sub = ap.add_subparsers(dest="cmd", required=True)

    init = sub.add_parser("init", help="write a new daemon config")
    init.add_argument("--relay-url", required=True, help="ws:// or wss:// URL to the relay /ws/daemon endpoint")
    init.add_argument("--bridge-token", required=True)
    init.add_argument("--nickname", required=True)
    init.add_argument("--mode", default="stdio", choices=["mock", "stdio"])
    init.add_argument("--codex-binary", default="codex")
    init.add_argument(
        "--default-cwd",
        default=None,
        help="workspace dir Codex runs turns in (stdio mode). Empty → $HOME",
    )
    init.add_argument("--config", default=None, help="override config path")
    init.set_defaults(func=_cmd_init)

    run = sub.add_parser("run", help="connect to the relay and service sessions")
    run.add_argument("--config", default=None)
    run.set_defaults(func=_cmd_run)

    status = sub.add_parser("status", help="print loaded config + host identity")
    status.add_argument("--config", default=None)
    status.set_defaults(func=_cmd_status)
    return ap


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
