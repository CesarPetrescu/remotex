# WorkLog

Append-only log of what agents actually changed in this repo. Multiple
agents work here in parallel and cannot see each other's sessions — this
file is the only shared memory.

## Rules

- **Read the last ~3 entries before you start.** Someone may already be in
  the file you're about to touch.
- **Add your entry when you finish** (or when you stop mid-way — say so).
  Newest entry at the **top**, right under this section.
- **Append, never rewrite.** Correcting an earlier entry? Add a new one
  that says what changed. Don't edit history.
- Keep an entry to what the next agent needs: files touched, what was
  verified vs. assumed, and anything left broken or half-done.
- Cross-reference: `Issues.md` IDs (`I-007`) and `ToDo.md` item numbers.
- One entry per unit of work, not per file.

### Entry template

```markdown
## YYYY-MM-DD — <short title>
**Agent:** <model/name> · **Branch:** <branch> · **Status:** done | partial | abandoned

- **Why:** one line.
- **Changed:** `path:line` — what and why. One bullet per file.
- **Verified:** what you actually ran, and the result. Say "not verified" plainly.
- **Left open:** anything still broken, plus the `Issues.md` ID if you filed one.
- **Restart needed:** daemon / relay rebuild / android install / none.
```

---

## 2026-08-09 — reconcile deployment work with hardened main and close I-013
**Agent:** Codex (root integrator) · **Branch:** `reconcile-origin-main` · **Status:** done

- **Why:** integrate the Codex bridge/SparkTunnel checkpoint with all newer
  `origin/main` security and lifecycle fixes before exposing the relay.
- **Changed:** reconciled daemon, relay, web, Android, Apple compatibility,
  documentation, and deployment conflicts by subsystem. Kept upstream host
  ownership checks, prompt queues, replay/frame/body limits, token storage,
  and Android close handling while retaining host-derived models, turn
  steering, Codex 0.147 events, and optional SparkTunnel deployment.
- **Changed:** `services/daemon/client.py` and relay WebSocket/hub handlers —
  added bounded welcome timeout, equal-jitter reconnect backoff, stable-link
  reset, slow authentication retry, replacement exit, handshake readiness
  gating, explicit active-turn failure on daemon loss, prompt invalidation
  tombstones, and current-socket identity checks so a displaced daemon cannot
  publish an already-queued stale frame after handoff.
- **Changed:** web/Android/Apple reconnect snapshots now consume the relay's
  authoritative in-flight turn state; web and Android preserve pending state
  across transient drops. Android replays from zero when reopening a cleared
  transcript, resets that cursor durably, reconnects on foreground, cancels
  stale host-model jobs, and now submits image-only send/steer prompts.
- **Changed:** `deploy/` and `.dockerignore` — the normal profile keeps a
  loopback listener, the optional SparkTunnel override publishes no relay
  port, Caddy receives `ACME_EMAIL`, connector v0.2.0 is checksum-pinned and
  non-root, and every `.env*` file is excluded from Docker build contexts.
  The personal mode-0600 `deploy/.env` remains ignored and untracked.
- **Verified:** service pytest **146 passed** and Ruff passed; disposable
  Postgres relay↔daemon↔client e2e passed; web Vitest **56 passed**, ESLint,
  Vite production build, and `npm audit --omit=dev` passed with zero known
  vulnerabilities; Android debug and release each passed **25 tests**, and
  lint passed with 0 errors (38 existing warnings, 1 informational finding).
- **Verified:** base, Caddy TLS, and portless SparkTunnel Compose configs
  validate; fresh relay and connector images build; connector reports 0.2.0,
  runs as UID 65532, and refuses a missing token with exit 64. Tracked/index
  secret scans, conflict-marker scans, and `git diff --check` passed. Xcode is
  unavailable on this machine, so no Apple build was run here.
- **Left open:** `I-011` until the installed daemon is pointed at the public
  relay with a fresh bridge key; `I-012` for missing Apple steer/interrupt,
  auto-reconnect, and progressive item-patch behavior. User/host/bridge-key
  provisioning and the final private-URL Android install are deployment steps,
  not source blockers.
- **Restart needed:** rebuild/start relay + SparkTunnel, provision credentials,
  rewrite daemon config, restart `remotex-daemon`, rebuild/reinstall Android.

## 2026-08-09 — reconcile documentation and deployment examples onto hardened main
**Agent:** Codex (`docs_merge`) · **Branch:** `reconcile-origin-main` · **Status:** done

- **Why:** resolve the documentation half of `57d074e` without restoring stale
  demo-seeding, token-storage, transport, CI, or model-catalogue claims.
- **Changed:** root and subproject READMEs retain `origin/main`'s hardened auth,
  replay, size-limit, Keychain, cleartext-transport, and test guidance while
  documenting host-derived models, active-turn steering, and MCP elicitation.
- **Changed:** `deploy/README.md` and `deploy/.env.example` document three
  independent modes: direct loopback/LAN, Caddy, and optional portless
  SparkTunnel. Proxy modes require `RELAY_TRUST_PROXY=1`; published modes keep
  `RELAY_SEED_DEMO=0`. No personal hostname or credential was added.
- **Changed:** `CLAUDE.md` no longer records a machine-specific bind/port and
  points at the current split Codex v2 protocol source.
- **Verified:** all assigned conflict markers removed; `git diff --check`
  passes for assigned files; base, Caddy, and portless SparkTunnel Compose
  configurations validate. Documentation claims were compared with the merged
  relay, daemon, web, Android, and iPhone code.
- **Left open:** none in the documentation scope.
- **Restart needed:** none for documentation; the overall merge still requires
  the daemon/relay/client restart rituals recorded by the implementation agents.

## 2026-08-09 — reconcile daemon and relay backend onto hardened main
**Agent:** Codex (`backend_merge`) · **Branch:** `reconcile-origin-main` · **Status:** done

- **Why:** resolve the backend half of `57d074e` without losing the security,
  size-limit, ownership, prompt-queue, or session-lifecycle fixes on
  `origin/main`.
- **Changed:** `services/daemon/client.py` — combined bounded WebSocket frames
  and paginated daemon-side model mapping with welcome timeouts, jittered
  reconnect backoff, stable-connection reset, slow auth retry, and clean exit
  when a newer daemon replaces this process.
- **Changed:** `services/daemon/adapters/admin.py`, `services/relay/app.py`,
  `services/relay/handlers/models_route.py`, and `services/relay/models.py` —
  retained the hardened shared ownership-checked daemon RPC, response bounds,
  API-key routes, body limits, and reservation sweeper; host Codex remains the
  model authority and the offline fallback now names no models.
- **Changed:** `services/tests/test_models_endpoint.py` — checks the no-name
  fallback, live Codex-shaped per-model efforts, owned-host proxy, offline
  fallback, and foreign-host 404.
- **Audited:** the auto-merged 0.147 item/elicitation/steer/resolved-prompt
  paths in `stdio.py`, `items.py`, `elicitation.py`, `ws_client.py`, and
  `ws_daemon.py` against the refreshed Codex source and installed 0.147 schema;
  no incompatible security or queue semantics found.
- **Verified:** focused backend tests **42 passed**; full services suite
  **138 passed**; all touched Python files compile; live installed Codex
  `model/list` returned 7 models and mapped the host effort union through
  `ultra`; Ruff lint passes. Ruff format is not clean repo-wide (51 existing
  files would be reformatted), so no bulk formatting was applied.
- **Left open:** `services/README.md` is still conflicted and owned by the
  documentation merge; no backend issue was deferred.
- **Restart needed:** daemon and relay rebuild/recreate after the overall
  cherry-pick completes.

## 2026-08-09 — reconcile web and Android clients onto security-hardened main
**Agent:** Codex (`client_merge`) · **Branch:** `reconcile-origin-main` · **Status:** done

- **Why:** preserve the newer security, prompt-queue, file-limit, WebSocket
  ordering and reconnect work while bringing back per-host models, live turn
  steering and progressive item patches from `57d074e`.
- **Changed:** `apps/web/src/**` — retained `origin/main` as the state-machine
  base; added `turn-steer`, `item-patch` / `steer-failed`, readable unknown-item
  labels, and a host-scoped model list with no hardcoded model names.
- **Changed:** `android/app/src/main/**` — the same features on top of the
  ordered socket and prompt queues; removed the cherry-pick's duplicate model
  loader and kept the existing model/effort validity reset and API fallback.
- **Verified:** web Vitest **55 passed**, ESLint clean, Vite production build
  clean; Android debug and release unit tests **22 passed each**, lint clean,
  and a public HTTPS `RELAY_URL` build produced the debug
  APK. Kotlin reports one pre-existing `LocalLifecycleOwner` deprecation.
- **Left open:** the host-model API is assumed to keep its authenticated
  `{models: [...]}` response envelope; backend reconciliation owns that route.
  The overall cherry-pick is still in progress outside these client files.
- **Restart needed:** relay image rebuild for web; rebuild/reinstall Android.

## 2026-08-09 — SparkTunnel deployment profile and pre-deploy hardening
**Agent:** Codex · **Branch:** `main` · **Status:** done

- **Why:** add an outbound-only PhotonSpark deployment path for the personal
  relay without forcing SparkTunnel on other self-hosters.
- **Changed:** `deploy/Dockerfile.sparktunnel` and
  `deploy/sparktunnel-entrypoint.sh` — pinned the official Linux amd64 0.2.0
  connector by SHA-256, run non-root/read-only, validate required HTTPS server,
  token and HTTP target.
- **Changed:** `deploy/docker-compose.yml` and
  `deploy/docker-compose.sparktunnel.yml` — optional connector profile; the
  SparkTunnel override removes every relay host port while the base file keeps
  normal loopback listening for local users.
- **Changed:** `deploy/.env.example`, `deploy/README.md`, `README.md` — documented
  local, Caddy and SparkTunnel modes, their commands, authentication boundary,
  reconnect behavior and connector upgrade pin.
- **Local only:** ignored mode-0600 `deploy/.env` holds the personal hostname,
  generated user token, rotated Postgres password and PhotonSpark connector
  token. No secret or personal hostname is staged. The existing Postgres role
  was rotated and password authentication verified, then the container stopped.
- **Verified:** official artifact reports 0.2.0 and checksum
  `e57b3293f8c4dcb2a55e0a5fbd44e040e87b6b55be7b99222c287248e422d6b7`;
  connector retry observed at 1/2/4/8 seconds; image builds and runs as UID
  65532 with read-only filesystem and all capabilities dropped; base Compose
  publishes loopback 8080; SparkTunnel Compose publishes no relay port;
  `test_daemon_connection.py` 4/4 passed; `git diff --check` clean.
- **Left open:** `I-013` is the deployment blocker: reconcile this checkpoint
  with the five commits on `origin/main`, especially security remediation,
  before starting the public tunnel. The installed daemon still needs a newly
  issued bridge key and its final relay URL after that reconciliation.
- **Restart needed:** relay/Compose rebuild and daemon reconfiguration after
  `I-013`; do not start the tunnel from this stale base.

## 2026-08-09 — iOS XCTest target + CI runs it (and CI immediately earned its keep)
**Agent:** Claude Opus 5 · **Branch:** `ios-xctest` → PR #16 · **Status:** done, green

- **Why:** the iOS client had no test target, so CI compiled it and stopped.
  The `item-patch` handler from the previous entry shipped unverified — no
  macOS available locally.
- **Changed** (all on branch `ios-xctest`, PR #16, **not** merged):
  - `apple/RemotexTests/FrameHandlingTests.swift` — 20 tests over
    `RemotexViewModel.handle(frame:)` driven with real daemon frames:
    `item-patch` replaces rather than appends, `fileChange` lands as a tool
    row with its diff (`I-006`), `slash-ack` renders `thread/compacted`
    (`I-009`), approval queue de-dupes by `approval_id` and keeps order,
    malformed frames are ignored.
  - `apple/Remotex.xcodeproj/project.pbxproj` — hand-written test target
    (`com.apple.product-type.bundle.unit-test`, `TEST_HOST` on the app,
    target dependency + container proxy, own config list). Followed the
    file's existing `91A0…` UUID convention.
  - `Remotex.xcscheme` — `TestableReference` + a `buildForTesting` entry.
  - `RemotexViewModel.handle(frame:)` — private → internal, so tests drive
    real frames. Everything it calls stays private.
  - `.github/workflows/ci.yml` — `xcodebuild build` → `test` against a
    **concrete** simulator resolved from the runner via `jq`
    (`generic/platform` can only build, never test); `.xcresult` uploaded on
    failure; DerivedData cache key now includes `apple/RemotexTests/**`.
- **Verified:** CI run `31308340132` → **iPhone · Xcode test: success**, all
  20 tests passing on `iPhone 16 Pro` / Xcode 16.4. Whole run green.
  pbxproj was validated locally first (balanced braces, no dangling UUID
  refs, all sections closed, scheme parses as XML) since Xcode isn't
  available here.
- **CI caught a real mistake on the first run** (18/19, one failure): my
  `testErrorFrameSurfacesTheMessage` asserted `status == .error`, which was
  true *before* `handle(frame:)` was rewritten on `origin/main`.
  `handleRelayError` deliberately leaves `status` alone — an error frame
  doesn't tear the socket down, and `.error` would wedge the composer with no
  route back to `.connected`. The test was wrong, not the code; fixed to
  assert the intended behaviour plus the turn-busy case.
- **Left open:** `I-013` — **local `main` was 3 commits / +7830 lines behind
  `origin/main`.** Discovered because the PR was created `CONFLICTING` and
  GitHub silently refuses to run `pull_request` workflows when it can't
  compute a merge commit: CI never queued and nothing said why. Resolved for
  this branch by cherry-picking onto `origin/main` in a **throwaway
  worktree**, so the 38 uncommitted files in the main tree were never at
  risk. `origin/main` had independently rewritten
  `apple/Remotex/RemotexViewModel.swift` (+267, structured
  `pendingApprovals`, new `PendingPromptsView.swift`, `Keychain.swift`) and
  also touched `services/relay/models.py` and
  `services/tests/test_models_endpoint.py` — both of which this session's
  **uncommitted** work also rewrites. That reconciliation is still to do.
- **Restart needed:** none (CI + iOS only).

## 2026-08-09 — close out every open issue (I-001…I-010): steer, per-host models, file diffs, resolved-request retraction
**Agent:** Claude Opus 5 · **Branch:** main · **Status:** done

- **Why:** second pass over `Issues.md` — fix all ten. Eight fixed, one
  turned out to be an upstream codex quirk (`I-005`, invalid), one is
  documentation (`I-007`). Two new issues found and filed (`I-011`,
  `I-012`).
- **Changed:**
  - `adapters/items.py` — `fileChange` maps to `tool_call`, and
    `_format_changes()` flattens `changes` into `tool`/`args.command`/
    `output`. Fixing it **in the daemon** gave web, Android and iOS
    file-change diffs with zero client work (`I-006`).
  - `adapters/stdio.py` — new handlers: `item/fileChange/patchUpdated` →
    `item-patch` (replace, not append — codex resends the whole patch),
    `item/commandExecution/terminalInteraction` → stdin echoed as a delta
    (`I-001`), `serverRequest/resolved` → `_retract_server_request()`
    reverse-looks the rpc id and emits `approval-resolved` /
    `user-input-resolved` (`I-003`), `thread/compacted` → `slash-ack`
    (`I-009`). Extracted `_build_input()` out of `turn-start` and added
    `_steer_turn()` for the new `turn-steer` frame (`I-004`).
  - `adapters/elicitation.py` — `_options()` now covers multi-select
    (`items.anyOf`, `items.enum`); `_coerce()` returns an array for those,
    detected via `items` because codex's multi-select schemas have **no**
    `type` key (`I-010`).
  - `adapters/admin.py` + `client.py` — `list_models()` and a
    `models-list-request`/`-response` pair (`I-002`).
  - `relay/models.py` — **model names deleted.** Only the `id: ""`
    "let codex decide" entry and a fallback effort list remain.
  - `relay/handlers/models_route.py` — new
    `GET /api/hosts/{host_id}/models` deriving models *and* per-model
    reasoning efforts from codex; `app.py` route added.
  - `relay/handlers/ws_client.py` — `turn-steer` forwarded **without**
    `try_begin_turn()` (that guard would block every steer), with a
    `user_message` echo so all clients see it.
  - `relay/handlers/ws_daemon.py` — `approval-resolved` /
    `user-input-resolved` clear the hub's pending map and re-broadcast the
    top-level frames clients already handle.
  - `apps/web/` — host-scoped model fetch (`relayClient.listHostModels`,
    effect keyed on `selectedHostId`); `config.js` model names deleted;
    `sendSteer` + `steerTurn`; composer stays live during a turn and the
    send button becomes a steer button; `item-patch` and `steer-failed`
    handled; `humanizeItemType()` for the ten item types no client renders
    specially (`contextCompaction`, `webSearch`, `plan`, `sleep`, …).
  - `android/` — same set: `listHostModels`, `refreshModels(hostId)` on
    `selectHost`, `MODEL_OPTIONS` reduced to the default entry, `steerTurn`,
    `item-patch`, `steer-failed`, `humanizeItemType`, and a three-state
    `SendOrStopButton`.
  - `apple/` — `item-patch` handled for parity. No steer/stop UI exists
    there at all; filed as `I-012` rather than silently expanding scope.
  - `deploy/install-daemon.sh` run → unit installed and started (`I-008`).
  - `services/tests/test_stdio_dispatch.py` (+12 tests, 22 total) and
    `test_models_endpoint.py` rewritten — its old assertion
    (`any(i.startswith("gpt-"))`) required the hardcoded list that was the
    bug.
- **Verified:**
  - `pytest tests/ -q` → **70 passed**.
  - `npm run build` clean; `npx eslint src` → 0 errors (3 pre-existing
    warnings in `JumpPicker.jsx`, untouched).
  - `./gradlew assembleDebug` → clean.
  - `docker compose build relay` → `remotex/relay:local` built with the new
    bundle.
  - **Live against codex 0.147** (`scratchpad/probe_steer.py`): wrong
    `expectedTurnId` → `-32600 expected active turn id …`; correct one →
    `{turnId}` and the model obeyed the steered instruction mid-turn.
  - **Live** (`probe_items.py`): captured real `fileChange`,
    `contextCompaction`, `turn/diff/updated` payloads, and proved `I-005` is
    upstream — codex's own `aggregatedOutput` omits the first line too.
  - `systemctl --user is-active remotex-daemon` → `active`.
  - **Not verified end-to-end:** no relay is reachable on this box — see
    `I-011`. Relay and client changes are compile-and-unit verified only.
    The elicitation path still hasn't met a real eliciting MCP server.
- **Left open:** `I-011` (port collision — needs a human decision, do not
  "fix" by touching `gospod-*`), `I-012` (iOS UI), plus the `ToDo.md`
  backlog (`turn/diff/updated`, rate limits, fuzzy file search, …).
- **Restart needed:** daemon **and** relay recreate. The relay image is
  built but **not** running (blocked by `I-011`). Android needs
  `./build.sh install`.

## 2026-08-09 — codex 0.147 protocol audit + fix dropped command output & turn-killing server requests
**Agent:** Claude Opus 5 · **Branch:** main · **Status:** done (ToDo #1, #2)

- **Why:** audited `adapters/stdio.py` against the codex protocol our hosts
  actually run (0.147.0). Two live bugs, one stale-data bug, plus a large
  unused protocol surface. Full findings in `ToDo.md`.
- **Changed:**
  - `services/daemon/adapters/stdio.py:1087` — handle
    `item/commandExecution/outputDelta`. Codex names it `outputDelta`, so
    the generic `item/*/delta` match never saw it and clients showed
    nothing until `item/completed`. Emits `item_type: "tool_call"`
    (not `command_execution`) because that's what `items.py:12` maps
    `commandExecution` to and what both clients append deltas for.
  - `services/daemon/adapters/elicitation.py` — **new**. Translates
    `mcpServer/elicitation/request` (form / openai-form / url modes) into
    our existing `user-input-request` questions, and the answers back into
    codex's `{action, content}` reply with per-field type coercion.
  - `services/daemon/adapters/stdio.py` — new `mcpServer/elicitation/request`
    branch in `_dispatch`; `_pending_elicitations` map (separate from
    `_pending_user_inputs` because the reply shapes differ);
    `_resolve_user_input` picks the right reply shape.
  - `services/daemon/adapters/stdio.py:1189`
    `_reject_unsupported_server_request` no longer pushes
    `turn-completed{error}`. It answered -32601 *and killed the user's
    turn* for requests codex handles fine alone (`item/tool/call`,
    `account/chatgptAuthTokens/refresh`, `attestation/generate`,
    `openai/form`).
  - `services/tests/test_stdio_dispatch.py` — **new**, 12 tests.
  - `ToDo.md`, `Issues.md`, `WorkLog.md` — new; `CLAUDE.md`, `AGENTS.md`
    point at them.
- **Verified:**
  - `~/.local/share/remotex/venv/bin/python -m pytest tests/ -q` → **55
    passed** (12 new).
  - Live probe against real `codex app-server` 0.147.0: a shell loop
    emits `item/commandExecution/outputDelta` with plain-string deltas,
    confirming the notification was being dropped. Probe kept at
    `/tmp/claude-*/scratchpad/probe_outputdelta.py`.
  - Read both clients' reducers — `useRemotex.js:327` (`APPEND_DELTA`) and
    `RemotexViewModel.kt:1191` both append tool deltas to `output`, so the
    fix needs **no client change**.
  - **Not verified:** daemon restart. `systemctl --user restart
    remotex-daemon` fails on this box — the unit isn't installed here (see
    `I-008`). Whoever runs a real host must restart and eyeball a
    streaming command.
  - **Not verified:** the elicitation path against a real MCP server that
    elicits. Unit-tested against schema-derived frames only.
- **Left open:** `I-001`, `I-005`, `I-006`, `I-010` filed from this work.
  `ToDo.md` #3/#4/#5 analysed with file:line steps but not started.
- **Restart needed:** daemon (`systemctl --user restart remotex-daemon`).
  No relay rebuild — nothing under `services/relay/` or `apps/web/` changed.
- **Not mine:** the working tree also has uncommitted changes in
  `services/daemon/client.py` (+199/-59) and a new
  `services/tests/test_daemon_connection.py` from another agent, plus
  modified `README.md`s. Untouched by this work; the full suite passes with
  them in place. Commit separately.
