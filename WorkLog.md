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
