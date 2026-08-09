# Codex App Server — integration notes

How `services/daemon/adapters/stdio.py` actually talks to
`codex app-server`. This documents the subset Remotex uses, as
implemented — not the full protocol.

> **The authoritative source is the codex repo itself, not this file.**
> The wire format changes between minor versions. Before adding a
> handler, clone it and read the real definitions:
>
> ```bash
> test -d /tmp/codex || git clone https://github.com/openai/codex /tmp/codex
> ```
>
> Canonical files: `codex-rs/app-server-protocol/src/protocol/v2.rs`
> (methods + `ThreadItem`), `.../protocol/common.rs` (method → enum),
> `codex-rs/protocol/src/{items,approvals,permissions,request_user_input}.rs`.
> Don't cite line numbers from here — re-grep.

## Transport

Remotex uses **stdio**. `codex app-server` reads one JSON object per line
on stdin and writes one per line on stdout. The daemon is already local,
so there's no network involved and no listener to expose.

Codex also has an experimental `--listen ws://…` transport. Remotex does
not use it: it's marked unstable and it doesn't solve NAT, which is the
entire reason the daemon exists.

The shape is JSON-RPC-lite: requests carry `id` + `method` + `params`;
responses carry `id` + `result` or `error`; notifications carry `method`
+ `params` with no `id`. The `"jsonrpc": "2.0"` header is omitted.

### Reading stdout

Do **not** use `StreamReader.readline()`. Codex's `thread/resume` reply
inlines the entire conversation as one line, which blows past asyncio's
64KB buffer limit and raises `ValueError`, killing the read task and
stranding every pending RPC. `stdio.py` uses `_read_line_unbounded()`,
which drains `LimitOverrunError` chunks with `readexactly()` until it
finds the newline.

## Lifecycle as the daemon drives it

```
daemon ─────▶ initialize {clientInfo, capabilities:{experimentalApi:true}}
       ◀───── result {userAgent, …}
       ─────▶ initialized {}                              (notification)

  ── new thread ──                     ── resume ──
       ─────▶ thread/start                 ─────▶ thread/resume
              {cwd, ephemeral, config}            {threadId, config}
       ◀───── result {thread:{id}, model, cwd}    (up to 600s; local
                                                   rollout is replayed
                                                   first so the user
                                                   sees the transcript
                                                   immediately)

       ─────▶ turn/start {threadId, input, cwd, summary, …}
       ◀───── turn/started            (notification)
       ◀───── item/started            (notification)
       ◀───── item/<type>/delta       (notification, repeated)
       ◀───── item/completed          (notification)
       ◀───── turn/completed          (notification)
```

The adapter's lifetime is one relay session = one Codex thread. It stays
resident between turns. It never sends `thread/close` — the process is
terminated on `stop()`.

`thread/start.config` currently carries `{"features.goals": true}`, since
codex 0.129 gates `thread/goal/*` behind an experimental feature that
defaults to false. Codex silently ignores unknown config keys, so verify
anything you add actually took effect.

## Methods the daemon sends

| Method | When |
|---|---|
| `initialize` / `initialized` | Once, on adapter start |
| `thread/start` | New session |
| `thread/resume` | Session opened with a `resume_thread_id` |
| `turn/start` | Every client `turn-start` frame |
| `turn/interrupt` | `turn-interrupt`. **Requires both `threadId` and `turnId`** |
| `thread/compact/start` | `/compact` |
| `thread/goal/{get,set,clear}` | `/goal` and the Android goal frames |
| `collaborationMode/list` | `/collab` |
| `thread/turns/list` | History replay fallback |

Plus JSON-RPC *responses* to server-initiated requests — see Approvals.

### The admin codex (`adapters/admin.py`)

Read-only host queries do **not** go through a session adapter. The daemon
keeps one extra long-lived `codex app-server` resident — cold-spawning
codex costs 2-5s, and overlapping spawns used to push the daemon past the
relay's HTTP timeouts — and drives it with the same handshake:

| Method | Serves |
|---|---|
| `thread/list` | `GET /api/hosts/{id}/threads` (25s call timeout) |
| `model/list` | `GET /api/hosts/{id}/models` |
| `fs/readDirectory` | `GET /api/hosts/{id}/fs` |

`model/list` params are `{cursor, limit, includeHidden}`, all sent
explicitly including nulls (`ModelListParams` in
`app-server-protocol/src/protocol/v2/model.rs`). The result is
`{data: [Model], nextCursor}`; each `Model` carries `model` (the slug
`turn/start` wants), `displayName`, `description`, `hidden`, and
`supportedReasoningEfforts` — a list of `{reasoningEffort, description}`.
`model_options_from_codex()` collapses that into the relay's
`{id, label, hint, efforts[]}` shape, keeps the empty-string "let codex
pick" sentinel at the head of both the model list and every effort list,
and gives that sentinel row the union of every effort any model accepts.
Hidden models are dropped. Anything that fails becomes a
`models-list-response {error}` and the relay falls back to its hostless
"let Codex decide" sentinel, so this path is never fatal and never names a
model it could not verify.

Any failed admin call tears the subprocess down so the next one respawns
rather than timing out against a wedged process. Its stderr is drained
continuously — nobody else reads that pipe, and at ~64KB codex blocks on
the write and stops servicing stdin.

### `turn/start` parameters

`{threadId, input, cwd, summary}` always. Optionally `model`, `effort`,
`sandboxPolicy`, `approvalPolicy`, `collaborationMode`.

`input` is a list of parts: `{type:"text", text, text_elements:[]}` and
`{type:"localImage", path}`. Image attachments arrive from the client as
base64, get written to temp files, and are unlinked on `turn/completed`.

`cwd` rides on **every** turn. Codex documents it as "override for this
turn and subsequent turns," so re-sending keeps the daemon's `/cd` state
and Codex in sync even on resumed threads.

`sandboxPolicy` / `approvalPolicy` are derived from the UI permission
chip by `adapters/permissions.py`:

| UI chip | `sandboxPolicy` | `approvalPolicy` |
|---|---|---|
| `full` / `full-access` | `{type: "dangerFullAccess"}` | `never` |
| `readonly` | `{type:"readOnly", access:{type:"fullAccess"}, networkAccess:false}` | `on-request` |
| anything else (default) | `{type:"workspaceWrite", writableRoots:[cwd], …}` | `on-request` |

A caller-supplied `approvalPolicy` is validated against
`never | on-request | on-failure | untrusted` (with snake/camel aliases);
an unrecognized value fails the turn rather than being silently dropped.

## Notifications the daemon consumes

| Method | Becomes |
|---|---|
| `turn/started` / `turn/completed` | `turn-started` / `turn-completed` |
| `item/started` / `item/completed` | `item-started` / `item-completed` |
| `item/<type>/delta` | `item-delta` |
| `item/reasoning/summaryTextDelta` | `item-delta` with `item_type: agent_reasoning` |
| `item/reasoning/summaryPartAdded` | `item-delta` carrying a `\n\n` block break |
| `item/mcpToolCall/progress` | `item-delta` with the progress message |
| `thread/status/changed` | `thread-status` |
| `thread/tokenUsage/updated` | `token-usage` (flattened from `tokenUsage.total`) |
| `thread/goal/updated` / `thread/goal/cleared` | `goal-updated` / `goal-cleared` |

Everything else (`mcpServer/*`, `account/*`, …) is logged at debug and
dropped.

### Subagent turns

Native Codex subagents emit their **own** `turn/started` and
`turn/completed` on the parent stream. Both handlers compare the incoming
`turn.id` against the live root turn and ignore mismatches — otherwise a
subagent finishing would end the user-visible turn early. Item events
still flow through so subagent progress renders.

## Approvals — server-initiated *requests*, not notifications

Codex asks for consent by sending a JSON-RPC **request** (it has an `id`
and blocks until you answer):

```json
{"id": 17,
 "method": "item/fileChange/requestApproval",
 "params": {"threadId": "...", "turnId": "...", "itemId": "...",
            "reason": "..."}}
```

Three methods are handled: `item/commandExecution/requestApproval`,
`item/fileChange/requestApproval`, `item/permissions/requestApproval`.
The adapter mints its own `approval_id`, stashes the rpc `id`, and emits
an `approval-request` session-event. When the client answers, it replies
to the stored rpc id:

```json
{"id": 17, "result": {"decision": "accept"}}
```

`decision` is one of `accept | acceptForSession | decline | cancel`.
The `permissions` variant takes a different result body:

```json
{"id": 17, "result": {"permissions": {...}, "scope": "session" | "turn"}}
```

where `permissions` is echoed back from the request when accepted, and
`{}` when declined.

The turn stays paused until you respond — nothing auto-resolves. Any
server request the adapter doesn't recognize gets an explicit `-32601`
error response plus a `turn-completed {error}`, so an unhandled prompt
surfaces instead of hanging the turn forever.

## User-input prompts

`item/tool/requestUserInput` is also a request. Params:

```json
{"callId": "...", "turnId": "...",
 "questions": [{"id": "...", "header": "...", "question": "...",
                "isOther": false, "isSecret": false,
                "options": [{"label": "...", "description": "..."}]}]}
```

Answer by replying to the rpc id with:

```json
{"answers": {"<question_id>": {"answers": ["<string>"]}}}
```

The adapter tolerates either `{qid: [str]}` or `{qid: {answers: [str]}}`
from the client, since web and Android build it slightly differently.

## Collaboration mode (`/plan`, `/default`)

`/plan` and `/default` set a pending mode; the *next* `turn/start` carries:

```json
{"mode": "plan",
 "settings": {"model": "<current model>",
              "reasoning_effort": "medium",
              "developer_instructions": null}}
```

`developer_instructions: null` asks app-server to fill in the built-in
instructions for that mode. Plan mode pins effort to `medium`. If Codex
never reported a current model, the turn fails with a clear error rather
than sending an incomplete payload.

## Auth + sandbox

- Codex authenticates to OpenAI via `~/.codex/auth.json`. The daemon does
  not touch this file and must not be asked to.
- Sandbox mode is a per-`turn/start` parameter — see the permissions
  table above.

## Generated type bindings

The official binary can emit schemas, which beats hand-typing envelopes:

```bash
codex app-server generate-ts --out ./schemas          # TypeScript
codex app-server generate-json-schema --out ./schemas # JSON Schema
```

## Probe before you implement

For anything codex-dependent, drive a real `codex app-server` manually
and read what it actually emits before writing adapter code. The probe
pattern lives in the repo root `AGENTS.md`.
