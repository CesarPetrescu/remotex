# CLAUDE.md — repo conventions for AI agents

This file is loaded into every Claude Code session in this repo.
Keep it short. Keep it accurate. **For deeper architecture, protocol
shape, and test workflow notes, read `AGENTS.md` (sibling of this
file).**

## What this repo is

**Remotex** is a remote-control plane for OpenAI's Codex CLI. The
machine running Codex never needs an inbound port — both the host
daemon and the user's clients dial out to a relay you control. End
users get a Web UI, Android app, and iPhone app that all attach to
the same `codex app-server` process via WebSocket fan-out.

```
┌─────────────┐  HTTPS+WSS   ┌──────────────┐  WSS (outbound)  ┌──────────────┐  stdio
│ Web/Android │─────────────▶│ Remotex relay│◀─────────────────│ Host daemon  │────────▶ codex app-server
│ /iPhone     │              │ aiohttp      │  bridge token    │ Python       │          (OpenAI)
└─────────────┘              └──────────────┘                   └──────────────┘
```

The relay is a rendezvous + auth point only — it never sees the
user's OpenAI auth. Codex runs as a child process of the daemon.

## Layout (one-line reminders)

- `services/daemon/` — Python daemon that runs on each user's host;
  spawns `codex app-server` subprocesses and bridges them to the
  relay. Run as the systemd user unit `remotex-daemon` (NOT in
  docker).
- `services/relay/` — aiohttp relay; routes between web/Android/iOS
  clients and the daemons. Lives in the `remotex-relay-1` docker
  container (compose file at `deploy/docker-compose.yml`).
- `apps/web/` — Vite + React UI. Built into the relay docker image
  at image build time (`deploy/Dockerfile.relay`).
- `android/` — Compose client. Build via `android/build.sh` (auto-
  detects the LAN IP for the relay URL); NOT via plain Gradle (the
  default URL is `10.0.2.2`, which only works on emulators).
- `apple/` — SwiftUI iPhone client.

## Codex source clone (mandatory before touching codex protocol)

The codex JSON-RPC app-server protocol is large and undocumented
outside the codex repo itself. Before adding a new event handler,
new MCP tool, new approval flow, etc., **clone the codex source**:

```bash
test -d /tmp/codex || git clone https://github.com/openai/codex /tmp/codex
```

Then grep `/tmp/codex/codex-rs/` for the wire shape you need —
`protocol/src/`, `app-server-protocol/src/protocol/v2.rs`,
`codex-mcp/src/`. Don't guess from training data; codex moves fast
and the wire format changes between minor versions. See AGENTS.md
for the canonical files to read.

## Test the change against a real codex BEFORE writing code

When implementing a feature that depends on codex behavior (a new
notification, an approval flow, an MCP detail), don't trust your
mental model of what codex emits. Drive a real `codex app-server`
manually first:

1. `codex app-server` accepts JSON-RPC on stdio. Spawn it with
   `asyncio.create_subprocess_exec("codex", "app-server", …)`.
2. Send the minimal initialize → initialized → thread/start
   handshake (see `services/daemon/adapters/stdio.py:start()` for
   the canonical sequence).
3. Send the request you're trying to implement; print every line
   codex sends back. Find the field shape, then encode that shape
   into the daemon adapter.

`/tmp/codex-resume-probe.py` (created during earlier debugging) is
an example pattern. Reuse this approach for any "what does codex
actually do here" question instead of guessing.

## Restart rituals after editing code

| You changed | Run |
|---|---|
| `services/daemon/**` | `systemctl --user restart remotex-daemon` |
| `services/relay/**` or `apps/web/**` | `cd deploy && docker compose build relay && docker compose up -d --force-recreate relay` |
| `android/**` | `cd android && ./build.sh install` |

The relay docker image bakes the web bundle in at image build time,
so any web change requires a relay image rebuild.

## Relay URL (matters for Android builds)

The relay binds inside docker on `:8080`; the host port is set in
`deploy/.env` (currently `RELAY_HOST_PORT=18080`,
`RELAY_HOST_BIND=0.0.0.0`). For LAN access from a phone:
`http://<LAN-IP>:18080`. The Android build script handles this
auto-magically — see `android/README.md`.

## Adapter / runtime test conventions

- Daemon Python tests live in `services/tests/`. Run via the
  daemon's venv at `~/.local/share/remotex/venv/bin/python -m pytest …`.
- Don't add tests that require a real `codex` binary unless you
  also guard them with the same `pytest.skip` pattern other adapter
  tests use — CI doesn't have the binary installed.
- For codex-dependent verification, use the manual probe approach
  above instead of pytest.

## What NOT to do

- Don't restart docker containers that aren't `remotex-*` — there
  are unrelated `cdx-chat-*` services in this docker daemon.
- Don't run `git push --force` to main without asking.
- Don't commit anything under `~/.codex/sessions/` — those are user
  rollout files, not part of the repo.
- Don't add Android features that bypass `android/build.sh` — the
  default Gradle relayUrl is wrong for real devices.
- Don't trust your training-data memory of the codex JSON-RPC
  schema. Read `/tmp/codex/codex-rs/` (clone it if missing).
