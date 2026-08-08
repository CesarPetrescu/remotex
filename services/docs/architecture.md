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

The relay **never interprets Codex payload content**. It does read the
event *envelope* — `event.kind` plus a handful of ids — to track turn
state, index threads, and hold pending approvals (`handlers/ws_daemon.py`).
Everything inside `event.data` is shuttled through opaque.

## REST API

All routes require `Authorization: Bearer <user token>` (or `?token=`).
Rate limited per token: token bucket, 30 burst / 10 req/s, HTTP 429 with
`Retry-After` on overflow. `/ws/*` and `/assets/*` bypass the bucket.

| Method | Path                                | Purpose |
|--------|-------------------------------------|---------|
| GET    | `/api/models`                       | Model + reasoning-effort catalogue (`relay/models.py`) |
| GET    | `/api/hosts`                        | Hosts owned by the caller |
| POST   | `/api/hosts`                        | Register a host row |
| POST   | `/api/hosts/{id}/api-key`           | Mint a bridge token for that host |
| GET    | `/api/hosts/{id}/threads`           | Proxied `thread/list` (30s timeout) |
| GET    | `/api/hosts/{id}/fs`                | List a directory on the host |
| POST   | `/api/hosts/{id}/fs/mkdir`          | Create a directory |
| GET    | `/api/hosts/{id}/fs/read`           | Read a file (base64, 50 MB cap) |
| POST   | `/api/hosts/{id}/fs/delete`         | Delete a single file (never a directory) |
| POST   | `/api/hosts/{id}/fs/rename`         | Move/rename, refuses to overwrite |
| POST   | `/api/hosts/{id}/fs/upload`         | Write a file from a base64 payload |
| GET    | `/api/hosts/{id}/telemetry`         | Cached telemetry snapshot + ~30s history |
| POST   | `/api/sessions`                     | Reserve a session id (see lifecycle below) |

Every `fs/*` and `threads` route is a *proxy*: the relay sends a request
frame to the daemon with a `request_id`, parks a future in
`hub.pending_admin`, and resolves it when the matching response frame
comes back.

Anything else under `/` serves the SPA (`handlers/static.py`).

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
| relay → daemon | `fs-{read,mkdir,readfile,delete,rename,write}-request` | `{request_id, …}` |
| daemon → relay | `threads-list-response` / `fs-*-response` | `{request_id, …}` or `{request_id, error}` |
| daemon → relay | `ping` → `pong`           | keepalive |

Client frames are forwarded to the daemon **verbatim**, with `session_id`
and `client_id` stamped on by the relay.

### Client ↔ relay

| Direction      | `type`                  | Payload |
|----------------|-------------------------|---------|
| client → relay | `hello`                 | `{token, session_id, client_id?, client_name?, last_seq?}` |
| relay → client | `attached`              | `{session_id, host_id, client_id, peer_count}` |
| relay → client | `pending-prompts`       | `{approvals[], user_inputs[]}` — unresolved prompts, not sequenced |
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
   host so sessions survive a daemon reconnect.
2. **User opens a session.** Client does `POST /api/sessions {host_id,
   thread_id?, cwd?}`. The relay reserves a `session_id`. Nothing happens
   on the daemon yet. If a session is already live for that
   `(host, thread)`, the existing id comes back with `reused: true`
   instead of spawning a second Codex.
3. **Client attaches** to `/ws/client` with `hello{token, session_id,
   last_seq?}`. The relay verifies ownership, replies `attached`, sends a
   `pending-prompts` snapshot, replays every buffered event newer than
   `last_seq`, **then** sends `session-open` to the daemon. That ordering
   guarantees the client observes `session-started` and everything after.
4. **Client sends `turn-start`.** The relay reserves the session's single
   turn slot (a second concurrent turn gets `error`), echoes the user
   message to every attached peer, and forwards the frame to the daemon.
5. **Daemon runs the adapter**, translating to `turn/start` over stdio;
   events flow back and are sequenced, buffered, and fanned out to all
   attached clients.
6. **Disconnect** — see the failure table below. Sessions are *not*
   dropped when the daemon socket closes; they are held so a reconnecting
   daemon can pick them back up.

## Multi-client semantics

Several clients can attach to the same `session_id` at once; each has its
own `client_id` and all of them see the same event stream. Reattaching
with the same `client_id` displaces the older socket (close code 4000,
`replaced`).

Approvals and user-input prompts are claimed atomically — first response
wins, later ones get `error: approval already resolved`, and every peer is
told the outcome via `approval-resolved` / `user-input-resolved`.

Events are sequenced per session and kept in a replay buffer
(`RELAY_SESSION_REPLAY_LIMIT`, default 1000 frames), so a reconnecting
client passes its `last_seq` and catches up without disturbing the peers
that stayed connected.

## Failure modes

| Failure | Behavior |
|---|---|
| Relay restart | Daemons reconnect with exponential backoff + jitter (1s → 30s cap). Clients re-attach and replay from `last_seq`. |
| Daemon crash / network loss | Host marked offline. Sessions are kept in the hub for reattach. `POST /api/sessions` for that host returns 502 until it reconnects. |
| Client sends a turn while the host is offline | Relay clears the turn slot and synthesizes `turn-completed {error: "host offline"}` so the UI doesn't spin. |
| Client tab closed, session idle | Closed after `RELAY_CLIENT_RECONNECT_GRACE_SECONDS` (default 75s) of no client and no daemon activity. |
| Client tab closed, turn in flight | Kept alive as long as the daemon keeps emitting frames; killed only after `RELAY_SESSION_STALL_CEILING_SECONDS` (default 2h) of total silence. |
| Slow consumer | Per-socket send timeout of 5s, then close with 1013 (`slow consumer`). One slow client can't wedge the relay's event loop. |
| Second daemon for the same host | Older socket closed with 4000 (`daemon-replaced`). |
| REST flood | HTTP 429 with `Retry-After`. |
| Invalid bridge token | Relay closes the daemon WS with 4401. |
| Invalid user token | Relay closes the client WS with 4401. |
| `codex app-server` dies mid-turn | Adapter fails every pending RPC, emits `turn-completed {error}` (and `thread-status: resume-failed` if a resume was in flight), and marks the session not-ready so later turns fail fast. |
| Codex slow to resume a long thread | Local rollout history is replayed immediately; the live `thread/resume` finishes in the background (600s ceiling). A new turn waits up to 20s on it. |

## What this deployment still skips

- **Real auth.** Bearer tokens looked up in Postgres, with
  `demo-user-token` / `demo-bridge-token` seeded on first init. See
  `production_plan.md`.
- **TLS by default.** The relay binds plain HTTP; `deploy/`'s `tls`
  profile fronts it with Caddy. Never expose the plain listener.
- **Event persistence.** The relay persists session open/close rows only.
  The replay buffer is in-memory and dies with the process.
- **Audit retention.** `audit()` writes structured log lines; nothing
  ships them anywhere durable.
