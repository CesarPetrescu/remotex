# Remotex services

The Python backend has two processes with deliberately different jobs:

- `relay` is the public rendezvous service. It authenticates users and hosts,
  stores inventory in Postgres, and routes HTTP/WebSocket traffic.
- `daemon` runs beside Codex on every controlled machine. It connects outward
  to the relay and starts the official `codex app-server` locally.

The relay never reads `~/.codex/auth.json` and does not call OpenAI. Codex owns
its own authentication and conversation history on the daemon host.

## Architecture

```text
web / Android / iPhone
        │  HTTPS + WSS, user token
        ▼
┌──────────────────────── relay ────────────────────────┐
│ REST API              WebSocket hub       Postgres    │
│ hosts/threads/files    fan-out + replay    inventory   │
└───────────────────────────┬────────────────────────────┘
                            │ WSS, bridge token
                            ▼
┌──────────────────────── daemon ───────────────────────┐
│ session adapter       admin Codex       telemetry      │
│ stdio subprocess or   thread/list +     CPU/RAM/GPU/   │
│ shared Unix socket    model/list + fs   network        │
└───────────────────────────┬────────────────────────────┘
                            │ JSON-RPC: stdio or WebSocket-over-UDS
                            ▼
                    codex app-server
```

### State ownership

| State | Owner |
| --- | --- |
| Users, hosts, bridge keys, session inventory | Postgres |
| Online sockets, replay buffers, pending prompts, active-turn locks | Relay memory |
| Codex threads and rollout history | Daemon host / Codex |
| Codex/OpenAI credentials | Daemon host / Codex |
| Daemon connection settings | `~/.remotex/config.toml` |

The in-memory relay state makes the current deployment single-relay. Restarting
the relay preserves inventory but drops active routes and replay buffers.

## Runtime flow

1. The daemon authenticates to `/ws/daemon` with a bridge token and reports its
   host identity.
2. A client lists hosts, reserves a session with `POST /api/sessions`, then
   authenticates to `/ws/client` with a user token.
3. Only after the client is attached does the relay send `session-open` to the
   daemon, so the first event cannot be lost.
4. The daemon creates a mock adapter or a real Codex adapter. The default
   `stdio` mode gives each session its own `codex app-server`; opt-in `shared`
   mode uses Codex's Unix control socket so terminal and Remotex clients can
   observe the same loaded threads.
5. Client frames become Codex JSON-RPC requests. Codex notifications become
   normalized `session-event` frames and fan out to every attached client.
6. Sequence numbers and a bounded replay buffer let clients reconnect without
   replacing other viewers. Pending approval and user-input prompts are
   restored separately, and the first response wins.
7. A separate owner-authenticated `/ws/inventory` channel notifies browsers
   when that owner's hosts or threads change; clients then refresh the relevant
   REST inventory without polling.

## Layout

```text
services/
├── relay/
│   ├── app.py                  route table + `main()`; everything else is a sibling module
│   ├── hub.py                  in-memory routing: daemons, clients, replay buffers, pending prompts
│   ├── store.py                asyncpg inventory (users, hosts, bridge keys, sessions)
│   ├── auth.py                 bearer-token extraction + `require_user`
│   ├── limits.py               REMOTEX_MAX_FILE_BYTES → HTTP body + WS frame caps
│   ├── models.py               default model/effort fallback served at /api/models
│   ├── logging.py              JSON formatter + `audit()` + `user_hash()`
│   ├── middleware/rate_limit.py   per-remote + per-token REST buckets; WS connect cap
│   └── handlers/
│       ├── ws_daemon.py        daemon socket: hello → welcome → frame loop
│       ├── ws_client.py        client socket: attach, replay, forward, grace watchdog
│       ├── sessions.py         POST /api/sessions (id reservation, thread reuse, TTL sweeper)
│       ├── hosts.py            host CRUD, bridge-key issue/list/revoke, cached telemetry
│       ├── daemon_rpc.py       shared REST→daemon request/response plumbing
│       ├── threads.py          proxied thread/list
│       ├── fs.py               proxied filesystem ops
│       ├── models_route.py     GET /api/models + GET /api/hosts/{id}/models
│       ├── ws_inventory.py     owner-scoped host/thread change notifications
│       └── static.py           SPA + asset serving
├── daemon/
│   ├── __main__.py             CLI: init / run / status
│   ├── config.py               TOML config, cross-platform paths, insecure-relay guard
│   ├── limits.py               same REMOTEX_MAX_FILE_BYTES ceiling as the relay
│   ├── client.py               outbound WSS client, per-session runners, fs + model handlers
│   ├── telemetry.py            CPU/memory/GPU/network sampling (psutil, /proc fallback)
│   └── adapters/
│       ├── base.py             SessionEvent envelope + SessionAdapter ABC
│       ├── factory.py          mode → adapter
│       ├── stdio.py            the real bridge to `codex app-server` (the big one)
│       ├── shared.py           shared app-server WS/UDS multiplexer + thread routing
│       ├── mock.py             scripted events for tests and offline demos
│       ├── admin.py            long-lived codex for cheap read-only ops
│       │                       (thread/list, model/list, fs/readDirectory)
│       ├── elicitation.py      MCP elicitation ↔ structured user input
│       ├── items.py            Codex item type → relay item_type, field flattening
│       ├── reasoning.py        reasoning-content summarization
│       ├── permissions.py      UI ↔ Codex permission/settings translation
│       ├── rollout.py          reads ~/.codex/sessions rollout files for local replay
│       └── codex_config.py     enables the Codex `goals` feature in config.toml
├── tests/                      pytest suite (hub, all WS paths, shared/stdio adapters,
│                               prompts/settings, reservations, reconnect, limits + fs,
│                               logging, models, telemetry, store + daemon helpers)
├── scripts/e2e_test.py         in-process relay ↔ daemon ↔ client end-to-end test
└── docs/
    ├── architecture.md         topology, frames, lifecycle, failure modes, known gaps
    └── codex_app_server_protocol.md   what the daemon actually sends/receives
```

## Requirements

- Python 3.11+
- `pip install -r requirements.txt` (aiohttp, asyncpg, psutil)
- A Postgres database for the relay — **not optional**, the store raises
  on startup without `RELAY_DATABASE_URL`.
- `codex` on PATH and logged in, for real `stdio` or `shared` mode.

## Run locally

### 1. Postgres

The bundled Compose stack doesn't publish a host port, so for a local
relay run your own — this matches what CI does:

```bash
docker run --rm -d --name remotex-pg -p 5432:5432 \
  -e POSTGRES_DB=remotex -e POSTGRES_USER=remotex -e POSTGRES_PASSWORD=remotex-dev \
  pgvector/pgvector:pg16
```

### 2. Relay

```bash
export RELAY_DATABASE_URL=postgresql://remotex:remotex-dev@127.0.0.1:5432/remotex
export RELAY_SEED_DEMO=1        # opt-in; see "Demo credentials" below
python3 relay/app.py --host 127.0.0.1 --port 8080
```

It serves the API and, if you've built `apps/web`, the UI. Without
`RELAY_SEED_DEMO` an empty database stays empty and there is nothing to
log in as.

### 3. Daemon

```bash
python3 -m daemon init \
    --relay-url ws://127.0.0.1:8080/ws/daemon \
    --bridge-token demo-bridge-token \
    --nickname devbox \
    --mode stdio \
    --default-cwd "$PWD" \
    --config ./demo-config.toml
python3 -m daemon run --config ./demo-config.toml
```

`--mode mock` swaps in the scripted adapter if you want to exercise the
UI without a real Codex. `python3 -m daemon status` prints the loaded
config and the host identity it will report.

The default `stdio` mode remains the portable, isolated option. On Unix,
`shared` mode can join Codex's local app-server daemon instead:

```bash
codex app-server daemon start
# Set mode = "shared" under [daemon] in the Remotex config, then restart it.
```

The socket defaults to
`$CODEX_HOME/app-server-control/app-server-control.sock` (or
`~/.codex/app-server-control/app-server-control.sock`). Set
`codex_socket_path` under `[daemon]` only for a custom socket. A plain `codex`
TUI automatically probes the default socket when its launch options can be
replayed by the shared server. Invocations with config/profile/strict-config
or other non-replayable overrides may deliberately start an isolated embedded
server instead, so those sessions will not appear live in Remotex.
When the default socket is absent (for example after a reboot), Remotex also
runs the idempotent `codex app-server daemon start` command. Custom socket
paths remain operator-managed.
That Codex lifecycle command currently requires the standalone managed
installation; npm-only users can supervise `codex app-server --listen
unix://` themselves or use the portable `stdio` mode.

`run` refuses to start when `relay_url` is cleartext `ws://` to anything
but loopback — that would ship the bridge token and every prompt in the
clear. Use `wss://`, or pass `--allow-insecure` at `init` time (it sets
`allow_insecure = true` in the config and logs a warning on every start).
The loopback URL above needs no flag.

### 4. Client

Open <http://127.0.0.1:8080/> and paste `demo-user-token` (the seeded one
from step 2 — it exists only because `RELAY_SEED_DEMO=1` was set), or run
the Vite dev server from `apps/web` (see that README) for hot reload.

## Tests

```bash
# Unit tests — no database, no codex binary needed
pip install -r requirements-dev.txt
pytest tests -v

# End-to-end — needs a disposable Postgres
E2E_DATABASE_URL=postgresql://remotex:remotex-dev@127.0.0.1:5432/remotex \
E2E_ALLOW_DESTRUCTIVE_RESET=1 \
python3 scripts/e2e_test.py
```

The e2e drives the relay with the demo credentials, so it sets
`RELAY_SEED_DEMO=1` for itself — you don't have to.

The e2e boots relay + daemon in-process, drives the REST API and a client
WebSocket, and asserts the scripted event sequence arrives in order. It
ends with `E2E: OK - full flow exercised relay <-> daemon <-> client`.

**Use a disposable database** — it drops and recreates the `inventory_*`
tables before running.

Adapter tests must not require a real `codex` binary unless they carry
the same `pytest.skip` guard the existing ones use; CI has no codex
installed. For codex-dependent verification, probe a live app-server by
hand instead (see `docs/codex_app_server_protocol.md`).

## Demo credentials

Seeding is **opt-in and off by default**. Start the relay with
`RELAY_SEED_DEMO=1` (`1` / `true` / `yes`) and, on an empty database, it
creates a user, a host row, and a bridge key so you can try the flow
without a setup dance:

- user token: `demo-user-token`
- bridge token: `demo-bridge-token` (bound to the seeded host)

> **Both tokens are hardcoded in this public repo.** A reachable relay
> that seeds them is a relay anyone on the internet can drive: they can
> list your hosts, open sessions, and run turns on them. Only ever set
> `RELAY_SEED_DEMO` on loopback or on a throwaway box. Leave it unset in
> Compose and anywhere behind Caddy.

For a real deployment, insert your own user row and mint bridge keys with
`POST /api/hosts/{id}/api-key`. Tokens are stored as `sha256(token)`, so a
minted key's plaintext is shown exactly once — afterwards only its
`key_id` (first 12 chars of the hash) is visible, which is also what
`GET /api/hosts/{id}/api-key` lists and what
`POST /api/hosts/{id}/api-key/revoke` accepts. Replacing these with
OIDC-issued credentials is the top item under "Known gaps" in
`docs/architecture.md`.

## What's real

- **Relay transport** — real. WS routing, Postgres inventory, bearer auth
  (hashed at rest), rate limiting, REST proxying to the daemon, sequenced
  replay with an explicit `replay-gap` when the buffer fell short,
  owner-scoped live host/thread inventory notifications, and one
  `REMOTEX_MAX_FILE_BYTES` ceiling shared by the HTTP body cap and every
  WebSocket leg.
- **Daemon → relay** — real. Outbound WSS with a 10s welcome deadline,
  exponential backoff and equal jitter (1s → 30s), stable-connection reset,
  slower authentication retries, clean replacement handling, session runners,
  and host telemetry (including every NVIDIA GPU reported by `nvidia-smi`).
  A lost socket resumes threads after reconnect; an
  in-flight `stdio` turn is failed explicitly because its child Codex process
  cannot survive the daemon-side adapter teardown. Shared Codex threads remain
  owned by Codex's app-server daemon and can be resumed after reconnect.
- **Codex integration** — real. The default `stdio` mode spawns an isolated
  `codex app-server`; Unix-only `shared` mode connects to Codex's local
  WebSocket-over-Unix-socket control plane. Both perform the handshake, stream
  turns, and handle approvals, user-input prompts, slash commands, thread goals,
  token usage, image attachments, active-turn steering, MCP elicitation,
  thread resume, and Codex-resolved model/effort/permission settings.
- **Model list** — real, and host-scoped. `GET /api/hosts/{id}/models`
  asks that host's Codex (`model/list` through the admin adapter); if the
  host cannot supply it, the fallback entry leaves model selection to Codex.
- **Mock adapter** — still ships, opt-in via `--mode mock`. The e2e test
  drives it so CI needs no codex binary.
- **User auth** — hashed token lookup against Postgres. No OIDC yet;
  that's the top item under "Known gaps" in `docs/architecture.md`.
