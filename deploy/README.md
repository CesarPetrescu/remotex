# Remotex deployment

Self-host the relay + web client with one command. This is the
"single box, no Kubernetes" path; see the "Known gaps" section of
`services/docs/architecture.md` for what needs to be done before
you point real users at it.

The supported layout is one small Docker Compose stack for the control plane,
plus a native daemon on every machine that runs Codex.

```text
internet / LAN
      │
      ├── Caddy (optional inbound TLS) ─────────────┐
      └── PhotonSpark edge                         │
                  ▲ outbound WSS                   │
          SparkTunnel connector ──────────────────┤ private HTTP/WS
                                                  ▼
                                      relay container ─── Postgres container
                                                  ▲
                                                  │ outbound ws/wss
                                          host daemon ─── codex app-server
```

The web bundle is compiled into the relay image and served by the relay. There
is no separate frontend container, and the host daemon should not run in
Docker because it needs the host filesystem, Codex installation, credentials,
and tools.

## Files

```text
deploy/
├── Dockerfile.relay          builds React, then the Python relay image
├── Dockerfile.sparktunnel    pinned PhotonSpark connector image
├── docker-compose.yml        relay, Postgres, optional ingress profiles
├── docker-compose.sparktunnel.yml removes host ports for SparkTunnel
├── Caddyfile                 HTTPS/WSS reverse proxy
├── .env.example              deployment settings
├── entrypoint.sh             unprivileged relay startup
├── sparktunnel-entrypoint.sh validates and starts the connector
├── install-daemon.sh         Linux daemon installer
├── remotex-daemon.service    systemd unit template
└── README.md
```

## Start locally

SparkTunnel is optional. The base Compose file needs no PhotonSpark account or
connector token and publishes the relay on loopback:

```bash
cd deploy
docker compose up -d --build
```

The relay binds to `127.0.0.1:8080` on the host. Inventory (users, hosts,
bridge keys, sessions) lives in the Postgres volume
(`remotex_search-data`), so restarts preserve it. Tokens are stored
hashed — an old install's plaintext rows are rewritten in place the first
time the new relay starts.

Web control UI is at http://127.0.0.1:8080/ - it's the
`apps/web/` React client, compiled into static assets during the
image build and served by the relay itself (no separate web
container).

For a different local interface or port, copy `.env.example` to `.env` and
set `RELAY_HOST_BIND` / `RELAY_HOST_PORT`. Binding to `0.0.0.0` exposes the
relay to the LAN; keep `RELAY_TRUST_PROXY=0` when clients connect directly.
The SparkTunnel override described below is the only mode that removes this
host port.

For any persistent or published deployment, replace the bundled Postgres
compatibility default with a random 256-bit password in the ignored,
mode-0600 `.env` file:

```bash
openssl rand -hex 32
```

Paste the result as `POSTGRES_PASSWORD=...`. On an existing Postgres volume,
changing only `.env` is not enough: rotate the database role password to the
same value before recreating the relay, or Postgres will reject it.

## Getting a token

A fresh database has **no users**. Compose deliberately ships
`RELAY_SEED_DEMO=0`: the demo credentials (`demo-user-token` /
`demo-bridge-token`) are hardcoded in this public repo, so any relay that
seeds them can be driven by anyone who can reach it — listing your hosts,
opening sessions, and running turns on your machines.

For a throwaway box you are only poking at from loopback, you can opt in:

```bash
RELAY_SEED_DEMO=1 docker compose up -d --build
curl -H "Authorization: Bearer demo-user-token" http://127.0.0.1:8080/api/hosts
```

Never do that on anything published, including behind the `tls` profile.
For a real deployment there is no signup flow yet, so insert your own row
in `inventory_users` — the `token` column holds `sha256(<your token>)`
hex, not the token:

```bash
docker compose exec postgres psql -U remotex -d remotex -c \
  "INSERT INTO inventory_users(token, email, created_at)
   VALUES (encode(sha256('<your token>'::bytea),'hex'), 'you@example.com',
           extract(epoch from now())::bigint);"
```

(Generate the token with something like `openssl rand -base64 32`, and
mind that it lands in your shell history.)

Then register each machine and mint a bridge key for it through the API:

```bash
curl -X POST -H "Authorization: Bearer <your token>" \
     -H 'Content-Type: application/json' -d '{"nickname":"mybox"}' \
     http://127.0.0.1:8080/api/hosts          # → {"id": "host_…"}

curl -X POST -H "Authorization: Bearer <your token>" \
     http://127.0.0.1:8080/api/hosts/<host_id>/api-key
```

The response is the **only** time the key's plaintext exists; the relay
keeps just its hash and a 12-char `key_id`. List the live ones with
`GET /api/hosts/{id}/api-key`, and retire one with:

```bash
curl -X POST -H "Authorization: Bearer <your token>" \
     -H 'Content-Type: application/json' -d '{"key_id":"<key id>"}' \
     http://127.0.0.1:8080/api/hosts/<host_id>/api-key/revoke
```

Revoking also drops that host's live daemon socket (close code 4401) —
the relay doesn't record which key a socket authenticated with, so it
disconnects and lets a daemon holding a still-valid key reconnect.

## Session reconnect grace

`RELAY_CLIENT_RECONNECT_GRACE_SECONDS` controls how long the relay keeps
an **idle** daemon session alive after a web or mobile socket
disappears. The default is 75 seconds, which covers phone sleep,
Wi-Fi/LTE switches, and browser reloads without leaving abandoned Codex
processes around for long.

A session with a turn still in flight is exempt: it stays alive as long
as the daemon keeps emitting events, and is only reaped after
`RELAY_SESSION_STALL_CEILING_SECONDS` (default 7200) of complete
silence. So a long agent run survives you closing the tab.

`RELAY_SESSION_REPLAY_LIMIT` (default 1000) is how many events per
session the relay buffers for reconnecting clients to catch up on. It's
in-memory — a relay restart drops it. When a reconnecting client asks for
frames that already fell out of the buffer, the relay sends it a
`replay-gap` frame first and the client renders an "earlier events
unavailable" marker, so a truncated transcript is never passed off as a
complete one.

`RELAY_SESSION_RESERVATION_TTL_SECONDS` (default 600) is how long a
`POST /api/sessions` reservation survives with no client ever attaching
before a background sweeper closes the row and forgets it.

The host daemon also reconnects automatically when its outbound WSS socket
dies. It waits for relay `welcome` for at most 10 seconds, then retries with
equal-jitter exponential backoff from 1 to 30 seconds; a connection healthy
for 60 seconds resets the next outage to the short delay. Invalid bridge
credentials retry slowly, and a daemon displaced by a newer process exits
instead of the two processes continually evicting each other.

The saved session and Codex thread resume after reconnect in both modes. In
`stdio` mode, however, losing the daemon socket tears down that session's local
Codex child, so an active turn cannot survive: the relay emits a failed
`turn-completed`, clears stale prompts, and releases the turn slot. In `shared`
mode the managed Codex app server owns the turn independently; after reconnect,
Remotex resumes the thread and reconciles its live snapshot instead of starting
a second process.

## File transfer ceiling

`REMOTEX_MAX_FILE_BYTES` (default `26214400` = 25 MiB) is the single knob
for anything that moves as bytes — workspace uploads, file reads, image
attachments. The relay derives its HTTP body cap (`+1 MiB` of framing
slack) and the WebSocket frame cap on **both** `/ws/client` and
`/ws/daemon` (`×4/3` for base64, `+4 MiB` for the JSON envelope) from it,
so raising it raises per-connection memory too.

The host daemon reads the same variable — **keep the two in sync**. If the
daemon's ceiling is higher than the relay's, an oversized frame gets
dropped by aiohttp, which kills the daemon socket and with it every
session on that host. Over-limit transfers are answered with an explicit
error instead (HTTP 413 with `{error, max_bytes, size}`, or an error
response frame), never a silent disconnect — the daemon measures each
frame before it writes it, because the receiving side cannot report an
oversize frame at all: aiohttp closes the socket before the handler sees
one.

The web and Android clients mirror the default client-side; a deployment
that raises the server limit should rebuild the web bundle with
`VITE_REMOTEX_MAX_FILE_BYTES` set to match and update `MAX_FILE_BYTES` in
`android/.../ui/RemotexViewModel.kt`.

## Quickstart - with TLS (Caddy + Let's Encrypt)

Point a DNS record at the server first, then:

```bash
cd deploy
cp .env.example .env
$EDITOR .env
docker compose --profile tls up -d --build
```

Set `REMOTEX_HOSTNAME` and `ACME_EMAIL`. Caddy binds ports 80 and 443,
obtains certificates, and proxies HTTP and WebSocket traffic to the relay.

**Set `RELAY_TRUST_PROXY=1` in `.env` when you do this.** Behind a proxy
the relay's view of every caller is the proxy's own address, so every
client and every daemon shares a single rate-limit bucket and one noisy
peer can 429 the rest. With the flag set, the relay reads the address from
`X-Forwarded-For` (which Caddy sets) instead. Leave it off when the relay
is exposed directly — the header is caller-supplied and worthless without
something in front to overwrite it.

## Publish through SparkTunnel

[SparkTunnel](https://webhost.photonspark.com) is an alternative to the
bundled Caddy profile when this machine should have no inbound firewall rule,
public IP, or router port-forward. A connector container dials out to
PhotonSpark, which provides the public hostname, TLS, HTTP routing, and
WebSocket upgrades. A PhotonSpark account and a site configured with the
SparkTunnel deployment method are required.

This mode is opt-in: `docker-compose.sparktunnel.yml` removes the relay's host
port only when it is explicitly included. It does not change local or Caddy
deployments that use `docker-compose.yml` by itself.

Create the tunnel in the PhotonSpark dashboard and copy its one-time connector
token. Then configure the optional Compose profile:

```bash
cd deploy
cp -n .env.example .env
chmod 600 .env
$EDITOR .env
docker compose -f docker-compose.yml -f docker-compose.sparktunnel.yml \
  --profile sparktunnel up -d --build
docker compose -f docker-compose.yml -f docker-compose.sparktunnel.yml \
  --profile sparktunnel logs -f sparktunnel
```

Set `SPARK_TUNNEL_TOKEN` and `RELAY_SEED_DEMO=0` in `.env`. The connector
defaults are normally correct:

```dotenv
RELAY_SEED_DEMO=0
SPARK_TUNNEL_SERVER=https://webhost.photonspark.com
SPARK_TUNNEL_TARGET=http://relay:8080
```

The SparkTunnel override forces `RELAY_TRUST_PROXY=0`. SparkTunnel 0.2.0 does
not provide the relay with a trustworthy visitor IP and preserves
caller-supplied `X-Forwarded-For` / `X-Real-IP` values. Trusting those headers
would let a caller evade the per-address rate limit. With the safe setting,
all public visitors share the connector's TCP-peer bucket; true per-visitor IP
limits require a future PhotonSpark header contract that strips spoofed input.

The target is deliberately the relay's private Compose address. The
SparkTunnel override removes the relay's host port entirely; only containers
on the private `remotex` network can reach it directly.
Do not run the `tls` and `sparktunnel` profiles together unless you explicitly
need two public ingress paths.

The connector image downloads the
[official Linux amd64 0.2.0 binary](https://webhost.photonspark.com/api/v1/downloads/spark-tunnel/linux/amd64)
and verifies its pinned SHA-256 before installing it. The connector itself
retries a lost broker connection with exponential backoff. Compose also
restarts the container if the process exits.

SparkTunnel does not add application authentication. Anyone who can reach the
PhotonSpark hostname can reach Remotex's authentication boundary, so never
publish a relay that still uses the repository's demo tokens. Keep the
connector token out of Git and rotate it from PhotonSpark if it is exposed.
The relay adds CSP, HSTS, frame blocking, no-sniff and referrer-policy headers
to browser responses, and marks authenticated API responses `no-store`.

## Install a host daemon

The recommended Linux installation is a systemd user service:

```bash
deploy/install-daemon.sh \
  --relay-url wss://relay.example.com/ws/daemon \
  --bridge-token brg_live_replace_me \
  --nickname mybox \
  --default-cwd "$PWD"
```

With no flags, the installer prompts for required values. It creates:

```text
~/.local/share/remotex/venv/
~/.remotex/config.toml
~/.config/systemd/user/remotex-daemon.service
```

It is safe to rerun. Existing configuration is preserved unless
`--force-config` is supplied. Useful operations:

```bash
systemctl --user status remotex-daemon
journalctl --user -u remotex-daemon -f
deploy/install-daemon.sh --uninstall
```

Leave `codex_binary` at its default, `codex`, unless you intentionally want to
pin a custom executable. The generated unit searches the service account's
`~/.local/bin` (the official standalone installer), normal system locations,
and the directory of the `codex` command selected when the daemon installer
runs. That last entry supports npm under nvm or a custom global prefix and also
keeps its adjacent `node` executable reachable. Re-run this installer after
switching nvm versions or moving an npm prefix. With `--system --run-as-user`,
pass an absolute `--codex-binary` for nonstandard locations because the root
installer cannot reliably reconstruct another user's shell environment.

### Optional: share terminal Codex sessions

The installed daemon defaults to `mode = "stdio"`: every Remotex session gets
an isolated `codex app-server` child, which works on every supported OS. On a
Unix host, opt into `mode = "shared"` when terminal and Remotex clients should
attach to the same Codex threads:

```bash
codex app-server daemon start
$EDITOR ~/.remotex/config.toml   # set mode = "shared" under [daemon]
systemctl --user restart remotex-daemon
```

Remotex uses Codex's default control socket at
`$CODEX_HOME/app-server-control/app-server-control.sock` (falling back to
`~/.codex/...`). A custom location can be set as
`codex_socket_path = "/absolute/path/to/app-server-control.sock"` in the same
config. Shared mode is Unix-only. For the default socket, Remotex runs the
idempotent `codex app-server daemon start` command when the socket is absent;
custom socket paths must already be served by an app-server.
Codex's daemon lifecycle command currently requires its standalone managed
installation. With an npm-only Codex install, supervise
`codex app-server --listen unix://` yourself or keep Remotex in `stdio` mode.

After `codex app-server daemon start`, a plain `codex` command automatically
probes that default socket when its launch configuration is reusable. Codex
intentionally stays isolated when launched with config/profile/strict-config
or other non-replayable overrides; such a session cannot be mirrored live.
Standalone `stdio` mode remains available by setting `mode = "stdio"` again.

The uninstall operation removes the service but deliberately keeps the config
and virtual environment. To keep a user daemon alive after logout:

```bash
sudo loginctl enable-linger "$USER"
```

`install-daemon.sh --system` is also available for system-wide installation;
run its `--help` before using that mode because the chosen service account must
own the relevant Codex credentials and workspace files.

For a relay on the same machine, use
`ws://127.0.0.1:8080/ws/daemon`. The daemon refuses cleartext `ws://` for a
non-loopback address because that would expose its bridge token and prompts.
Use `wss://` for LAN or public relays. A deliberate plaintext LAN deployment
requires `allow_insecure = true` under `[daemon]` in the daemon config and
logs a warning on every start.

For manual or non-Linux operation:

```bash
cd services
pip install -r requirements.txt
python3 -m daemon init \
  --relay-url wss://relay.example.com/ws/daemon \
  --bridge-token <token from the relay admin flow> \
  --nickname mybox \
  --mode stdio \
  --config ./demo-config.toml
python3 -m daemon run --config ./demo-config.toml
```

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `RELAY_HOST_BIND` | `127.0.0.1` | Host interface exposed by Compose |
| `RELAY_HOST_PORT` | `8080` | Host port mapped to relay port 8080 |
| `RELAY_CLIENT_RECONNECT_GRACE_SECONDS` | `75` | Idle-client reconnect window |
| `RELAY_SESSION_STALL_CEILING_SECONDS` | `7200` | Silence limit for an in-flight turn |
| `RELAY_SESSION_REPLAY_LIMIT` | `1000` | Events retained per live session |
| `RELAY_SESSION_RESERVATION_TTL_SECONDS` | `600` | Lifetime of an unattached session reservation |
| `REMOTEX_MAX_FILE_BYTES` | `26214400` | File and derived WebSocket size ceiling |
| `RELAY_SEED_DEMO` | `0` | Opt-in public demo credentials; never enable on a published relay |
| `RELAY_TRUST_PROXY` | `0` | Trust `X-Forwarded-For` only behind a proxy that overwrites it; Caddy may use `1`, SparkTunnel forces `0` |
| `RELAY_RATE_LIMIT_REMOTE_BURST` | `120` | Per-remote REST burst capacity |
| `RELAY_RATE_LIMIT_REMOTE_PER_SECOND` | `40` | Per-remote REST refill rate |
| `RELAY_RATE_LIMIT_MAX_BUCKETS` | `10000` | Maximum entries in each REST bucket map before cleanup |
| `RELAY_RATE_LIMIT_IDLE_SECONDS` | `300` | Idle age for REST bucket eviction |
| `RELAY_WS_CONNECT_BURST` | `60` | Per-remote WebSocket connection burst capacity |
| `RELAY_WS_CONNECT_PER_SECOND` | `5` | Per-remote WebSocket connection refill rate |
| `TZ` | `Etc/UTC` | Relay container timezone |
| `POSTGRES_DB` | `remotex` | Inventory database |
| `POSTGRES_USER` | `remotex` | Inventory database user |
| `POSTGRES_PASSWORD` | `remotex-search` | Inventory database password |
| `REMOTEX_HOSTNAME` | `localhost` | Caddy hostname |
| `ACME_EMAIL` | invalid placeholder | Certificate contact email |
| `SPARK_TUNNEL_TOKEN` | none | PhotonSpark one-time connector bearer token |
| `SPARK_TUNNEL_SERVER` | `https://webhost.photonspark.com` | PhotonSpark connector endpoint |
| `SPARK_TUNNEL_TARGET` | `http://relay:8080` | Private HTTP/WebSocket target inside Compose |
| `SPARK_TUNNEL_DOWNLOAD_URL` | official Linux amd64 artifact | Connector image build source |
| `SPARK_TUNNEL_SHA256` | pinned 0.2.0 digest | Connector artifact integrity check |

Compose passes the reconnect grace, reservation TTL, file limit, demo seed,
proxy trust, and timezone variables through to the relay. The stall/replay and
rate-limit variables above are read by the relay code but are not currently in
the Compose service's `environment` list; setting them only in `.env` has no
effect. Add the variables you want to tune to that list before recreating the
relay.

## Storage and restart behavior

- `remotex_search-data` contains Postgres inventory: users, hosts, bridge
  keys, and session records.
- `remotex_relay-data` exists only for one-time migration from older SQLite
  installations.
- `remotex_caddy-data` and `remotex_caddy-config` contain Caddy state.
- Live routes, replay buffers, turn locks, and pending-prompt bookkeeping are
  in relay memory and do not survive a relay restart. An isolated `stdio` turn
  ends when its adapter disconnects; a `shared` Codex turn can keep running in
  the managed app server and is reconciled when Remotex reconnects, although
  relay-buffered events from the outage are unavailable.
- Codex thread history stays on each daemon host.

## Operations

```bash
cd deploy
docker compose ps
docker compose logs -f relay
docker compose logs -f postgres
docker compose --profile tls logs -f caddy
docker compose -f docker-compose.yml -f docker-compose.sparktunnel.yml \
  --profile sparktunnel logs -f sparktunnel
docker compose up -d --build
```

To remove the stack while keeping data:

```bash
docker compose down
```

To remove the stack and all Compose volumes:

```bash
docker compose down -v
```

The second command permanently deletes the Postgres inventory and Caddy state.

## Production limits

Docker Compose gets you a self-hosted demo, not a hardened service.
Open items tracked against the roadmap:

- Keycloak / OIDC auth. Tokens are hashed at rest and demo seeding is off
  by default, but a long-lived bearer string is still a bearer string.
- Audit retention + metrics. Audit lines are emitted on `logger=audit`
  in the relay's JSON logs, but nothing collects them.
- Bridge-key metadata: issue, list, and revoke work; expiry and an
  `issued_by` column don't exist yet.
- Single-replica only — the routing hub is process-local, so you can't
  scale the relay horizontally without sticky routing or a shared bus.

Backpressure is handled: a client that can't accept a frame within 5s is
closed with code 1013 rather than stalling the relay.

Full list and ordering: the "Known gaps" section of
`services/docs/architecture.md`.
