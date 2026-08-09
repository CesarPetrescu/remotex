# CLAUDE.md — repo conventions for AI agents

This file is loaded into every Claude Code session in this repo.
Keep it short. Keep it accurate. **For deeper architecture, protocol
shape, and test workflow notes, read `AGENTS.md` (sibling of this
file).**

## Shared agent memory — read and write these

Several agents work this repo in parallel and cannot see each other's
sessions. These three log files are the shared work state:

| File | Read | Write |
|---|---|---|
| `WorkLog.md` | last ~3 entries, **before you start** | an entry when you finish or stop |
| `Issues.md` | when a bug looks familiar — it may be filed | file anything you notice and walk past |
| `ToDo.md` | before picking up work | tick items off; add ones you analysed but didn't do |

Non-negotiable: **log your work in `WorkLog.md` before you report done.**
An unlogged change is invisible to the next agent and will get
re-investigated or clobbered. Say plainly what you verified and what you
only assumed. Never edit someone else's entry — append a new one.

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

## Hard gate: inspect Codex source before daemon ↔ Codex work

The codex JSON-RPC app-server protocol is large and undocumented
outside the codex repo itself. This gate applies to implementation,
diagnosis, or review of daemon JSON-RPC, adapters, item translation,
approvals, permissions, goals, thread configuration, rollout history,
or Codex process lifecycle.

**Before editing code**, clone Codex into `/tmp/codex`, or refresh the
existing checkout, then check the installed binary version:

```bash
if test -d /tmp/codex/.git; then
  git -C /tmp/codex pull --ff-only
else
  git clone https://github.com/openai/codex /tmp/codex
fi
codex --version
```

If refresh fails, do not reset or delete the checkout. Say that it may be
stale, inspect it anyway, and probe the installed app-server. Prefer a source
tag matching the installed version when available; observed binary behavior
wins when it differs from the checkout.

Then grep and read `/tmp/codex/codex-rs/` yourself for the wire shape —
`protocol/src/`, `app-server-protocol/src/protocol/v2/`,
`codex-mcp/src/`. Do not guess from training data, Remotex fixtures, or
client types. Codex moves fast and wire formats change between minor
versions. See AGENTS.md for the canonical files to read.

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

The relay binds inside Docker on `:8080`. Base Compose publishes it using
`RELAY_HOST_PORT` / `RELAY_HOST_BIND` from the gitignored `deploy/.env`;
the SparkTunnel override publishes no host port and reaches `relay:8080`
on the private Compose network. Read the selected Compose files and `.env`
instead of assuming a mode. For direct LAN access from a phone, use
`http://<LAN-IP>:<RELAY_HOST_PORT>`; see `android/README.md`.

The relay also needs `RELAY_DATABASE_URL`; it raises on startup without
one. Compose supplies it, running `relay/app.py` from source does not.

## Two env vars that will bite you

- `RELAY_SEED_DEMO` — the `demo-user-token` / `demo-bridge-token` pair
  is only seeded when this is truthy, and it defaults to **off** (the
  tokens are public). A fresh database has no users at all. Set it for
  a loopback dev relay; never in Compose or on anything reachable.
- `REMOTEX_MAX_FILE_BYTES` — one ceiling (default 25 MiB) for every
  byte path. The relay's HTTP body cap and both WS `max_msg_size`
  values derive from it, and the daemon reads the same var. Change one
  side without the other and an oversize frame kills the daemon socket
  — taking every session on that host with it. Oversize must always
  produce an explicit JSON error, and it has to be caught **before the
  write**: aiohttp closes the socket before the receiving handler sees
  an oversize frame, so only the sender can report it.

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
- Don't log a raw bearer or bridge token. Audit lines take
  `user_hash=user_hash(token)` — the *raw* presented token, so it lines
  up with `key_id`; passing the already-hashed `user["token"]` column
  gives a hash of a hash. Bridge keys are named by `key_id`.
- Don't trust your training-data memory of the codex JSON-RPC
  schema. Read `/tmp/codex/codex-rs/` (clone it if missing).
