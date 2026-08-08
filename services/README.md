# Remotex services — relay + daemon

The backend for Remotex: the central relay that rendezvous-routes clients
to hosts, and the per-host daemon that spawns `codex app-server` on each
machine you want to reach. Verified end to end by `scripts/e2e_test.py`.

## Layout

```
services/
├── relay/
│   ├── app.py                  route table + `main()`; everything else is a sibling module
│   ├── hub.py                  in-memory routing: daemons, clients, replay buffers, pending prompts
│   ├── store.py                asyncpg inventory (users, hosts, bridge keys, sessions)
│   ├── auth.py                 bearer-token extraction + `require_user`
│   ├── models.py               model/effort catalogue served at /api/models
│   ├── logging.py              JSON formatter + `audit()`
│   ├── middleware/rate_limit.py   per-token bucket, 429 + Retry-After
│   └── handlers/
│       ├── ws_daemon.py        daemon socket: hello → welcome → frame loop
│       ├── ws_client.py        client socket: attach, replay, forward, grace watchdog
│       ├── sessions.py         POST /api/sessions (id reservation + thread reuse)
│       ├── hosts.py            host CRUD, bridge-key issuance, cached telemetry
│       ├── threads.py          proxied thread/list
│       ├── fs.py               proxied filesystem ops
│       ├── models_route.py     GET /api/models
│       └── static.py           SPA + asset serving
├── daemon/
│   ├── __main__.py             CLI: init / run / status
│   ├── config.py               TOML config, cross-platform paths
│   ├── client.py               outbound WSS client, per-session runners, fs handlers
│   ├── telemetry.py            CPU/memory/GPU/network sampling (psutil, /proc fallback)
│   └── adapters/
│       ├── base.py             SessionEvent envelope + SessionAdapter ABC
│       ├── factory.py          mode → adapter
│       ├── stdio.py            the real bridge to `codex app-server` (the big one)
│       ├── mock.py             scripted events for tests and offline demos
│       ├── admin.py            long-lived codex for cheap read-only ops
│       ├── items.py            Codex item type → relay item_type, field flattening
│       ├── reasoning.py        reasoning-content summarization
│       ├── permissions.py      UI permission chip → sandboxPolicy/approvalPolicy
│       ├── rollout.py          reads ~/.codex/sessions rollout files for local replay
│       └── codex_config.py     enables the Codex `goals` feature in config.toml
├── tests/                      pytest suite (hub, rate limit, logging, models, daemon helpers)
├── scripts/e2e_test.py         in-process relay ↔ daemon ↔ client end-to-end test
└── docs/
    ├── architecture.md         topology, frames, lifecycle, failure modes
    ├── codex_app_server_protocol.md   what the daemon actually sends/receives
    └── production_plan.md      what's left before real users
```

## Requirements

- Python 3.11+
- `pip install -r requirements.txt` (aiohttp, asyncpg, psutil)
- A Postgres database for the relay — **not optional**, the store raises
  on startup without `RELAY_DATABASE_URL`.
- `codex` on PATH and logged in, for the daemon's default `stdio` mode.

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
python3 relay/app.py --host 127.0.0.1 --port 8080
```

It serves the API and, if you've built `apps/web`, the UI. On first run
against an empty database it seeds the demo credentials below.

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

### 4. Client

Open <http://127.0.0.1:8080/> and paste `demo-user-token`, or run the
Vite dev server from `apps/web` (see that README) for hot reload.

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

On first run against an empty database the relay seeds a user, a host
row, and a bridge key so you can try the flow without a setup dance:

- user token: `demo-user-token`
- bridge token: `demo-bridge-token` (bound to the seeded host)

These are prototype conveniences. `docs/production_plan.md` covers
replacing them with OIDC-issued credentials.

## What's real

- **Relay transport** — real. WS routing, Postgres inventory, bearer
  auth, rate limiting, REST proxying to the daemon, sequenced replay.
- **Daemon → relay** — real. Outbound WSS with exponential backoff and
  jitter, session runners, host telemetry.
- **Codex integration** — real, and the default. `StdioCodexAdapter`
  spawns `codex app-server`, performs the handshake, streams turns, and
  handles approvals, user-input prompts, slash commands, thread goals,
  token usage, image attachments, and thread resume.
- **Mock adapter** — still ships, opt-in via `--mode mock`. The e2e test
  drives it so CI needs no codex binary.
- **User auth** — token lookup against Postgres. No OIDC yet; that's the
  top item in `docs/production_plan.md`.
