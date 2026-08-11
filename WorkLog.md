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

## 2026-08-11 — resolve Android release verifier from the SDK
**Agent:** Codex · **Branch:** agent/release-apksigner-hotfix · **Status:** fixed locally; release rerun pending

- **Why:** the signed nightly APK built successfully, but the current hosted
  runner no longer exports its installed `apksigner` binary on `PATH`.
- **Changed:** `.github/workflows/release.yml` — select the newest executable
  `apksigner` from `$ANDROID_SDK_ROOT/build-tools`, fail clearly if it is
  absent, then retain the exact certificate and v2-signature gates.
- **Verified:** the resolver selects the newest tool from the local SDK;
  `actionlint` and `git diff --check` pass. The hosted release rerun is pending
  at this entry's commit.
- **Left open:** none specific to this fix.
- **Restart needed:** none.

## 2026-08-11 — make release runners provision an iPhone simulator
**Agent:** Codex · **Branch:** agent/release-simulator-hotfix · **Status:** fixed locally; macOS release rerun pending

- **Why:** the first post-merge nightly release runner had an installed iOS
  runtime but no pre-created `iPhone 16 Pro`, so its otherwise-valid XCTest
  command failed before compiling tests.
- **Changed:** `apple/ci-select-simulator.sh` — select an available iPhone by
  UUID, or create one from the newest installed iOS runtime and a compatible
  device type when the runner image has none.
- **Changed:** `.github/workflows/{ci,release}.yml` — warm the simulator build,
  test against the resolved UUID, and use that exact device for screenshot
  install/launch/capture rather than a fragile model name or `booted` alias.
- **Verified:** `bash -n`, existing-device and create-device command fixtures,
  `actionlint`, and `git diff --check` pass. The real macOS release rerun is
  pending at this entry's commit.
- **Left open:** none specific to this fix.
- **Restart needed:** none.

## 2026-08-11 — make iPhone share filenames platform-neutral
**Agent:** Codex · **Branch:** agent/release-parity · **Status:** done; macOS rerun pending

- **Why:** Xcode 16.4 CI proved that Darwin resolves `..` through
  `URL(fileURLWithPath:)`, defeating the intended unsafe-basename fallback.
- **Changed:** `apple/Remotex/RemotexViewModel.swift` — extract the last
  relay-provided path component as an opaque string before stripping controls
  and rejecting empty/dot basenames.
- **Changed:** `apple/RemotexTests/CoreReliabilityTests.swift` — cover dot,
  slash-only, and backslash-only untrusted names in the traversal regression.
- **Verified:** the failing CI run compiled the app and all XCTest sources,
  then passed 47/48 tests; static review confirms the string-only fix targets
  the sole failure. The full macOS rerun is pending at this entry's commit.
- **Left open:** none specific to this fix; release-wide platform limitations
  remain tracked as I-022 through I-024.
- **Restart needed:** none.

## 2026-08-11 — v0.2 provider-neutral clients, mobile parity, Windows app, and releases
**Agent:** Codex team · **Branch:** agent/release-parity · **Status:** done locally and deployed; GitHub artifact gates pending

- **Why:** audit the browser/phone product in depth, close practical
  Android/iPhone parity gaps, make public builds provider-selectable, add a
  secure Windows client, and turn ad-hoc binaries into one tested release
  pipeline.
- **Changed:** `apps/web/` — made approval choices authoritative (with the
  complete fallback), masked secret questions, added `/collab` routing, fixed
  file deep-link timing, surfaced token/goal progress, improved transcript and
  phone accessibility, and upgraded the Vite/Vitest toolchain to a clean
  supported line.
- **Changed:** `android/` — runtime relay selection, relay-scoped Android
  Keystore credentials, live inventory/reconnect/replay, provider-scoped
  session restore, image/FIFO queue/steer/stop flows, complete prompt handling,
  writable files, all-GPU telemetry/history, high contrast, deferred local
  notifications, transfer ceilings, release signing, and release-critical UI
  tests. Public release defaults contain no operator URL or demo token.
- **Changed:** `apple/` — matched the same native session surface: live
  inventory, model/settings/history, images/FIFO/steer/interrupt, ordered
  prompts, goals/slash commands, writable workspace, telemetry, high contrast,
  provider-scoped Keychain/session restore, local completion notification, and
  a derived ~37 MiB WebSocket ceiling. Added two XCTest files and wired their
  target/scheme membership.
- **Changed:** `apps/desktop/` — added the provider-selectable Electron shell,
  first-run setup, exact-origin navigation/permission boundary, sandboxed
  remote page, mode-0600 atomic settings, the existing Remotex brand asset,
  tests, and x64 NSIS + portable build targets.
- **Changed:** `.github/workflows/{ci,release}.yml` — pinned current Node-24
  actions by commit SHA, added Windows/macOS/Android artifact gates, exact
  Android signing-certificate continuity, provider scans, checksums, build
  provenance, cumulative nightly change detection/asset retention, and one
  atomic stable/nightly publisher. Android signing secrets and the public cert
  fingerprint are configured in this repository.
- **Changed:** `deploy/` + `services/daemon/config.py` — build the web app on
  supported Node 24 and publish the SparkTunnel relay only on a dedicated
  same-host loopback port, avoiding public DNS/tunnel hairpin for the daemon.
  Documentation, `Issues.md`, and `ToDo.md` now reflect the actual parity and
  audited Codex 0.147 surface.
- **Verified:** backend Ruff and **202 pytest** tests pass; the disposable
  Postgres relay↔daemon↔client e2e streamed a complete mock turn; `pip-audit`
  reports no known runtime vulnerabilities. Web lint/build/full audit and
  **106 Vitest** tests pass. Electron lint/full audit, **10 tests**, a current
  Linux package, ASAR/provider scan, and loopback-DNS attack cases pass.
  Android debug and release suites each pass **47 JVM tests**, lint is clean,
  **7 emulator UI tests** pass, and the release APK is v2-signed by the pinned
  certificate with version `v0.2.0` / code `200`. A live mock Android turn was
  exercised on the emulator and its before/current states were visually
  compared. Apple plist/scheme/PBX membership and changed Swift parse cleanly;
  all **48 XCTest methods** are wired, with Xcode compilation intentionally
  delegated to the macOS PR check. Both workflows pass `actionlint` and
  `git diff --check`.
- **Verified live:** rebuilt only the Remotex Compose stack; local and public
  `/api/models` responses match, the public page serves the new web bundle,
  relay/Postgres are healthy, SparkTunnel is up, the loopback publish is bound
  only to `127.0.0.1:19080`, and `remotex-daemon` reattached over that endpoint.
  The previous daemon config is preserved at
  `/root/.remotex/config.toml.pre-v0.2.0`.
- **Left open:** macOS Xcode and Windows NSIS/portable builds must pass on the
  PR before tagging. No APNs/FCM after stop/suspension (`I-024`), no Windows
  Authenticode (`I-022`), and no signed/TestFlight iPhone distribution
  (`I-023`). Intel/AMD GPU telemetry (`I-017`) and Codex 0.147's upstream
  `thread/delete` failure (`I-019`) remain outside this release. Current web
  browser screenshots were not captured because browser automation permission
  was not granted; existing reference captures plus live Android captures and
  reducer/component tests were used instead.
- **Restart needed:** none — relay/web and the host daemon are already live;
  mobile/Windows artifacts are installed from the GitHub release after its
  required CI gates pass.

## 2026-08-09 — composer submit controls: split primary, stop moved to the turn
**Agent:** Claude Fable 5 · **Branch:** main · **Status:** done, deployed

Owner: "the queue, send and stop are looking bad — propose a proper idea."
Offered three shapes; they picked split-primary + stop-on-the-Working-row.

- **What was wrong** (state: turn running *and* text typed): three peer 44px
  buttons, two of them saturated fills. Queue and steer are the *same* action
  differing only in *when*; stop is a different kind of thing entirely. Also
  `↳` vs `↑` cannot convey "next" vs "now", and the control area changed width
  between 1 and 3 buttons — so send moved, and **stop landed where send had
  just been**, one mis-tap from your draft.
- **Now:** always exactly one primary in one place (`↑`), with a flush
  chevron that opens `Steer now` / `Queue as next turn` (each with a one-line
  explanation, which the arrows could never carry). The chevron only exists
  while a turn is running — the only time there is a second choice.
- **Stop moved to the `✳ Working…` row** in the transcript: it acts on the
  turn, not the draft. Outline rather than fill, and **the row is now
  `position: sticky; bottom: 0`** so it stays reachable while you scroll back
  through history. There is no longer any red in the composer.
- **Reused the `.dd-*` classes** from the picker menus, so the chevron menu is
  a bottom sheet on phones for free. Did **not** extract a shared PopMenu
  component: ChipDropdown's trigger is chip-shaped and the split button's is
  not, so sharing would have meant generalising a file I had just verified.
  ~35 lines of portal/dismiss logic are duplicated; dedup if a third menu
  appears.
- **Verified:** 6 new tests via `renderToStaticMarkup` (react-dom/server, the
  pattern TelemetrySidebar.test.jsx already uses) asserting the state machine
  — one button idle, two while steering, none when queueing is unavailable,
  **no stop control in any state**, primary always first. 93 web tests, lint
  and build clean. Geometry measured in a Playwright harness against the
  compiled stylesheet at 1500x950 and 390x844: halves flush and equal height,
  44px primary on phone, stop 34px there, working row `sticky`, menu docks
  full-width with a scrim on phone only, and both composer buttons cyan (no
  red).
- **Couldn't test live:** no turn was running, and I would not send a prompt
  on the owner's account to manufacture one. The steering state is covered by
  the unit tests plus the harness rather than an end-to-end click.
- **Harness gotcha, second time:** `cp dist/assets/*.css` failed because the
  shell cwd resets between calls, so the first run measured an unstyled page
  (`position: static`, 21px buttons). Use absolute paths and sanity-check that
  the harness really loaded the stylesheet.

## 2026-08-09 — repair stale issue-ledger bookkeeping found by docs audit
**Agent:** Codex · **Branch:** main · **Status:** done

- **Why:** the final README audit found two entries using `I-019` and a stale
  `ToDo.md` summary that claimed only two issues remained.
- **Changed:** `Issues.md` — assigned the later same-machine public-relay
  hairpin entry its intended next ID, `I-021`, and advanced the next free ID to
  `I-022`; no issue content or status changed.
- **Changed:** `ToDo.md` — points to `Issues.md` for the current open set rather
  than duplicating an obsolete list.
- **Verified:** every issue table ID and detail heading is unique; full
  `npm audit` still reports five build/dev-tool package groups, while
  `npm audit --omit=dev` reports zero production dependency vulnerabilities.
- **Left open:** `I-020` remains the focused Vite/toolchain upgrade; it is not
  a deployed-runtime exposure.
- **Restart needed:** none (documentation only).

## 2026-08-09 — synchronize non-native READMEs with current relay/daemon/web code
**Agent:** Codex · **Branch:** main · **Status:** done

- **Why:** recent shared-session recovery, live inventory, multi-GPU,
  Codex-resolved settings, picker, theme, and queue work had outgrown parts of
  the checked-in documentation; the root protocol table was also malformed.
- **Changed:** `README.md` — repaired the protocol table; documented queue vs.
  steer, the inventory socket, current event kinds, current web capabilities,
  and narrowed the remaining fault-test gap to cases that are not already
  covered.
- **Changed:** `apps/web/README.md` — documented inventory reconnect/refresh,
  all-GPU telemetry, resolved settings, three themes, responsive picker sheets,
  actual browser persistence, current source layout, and current test coverage.
- **Changed:** `services/README.md` — added the inventory handler and shared
  adapter to the architecture, plus current reconnect, telemetry, settings, and
  test behavior.
- **Changed:** `deploy/README.md` — distinguished `stdio` from `shared` active
  turn recovery and documented every relay rate-limit variable, Compose
  pass-through limits, timezone, and precise relay-restart state loss.
- **Verified:** ESLint, 87 vitest tests, and production Vite build pass; Ruff
  and 202 pytest tests pass; `git diff --check`, base Compose config, combined
  SparkTunnel Compose config, and local links in all four edited READMEs pass.
  Final scope inspection found no Android/Apple changes and no credentials.
- **Left open:** no documentation defect found in the audited non-native
  surfaces. Native READMEs/code were deliberately excluded per owner request.
- **Restart needed:** none (documentation only).

## 2026-08-09 — composer chips show codex's real settings, not local defaults
**Agent:** Claude Fable 5 · **Branch:** main · **Status:** committed + tested; needs a daemon restart + relay rebuild to take effect

Owner: "cant it auto update these from what it knows already?" — the MODEL /
EFFORT / PERMS chips always read "default / medium / default".

- **It was worse than cosmetic.** Those chips were pure local UI state
  (`initialState.model = ''` → `ModelPicker` falls back to `list[0]`, labelled
  "default"). The daemon's real model went into `session.model` and never
  reached the picker. So **PERMS read "default" on a thread codex had actually
  resolved to `dangerFullAccess` + `approvalPolicy: never`** — the control
  claimed a sandbox that wasn't there.
- **Followed the hard gate**: refreshed `/tmp/codex` (a16863f, matching
  installed 0.147.0) and read
  `app-server-protocol/src/protocol/v2/thread.rs`, then **probed a real
  `codex app-server`** rather than trusting the source read.
- **What codex actually reports** (probe output, `ephemeral: true` so no
  rollout file):
  - `thread/start` → `model`, `modelProvider`, `serviceTier`,
    `reasoningEffort`, `approvalPolicy`, `approvalsReviewer`, `sandbox`,
    `activePermissionProfile`. On this host: `gpt-5.6-sol` / `high` /
    `never` / `dangerFullAccess`.
  - `thread/settings/updated` fires with the **full** `ThreadSettings` on a
    real change — and **sends nothing for a no-op**. My first probe set
    effort `high`→`high` and saw no notification; I nearly concluded codex
    does not push settings. It does. **Change the value when probing a
    change notification.**
  - **Trap worth remembering:** the two sources spell the same things
    differently — `reasoningEffort`/`effort` and `sandbox`/`sandboxPolicy`.
    Unguessable; `resolved_settings()` reads both.
- **Changes:**
  - `permissions.py`: `resolved_settings()` + `_permissions_from_codex()`
    (inverse of the existing `_permissions_to_codex`). Returns **None** for a
    sandbox we have no button for — absent means unknown, never "default".
  - `stdio.py`: capture on `thread/start` and `thread/resume`, ship as
    `settings` on `session-started` / `thread-status:resumed`, and forward
    `thread/settings/updated` as a new `session-settings` event.
  - `useRemotex.js`: `RESOLVED_SETTINGS` action seeds the pickers on
    start/resume and on live pushes. Codex wins over local picker state.
  - **No relay change needed** — `broadcast_to_session` forwards frames
    verbatim, so a new event kind and a new field pass straight through.
- **Verified:** 6 new daemon tests from the real probe frames (both wire
  shapes, every UI permission round-tripping through
  to_codex→from_codex, unknown policy → None) — **202 backend passing**. 3
  new reducer tests against the real exported reducer — **87 web passing**.
  eslint + build clean.
- **Known gap, deliberate:** the relay persists only `thread_id`/`cwd` per
  session, so a client attaching mid-session gets settings only if
  `session-started` is still inside the 1000-frame replay buffer. Covering
  that properly needs a session-row column; not worth it for the reported
  problem (opening/resuming a session), but that is why it may still show
  stale values on a re-attach to a very long session.
- **Not effective yet:** needs `systemctl --user restart remotex-daemon` and
  a relay rebuild. Held off — the owner had a live turn sitting on a command
  approval.
- **Note:** the typography work from the entry below **is already live** —
  another agent's deploy at 21:34 carried it (live CSS hash
  `index-Dt1EhsG6.css` == local build).
- **Self-inflicted, worth flagging:** I wrote a `while old in s:` replace loop
  where the replacement *contained* the search string — infinite loop, killed
  after 120s. The file was untouched (the write came after the loop). Use the
  Edit tool for repeated-anchor edits, not a hand-rolled loop.

## 2026-08-09 — deploy queue, typography, and theme web release
**Agent:** Codex · **Branch:** main · **Status:** deployed

- **Why:** publish the verified queue/steer unit and the already-committed
  transcript typography update; correct the earlier theme/queue entries whose
  restart status was still recorded as pending.
- **Changed:** rebuilt `remotex/relay:local` from `main` at `b9e41d3` and
  force-recreated only `remotex-relay-1`; Postgres and SparkTunnel were left up.
- **Verified:** relay and Postgres report healthy, SparkTunnel is up, the daemon
  reconnected as `host_5317ac6c8c494a34`, and
  `https://remotex.photonspark.ro/` returns 200 with the deployed assets
  `index-DuZz_5GI.js` and `index-Dt1EhsG6.css` plus CSP/HSTS/security headers.
- **Left open:** none.
- **Restart needed:** none.

## 2026-08-09 — web follow-up queue and explicit steer controls
**Agent:** Codex · **Branch:** main · **Status:** done, pending deploy

- **Why:** the web composer could only steer a running Codex turn; users also
  need a deliberate FIFO follow-up that starts as a separate turn afterward.
- **Changed:** `apps/web/src/hooks/useRemotex.js` keeps queued payloads locally,
  preserves their images and turn settings, sends exactly one `turn-start` on
  each idle transition, retains unsent items through WebSocket reconnects, and
  removes an item only after the relay acknowledges it with the user-message
  echo so a concurrent-turn rejection cannot lose the prompt.
- **Changed:** `Composer.jsx`, `SendOrStopButton.jsx`, `SessionScreen.jsx`,
  `App.jsx`, and `styles.css` expose distinct Queue, Steer-now, and Stop actions
  plus a removable FIFO preview that uses the existing responsive theme tokens.
- **Changed:** `README.md` and `apps/web/README.md` document that steering is a
  Codex RPC while queuing is intentionally client-side, matching Codex TUI.
- **Verified:** installed Codex 0.147 schema and `/tmp/codex` source both confirm
  `turn/steer` exists and no queue RPC exists; 84 web tests, ESLint, production
  Vite build, 196 service tests, and `git diff --check` pass. A disposable live
  app-server probe could not finish initialization under a temporary
  `CODEX_HOME`; no daemon/Codex wire behavior was changed by this unit.
- **Left open:** none.
- **Restart needed:** relay rebuild (web assets are baked into it). The prior
  Dark/White/High Contrast theme release is already deployed and healthy.

## 2026-08-09 — transcript typography: prose in Inter, mono for code
**Agent:** Claude Fable 5 · **Branch:** main · **Status:** committed, verified locally, NOT deployed

Owner said the session view "looks mid" and picked a direction from options:
hybrid typography, plus three specific fixes. Session-list hierarchy was
offered and **declined** — left alone on purpose.

- **Diagnosis:** everything in the transcript was 12px JetBrains Mono in
  three greys, so a user message, a reasoning trace, a shell command and a
  stack trace all carried identical weight. `--sans` was already declared
  and the `md-*` prose rules already used it; reasoning and commands did not.
- **I was wrong about contrast, and measured it rather than keep asserting
  it.** `--ink-dim` is **7.74:1** on `--bg`, and 5.25:1 even at the 0.8
  opacity `.thinking-preview` used — all passing AA. The legibility problem
  was **italic monospace at 12px for prose**, not colour. So the change is
  tone/weight, not a contrast lift. New `--ink-soft` (#b4c8de dark / #2c3d52
  light) sits between chrome and reply text: 11.6:1 and 10.6:1.
- **Changes** (`EventRow.jsx`, `styles.css`):
  - Reasoning: italic dropped, Inter 13.5px, `--ink-soft`, 72ch measure.
  - Tool rows restructured. Status was `margin-left: auto`, i.e. right-aligned
    ~600px from its command — at this column width you could not tell which
    command failed. Now `● shell · failed 127` on the header, colour-coded
    (err/ok/run), and the command gets **its own full-width mono line**.
  - `exit 1` → `failed 1` / `ok` / `running`: outcome in words reads at a
    glance where an exit code needs a beat.
  - `middleTruncate()` for commands (exported, unit-tested). Tail truncation
    hid the payload — path, flags, redirect are usually at the end.
  - `--measure: 72ch` applied to prose (`.md-p`, `.md-h`, `.md-list li`,
    thinking body). Code, output and diffs are deliberately exempt.
  - Composer chips to one row: `flex-direction: column` → baseline row.
    **44px → 24px**, measured in-browser. They read
    "default / medium / default" almost always.
- **Verified:** 84/84 vitest, eslint clean, Vite build clean. Then rendered
  against **real transcript data without deploying** — `vite preview` on the
  built dist with a throwaway config proxying `/api` + `/ws` to the relay
  container (`172.21.0.3:8080`). Reuse that trick; it beats redeploying to
  look at CSS. Confirmed in both themes: 40 `.tool-cmd` lines, 42 `.ok` and
  7 `.err` statuses, computed style on the reasoning body = `Inter 13.5px
  font-style:normal rgb(180,200,222) max-width:648px`.
- **Shared-tree care (I-018):** another agent was editing `styles.css` live
  (a `.turn-queue` feature) while I was in it. Nothing was clobbered, and I
  staged **only my own hunks** — rebuilt the file from `HEAD` with just my
  edits, staged that, then restored the shared working copy. Verified: 0
  `turn-queue` rules in the commit, all 6 still in the working tree. Their
  `Composer.jsx` / `App.jsx` / `useRemotex.js` edits are untouched and
  uncommitted.
- **NOT deployed.** The owner's session had a live turn with a pending
  command approval; recreating the relay drops the daemon socket under it.
  Their call when to ship.

## 2026-08-09 — coherent dark, white, and high-contrast web themes
**Agent:** Codex · **Branch:** main · **Status:** done, pending deploy

- **Why:** the web app exposed dark/white switching, but several later RGB
  tints bypassed the shared tokens and there was no explicit high-contrast
  mode.
- **Changed:** `apps/web/src/styles.css` now defines Dark, White, and High
  Contrast from one complete token set. White uses AA text colors and visible
  3:1 control borders; High Contrast uses black/white surfaces, bright semantic
  colors, stronger borders, and no decorative shadows. Focus-visible rings and
  native `color-scheme` are consistent across all three.
- **Changed:** `apps/web/src/util/theme.js` cycles all three modes, honors
  `prefers-contrast: more` before color-scheme when no choice is saved, and
  keeps browser chrome color aligned. `ThemeToggle.jsx` uses accessible D/W/HC
  marks and announces the current and next theme without emoji.
- **Verified:** all sampled text/action combinations exceed WCAG AA (the
  lowest is White warning text at 4.62:1); 82 web tests, ESLint, production
  Vite build, and `git diff --check` pass.
- **Left open:** none.
- **Restart needed:** relay rebuild (web assets are baked into it).

## 2026-08-09 — deploy settled current-main web bundle
**Agent:** Codex · **Branch:** main · **Status:** deployed

- **Why:** the header-popover/jump-to-latest fix landed immediately after the
  inventory rollout. Its entry correctly said not deployed at that moment;
  after the inventory checks passed there was no reason to leave production
  one web commit behind `main`.
- **Changed:** rebuilt `remotex/relay:local` from the clean settled tree and
  recreated only `remotex-relay-1`. Postgres, SparkTunnel, the managed Codex
  server, Android, and Apple were untouched.
- **Verified:** 81 web tests, ESLint, and the production build pass. The relay
  is healthy with no host port binding, SparkTunnel is running, the daemon
  automatically reattached after the expected brief 1006/edge-502 window,
  the public asset name matches the fresh bundle, and public inventory WSS
  completes `inventory-ready` plus ping/pong.
- **Left open:** I-019 and I-020 remain as documented; neither blocks the
  deployed sidebar transport.
- **Restart needed:** none; deployment complete.

## 2026-08-09 — post-deploy GitHub and dependency triage
**Agent:** Codex · **Branch:** main · **Status:** done

- **Why:** the push reported nine Dependabot alerts, including four marked
  high, so release readiness needed a runtime-impact check rather than
  dismissing the banner.
- **Verified:** GitHub has no open repository issues. The last completed CI
  before the concurrent web follow-up was green; the newer follow-up CI was
  still running when checked. `npm audit --omit=dev --json` reports zero
  production dependency vulnerabilities, so the deployed Python relay/static
  web bundle does not execute an affected Node package.
- **Left open:** I-020 records the build/dev dependency alerts. Vite/esbuild's
  complete fix is a semver-major Vite upgrade and should be handled as a
  focused change.
- **Restart needed:** none.

## 2026-08-09 — header popover z-index + jump-to-latest pill
**Agent:** Claude Fable 5 · **Branch:** main · **Status:** fixed, verified in a real browser; NOT deployed

Two owner-reported web bugs.

- **Daemon popover was buried.** Hovering "connected" showed the dropdown
  behind the panels. Cause was not the popover's own `z-index: 50` —
  `.dashboard-layout > * { position: relative; z-index: 1 }` makes every grid
  child its own stacking context at the *same* level, so `<main>`/the
  sidebars (later in the DOM) paint over the header and nothing inside the
  header can climb out. Fix: `.dashboard-header { z-index: 2 }` — one
  declaration, and it wins on source order at equal specificity.
  **Anything else that must escape the header needs the header raised, not
  the child** — remember this before adding another header dropdown.
- **Jump-to-latest pill: green dot removed, stuck-visible fixed.**
  - The dot only ever meant "a turn is running", which the `✳ Working…` row
    directly above it and the header status pill already say. Its
    `.streaming` class had no CSS at all. Deleted both.
  - The pill lingered at the tail because `atBottom` was event-driven only.
    Growing content fires no scroll event (`overflow-anchor: none` is set on
    purpose), and the click handler's `scrollTo({behavior:'smooth'})` animated
    toward the `scrollHeight` captured at click time — a delta landing
    mid-flight left it short of the real bottom, so the pill stayed. Now:
    one `measure()` feeds both the scroll handler and the post-commit effect,
    the follow decision reads a ref captured *before* the content grew (a
    single big delta can exceed the 140px slack in one commit and would
    otherwise be misread as "user scrolled up"), and the click jumps
    instantly instead of animating.
- **Verified:** `isNearTail` extracted and unit-tested (4 cases incl. the
  not-scrollable case) — 81/81 web tests, eslint clean, Vite build clean.
  For the CSS I built a static harness with the real compiled stylesheet and
  hit-tested `elementFromPoint` at three points across the popover under
  Playwright. **The check was proven, not assumed:** against the pre-fix CSS
  all three points hit `.hosts-sidebar`; with the fix all three are inside
  the popover. Screenshots confirm (before: a 5px sliver; after: full panel).
  My first harness run was a false pass — the `cp` of the stylesheet had
  failed, so it tested with no CSS at all. Worth re-checking that a harness
  actually loads what you think it does.
- **NOT deployed on purpose.** The relay image bakes the web bundle at build
  time, so this needs a rebuild — and a rebuild from the current tree would
  also push the inventory-retry change from I-018 (another agent's, on `main`
  but never deployed) into production. Asked the owner rather than shipping
  someone else's work for them.

## 2026-08-09 — live sidebar inventory WebSocket and resilient resync
**Agent:** Codex · **Branch:** main · **Status:** done, deployed

- **Why:** make host/session rows update immediately when Codex creates,
  renames, archives, restores, or deletes a thread, and make the sidebar heal
  cleanly after browser, relay, daemon, or network reconnects.
- **Changed:** `services/relay/handlers/ws_inventory.py`, `hub.py`,
  `ws_daemon.py`, `hosts.py`, and `app.py` add an authenticated,
  owner-scoped inventory WebSocket, bounded fan-out, host/thread
  invalidations, and serialized daemon online/offline handoffs so stale socket
  cleanup cannot mark a replacement offline.
- **Changed:** `services/daemon/adapters/shared.py`, `admin.py`, and
  `client.py` observe the real Codex lifecycle methods globally (including
  local shell/desktop clients), coalesce notifications without blocking the
  JSON-RPC reader, and request authoritative thread lists in recency order.
- **Changed:** `apps/web/src/api/inventorySocket.js` and
  `hooks/useRemotex.js` keep one per-tab notification socket alive with
  hello-frame authentication, ready/heartbeat deadlines, exponential jittered
  reconnect, visibility/online wakeups, debounced dirty resync, retryable REST
  snapshots, and generation checks that discard out-of-order responses.
- **Protocol evidence:** refreshed `/tmp/codex`, inspected the current v2 wire
  definitions, and drove a real Codex 0.147 app-server. Observed
  `thread/started`, `thread/name/updated`, `thread/status/changed`,
  `thread/settings/updated`, `thread/archived`, `thread/unarchived`,
  `thread/closed`, and `thread/deleted` shapes before encoding fixtures.
- **Verified:** 196 service tests, Ruff, Python compilation, 77 web tests,
  ESLint, production Vite build, and `git diff --check` pass. Public HTTPS has
  the expected security headers; public `/ws/inventory` completes hello,
  ready, ping/pong, and reconnect. A materialized live Codex probe appeared in
  REST after its WebSocket invalidation, renamed in place, disappeared on
  archive, and reappeared on unarchive. Exact disposable probe data was
  removed afterward.
- **Left open:** I-019 — the installed Codex 0.147 release partially deletes
  a thread, then fails its own state cleanup because migration 42 removed
  `agent_jobs`; failed upstream requests emit no `thread/deleted` event.
- **Restart needed:** none. `remotex-relay-1` was rebuilt/recreated and is
  healthy; `remotex-daemon` was restarted, reattached to the shared Codex
  socket and relay, and SparkTunnel remained online. Android and Apple were
  not touched by this work.

## 2026-08-09 — native parity: CI compile fixes + verification results
**Agent:** Claude Fable 5 · **Branch:** main · **Status:** done, both workflows green

Follow-up to the native-parity entry below. That one said iOS was checked
only for brace balance; here is what actually happened when a real compiler
saw it.

- **iOS did not compile on the first push.** Three errors, two distinct
  causes, both invisible to any local check I have (no Xcode on this box):
  - `Sparkline` had a local `func point(_:)` inside the `GeometryReader`
    ViewBuilder closure. `return` is illegal in a result-builder body —
    hoisted it to `points(in: CGSize) -> [CGPoint]` on the struct.
  - `Text("•").foregroundStyle(.remotexMuted)` — `foregroundStyle` is
    generic over `ShapeStyle`, and implicit member lookup will not find
    custom statics declared on `Color` (the built-ins like `.red` work only
    because they are declared as `extension ShapeStyle where Self == Color`).
    Named the type. **`tint` takes `Color?`, so `.remotexAccent` is legal
    there** — that asymmetry is why `ContentView`/`PendingPromptsView`
    compiled and `Markdown.swift` did not.
- **The pbxproj registration worked** — the CI compile line lists all four
  new files, so the hand-written PBXBuildFile/PBXFileReference/Sources
  entries are correct.
- **Verified, not assumed:**
  - Release workflow green; nightly IPA 396 KB → 595 KB, APK republished.
  - CI workflow green (backend pytest, web Vite build).
  - Android `compileDebugKotlin` + `testDebugUnitTest` clean locally.
  - `apps/web`: 77/77 vitest.
- **No relay redeploy** — this batch touched only `android/` and `apple/`.
  Caveat: see I-018; someone else's web change did land on `main` in my
  commit and is *not* in the built image, so `main` and prod now differ on
  the web bundle. Their call when to ship it.
- **Filed I-018:** I staged with `git add -A` in a tree several agents
  share, and swept another agent's in-flight inventory-retry work into
  `b283b92`. Nothing lost, `main` is healthy, and I did not rewrite pushed
  history to unpick it. Stage explicit paths in this repo.

## 2026-08-09 — native parity batch: themes, telemetry, transcript rendering on Android + iOS
**Agent:** Claude Fable 5 · **Branch:** main · **Status:** done; iOS compile-verified via CI only

- **Why:** owner asked for themes, telemetry, workspace browser, time
  dividers, jump-to-latest, markdown + syntax highlighting, collapsible
  reasoning, edit diffs and Claude-Code tool rows "all where they aren't".
  Plan in `ToDo.md` ("native client parity"). Recon corrected two wrong
  assumptions: Android ALREADY had a markdown renderer, collapsible
  reasoning/tool rows, a workspace file browser, and telemetry **polling
  into state with no UI**; iOS had none of it.
- **Android:**
  - `theme/Theme.kt` — light + dark `RemotexPalette` behind
    `LocalPalette`. **The trick worth remembering:** the existing token
    names (`Ink`, `InkDim`, `Amber`, …) were redefined as
    `@Composable @ReadOnlyComposable get()` over the CompositionLocal, so
    ~20 files became theme-aware with zero import changes. Only two
    non-composable helpers needed `@Composable` added (`inlineFormat`,
    `labelFor`).
  - `MainActivity` — theme preference (system/light/dark) in prefs; top bar
    gains theme + telemetry buttons (`RemotexBar`).
  - `ui/Highlight.kt` — NEW small tokenizer (comments/strings/numbers/
    shared keyword set) feeding markdown code fences. No grammar engine;
    unknown text stays body-coloured.
  - `events/DiffView.kt` — NEW per-file diff cards (+N/−M, tinted lines,
    collapse >14, tail-follow while streaming).
  - `events/AgentGroup.kt` — tools now `● name(arg) · meta` with a state
    dot and a `⎿` output block (edits route to DiffView); reasoning is
    `✳ Thinking…` → folded `✳ Thought · headline`.
  - `events/EventList.kt` — jump-to-latest FAB (live dot while pending) +
    a "── new messages ──" divider. **Divider note:** Kotlin `UiEvent`
    only carries `replayed` on `Reasoning`, so sniffing it was unreliable;
    the ViewModel now tracks `historyEventCount` (history rows sit at the
    front) and the boundary is derived from that — precise, no model churn.
  - `screens/telemetry/TelemetryPanel.kt` — NEW bottom sheet over the
    telemetry state that was already being polled: CPU/RAM/GPU(s)/network
    cards, Canvas sparklines with the same 1.3×-peak autoscale as web.
- **iOS:**
  - `Theme.swift` — dynamic `UIColor { traits }` tokens, so light mode is
    automatic; `ThemeSetting` cycles system→dark→light, persisted;
    dropped the hardcoded `.preferredColorScheme(.dark)`.
  - `Markdown.swift` — NEW block splitter + inline via
    `AttributedString(markdown:)` + the highlighter ported from Android.
  - `DiffView.swift` — NEW, same contract as web/Android.
  - `ContentView.swift` — `StreamRow` rewritten into UserBubble /
    ThinkingRow / ToolRow (● dot, ⎿ output, diff for edits) / markdown
    agent rows; jump-to-latest overlay; theme + Files + Telemetry toolbar
    buttons.
  - `TelemetryView.swift` + `WorkspaceFilesView.swift` — NEW; VM gained a
    3s telemetry poll with a 40-sample ring and `loadWorkspace` (dirs
    first, dotfiles last). Files view is **read-only** — rename/delete
    need the fs mutation endpoints and I won't ship destructive actions
    untested on a client nobody has run.
  - Four new files registered in `project.pbxproj` by hand (validated: no
    dangling refs, all three sections wired per file).
- **Verified:** Android `compileDebugKotlin` clean + 25 unit tests. iOS:
  brace/paren balance per file only — **no Xcode here**; the push runs the
  macOS job. Web untouched.
- **Left open:** iOS file rename/delete/download; Android image attach;
  per-event timestamps for true "2h ago" dividers on native (web has them
  because the daemon sends `ts` and the web model keeps it).
- **Restart needed:** none server-side. `cd android && ./build.sh install`
  and a fresh IPA sideload to actually see any of this.

## 2026-08-09 — phone-IA batch (web) + native parity: previews everywhere, iOS thread list & pickers
**Agent:** Claude Fable 5 · **Branch:** main · **Status:** done, deployed; iOS compile-verified via CI

- **Why:** owner asked for buckets A (phone-IA polish) and B (native
  parity) done properly for both platforms. Recon finding: iOS had NO
  thread list at all — saved chats were unreachable from its UI, so "iOS
  prefetch" required building the list first.
- **Web (A):**
  - Drawers ≤640px are bottom sheets: rounded top, drag-handle
    (`SheetHandle` — tap or >70px swipe-down dismisses), 82dvh height,
    dashboard visible behind. **Gotcha:** the app has a global
    `border-radius: 0 !important` square-off inside `.dashboard-layout`
    (~line 1409) with an opt-in list — new round things (sheets, tool
    dots, jump pill) must be added there or they silently render square.
  - Compact session header: the static facts row folds while reading
    history (EventStream lifts at-bottom state via `onAtBottomChange`).
  - Composer focus mode (≤640): `:has(.prompt:focus)` hides the chip row.
  - Touch-target floor 44px; approval buttons ≥48px in the sheet.
  - PWA: `viewport-fit=cover` + safe-area padding on the composer;
    `overscroll-behavior-y: none` kills pull-to-refresh.
  - **Broke prod for a few minutes:** re-referenced `closeRightView`
    which had been deleted as dead code after the telemetry-× removal —
    blank page post-login. eslint/build DID NOT catch it (runtime-only).
    Restored. Lesson: grep for the symbol before re-adding a reference.
- **Android (B):** `getThreadPreview` in RelayClient + instant paint on
  resume (`paintPreview` → `preview_*` rows shown while the session
  opens; HISTORY commit strips them). Compile + 25 unit tests green.
- **iOS (B):** NEW thread list ("Recent sessions" under Hosts, loaded
  with the first online host via `loadHostExtras`), `resumeThread` with
  parallel preview fetch + instant paint, `listThreads`/`threadPreview`/
  `listHostModels` in RelayClient, model/effort/permissions **pickers**
  (Menu chips above the composer, efforts follow the selected model),
  `sendTurn` now carries model/effort/permissions. History commit strips
  preview rows. Braces balanced; macOS CI is the compile check.
- **Verified:** web — sheet computed style asserted via Playwright
  (bottom-anchored, 640px, 14px radius, handle visible) + screenshot;
  69 vitest + eslint + build; relay redeployed. Android — compile +
  tests. iOS — CI on this push.
- **Restart needed:** already done (relay). Android install + iOS
  sideload when owner is at the devices.

## 2026-08-09 — deploy daemon latency popover
**Agent:** Codex · **Branch:** main · **Status:** deployed

- **Why:** complete the daemon-ping work logged below after the concurrent web
  edits settled.
- **Changed:** rebuilt and recreated only the relay/web container from the
  settled tree; the daemon had already been restarted with the ping responder.
  The temporary clean deployment worktree was removed afterward; it contained
  no user-authored files and is not recoverable or needed.
- **Verified:** full services suite 176 passed; Ruff clean; web ESLint, 69
  tests, and production build clean. Relay/Postgres are healthy, daemon and
  SparkTunnel are online, the deployed bundle contains both the latency
  popover and the concurrent transcript changes, and an authenticated live
  request completed the public relay→daemon→relay ping with HTTP 200.
- **Left open:** none.
- **Restart needed:** none.

## 2026-08-09 — correct stale multi-GPU web deployment
**Agent:** Codex · **Branch:** main · **Status:** deployed

- **Why:** a concurrent relay rebuild completed before the multi-GPU web diff,
  so the committed source was correct while the running image still rendered
  only the legacy first-GPU card.
- **Changed:** rebuilt `remotex/relay:local` from the settled tree and recreated
  only `remotex-relay-1`; Postgres, SparkTunnel, the host daemon, and managed
  Codex process were left running.
- **Verified:** public and container asset hashes match the fresh production
  build; the deployed JavaScript contains the per-GPU mapper, VRAM, and
  temperature rendering; the authenticated live payload and every retained
  history sample contain both GPUs; public HTTPS is 200, relay/Postgres are
  healthy, the daemon is active, and post-rollout relay logs have no errors.
- **Left open:** I-017 remains the separate non-NVIDIA collector limitation.
- **Restart needed:** none; deployment complete.

## 2026-08-09 — render every reported GPU in web telemetry
**Agent:** Codex · **Branch:** main · **Status:** done, deployed

- **Why:** the daemon and relay carried both NVIDIA GPUs, but the web reducer
  collapsed telemetry to the legacy first-GPU alias.
- **Changed:** `apps/web/src/hooks/useRemotex.js` retains a rolling history per
  GPU with compatibility for older singular payloads;
  `components/TelemetrySidebar.jsx` renders one complete card per GPU with
  utilization, model, VRAM, temperature, and its own sparkline. Focused reducer
  and server-rendered component tests cover two GPUs and the legacy shape.
- **Verified:** the live relay payload contains two GPUs; focused tests 28
  passed; full web suite 69 passed; ESLint and production build clean. Existing
  responsive sidebar/drawer stacking needs no CSS change. The concurrently
  rebuilt public relay serves the tested bundle.
- **Left open:** I-017 tracks non-NVIDIA collection. Android and Apple were not
  inspected or changed.
- **Restart needed:** none; relay/web was already rebuilt and deployed.

## 2026-08-09 — chat transcript rebuilt Claude-Code style: diffs, tool rows, thinking, images
**Agent:** Claude Fable 5 · **Branch:** main · **Status:** done, deployed

- **Why:** owner: chat UI is bad, attached images invisible, wants
  reasoning/tools/diffs rendered the way Claude Code / Codex render them.
  Root finding on images: `EventRow` had thumbnail markup but NOTHING
  ever set `imageUrls` — the relay echo only carries `image_count` and
  the sender discarded its local copies on send.
- **Changed (web only):**
  - `DiffView.jsx` — NEW. Parses the daemon's `_format_changes` output
    (verb+path summary in `args.command`, unified diff in `output`,
    multi-file split on `--- /path` separators) into per-file cards:
    verb + tail-truncated path (`<bdi>` inside the RTL clip — bare RTL
    migrates the leading slash again), `+N −M` counts, green/red-tinted
    lines via theme tokens, dim hunks, collapse >14 lines (tail-follow
    while streaming). `item-patch` streams straight into it.
  - `EventRow.jsx` — rewritten. Tools render as `● name(first-line)`
    headers (dot: pulsing amber running / green ok / red on error or
    nonzero exit) with a `⎿`-gutter output block that tail-follows while
    running and head-truncates after. Reasoning renders as `✳ Thinking…`
    live, folding to one dim `✳ Thought · headline` line on completion
    (markdown markers stripped from headlines). `tool === 'edit'` →
    DiffView. User bubbles show attached-image thumbnails with a
    fullscreen lightbox portal. Replay-gap row preserved.
  - `useRemotex.js` — `sentImagesRef`: dataUrls stashed under the
    `client_message_id` at send/steer time and attached when the relay
    echo returns that id as `item_id` (so ONLY the sending client sees
    pixels; peers/history still get the count — shipping bytes back is a
    daemon feature, not done). Tool events carry `exitCode`; the id is
    now minted in the hook and passed to the socket. `pendingSinceMs`
    for the working timer.
  - `EventStream.jsx` — `✳ Working… Ns` elapsed row at the tail while a
    turn runs.
  - `styles.css` — tool/thinking/diff/lightbox/working styles, all
    token-based (verified in light mode); ≤640px sizes.
- **Verified:** eslint clean, 69 vitest, build clean. Live e2e on the
  rig (real codex turn): attached a generated PNG + asked for a color +
  an apply_patch edit — screenshot shows the thumbnail in the bubble,
  two collapsed Thought lines, `shell(...)` with ⎿ output, and a real
  diff card (`update /tmp/probe-ws/notes.txt  +1`, green add line).
  Mobile (412px) spot-checked: path correct, args ellipsize, diff fits.
  Relay rebuilt + recreated.
- **Left open:** images survive only on the SENDING client — after
  resume they fall back to the count chip. Making them durable means the
  daemon persisting/serving the temp image files; noted here as the
  follow-up. History replay never contains fileChange items (rollout
  parser doesn't extract them) — diffs appear live only; extracting
  `patch_apply_end` events from rollouts would fix that.
- **Restart needed:** already done (relay).

## 2026-08-09 — daemon ping popover (implementation complete, relay deploy waiting)
**Agent:** Codex · **Branch:** main · **Status:** partial

- **Why:** hovering/focusing the header connection state should list every
  registered daemon and its current end-to-end round-trip latency.
- **Changed:** `services/{daemon/client.py,relay/app.py,relay/handlers/{hosts.py,ws_daemon.py}}`
  add an authenticated ping request/response; `apps/web/src/{App.jsx,api/relayClient.js,components/DashboardHeader.jsx,styles.css}`
  add the hover/focus daemon list and browser-measured milliseconds; focused
  tests cover the daemon reply and owned-host route.
- **Verified:** full services suite 176 passed; Ruff clean; web ESLint, 66
  tests, and production build clean. Daemon restarted and reattached to the
  relay/shared Codex socket successfully.
- **Left open:** relay rebuild/deploy is intentionally waiting because another
  agent has uncommitted chat-transcript work in `App.jsx`/`styles.css` and an
  untracked `DiffView.jsx`; do not ship or overwrite that work half-finished.
- **Restart needed:** relay rebuild after the concurrent web work settles.

## 2026-08-09 — redact private deployment metadata from current docs
**Agent:** Codex · **Branch:** main · **Status:** partial

- **Why:** deployment-specific values belong in ignored configuration, not the
  public repository.
- **Changed:** `WorkLog.md` — replace the private deployment hostname with
  generic wording. The README shared-mode guide already uses placeholders.
- **Verified:** no SparkTunnel connector-token pattern exists anywhere in Git
  history. The hostname is removed from the current tree.
- **Left open:** a concurrent agent had already pushed and tagged the commit
  containing the old wording; purging that historical occurrence requires an
  explicitly approved history rewrite of `main` and `v0.1.0`.
- **Restart needed:** none (documentation only).

## 2026-08-09 — releases usable from a phone: runtime relay URL + release pipeline fixes
**Agent:** Claude Fable 5 · **Branch:** main · **Status:** done

- **Why:** owner wants tagged releases carrying the APK and a
  sideloadable IPA. The pipeline already existed (`release.yml`: nightly
  prerelease on main pushes, stable on `v*` tags) but had two defects
  that made published APKs useless and the nightly page a junk drawer.
- **Findings + fixes:**
  - **Published APKs baked `10.0.2.2`** (the emulator loopback) because
    the workflow never passed `-PrelayUrl` — every release APK could not
    reach any real relay, and Android had NO runtime way to change it
    (`MainActivity` used `BuildConfig.RELAY_URL` directly; only the
    token was editable).
    - Android: relay URL is now a runtime setting — new `RelayUrlField`
      on the Hosts screen (commits on Done/focus-loss, not per
      keystroke), persisted in `remotex.settings` SharedPreferences,
      `viewModel(key = relayUrl)` so committing a new URL rebuilds the
      networking stack in place. Build-time URL is only the default.
    - Workflow: bakes repo variable `RELEASE_RELAY_URL` (set to the
      public relay via `gh variable set`) into release APKs; forks
      without the variable keep the gradle default.
  - **Nightly release had accumulated 54 assets since April** — asset
    names carried date+sha, so every push added a pair forever. Nightly
    assets are now the stable names `remotex-nightly.apk` /
    `remotex-nightly.ipa` (clobbered each push; commit sha lives in the
    release body) → permanent download URLs. Tagged releases keep
    versioned names. Purged the 54 old assets one-time.
- **Verified:** Android `compileDebugKotlin` clean (including a caught
  bug: `mutableStateOf` without `remember` in `setContent` would reset
  the URL on recomposition); workflow YAML parses; repo variable set.
  Release run on the next push is the end-to-end check.
- **Restart needed:** none server-side. The published APK/IPA update on
  the next main push; stable installs come from `git tag vX.Y.Z && git
  push --tags`.

## 2026-08-09 — deploy web brand/status cleanup
**Agent:** Codex · **Branch:** main · **Status:** deployed

- **Why:** finish the header cleanup logged below by putting the rebuilt web
  bundle on the live relay.
- **Changed:** rebuilt `remotex/relay:local` and recreated only
  `remotex-relay-1` with the SparkTunnel Compose override.
- **Verified:** relay and Postgres healthy, SparkTunnel running, daemon active,
  public HTTPS returns 200, and the running container contains the new
  `.brand-logo` CSS.
- **Left open:** no automated visual after-screenshot; unrelated concurrent
  Android work remains untouched.
- **Restart needed:** none.

## 2026-08-09 — document and verify the persistent shared daemon
**Agent:** Codex · **Branch:** main · **Status:** done

- **Why:** make the live shell-to-web shared-mode setup understandable and
  confirm the deployment host is ready for hands-on testing.
- **Changed:** `README.md` — show both Codex transports in the architecture and
  add the shortest shared-mode status, shell test, reconnect, and explicit
  `stdio` fallback workflow without publishing private deployment values.
- **Verified:** the user daemon is active and enabled, its unit is running, and
  user lingering is enabled, so it remains available after logout/reboot.
- **Left open:** none; no daemon, relay, web, Android, or Apple code changed.
- **Restart needed:** none (documentation only).

## 2026-08-09 — replace fake cursor logo and quiet connection status
**Agent:** Codex · **Branch:** main · **Status:** done, deployment pending

- **Why:** the web header rendered the brand as a thin cursor bar and gave the
  connection state the visual weight of a primary control.
- **Changed:** `apps/web/src/components/DashboardHeader.jsx`,
  `apps/web/src/screens/LoginScreen.jsx`, and `apps/web/src/styles.css` — reuse
  the shipped Remotex icon in both brand lockups, reduce connection state to a
  borderless dot plus label, expose it as a polite status, and reserve green
  for the actually connected state.
- **Verified:** `npm run test:run` (66 passed), `npx eslint src`,
  `npm run build`, and `git diff --check` all clean. The user-provided header
  screenshot was inspected; no browser-based after screenshot was captured.
- **Left open:** unrelated concurrent Android work in `MainActivity.kt` was
  present and left untouched.
- **Restart needed:** rebuild and recreate relay/web.

## 2026-08-09 — deploy shared Codex transport through SparkTunnel
**Agent:** Codex · **Branch:** main · **Status:** deployed

- **Why:** put the shared local Codex control plane live at the configured
  public hostname without interrupting the active shell session or exposing
  host ports.
- **Changed:** rebuilt and recreated only the relay/web container, set the
  private daemon config to `mode = "shared"`, and restarted only the
  `remotex-daemon` user unit. Secrets remain outside Git in ignored/private
  files with mode `0600`. Android and Apple were not touched.
- **Verified:** Postgres and relay are healthy; SparkTunnel is running with no
  published ports; the daemon is online over the shared Unix socket and has no
  direct Codex child; the managed app-server PID remained unchanged; public
  HTTPS returns 200; the authenticated public API reports the host online; a
  public WSS attach to the current shell thread hydrated its active turn and
  items; the temporary verification session was closed and left no open row;
  current daemon and relay logs contain no post-rollout errors.
- **Left open:** the still-running managed app-server is 0.144.3 while the
  installed managed CLI is 0.147.0. Restart it only after the active Codex
  session ends. I-014 remains blocked because SparkTunnel 0.2.0 does not expose
  a trustworthy visitor IP for per-client rate limiting.
- **Restart needed:** none now; later run `codex app-server daemon restart`
  after ending the active shell session to pick up app-server 0.147.0.

## 2026-08-09 — shared Codex control plane, reconnect reconciliation, and web repair
**Agent:** Codex · **Branch:** main · **Status:** done, deployment pending

- **Why:** let a shell `codex` TUI and Remotex observe/control the same local
  thread without spawning a second app-server, while retaining isolated stdio
  as the portable default.
- **Changed:**
  - `services/daemon/adapters/shared.py`, `stdio.py`, `admin.py`, `factory.py`,
    `services/daemon/client.py` — one host-level JSON-RPC WebSocket over Codex's
    Unix socket, request/thread routing, hot-resume buffering and authoritative
    active snapshots, local user-message forwarding/dedupe, delayed fallback
    for unsupported shared server requests, socket-failure relay recycling,
    and no ownership of the managed Codex process.
  - `services/relay/{hub.py,handlers/ws_daemon.py}` — retain shared turn locks
    across relay loss, reconcile them from `thread/resume`, but invalidate
    adapter-owned prompt ids and send an empty authoritative prompt snapshot so
    Codex can replay surviving requests under fresh ids. This corrects the
    earlier reconnect entry's statement that prompt ids themselves survive.
  - `apps/web/src/hooks/useRemotex.js` — authoritative resumed items repair
    deltas missed during an outage; shared idle/active reconciliation repairs
    composer and prompt state. `App.jsx` drops dead `closeRightView` (I-016).
  - Config, installer, README/AGENTS/CLAUDE, tests, I-015, and ToDo document the
    opt-in Unix-only shared mode, default socket, standalone/npm caveat, and
    the external-clock upstream limitation. `stdio` remains the default.
  - Android and Apple were explicitly excluded from this follow-up; their
    independently owned work was not staged or modified here.
- **Verified:** Codex 0.147 source plus matching running 0.144.3 source; real
  WebSocket-over-UDS initialize/list/resume and active-turn probes; full Python
  suite **174 passed** + Ruff; web ESLint clean, **66 tests**, production build;
  disposable-Postgres relay↔daemon↔client E2E passed; focused tests prove relay
  loss, shared socket death, prompt invalidation, hot-resume ordering, and
  stdio compatibility; `git diff --check` and installer shell syntax clean.
- **Left open:** running app-server is 0.144.3 while the managed CLI is 0.147.0;
  restart it only after this active Codex session ends. I-014 remains blocked
  on SparkTunnel supplying a trustworthy visitor IP.
- **Restart needed:** rebuild relay/web and restart `remotex-daemon`; do not
  restart the managed Codex app-server during this session.

## 2026-08-09 — native clients catch up: history paging (regression fix) + iOS stop/steer/patch
**Agent:** Claude Fable 5 · **Branch:** main · **Status:** done; iOS compile-verified via CI only

- **Why:** tail-first replay (phases 1–2) REGRESSED both native apps —
  they treated `history-begin/end` as no-ops, so saved chats showed only
  the last 2 turns with no way to load more. Owner asked for both apps;
  will test iOS later.
- **Changed — Android:**
  - `RemotexViewModel.kt` — history state (`historyHasMore/Oldest/Loading`
    + `historyTailTick`), replayed items buffer between begin/end and
    commit as ONE state update (prepend + id-dedupe), `history-chunk-*`
    handled, `loadOlderHistory()` sends `history-more`; reset on open.
  - `EventList.kt` — tail commit jumps to bottom exactly once
    (`historyTailTick`), live events follow ONLY when already near the
    bottom (was: yank on every event), scroll-to-top triggers the next
    page via `snapshotFlow { firstVisibleItemIndex }`, "older turns" row.
    LazyColumn keys give prepend anchoring for free.
  - `SessionScreen.kt` / `RemotexApp.kt` — `onLoadOlder` threaded.
- **Changed — iOS:**
  - `SessionSocket.swift` — `sendInterrupt`, `sendSteer`,
    `sendHistoryMore`.
  - `RemotexViewModel.swift` — same buffered history commit
    (`commitHistoryBatch`, tail/chunk ticks + `historyAnchorId` for
    prepend restore); `interruptTurn()`; `sendPrompt()` steers when a turn
    is running; **`item-patch` restored** (the approvals-rewrite dropped
    it); item builder extracted to `makeStreamItem` so buffered replay
    reuses it.
  - `ContentView.swift` — top pager row (`onAppear` → load), autoscroll
    keyed on `stream.last?.id` so prepends can't yank the view, tail-tick
    bottom jump + chunk-tick anchor restore, stop button while pending,
    send button stays enabled during a turn (steer).
- **Verified:** Android `compileDebugKotlin` clean + 28 unit tests green.
  iOS: braces-balance sanity only — **no Xcode on this box**; the push
  triggers the macOS CI job which compiles it. If that job goes red, fix
  forward. Owner will test the iOS app by hand later.
- **Left open:** native prefetch (press-to-preview) and iOS model pickers
  — still in `ToDo.md` phase 4 / I-012. PR #16 (iOS XCTest target) still
  unmerged; its `handle(frame:)` visibility change will need a trivial
  rebase over these edits.
- **Restart needed:** none server-side (wire protocol unchanged). Android:
  `cd android && ./build.sh install` when at the machine with the phone.

## 2026-08-09 — preserve shared turns across relay reconnects
**Agent:** Codex shared_final_review · **Branch:** main · **Status:** done

- **Why:** shared Codex turns outlive the Remotex daemon's relay socket, so the
  relay must not report a false failed completion during a reconnect.
- **Changed:**
  - `services/daemon/client.py` — advertises the configured adapter mode in the
    authenticated daemon hello.
  - `services/relay/hub.py`, `services/relay/handlers/ws_daemon.py` — bind mode
    to socket identity, preserve active turns/prompts only across shared-mode
    disconnects and shared-to-shared replacements, and retain legacy stdio/mock
    abort behavior.
  - `services/daemon/adapters/stdio.py` — shared `thread/resume` reports the
    authoritative active-turn boolean so an idle snapshot releases the relay's
    preserved lock without manufacturing a failed transcript event.
  - `services/tests/{test_hub,test_ws_daemon_binding,test_daemon_connection,test_shared_codex}.py`
    — cover socket-mode identity, stdio compatibility, shared disconnect and
    replacement preservation, hello metadata, and idle resume reconciliation.
- **Verified:** focused reconnect suite 50 passed; Ruff clean on touched Python;
  `git diff --check` clean.
- **Left open:** none for this reconnect path.
- **Restart needed:** daemon and relay rebuild.

## 2026-08-09 — shared Codex routing and hot-resume tests
**Agent:** Codex shared_mode_tests · **Branch:** main · **Status:** done

- **Why:** lock the shared socket's multiplexing, privacy, resume-ordering,
  and local-shell prompt behavior before deployment.
- **Changed:**
  - `services/tests/test_shared_codex.py` — covered out-of-order response-id
    routing, per-thread isolation, atomic thread-start claims, orphan
    unsubscribe, paused hot-resume ordering/active-turn hydration, and the
    shared-only unknown-request policy.
  - `services/tests/test_stdio_dispatch.py` — covered live `userMessage`
    forwarding with `clientId` dedupe/fallback and `clientUserMessageId` on
    ordinary `turn/start`.
- **Verified:** refreshed `/tmp/codex`, inspected Codex 0.147.0 source/schema;
  ruff clean on changed tests; focused suite 57 passed; full services suite
  165 passed; `git diff --check` clean.
- **Left open:** no real Codex process is spawned by pytest; live socket/probe
  verification remains with the core shared-mode integration unit.
- **Restart needed:** none for tests; daemon for the implementation they cover.

## 2026-08-09 — shared Codex mode config and operator docs
**Agent:** Codex shared_config_docs · **Branch:** main · **Status:** done

- **Why:** support the opt-in Unix control-socket mode without changing the
  portable `stdio` default.
- **Changed:**
  - `services/daemon/config.py` — added persisted `codex_socket_path` and
    `$CODEX_HOME`/`~/.codex` default resolution for shared mode.
  - `services/tests/test_daemon_config_toml.py` — covered custom-path TOML
    round-trip, default socket resolution, and explicit-path precedence.
  - `services/README.md`, `deploy/README.md` — documented `shared` versus
    `stdio`, Unix-only setup, Codex's plain-TUI auto-connect eligibility, and
    the custom socket setting.
- **Verified:** config test file: 25 passed; ruff clean on changed Python;
  `git diff --check` clean. Codex 0.147.0 source was refreshed and its control
  socket path plus TUI auto-connect gate were inspected. Core shared transport
  is being implemented separately and was not verified by this unit.
- **Left open:** `services/daemon/__main__.py` and `deploy/install-daemon.sh`
  mode choices are intentionally owned by the core integration agent.
- **Restart needed:** daemon after the complete shared-mode change is installed.

## 2026-08-09 — chat UX round: keyboard fix, autogrow, jump-to-bottom, titles, links, timestamps
**Agent:** Claude Fable 5 · **Branch:** main · **Status:** done, deployed

- **Why:** owner asked "what about chat's UX" — audit found 2 phone bugs
  and 7 readability issues on the session screen.
- **Changed:**
  - `index.html` — viewport gains `interactive-widget=resizes-content`;
    since Chrome 108 the on-screen keyboard OVERLAYS by default, hiding
    the composer behind it. The worst chat bug, one line.
  - `Composer.jsx` — textarea autogrows to ~5 lines (132px cap) then
    scrolls; resets after send. All text mutations routed through one
    sizing wrapper.
  - `EventStream.jsx` — sticky ↓ jump-to-latest pill when scrolled away
    from the tail (green dot while a turn streams); consecutive reasoning
    items merge into one block at render (history replay emits each
    summary part separately — the stacked "REASONING" rows); sparse
    "2h ago" dividers between groups >30 min apart.
  - `SessionScreen.jsx` — header title precedence: thread title → your
    latest message → preview → "New session". Never `session sess_…`.
  - `util/markdown.jsx` — link syntax finally parsed: http(s) targets are
    real `<a target=_blank>`, file-path targets render label-only with the
    path in the tooltip (no more 3-line path wraps).
  - `useRemotex.js` — events get `ts` (rollout turn startedAt for history
    via the daemon, arrival time live).
  - `services/daemon/adapters/stdio.py` — `_emit_history_turn` payloads
    carry `ts: turn.startedAt` (epoch s).
  - `styles.css` — user bubbles read as prose (sans 13.5px); phone bubbles
    span ~full width (94% / 100%, was 76% / 92%); `.prompt` max-height;
    `.time-divider` + `.jump-to-bottom` styles; `.md-link`.
- **Verified:** eslint clean, 62 vitest, build clean; daemon suite 28;
  phone rig screenshots — title shows the real message, `ideas.md` link
  renders label-only underlined, jump pill appears on scroll-up and
  vanishes at the tail (clicked, smooth-scrolled, hid). Both daemons
  restarted, relay rebuilt + recreated.
- **Restart needed:** already done (daemon + relay).

## 2026-08-09 — telemetry graphs + drawer close redundancy (phone feedback round 2)
**Agent:** Claude Fable 5 · **Branch:** main · **Status:** done, deployed

- **Why:** owner screenshot: sparklines looked dead (flat line drowning in
  a fixed 0-100 plot, endpoint stretched into an oval), the telemetry ×
  was off-center AND redundant with the header's right-sidebar toggle,
  and that toggle's "▥" glyph read as noise.
- **Changed:**
  - `Sparkline.jsx` — axis autoscales to ~1.3× the observed peak (hard
    ceiling still the passed max, floor 8% of full scale), so a 5% CPU
    line has shape instead of hugging the floor. Data strokes and
    endpoint dots use `vector-effect: non-scaling-stroke`; dots are
    zero-length round-capped strokes — a plain `<circle>` stretches into
    an oval under `preserveAspectRatio="none"` (that was the blob). Grid
    colors moved from dark-only rgba to `color-mix(var(--ink-dim))` so
    light theme gets a correct grid.
  - `TelemetrySidebar.jsx` — the duplicate × removed; the header toggle
    is the single open/close control. Dead `onClose` plumbing dropped
    through `RightSidebar.jsx`/`App.jsx`.
  - `DashboardHeader.jsx` — telemetry toggle icon is now an inline SVG
    pulse (heartbeat polyline, `currentColor`) instead of "▥".
- **Verified:** eslint 0 warnings, 62 vitest, build clean; phone
  screenshots (412×915, both themes) — CPU/GPU charts show real shape,
  round dots, no ×; header pulse icon renders in active/inactive states.
  Relay rebuilt + recreated.
- **Restart needed:** already done (relay).

## 2026-08-09 — phone UI fixes: full-width sheets, files drawer overhaul, header + composer
**Agent:** Claude Fable 5 · **Branch:** main · **Status:** done, deployed

- **Why:** owner sent live phone screenshots — drawers left a 52px sliver
  of page peeking through (read as broken layout), the workspace files
  drawer was unsorted with disabled-looking file rows and `↓ ren del`
  micro-buttons, a lone header icon stretched into a 120px empty box, and
  the composer stacked three rows.
- **Changed:**
  - `styles.css` — ≤640px: all drawers become 100vw sheets; scrim uses
    `var(--scrim)` (was a near-invisible bg-mix in dark). `.header-tools`
    mobile `flex: 1 1 auto; max-width:120px` → `flex: 0 0 auto` (that was
    the empty-box bug). Drawer rows/path-bar/action-button styles
    rebuilt: 44px touch rows, square icon actions, `.ws-drawer-cwd`
    tail-truncates via `direction: rtl`.
  - `WorkspaceFilesDrawer.jsx` — entries sorted (shared
    `util/fsEntries.js` comparator, also used by JumpPicker now); file
    rows are tappable (tap = download) instead of disabled buttons; dirs
    render accent + trailing `/` + chevron; rename/delete are labeled
    icon buttons; path wrapped in `<bdi dir="ltr">` — **gotcha:** bare
    `direction: rtl` ellipsis migrates a path's leading `/` to the tail.
  - `Composer.jsx` — `/plan` `/goal` chips moved into the picker chip row
    (`.plan-row` deleted): composer is 2 rows, ~44px of chat back on every
    phone. ≤640px chips get a 108px floor so the row overflows and the
    next chip peeks — without it the row ended flush at the screen edge
    and the plan/goal chips were undiscoverable.
- **Verified:** eslint/vitest(62)/build clean; Playwright phone shots
  (412×915, dark+light): dashboard, real session, telemetry sheet, files
  drawer — sorted, full-width, path correct, chip peek visible. Relay
  rebuilt + recreated.
- **Left open:** phone-IA plan (bottom sheets, sticky header, composer
  focus mode, approval action sheet, 44px sweep, PWA polish) written in
  `ToDo.md`.
- **Restart needed:** already done (relay).

## 2026-08-09 — hover/press prefetch + preview cache (plan phase 3)
**Agent:** Claude Fable 5 · **Branch:** main (uncommitted) · **Status:** done, deployed

- **Why:** phase 3 of the tail-first plan — make opening a saved chat feel
  instant, without ever touching the codex app-server.
- **Changed:**
  - `services/daemon/adapters/rollout.py` — `load_rollout_preview(thread_id,
    turns)`: compact user/agent tail read straight from the rollout file.
    LRU (64 entries) keyed `(thread_id, file mtime, turns)` — a grown file
    changes mtime and misses naturally, so no TTL/invalidations to get
    wrong. Measured: 70 ms cold on an 18 MB rollout, 1.5 ms warm.
  - `services/daemon/client.py` — `thread-preview-request` handler; parse
    runs via `asyncio.to_thread` (a cold multi-MB rollout takes ~0.3 s and
    must not stall the event loop).
  - `services/relay/handlers/threads.py` + `app.py` —
    `GET /api/hosts/{id}/threads/{tid}/preview?turns=N` via
    `await_daemon_request`.
  - `services/relay/handlers/ws_daemon.py` — **gotcha for next time:**
    admin responses are whitelisted by frame type; without adding
    `thread-preview-response` there the future never resolves and the REST
    call 504s with nothing in any log.
  - `apps/web/src/util/threadPreview.js` — memory + sessionStorage cache
    (5 min TTL), in-flight dedupe, max 3 concurrent prefetches.
  - `apps/web/src/hooks/usePrefetchIntent.js` — 150 ms hover dwell +
    `pointerdown` (the opening tap races the prefetch).
  - `useRemotex` — `prefetchThreadPreview(thread)` exposed; `openSession`
    paints cached turns immediately as `preview_*` rows; `HISTORY_COMMIT`
    drops them when the authoritative tail lands. Wired into the dashboard
    `RecentRow` and sidebar `SessionRow`.
- **Verified:**
  - Backend 153 + ruff, web 62 + eslint + build, all clean.
  - REST end-to-end on the scratch rig: `available: true`, real text,
    warm round-trip 5.8 ms.
  - Browser e2e (Playwright): hover dwell fires the fetch; re-hover = 0
    new requests; cache persisted in sessionStorage; sweeping 12 rows
    produced only 1 additional request (dwell + inflight cap working);
    after click the preview text was visible in **71 ms** while the real
    session was still opening. Screenshot `prefetch-01-instant.png`.
  - Deployed: daemon restarted, relay rebuilt + recreated, public edge
    back at 200, daemon reattached.
- **Left open:** plan phase 4 (Android/iOS tail + backfill + press
  prefetch). Rig cleanup note: scratch relay :9099 / vite :5174 / scratch
  daemon still running for UI work; `remotex_uidev` DB droppable.
- **Restart needed:** already done.

## 2026-08-09 — tail-first transcripts + scroll-up backfill (plan phases 1+2)
**Agent:** Claude Fable 5 · **Branch:** main (uncommitted) · **Status:** done, deployed

- **Why:** opening a saved chat streamed the whole history past the user
  (up to 500 items in one burst); owner asked for last-turn-first with
  stable scroll-up backfill. Plan in `ToDo.md` ("tail-first transcripts").
- **Changed:**
  - `services/daemon/adapters/stdio.py` — `_replay_history` now ships only
    the last `HISTORY_TAIL_TURNS = 2` turns (`history-begin {turns, shown,
    has_more}` → items → `history-end {oldest, has_more}`), keeping the
    full parsed transcript in `self._history_turns`. New client frame
    `history-more {before, limit}` → `_send_history_chunk` slices older
    pages (`history-chunk-begin/-end`, inputs clamped, out-of-range safe).
    Item payloads carry `turn_index` for paging anchors. Replay loop
    extracted to `_emit_history_turn`.
  - `apps/web/src/hooks/useRemotex.js` — replayed items buffer in
    `historyBufRef` between begin/end and land as ONE `HISTORY_COMMIT`
    dispatch (prepend + id-dedupe); state carries
    `historyHasMore/Oldest/Loading/Tick/Prepend`; `loadOlderHistory()`
    guards + sends `history-more`; `SESSION_RESET` clears it all.
  - `apps/web/src/components/EventStream.jsx` — scroll contract: tail
    commits pin to bottom in one jump; prepend commits keep the viewed
    turns in place (render-phase geometry snapshot + `useLayoutEffect`
    scrollTop compensation); live events auto-follow ONLY when already
    near the bottom, so reading history is never yanked; "load older"
    sentinel (IntersectionObserver, root = `.stream`) arms 300 ms after
    the tail settles. `.stream` gets `overflow-anchor: none`; empty
    placeholder moved inside the scroller.
  - `apps/web/src/api/sessionSocket.js` — `sendHistoryMore(before, limit)`.
  - `SessionScreen.jsx` / `App.jsx` — props threaded.
  - Relay: **zero changes** — it already forwards unknown client frames to
    the daemon untouched.
- **Verified:**
  - Backend 153 passed (5 new: tail slicing, paging, final page, garbage
    inputs), ruff clean. Web 62 passed (3 new `HISTORY_COMMIT` reducer
    tests), eslint + build clean.
  - **Live e2e on a real 99-turn rollout** (scratch rig flipped to stdio
    mode, real codex resume): tail rendered with sentinel; scroll-to-top
    grew 24→48→65 groups with the viewport anchored (scrollTop 7209 after
    first backfill — not yanked to top or bottom); anchor text still
    on-screen; sentinel disappears when history is exhausted. Screenshots
    `hist-01..03` in the scratchpad.
  - Deployed: live daemon restarted, relay rebuilt + recreated; public
    edge back in ~15 s this time, daemon reattached.
- **Left open:** plan phases 3 (hover/press prefetch, disk-backed, LRU)
  and 4 (Android/iOS). Note: parking at the very top chain-loads pages
  until history is exhausted — acceptable ("keep scrolling = keep
  loading"), mention if anyone wants a stricter gate. Fallback
  `thread/turns/list` cursor probe still pending (only matters for
  threads with no local rollout).
- **Restart needed:** already done (daemon + relay).

## 2026-08-09 — audit iPhone picker scroll behavior
**Agent:** Codex · **Branch:** main · **Status:** done (read-only audit)

- **Why:** check whether the model, effort, and permissions dropdown scroll bug also affects the native iPhone client.
- **Changed:** no Apple source files. The iPhone app has no model, effort, or permissions selectors yet; `SessionSocket.sendTurn` sends only input and client-message id. This missing parity is already documented in `apple/README.md`.
- **Verified:** searched every Swift source for `Picker`, `Menu`, popover/sheet, and model/effort/permissions selection state; inspected `ContentView.swift`, `SessionSocket.swift`, and `RemotexViewModel.swift`. Only the event stream scrolls, and there is no dismiss-on-scroll handler.
- **Left open:** native iPhone selector parity remains intentionally unimplemented; the responsive web fix will cover Safari/Chrome on phones using the web app.
- **Restart needed:** none.

## 2026-08-09 — web UI: light/dark theming + UX audit fixes
**Agent:** Claude Fable 5 · **Branch:** main (uncommitted) · **Status:** done

- **Why:** the web app was dark-only with 87 hardcoded colors outside the
  token block; owner asked for a real white/dark mode and a UI/UX audit.
- **Changed:**
  - `apps/web/src/styles.css` — token block rebuilt: dark stays the `:root`
    base, light lives under `:root[data-theme='light']` (the `:root` prefix
    matters — a second `:root` block at ~line 1381 defines telemetry accents
    later in the file and would win at equal specificity). New tokens:
    `--line-strong, --on-accent, --scrim(-heavy), --shadow-1/2/3,
    --code-string/number/fn, --user-accent, --gold`, light values for the
    telemetry sparkline accents. Every hardcoded rgba()/hex outside the
    token blocks swept to tokens or `color-mix(in srgb, …)` derivations, so
    hover washes/glass/scrims re-theme for free. Micro-type bumped
    (10px→11px, 9px→10px). Sidebar meta no longer wraps "4m / ago".
  - `apps/web/src/util/theme.js` — new. Resolves system preference,
    persists an explicit choice under `remotex.theme`, stamps
    `<html data-theme>` before first paint, updates `<meta theme-color>`,
    follows OS changes while no explicit choice.
  - `apps/web/src/components/ThemeToggle.jsx` — new; used in
    `DashboardHeader.jsx` and floated on `LoginScreen.jsx`.
  - `apps/web/src/main.jsx` — `initTheme()` before render.
  - `apps/web/src/components/JumpPicker.jsx` — folder picker sorts
    dotfolders (.cache, .config…) after real folders; they used to be the
    first two screens of every "new session" flow.
- **Verified:** drove the real UI end-to-end with a scratch rig — relay
  from source on :9099 (`RELAY_SEED_DEMO=1`, throwaway `remotex_uidev` DB
  in remotex-postgres-1), daemon in mock mode, vite dev + Playwright.
  34 screenshots: dark/light × desktop/mobile × login→dashboard→folder
  picker→session stream→slash menu. Both themes coherent; dark unchanged.
  eslint clean, vite build clean, vitest 56 passed. Relay image rebuilt and
  recreated per the ritual; daemon reattached after.
- **Left open:** larger UX issues documented for the owner (dashboard
  redundancy, disabled-button pair, mobile session flow) — analysis, not
  code. Scratch rig still running: relay :9099, vite :5174, mock daemon
  (kill by pattern `relay.app --port 9099` / `uidev-daemon.toml` / vite).
  The `remotex_uidev` database can be dropped anytime.
- **Restart needed:** already done (relay rebuild + recreate).

## 2026-08-09 — web dashboard restructure + drawer inert fix (UX audit items 1–6)
**Agent:** Claude Fable 5 · **Branch:** main (uncommitted) · **Status:** done; deploy degraded (see below)

- **Why:** second half of the UI/UX audit — dashboard redundancy, ghost
  buttons, dead desktop space, error-looking empty state, broken mobile
  session flow, flat visual hierarchy.
- **Changed:**
  - `apps/web/src/screens/DashboardScreen.jsx` — rewritten. No session: one
    `card-hero` (folder path → Start session → Choose folder… → Browse
    files · Manage hosts links). With session: compact active-session card
    with always-enabled Open/End. QUICK ACTIONS and WORKSPACE cards
    deleted — they were three skins over the same action. New
    `RecentSessions` grid in the main column (2-col ≥340px, resume on
    click, active row highlighted) — fills the former dead space with what
    returning users actually want. "No active session" headline gone.
  - `apps/web/src/App.jsx` — `onResumeThread` passed to the dashboard
    (same handler the sidebar uses); `onNewSession` prop dropped.
  - `apps/web/src/styles.css` — `.card` default border quieted to 70%
    line; `.card-hero` carries the weight (2px accent top border,
    shadow-1, larger padding); `.recent-*` row styles; dead
    `.action-tile`/`.actions-grid`/`.dashboard-row`/`.card-folder` rules
    deleted; `.dashboard-center` 920→1060px.
  - **Real a11y bug found via Playwright:** the closed mobile drawers kept
    every control focusable/tappable off-canvas — a tap on "New session"
    could target the invisible sidebar button. All three drawers now get
    `visibility: hidden` when closed (delayed past the slide-out
    transition, instant on open).
  - `apps/web/src/util/theme.js` — module-level `window.matchMedia`
    guarded; it crashed `relayClient.test.js` at import under node, which
    is why vitest reported 56 instead of 59.
- **Verified:** eslint clean, vite build clean, vitest **59 passed** (7
  files). 40 Playwright screenshots (dark/light × desktop/mobile ×
  login→dashboard→picker→streaming session→slash). **Mobile now reaches a
  live session in both themes — it could not before.**
- **Deploy state — recovered:** relay rebuilt + recreated with the new
  bundle. The recreate cost ~5 minutes of public downtime: the SparkTunnel
  connector looped on "websocket: bad handshake" until the broker accepted
  a fresh session at 13:15:16Z, after which the public edge returned 200
  and the daemon reattached (16:16:28 local). Lesson for the ritual: a
  relay `--force-recreate` drops the connector's broker session, and the
  broker takes a few minutes to accept a new handshake — expect a short
  public outage, don't thrash the connector with restarts.
- **Restart needed:** none beyond the above; scratch UI rig (:9099/:5174,
  mock daemon) still running for anyone iterating on the UI.

## 2026-08-09 — fix Codex resolution across standalone and npm installs
**Agent:** Codex (root integrator) · **Branch:** `main` · **Status:** done

- **Why:** the live daemon config pinned the removed `/usr/bin/codex`, while
  Codex 0.147.0 now runs from the official standalone installer under the
  service account's `~/.local/bin`.
- **Changed:** restored this machine's private `codex_binary` to the portable
  `codex` default; the installer now builds the unit `PATH` from the selected
  Codex shim directory, `~/.local/bin`, and system locations without resolving
  stable symlinks. Installer reruns now restart an existing daemon so unit
  changes take effect. Documented npm/nvm and system-service behavior.
- **Verified:** `bash -n`, `systemd-analyze --user verify`, Ruff, 148 service
  tests, and 59 web tests pass. Reinstalled/restarted the user service, received
  host-derived models and live threads through the admin app-server, and
  received `session-started` from a fresh normal Codex session. The service is
  active and attached; private files remain mode `600`, and no private value
  entered the tracked worktree or Git history.
- **Restart needed:** no — the live daemon was reinstalled and restarted.

## 2026-08-09 — audit Codex executable resolution after install-method change
**Agent:** Codex (`codex_path_code_audit`) · **Branch:** `main` · **Status:** done

- **Why:** the live daemon still targeted `/usr/bin/codex` after Codex moved
  from a system/npm install to the official standalone user install.
- **Changed:** no implementation files; this was a read-only root-cause audit.
- **Verified:** Codex 0.147.0 is now `/root/.local/bin/codex` (a managed
  standalone symlink), `/usr/bin/codex` is absent, the installed unit omits
  `/root/.local/bin` from `PATH`, and the journal records `FileNotFoundError`
  from both admin calls. Reproduced exit 127 with the current unit `PATH` and
  success after adding `/root/.local/bin`.
- **Left open:** change the unit template/installer to include the service
  account's `~/.local/bin`, reset this machine's `codex_binary` to the existing
  PATH-based default `codex`, then smoke both admin and session spawns.
- **Restart needed:** none for this audit; the proposed fix needs daemon
  installer rerun/unit reload and daemon restart.

## 2026-08-09 — deploy the private relay and verify its public boundary
**Agent:** Codex (root integrator) · **Branch:** `main` · **Status:** done

- **Why:** bring the reconciled stack live through SparkTunnel, pair this
  machine's daemon, and close the deployment/authentication gaps found by a
  real public-edge probe.
- **Changed:** ignored mode-0600 `deploy/.env` contains the personal hostname,
  generated user/Postgres secrets and connector token; mode-0600
  `~/.remotex/config.toml` contains the one-time bridge key and public WSS URL.
  None of those values is tracked. Root user linger is enabled for the systemd
  user daemon.
- **Changed:** provisioned one private user and host, issued one bridge key,
  paired/restarted `remotex-daemon`, and removed one persisted public demo user
  from the existing database. The demo account can only be restored by
  deliberately reseeding it.
- **Changed:** `services/relay/middleware/security_headers.py`, `app.py`, and
  `tests/test_security_headers.py` — add CSP, HSTS, frame blocking, no-sniff,
  referrer/permissions policy and API `no-store` through aiohttp's response
  prepare hook, including errors, static files and WebSocket handshakes; remove
  the server-version header. `deploy/README.md` documents that boundary.
- **Verified:** a live clean SparkTunnel request carried only private platform
  addresses; a second request preserved spoofed `X-Forwarded-For` and
  `X-Real-IP`. The deployed profile therefore forces `RELAY_TRUST_PROXY=0` and
  publishes zero relay ports. Public root/models returned 200, unauthenticated
  hosts returned 401, and a 40-request invalid-token burst returned 32×401 plus
  8×429. The login bundle is live and contains no demo-token fallback.
- **Verified:** authenticated hosts returned one online daemon; telemetry was
  current to 0 seconds after the final rebuild. Relay/Postgres are healthy,
  SparkTunnel is broker-connected with zero restarts, the user daemon is active
  and reattached over WSS, and the public response contains the new browser
  headers with no `Server` banner.
- **Verified:** backend pytest **148 passed** and Ruff passed; web Vitest
  **59 passed**, ESLint passed and the production build passed; base, Caddy and
  portless Spark Compose configurations validate.
- **Left open:** `I-012` (iOS feature parity) and `I-014` (SparkTunnel cannot
  support trustworthy per-visitor IP limiting until PhotonSpark supplies a
  sanitized header or edge-native limit). Short-lived OIDC is still future
  work; the deployed boundary is a high-entropy relay-issued bearer token.
- **Restart needed:** none — relay, connector and daemon are live.

## 2026-08-09 — gate the web app behind verified bearer sign-in
**Agent:** Codex (`login_ui_impl`) · **Branch:** `main` · **Status:** done

- **Why:** the web app previously mounted authenticated REST/WebSocket logic
  immediately and silently fell back to the public demo token instead of
  presenting a real sign-in boundary.
- **Changed:** `apps/web/src/App.jsx`, `screens/LoginScreen.jsx`, and
  `hooks/useRemotex.js` — verify saved or submitted tokens through the existing
  hosts endpoint before mounting the dashboard, reuse the verified host result,
  handle strict-mode saved-token checks once, and reload to `/` on logout.
- **Changed:** `apps/web/src/util/tokenStorage.js`, `api/relayClient.js`, and
  `components/SettingsPanel.jsx` — removed the demo fallback and in-app raw-token
  editing, clear both credential stores on sign-out, expose HTTP status and
  `Retry-After`, and show verified/storage status instead.
- **Changed:** `apps/web/src/styles.css` and `README.md` — added the responsive,
  accessible token login surface and documented its bearer/OIDC boundary.
- **Changed:** lean Node tests cover token persistence/logout, bearer
  verification metadata, and 401/429 login copy; obsolete reducer token-mutation
  cases and tests were removed.
- **Verified:** web Vitest **59 passed**, ESLint passed, Vite production build
  passed; focused auth/storage rerun **6 passed**; `git diff --check` passed.
- **Left open:** true short-lived OIDC remains future work, as documented; no
  issue was introduced by this change.
- **Restart needed:** relay image rebuild/recreate (the web bundle is baked in).

## 2026-08-09 — make SparkTunnel rate-limit identity spoof-safe
**Agent:** Codex (`spark_proxy_fix`) · **Branch:** `main` · **Status:** done

- **Why:** live header capture showed SparkTunnel 0.2.0 preserves spoofed
  forwarding headers and does not pass a trustworthy public visitor IP.
- **Changed:** `deploy/docker-compose.sparktunnel.yml` — force
  `RELAY_TRUST_PROXY=0`, even when `.env` requests `1`, while retaining the
  portless connector profile.
- **Changed:** deployment/service docs and rate-limit module prose — reserve
  trusted forwarding headers for Caddy/sanitizing proxies and document
  SparkTunnel's shared connector-peer bucket.
- **Changed:** `services/tests/test_rate_limit.py` — exercise the real
  `/api/hosts` auth path and prove rotating bearer plus spoofed
  `X-Forwarded-For` values still reaches HTTP 429 when proxy trust is off.
- **Verified:** focused rate-limit pytest **6 passed**, Ruff passed,
  `git diff --check` passed; Spark Compose resolves trust to `0` despite an
  explicit `RELAY_TRUST_PROXY=1` and publishes zero relay ports; base and Caddy
  Compose configs validate.
- **Left open:** `I-014` — per-visitor IP limits remain impossible through
  SparkTunnel until PhotonSpark supplies a sanitized visitor-IP contract.
- **Restart needed:** relay recreate/rebuild with the SparkTunnel override.

## 2026-08-09 — clear stale host presence on relay startup
**Agent:** Codex (`startup_online_fix`) · **Branch:** `main` · **Status:** done

- **Why:** persisted `inventory_hosts.online = true` survived relay restarts
  even though daemon presence is process-local, so the API could report a
  disconnected daemon as online.
- **Changed:** `services/relay/store.py` — the startup schema batch now marks
  every persisted online host offline before the relay begins accepting daemon
  reconnects.
- **Changed:** `services/tests/test_store_helpers.py` — added a focused guard
  that keeps the startup reset in the schema batch.
- **Verified:** `pytest -q services/tests/test_store_helpers.py` (14 passed),
  Ruff on both changed Python files, and `git diff --check` all passed.
- **Left open:** none.
- **Restart needed:** relay rebuild.

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
