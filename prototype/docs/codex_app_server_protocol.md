# Codex App Server — integration notes

This is the reference for how the daemon will talk to `codex app-server`
once `StdioCodexAdapter` replaces `MockCodexAdapter`. It's a practical
subset, not a spec — the authoritative source is the `codex-rs/app-server`
README in the official `openai/codex` repo.

## Transport

Two supported transports:

1. **stdio (default, stable).** `codex app-server` reads one JSON object
   per line on stdin and writes one per line on stdout. This is what the
   daemon should use — it avoids exposing the WS listener on the host,
   and the daemon is already local so there's no network involved.
2. **websocket (experimental).** `codex app-server --listen ws://HOST:PORT`
   accepts WebSocket clients. Useful for dev containers and for the
   `codex` TUI's `--remote` flag. **Do not rely on this remotely** — OpenAI
   explicitly marks it unstable and it doesn't solve NAT, which is the
   whole reason we have a daemon.

Both transports speak the same JSON-RPC-lite shape: requests have
`id` + `method` + `params`; responses carry `id` + `result` or `error`;
notifications have `method` + `params` with no `id`. The `"jsonrpc":"2.0"`
header is omitted.

## Lifecycle

```
client ──────────────▶ initialize {clientInfo}           (request)
       ◀────────────── result {serverInfo, capabilities}
       ──────────────▶ initialized {}                    (notification)
       ──────────────▶ thread/start {...}                (request)
       ◀────────────── result {threadId}
       ──────────────▶ turn/start {threadId, input, ...} (request)
       ◀────────────── item/started {...}                (notification)
       ◀────────────── item/agentMessage/delta {...}     (notification, repeated)
       ◀────────────── item/completed {...}              (notification)
       ◀────────────── turn/completed {...}              (notification)
       ──────────────▶ thread/close {threadId}
```

The server will reject any request other than `initialize` before the
handshake completes.

## Primitives

- **Item.** The atomic unit. Types the daemon cares about:
  `agent_message`, `agent_reasoning`, `tool_call` (or its
  command-specific variants), `file_change`, `approval_request`.
  Every item has `item_id`, `item_type`, and a lifecycle of
  `item/started` → zero or more `item/.../delta` → `item/completed`.
- **Turn.** A single pass of agent work. Bounded by `turn/started`
  and `turn/completed` notifications; contains a sequence of items.
- **Thread.** The durable container. Supports `thread/start`,
  `thread/resume`, `thread/fork`, `thread/close`. Codex persists
  thread history so a reconnecting client can reattach without
  losing state.

## Approvals (the bit you cannot skip)

When Codex wants to take an action that requires user consent (write
files, run a tool outside the sandbox), it sends a notification like:

```json
{"method":"item/fileChange/requestApproval",
 "params":{"approval_id":"appr_...", "item_id":"itm_...",
           "changes":[{"path":"src/auth/verify.ts","+":84,"-":12}]}}
```

The turn **pauses** until you respond:

```json
{"id":42, "method":"approval/resolve",
 "params":{"approval_id":"appr_...", "decision":"allow_once"}}
```

`decision` is one of `deny`, `allow_once`, `always`. If the user closes
the client before responding, Codex waits — nothing auto-resolves. This
is why the relay envelopes have a dedicated `approval` frame shape.

## Auth + sandbox

- Codex authenticates to OpenAI via `~/.codex/auth.json`. The daemon
  does not touch this file and should not be asked to.
- Sandbox mode is a per-`turn/start` or per-thread parameter:
  `read-only`, `workspace-write`, `trusted`. Pick a default in the
  daemon config; let the client override per turn.
- On Windows, the elevated sandbox requires admin setup and uses a
  private desktop for UI isolation. The unelevated fallback uses ACLs
  and a restricted token. Both interact with WSL via the `wsl` CLI.

## Generated type bindings

Rather than hand-typing the envelope shapes, the official binary can
emit schemas:

```bash
codex app-server generate-ts --out ./schemas         # TypeScript
codex app-server generate-json-schema --out ./schemas # JSON Schema
```

Feed the JSON Schema bundle into `datamodel-code-generator` (Python),
`quicktype` (anything), etc., and the client + daemon stay type-safe
across version bumps.

## Where the daemon maps the protocol

`daemon/adapters.py::StdioCodexAdapter` is the skeleton. It:

1. Spawns `codex app-server` with stdin/stdout pipes.
2. Performs the `initialize` / `initialized` handshake.
3. Routes relay frames onto JSON-RPC:
   - `turn-start`   → `turn/start`
   - `approval`     → `approval/resolve`
4. Translates inbound `item/*` notifications into `SessionEvent`
   objects the relay already knows how to forward.

The full mapping table lives inline in the adapter. The missing
production pieces are request/response correlation (today the skeleton
fires and forgets) and connection loss recovery via `thread/resume`.
