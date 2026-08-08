<p align="center">
  <img src="docs/brand/logo.png" alt="Remotex" width="160" />
</p>

# Remotex

Remotex lets you control Codex sessions running on your own machines from
a browser, Android app, or iPhone app. The machine running Codex can sit
behind NAT, CGNAT, VPNs, or firewalls because it never needs an inbound
port: both the client and the host daemon dial out to a relay you control.

## How It Works

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
    D == "local stdio<br/>JSON-RPC" ==> X
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

## Remotex vs. Codex Remote Connections

OpenAI now ships official
[Codex Remote Connections](https://developers.openai.com/codex/remote-connections),
which covers the mainstream version of Remotex's original idea: control
Codex on a trusted host from another device without exposing that host
directly to the internet. The official path is deeply integrated with
ChatGPT, the Codex App, workspace auth, SSH projects, worktrees, Git UI,
Computer Use, browser features, automations, and enterprise controls.

Remotex is different by design: it is a self-hosted relay and custom
client stack for people who want to run `codex app-server` on their own
machine, own the rendezvous service, and add product features around that
wire protocol.

| Area | Official Codex Remote Connections | Remotex |
| --- | --- | --- |
| Control plane | OpenAI-managed secure relay across authorized ChatGPT/Codex devices | Self-hosted aiohttp relay with your own Postgres inventory and tokens |
| Host runtime | Codex App host on macOS/Windows, plus SSH hosts managed through the Codex App | Python daemon that starts the official `codex app-server` binary over stdio |
| Clients | ChatGPT mobile and supported Codex App devices | Custom web app, Android app, and starter iPhone app |
| Auth | ChatGPT account/workspace auth, MFA/SSO/passkeys, admin policy | Prototype bearer-token auth today: user token plus bridge token |
| Session basics | Start new host threads, continue existing threads, switch hosts and threads | Start/resume Codex threads, replay history during resume, reconnect with event replay |
| Live control | Send follow-ups, answer questions, steer active work, approve actions | Send turns, interrupt turns, answer approvals and user-input prompts, use slash commands and goals |
| Host environment | Uses the host's projects, files, credentials, plugins, MCP servers, skills, browser setup, Computer Use, and local tools | Uses the daemon host's filesystem and Codex configuration; Remotex adds custom file browsing/upload and host telemetry |
| Codex App features | Worktrees, built-in Git diff/review, commit/push/PR flows, automations, IDE sync, Computer Use, in-app browser | Not first-class in the Remotex UI; Codex can still run tools and shell commands through app-server |
| Notifications | Built-in task and approval notifications in Codex/ChatGPT surfaces | Android foreground/done notifications and web background alerts; iPhone parity still pending |
| Extensibility | OpenAI product surface and settings | Direct access to the relay, clients, daemon adapter, and normalized event stream |
| Best fit | Most users who want polished official remote Codex access | Self-hosters, protocol hackers, Linux/workstation setups, custom mobile/web clients, and private relay deployments |

Under the hood, both approaches meet at the same important boundary:
[`codex app-server`](https://developers.openai.com/codex/app-server). It
speaks JSON-RPC over transports such as stdio, Unix sockets, and
experimental WebSocket. Remotex intentionally uses the default stdio
transport and wraps it in its own outbound WebSocket relay.

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

The debug APK defaults to `http://10.0.2.2:8080`, which reaches the host
machine from an Android emulator. For a real phone, build with your LAN or
public relay URL:

```bash
cd android
./gradlew assembleDebug -PrelayUrl=http://<your-lan-ip>:8080
```

## Runtime Flow

1. The daemon connects to `/ws/daemon` and sends `hello` with its bridge
   token, hostname, platform, and nickname.
2. The relay validates the bridge token, marks that host online, and
   replies with `welcome`.
3. A web, Android, or iPhone client calls `GET /api/hosts` with a user
   token and chooses an online host.
4. The client calls `POST /api/sessions` for that host. The relay reserves
   a `session_id`; it does not start Codex yet.
5. The client opens `/ws/client` and sends `hello` with the user token and
   `session_id`.
6. After the client is attached, the relay sends `session-open` to the
   daemon. This ordering makes sure the client sees `session-started` and
   every later event.
7. A client prompt becomes a `turn-start` frame.
8. The daemon translates that into Codex `turn/start` over stdio.
9. Codex notifications are normalized into `session-event` frames and
   streamed back through the relay to the client.

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
└── .github/workflows/     CI for web, Python, and Android
```

More detail lives in the subproject READMEs:

- [`apps/web/README.md`](apps/web/README.md)
- [`android/README.md`](android/README.md)
- [`apple/README.md`](apple/README.md)
- [`services/README.md`](services/README.md)
- [`deploy/README.md`](deploy/README.md)

## Quick Start

### 1. Run the Relay

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
python3 relay/app.py
```

The relay listens on `http://127.0.0.1:8080` and seeds demo credentials
into an empty database on first run:

```text
user token:   demo-user-token
bridge token: demo-bridge-token
```

(The Docker Compose path in step 6 brings up Postgres for you — this
manual step is only for running the relay straight from source.)

### 2. Run a Daemon

Use `stdio` mode for real Codex. You need the `codex` CLI installed and
logged in on this machine.

```bash
cd services
python3 -m daemon init \
  --relay-url ws://127.0.0.1:8080/ws/daemon \
  --bridge-token demo-bridge-token \
  --nickname devbox \
  --mode stdio \
  --default-cwd "$PWD" \
  --config ./demo-config.toml

python3 -m daemon run --config ./demo-config.toml
```

For an API-free UI demo, use `--mode mock` instead of `--mode stdio`.

### 3. Run the Web Client

```bash
cd apps/web
npm install
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

See [`android/README.md`](android/README.md) for the details.

### 5. Run the iPhone App

Open the Xcode project and run it on an iPhone simulator:

```bash
open apple/Remotex.xcodeproj
```

The iOS simulator can reach a relay running on the same Mac at
`http://127.0.0.1:8080`. For a real iPhone, enter a LAN or public relay
URL in the app.

### 6. Deploy with Docker Compose

```bash
cd deploy
docker compose up -d --build
```

This builds the web client, bundles it into the relay image, and serves
everything from the relay container on `127.0.0.1:8080`. The Compose
stack also starts Postgres for the relay inventory.

For TLS:

```bash
cp .env.example .env
$EDITOR .env
docker compose --profile tls up -d --build
```

## What Works

| Area | Status |
| --- | --- |
| Relay REST + WebSocket transport | Working; Postgres-backed; demo tokens seeded |
| Daemon -> relay connection | Working; outbound WebSocket with reconnect |
| Real Codex bridge | Working through `codex app-server` stdio |
| Mock adapter | Working for tests and offline demos |
| Web client | Lists hosts, opens/resumes sessions, sends text/image turns, streams reasoning/tool/agent events, handles approvals, user-input prompts, models, effort, permissions, slash commands, goals, files, and telemetry |
| Android client | At parity with web apart from push: hosts, thread resume, events, turns, images, model/effort/permissions, approvals, user-input, slash commands, goals, files, interrupt, reconnect, background notifications |
| iPhone client | Starter SwiftUI app; lists hosts, opens sessions, sends text turns, streams events |
| Docker Compose | Working relay + web bundle, Postgres inventory store, optional Caddy TLS |
| CI | ESLint, Vite build, npm audit, Ruff, pytest, relay↔daemon e2e, Android debug APK artifact, iPhone simulator build |

## Protocol Surface

Relay frames are JSON objects with a `type` field. Session frames also
carry `session_id`. Client frames are forwarded to the daemon verbatim,
with `session_id` and `client_id` stamped on by the relay.

| Frame | Direction | Purpose |
| --- | --- | --- |
| `hello` | daemon/client -> relay | Authenticate socket |
| `welcome` | relay -> daemon | Confirm daemon host ID |
| `attached` | relay -> client | Confirm attach; carries `client_id` + `peer_count` |
| `pending-prompts` | relay -> client | Unresolved approvals / prompts on attach |
| `session-open` | relay -> daemon | Start or resume a Codex thread |
| `session-close` | relay/client -> daemon | Tear the session down |
| `turn-start` | client -> daemon | Send user input (text, images, model, effort, permissions) |
| `turn-interrupt` | client -> daemon | Interrupt the active Codex turn |
| `approval-response` | client -> daemon | Resolve a Codex approval request |
| `user-input-response` | client -> daemon | Answer a Codex user-input prompt |
| `slash-command` | client -> daemon | `/plan`, `/default`, `/cd`, `/pwd`, `/compact`, `/goal`, `/collab` |
| `goal-get` / `goal-set` / `goal-clear` | client -> daemon | Thread goal control |
| `session-event` | daemon -> client | Stream a normalized Codex event (sequenced) |
| `approval-resolved` / `user-input-resolved` | relay -> client | Tell other peers a prompt was answered |
| `host-telemetry` | daemon -> relay -> client | CPU / memory / GPU / network samples |
| `threads-list-request` / `fs-*-request` | relay -> daemon | REST calls proxied to the host |
| `threads-list-response` / `fs-*-response` | daemon -> relay | Correlated by `request_id` |
| `session-closed` | daemon -> client | End the session |
| `ping` / `pong` | either way | Keepalive; also marks the session active |

`session-event` kinds: `session-started`, `turn-started`, `turn-completed`,
`item-started`, `item-delta`, `item-completed`, `approval-request`,
`user-input-request`, `thread-status`, `token-usage`, `goal-snapshot`,
`goal-updated`, `goal-cleared`, `slash-ack`, `collab-modes`,
`history-begin`, `history-end`.

Full payload shapes live in
[`services/docs/architecture.md`](services/docs/architecture.md).

## Current Gaps

These are the main items before this is ready for real users:

1. Replace demo bearer tokens with OIDC/Keycloak login.
2. Add bridge-key revocation — the `revoked_at` column is honored but
   nothing ever sets it.
3. Add audit retention and metrics dashboards (audit lines are emitted
   on `logger=audit`; nothing ships them anywhere durable).
4. Bring the iPhone app to Android feature parity: thread resume, images,
   model/effort controls, permissions, approvals, interrupt, and reconnect.
5. Add mobile push notifications for approval requests.
6. Add fault tests: daemon disconnect mid-turn, racing approvals, slow
   clients, and host offline during a turn.
7. Decide whether to keep pursuing custom remote-control features now that
   official Codex Remote Connections covers the mainstream hosted path.

Detail and ordering live in
[`services/docs/production_plan.md`](services/docs/production_plan.md).

## Development

Run the main checks locally. There is no root `package.json` — the web
client is its own npm project under `apps/web`.

```bash
# Web
(cd apps/web && npm ci && npm run lint && npm run build)

# Python — lint + unit tests need no database
(cd services && pip install -r requirements-dev.txt && ruff check . && pytest tests -v)

# Python — e2e needs a disposable Postgres (see Quick Start step 1)
(cd services && E2E_DATABASE_URL=postgresql://remotex:remotex-dev@127.0.0.1:5432/remotex \
  E2E_ALLOW_DESTRUCTIVE_RESET=1 python scripts/e2e_test.py)

# Android
(cd android && ./gradlew assembleDebug && ./gradlew test)

# iPhone
open apple/Remotex.xcodeproj
```

The screenshots under `docs/screenshots/` were captured by hand against a
live relay, daemon, and web client; there is no capture script in the
repo.

## Status

`v0.1` - the relay, daemon, real Codex bridge, web client, Android client,
iPhone starter, Docker deployment, and CI all have working vertical slices.
The project is usable for self-hosted development, but it still needs
production auth, storage, auditability, and stronger failure handling before
public use.

## License

MIT License. See [`LICENSE`](LICENSE).
