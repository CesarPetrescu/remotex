# Issues

Known problems we are **not** fixing right now. The point is that nothing
gets silently forgotten: if you notice something and walk past it, file it
here instead of leaving it in a session nobody else can read.

Not a bug tracker for user reports — this is agent-to-agent. Work you
*intend* to do goes in `ToDo.md`; what you *did* goes in `WorkLog.md`.

## Rules

- IDs are sequential and permanent: `I-001`, `I-002`, … Never renumber,
  never reuse. Next free ID: **I-022**.
- Add new issues to the bottom of the table and the bottom of the details
  section.
- **Status:** `open` · `investigating` · `fixed` · `wontfix` · `invalid` ·
  `blocked`
- **Severity:** `high` (data loss, broken flow, wrong output) · `medium`
  (degraded UX, stale data) · `low` (cosmetic, papercut) · `info`
  (knowledge worth keeping, not a defect)
- Closing one: set status, add a **Resolution** line with the date and the
  `WorkLog.md` entry that did it. Leave the entry in place.
- Every issue needs **Evidence** — a command, a log line, a `file:line`.
  "I think X is wrong" without evidence is a guess; mark it
  `investigating` and say so.

| ID | Status | Sev | Area | Summary |
|---|---|---|---|---|
| I-001 | fixed | medium | daemon | `item/fileChange/patchUpdated` and `item/commandExecution/terminalInteraction` are dropped |
| I-002 | fixed | medium | relay/clients | Model list hardcoded in 3 places and already stale |
| I-003 | fixed | medium | daemon/relay | `serverRequest/resolved` unhandled → stale approval modals |
| I-004 | fixed | low | web/android | No `turn/steer`; mid-turn input requires interrupt + retype |
| I-005 | invalid | low | daemon | First chunk of command output never arrives as a delta — upstream codex, not us |
| I-006 | fixed | medium | web/android | `file_change` items render as a bare system row, no diff |
| I-007 | info | info | tooling | `generate-json-schema` output is incomplete — probe to confirm existence |
| I-008 | fixed | medium | env | `remotex-daemon` systemd unit not installed on this dev box |
| I-009 | fixed | low | daemon | `thread/compacted` ignored after we send `thread/compact/start` |
| I-010 | fixed | low | daemon | Elicitation multi-select / nested object fields not mapped |
| I-011 | fixed | high | env | Relay port 18080 is held by an unrelated project; no remotex relay is running |
| I-012 | open | low | apple | iOS client has approval UI, but still lacks steer / interrupt and progressive item-patch handling |
| I-013 | fixed | high | process | Local `main` was 3 commits / +7830 lines behind `origin/main`; a whole session was built on a stale base |
| I-014 | blocked | medium | deploy/security | SparkTunnel 0.2.0 supplies no trustworthy visitor IP for per-address limits |
| I-015 | wontfix | info | daemon/upstream | Codex external-clock mode rejects multi-subscriber shared threads |
| I-016 | fixed | low | web | Dead `closeRightView` kept ESLint noisy |
| I-017 | open | info | daemon | GPU telemetry is NVIDIA-only; Intel/AMD accelerators are not sampled |
| I-018 | open | medium | process | `git add -A` in this shared tree commits other agents' in-flight work |
| I-019 | open | medium | codex/upstream | Codex 0.147 `thread/delete` fails against its migrated state database |
| I-020 | open | medium | web/tooling | Dependabot reports high-severity vulnerabilities in web build/dev dependencies |
| I-021 | open | low | daemon/deploy | Daemon reaches its own machine's relay via the public tunnel (the reconnect storm was relay restarts) |

---

## I-001 — `patchUpdated` and `terminalInteraction` dropped

**Status:** fixed · **Sev:** medium · **Area:** daemon · **Opened:** 2026-08-09
**Resolution:** 2026-08-09 — both handled in `_dispatch`; see `WorkLog.md`.

- **Symptom:** live file-edit progress and interactive-TTY prompts from
  codex never reach clients.
- **Why:** both are `ServerNotification`s in codex 0.147 with no branch in
  `_dispatch`, so they hit the `else: log.debug("ignored codex
  notification")` at `services/daemon/adapters/stdio.py:1185`.
- **Fixed by:** `patchUpdated` → a new `item-patch` event carrying
  `{item_id, output, args.command, changes}`; it *replaces* the item's diff
  because codex resends the whole patch each time (appending would
  duplicate). `terminalInteraction` turned out to be
  `{threadId, turnId, itemId, processId, stdin}` — codex echoing what it
  wrote to an interactive process — so we append `stdin` to the tool output
  as a delta. Web, Android and iOS all handle `item-patch`.
- **Observed:** codex 0.147 usually re-sends the whole `fileChange` item
  rather than emitting `patchUpdated`; the handler is there for when it does.
- **Evidence:** `ServerNotification.json` from
  `codex app-server generate-json-schema`; both absent from the method
  grep of `services/daemon/adapters/*.py`.
- **Note:** `item/fileChange/outputDelta` is deliberately **not** handled —
  codex 0.147 marks it deprecated and no longer emits it.

## I-002 — model list hardcoded in three places, already stale

**Status:** fixed · **Sev:** medium · **Area:** relay/clients · **Opened:** 2026-08-09
**Resolution:** 2026-08-09 — models *and* reasoning efforts are now fetched
per host from codex; no model name is hardcoded anywhere.

- **Symptom:** the picker can't offer models the host actually has. Live
  `model/list` on this box returns `gpt-5.6-sol`; none of our lists know it.
- **Why:** nobody asks codex. `services/relay/models.py:20` is a constant
  whose comment still says "codex 0.122.0", mirrored as fallbacks in
  `apps/web/src/config.js:38` and
  `android/.../ui/RemotexViewModel.kt:187`.
- **Fixed by:** `AdminCodex.list_models()` → `models-list-request` frame →
  `GET /api/hosts/{host_id}/models`, mapping codex's
  `{id, displayName, description, hidden, isDefault,
  supportedReasoningEfforts}`. Both clients fetch it when a host is
  selected. The hardcoded lists in `relay/models.py`, `web/config.js` and
  `RemotexViewModel.kt` are gone — all that's left is the `id: ""`
  "let codex decide" entry, which needs no model name.
- **Bonus find:** efforts are **per model** in codex
  (`supportedReasoningEfforts`), and newer models support `max` and
  `ultra`, which the old global `ALL_EFFORTS` omitted — they were
  unselectable. Now derived, including the union across a host's models
  for the "default" entry.
- **Evidence:** live probe of `model/list`; the three `grep -rn "gpt-5"`
  hits.

## I-003 — `serverRequest/resolved` unhandled → stale approval modals

**Status:** fixed · **Sev:** medium · **Area:** daemon/relay · **Opened:** 2026-08-09
**Resolution:** 2026-08-09 — daemon retracts, relay re-broadcasts.

- **Symptom:** when *codex* resolves an approval itself (auto-approval rule
  matched, turn aborted), attached clients keep showing the modal. Answering
  it then errors with "approval already resolved".
- **Why:** we handle the client-answered case only —
  `services/relay/handlers/ws_client.py:322` broadcasts `approval-resolved`,
  consumed at `useRemotex.js:555` / `RemotexViewModel.kt:1022`. The
  codex-initiated notification `{threadId, requestId}` has no branch.
- **Fixed by:** `_retract_server_request()` reverse-looks codex's rpc id
  across `_pending_approvals`, `_pending_user_inputs` and
  `_pending_elicitations`, pops it, and emits `approval-resolved` /
  `user-input-resolved`. `ws_daemon.py` clears the hub's pending map and
  re-emits the top-level frame both clients already consume. Nothing is
  sent back to codex — the request is already resolved on its side.
- **Evidence:** `ServerRequestResolvedNotification` in the 0.147 schema; no
  match for `serverRequest` under `services/`.

## I-004 — no `turn/steer`

**Status:** fixed · **Sev:** low · **Area:** web/android · **Opened:** 2026-08-09
**Resolution:** 2026-08-09 — implemented on daemon, relay, web and Android.
iOS has no send-during-turn UI at all; tracked separately as `I-012`.

- **Symptom:** to add a thought mid-turn you must interrupt and retype the
  whole prompt. Worst on mobile, which is the product's whole point.
- **Why:** never implemented. Also `hub.try_begin_turn()`
  (`ws_client.py:240`) rejects any turn frame while one is running, so a
  steer frame needs its own path.
- **Fixed by:** a `turn-steer` client frame → `_steer_turn()` →
  `turn/steer` with `expectedTurnId = self._turn_id`. The relay forwards it
  **without** `try_begin_turn()` and echoes a `user_message` item so every
  attached client sees what was steered in. Failures surface as
  `steer-failed` and explicitly do **not** end the turn. In both clients the
  composer stays live during a turn: type and the send button steers.
- **Verified live** against codex 0.147: a wrong `expectedTurnId` is
  rejected with `-32600 expected active turn id …`, and a correct one
  returns `{turnId}` and reaches the model mid-turn (it abandoned its
  counting task and obeyed the steered instruction).
- **Evidence:** `ClientRequest.json` → `turn/steer` present in 0.147; live
  probe returned `Invalid request: missing field threadId`, i.e. the method
  exists.

## I-005 — first chunk of command output not delivered as a delta

**Status:** invalid · **Sev:** low · **Area:** daemon · **Opened:** 2026-08-09
**Resolution:** 2026-08-09 — not our bug. Codex itself never reports the
first line: `item/started` carries `aggregatedOutput: null` and
`item/completed` carries only `"line2\nline3\n"` for a loop that printed
`line1..line3`. Our deltas match codex's own final output exactly, so
there is nothing to seed from. Likely swallowed during
`unifiedExecStartup` PTY setup upstream.

- **Symptom:** probing a 5-line shell loop, codex sent `outputDelta` for
  `line2`…`line5` but never `line1`. Clients stream from the second chunk.
- **Why:** unknown — probably batched into the `item/started` payload or
  emitted before the item is announced. Not investigated.
- **Impact:** self-correcting. `item/completed` carries the full aggregated
  output and both clients replace `output` with it
  (`useRemotex.js:632`), so the final render is right; only the live tail
  is missing a line.
- **How to fix:** re-run the probe printing full `item/started` params for
  `commandExecution`; if `line1` is in there, seed `output` from it.
- **Evidence:** `/tmp/claude-*/scratchpad/probe_outputdelta.py` output,
  2026-08-09.

## I-006 — `file_change` items render as a bare system row

**Status:** fixed · **Sev:** medium · **Area:** daemon · **Opened:** 2026-08-09
**Resolution:** 2026-08-09 — fixed in the daemon, so all three clients got
it at once.

- **Symptom:** when codex edits files, the transcript shows a generic row
  labelled `file_change` with no diff, no file list, nothing.
- **Why:** the daemon does send it — `items.py:65` flattens
  `changes` onto the event — but `buildItemEvent`
  (`apps/web/src/hooks/useRemotex.js:1346`) has no `file_change` case and
  falls through to `default: role: 'system', label: data.item_type`.
  Android has no `file_change` UI event either (the only match in the whole
  client is an approval-kind comment at `RemotexViewModel.kt:73`).
- **Fixed by:** `fileChange` now maps to `tool_call` in `_ITEM_TYPE_MAP`,
  and `_item_extras` flattens `changes` into the `tool` / `args.command` /
  `output` fields the tool renderer already draws — collapsible, copyable,
  truncated, with a "add /path" / "update /path → /moved" summary line.
  Formatting once in the daemon fixed web, Android **and** iOS with no
  client changes; each client had its own missing case otherwise.
- **Still open as an enhancement:** `turn/diff/updated` gives the whole
  cumulative turn diff in one string — a nicer "what changed" view than
  per-item diffs. Left in the `ToDo.md` backlog.
- **Evidence:** `grep -rn "file_change" apps/web/src/` → no hits.

## I-007 — `generate-json-schema` output is incomplete

**Status:** info · **Sev:** info · **Area:** tooling · **Opened:** 2026-08-09

- **What:** `codex app-server generate-json-schema --out DIR` on the
  installed binary is the best source for **field shapes**, but it is not a
  complete method list. `collaborationMode/list` and `thread/turns/list`
  are absent from the dump yet both work live — and Remotex calls both
  (`stdio.py:556`, `stdio.py:813`).
- **Why it matters:** an agent who only greps the dump will "discover" that
  working code calls nonexistent methods and delete it. Confirm existence
  with a live probe before removing anything.
- **Also:** any probe must use an unbounded line reader. `skills/list`
  alone returns >64KB and kills a plain `StreamReader.readline()` — the
  same trap `_read_line_unbounded` (`stdio.py:36`) exists for.

## I-008 — `remotex-daemon` systemd unit not installed on this dev box

**Status:** fixed · **Sev:** medium · **Area:** env · **Opened:** 2026-08-09
**Resolution:** 2026-08-09 — `deploy/install-daemon.sh --non-interactive`
rendered and started the unit. `~/.remotex/config.toml` already existed, so
no relay URL or bridge token had to be supplied. `systemctl --user is-active
remotex-daemon` → `active`. It cannot reach a relay yet — see `I-011`.

- **Symptom:** `systemctl --user restart remotex-daemon` →
  `Unit remotex-daemon.service not found`. `~/.config/systemd/user/` has
  no remotex unit and no daemon process is running.
- **Impact:** the restart ritual in `CLAUDE.md` can't be exercised here, so
  daemon changes get unit-tested and probe-verified but never run in place.
  Anything relying on live daemon behaviour is unverified on this machine.
- **How to fix:** install the unit (see `services/README.md` /
  `deploy/`), or accept it and make the runtime check part of a real host's
  workflow.
- **Evidence:** `systemctl --user list-units --all | grep -i remotex` →
  empty, 2026-08-09.

## I-009 — `thread/compacted` ignored

**Status:** fixed · **Sev:** low · **Area:** daemon · **Opened:** 2026-08-09
**Resolution:** 2026-08-09 — emits `slash-ack{command: "compact"}`, which
both clients already render.

- **Symptom:** `/compact` gives no completion signal — we fire
  `thread/compact/start` and never tell the client it finished.
- **Why:** no `thread/compacted` branch in `_dispatch`.
- **Fixed by:** one `elif` → `slash-ack`. Note codex 0.147 marks the
  notification deprecated in favour of a `contextCompaction` **item**, which
  we forward anyway; the handler covers older hosts. Probing `/compact`
  produced only the item, no notification.
- **Related:** `contextCompaction` is one of ten item types no client had a
  case for (also `webSearch`, `plan`, `sleep`, `subAgentActivity`,
  `imageGeneration`, `imageView`, `enteredReviewMode`, `exitedReviewMode`,
  `hookPrompt`). They rendered as raw camelCase labels; both clients now run
  the type through a `humanizeItemType` helper.
- **Evidence:** `thread/compacted` in the 0.147 `ServerNotification` union;
  no match under `services/`.

## I-010 — elicitation multi-select / nested fields not mapped

**Status:** fixed · **Sev:** low · **Area:** daemon · **Opened:** 2026-08-09
**Resolution:** 2026-08-09 — every enum shape codex can send is now mapped.

- **Symptom:** an MCP server asking for a multi-select
  (`items.anyOf` / `items.enum`) or a nested object gets a free-text box
  instead of a proper picker.
- **Why:** deliberate ceiling in `_options()`
  (`services/daemon/adapters/elicitation.py`) — marked with a `ponytail:`
  comment. Single-select (`oneOf[{const,title}]`, `enum`, `boolean`) is
  mapped; the rest degrade to text, which still round-trips as a string.
- **Fixed by:** `_options()` now reads `items.anyOf[{const,title}]` and
  `items.enum` (+ `enumNames`) as well as `oneOf`, `enum` and `boolean`, so
  a multi-select field shows the right choices instead of a free-text box.
  `_coerce()` returns an **array** for those fields — detected via `items`,
  because codex 0.147's multi-select schemas carry no `type` key at all,
  which would otherwise have sent a bare string to the MCP server.
- **Remaining ceiling:** the dialog is single-pick, so the user selects one
  value from a multi-select set (returned as a one-element array). A real
  multi-pick dialog is client work; nested objects still degrade to text.
- **Evidence:** `WorkLog.md` 2026-08-09; the elicitation definitions in
  `McpServerElicitationRequestParams.json`.

## I-011 — relay port 18080 is taken by an unrelated project

**Status:** fixed · **Sev:** high · **Area:** env · **Opened:** 2026-08-09
**Resolution:** 2026-08-09 — solved by *removing* the host port rather than
moving it. The SparkTunnel overlay (`deploy/docker-compose.sparktunnel.yml`,
`ports: !reset []`) publishes nothing on the host and reaches `relay:8080`
over the private Compose network, so the `gospod-nginx-1` listener on 18080
is irrelevant. `deploy/.env` chains that overlay via `COMPOSE_FILE` and no
longer sets `RELAY_HOST_PORT`. This applies the same dial-out inversion the
daemon already used. See the WorkLog entry "deploy the private relay and
verify its public boundary".

- **Symptom:** the daemon starts, then loops on
  `WSServerHandshakeError: 421, message='Invalid response status',
  url='ws://127.0.0.1:18080/ws/daemon'`. Nothing works end to end.
- **Why:** two things at once. (1) **No remotex relay is running** —
  `docker ps` lists no `remotex-*` container. (2) `127.0.0.1:18080`, the
  port `~/.remotex/config.toml` and `deploy/.env` (`RELAY_HOST_PORT=18080`)
  both point at, is bound by **`gospod-nginx-1`**, an unrelated project on
  the same docker daemon. The 421 is that nginx refusing a host it doesn't
  serve. Starting `remotex-relay-1` as configured would fail to bind.
- **How to fix — needs a human decision**, which is why this is filed
  rather than done:
  1. Move remotex off the port: set a free `RELAY_HOST_PORT` in
     `deploy/.env`, update `relay_url` in `~/.remotex/config.toml`, and
     rebuild the Android APK (`android/build.sh` bakes the URL in), **or**
  2. free 18080 by reconfiguring `gospod-nginx-1` — but `CLAUDE.md`
     explicitly forbids touching non-`remotex-*` containers, so that is the
     owner's call.
- **Blocks:** any end-to-end verification of relay or client changes on this
  box. The relay image builds fine (`docker compose build relay` →
  `remotex/relay:local`); only bringing it up is blocked.
- **Evidence:** `journalctl --user -u remotex-daemon` (2026-08-09 12:56),
  `ss -ltnp | grep 18080`, `docker ps`.
- **Deployment update:** the chosen SparkTunnel Compose override publishes no
  relay host port, so the unrelated 18080 listener no longer blocks that
  deployment path. The issue remained open until `~/.remotex/config.toml` was
  changed from the stale local URL to the public WSS relay and the installed
  daemon reconnected with a newly issued bridge key.
- **Both closing conditions are now met (verified 2026-08-09 ~15:00):**
  `~/.remotex/config.toml` uses the configured public WSS hostname (kept
  outside Git), and the daemon journal shows it attached after
  the restart that followed the last 18080 failure. `docker ps` lists
  `remotex-relay-1` (healthy), `remotex-sparktunnel-1` and
  `remotex-postgres-1` (healthy), with the relay exposing only `8080/tcp`
  internally — no host publish.
- **Unblocks:** end-to-end verification on this box, which every relay and
  client change this session lacked. Those changes are still only
  compile-and-unit verified; someone should now drive a real turn through a
  client and confirm streaming output, steering, and the model picker
  against a live host.

## I-012 — iOS client still lacks steer / interrupt and progressive item patches

**Status:** open · **Sev:** low · **Area:** apple · **Opened:** 2026-08-09

- **Symptom:** the SwiftUI client can start turns and answer ordered approval
  and user-input prompts, but it still cannot stop or steer a running turn.
  It also has no `item-patch` reducer branch, so progressive file-change
  patches only become visible when the completed item arrives.
- **Why:** `SessionSocket` implements `sendTurn`, `sendApproval`, and
  `sendUserInput`, but no `turn-interrupt` or `turn-steer`; the session UI has
  no send/stop/steer state machine. `RemotexViewModel.handleSessionEvent`
  handles item start/delta/completion but not `item-patch`.
- **Impact:** web and Android have the complete active-turn controls. iOS is
  usable for normal turns and prompts, but is a less capable deployment
  client and does not reconnect automatically after a socket loss.
- **How to fix:** add interrupt/steer send methods and a three-state composer,
  then port the tested `item-patch` reducer from the separate `ios-xctest`
  branch. Do not assume that branch's source change is already on `main`.
- **Evidence:** `apple/Remotex/SessionSocket.swift`,
  `apple/Remotex/RemotexViewModel.swift`; `item-patch` exists only in the
  unmerged `origin/ios-xctest` history as of 2026-08-09.

## I-013 — work was built on a stale local `main`

**Status:** fixed · **Sev:** high · **Area:** process · **Opened:** 2026-08-09
**Resolution:** 2026-08-09 — the full local feature/deployment commit was
cherry-picked onto `origin/main`, every conflict was reconciled by subsystem,
the combined reconnect/security races found in review were fixed, and the
complete validation matrix passed; see the 2026-08-09 reconciliation entry in
`WorkLog.md`.

- **Symptom:** local `main` sat at `2e5136d` while `origin/main` was at
  `03e3192` — **3 commits, 97 files, +7830 lines** ahead, including
  `fix: remediate 24 reported issues across relay, daemon, and clients`.
  An entire session of work (I-001…I-010) was written against the old tree.
- **How it surfaced:** the `ios-xctest` PR was created `CONFLICTING`, and
  GitHub **silently does not run `pull_request` workflows when it can't
  compute a merge commit** — CI never queued, with no error anywhere. The
  branch looked pushed and fine.
- **Overlap found:** `origin/main` independently rewrote
  `apple/Remotex/RemotexViewModel.swift` (+267, structured
  `pendingApprovals` replacing the old system-row approach, new
  `PendingPromptsView.swift`, `Keychain.swift`), and also touched
  `services/relay/models.py`, `services/tests/test_models_endpoint.py`,
  `test_hub.py`, `test_rate_limit.py` — **all files this session edited.**
- **Resolved for the iOS branch only:** cherry-picked onto `origin/main` in
  a throwaway worktree (so the 38 uncommitted files in the main tree were
  never at risk), unioned the pbxproj conflict, and rewrote the approval
  test against the new `pendingApprovals` API.
- **Reconciled:** daemon, relay, web, Android, Apple compatibility, docs, and
  deployment changes now sit on the hardened `origin/main` base. Conflict
  resolution retained upstream ownership checks, prompt queues, replay and
  frame limits, token storage, and Android close-handshake behavior alongside
  the local Codex bridge, steering, host-model, and optional SparkTunnel work.
- **Prevention:** `git fetch && git status -sb` before starting, and treat
  a `CONFLICTING` PR as "CI cannot run", not "CI is slow".
- **Evidence:** `git log --oneline origin/main`,
  `git diff --stat main origin/main`, `gh pr view 16 --json mergeable`.

## I-014 — SparkTunnel has no trustworthy visitor IP

**Status:** blocked · **Sev:** medium · **Area:** deploy/security · **Opened:** 2026-08-09

- **Symptom:** the safe SparkTunnel deployment keys REST and WebSocket
  connection limits on the connector's TCP peer, so every public visitor
  shares one quota and one noisy caller can throttle the others.
- **Cause:** SparkTunnel 0.2.0 supplies no sanitized visitor-IP header and
  preserves caller-supplied `X-Forwarded-For` and `X-Real-IP`; trusting those
  values would let an attacker rotate identities and evade per-address limits.
- **Current mitigation:** `deploy/docker-compose.sparktunnel.yml` forces
  `RELAY_TRUST_PROXY=0`, even if `.env` requests `1`. Bearer authentication
  remains mandatory, and spoofed forwarded-IP rotation stays in the shared
  connector-peer bucket.
- **How to unblock:** PhotonSpark must document and supply a sanitized header
  after stripping caller input, or enforce equivalent rate limits at its edge.
- **Evidence:** packet capture of requests sent through the deployed connector;
  `services/tests/test_rate_limit.py::test_spoofed_forwarded_ips_share_quota_when_proxy_is_untrusted`;
  `WorkLog.md` entry "make SparkTunnel rate-limit identity spoof-safe".

## I-015 — Codex external-clock mode requires exactly one subscriber

**Status:** wontfix · **Sev:** info · **Area:** daemon/upstream · **Opened:** 2026-08-09

- **Scope:** only Codex's under-development `current_time_reminder` with an
  explicitly configured external clock. The default system clock and normal
  shared Remotex/TUI sessions are unaffected.
- **Why:** upstream app-server checks for exactly one subscribed connection
  before asking a client for external time. Shared mode intentionally creates
  two subscribers while a terminal TUI and Remotex view the same thread.
- **Decision:** do not weaken shared fan-out or special-case an upstream,
  disabled-by-default experimental provider. Revisit if Codex makes external
  time a supported multi-client feature.
- **Evidence:** matching Codex `rust-v0.144.3` and current HEAD source in
  `codex-rs/app-server/src/current_time.rs`; shared transport review recorded
  in the 2026-08-09 WorkLog entries.

## I-016 — dead `closeRightView` keeps web lint noisy

**Status:** fixed · **Sev:** low · **Area:** web · **Opened:** 2026-08-09 · **Fixed:** 2026-08-09

- **Symptom:** `npm run lint` succeeds with one warning instead of cleanly:
  `apps/web/src/App.jsx:106:9 'closeRightView' is assigned a value but never used`.
- **Cause:** the callback remained after the drawer close controls stopped
  calling it.
- **Fix:** removed the unused callback; live close paths already update the
  persisted right-view state directly.
- **Evidence:** `cd apps/web && npm run lint` on 2026-08-09.

## I-017 — telemetry does not sample non-NVIDIA GPUs

**Status:** open · **Sev:** info · **Area:** daemon · **Opened:** 2026-08-09

- **Scope:** `TelemetryCollector._gpus()` uses `nvidia-smi`, so it reports every
  NVIDIA device but not Intel or AMD accelerators.
- **Current impact:** the deployment host reports both NVIDIA GPUs correctly;
  its integrated Intel controller is outside the existing telemetry contract.
- **Why deferred:** cross-vendor utilization, VRAM, and temperature collection
  requires vendor-specific tools or sysfs handling. It is separate from the
  fixed web regression that discarded every GPU after the first.
- **Evidence:** `nvidia-smi --query-gpu=index,name` returns two devices and the
  live relay payload contains both; `lspci` also lists an Intel display
  controller that `nvidia-smi` cannot sample.

### I-018 — `git add -A` in this shared tree commits other agents' in-flight work

- **Status:** open · **Severity:** medium · **Area:** process
- **What happened:** commit `b283b92` was meant to carry two Swift compile
  fixes. Because it staged with `git add -A`, it also swept in another
  agent's unfinished inventory-retry work (`apps/web/src/hooks/useRemotex.js`
  +64/−13, its test file, and `services/tests/test_shared_codex.py`) and
  pushed it to `main` under a commit message that says nothing about it.
- **Evidence:** `git show b283b92 --stat -- apps/web services/tests` lists
  three files the commit message does not mention. The window between
  `bf38a13` (17:05) and `b283b92` (17:08) is when they appeared.
- **Impact:** nothing was lost, and `main` is not broken — all three
  helpers (`shouldRetryInventoryRequest`, `inventoryRetryDelay`,
  `hostRefreshRef`) are defined and 77/77 web tests plus the pytest suite
  pass. But the author's work is now on `main` attributed to someone
  else's message, ahead of whatever they intended to ship, and the built
  relay image does **not** contain it (no redeploy was run), so live prod
  and `main` disagree on the web bundle.
- **Not fixed by rewriting history:** the commits are pushed to `main`, and
  a force-push there needs the owner's say-so. Left in place on purpose.
- **How to avoid:** stage explicit paths — `git add apple/ android/` — or
  `git commit -- <paths>`. Never `git add -A` in this repo; several agents
  share one checkout and one index.
- **For whoever wrote the retry code:** your change is already on `main` at
  `b283b92`. Nothing to re-apply; just deploy it when you are ready.

### I-019 — Codex 0.147 `thread/delete` fails against its migrated state database

- **Status:** open · **Severity:** medium · **Area:** codex/upstream
- **Symptom:** the managed Codex 0.147 app-server removes the rollout, then
  answers `thread/delete` with JSON-RPC `-32603` and `no such table:
  agent_jobs`. Because the request failed, it does not emit
  `thread/deleted`; a Remotex sidebar that is already open has no deletion
  invalidation until another lifecycle event triggers an authoritative list.
- **Cause evidence:** `/root/.codex/state_5.sqlite` has successful migrations
  through 46; migration 42 intentionally drops `agent_jobs`. The installed
  release still tries to clean that removed table on the delete path. Current
  Codex main no longer contains that runtime query, so this appears confined
  to the released 0.147 binary/state-migration combination rather than
  Remotex's adapter.
- **Remotex status:** the `thread/deleted` notification is subscribed,
  owner-scoped, forwarded, and covered by relay/daemon/web tests. A separate
  disposable app-server probe emitted the expected notification. The live
  deployment verified create, rename, archive, unarchive, authoritative REST
  convergence, and inventory reconnect; only Codex's failed delete could not
  produce a live notification.
- **Cleanup:** both disposable probe rollouts were already removed by Codex's
  partial delete. Their exact, relation-free metadata rows were removed after
  checking all Codex SQLite databases; no user thread was touched.
- **Next:** retest after the next Codex CLI release and close this issue when
  `thread/delete` returns `{}` and emits `thread/deleted` on the managed
  app-server daemon.

### I-020 — vulnerable web build/dev dependencies

- **Status:** open · **Severity:** medium · **Area:** web/tooling
- **What:** GitHub Dependabot reports nine open npm advisories (four high,
  four moderate, one low). A current local `npm audit` collapses them into
  five affected packages: direct `vite`, plus transitive `esbuild`,
  `js-yaml`, `brace-expansion`, and `@babel/core`.
- **Production boundary:** `npm audit --omit=dev --json` reports zero
  vulnerabilities across the seven production dependencies. The relay image
  uses Node only in its build stage and serves static output from Python, so
  none of these packages execute in the deployed container. The Vite/esbuild
  advisories chiefly matter when running the development server, especially
  if someone exposes it beyond localhost.
- **Why deferred:** safe fixes for the transitive parser/glob packages are
  available, but npm's complete Vite/esbuild resolution proposes Vite 8.2.1,
  a semver-major upgrade from the repository's Vite 5 line. That deserves a
  focused dependency update and browser/build regression pass rather than an
  unrelated deployment-time lockfile rewrite.
- **Evidence:** GitHub Dependabot API on 2026-08-09; local full audit reports
  three high, one moderate, one low package groups, while the production-only
  audit reports zero.

### I-021 — daemon reaches its own machine's relay via the public internet

- **Status:** open · **Severity:** low · **Area:** daemon/deploy
- **Read the correction below before acting on this.** Filed at first as
  "the tunnel keeps tearing down the daemon socket". That was wrong, and the
  experiments that disproved it are recorded here so nobody repeats them.
- **What is actually true:** `/root/.remotex/config.toml` has
  `relay_url = "wss://remotex.photonspark.ro/ws/daemon"`, so a daemon and a
  relay on the *same machine* talk via DNS → public edge
  (`webhost.photonspark.com`) → SparkTunnel → `relay:8080`. That is real and
  worth fixing, but it is **not** what produced the reconnect storm in the
  logs.
- **The reconnect storm was relay restarts — mostly ours.** Every
  `docker compose up -d --force-recreate relay` yields exactly the observed
  signature: `1006` (container killed under the socket) → `502` on the first
  retry (tunnel has no upstream yet) → reattach 1–4s later. Proven for one
  drop to sub-second precision:
  - daemon: `20:24:59.386 relay websocket closed (code=1006)`
  - `docker inspect remotex-relay-1 -f '{{.State.StartedAt}}'` →
    `2026-08-09T17:24:59.702Z` (= 20:24:59.702 local)
  The rest cluster in the same windows as builds/deploys during this
  session. **Since the last deploy: 0 drops in 35 min.**
- **Hypotheses tested and DISPROVEN** — do not re-litigate without new
  evidence:
  - *Tunnel/edge idle timeout:* two idle `/ws/inventory` sockets, one via
    `wss://remotex.photonspark.ro` and one direct to the container, with
    `heartbeat=None` client-side. **Both alive 18+ min.** If the edge killed
    idle WS connections, the tunnel one would have died and the direct one
    lived.
  - *Oversize frame (the `REMOTEX_MAX_FILE_BYTES` trap):* drove the real web
    app through login + opening a chat + scrolling to force history
    backfill, recording every frame. **Largest frame 780 bytes** against a
    25 MiB limit. Not close.
  - *Event-loop starvation missing the `heartbeat=20` pong window:*
    saturated all 32 cores for 45 s. **No drop.**
- **Still genuine, but rare:** 4× `ClientConnectorDNSError` in 6 h — local
  resolver failures. Only reachable because the daemon depends on public DNS
  to reach its own machine.
- **Separate, real, already filed as I-011:** 48× `421 Misdirected Request`
  from `ws://127.0.0.1:18080/ws/daemon` in the 14:00 hour. 18080 is
  published by the unrelated `gospod-nginx-1`, which rejects a Host it does
  not serve. That collision is *why* the config was moved to the public URL.
- **Fix, when someone wants it (low priority):** publish the relay on
  `127.0.0.1:<free port>` and point `relay_url` there, keeping the tunnel for
  phones. 18080 and 18081 are taken; 18090 and 19080 were free. The publish
  must go in `docker-compose.sparktunnel.yml` — that file's
  `ports: !reset []` wipes anything the base file publishes. Payoff is
  modest: no DNS/edge dependency, and deploy-time drops reconnect faster.
  It does **not** eliminate deploy-time drops — restarting the relay always
  drops the socket.
- **Do not build daemon-socket failover for this.** Two sockets over the
  same path fail together, and load-balancing session frames across them
  would reorder `turn-started`/`turn-completed` and the replay buffer. The
  drops were deploys.
