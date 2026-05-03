# AGENTS.md — implementation guide for AI coding agents

> Companion to `CLAUDE.md`. CLAUDE.md is the short-and-sharp
> always-loaded reminder. This file is the longer onboarding doc you
> read once before you start touching code, then refer back to when
> something doesn't behave like you expected.

---

## What Remotex actually is

Remotex is a **remote-control plane for OpenAI's Codex CLI**.

End users want to run Codex on a beefy workstation but interact with
it from a laptop, phone, or browser tab — without exposing the
workstation to the internet. Remotex makes that work by inverting
the connection direction: the user's *workstation* dials out to a
relay, and the user's *clients* (web/Android/iOS) also dial out to
the same relay. Neither side needs an inbound port.

It is **not**:

- A wrapper around the OpenAI API. We don't talk to OpenAI; codex
  does.
- A reimplementation of codex. We spawn the official `codex
  app-server` binary and bridge its stdio JSON-RPC.
- A multi-tenant chat product. There's a user/auth layer but the
  intended deployment is "a few people who trust each other share
  a relay."

It **is**:

- A WebSocket fan-out for codex sessions: one codex process can
  have multiple clients attached and they all see the same stream.
- An orchestrator on top of codex: a long-horizon "brain" codex
  that plans a DAG of subtasks and dispatches each to a fresh
  child codex via MCP.
- A semantic-search index over historical chats (pgvector +
  Qwen3-Embedding-8B).

---

## Architecture at the level you need to make changes

```
┌────────────────────────────┐  HTTPS+WSS                  ┌──────────────┐
│  Web client (apps/web)     │ ─────────────────────────▶  │              │
│  Android app (android/)    │  user bearer token          │              │
│  iOS app (apple/)          │                             │              │
└────────────────────────────┘                             │              │
                                                           │  Relay       │
┌────────────────────────────┐  WSS (outbound)             │  services/   │
│  Host daemon (services/    │ ─────────────────────────▶  │  relay/      │
│  daemon/) — systemd user   │  bridge token               │              │
│  unit `remotex-daemon`     │                             │              │
└─────────────┬──────────────┘                             └──────┬───────┘
              │ stdio JSON-RPC                                    │
              ▼                                                   ▼
   `codex app-server`                                  Postgres + pgvector
   (the official OpenAI                                (chat history,
    binary; one subprocess                              semantic search,
    per session)                                        host inventory)
```

### Data flow for a single user prompt

1. User types in the web/Android composer.
2. Client sends `{"type": "turn-start", "input": "...", ...}` over
   the WebSocket to the relay.
3. Relay forwards the frame to the daemon for that session
   (`hub.broadcast_to_daemon`).
4. Daemon's `StdioCodexAdapter.handle()` translates the frame into
   `turn/start` JSON-RPC and writes it to codex's stdin.
5. Codex emits a stream of notifications: `turn/started`,
   `item/started`, `item/.../delta`, `item/completed`,
   `turn/completed`. The daemon's `_dispatch()` translates each
   into a `SessionEvent`.
6. Each SessionEvent becomes a `{"type": "session-event", "event":
   {"kind": ..., "data": ...}}` frame on the relay→clients
   WebSocket.
7. Web (`useRemotex.handleFrame`) and Android
   (`RemotexViewModel.handleFrame`) reduce these into UI state.

### Component-by-component

**`services/relay/`** (aiohttp inside docker `remotex-relay-1`)
- `app.py` — HTTP routes + WebSocket entry points.
- `handlers/sessions.py` — `POST /api/sessions` (reserves session
  IDs, picks `kind`, stashes per-session overrides).
- `handlers/ws_daemon.py` — daemon WebSocket; receives
  session-event frames and broadcasts to attached clients;
  persists orchestrator events.
- `handlers/ws_client.py` — client WebSocket; manages attach/grace,
  fans out to multiple peers, replays buffered events on reconnect.
- `hub.py` — in-memory state: which daemons are online, which
  sessions are open, attach maps, replay buffers.
- `store.py` — Postgres access (sessions, hosts, chats).
- `search/` — pgvector indexer + reranker integration.

**`services/daemon/`** (Python systemd user unit `remotex-daemon`)
- `client.py` — outbound WebSocket to relay; receives session-open
  / session-close frames and constructs adapters.
- `adapters/factory.py` — picks `StdioCodexAdapter` (kind=codex)
  or `OrchestratorAdapter` (kind=orchestrator).
- `adapters/stdio.py` — bridges one codex subprocess to relay
  frames. The "main" file in the daemon (~700 lines).
- `adapters/admin.py` — long-lived codex used for cheap read-only
  ops (thread/list, fs/read).
- `adapters/items.py` — translates codex item types to our wire
  shape.
- `adapters/permissions.py` — maps UI permission chip → codex
  `sandboxPolicy` + `approvalPolicy`.
- `orchestrator/adapter.py` — wraps a brain `StdioCodexAdapter`,
  injects the orchestrator MCP server into its `thread/start`
  config.
- `orchestrator/mcp_shim.py` — standalone MCP server that codex
  spawns; relays `tools/call` over a Unix socket to the daemon.
- `orchestrator/bridge.py` — Unix socket server inside the
  daemon, dispatches MCP tool calls to runtime methods.
- `orchestrator/runtime.py` — DAG of `_Step` objects, owns child
  StdioCodexAdapter lifecycles, emits orchestrator-step-* events.

**`apps/web/`** (Vite + React, bundled into relay docker image)
- `src/hooks/useRemotex.js` — single big reducer + WebSocket
  attach/reconnect + REST fetch wrappers. State machine for the
  whole app.
- `src/screens/SessionScreen.jsx` — chat surface.
- `src/screens/DashboardScreen.jsx` — landing surface.
- `src/components/Composer.jsx` — chip row + textarea + send/stop.
- `src/components/PlanTree.jsx` — orchestrator plan panel
  (collapsible).
- `src/components/Pickers.jsx` — model/effort/permissions/kind
  chip components.
- `src/components/{Approval,UserInput,FolderPicker,Toast}.jsx` —
  modals; **all rendered through `createPortal(document.body)`**
  because `.dashboard-layout > * { position: relative }` would
  otherwise kick their `position: fixed` into a stray grid cell.

**`android/`** (Kotlin + Jetpack Compose)
- `app/src/main/java/app/remotex/ui/RemotexViewModel.kt` —
  mirror of useRemotex.js (same event reducer, same state shape).
- `app/src/main/java/app/remotex/net/{RelayClient,SessionSocket}.kt`
  — REST + WebSocket.
- `app/src/main/java/app/remotex/ui/screens/session/` — session
  surface; matches `apps/web/src/screens/SessionScreen.jsx`.
- `build.sh` (top of `android/`) — wraps Gradle with the right
  LAN relay URL. **Always use this**, not `gradlew assembleDebug`
  alone.

---

## Codex source clone — required reading before touching codex code

The codex JSON-RPC app-server protocol is undocumented outside the
codex repo itself. The schema changes between minor versions
(e.g. 0.122 → 0.128 added `tool_search`, changed `RequestUserInput`
shape, etc). Your training data is almost certainly stale.

**Mandatory first step** for any work touching codex behavior:

```bash
test -d /tmp/codex || git clone https://github.com/openai/codex /tmp/codex
```

Then grep the source. The files you'll need most often:

| You're working on | Read |
|---|---|
| New JSON-RPC method or notification | `/tmp/codex/codex-rs/app-server-protocol/src/protocol/v2.rs` (the canonical wire definition) and `/tmp/codex/codex-rs/app-server-protocol/src/protocol/common.rs` (method name → enum mapping) |
| Item types codex emits | `/tmp/codex/codex-rs/app-server-protocol/src/protocol/v2.rs` (`enum ThreadItem`) + `/tmp/codex/codex-rs/protocol/src/items.rs` |
| Approval / sandbox / permissions | `/tmp/codex/codex-rs/protocol/src/{approvals,permissions,request_user_input}.rs` and `/tmp/codex/codex-rs/codex-mcp/src/mcp/mod.rs` (auto-approve rules) |
| MCP tool calls (codex acting as MCP client) | `/tmp/codex/codex-rs/core/src/mcp_tool_call.rs` and `/tmp/codex/codex-rs/codex-mcp/src/` |
| Plan mode / collaboration mode | `/tmp/codex/codex-rs/protocol/src/plan_tool.rs` and `collaboration_mode_kind` references in `core/` |
| Rollout file format (chat history on disk) | `/tmp/codex/codex-rs/rollout/` and `/tmp/codex/codex-rs/rollout-trace/` |
| TUI behavior (useful for "how does the official client handle this?") | `/tmp/codex/codex-rs/tui/` |

**Don't quote line numbers from this file** — codex moves fast and
they'll drift. Always re-grep when you need them.

---

## Test against a real codex BEFORE writing code

Don't trust your mental model of what codex emits. Drive a real
`codex app-server` process manually first, capture its output, then
encode the observed shape into the daemon adapter.

### Minimal probe pattern

```python
# /tmp/codex-probe.py
import asyncio, json

async def main():
    proc = await asyncio.create_subprocess_exec(
        "codex", "app-server",
        stdin=asyncio.subprocess.PIPE,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )

    async def send(frame):
        proc.stdin.write((json.dumps(frame) + "\n").encode())
        await proc.stdin.drain()

    async def reader():
        while True:
            line = await proc.stdout.readline()
            if not line: return
            try:
                msg = json.loads(line)
                print("⇐", json.dumps(msg)[:400])
            except json.JSONDecodeError:
                print("⇐ raw:", line[:200])

    asyncio.create_task(reader())
    await send({"id": 1, "method": "initialize", "params": {
        "clientInfo": {"name": "probe", "title": "Probe", "version": "0.1"},
        "capabilities": {"experimentalApi": True},
    }})
    await asyncio.sleep(0.5)
    await send({"method": "initialized", "params": {}})
    await asyncio.sleep(0.5)

    # ---- replace this block with whatever you're investigating ----
    await send({"id": 2, "method": "thread/start", "params": {
        "cwd": "/tmp",
        "ephemeral": True,
        # add per-feature config here, e.g. mcp_servers, etc.
    }})
    # --------------------------------------------------------------

    await asyncio.sleep(60)
    proc.kill()
    await proc.wait()

asyncio.run(main())
```

Run it: `python3 /tmp/codex-probe.py 2>&1 | tee /tmp/codex-probe.log`.

Read the log, identify the exact shape codex actually emits for
your feature, then add a handler in `services/daemon/adapters/
stdio.py::_dispatch()` that mirrors that shape. **Update the unit
test fixtures** in `services/tests/` to match the real frames you
captured.

### When to do this

Always do it for:
- New `item/...` notifications you want to render in the UI.
- New approval prompt flows (`item/tool/requestUserInput`,
  `item/commandExecution/requestApproval`, etc).
- Any change to `thread/start.config` — codex silently ignores
  unknown keys but the surface area is huge.
- Anything MCP-related on the codex side (codex as MCP client OR
  server).

Skip it for:
- UI-only changes that don't depend on codex behavior.
- Pure web/Android cosmetic work.
- Renames, refactors, comment cleanup.

---

## Wire shapes you'll touch most

### Daemon → relay (session events)

```json
{
  "type": "session-event",
  "session_id": "sess_…",
  "event": {
    "kind": "session-started" | "turn-started" | "item-started" |
            "item-delta" | "item-completed" | "turn-completed" |
            "approval-request" | "user-input-request" |
            "orchestrator-plan" | "orchestrator-step-status" |
            "orchestrator-step-event" | "orchestrator-finished" |
            "host-telemetry" | "session-closed",
    "data": { … kind-specific … },
    "ts": 1234567890.123
  }
}
```

### Relay → daemon (session control)

```json
{ "type": "session-open",  "session_id": "...", "kind": "codex"|"orchestrator", "cwd": "...", "thread_id": "...", "task": "...", "model": "...", "effort": "...", "permissions": "...", "approval_policy": "..." }
{ "type": "session-close", "session_id": "..." }
```

### Client → relay (input)

```json
{ "type": "turn-start", "input": "user prompt", "model": "...", "effort": "...", "permissions": "...", "images": [{"data": "base64", "mime": "image/png"}] }
{ "type": "turn-interrupt" }
{ "type": "approval-response", "approval_id": "appr_…", "decision": "accept"|"acceptForSession"|"decline"|"cancel" }
{ "type": "user-input-response", "call_id": "ui_…", "answers": { ... } }
{ "type": "slash-command", "command": "plan"|"default"|"cd"|"pwd"|"compact", "args": "..." }
```

### Daemon ↔ codex (stdio JSON-RPC)

Sent by daemon: `initialize`, `initialized`, `thread/start`,
`thread/resume`, `turn/start`, `turn/interrupt`,
`item/tool/requestUserInput/respond`, `item/{commandExecution,
fileChange}/requestApproval/respond`.

Received from codex: `turn/started`, `turn/completed`,
`item/started`, `item/{type}/delta`,
`item/reasoning/summaryTextDelta`,
`item/reasoning/summaryPartAdded`, `item/completed`,
`item/tool/requestUserInput`,
`item/{commandExecution,fileChange}/requestApproval`,
`thread/tokenUsage/updated`, `thread/started`, etc.

For exact field shapes, see the canonical reference:
`/tmp/codex/codex-rs/app-server-protocol/src/protocol/v2.rs`.

---

## Common gotchas (one-line each)

- The relay's `.dashboard-layout > * { position: relative }` rule
  overrides any modal scrim's `position: fixed`. **Always portal
  modals to `document.body`**, never render them inline inside the
  dashboard layout.
- The Android debug APK's default relay URL is `10.0.2.2:8080`.
  Real devices need the LAN IP — use `android/build.sh`.
- The orchestrator brain MUST run with `permissions=full`. Codex
  only auto-approves MCP tool calls when `approvalPolicy=never`
  AND `sandbox=dangerFullAccess`. Otherwise it pops a
  `RequestUserInput` and the brain hangs.
- Codex's `thread/resume` reply inlines the entire conversation as
  one JSON-RPC frame. Use `_read_line_unbounded` (in
  `adapters/stdio.py`) — `StreamReader.readline()`'s 64KB cap
  silently truncates and stranding the read task.
- Restarting the daemon kills any in-flight orchestrator. The
  child codex processes get reparented to init. Clean them up
  with `pkill -f "codex app-server"` if they accumulate.
- The Postgres container is `remotex-postgres-1`. Don't confuse
  it with the unrelated `cdx-chat-postgres-1` running on the same
  docker daemon.
- `kind` (`coder` | `orchestrator`) is on the wire in
  `session-started.data.kind`. Both clients consume it; don't
  remove it without updating both.
- Web's PlanTree auto-expands when a step transitions to
  `running`; respect that signal if you add new orchestrator
  events.

---

## Where to start when you're stuck

1. **Read the relevant subproject README** — `apps/web/`,
   `android/`, `services/`, `deploy/` each have one.
2. **Grep `/tmp/codex/codex-rs/`** for the codex-side wire shape.
3. **Probe a real codex** with the pattern above; don't guess.
4. **Read the daemon journal** (`journalctl --user -u
   remotex-daemon -n 200 --no-pager`).
5. **Read the relay logs** (`docker logs --since 30m
   remotex-relay-1`).
6. **Check the actual SQLite/Postgres state** —
   `docker exec remotex-postgres-1 psql -U remotex -d remotex
   -c "SELECT id, kind, ... FROM inventory_sessions ORDER BY
   opened_at DESC LIMIT 5;"`.
7. **Inspect open codex processes**: `pstree -lp $(pgrep -f
   "remotex-daemon")` — orchestrator brains have `mcp_shim.py` as
   a sibling child; coder sessions don't.
