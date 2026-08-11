<p align="center">
  <img src="docs/brand/logo.png" alt="Remotex" width="160" />
</p>

# Remotex

Remotex lets you control Codex sessions running on your own machines from
a browser, Android app, iPhone app, or Windows desktop app. The machine running Codex can sit
behind NAT, CGNAT, VPNs, or firewalls because it never needs an inbound
port: both the client and the host daemon dial out to a relay you control.

## Architecture

```mermaid
flowchart TB
    subgraph clients["clients"]
        W["Web client<br/><code>apps/web/</code>"]
        E["Windows desktop<br/><code>apps/desktop/</code>"]
        A["Android app<br/><code>android/</code>"]
        I["iPhone app<br/><code>apple/</code>"]
    end
    R["<b>Remotex relay</b><br/><code>services/relay</code><br/>auth · hosts · sessions · routing"]
    D["<b>Host daemon</b><br/><code>services/daemon</code><br/>runs on the codex machine"]
    X["<code>codex app-server</code><br/>official OpenAI binary"]
    P["Postgres<br/><code>deploy/docker-compose.yml</code>"]

    W -- "HTTPS + WSS<br/>user bearer token" --> R
    E -- "HTTPS + WSS<br/>user bearer token" --> R
    A -- "HTTPS + WSS<br/>user bearer token" --> R
    I -- "HTTPS + WSS<br/>user bearer token" --> R
    D == "outbound WSS<br/>bridge token" ==> R
    D == "local stdio or WebSocket-over-UDS<br/>JSON-RPC" ==> X
    R -- "inventory" --> P

    classDef clientNode fill:#1a1e26,stroke:#e8a756,color:#e8dfd0;
    classDef relayNode fill:#14171d,stroke:#7dc87d,color:#e8dfd0;
    classDef daemonNode fill:#14171d,stroke:#5f8fb0,color:#e8dfd0;
    classDef codexNode fill:#0d0f13,stroke:#9a958a,color:#9a958a;
    class W,E,A,I clientNode;
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
- The web, Windows, Android, and iPhone clients are part of this repository and can be
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

Public Android releases start without an operator URL and ask for the user's
relay. For local development, the wrapper supplies a reachable starting URL;
it remains editable in the app:

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
3. A web, Windows, Android, or iPhone client calls `GET /api/hosts` with a user
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
├── apps/
│   ├── web/               React + Vite control-plane web client
│   └── desktop/           secure Electron shell for Windows
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
└── .github/workflows/     CI and multi-platform release automation
```

More detail lives in the subproject READMEs:

- [`apps/web/README.md`](apps/web/README.md)
- [`apps/desktop/README.md`](apps/desktop/README.md)
- [`android/README.md`](android/README.md)
- [`apple/README.md`](apple/README.md)
- [`services/README.md`](services/README.md)
- [`deploy/README.md`](deploy/README.md)

## Client releases

[GitHub Releases](https://github.com/CesarPetrescu/remotex/releases) provides
stable semver builds and a rolling `nightly`:

| Platform | Assets | Trust / signing note |
| --- | --- | --- |
| Android | Signed universal APK | Release-key signed. Builds before `v0.2.0` used the Android debug key, so uninstall one of those old APKs once before installing the new signing line. |
| Windows x64 | NSIS setup and portable `.exe` | Not Authenticode-signed yet; SmartScreen may show an unknown publisher. |
| iPhone | Unsigned `.ipa` | Must be re-signed with the user's Apple identity before installation. It is not an App Store build. |

Every release also includes `SHA256SUMS.txt`, and the workflow creates GitHub
build-provenance attestations for the published files. Public mobile and
desktop builds are provider-neutral: they contain no maintainer relay URL and
no demo token. Enter the HTTPS URL and bearer token supplied by the operator
of the relay you intend to use.

The Android release key is the app's upgrade identity. A relay operator who
forks the release workflow must back up their own keystore and credentials;
losing that key means future APKs cannot upgrade its installed builds.

## Quick Start

### 1. Start the Relay and Postgres for loopback development

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

### 4. Run the Windows App

The simplest path is the NSIS installer or portable executable from
[GitHub Releases](https://github.com/CesarPetrescu/remotex/releases). On first
launch, enter the HTTPS base URL serving your Remotex web app. The shell
persists that origin and can switch it later through **Relay -> Change Relay**.

For development:

```bash
cd apps/desktop
npm ci
npx electron .
```

The shell loads the selected relay's web UI with Chromium sandboxing, context
isolation, no Node integration, and an exact-origin navigation allowlist. See
[`apps/desktop/README.md`](apps/desktop/README.md) for the security model and
Windows packaging commands.

### 5. Build the Android App

```bash
cd android
cp local.properties.example local.properties
./build.sh install
```

`build.sh` is the supported local-development path: it detects your LAN IP and
relay port and supplies a useful initial URL. Bare Gradle debug builds default to
`http://10.0.2.2:8080`, which only works from an emulator — a real phone
built that way starts with the wrong address. The relay URL is editable at
runtime, so changing providers no longer requires a reinstall.

```bash
./gradlew assembleDebug                                    # emulator only
./gradlew assembleDebug -PrelayUrl=https://relay.example.com   # explicit URL
```

Public release APKs contain an empty initial URL and ask for the user's relay
and token. Plain `http://` relay URLs only work in **debug** builds: the release
network policy requires HTTPS.

See [`android/README.md`](android/README.md) for the details.

### 6. Run the iPhone App

Open the Xcode project and run it on an iPhone simulator:

```bash
open apple/Remotex.xcodeproj
```

The app starts without a provider URL or demo token. The iOS simulator can
reach a relay running on the same Mac after you enter
`http://localhost:8080`; for a real iPhone, enter a LAN or public relay URL.
App Transport Security is not
globally disabled: `NSAllowsLocalNetworking` plus a `localhost` exception
allow plain `http://` to a relay on the same Mac, a `.local` name, or a
private LAN address, while a plaintext relay on a *public* address stays
blocked — put that behind HTTPS.

The bearer token is stored in the Keychain under a relay-scoped account, so
switching providers cannot silently reuse the previous relay's credential.

### 7. Add TLS for a Public Relay

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
| Windows client | Secure Electron shell around the selected relay's web UI; same product surface as web, provider-selectable on first launch |
| Android client | Native host/thread inventory, open/resume and paged history, text/images, FIFO queue or active-turn steer, settings, approvals/input, slash commands/goals, files, telemetry, process/session restore, interrupt, local notifications, and Dark/Light/High Contrast themes |
| iPhone client | Live host/thread inventory, cwd selection, resume and paged history, text/images, FIFO queue or steer, model/effort/permissions, approvals/input, slash commands/goals, writable workspace files, telemetry, provider-scoped process/session restore, interrupt/reconnect, local completion notifications, relay-scoped Keychain credentials, and Dark/Light/High Contrast themes |
| Docker Compose | Working relay + web bundle, Postgres inventory store, optional Caddy TLS or outbound SparkTunnel ingress |
| CI | Web lint/build/vitest/audit, Electron tests/audit + Windows packaging, Ruff/pytest/dependency audit/relay↔daemon e2e, Android APK/JVM/lint/emulator UI tests, and iPhone simulator build + XCTest |

## What local Codex still does that Remotex does not

Remotex covers the core remote session path: new/resumed chats, model and
effort, permissions, plan/default modes, goals, cwd changes, compaction,
images, approvals, user-input prompts, steering, stopping, history, files, and
host telemetry. It is not yet a complete replacement for every control in the
local Codex TUI. An audit against the host's installed Codex `0.147.0` found
these important remaining differences (plugins and skills intentionally
excluded):

| Local Codex feature | Remotex today |
| --- | --- |
| Structured `/review` targets and aggregate `/diff` | You can ask for a review in chat and see individual file-change items, but there is no branch/commit/uncommitted review picker or cumulative turn diff view. |
| `/rename`, `/archive`, `/delete`, `/fork`, `/side`/`/btw`, and `/agent` | Remotex can create and resume sessions and renders sub-agent activity, but it cannot manage thread metadata, make ephemeral side forks, or switch the active agent thread. (`thread/delete` also has an upstream Codex 0.147 bug tracked as `I-019`.) |
| `@` file mention and `/ide` context | Remotex has images, a cwd picker, and workspace files, but no fuzzy file-mention autocomplete or current IDE selection/open-file context. |
| `/ps` and `/stop` background-terminal management | Agent-started command output streams remotely, but there is no separate remote terminal/process list or stop-all-background-terminals control. |
| Full `/status` and `/usage` | Remotex shows resolved session settings, per-thread token totals, goal budget, and host hardware telemetry; it does not show account quota/reset/credits or the complete layered Codex configuration. |
| `/copy`, `/export`, and raw scrollback | Tool output can be copied, but there is no one-action copy-last-response, Markdown transcript export, or copy-friendly raw transcript mode. |
| Host-side `/mcp`, hooks, memories, personality, and experimental settings | The host's configured capabilities still work inside Codex turns, but Remotex has no remote management UI for them. Authentication/logout and global Codex configuration intentionally remain host-local. |

The app-server already exposes much of this protocol surface, so these are
product backlog items rather than reasons to reimplement Codex.

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
| `history-more` | client -> daemon | Request an older page of rollout turns |
| `approval-response` | client -> daemon | Resolve a Codex approval request |
| `user-input-response` | client -> daemon | Answer a Codex user-input prompt |
| `slash-command` | client -> daemon | `/plan`, `/default`, `/cd`, `/pwd`, `/compact`, `/goal`, `/collab` |
| `goal-get` / `goal-set` / `goal-clear` | client -> daemon | Direct thread-goal control from native clients |
| `session-event` | daemon -> client | Stream a normalized Codex event (sequenced) |
| `approval-resolved` / `user-input-resolved` | relay -> client | Tell other peers a prompt was answered |
| `host-telemetry` | daemon -> relay -> client | CPU / memory / GPU / network samples |
| `threads-list-request` / `models-list-request` / `fs-*-request` | relay -> daemon | REST calls proxied to the host |
| `threads-list-response` / `models-list-response` / `fs-*-response` | daemon -> relay | Correlated by `(host_id, request_id)` |
| `session-closed` | daemon -> client | End the session |
| `ping` / `pong` | either way | Keepalive; also marks the session active |

The clients' follow-up queues are intentionally not wire frames. Codex has no
queue RPC: a client retains FIFO items locally, then sends one ordinary
`turn-start` after `turn-completed`. Use **Steer** to affect the running turn
immediately, or **Queue** to start a separate turn afterward. Local queues do
not synchronize between devices and are discarded when their client closes
the session.

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
4. Add mobile push notifications for approvals that arrive while an app is
   fully closed. Android's foreground-service notifications and iPhone's
   app-alive completion notification are local; no relay-to-FCM/APNs path
   exists.
5. Code-sign the Windows executables with Authenticode and provision the
   iPhone app for normal installation or App Store/TestFlight distribution.
6. Add an end-to-end test that drives a genuinely slow network consumer to a
   1013 close. Daemon disconnect and host-offline turn recovery already have
   focused coverage.
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

# Windows desktop shell (pack:win is canonical on Windows/CI)
(cd apps/desktop && npm ci && npm run lint && npm test && npm audit)

# Python — lint + unit tests need no database
(cd services && pip install -r requirements-dev.txt && ruff check . && pytest tests -v)

# Python — e2e needs a disposable Postgres (see Quick Start step 1)
(cd services && E2E_DATABASE_URL=postgresql://remotex:remotex-dev@127.0.0.1:5432/remotex \
  E2E_ALLOW_DESTRUCTIVE_RESET=1 python scripts/e2e_test.py)

# Android
(cd android && ./gradlew assembleDebug && ./gradlew test && ./gradlew lint)

# iPhone build + XCTest (use an installed simulator name for test)
(xcodebuild -project apple/Remotex.xcodeproj -scheme Remotex -configuration Debug -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build)
(xcodebuild -project apple/Remotex.xcodeproj -scheme Remotex -configuration Debug -destination 'platform=iOS Simulator,name=iPhone 16 Pro,OS=latest' CODE_SIGNING_ALLOWED=NO test)
```

The screenshots under `docs/screenshots/` were captured by hand against a
live relay, daemon, and web client; there is no capture script in the
repo.

The end-to-end command resets `inventory_*` tables. Point it only at a
disposable database.

## Status

The `v0.2` line adds provider-neutral native clients, a Windows Electron app,
release-signed Android APKs, multi-platform release automation, and a much
closer mobile feature match to the web client. The project is usable for
self-hosted development and trusted small deployments, but it still needs
production authentication, durable live relay state, audit retention, signed
Windows/iPhone distribution, and broader failure coverage before public use.

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

The default target is the private `http://relay:8080` Compose address. The
override also retains a loopback-only `127.0.0.1:19080` endpoint so a daemon on
the relay host does not hairpin through the public tunnel. See the
[deployment guide](deploy/README.md#publish-through-sparktunnel) for the full
setup and security notes. The profile forces `RELAY_TRUST_PROXY=0` because
SparkTunnel 0.2.0 preserves spoofable forwarding headers instead of supplying
a trustworthy visitor IP, so all visitors share one connector-peer rate-limit
bucket. SparkTunnel does not replace Remotex authentication; do not expose an
installation that uses the public demo credentials.
