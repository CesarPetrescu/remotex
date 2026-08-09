# Remotex web client

The control-plane web app: React 18 + Vite, no UI framework, no router.
It's the surface daemons are driven from, and it ships inside the relay
Docker image (`deploy/Dockerfile.relay` builds it and copies `dist/` to
`/app/web`, which the relay serves).

## What it does

- Loads hosts from `/api/hosts` and shows which are online, with live
  CPU / memory / GPU / network telemetry sparklines per host.
- Lists saved Codex threads per host and resumes them, replaying the
  transcript while Codex rehydrates in the background.
- Opens a session, attaches to `/ws/client`, and streams events:
  reasoning, tool calls, file changes, MCP tool calls, and agent messages
  with delta streaming and syntax-highlighted markdown.
- Sends, steers the active turn, or queues FIFO follow-up turns with optional
  image attachments, model, reasoning effort, and permission chips. Queuing is
  client-side, like the Codex TUI: exactly one normal `turn-start` is sent when
  the active turn becomes idle, and unsent items survive WebSocket reconnects.
  The model list comes from
  `/api/hosts/{host_id}/models` (what that host's Codex actually offers);
  if the host cannot supply it, the default entry lets Codex choose.
- Queues approval prompts and Codex user-input dialogs — a second
  concurrent prompt lines up behind the first instead of replacing it —
  with first-response-wins arbitration when several clients are attached.
- Marks a replay gap in the stream when the relay's buffer no longer has
  the events a reconnecting client asked for.
- Interrupts a running turn; runs slash commands (`/plan`, `/default`,
  `/cd`, `/pwd`, `/compact`, `/goal`, `/collab`); sets and clears thread
  goals with a token budget.
- Browses, uploads, renames, and deletes files on the host, with the
  **Jump** folder picker (search across recents/favorites, `/path`
  teleport, or tree browsing).
- Reconnects with `last_seq` so a refresh or a sleeping phone catches up
  on missed events instead of losing the stream.
- Alerts on turn completion when the tab is backgrounded.
- Gates the dashboard behind a bearer-token sign-in that verifies access with
  `GET /api/hosts` before mounting any dashboard REST or WebSocket logic.
- Persists sidebar layout and folder history in `localStorage`. The
  verified bearer token goes to `localStorage` only when "remember on this
  device" is on; with it off the token lives in `sessionStorage` and dies
  with the tab. Sign out clears both stores. There is no prefilled demo token.
  A short-lived OIDC flow remains future work for deployments that need more
  than this relay-issued bearer-token boundary.
- Refuses image attachments and workspace uploads over
  `MAX_FILE_BYTES` (25 MB, matching the relay's `REMOTEX_MAX_FILE_BYTES`)
  with a readable error instead of a failed request.

## Architecture

```text
App.jsx
├── LoginScreen               verifies the bearer before mounting the app
├── DashboardScreen           host, thread, folder, and session entry points
├── SessionScreen             event stream + composer
├── FilesScreen / JumpPicker  choose a working directory
├── HostsSidebar              hosts and resumable threads
├── RightSidebar              approvals, questions, telemetry
└── useRemotex                state reducer + REST/WS orchestration
    ├── api/relayClient.js     HTTP client
    └── api/sessionSocket.js   WebSocket framing and client identity
```

`useRemotex` is the client state machine. It reduces normalized relay events,
tracks the last sequence number, reconnects with capped exponential backoff,
and exposes UI actions. Presentation components do not speak the wire protocol
directly.

Persistent browser state is intentionally small:

- `localStorage`: remembered user token, layout preferences, folder recents/favorites
- `sessionStorage`: tab-only user token and unresolved prompt backups
- relay replay buffer: recent session events used after reconnect

## Development

Requirements: Node.js 20+ and a relay listening on `127.0.0.1:8080`.

```bash
cd apps/web
npm ci
npm run dev
```

Runs on <http://localhost:5174> and proxies `/api/*` and `/ws/*` to a
relay on `127.0.0.1:8080`. Override the relay port with `RELAY_PORT=…`.

You need a relay (and a daemon) running — see `services/README.md`. Note
that the relay requires `RELAY_DATABASE_URL`; it will not start without
a Postgres DSN.

```bash
npm run lint     # eslint
npm run test     # vitest, watch mode
npm run test:run # vitest, one shot
npm run build    # → apps/web/dist/
npm run preview  # serve the built bundle
```

In production the bundle is baked into the relay image, so **any web
change needs a relay image rebuild**:

```bash
cd deploy && docker compose build relay && docker compose up -d --force-recreate relay
```

## Project layout

```text
apps/web/
├── index.html
├── vite.config.js            dev server + /api,/ws proxy
├── eslint.config.js
└── src/
    ├── main.jsx
    ├── App.jsx               app shell: screen routing, sidebars, Jump picker wiring
    ├── config.js             screens, statuses, permission chips, default model entry, size cap
    ├── styles.css            the entire stylesheet
    ├── api/
    │   ├── relayClient.js    REST wrapper around the relay
    │   └── sessionSocket.js  /ws/client socket with reconnect + last_seq
    ├── hooks/
    │   ├── useRemotex.js     the whole state machine: reducer, frame handling, actions
    │   └── useBackgroundCompletionAlert.js
    ├── screens/
    │   ├── LoginScreen.jsx       verified bearer-token sign in
    │   ├── DashboardScreen.jsx   host + thread landing surface
    │   ├── SessionScreen.jsx     chat surface
    │   └── FilesScreen.jsx       standalone file browser
    ├── components/
    │   ├── Composer.jsx          chip row + textarea + queue/steer/send/stop
    │   ├── Pickers.jsx           model / effort / permission chips
    │   ├── SendOrStopButton.jsx
    │   ├── EventStream.jsx, EventRow.jsx
    │   ├── PendingPromptsPanel.jsx   approvals + user-input dialogs
    │   ├── JumpPicker.jsx        folder picker (search / teleport / browse)
    │   ├── SettingsPanel.jsx     verified sign-in status + sign out
    │   ├── WorkspaceFilesDrawer.jsx, FileRow.jsx
    │   ├── HostsSidebar.jsx, HostRow.jsx, DashboardHeader.jsx
    │   ├── RightSidebar.jsx, TelemetrySidebar.jsx, Sparkline.jsx
    │   ├── ResumingBanner.jsx, CopyButton.jsx, Toast.jsx
    └── util/
        markdown.jsx, path.js, slash.js, url.js, fuzzy.js,
        copy.js, time.js, host.js, folderHistory.js, tokenStorage.js
```

Tests live next to what they cover (`src/**/*.test.js`) and run in
vitest's `node` environment — pure helpers, token/HTTP auth behavior, and the
`useRemotex` reducer. No jsdom or component rendering:

```
src/util/{slash,path,fuzzy,url}.test.js
src/util/tokenStorage.test.js      browser credential persistence + logout
src/api/relayClient.test.js        bearer verification + rate-limit metadata
src/hooks/useRemotex.test.js       reducer: prompt queues + model list
```

`useRemotex.js` is the single source of truth for app state — a reducer
plus the WebSocket attach/reconnect logic and the REST calls. Its Android
counterpart is `RemotexViewModel.kt`, which mirrors the same reducer and
state shape deliberately; changing the event handling here usually means
changing it there too.

## Gotcha: modals must be portalled

`.dashboard-layout > * { position: relative }` (in `styles.css`) creates a
containing block that turns any child's `position: fixed` into a stray
grid cell. Anything that overlays the page — `Toast`, `JumpPicker`, and
the `Pickers` dropdowns — renders through `createPortal(node,
document.body)`. Add new overlays the same way.

## Still to do

Tracked in the root `README.md` and under "Known gaps" in
`services/docs/architecture.md`:

- Real OIDC login, replacing the demo bearer token. "Remember on this
  device" only narrows the window a long-lived bearer token is readable
  in; it does not fix the underlying model.
- Push notifications for approvals that arrive while the tab is closed.
