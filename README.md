<p align="center">
  <img src="docs/brand/logo.png" alt="Remotex" width="160" />
</p>

# Remotex

Remotex lets you control Codex sessions running on your own machines from
a browser, Android app, or iPhone app. The machine running Codex can sit
behind NAT, CGNAT, VPNs, or firewalls because it never needs an inbound
port: both the client and the host daemon dial out to a relay you control.

## Architecture

```mermaid
flowchart TB
    subgraph clients["clients"]
        W["Web client<br/><code>apps/web/</code>"]
        A["Android app<br/><code>android/</code>"]
        I["iPhone app<br/><code>apple/</code>"]
    end
    R["<b>Remotex relay</b><br/><code>services/relay</code><br/>auth · hosts · sessions · routing"]
    D["<b>Host daemon</b><br/><code>services/daemon</code><br/>runs on the codex machine"]
    X["<code>codex app-server</code><br/>official OpenAI binary"]
    P["Postgres<br/><code>deploy/docker-compose.yml</code>"]

    W -- "HTTPS + WSS<br/>user bearer token" --> R
    A -- "HTTPS + WSS<br/>user bearer token" --> R
    I -- "HTTPS + WSS<br/>user bearer token" --> R
    D == "outbound WSS<br/>bridge token" ==> R
    D == "local stdio or WebSocket-over-UDS<br/>JSON-RPC" ==> X
    R -- "inventory" --> P

    classDef clientNode fill:#1a1e26,stroke:#e8a756,color:#e8dfd0;
    classDef relayNode fill:#14171d,stroke:#7dc87d,color:#e8dfd0;
    classDef daemonNode fill:#14171d,stroke:#5f8fb0,color:#e8dfd0;
    classDef codexNode fill:#0d0f13,stroke:#9a958a,color:#9a958a;
    class W,A,I clientNode;
    class R,P relayNode;
    class D daemonNode;
    class X codexNode;
```

The relay is a rendezvous point. It authenticates clients and daemons,
tracks which hosts are online, reserves session IDs, and routes frames
between a client and the selected daemon. Codex itself still runs locally
on the host through `codex app-server`.

Two credentials are intentionally separate:

| Credential | Used by | Stored at |
| --- | --- | --- |
| Remotex bridge token | daemon -> relay auth | `~/.remotex/config.toml` |
| Codex/OpenAI auth | `codex app-server` -> OpenAI auth | `~/.codex/auth.json` |

The daemon does not read the Codex auth file. It only starts the local
Codex app server process.

Postgres stores inventory only: users, hosts, bridge keys, and session
records. Live sockets, event replay, active-turn locks, and pending prompts
stay in relay memory. Codex threads and rollout history stay on the host.

## Why Remotex Still Exists

OpenAI now ships official
[Codex Remote Connections](https://developers.openai.com/codex/remote-connections),
which covers the mainstream hosted workflow. Remotex serves a narrower,
different need:

- You operate the relay and Postgres database.
- Linux workstations are first-class daemon hosts.
- The web, Android, and iPhone clients are part of this repository and can be
  changed with the protocol.
- Host telemetry, direct workspace-file operations, normalized event fan-out,
  and custom session controls belong to the Remotex product surface.
- The boundary is the official
  [`codex app-server`](https://developers.openai.com/codex/app-server), not a
  reimplementation or an OpenAI API wrapper.

Remotex is therefore aimed at self-hosters, protocol hackers, private relay
deployments, and teams that want to own or customize the complete remote
control plane.

## Screenshots

A live web session: pick an online host, open a session, send a prompt,
watch reasoning, tool calls, and streamed agent messages arrive live:

<p align="center">
  <img src="docs/screenshots/client-session.png" alt="Remotex web client - live session" width="820" />
</p>

The **Jump** folder picker — recall a folder by name from recents and
favorites, type a `/path` to teleport, or browse the tree; click to drill in
and pick the working directory from the bar at the bottom:

<p align="center">
  <img src="docs/screenshots/client-jump.png" alt="Remotex web client - Jump folder picker" width="820" />
</p>

The three surfaces side-by-side (click to view full size):

<table>
  <tr>
    <td align="center" valign="top" width="50%">
      <img src="docs/screenshots/client-desktop.png" alt="Web - desktop idle" width="420" /><br/>
      <sub><b>Web · desktop</b> (1440 × 900)</sub>
    </td>
    <td align="center" valign="top" width="25%">
      <img src="docs/screenshots/client-mobile.png" alt="Web - mobile (Jump folder picker)" width="180" /><br/>
      <sub><b>Web · mobile</b> (390 × 844)</sub>
    </td>
    <td align="center" valign="top" width="25%">
      <img src="docs/screenshots/android-installed.png" alt="Android - real device" width="180" /><br/>
      <sub><b>Android</b> (Kotlin + Compose)</sub>
    </td>
  </tr>
</table>

Build Android with the wrapper so the APK receives a relay URL reachable from
the target device:

```bash
cd android
./build.sh install
# or: RELAY_URL=https://relay.example.com ./build.sh install
```

## Runtime Flow

1. The daemon connects to `/ws/daemon` and sends `hello` with its bridge
   token, hostname, platform, and nickname.
2. The relay validates the bridge token, marks that host online, and
   replies with `welcome`.
3. A web, Android, or iPhone client calls `GET /api/hosts` with a user
   token and chooses an online host. Picking a host also fetches
   `GET /api/hosts/{host_id}/models`, which asks that host's Codex what it
   actually offers; if the host cannot supply it, the fallback entry leaves
   model selection to Codex.
4. The client calls `POST /api/sessions` for that host. The relay reserves
   a `session_id`; it does not start Codex yet. An unattached reservation
   is swept after 10 minutes.
5. The client opens `/ws/client` and sends `hello` with the user token,
   `session_id`, and its `last_seq` cursor.
6. After the client is attached, the relay sends `session-open` to the
   daemon. This ordering makes sure the client sees `session-started` and
   every later event. Anything the client missed is replayed first —
   preceded by a `replay-gap` frame if the buffer has already evicted part
   of it.
7. A client prompt becomes a `turn-start` frame.
8. The daemon translates that into Codex `turn/start` over stdio, or over
   Codex's shared WebSocket-over-Unix-socket control plane in `shared` mode.
9. Codex notifications are normalized into `session-event` frames and
   streamed back through the relay to the client.

While a turn is active, the web client can send `turn-steer` to affect that
same Codex turn or retain a follow-up in its local FIFO queue. A queued item is
sent later as an ordinary `turn-start`, after the active turn completes.

## Repo Layout

```text
remotex/
├── apps/web/              React + Vite control-plane web client
├── android/               Kotlin + Jetpack Compose native client
├── apple/                 SwiftUI iPhone client
├── services/
│   ├── relay/             aiohttp relay + Postgres inventory + WS routing
│   ├── daemon/            outbound-WSS daemon + Codex adapters
│   ├── scripts/e2e_test.py
│   └── docs/              architecture and protocol notes
├── deploy/
│   ├── Dockerfile.relay   builds web assets and serves relay + UI
│   ├── docker-compose.yml
│   └── Caddyfile          optional TLS reverse proxy
├── docs/                  logo and real product screenshots
└── .github/workflows/     CI for web, Python, Android, and iPhone
```

More detail lives in the subproject READMEs:

- [`apps/web/README.md`](apps/web/README.md)
- [`android/README.md`](android/README.md)
- [`apple/README.md`](apple/README.md)
- [`services/README.md`](services/README.md)
- [`deploy/README.md`](deploy/README.md)

## Quick Start

### 1. Start the Relay and Postgres

The relay stores its inventory in Postgres and **will not start without
`RELAY_DATABASE_URL`**. For local development, run one:

```bash
docker run --rm -d --name remotex-pg -p 5432:5432 \
  -e POSTGRES_DB=remotex -e POSTGRES_USER=remotex -e POSTGRES_PASSWORD=remotex-dev \
  pgvector/pgvector:pg16
```

Then:

```bash
cd services
pip install -r requirements.txt
export RELAY_DATABASE_URL=postgresql://remotex:remotex-dev@127.0.0.1:5432/remotex
export RELAY_SEED_DEMO=1
python3 relay/app.py
```

The relay listens on `http://127.0.0.1:8080`. `RELAY_SEED_DEMO=1` is what
puts the demo credentials into an empty database:

```text
user token:   demo-user-token
bridge token: demo-bridge-token
```

> Seeding is **off by default and must stay off on anything reachable**.
> Both tokens are hardcoded in this public repo, so a relay that seeds
> them is a relay any stranger can drive: list your hosts, open sessions,
> and run Codex turns on your machines. Use it on loopback or a throwaway
> box only. Real deployments mint per-host bridge keys through
> `POST /api/hosts/{id}/api-key` — see [`deploy/README.md`](deploy/README.md).

Tokens are stored as `sha256(token)`; a minted bridge key's plaintext is
returned exactly once, and after that only its 12-char `key_id` is
visible.

(The Docker Compose path in step 6 brings up Postgres for you — this
manual step is only for running the relay straight from source.)

### 2. Run a Daemon

Use `stdio` mode for real Codex. You need the `codex` CLI installed and
logged in on this machine.

```bash
python3 -m venv /tmp/remotex-venv
/tmp/remotex-venv/bin/pip install -r services/requirements.txt

cd services
/tmp/remotex-venv/bin/python -m daemon init \
  --relay-url ws://127.0.0.1:8080/ws/daemon \
  --bridge-token demo-bridge-token \
  --nickname devbox \
  --mode stdio \
  --default-cwd /path/to/projects \
  --config ./demo-config.toml

/tmp/remotex-venv/bin/python -m daemon run --config ./demo-config.toml
```

Use `--mode mock` to replace live session output with a scripted stream.
Thread and directory administration still uses the local Codex binary.
On Unix, `--mode shared` connects Remotex and eligible plain `codex` TUI
invocations to the same loaded threads. Start it once with
`codex app-server daemon start`; Remotex also starts the default control
socket automatically when shared mode is selected and it is absent.
Invocations with config/profile/strict-config overrides can remain isolated by
Codex and therefore will not mirror live.
For a persistent Linux installation, use `deploy/install-daemon.sh`; see the
[deployment guide](deploy/README.md).

#### Test a shared shell session

After installing the persistent daemon, set `mode = "shared"` under `[daemon]`
in `~/.remotex/config.toml`, then check the local pieces:

```bash
codex app-server daemon start
systemctl --user enable --now remotex-daemon
sudo loginctl enable-linger "$USER"   # keep it running after logout
systemctl --user is-active remotex-daemon
codex app-server daemon version

cd services
~/.local/share/remotex/venv/bin/python -m daemon status
```

Run a plain `codex` command in another shell and start a turn. In the Remotex
web client, select this online host and resume that thread. Remotex attaches to
the selected thread; it does not automatically open every local Codex thread.
Messages entered in either client should then appear in both.

Restarting `remotex-daemon` is a safe reconnect test: the managed Codex process
and its turn keep running while Remotex reconnects and reloads the live state.
Relay and shared-socket failures use bounded backoff. Shared mode deliberately
does not fall back automatically to `stdio`, because that would start a
different Codex process instead of reconnecting to the shell session. Set
`mode = "stdio"` and restart the service when an isolated fallback is wanted.

The daemon refuses to start against a cleartext `ws://` relay on anything
but loopback — that would put the bridge token and every prompt on the
wire in the clear. The URL above is loopback, so it's fine; a LAN relay
wants `wss://`, or an explicit `--allow-insecure` at `init` time.

### 3. Run the Web Client

```bash
cd apps/web
npm ci
npm run dev
```

Open <http://localhost:5174>. The Vite dev server proxies `/api/*` and
`/ws/*` to the relay on `127.0.0.1:8080`.

### 4. Build the Android App

```bash
cd android
cp local.properties.example local.properties
./build.sh install
```

`build.sh` is the supported path: it detects your LAN IP and relay port
and bakes the right URL into the APK. Bare Gradle defaults to
`http://10.0.2.2:8080`, which only works from an emulator — a real phone
built that way will hang on "connecting…".

```bash
./gradlew assembleDebug                                    # emulator only
./gradlew assembleDebug -PrelayUrl=https://relay.example.com   # explicit URL
```

Plain `http://` relay URLs only work in **debug** builds: the app ships a
network security config, and the release build type refuses cleartext
outright. A release APK needs an `https://` relay.

See [`android/README.md`](android/README.md) for the details.

### 5. Run the iPhone App

Open the Xcode project and run it on an iPhone simulator:

```bash
open apple/Remotex.xcodeproj
```

The iOS simulator can reach a relay running on the same Mac at
`http://localhost:8080` (the app's default). For a real iPhone, enter a
LAN or public relay URL in the app. App Transport Security is not
globally disabled: `NSAllowsLocalNetworking` plus a `localhost` exception
allow plain `http://` to a relay on the same Mac, a `.local` name, or a
private LAN address, while a plaintext relay on a *public* address stays
blocked — put that behind HTTPS.

### 6. Add TLS for a Public Relay

```bash
cd deploy
docker compose up -d --build
```

This builds the web client, bundles it into the relay image, and serves
everything from the relay container on `127.0.0.1:8080`. The Compose
stack also starts Postgres for the relay inventory. Demo seeding is off
in Compose (`RELAY_SEED_DEMO=0`), so a fresh stack has no users until you
create one — [`deploy/README.md`](deploy/README.md) covers both the
throwaway-box shortcut and the real path.

For TLS:

```bash
cp .env.example .env
$EDITOR .env
docker compose --profile tls up -d --build
```

## What Works

| Area | Status |
| --- | --- |
| Relay REST + WebSocket transport | Working; Postgres-backed; tokens hashed at rest; demo tokens opt-in via `RELAY_SEED_DEMO` |
| Daemon -> relay connection | Working; outbound WebSocket with bounded jittered reconnect and clean active-turn failure/resume semantics |
| Real Codex bridge | Working through isolated app-server stdio or opt-in shared WebSocket-over-UDS |
| Mock adapter | Working for tests and offline demos |
| Web client | Live host/thread inventory, open/resume, text/images, FIFO queue or active-turn steer, streamed events, approvals/input prompts, Codex-resolved settings, slash commands, goals, files, all NVIDIA GPUs, and Dark/White/High Contrast themes |
| Android client | At parity with web apart from push: hosts, thread resume, events, turns, images, model/effort/permissions, approvals, user-input, slash commands, goals, files, interrupt, reconnect, background notifications |
| iPhone client | Starter SwiftUI app; lists hosts, opens sessions, sends text turns, streams events, answers queued approval/user-input prompts, keeps its token in the Keychain |
| Docker Compose | Working relay + web bundle, Postgres inventory store, optional Caddy TLS or outbound SparkTunnel ingress |
| CI | ESLint, Vite build, vitest, npm audit, Ruff, pytest, relay↔daemon e2e, Android debug APK + JVM tests + lint, iPhone simulator build |

## Protocol Surface

Relay frames are JSON objects with a `type` field. Session frames also
carry `session_id`. Client frames are forwarded to the daemon verbatim,
with `session_id` and `client_id` stamped on by the relay.

| Frame | Direction | Purpose |
| --- | --- | --- |
| `hello` | daemon/client -> relay | Authenticate socket |
| `welcome` | relay -> daemon | Confirm daemon host ID |
| `attached` | relay -> client | Confirm attach; carries `client_id` + `peer_count` |
| `pending-prompts` | relay -> client | Unresolved approvals / prompts on attach, as queues (oldest first) |
| `replay-gap` | relay -> client | `{missed_from, missed_to}` when the replay buffer no longer covers the client's cursor |
| `session-open` | relay -> daemon | Start or resume a Codex thread |
| `session-close` | relay/client -> daemon | Tear the session down |
| `turn-start` | client -> daemon | Send user input (text, images, model, effort, permissions) |
| `turn-steer` | client -> daemon | Add input to the active Codex turn |
| `turn-interrupt` | client -> daemon | Interrupt the active Codex turn |
| `approval-response` | client -> daemon | Resolve a Codex approval request |
| `user-input-response` | client -> daemon | Answer a Codex user-input prompt |
| `slash-command` | client -> daemon | `/plan`, `/default`, `/cd`, `/pwd`, `/compact`, `/goal`, `/collab` |
| `goal-get` / `goal-set` / `goal-clear` | client -> daemon | Thread goal control |
| `session-event` | daemon -> client | Stream a normalized Codex event (sequenced) |
| `approval-resolved` / `user-input-resolved` | relay -> client | Tell other peers a prompt was answered |
| `host-telemetry` | daemon -> relay -> client | CPU / memory / GPU / network samples |
| `threads-list-request` / `models-list-request` / `fs-*-request` | relay -> daemon | REST calls proxied to the host |
| `threads-list-response` / `models-list-response` / `fs-*-response` | daemon -> relay | Correlated by `(host_id, request_id)` |
| `session-closed` | daemon -> client | End the session |
| `ping` / `pong` | either way | Keepalive; also marks the session active |

The web client's follow-up queue is intentionally not a wire frame. Codex has
no queue RPC: the browser retains FIFO items locally, then sends one ordinary
`turn-start` after `turn-completed`. Use **Steer** to affect the running turn
immediately, or **Queue** to start a separate turn afterward.

`session-event` kinds: `session-started`, `turn-started`, `turn-completed`,
`item-started`, `item-delta`, `item-patch`, `item-completed`, `steer-failed`,
`approval-request`, `approval-resolved`, `user-input-request`,
`user-input-resolved`, `thread-status`, `session-settings`, `token-usage`,
`goal-snapshot`, `goal-updated`, `goal-cleared`, `slash-ack`, `collab-modes`,
`history-begin`, `history-chunk-begin`, `history-chunk-end`, `history-end`.

The separate owner-scoped `/ws/inventory` socket sends `inventory-ready`,
`hosts-changed`, and `threads-changed` so the sidebar can refresh without
polling or requiring an attached chat session.

Full payload shapes live in
[`services/docs/architecture.md`](services/docs/architecture.md).

## Current Gaps

These are the main items before exposing Remotex to untrusted users:

1. Replace long-lived bearer tokens with OIDC/Keycloak login. They are
   hashed at rest and demo seeding is off by default, but the model is
   still "one string, forever."
2. Add bridge-key expiry and an `issued_by` column — issue, list, and
   revoke exist now; provenance and lifetime don't.
3. Add audit retention and metrics dashboards (audit lines are emitted
   on `logger=audit`; nothing ships them anywhere durable).
4. Bring the iPhone app to Android feature parity: thread resume, images,
   model/effort controls, permissions, interrupt, and reconnect backoff.
5. Add mobile push notifications for approval requests.
6. Add end-to-end fault tests for a real slow consumer being closed with 1013
   and a client starting a turn while its selected host is offline. Daemon
   disconnect and shared-session recovery paths already have focused coverage.
7. Decide whether to keep pursuing custom remote-control features now that
   official Codex Remote Connections covers the mainstream hosted path.

Detail and ordering live in the "Known gaps" section of
[`services/docs/architecture.md`](services/docs/architecture.md).

## Development

Run the main checks locally. There is no root `package.json` — the web
client is its own npm project under `apps/web`.

```bash
# Web
(cd apps/web && npm ci && npm run lint && npm run test:run && npm run build)

# Python — lint + unit tests need no database
(cd services && pip install -r requirements-dev.txt && ruff check . && pytest tests -v)

# Python — e2e needs a disposable Postgres (see Quick Start step 1)
(cd services && E2E_DATABASE_URL=postgresql://remotex:remotex-dev@127.0.0.1:5432/remotex \
  E2E_ALLOW_DESTRUCTIVE_RESET=1 python scripts/e2e_test.py)

# Android
(cd android && ./gradlew assembleDebug && ./gradlew test && ./gradlew lint)

# iPhone
(xcodebuild -project apple/Remotex.xcodeproj -scheme Remotex -configuration Debug -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build)
```

The screenshots under `docs/screenshots/` were captured by hand against a
live relay, daemon, and web client; there is no capture script in the
repo.

The end-to-end command resets `inventory_*` tables. Point it only at a
disposable database.

## Status

`v0.1` - the relay, daemon, real Codex bridge, web client, Android client,
iPhone starter, Docker deployment, and CI all have working vertical slices.
The project is usable for self-hosted development, but it still needs
production authentication, durable live relay state, audit retention, and
broader failure coverage before public use.

## License

MIT License. See [`LICENSE`](LICENSE).

## Optional SparkTunnel Ingress

Remotex can be published without an inbound firewall rule through the optional
`sparktunnel` Compose profile. It requires a PhotonSpark account and a site
configured for SparkTunnel at [webhost.photonspark.com](https://webhost.photonspark.com).
The connector dials out, while PhotonSpark supplies the public hostname, TLS,
normal HTTP forwarding, and WebSocket upgrades.

SparkTunnel is not required. The normal `docker compose up -d --build` command
continues to listen locally on `127.0.0.1:8080`; the portless behavior applies
only when `docker-compose.sparktunnel.yml` is included.

Set `SPARK_TUNNEL_TOKEN` and `RELAY_SEED_DEMO=0` in `deploy/.env`, then run:

```bash
cd deploy
docker compose -f docker-compose.yml -f docker-compose.sparktunnel.yml \
  --profile sparktunnel up -d --build
```

The default target is the private `http://relay:8080` Compose address. See the
[deployment guide](deploy/README.md#publish-through-sparktunnel) for the full
setup and security notes. The profile forces `RELAY_TRUST_PROXY=0` because
SparkTunnel 0.2.0 preserves spoofable forwarding headers instead of supplying
a trustworthy visitor IP, so all visitors share one connector-peer rate-limit
bucket. SparkTunnel does not replace Remotex authentication; do not expose an
installation that uses the public demo credentials.
