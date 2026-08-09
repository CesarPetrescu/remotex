# Remotex — architecture

## Goal

Let a user control their Codex sessions from a phone or browser, even when
the machine running Codex is behind NAT/CGNAT/firewalls. Multi-user,
multi-machine per user.

## Topology

```
    Client (web / iOS / Android)
              │
              │  HTTPS + WSS (user bearer token)
              ▼
    ┌────────────────────────┐           ┌────────────────────────┐
    │  Relay (aiohttp)       │◄── WSS ───│ Daemon (user's host)   │
    │  · bearer-token auth   │  outbound │ · reads bridge token   │
    │  · routes session IDs  │   only    │ · spawns one           │
    │  · replay + fan-out    │           │   `codex app-server`   │
    └───────────┬────────────┘           │   per session          │
                │                        │ · stdio JSON-RPC local │
                ▼                        └────────────────────────┘
          Postgres                                   │
          (inventory)                                ▼
                                           codex app-server (local)
```

Clients and daemons **both dial outward** to the relay. The relay is the
matchmaker; it never opens inbound connections to a daemon, and the
daemon never opens an inbound port on the host.

The relay ships in `deploy/docker-compose.yml` alongside its Postgres
inventory store. `RELAY_DATABASE_URL` is **required** — the store raises
on startup without it (`relay/store.py`).

## Trust boundaries

Two separate credentials live on the daemon machine:

| Credential          | Purpose                          | Location                 | Who holds it |
|---------------------|----------------------------------|--------------------------|--------------|
| Bridge API key      | Auth the **daemon** to the relay | `~/.remotex/config.toml` | Remotex      |
| OpenAI / Codex auth | Auth **codex** to the model      | `~/.codex/auth.json`     | OpenAI       |

The daemon **never reads** the Codex auth. It only spawns the local
`codex app-server` process, which picks up `~/.codex/auth.json` the way
it always does. Conflating the two is the single biggest footgun in
this system.

The daemon refuses to start when `relay_url` is cleartext `ws://` to a
non-loopback host, because that puts the bridge token and every prompt on
the wire in the clear. Override with `allow_insecure = true` under
`[daemon]` (or `daemon init --allow-insecure`); it logs a warning on
every start.

The relay **never interprets Codex payload content**. It does read the
event *envelope* — `event.kind` plus a handful of ids — to track turn
state, index threads, and hold pending approvals (`handlers/ws_daemon.py`).
Everything inside `event.data` is shuttled through opaque.

### Tokens at rest

User tokens and bridge keys are stored as `sha256(token)` hex, never in
plaintext (`store.hash_token`). A bridge key's plaintext is returned
exactly once, by the route that mints it; after that only its **key id**
— the first 12 chars of the stored hash — is visible, and that is what
the list and revoke routes speak in. Existing plaintext rows are
rewritten in place by the startup schema (the migration skips values that
already look like a 64-char hex digest).

Audit lines never carry a credential either: `logging.user_hash(token)`
(first 12 chars of the digest) is what goes in the log.

The demo user/host/bridge key are **opt-in** — the relay only seeds them
when `RELAY_SEED_DEMO` is truthy (`1`/`true`/`yes`), and it never logs the
token values. The tokens are hardcoded in this public repo, so a relay
that seeds them is a relay anyone on the internet can drive.

## REST API

All routes require `Authorization: Bearer <user token>` (or `?token=`),
except `GET /api/models`, which is the same for everyone.

Rate limiting is two token buckets, both of which have to allow a request:
per **remote address** (120 burst / 40 req/s) and, when the request
carries a credential, per **credential** (30 burst / 10 req/s). The
per-remote one is the one that stops abuse — the credential is
caller-supplied, so bucketing only by it means rotating the
`Authorization` header mints a fresh full bucket every request and a token
brute-force is never throttled at all. Overflow is HTTP 429 with
`Retry-After`.

`/ws/*` and `/assets/*` bypass both buckets — the WS handlers apply their
own per-remote cap on connection *attempts* instead
(`RELAY_WS_CONNECT_BURST`, default 60, refilling at
`RELAY_WS_CONNECT_PER_SECOND`, default 5/s).

`request.remote` is the TCP peer, so behind a reverse proxy (the Caddy or
SparkTunnel profile in `deploy/docker-compose.yml`) every caller collapses
onto one address and shares one bucket. Set `RELAY_TRUST_PROXY=1` **only**
in a proxied deployment: it makes the relay read the client address from
`X-Forwarded-For`, which is a caller-supplied header and worthless
without a proxy in front to overwrite it.

| Method | Path                                | Purpose |
|--------|-------------------------------------|---------|
| GET    | `/api/models`                       | Hostless "let Codex decide" sentinel + fallback efforts |
| GET    | `/api/hosts`                        | Hosts owned by the caller |
| POST   | `/api/hosts`                        | Register a host row |
| POST   | `/api/hosts/{id}/api-key`           | Mint a bridge key — plaintext returned once, plus its `key_id` |
| GET    | `/api/hosts/{id}/api-key`           | List non-revoked keys by `key_id` (never the key itself) |
| POST   | `/api/hosts/{id}/api-key/revoke`    | Revoke by `{token}` or `{key_id}`; also drops the host's live daemon socket |
| GET    | `/api/hosts/{id}/models`            | What that host's Codex reports; falls back to the default sentinel |
| GET    | `/api/hosts/{id}/threads`           | Proxied `thread/list` (30s timeout) |
| GET    | `/api/hosts/{id}/fs`                | List a directory on the host |
| POST   | `/api/hosts/{id}/fs/mkdir`          | Create a directory |
| GET    | `/api/hosts/{id}/fs/read`           | Read a file (base64, `REMOTEX_MAX_FILE_BYTES` cap) |
| POST   | `/api/hosts/{id}/fs/delete`         | Delete a single file (never a directory) |
| POST   | `/api/hosts/{id}/fs/rename`         | Move/rename, refuses to overwrite |
| POST   | `/api/hosts/{id}/fs/upload`         | Multipart upload into a host directory |
| GET    | `/api/hosts/{id}/telemetry`         | Cached telemetry snapshot + ~30s history |
| POST   | `/api/sessions`                     | Reserve a session id (see lifecycle below) |

Every `fs/*`, `threads` and host-`models` route is a *proxy*: the relay
sends a request frame to the daemon with a `request_id`, parks a future in
`hub.pending_admin` under `(host_id, request_id)` so only the daemon that
was asked can answer it, and resolves it when the matching response frame
comes back (`handlers/daemon_rpc.py`).

`GET /api/hosts/{id}/models` degrades instead of failing: if the host is
offline, errors, or misses the 10s deadline, the relay answers with the
hostless default sentinel and `"source": "fallback"` rather than naming
models it cannot verify. A successful host answer is tagged
`"source": "host"`. Either way the model objects keep the
`{id, label, hint, efforts[]}` shape.

Anything else under `/` serves the SPA (`handlers/static.py`).

### Size limits

`REMOTEX_MAX_FILE_BYTES` (default `26214400` = 25 MiB) is the single knob;
everything else is derived from it, on both sides of the wire
(`relay/limits.py`, `daemon/limits.py` — keep the two defaults in sync):

| Derived limit | Value | Applies to |
|---|---|---|
| aiohttp `client_max_size` | file + 1 MiB | every REST body, i.e. `fs/upload` |
| WebSocket `max_msg_size`  | `ceil(file × 4/3)` + 4 MiB | relay `/ws/daemon`, relay `/ws/client`, daemon `ws_connect` |

The websocket ceiling carries the base64 inflation (4/3) plus slack for
the JSON envelope, because a file only ever reaches a client base64'd
inside a frame. Oversize is an **explicit error**, never a silent socket
drop — but that guarantee has to be met by the **sender**, because the
receiver cannot honour it: aiohttp closes the socket before the handler
ever sees an oversize frame, so the `{"type": "error", ...}` the handlers
attempt on `WSMsgType.ERROR` is best-effort and usually never leaves. So:

- REST answers 413 with `{error, max_bytes, size}`.
- The daemon caps every `fs-*` payload and answers `fs-*-response {error}`.
- The daemon measures every outbound frame against the ceiling in
  `DaemonClient._run_once`'s `send()` and substitutes
  `_oversize_error_frame()` — same type, session and request id, payload
  replaced by `error` — rather than letting aiohttp drop the socket and
  take every session on that host with it.

Clients enforce the same ceiling before anything leaves the device:
`MAX_FILE_BYTES` in `apps/web/src/config.js` (overridable at build time
with `VITE_REMOTEX_MAX_FILE_BYTES`) and in
`android/.../ui/RemotexViewModel.kt`. Image attachments are checked
*cumulatively* on both, since the whole batch rides one `turn-start` frame.

## WebSocket frames

All frames are single-line JSON objects with a `type` field.
Session-scoped frames carry a `session_id`.

### Daemon ↔ relay

| Direction      | `type`                    | Payload |
|----------------|---------------------------|---------|
| daemon → relay | `hello`                   | `{token, hostname, platform, nickname, os_user, home_dir, default_cwd}` |
| relay → daemon | `welcome`                 | `{host_id}` |
| relay → daemon | `session-open`            | `{session_id, resume_thread_id?, cwd?, kind?}` |
| relay → daemon | `session-close`           | `{session_id}` |
| daemon → relay | `session-event`           | `{session_id, event: {kind, data, ts}}` |
| daemon → relay | `session-closed`          | terminal frame for a session |
| daemon → relay | `host-telemetry`          | `{data}` — cached, then fanned out to clients on that host |
| relay → daemon | `threads-list-request`    | `{request_id, limit}` |
| relay → daemon | `models-list-request`     | `{request_id}` — "what models does your codex offer?" |
| relay → daemon | `fs-{read,mkdir,readfile,delete,rename,write}-request` | `{request_id, …}` |
| daemon → relay | `threads-list-response` / `models-list-response` / `fs-*-response` | `{request_id, …}` or `{request_id, error}` |
| daemon → relay | `ping` → `pong`           | keepalive |

`models-list-response` carries `{request_id, models: [{id, label, hint,
efforts[]}]}`, mapped from codex's `model/list` by
`daemon/adapters/admin.py::model_options_from_codex`. On any failure the
daemon answers `{request_id, error}` and the relay serves only its hostless
default sentinel instead.

Client frames are forwarded to the daemon **verbatim**, with `session_id`
and `client_id` stamped on by the relay.

### Client ↔ relay

| Direction      | `type`                  | Payload |
|----------------|-------------------------|---------|
| client → relay | `hello`                 | `{token, session_id, client_id?, client_name?, last_seq?}` |
| relay → client | `attached`              | `{session_id, host_id, client_id, peer_count}` |
| relay → client | `pending-prompts`       | `{approvals[], user_inputs[]}` — unresolved prompts as **queues**, oldest first, not sequenced |
| relay → client | `replay-gap`            | `{session_id, missed_from, missed_to}` — frames the replay buffer no longer holds; sent *before* the frames it precedes |
| client → relay | `turn-start`            | `{input, model?, effort?, permissions?, approvalPolicy?, collaborationMode?, images?, client_message_id?}` |
| client → relay | `turn-interrupt`        | interrupt the live turn |
| client → relay | `approval-response`     | `{approval_id, decision}` |
| client → relay | `user-input-response`   | `{call_id, answers}` |
| client → relay | `slash-command`         | `{command, args}` |
| client → relay | `goal-get` / `goal-set` / `goal-clear` | direct goal control (Android; web routes `/goal` through `slash-command`) |
| client → relay | `session-close`         | close the backend session for every attached client |
| client → relay | `ping` → `pong`         | keepalive; also counts as session activity |
| relay → client | `session-event`         | same envelope as from the daemon, plus a `seq` |
| relay → client | `approval-resolved` / `user-input-resolved` | tells other peers a prompt was answered |
| relay → client | `host-telemetry`        | `{host_id, data, ts}` |
| relay → client | `error`                 | `{error}` — e.g. `host offline`, `a turn is already running in this chat` |

### `session-event` kinds

Emitted by the daemon adapter, normalized from Codex notifications:

| Kind | Notes |
|---|---|
| `session-started` | `{model, cwd, thread_id, transport, kind, resuming?}` |
| `turn-started` / `turn-completed` | `{turn_id, …}`; `turn-completed` carries `error` on failure |
| `item-started` / `item-delta` / `item-completed` | `{turn_id, item_id, item_type, …}` |
| `approval-request` | `{approval_id, kind, decisions[], …}` — command / file_change / permissions |
| `user-input-request` | `{call_id, turn_id, questions[]}` |
| `thread-status` | resume progress: `resuming` / `resumed` / `resume-failed` |
| `token-usage` | flattened `thread/tokenUsage/updated` totals + context window |
| `goal-snapshot` / `goal-updated` / `goal-cleared` | Codex thread goals |
| `slash-ack` | result of a `slash-command` |
| `collab-modes` | reply to `/collab` |
| `history-begin` / `history-end` | brackets a replayed transcript on resume |

`item_type` is the mapped snake_case form of the Codex item type
(`adapters/items.py`): `agent_message`, `agent_reasoning`, `tool_call`
(from Codex's `commandExecution`), `file_change`, `mcp_tool_call`,
`dynamic_tool_call`, `collab_agent_tool_call`, `user_message`. Unmapped
Codex types pass through unchanged.

## Connection lifecycle

1. **Daemon starts** → `hello` with the bridge token. The relay resolves
   the `host_id`, records host identity, marks it online, replies
   `welcome`, then re-sends any cached `session-open` frames for that
   host so sessions resume their Codex threads after a daemon reconnect.
   An in-flight turn cannot survive because the disconnected daemon tears
   down its Codex subprocess; the relay completes that turn with an error,
   clears its prompts, and leaves the resumed session ready for a retry.
2. **User opens a session.** Client does `POST /api/sessions {host_id,
   thread_id?, cwd?}`. The relay reserves a `session_id`. Nothing happens
   on the daemon yet. If a session is already live for that
   `(host, thread)`, the existing id comes back with `reused: true`
   instead of spawning a second Codex.
   A reservation nobody ever attaches to is reaped after
   `RELAY_SESSION_RESERVATION_TTL_SECONDS` (default 600) by a background
   sweeper, so an abandoned `POST` doesn't leave an open row — or, for a
   resumed thread, a `(host, thread)` index entry that hands the same dead
   id to every later caller.
3. **Client attaches** to `/ws/client` with `hello{token, session_id,
   last_seq?}`. The relay verifies ownership *against the store* (a live
   session in the hub is not permission to join it), replies `attached`,
   sends a `pending-prompts` snapshot, sends `replay-gap` if the buffer
   can't cover `last_seq`, replays every buffered event newer than
   `last_seq`, **then** sends `session-open` to the daemon. That ordering
   guarantees the client observes `session-started` and everything after.
   When the in-memory reservation is gone (relay restart, or a plain
   reattach) the session-open overrides are rebuilt from the persisted row
   — `kind`, `thread_id` and `cwd` are columns on `inventory_sessions` —
   so the daemon resumes the original codex thread instead of silently
   starting a fresh one under the same session id. `thread_id` / `cwd` are
   written back as soon as the daemon reports `session-started`.
4. **Client sends `turn-start`.** The relay reserves the session's single
   turn slot (a second concurrent turn gets `error`), echoes the user
   message to every attached peer, and forwards the frame to the daemon.
5. **Daemon runs the adapter**, translating to `turn/start` over stdio;
   events flow back and are sequenced, buffered, and fanned out to all
   attached clients.
6. **Disconnect** — see the failure table below. Sessions are *not*
   dropped when the daemon socket closes; they are held so a reconnecting
   daemon can pick them back up. Any active turn is explicitly failed first
   because its adapter no longer exists.

## Multi-client semantics

Several clients can attach to the same `session_id` at once; each has its
own `client_id` and all of them see the same event stream. Reattaching
with the same `client_id` displaces the older socket (close code 4000,
`replaced`).

Approvals and user-input prompts are claimed atomically — first response
wins, later ones get `error: approval already resolved`, and every peer is
told the outcome via `approval-resolved` / `user-input-resolved`. A claim
is only dropped once the answer is actually on its way to the daemon; if
that forward fails, the prompt is handed back at its original queue
position and the queue is re-pushed to the client that answered.

Pending prompts are **queues**, not slots. The relay stamps each with a
monotonic order and `pending-prompts` lists them oldest-first; clients
render the head of each queue and popping it reveals the next, so a second
concurrent prompt can never overwrite or hide an earlier unanswered one.
All three clients implement this (`pendingApprovals` / `pendingUserInputs`
in `useRemotex.js`, `RemotexViewModel.kt`, `RemotexViewModel.swift`).

Events are sequenced per session and kept in a replay buffer
(`RELAY_SESSION_REPLAY_LIMIT`, default 1000 frames), so a reconnecting
client passes its `last_seq` and catches up without disturbing the peers
that stayed connected. When the buffer has already evicted frames the
client asked for, the relay sends `replay-gap {missed_from, missed_to}`
ahead of the replay and the client renders a visible "earlier events
unavailable" marker — a hole is never presented as the whole transcript.
That includes `last_seq <= 0`: a client asking for everything from the
start still only gets the tail once the buffer has wrapped.

`seq` is minted per relay *process*, so a client that stored a cursor
before a redeploy presents one far ahead of the new counter. The relay
treats `last_seq > ` the session's current seq as a fresh attach and
replays everything it has; clients store the seq they last saw rather
than a running maximum, so the cursor can follow the counter back down.

## Failure modes

| Failure | Behavior |
|---|---|
| Relay restart | Daemons reconnect with exponential backoff + equal jitter (1s → 30s cap; reset after 60s stable). Clients re-attach; relay-memory replay is empty after the restart, while the persisted Codex thread still resumes. |
| Daemon crash / network loss | Host marked offline. Any active turn gets a sequenced failed `turn-completed`, its prompts are invalidated, and the turn slot is released. Sessions stay in the hub and resume their persisted Codex threads when the daemon reconnects. `POST /api/sessions` returns 502 while it is offline. |
| Client sends a turn while the host is offline | Relay clears the turn slot and synthesizes `turn-completed {error: "host offline"}` so the UI doesn't spin. |
| Client tab closed, session idle | Closed after `RELAY_CLIENT_RECONNECT_GRACE_SECONDS` (default 75s) of no client and no daemon activity. |
| Client tab closed, turn in flight | Kept alive as long as the daemon keeps emitting frames; killed only after `RELAY_SESSION_STALL_CEILING_SECONDS` (default 2h) of total silence. |
| Slow consumer | Per-socket send timeout of 5s, then close with 1013 (`slow consumer`). One slow client can't wedge the relay's event loop. |
| Second daemon for the same host | Older socket receives close 4000 (`daemon-replaced`) and exits without retrying; any old active turn fails cleanly before the replacement resumes the session. |
| REST flood | HTTP 429 with `Retry-After`. |
| WebSocket connect flood | Per-remote bucket; HTTP 429 with `Retry-After` before the upgrade. |
| Invalid bridge token | Relay closes the daemon WS with 4401. |
| Invalid user token | Relay closes the client WS with 4401. |
| Bridge key revoked | Relay closes the host's live daemon socket with 4401 (`bridge key revoked`). A daemon holding a still-valid key reconnects immediately. |
| Daemon frame for another host's session | Dropped, logged, and audited (`daemon.session.foreign`); a valid bridge key for one host can't write into another host's session. |
| Frame over the websocket ceiling | The socket is already closed by aiohttp; both handlers send `error: frame rejected (max N bytes)` before exiting rather than dropping silently. |
| Adapter fails to build / start a session | Contained to that session: the daemon emits `turn-completed {error}` + `session-closed` for it and keeps every other session on the host running. |
| Reservation never attached | Swept after `RELAY_SESSION_RESERVATION_TTL_SECONDS` (default 600); the row is closed and the hub state forgotten. |
| `codex app-server` dies mid-turn | Adapter fails every pending RPC, emits `turn-completed {error}` (and `thread-status: resume-failed` if a resume was in flight), and marks the session not-ready so later turns fail fast. |
| Codex slow to resume a long thread | Local rollout history is replayed immediately; the live `thread/resume` finishes in the background (600s ceiling). A new turn waits up to 20s on it. |

## Environment

Everything the relay reads, with the module that reads it. All of them
have defaults except `RELAY_DATABASE_URL`.

| Variable | Default | Effect |
|---|---|---|
| `RELAY_DATABASE_URL` | — | Postgres DSN. **Required**; `store.start()` raises without it. |
| `RELAY_SEED_DEMO` | off | Seed the demo user/host/bridge key on an empty database. Truthy = `1`/`true`/`yes`. |
| `REMOTEX_MAX_FILE_BYTES` | `26214400` | The one transfer ceiling; HTTP body and both WS frame caps derive from it. The daemon reads the same variable. |
| `RELAY_CLIENT_RECONNECT_GRACE_SECONDS` | `75` | How long an idle session survives with no client attached. |
| `RELAY_SESSION_STALL_CEILING_SECONDS` | `7200` | Hard kill for a session with a turn in flight but no activity at all. |
| `RELAY_SESSION_REPLAY_LIMIT` | `1000` | Frames buffered per session for reconnecting clients. |
| `RELAY_SESSION_RESERVATION_TTL_SECONDS` | `600` | How long a `POST /api/sessions` reservation survives unattached. |
| `RELAY_RATE_LIMIT_MAX_BUCKETS` | `10000` | Cap on each REST bucket map before an idle/LRU sweep. |
| `RELAY_RATE_LIMIT_IDLE_SECONDS` | `300` | Idle age at which a bucket is swept. |
| `RELAY_RATE_LIMIT_REMOTE_BURST` | `120` | Per-remote REST burst — the bucket a caller cannot escape by rotating credentials. |
| `RELAY_RATE_LIMIT_REMOTE_PER_SECOND` | `40` | Refill rate for that bucket. |
| `RELAY_TRUST_PROXY` | off | Read the client address from `X-Forwarded-For`. Set **only** behind a reverse proxy that overwrites it. |
| `RELAY_WS_CONNECT_BURST` | `60` | Websocket connection attempts per remote address. |
| `RELAY_WS_CONNECT_PER_SECOND` | `5` | Refill rate for that bucket. |

## Known gaps

What still has to change between this tree and something you'd point real
users at, ordered roughly by blast radius. The **Already handled** list at
the end exists so finished work doesn't get re-proposed.

### Relay

1. **Keycloak / OIDC for user auth.** Replace the token lookup in
   `store.user_for_token()` with a JWT verifier; claims → `owner_token`.
   This is the single biggest gap and everything below assumes it. Tokens
   are hashed at rest and demo seeding is opt-in, but a long-lived bearer
   string is still a long-lived bearer string.
2. **TLS by default.** The relay binds plain HTTP; `deploy/`'s `tls`
   profile fronts it with Caddy. Never expose the plain listener.
3. **Bridge-key metadata.** Issue / list / revoke exist. Still missing: an
   `issued_by` user id column and optional expiry, so a key's provenance
   and lifetime are auditable rather than just its hash.
4. **Audit retention.** `audit()` emits structured lines on `logger=audit`
   covering auth, daemon attach/detach, session attach, turn start,
   bridge-key revocation, and session close. Nothing ships them anywhere
   durable — wire up Loki/CloudWatch and pick a retention window.
5. **Metrics.** No Prometheus endpoint. Wanted: `relay_sessions_open`,
   `relay_frames_total{direction,kind}`, `relay_daemons_online`,
   `relay_ws_errors_total{kind}`.
6. **Per-session and per-host limits.** REST is bucketed per token and
   websocket *connects* per remote address. Still missing: a cap on
   concurrent sessions per user, and per-host reconnect limits.
7. **Durable replay.** The replay buffer is in-memory
   (`RELAY_SESSION_REPLAY_LIMIT`, default 1000) and dies with the process,
   so a relay restart loses transcript catch-up for live sessions —
   `replay-gap` makes the loss visible rather than fixing it. The relay
   persists session open/close rows and resume state only. Persist the
   buffer, or accept the loss explicitly.
8. **Horizontal scale.** `Hub` is process-local: two relay replicas can't
   route each other's sessions. Multi-replica needs sticky routing by
   `host_id` or a shared bus (Redis/NATS) behind the hub.

### Daemon

1. **Packaging.** Ship as a wheel (`pip install remotex-daemon`) and a
   single-file PyInstaller binary for users without a Python toolchain.
   Today it's a git clone plus `deploy/install-daemon.sh`.
2. **Non-Linux service integration.** Linux has a systemd user unit;
   Windows (NSSM / `pywin32`) and macOS (`launchd` plist) don't.
3. **First-run validation.** `daemon init` writes the config without
   checking it. It should dial the relay once and confirm the bridge token
   is valid before writing anything. (It does now refuse an insecure
   `relay_url` at `run` time — see Trust boundaries.)
4. **Update channel.** Check a `/api/daemon/version` endpoint on start and
   warn — never auto-update — when behind.
5. **Filesystem hardening.** The `fs-*` handlers operate on absolute paths
   anywhere the daemon user can reach, trusting the relay to have
   authenticated the caller. `mkdir` validates its name segment, `delete`
   refuses directories, and both directions enforce
   `REMOTEX_MAX_FILE_BYTES`, but there is no root-jail. Decide whether to
   confine them to configured roots.

### Clients

1. **iPhone parity.** The SwiftUI app now has approvals, user-input
   prompts, prompt queues, replay-gap markers, and Keychain token storage;
   still missing are thread resume, images, model/effort controls,
   permissions, interrupt, and reconnect backoff. See `apple/README.md`.
2. **Push notifications for approvals.** Android has a foreground service
   and turn-complete notifications; no platform gets a push when an
   approval lands while the app is closed. Needs FCM (Android) and APNs
   (iOS), which needs the relay to hold device tokens.
3. **Runtime relay picker on Android.** `BuildConfig.RELAY_URL` is baked in
   at compile time, so pointing at a different relay means a rebuild. iOS
   already does this at runtime.

### Tests

1. **Fault tests.** Missing: kill the daemon mid-stream and assert the
   client sees a terminal frame; a slow client actually getting closed with
   1013; host going offline mid-turn. (First-response-wins arbitration,
   claim restore, and queue position are covered by `test_hub.py` and
   `test_ws_client_prompts.py`.)
2. **Adapter tests against captured frames.** `services/tests/` covers hub
   routing, ws attach/binding, prompt queues, session reservations, rate
   limiting, size limits + fs, logging, both model endpoints, store
   helpers, and daemon config/helpers. The `stdio.py` dispatch table — the
   most protocol-fragile code in the repo — is still only exercised
   indirectly.

### Rollout

- Phase 1: self-host the relay for yourself. This is where the project is
  now, and it works.
- Phase 2: invite-gate a handful of trusted users. Needs OIDC first.
- Phase 3: public signup. Audit retention, metrics, and horizontal scale
  become non-negotiable.

### Already handled

Kept so these don't get re-proposed:

- **Postgres inventory store.** `asyncpg`, schema in `relay/store.py`.
  SQLite is gone.
- **Tokens hashed at rest**, with an in-place migration of old plaintext
  rows, `key_id` handles instead of readable keys, and `user_hash` in
  audit lines.
- **Opt-in demo credentials** (`RELAY_SEED_DEMO`, default off).
- **Bridge-key issue / list / revoke**, including dropping the host's live
  daemon socket on revoke.
- **Host-scoped model listing** (`GET /api/hosts/{id}/models`) with the
  hostless "let Codex decide" sentinel as an automatic fallback.
- **One transfer ceiling** (`REMOTEX_MAX_FILE_BYTES`) shared by the HTTP
  body cap, both relay websockets, and the daemon's `ws_connect`, with
  explicit errors rather than dropped sockets.
- **`StdioCodexAdapter` against a real `codex app-server`.** It is the
  default mode; `MockCodexAdapter` is opt-in (`--mode mock`) and drives the
  e2e test.
- **Request/response correlation.** `_request()` parks a future per rpc id;
  `_read_line_unbounded()` handles multi-MB resume replies. Relay-side
  admin futures are keyed by `(host_id, request_id)`.
- **`thread/resume` on reconnect**, with local rollout replay so the
  transcript renders before Codex finishes rehydrating, and durable
  `thread_id` / `cwd` so a relay restart resumes rather than restarts.
- **Bounded queues / backpressure.** `_bounded_send()` closes a socket that
  can't accept a frame within 5s (code 1013) instead of letting one slow
  consumer stall the relay.
- **Rate limiting.** REST token bucket with a bounded, LRU-swept bucket
  map, plus a per-remote websocket connect bucket.
- **Structured JSON logs + audit events** on `logger=audit`.
- **Session resume for clients.** Sequenced events, a replay buffer, a
  `pending-prompts` snapshot, `replay-gap` when the buffer fell short, and
  a grace watchdog that distinguishes an idle session from one still
  producing output.
- **Approval and user-input plumbing**, end to end, as ordered queues with
  first-response-wins arbitration and claim restore on a failed forward.
- **Session reservation TTL sweeper**, so an abandoned `POST /api/sessions`
  doesn't leak a row and a `(host, thread)` index entry.
- **Daemon insecure-relay guard** (`allow_insecure`).
- **systemd user unit** (`deploy/remotex-daemon.service` +
  `install-daemon.sh`).
- **CI**: ruff, pytest, relay↔daemon e2e, web lint/build/audit/vitest,
  Android APK + unit tests + lint, and an iPhone simulator build.
