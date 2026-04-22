# From prototype to production

A concrete list of what has to change between this `services/` tree
and something you'd run for real users. Ordered roughly by
blast-radius: the top items will hurt you fastest if skipped, the
bottom items are polish.

## Relay

1. **Keycloak / OIDC for user auth.** Replace the `demo-user-token`
   lookup with a JWT verifier pointed at your existing PhotonSpark
   Keycloak realm. Claims → `owner_token` (really: user ID).
2. **Bridge keys as opaque server-minted tokens.** Current behavior is
   already close; what's missing is an `issued_by` user ID column and
   a revoke endpoint that marks `revoked_at`.
3. **Postgres, not SQLite.** The schema translates 1:1. Use `asyncpg`
   if you want to go async, or just move from `sqlite3` to `psycopg`.
4. **Port the hot path to a compiled runtime if needed.** The current
   relay is fine to tens of thousands of sessions — the work per frame
   is `json.loads` + a dict lookup. If you outgrow Python, port to Go
   (`gorilla/websocket` or `nhooyr.io/websocket`). The protocol is
   language-independent by design.
5. **TLS + ingress.** Run behind Envoy Gateway. Never expose `ws://`
   directly to the internet. HTTPRoute for `/api/*`, WS upgrade for
   `/ws/*`.
6. **Bounded queues + backpressure.** Today a slow client can hold the
   daemon's send buffer open indefinitely. Add per-client bounded
   queues with drop-oldest semantics for non-critical events (deltas)
   and block-with-timeout for critical ones (approvals).
7. **Audit log.** Persist every `session-event` kind (not the full
   payload — too much) plus `approval` frames with decision + actor.
   This is a compliance and incident-response requirement, not a
   feature.
8. **Rate limits.** Per-user on REST, per-host on WS reconnects, per-
   session on `turn-start` (the expensive one).
9. **Metrics.** Prometheus endpoint: `relay_sessions_open`,
   `relay_frames_total{direction,kind}`, `relay_daemons_online`,
   `relay_ws_errors_total{kind}`.

## Daemon

1. **Swap `MockCodexAdapter` for `StdioCodexAdapter`.** Fill in
   request/response correlation and the `thread/resume` path for
   reconnects.
2. **Packaging.** Ship as a Python wheel (`pip install remotex-daemon`)
   and a single-file binary via PyInstaller for users who don't have
   a Python toolchain.
3. **Service integration.**
   - Linux: `systemd` unit template in `packaging/linux/`.
   - Windows: NSSM or native service via `pywin32`.
   - macOS: `launchd` plist.
4. **First-run UX.** `remotex-daemon setup` should paste a token and
   hit the relay once to confirm it's valid before writing the
   config file.
5. **Update channel.** Daemon checks `/api/daemon/version` on start;
   warns (doesn't auto-update) if a newer version exists.
6. **Session resume on reconnect.** On `session-open` for a
   `session_id` the daemon already has a thread for, call
   `thread/resume` instead of `thread/start`.
7. **Approval signaling in the mock adapter.** So the web/mobile
   client's approval UX can be tested without a real Codex.

## Clients

1. **Port `services/web` to the real Next.js client.** Keycloak
   login flow, host list, session view. The existing mockup
   (`panels/MobilePanel.jsx`) is the reference.
2. **iOS / Android wrappers.** The PWA is enough to start; wrap with
   Capacitor for push (iOS) and FCM (Android) to get approval
   notifications without opening the app — this is the feature the
   Android panel wireframe calls out.
3. **Offline/reconnect.** If the tab goes background for > 60s,
   close the WS and reconnect with a resume frame.

## Tests

1. The existing `scripts/e2e_test.py` should run in CI on every PR.
2. Add a fault test: kill the daemon mid-stream, assert the client
   sees `session-closed` within 2s.
3. Add a multi-client fault test: two clients attaching to the same
   session — pick one to serve, reject the other cleanly.

## Rollout

Phase 1 (you): self-host relay on your k8s next to PhotonSpark.
Phase 2: invite-gate 10-50 early users; watch metrics + audit log.
Phase 3: public signup. At this point the audit log, bounded queues,
and Keycloak Organizations become non-negotiable.
