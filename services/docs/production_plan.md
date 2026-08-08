# From prototype to production

What still has to change between this `services/` tree and something
you'd point real users at. Ordered roughly by blast radius: the top
items hurt you fastest if skipped.

Items that were on this list and are now **done** are recorded at the
bottom, so nobody re-implements them.

## Relay

1. **Keycloak / OIDC for user auth.** Replace the `demo-user-token`
   lookup in `store.user_for_token()` with a JWT verifier. Claims →
   `owner_token`. This is the single biggest gap; everything else on
   this list assumes it.
2. **Bridge-key lifecycle.** The schema already has `revoked_at` and
   `resolve_bridge_key()` honors it, but nothing ever sets it. Needs a
   revoke endpoint, an `issued_by` user-id column, and optional expiry.
3. **Audit retention.** `audit()` emits structured lines on
   `logger=audit` covering auth, daemon attach/detach, session attach,
   turn start, and session close. Nothing ships them anywhere durable —
   wire up Loki/CloudWatch and decide a retention window.
4. **Metrics.** No Prometheus endpoint today. Wanted:
   `relay_sessions_open`, `relay_frames_total{direction,kind}`,
   `relay_daemons_online`, `relay_ws_errors_total{kind}`.
5. **Per-session and per-host limits.** The REST bucket is per-token
   only (30 burst / 10 rps). Still missing: per-host WS reconnect limits
   and a cap on concurrent sessions per user.
6. **Durable replay.** The replay buffer is in-memory
   (`RELAY_SESSION_REPLAY_LIMIT`, default 1000 frames) and dies with the
   process, so a relay restart loses transcript catch-up for live
   sessions. Persist it, or accept the loss explicitly.
7. **Horizontal scale.** `Hub` is process-local: two relay replicas
   can't route each other's sessions. Multi-replica needs either sticky
   routing by `host_id` or a shared bus (Redis/NATS) behind the hub.

## Daemon

1. **Packaging.** Ship as a wheel (`pip install remotex-daemon`) and a
   single-file binary via PyInstaller for users without a Python
   toolchain. Today the install path is a git clone plus
   `deploy/install-daemon.sh`.
2. **Non-Linux service integration.** Linux has a systemd user unit.
   Windows (NSSM or `pywin32`) and macOS (`launchd` plist) don't.
3. **First-run validation.** `daemon init` writes the config without
   checking it. It should hit the relay once and confirm the bridge
   token is valid before writing anything.
4. **Update channel.** Daemon checks a `/api/daemon/version` endpoint on
   start and warns (never auto-updates) when it's behind.
5. **Filesystem hardening.** The `fs-*` handlers operate on absolute
   paths anywhere the daemon user can reach, trusting the relay to have
   authenticated the caller. `mkdir` validates its name segment and
   `delete` refuses directories, but there is no root-jail. Decide
   whether to confine them to configured roots.

## Clients

1. **iPhone parity.** The SwiftUI app is a starter: no thread resume,
   images, model/effort controls, permissions, approvals, interrupt, or
   reconnect backoff. See `apple/README.md`.
2. **Push notifications for approvals.** Android has a foreground
   service and turn-complete notifications; no platform gets a push when
   an approval lands while the app is closed. Needs FCM (Android) and
   APNs (iOS), which in turn needs the relay to hold device tokens.
3. **Runtime relay picker on Android.** `BuildConfig.RELAY_URL` is baked
   in at compile time, so pointing at a different relay means a rebuild.
   iOS already does this at runtime.

## Tests

1. **Fault tests.** None of these exist yet: kill the daemon mid-stream
   and assert the client sees a terminal frame; two clients racing the
   same approval; a slow client getting closed with 1013; host going
   offline mid-turn.
2. **Adapter tests against captured frames.** `services/tests/` covers
   hub routing, rate limiting, logging, the models endpoint, and daemon
   helpers. The `stdio.py` dispatch table — the most protocol-fragile
   code in the repo — is only exercised indirectly.

## Rollout

- Phase 1: self-host the relay for yourself. This is where the project
  is now and it works.
- Phase 2: invite-gate a handful of trusted users. Needs OIDC (Relay 1)
  and bridge-key revocation (Relay 2) first.
- Phase 3: public signup. Audit retention, metrics, and horizontal scale
  become non-negotiable.

## Already done

Kept here so these don't get re-proposed:

- **Postgres inventory store.** `asyncpg`, schema in `relay/store.py`.
  SQLite is gone.
- **`StdioCodexAdapter` against a real `codex app-server`.** It is the
  default mode; `MockCodexAdapter` is opt-in (`--mode mock`) and drives
  the e2e test.
- **Request/response correlation.** `_request()` parks a future per rpc
  id; `_read_line_unbounded()` handles multi-MB resume replies.
- **`thread/resume` on reconnect**, with local rollout replay so the
  transcript renders before Codex finishes rehydrating.
- **Bounded queues / backpressure.** `_bounded_send()` closes a socket
  that can't accept a frame within 5s (code 1013) instead of letting one
  slow consumer stall the relay.
- **REST rate limiting.** Token bucket, HTTP 429 + `Retry-After`.
- **Structured JSON logs + audit events** on `logger=audit`.
- **Session resume for clients.** Sequenced events, a replay buffer, a
  `pending-prompts` snapshot, and a grace watchdog that distinguishes an
  idle session from one still producing output.
- **Approval and user-input plumbing**, end to end, with first-response-
  wins arbitration across multiple attached clients.
- **systemd user unit** (`deploy/remotex-daemon.service` +
  `install-daemon.sh`).
- **e2e in CI**, plus ruff, pytest, web lint/build/audit, Android APK,
  and an iPhone simulator build.
