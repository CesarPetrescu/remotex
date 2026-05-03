# CLAUDE.md — repo conventions for AI agents

This file is loaded into every Claude Code session in this repo.
Keep it short. Keep it accurate.

## Layout (one-line reminders)

- `services/daemon/` — Python daemon that runs on each user's host;
  spawns `codex app-server` subprocesses and bridges them to the relay.
  Run as the systemd user unit `remotex-daemon` (not in docker).
- `services/relay/` — aiohttp relay; routes between web/Android clients
  and the daemons. Lives in the `remotex-relay-1` docker container
  (compose file at `deploy/docker-compose.yml`).
- `services/daemon/orchestrator/` — long-horizon brain that uses MCP
  to spawn child codex sessions. See `adapter.py` for how the brain's
  `thread/start.config.mcp_servers` is wired up.
- `apps/web/` — Vite + React UI. Built into the relay docker image.
- `android/` — Compose client. Build via `android/build.sh` (auto-
  detects the LAN IP for the relay URL), NOT via plain Gradle (the
  default URL is `10.0.2.2` which only works on emulators).

## Restart rituals after editing code

- Daemon code (`services/daemon/**`):
  `systemctl --user restart remotex-daemon`
- Relay code (`services/relay/**`) **or** web (`apps/web/**`):
  `cd deploy && docker compose build relay && docker compose up -d relay`
  (the relay image bakes the web bundle in at build time)
- Android: `cd android && ./build.sh install`

## Relay URL (matters for Android builds)

The relay binds inside docker on `:8080`; the host port is set in
`deploy/.env` (currently `RELAY_HOST_PORT=18080`, `RELAY_HOST_BIND=0.0.0.0`).
For LAN access from a phone: `http://<LAN-IP>:18080`. The Android
build script handles this auto-magically — see `android/README.md`.

## Adapter / runtime test conventions

- Daemon Python tests live in `services/tests/`. Run via the daemon's
  venv at `~/.local/share/remotex/venv/bin/python -m pytest …`.
- Don't add tests that require a real `codex` binary unless you also
  guard them with the same `pytest.skip` pattern other adapter tests
  use — CI doesn't have the binary installed.

## Orchestrator-specific gotchas

- The brain's per-turn frame ALWAYS uses `permissions="full"` (see
  `services/daemon/orchestrator/adapter.py::_brain_turn_frame`). This
  is required because codex only auto-approves MCP tool calls when
  `approvalPolicy=never` AND `sandbox=dangerFullAccess`. Children
  spawned by the runtime still inherit the operator's permissions.
- The MCP shim lives at `services/daemon/orchestrator/mcp_shim.py`.
  Codex spawns it as a child process; it relays `tools/call` over a
  Unix socket (`/run/user/$UID/remotex-orch-<sid>.sock`) to a
  `BridgeServer` running inside the daemon.

## What NOT to do

- Don't restart docker containers that aren't `remotex-*` — there are
  unrelated `cdx-chat-*` services in this docker daemon.
- Don't run `git push --force` to main without asking.
- Don't commit anything under `~/.codex/sessions/` — those are user
  rollout files, not part of the repo.
