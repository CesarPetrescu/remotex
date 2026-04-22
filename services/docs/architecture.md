# Remotex — architecture

## Goal

Let a user control their Codex sessions from a phone or browser, even when
the machine running Codex is behind NAT/CGNAT/firewalls. Multi-user,
multi-machine per user.

## Topology

```
    Client (web / iOS / Android)
              │
              │  WSS (user bearer token)
              ▼
    ┌────────────────────────┐           ┌────────────────────────┐
    │  Relay (central, k8s)  │◄── WSS ──►│ Daemon (user's host)   │
    │  · OIDC via Keycloak   │  outbound │ · reads Bridge API key │
    │  · routes session IDs  │   only    │ · spawns / attaches to │
    │  · passthrough only    │           │   `codex app-server`   │
    └────────────────────────┘           │ · stdio JSON-RPC local │
                                         └────────────────────────┘
                                                     │
                                                     ▼
                                           codex app-server (local)
```

Clients and daemons **both dial outward** to the relay. The relay is the
matchmaker; it never opens inbound connections to a daemon, and the
daemon never opens an inbound port on the host.

## Trust boundaries

Two separate credentials live on the daemon machine:

| Credential        | Purpose                            | Location                         | Who holds it |
|-------------------|------------------------------------|----------------------------------|--------------|
| Bridge API key    | Auth the **daemon** to the relay   | `~/.remotex/config.toml`         | Remotex      |
| OpenAI / Codex auth | Auth **codex** to the model     | `~/.codex/auth.json`             | OpenAI       |

The daemon **never reads** the Codex auth. It only spawns the local
`codex app-server` process, which picks up `~/.codex/auth.json` the way
it always does. Conflating the two is the single biggest footgun in
this system.

The relay **never parses Codex payloads**. It shuttles opaque session
envelopes between a daemon and a client by session ID.

## Frame envelope (relay ↔ daemon / relay ↔ client)

All WebSocket frames are single-line JSON objects with a `type` field.
Session-scoped frames carry a `session_id`.

| Direction        | `type`            | Notes |
|------------------|-------------------|-------|
| daemon → relay   | `hello`           | `{token, hostname, platform, nickname}` |
| relay → daemon   | `welcome`         | `{host_id}` |
| relay → daemon   | `session-open`    | starts a session for `session_id` |
| client → relay   | `hello`           | `{token, session_id}` |
| relay → client   | `attached`        | `{session_id, host_id}` |
| client → relay → daemon | `turn-start` | `{session_id, input}` |
| client → relay → daemon | `approval`   | `{session_id, approval_id, decision}` |
| daemon → relay → client | `session-event` | `{session_id, event: {kind, data, ts}}` |
| daemon → relay → client | `session-closed` | terminal frame |

`kind` values used by the mock adapter (kept close to the real protocol
item types so the client UI needs no rewrite when we swap to stdio):

- `session-started` — `{model, cwd, sandbox}`
- `turn-started` / `turn-completed` — `{turn_id, input?}`
- `item-started` — `{turn_id, item_id, item_type, ...}` where
  `item_type ∈ {agent_reasoning, tool_call, agent_message}`
- `item-delta` — streaming chunks for `agent_message`
- `item-completed` — terminal payload for any item

## Connection lifecycle

1. **Daemon starts** → `hello` with bridge token. Relay looks up the
   `host_id`, marks the host online, replies `welcome`.
2. **User opens a session from the client.** Client does
   `POST /api/sessions {host_id}` (REST, bearer token), gets
   `session_id` back. Nothing happens on the daemon yet.
3. **Client attaches** to `/ws/client` with `hello{token, session_id}`.
   Relay verifies ownership, replies `attached`, **then** sends
   `session-open` to the daemon. This ordering guarantees the client
   observes `session-started` and every subsequent event.
4. **Client sends `turn-start`** — relay forwards it verbatim to the
   daemon; the daemon runs the adapter; events flow back the same way.
5. **Disconnect** — when the daemon socket closes, the relay marks the
   host offline and drops all sessions routed through it; any attached
   client gets a terminal `error: host offline`.

## Failure modes

| Failure                          | Behavior |
|----------------------------------|----------|
| Relay restart                    | Daemons reconnect with exponential backoff (1s → 30s cap). Clients must re-attach. |
| Daemon crash / network loss      | Host marked offline. New session requests return 502 until it reconnects. |
| Client tab closed                | Session remains open on the daemon until the daemon closes it. A future client reattach can resume via `session_id` (not yet implemented). |
| Relay overload                   | Return HTTP 503 on REST, backoff on WS. Bounded queues per session. |
| Invalid bridge token             | Relay closes daemon WS with code 4401 and does not retry. |
| Invalid user token               | Relay closes client WS with code 4401. |

## What this prototype skips (on purpose)

- **Real auth.** Uses a hardcoded `demo-user-token` / `demo-bridge-token`
  seeded on first DB init. Production: OIDC via Keycloak for users, HMAC-
  signed or Keycloak-scoped tokens for bridge keys.
- **TLS.** Terminate at Envoy Gateway; never ship the plain `ws://`
  listener in front of real users.
- **Session resume.** A reconnecting client currently loses the stream
  unless it attaches inside the turn's lifetime.
- **Approval plumbing.** The envelope is defined (`approval`) but the
  mock adapter doesn't request approvals yet.
- **Per-session audit log / persistence.** The relay currently only
  persists session open/close, not the event stream.
