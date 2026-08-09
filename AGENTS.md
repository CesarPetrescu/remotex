# AGENTS.md — implementation guide for AI coding agents

> Companion to `CLAUDE.md`. CLAUDE.md is the short-and-sharp
> always-loaded reminder. This file is the longer onboarding doc you
> read once before you start touching code, then refer back to when
> something doesn't behave like you expected.

---

## Before you touch anything: the shared log

Agents run here in parallel, in separate sessions, with no view of each
other. Four files carry state between them — treat them as part of the
build, not as documentation:

- **`WorkLog.md`** — append-only record of what changed. **Read the last
  few entries first**: someone may be mid-edit in your file. **Write an
  entry before you report done**, including what you verified versus
  assumed, and which restart ritual your change needs. Newest on top.
  Template is in the file.
- **`Issues.md`** — known problems we chose not to fix yet. Numbered
  `I-NNN`, permanent IDs, with symptom / cause / how-to-fix / evidence.
  Noticed something and walked past it? File it. Fixed one? Set status
  and add a Resolution line pointing at your WorkLog entry.
- **`ToDo.md`** — analysed-but-unstarted work, with `file:line` steps.
  Ranked bugs first. Also records how the codex protocol audit was done,
  so the next bump doesn't start from zero.
- **`CLAUDE.md`** — always loaded; conventions and restart rituals.

Rules of thumb: don't rewrite another agent's entry, cross-reference
IDs instead of restating findings, and if you stop half-way, log the
half. A silent partial change is worse than no change.

### Ground truth for the codex protocol

The installed binary emits its own schema — prefer it over the
`/tmp/codex` clone, which tracks HEAD and is ahead of what hosts run:

```bash
codex --version
codex app-server generate-json-schema --out /tmp/codex-schema
codex app-server generate-ts --out /tmp/codex-ts
```

Use the dump for **field shapes**. Use a live probe for **whether a
method exists** — the dump is incomplete (see `I-007`), and it omits
methods Remotex successfully calls today. Any probe needs an unbounded
line reader; `skills/list` alone exceeds asyncio's 64KB default.

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
   `codex app-server`                                  Postgres
   (the official OpenAI                                (host inventory)
    binary; one subprocess
    per session)
```

### Data flow for a single user prompt

1. User types in the web/Android composer.
2. Client sends `{"type": "turn-start", "input": "...", ...}` over
   the WebSocket to the relay.
3. Relay forwards the frame to the daemon for that session
   (`hub.forward_to_daemon`).
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
  IDs, picks `kind`, stashes per-session overrides) plus the TTL
  sweeper that reaps reservations nobody attached to.
- `handlers/ws_daemon.py` — daemon WebSocket; receives
  session-event frames and broadcasts to attached clients.
- `handlers/ws_client.py` — client WebSocket; manages attach/grace,
  fans out to multiple peers, replays buffered events on reconnect
  (and emits `replay-gap` when the buffer fell short).
- `handlers/daemon_rpc.py` — the shared REST→daemon round-trip
  (ownership check, `request_id`, parked future). `threads.py`,
  `fs.py`, and the host-models route all go through it.
- `hub.py` — in-memory state: which daemons are online, which
  sessions are open, attach maps, replay buffers, pending-prompt
  queues.
- `limits.py` — `REMOTEX_MAX_FILE_BYTES` and the HTTP-body /
  WS-frame ceilings derived from it. `daemon/limits.py` is its twin;
  changing one means changing both.
- `store.py` — Postgres access (users, hosts, bridge keys,
  sessions). Tokens are stored as `sha256(token)` — every lookup
  hashes its input, so never compare a raw token to a column.

**`services/daemon/`** (Python systemd user unit `remotex-daemon`)
- `client.py` — outbound WebSocket to relay; receives session-open
  / session-close frames and constructs adapters.
- `adapters/factory.py` — picks `StdioCodexAdapter` (kind=codex)
  or `MockCodexAdapter` (mode=mock).
- `adapters/stdio.py` — bridges one codex subprocess to relay
  frames. The "main" file in the daemon (~700 lines).
- `adapters/admin.py` — long-lived codex used for cheap read-only
  ops (`thread/list`, `model/list`, `fs/readDirectory`), plus the
  `model/list` → `{id, label, hint, efforts}` mapping the relay's
  host-models route serves.
- `adapters/items.py` — translates codex item types to our wire
  shape (`commandExecution` → `tool_call`, etc).
- `adapters/permissions.py` — maps UI permission chip → codex
  `sandboxPolicy` + `approvalPolicy`.
- `adapters/rollout.py` — reads `~/.codex/sessions` rollout files so a
  resumed thread renders before codex finishes rehydrating.
- `adapters/reasoning.py` — summarizes reasoning content blocks.
- `adapters/codex_config.py` — flips the codex `goals` feature on in
  `~/.codex/config.toml`.
- `telemetry.py` — CPU/memory/GPU/network sampling, pushed every 3s.

**`apps/web/`** (Vite + React, bundled into relay docker image)
- `src/hooks/useRemotex.js` — single big reducer + WebSocket
  attach/reconnect + REST fetch wrappers. State machine for the
  whole app.
- `src/screens/SessionScreen.jsx` — chat surface.
- `src/screens/DashboardScreen.jsx` — landing surface.
- `src/components/Composer.jsx` — chip row + textarea + send/stop.
- `src/components/Pickers.jsx` — model/effort/permissions
  chip components.
- `src/components/PendingPromptsPanel.jsx` — approval + user-input
  dialogs.
- `src/components/JumpPicker.jsx` — the folder picker (search /
  `/path` teleport / tree browse).
- `src/components/SettingsPanel.jsx` — the settings overlay (token
  storage preference lives here).
- **Anything that overlays the page must be rendered through
  `createPortal(node, document.body)`** — today that's `Toast.jsx`,
  `JumpPicker.jsx`, `SettingsPanel.jsx`, and the `Pickers.jsx`
  dropdowns. The rule
  `.dashboard-layout > * { position: relative }` in `styles.css`
  would otherwise kick a `position: fixed` overlay into a stray grid
  cell.

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

## Hard gate: inspect Codex source before daemon ↔ Codex work

The codex JSON-RPC app-server protocol is undocumented outside the
codex repo itself. The schema changes between minor versions
(e.g. 0.122 → 0.128 added `tool_search`, changed `RequestUserInput`
shape, etc). Your training data is almost certainly stale.

This gate applies to any implementation, diagnosis, or review involving the
daemon's Codex connection: JSON-RPC methods or notifications, session and
admin adapters, item translation, approvals, permissions, goals, thread
start/resume configuration, rollout history, or Codex process lifecycle.

**Before editing code**, clone Codex into `/tmp/codex`, or fast-forward the
existing checkout:

```bash
if test -d /tmp/codex/.git; then
  git -C /tmp/codex pull --ff-only
else
  git clone https://github.com/openai/codex /tmp/codex
fi
codex --version
```

If the refresh fails, do not delete or reset the checkout; report that it may
be stale and continue only with explicit source inspection plus a real binary
probe. Prefer source matching the installed Codex version when a matching tag
exists. The installed binary's observed behavior is decisive when it differs
from the current source checkout.

Then grep and read the relevant source yourself before deciding what to
change. Do not infer a wire shape from memory, existing Remotex fixtures, or
client types alone. The files you'll need most often:

| You're working on | Read |
|---|---|
| New JSON-RPC method or notification | `/tmp/codex/codex-rs/app-server-protocol/src/protocol/v2/` (the canonical wire definitions, split per area — `item.rs`, `model.rs`, `permissions.rs`, …) and `/tmp/codex/codex-rs/app-server-protocol/src/protocol/common.rs` (method name → enum mapping) |
| Item types codex emits | `/tmp/codex/codex-rs/app-server-protocol/src/protocol/v2/item.rs` (`enum ThreadItem`) + `/tmp/codex/codex-rs/protocol/src/items.rs` |
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
    "kind": "session-started" | "turn-started" | "turn-completed" |
            "item-started" | "item-delta" | "item-completed" |
            "approval-request" | "user-input-request" |
            "thread-status" | "token-usage" |
            "goal-snapshot" | "goal-updated" | "goal-cleared" |
            "slash-ack" | "collab-modes" |
            "history-begin" | "history-end",
    "data": { … kind-specific … },
    "ts": 1234567890.123
  }
}
```

`session-closed`, `host-telemetry`, `pending-prompts` and `replay-gap`
are **top-level frame types**, not session-event kinds — don't add them
to the `kind` switch.

The relay stamps a per-session `seq` on every frame it forwards, and
keeps the last 1000 in a replay buffer for reconnecting clients. When a
client's `last_seq` predates what the buffer still holds, the relay sends

```json
{ "type": "replay-gap", "session_id": "...", "missed_from": 41, "missed_to": 108 }
```

*before* the frames it replays, and every client renders a visible
"earlier events unavailable" marker there. `last_seq: 0` is not exempt —
a client asking for everything still only gets the tail once the buffer
has wrapped. `seq` restarts at 1 in every new relay process, so a
`last_seq` above the session's current seq is treated as a fresh attach
and clients store the last seq they *saw*, never a running maximum.

`pending-prompts` carries **queues**, not slots:

```json
{ "type": "pending-prompts", "session_id": "...", "approvals": [...], "user_inputs": [...] }
```

Both lists are oldest-first and each entry carries the relay's `order`.
Clients render the head of each and popping it reveals the next — a second
concurrent prompt must never overwrite or hide an earlier unanswered one,
and a claim the relay hands back after a failed forward returns to its
old slot rather than the tail. The relay's `order` is authoritative
whenever it is present; entries are deduped by `approval_id` / `call_id`.
That invariant is mirrored in `useRemotex.js`, `RemotexViewModel.kt`, and
`RemotexViewModel.swift`; if you change it in one, change it in all three.

A prompt with no `decisions` list falls back to the full set —
`accept`, `acceptForSession`, `decline`, `cancel` — in all three clients.

### Relay → daemon (session control)

The session-open frame is built in `hub.ensure_session_open_frame()` and
carries **only** these keys — model/effort/permissions ride on each
`turn-start` instead, not on session-open:

```json
{ "type": "session-open",  "session_id": "...", "resume_thread_id": "...", "cwd": "...", "kind": "codex" }
{ "type": "session-close", "session_id": "..." }
```

`resume_thread_id` and `cwd` are only present when the client passed
them to `POST /api/sessions`. Note the key is `resume_thread_id`, not
`thread_id`.

### Client → relay (input)

```json
{ "type": "turn-start", "input": "user prompt", "model": "...", "effort": "...", "permissions": "...", "approvalPolicy": "...", "collaborationMode": "plan", "images": [{"data": "base64", "mime": "image/png"}] }
{ "type": "turn-interrupt" }
{ "type": "approval-response", "approval_id": "appr_…", "decision": "accept"|"acceptForSession"|"decline"|"cancel" }
{ "type": "user-input-response", "call_id": "ui_…", "answers": { "<qid>": {"answers": ["..."]} } }
{ "type": "slash-command", "command": "plan"|"default"|"cd"|"pwd"|"compact"|"goal"|"collab", "args": "..." }
{ "type": "goal-set", "objective": "...", "status": "active", "token_budget": 500000 }
{ "type": "goal-get" }
{ "type": "goal-clear" }
{ "type": "session-close" }
{ "type": "ping", "ts": 123 }
```

Web routes `/goal` through `slash-command`; Android sends the `goal-*`
frames directly. The daemon handles both.

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
`/tmp/codex/codex-rs/app-server-protocol/src/protocol/v2/`.

---

## Common gotchas (one-line each)

- The relay's `.dashboard-layout > * { position: relative }` rule
  overrides any modal scrim's `position: fixed`. **Always portal
  modals to `document.body`**, never render them inline inside the
  dashboard layout.
- The Android debug APK's default relay URL is `10.0.2.2:8080`.
  Real devices need the LAN IP — use `android/build.sh`.
- Codex's `thread/resume` reply inlines the entire conversation as
  one JSON-RPC frame. Use `_read_line_unbounded` (in
  `adapters/stdio.py`) — `StreamReader.readline()`'s 64KB cap
  silently truncates and stranding the read task.
- The Postgres container is `remotex-postgres-1`. Don't confuse
  it with the unrelated `cdx-chat-postgres-1` running on the same
  docker daemon.
- `kind` is on the wire in `session-started.data.kind`. Both
  clients consume it; don't remove it without updating both.
- The relay **will not start without `RELAY_DATABASE_URL`** —
  `Store.start()` raises. Compose supplies it; running
  `python3 relay/app.py` from source does not.
- **`demo-user-token` no longer exists unless you ask for it.**
  Seeding is gated on `RELAY_SEED_DEMO` (truthy = `1`/`true`/`yes`),
  default off, because the tokens are public. A fresh relay with an
  empty database has zero users; every request 401s until you seed or
  insert a row. Never turn it on in Compose or in a doc example that
  isn't explicitly loopback-only.
- **Never log a raw token.** Audit lines take
  `user_hash=user_hash(token)` (`relay/logging.py`) with the *raw*
  presented token, so it equals the first 12 chars of the stored hash —
  the same identifier as a bridge key's `key_id`. Don't pass
  `user["token"]`; that column is already hashed and would give you a
  hash of a hash that joins to nothing.
- One ceiling governs every byte path: `REMOTEX_MAX_FILE_BYTES`
  (default 25 MiB). The relay's `client_max_size`, both relay WS
  `max_msg_size` values, and the daemon's `ws_connect` all derive from
  it. Oversize must be caught **before the write**: aiohttp closes the
  socket before the receiving handler ever sees an oversize frame, so
  the error frame the WS handlers attempt on `WSMsgType.ERROR` is
  best-effort only. The daemon measures every outbound frame in
  `DaemonClient._run_once`'s `send()` and substitutes an error payload;
  web and Android cap attachments/uploads client-side. Without that, one
  oversize frame kills the daemon socket and every session on that host.
- The daemon refuses to `run` against cleartext `ws://` to a
  non-loopback host unless `allow_insecure = true` is set in
  `~/.remotex/config.toml`.
- Relay→client errors that the client must not retry carry
  `"fatal": true` (bad/closed/foreign session, bad token). Web and
  Android stop their reconnect loop on it; without that the client
  reconnects forever against an id the relay will never accept.
- The relay does read `event.kind` and a few ids off session events
  (turn state, approvals, thread indexing) even though it never
  interprets `event.data`. Adding a new kind that affects turn
  lifecycle means touching `handlers/ws_daemon.py` too.
- `apps/web` is its own npm project. There is no root `package.json`,
  so `npm ci` at the repo root fails. Its tests are vitest in the
  `node` environment (no jsdom): pure helpers plus the `useRemotex`
  reducer, which is exported precisely so it can be driven headless.
  `npm run test:run` is what CI runs.
- CI runs `./gradlew assembleDebug`, `./gradlew test` and
  `./gradlew lint` for Android, and the iPhone job is gated on
  `apple/**` changes (it burns macOS minutes).

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
6. **Check the actual Postgres state** —
   `docker exec remotex-postgres-1 psql -U remotex -d remotex
   -c "SELECT id, kind, ... FROM inventory_sessions ORDER BY
   opened_at DESC LIMIT 5;"`.
7. **Inspect open codex processes**: `pstree -lp $(pgrep -f
   "remotex-daemon")`.
