# Remotex services — relay + daemon

The backend for Remotex: the central relay that rendezvous-routes
clients to hosts, and the per-host daemon that spawns `codex
app-server` on each machine you want to reach. Verified end-to-end
with `scripts/e2e_test.py`.

## Layout

```
services/
├── relay/app.py                   aiohttp relay + SQLite inventory + WS routing
├── daemon/
│   ├── __main__.py                CLI: init / run / status
│   ├── config.py                  TOML config, cross-platform paths
│   ├── adapters.py                MockCodexAdapter + StdioCodexAdapter skeleton
│   └── client.py                  outbound WSS client, per-session runners
├── web/index.html                 single-file web control UI
├── scripts/e2e_test.py            in-process end-to-end test
└── docs/
    ├── architecture.md            topology, trust boundaries, failure modes
    ├── codex_app_server_protocol.md    integration notes for the real Codex
    └── production_plan.md         what to change before real users see it
```

## Requirements

- Python 3.11+
- `pip install aiohttp` (the only non-stdlib dependency)

## Run locally

```bash
# Terminal 1 — the relay (serves API + web UI on http://127.0.0.1:8080)
python3 relay/app.py --host 127.0.0.1 --port 8080

# Terminal 2 — a daemon (uses the seeded demo bridge token)
python3 -m daemon init \
    --relay-url ws://127.0.0.1:8080/ws/daemon \
    --bridge-token demo-bridge-token \
    --nickname devbox \
    --mode mock \
    --config ./demo-config.toml
python3 -m daemon run --config ./demo-config.toml

# Browser
open http://127.0.0.1:8080/
# Token field is pre-filled with demo-user-token — click Load hosts,
# pick the demo host, Open session, type a prompt.
```

## Run the e2e test

```bash
python3 scripts/e2e_test.py
```

Expected output ends with `E2E: OK — full flow exercised relay <-> daemon <-> client`.
The test boots relay + daemon in-process, drives the REST API and a
client WebSocket, and asserts the scripted sequence of session events
arrives in order.

## Demo credentials

On first run the relay seeds a demo user and host so you can try the
flow without running setup:

- user token: `demo-user-token`
- bridge token: `demo-bridge-token` (bound to a seeded host)

These are prototype conveniences; see `docs/production_plan.md` for
how to replace them with Keycloak-issued credentials.

## What's real vs. mocked

- **Relay transport** — real. The WS routing, SQLite inventory, bearer
  auth middleware, and REST endpoints all work.
- **Daemon → relay** — real. Outbound WSS with hello + session routing
  and exponential backoff reconnect.
- **Codex integration** — mocked. The default adapter emits a scripted
  sequence of events shaped like the real Codex App Server protocol.
  `daemon/adapters.py::StdioCodexAdapter` is the skeleton for the real
  stdio bridge; see `docs/codex_app_server_protocol.md`.
- **User auth** — token lookup against SQLite only. No Keycloak yet.

## Next steps

Pick whichever produces the most value next:

1. Flesh out `StdioCodexAdapter` against a real `codex app-server`
   install. This is the biggest jump toward usable.
2. Swap the web UI for the Next.js client from `src/` (uses the
   existing wireframes).
3. Replace the token lookup with a Keycloak OIDC verifier.
