# ToDo — codex app-server protocol gaps

Findings from auditing `services/daemon/adapters/stdio.py` against **codex
0.147.0** (the version installed on this box). Ranked: bugs first, then
features. Each item lists the exact files to touch.

**Status:** all five are **done** (2026-08-09, see `WorkLog.md`), along with
every issue that was open in `Issues.md` except `I-011` (relay port
collision — needs a human decision) and `I-012` (iOS composer UI). What
remains here is the backlog at the bottom.

## How this was verified (do this again next codex bump)

The installed binary dumps its own protocol — don't grep training memory,
and don't trust the `/tmp/codex` clone (it's HEAD, ahead of what you run):

```bash
codex app-server generate-json-schema --out /tmp/codex-schema-$(codex --version | tr ' ' '-')
codex app-server generate-ts --out /tmp/codex-ts       # same thing as TS types
```

Caveat found the hard way: **the schema dump is incomplete.** It omits
`collaborationMode/list` and `thread/turns/list`, both of which work fine
live. So use the dump for *field shapes* and a live probe (AGENTS.md
"Minimal probe pattern") for *existence*. Probe reader must use
`_read_line_unbounded` — `skills/list` alone returns >64KB and kills a
plain `StreamReader.readline()`.

---

## 1. ~~Live command output is dropped~~ — DONE 2026-08-09

**Symptom:** clients show nothing while a command runs, then the whole
output appears at once on `item/completed`. Same for file edits.

**Cause:** `_dispatch()` matches deltas with
`method.startswith("item/") and method.endswith("/delta")`
(`services/daemon/adapters/stdio.py:1087`). Codex names these
`outputDelta`, not `/delta`, so they hit the `else: log.debug("ignored
codex notification")` branch at `stdio.py:1185`.

Unhandled, all confirmed present in 0.147 `ServerNotification`:

| method | params |
|---|---|
| `item/commandExecution/outputDelta` | `{threadId, turnId, itemId, delta}` |
| `item/fileChange/outputDelta` | same (marked deprecated upstream — server no longer emits it; skip) |
| `item/fileChange/patchUpdated` | `{threadId, turnId, itemId, ...patch}` |
| `item/commandExecution/terminalInteraction` | interactive TTY prompt |

**Do:** add one `elif` next to the existing `item/mcpToolCall/progress`
handler (`stdio.py:1077`) — it already emits exactly the envelope we
need:

```python
elif method in ("item/commandExecution/outputDelta",
                "item/fileChange/outputDelta"):
    await self._queue.put(SessionEvent("item-delta", {
        **_thread_extra(params),
        "turn_id": params.get("turnId"),
        "item_id": params.get("itemId"),
        "item_type": ("command_execution"
                      if "commandExecution" in method else "file_change"),
        "delta": params.get("delta", ""),
    }))
```

**Check the clients actually append it.** Web `handleFrame`
(`apps/web/src/hooks/useRemotex.js`) and Android
(`RemotexViewModel.handleFrame`) reduce `item-delta` by `item_type`;
confirm `command_execution` / `file_change` items concatenate deltas
rather than only rendering the `item-completed` payload. If they replace
instead of append, that's the second half of this fix.

**Test:** ask a session to run `for i in $(seq 5); do echo $i; sleep 1;
done` and watch output stream. Add a `_dispatch` unit test in
`services/tests/` with a captured frame.

**Restart:** `systemctl --user restart remotex-daemon` (+ relay rebuild if
you touch web).

---

## 2. ~~Two codex server requests kill the turn~~ — DONE 2026-08-09

`_reject_unsupported_server_request()` (`stdio.py:1189`) answers with
JSON-RPC -32601 **and** pushes `SessionEvent("turn-completed", {"error":
...})`. Any server request outside the three approval methods
(`stdio.py:1001`) therefore aborts the user's turn.

Reachable in 0.147 (`ServerRequest` union):

- `mcpServer/elicitation/request` — an MCP server asking the user for
  input. Fires for real MCP servers that use elicitation.
- `item/tool/call` — codex asking the *client* to run a tool.
- `account/chatgptAuthTokens/refresh`, `attestation/generate`,
  `openai/form` — auth/plumbing; a -32601 here is fine, but it should not
  fail the turn.

**Do (lazy fix, ~10 lines):** split the two behaviours.

1. Keep sending -32601, but drop the `turn-completed` error — emit an
   informational event instead (reuse `SessionEvent("thread-status", …)`
   or a `notice` kind the clients already log).
2. Route `mcpServer/elicitation/request` into the **existing**
   user-input flow. `_pending_user_inputs` + `user-input-request` +
   `_resolve_user_input` (`stdio.py:780`) already implement a
   question/answer dialog on all three clients. Map the elicitation's
   requested schema onto one `questions[]` entry and reply with the shape
   in `McpServerElicitationRequestResponse.json` from the schema dump.
3. Leave `item/tool/call` rejected — we expose no client-side tools.
   Just don't fail the turn.

**Test:** unit-test `_dispatch` with a synthetic
`mcpServer/elicitation/request` frame; assert a `user-input-request`
event and no `turn-completed`.

**Restart:** daemon only.

---

## 3. ~~Model list is hardcoded in 3 places and already stale~~ — DONE 2026-08-09

Live `model/list` on this host returns `gpt-5.6-sol` (plus
`displayName`, `description`, `modelSpecialty`, `upgradeInfo`). None of
our lists know it:

- `services/relay/models.py:20` — `MODEL_OPTIONS`, claims "Models visible
  in codex 0.122.0", served verbatim by
  `services/relay/handlers/models_route.py` at `GET /api/models`
- `apps/web/src/config.js:38` — `FALLBACK_MODEL_OPTIONS`
- `android/.../ui/RemotexViewModel.kt:187` — `ModelOption` list

The relay's docstring already says "never duplicate this list in a client
again" — the duplication is the fallback copies, which is fine. The real
problem is nobody asks codex.

**Do:** the model list is host-specific (depends on that host's codex +
account), so serve it per host, following the `thread/list` pattern that
already exists end to end:

1. `services/daemon/adapters/admin.py` — add
   `async def list_models(self): return await self._call("model/list", {})`
   next to `list_threads` (`admin.py:48`). One-liner; `AdminCodex` is
   already resident and has the unbounded reader.
2. `services/daemon/client.py` — add a `models-list-request` /
   `models-list-response` pair. Copy `_handle_threads_list`
   (`client.py:480`) and the dispatch line at `client.py:206`.
3. `services/relay/` — add `GET /api/hosts/{host_id}/models` mirroring
   `fs_h.list_host_fs` (registered at `services/relay/app.py:61`). Map
   codex's `{id, displayName, description}` onto our
   `{id, label, hint, efforts}`. Keep `MODEL_OPTIONS` as the offline-host
   fallback; keep `GET /api/models` working so old clients don't break.
4. Web `useRemotex` already fetches `/api/models` into
   `state.modelOptions` and `Pickers.jsx:10` already falls back — point
   the fetch at the host-scoped URL once a host is known.

**Note (was wrong, corrected on implementation):** codex *does* report
efforts per model — `supportedReasoningEfforts: [{reasoningEffort,
description}]`, plus `hidden`, `isDefault` and `displayName`. Newer models
support `max` and `ultra`, which the old global `ALL_EFFORTS` omitted, so
they were unselectable. Everything is derived from codex now; no model names
or effort lists are hardcoded in the relay or either client.

**Ceiling:** if `model/list` is slow on a cold admin codex, cache it per
host for the daemon's lifetime — same reasoning as the `AdminCodex`
docstring (`admin.py:26`).

**Restart:** daemon + relay rebuild.

---

## 4. ~~`serverRequest/resolved` — stale approval dialogs~~ — DONE 2026-08-09

`{threadId, requestId}` notification, fires when codex resolves a server
request *itself* — auto-approval rule matched, turn aborted, another
surface answered it.

**Good news: the client half already exists.** A client answering an
approval already dismisses everyone else's dialog:
`ws_client.py:322` broadcasts `approval-resolved`, consumed at
`useRemotex.js:555` and `RemotexViewModel.kt:1022`. Only the
*codex-initiated* case leaks a stale modal.

**Do:**

1. **Daemon** (`stdio.py`): handle `serverRequest/resolved`. Reverse-look
   the codex rpc id in `_pending_approvals` (values hold `rpc_id`,
   `stdio.py:1013`) and `_pending_user_inputs` (`stdio.py:990`), pop it,
   and emit `SessionEvent("approval-resolved", {"approval_id": …})` /
   `("user-input-resolved", {"call_id": …})`.
2. **Relay** (`services/relay/handlers/ws_daemon.py:97`): where it
   currently calls `hub.note_approval_request` on `approval-request`, add
   the inverse — on kind `approval-resolved`, call
   `hub.resolve_approval(sid, approval_id)` so the pending map stays
   honest, and re-emit the top-level
   `{"type": "approval-resolved", …}` frame the clients already handle.
3. **Clients:** nothing. Verify only.

**Test:** two browser tabs on one session, trigger an approval, set an
auto-approve rule / interrupt the turn, confirm both modals clear.

**Restart:** daemon + relay rebuild.

---

## 5. ~~`turn/steer` — message a running turn~~ — DONE 2026-08-09

Today the only mid-turn control is `turn-interrupt`
(`RemotexViewModel.kt:506`, `useRemotex.js:1128`) — you lose the turn and
retype. On a phone that's the main pain point.

Verified in 0.147 `ClientRequest`, `TurnSteerParams`:

```json
{"threadId": "...", "expectedTurnId": "...", "input": [...UserInput],
 "clientUserMessageId": null}
```

`expectedTurnId` is a required precondition — the call fails if it isn't
the active turn. We already track it as `self._turn_id`
(`stdio.py:1058`), and `input[]` is the same `UserInput` array
`turn-start` already builds (`stdio.py:287`).

**Do:**

1. **Daemon** (`stdio.py:255` `handle()`): new `ftype == "turn-steer"`
   branch. Reject with a `turn-completed`-free error event if
   `self._turn_id is None`. Reuse the `turn-start` input-building block
   (extract it to a helper — it also handles the base64 image temp files,
   `stdio.py:295`).
2. **Relay** (`services/relay/handlers/ws_client.py:240`): forward
   `turn-steer` **without** `hub.try_begin_turn()` — that guard exists to
   reject a second concurrent turn and would block steering. Do still
   broadcast the `item-started` / `user_message` echo (`ws_client.py:253`)
   so every attached client sees what was steered in, and stamp
   `client_id` / `client_message_id` the same way.
3. **Clients:** while a turn is in flight, make the composer send
   `turn-steer` instead of disabling send. Web:
   `apps/web/src/components/Composer.jsx` + a `sendSteer` on the socket
   wrapper next to `sendInterrupt`. Android: mirror in
   `RemotexViewModel.kt` next to the interrupt at line 499. Keep the Stop
   button.

**Test:** start a long turn, steer "also update the README", confirm
codex picks it up without restarting the turn, and that a second tab
sees the steered message.

**Restart:** daemon + relay rebuild + `cd android && ./build.sh install`.

---

## Backlog — available, not needed yet

Confirmed present in 0.147, currently ignored by `_dispatch`'s `else`
branch (`stdio.py:1185`) or simply never called. No action, just so
nobody re-discovers them:

- `turn/diff/updated` `{threadId, turnId, diff}` — free cumulative
  unified diff per turn. Cheapest path to a mobile "what changed" view;
  beats reassembling `fileChange` items.
- `thread/compacted` — we *send* `thread/compact/start`
  (`stdio.py:handle`) and ignore the completion notification.
- `account/rateLimits/read` + `account/rateLimits/updated` +
  `account/usage/read` — probed working: `usedPercent`, `resetsAt`,
  credits, streaks. Quota widget.
- `turn/plan/updated` `{threadId, turnId, explanation, plan[]}`;
  `item/plan/delta` already works via the generic `/delta` match.
- `model/rerouted`, `turn/moderationMetadata`,
  `model/safetyBuffering/updated` — explain to the user why output
  changed.
- `permissionProfile/list` — probed: `:read-only`, `:workspace`,
  `:danger-full-access`. Could replace the hand-rolled mapping in
  `adapters/permissions.py`.
- `fuzzyFileSearch/sessionStart|sessionUpdated|sessionStop` — @-file
  autocomplete in the composer.
- `thread/fork`, `thread/archive|unarchive|delete`,
  `thread/metadata/update`, `thread/name/set` — session management from
  the phone. (`thread/rollback` exists but is marked **DEPRECATED, will
  be removed** — don't build on it.)
- `command/exec` + `command/exec/{write,resize,terminate,outputDelta}`
  and the `process/*` family — a real terminal in the app, no ssh.
- `thread/realtime/*` — voice in/out, incl. `listVoices`, sdp, audio
  deltas.
- `skills/list`, `plugin/list`, `hook/started|completed` — surface skills
  as slash commands.
- `experimentalFeature/enablement/set` — the sanctioned replacement for
  `ensure_codex_goals_feature_enabled()` hand-editing `~/.codex/config.toml`
  (`adapters/codex_config.py`).

## ~~Shared local Codex daemon integration~~ — DONE 2026-08-09

Remotex now optionally uses `codex app-server daemon` through its local
WebSocket-over-Unix-socket control plane. One host connection multiplexes
threads, hot-resumes active terminal turns, and forwards local TUI prompts.
The portable isolated `stdio` mode remains the default. Codex's separate
`remoteControl/*` pairing/client-revocation family is not used: Remotex keeps
its own authenticated relay and only adopts the same-machine app-server
transport.

---

# Plan: tail-first transcripts, scroll-up backfill, hover prefetch (2026-08-09)

Owner-requested. Goal: clicking a saved chat shows the **last user + agent
turn instantly**, older turns load as you scroll up **without scroll jumps**,
and hovering (desktop) / pressing (phone) a chat row prefetches so open
feels instant — without hammering the codex app-server.

## Design facts that shape this (verified)

- `services/daemon/adapters/rollout.py:_load_rollout_history()` already
  parses the full transcript **from the rollout JSONL on disk** — codex is
  never involved. Prefetch and tail replay must use this path; the
  "don't overload codex" worry then disappears by construction. Caching
  only protects against re-parsing large JSONL, not against codex load.
- The relay forwards client frames it doesn't know to the daemon untouched
  (`ws_client.py` "Forward client-originated frames"), so the new
  `history-more` frame needs no relay routing work.
- `thread/list` already returns `preview` text per row — rows are fine;
  this plan is about the transcript body.
- Today `_replay_history()` ships ALL turns (≤500 items) as one burst of
  replayed session-events; the client renders them incrementally → the
  scroll-spam the owner sees.
- Hub replay deques are bounded (`maxlen`), but history bursts can evict
  *live* events from the reconnect buffer — phase 1 should mark replayed
  history events so the hub skips buffering them.

## Phase 1 — daemon: tail replay + paging — DONE 2026-08-09

`services/daemon/adapters/stdio.py`:

1. Adapter keeps the parsed turns list in memory (`self._history_turns`)
   after `_load_rollout_history` / resume — it already holds it briefly;
   just don't drop it.
2. `_replay_history(tail=2)`: emit `history-begin {turns: total, shown: N,
   has_more: bool}` + only the last 2 turns' items + `history-end`.
3. New client frame:
   `{type: "history-more", before: <turn_index>, limit: 10}` →
   `history-chunk-begin {before, count}` + replayed items (each tagged
   `replayed: true`, plus `turn_index`) + `history-chunk-end`.
   Broadcast like any session-event: all attached clients converge, and
   clients dedupe by item id anyway.
4. Fallback when no local rollout (fresh host, foreign thread):
   `thread/turns/list` with `sortDirection: "desc"` + `limit` — **probe
   cursor semantics against real codex first** (AGENTS.md ritual); cache
   the fetched turns in the same `self._history_turns`.
5. Tag replayed items `ephemeral: true`; relay `ws_daemon.py` skips
   putting ephemeral events into the reconnect replay deque (one `if`).

Tests: `test_stdio_dispatch.py` — tail emits exactly last-2-turns items;
`history-more` returns the right slice; out-of-range `before` is a no-op.

## Phase 2 — web: stable rendering — DONE 2026-08-09

`apps/web/src/hooks/useRemotex.js` + `EventStream.jsx`:

1. Buffer replayed events between `history-begin`/`history-end`; commit in
   ONE dispatch, then jump-scroll to bottom (no smooth), then reveal.
   Kills the replay scroll-spam.
2. Top sentinel (IntersectionObserver) mounts only after `history-end`
   +300ms and only while `has_more`; on hit → send `history-more`.
3. Prepend with scroll anchoring: record `scrollHeight`/`scrollTop`
   before commit, restore `scrollTop += delta` in `useLayoutEffect`;
   `overflow-anchor: none` on the scroller so the browser doesn't fight.
4. Dedupe prepends by item id (events already keyed by id).

## Phase 3 — hover/press prefetch + caches — DONE 2026-08-09

1. Daemon: new admin-style frame `thread-preview-request {thread_id,
   turns: 2}` → reads rollout from disk, returns compact
   `{title, turns: [{role, text≤600ch, ts}], available}`. **No codex.**
   LRU cache ~64 entries keyed `(thread_id, rollout mtime)` — mtime makes
   invalidation free.
2. Relay: `GET /api/hosts/{id}/threads/{tid}/preview` following the
   models/threads handler pattern (auth + `await_daemon_request`).
   No relay cache in v1 — the daemon answer is disk-cheap.
3. Web: prefetch on `mouseenter` with ~150 ms delay (drive-by guard) and
   on `pointerdown` (phone press = the opening tap; prefetch races the
   session-open). In-memory Map + sessionStorage cache, TTL ~5 min.
   SessionScreen paints the cached preview immediately, replaced by the
   real tail on `history-end`. In-flight dedupe so hovering 20 rows ≠ 20
   parallel requests (cap ~3 concurrent, drop stale).

## Phase 4 — Android/iOS (follow-up)

The wire protocol (tail + `history-more` + preview endpoint) is
client-agnostic by design. Android: same buffering in `RemotexViewModel`
(`history-begin/end` are currently no-op markers) + `LazyColumn`
`prependedItems` scroll anchoring; press-prefetch via the same REST
endpoint. iOS after Android.

## Order & size

1 → 2 ship together (that's the felt fix); 3 after; 4 later. Rough:
daemon ~150 lines + tests, web ~200, relay ~40, preview stack ~200.

## Risks

- Scroll anchoring is the fiddly part — test with a 300-turn fixture in
  the mock adapter rig (scratchpad `ui-shots.mjs` infra reusable).
- `thread/turns/list` cursor shape unverified → probe before coding the
  fallback.
- Multi-client: a second client attached mid-backfill receives chunks it
  didn't request — id-dedupe makes this benign; verify with two tabs.


---

# Plan: phone UI/IA — remaining work (2026-08-09)

Owner flagged the phone experience. Fixed same day (see WorkLog): header
tool buttons stretching into empty boxes, drawers leaving a 52px content
sliver (now full-width sheets ≤640px with a real scrim), workspace files
drawer (sorted dirs-first/dotlast, tappable files = download, icon
actions, tail-truncated path via `<bdi>`), composer squeezed from 3 rows
to 2 (plan/goal chips live in the scrollable chip row, min-width floor
makes the next chip peek).

What remains for a properly phone-first layout, in value order:

1. **Bottom sheets instead of side drawers** (≤640px). Telemetry, prompts
   and workspace files as swipe-dismissable bottom sheets — thumb
   reachable, native feel. CSS transform change + a drag handle; the
   components don't care.
2. **Sticky compact session header.** On scroll, collapse the two-line
   session header to one 36px line (host · model). Gives back ~40px.
3. **Composer focus mode.** When the textarea focuses on phone, hide the
   chip row until blur (chips are pre-turn settings; while typing you
   want space). Pure CSS `:focus-within` first pass.
4. **Approvals as action sheet.** The pending-prompt panel is desktop-ish;
   on phone it should be a bottom sheet with big Approve/Deny buttons in
   the thumb zone.
5. **Larger touch targets sweep.** Several controls sit at 32-34px; audit
   to ≥44px hit areas (padding, not visual size).
6. **PWA polish.** manifest exists; add display: standalone testing,
   safe-area-inset padding for the composer (iPhone home bar), and
   overscroll-behavior to stop pull-to-refresh killing the session view.

Non-goals: bottom tab bar (only 2 real destinations — dashboard and
session; the header already covers it), swipe-between-sessions.

---

# ~~Plan: native client parity — themes, telemetry, transcript rendering~~ — DONE 2026-08-09

Owner: "add themes to android and apple too, and telemetry properly, and
workspace file browser, time dividers, jump to latest, markdown +
syntax highlighted code, collapsible reasoning, read edit diffs, and
Claude-Code style tool rows — all where they aren't."

## Corrected inventory (recon, not assumption)

Android already HAS: markdown renderer with code fences (`ui/Markdown.kt`),
collapsible `▸ REASONING` / `▸ TOOL` rows with truncation
(`events/AgentGroup.kt`), workspace file browser with rename/delete
(`session/files/WorkspaceFilesPanel.kt`), and telemetry **polling into
state already** (`startTelemetryPoll` → `state.hostTelemetry`) with no UI.
Missing: light theme, syntax highlighting, edit diffs, Claude-Code tool
styling, time dividers, jump-to-latest, telemetry UI.

iOS has: plain-text `StreamRow` only. Missing everything on the list.

## Phases

1. **Android theme** — `LocalPalette` CompositionLocal + light/dark
   palettes. Trick that avoids touching 20 files: keep the existing
   top-level names (`Ink`, `InkDim`, `Amber`, `Line`, `Ok`, `Warn`,
   `AccentDeep`) but redefine them as `@Composable @ReadOnlyComposable get()`
   reading the palette. Toggle in the top bar, persisted in
   `remotex.settings` prefs alongside the relay URL.
2. **Android transcript** — new `Highlight.kt` (small Kotlin tokenizer:
   keywords/strings/numbers/comments → AnnotatedString spans, reused by
   markdown code fences), `DiffView.kt` (per-file +N/−M, tinted lines),
   Claude-Code tool rows (● dot + `name(arg)` + ⎿ output, tail-follow
   while running), `✳ Thinking/Thought` reasoning, time dividers +
   jump-to-latest FAB in `EventList.kt`.
3. **Android telemetry** — `TelemetryPanel.kt` reading the state that
   already exists: CPU/RAM/GPU/net cards + Canvas sparklines. Reachable
   from the session top bar.
4. **iOS theme** — dynamic `UIColor { traitCollection }` colors so light
   mode is automatic, plus an explicit override toggle; drop the
   hardcoded `.preferredColorScheme(.dark)`.
5. **iOS transcript** — `Markdown.swift` (block splitter + inline via
   `AttributedString(markdown:)` + the same highlighter port),
   `DiffView.swift`, Claude-Code tool rows, collapsible thinking, time
   dividers, jump-to-latest.
6. **iOS telemetry + file browser** — `TelemetryView.swift` (Path
   sparklines) and `WorkspaceFilesView.swift` (list + rename/delete/
   download via the existing fs endpoints).

Verification: Android compiles + unit tests locally; iOS via the macOS CI
job (no Xcode on this box) and the owner's sideload test.

## Outcome (2026-08-09)

All six phases shipped — see the `WorkLog.md` entry of the same date.
Android: `compileDebugKotlin` + 25 unit tests green locally. iOS: green in
the macOS Release job; the nightly IPA grew 396 KB → 595 KB.

**Two Swift traps the local brace-balance check could not catch**, both
found only by the CI compile — worth knowing before writing more SwiftUI
here:

- A local `func` whose body uses `return` cannot live inside a result
  builder closure (`GeometryReader { … }`). Hoist it to a method on the
  struct and call it for a precomputed value.
- `foregroundStyle` is generic over `ShapeStyle`, so implicit member
  lookup (`.remotexMuted`) does **not** see custom statics declared on
  `Color` — write `Color.remotexMuted`. `tint` takes `Color?`, so
  `.remotexAccent` is fine there. That asymmetry is why some call sites
  compiled and one did not.

Deferred, deliberately:

- iOS file rename / delete / download — the view is read-only. Shipping
  destructive fs actions on a client nobody has run yet is not a trade
  worth making; wire them after the owner's first sideload.
- Android image attachment (web-only today).
- True "2h ago" dividers on native. The daemon does send `ts`, but
  neither native model keeps it, so both clients use a
  history-vs-live boundary instead. Add `ts` to `UiEvent` /
  `StreamEvent` when someone wants real timestamps.

