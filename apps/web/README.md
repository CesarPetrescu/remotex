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
- Sends turns with optional image attachments, model, reasoning effort,
  and permission chips (fetched from `/api/models`, with an embedded
  fallback in `src/config.js`).
- Handles approval prompts and Codex user-input dialogs, including
  first-response-wins arbitration when several clients are attached.
- Interrupts a running turn; runs slash commands (`/plan`, `/default`,
  `/cd`, `/pwd`, `/compact`, `/goal`, `/collab`); sets and clears thread
  goals with a token budget.
- Browses, uploads, renames, and deletes files on the host, with the
  **Jump** folder picker (search across recents/favorites, `/path`
  teleport, or tree browsing).
- Reconnects with `last_seq` so a refresh or a sleeping phone catches up
  on missed events instead of losing the stream.
- Alerts on turn completion when the tab is backgrounded.
- Persists the user token, sidebar layout, and folder history in
  `localStorage`.

## Dev

```bash
cd apps/web
npm install
npm run dev
```

Runs on <http://localhost:5174> and proxies `/api/*` and `/ws/*` to a
relay on `127.0.0.1:8080`. Override the relay port with `RELAY_PORT=…`.

You need a relay (and a daemon) running — see `services/README.md`. Note
that the relay requires `RELAY_DATABASE_URL`; it will not start without
a Postgres DSN.

```bash
npm run lint     # eslint
npm run build    # → apps/web/dist/
npm run preview  # serve the built bundle
```

In production the bundle is baked into the relay image, so **any web
change needs a relay image rebuild**:

```bash
cd deploy && docker compose build relay && docker compose up -d --force-recreate relay
```

## Project layout

```
apps/web/
├── index.html
├── vite.config.js            dev server + /api,/ws proxy
├── eslint.config.js
└── src/
    ├── main.jsx
    ├── App.jsx               app shell: screen routing, sidebars, Jump picker wiring
    ├── config.js             screens, statuses, permission chips, fallback model list
    ├── styles.css            the entire stylesheet
    ├── api/
    │   ├── relayClient.js    REST wrapper around the relay
    │   └── sessionSocket.js  /ws/client socket with reconnect + last_seq
    ├── hooks/
    │   ├── useRemotex.js     the whole state machine: reducer, frame handling, actions
    │   └── useBackgroundCompletionAlert.js
    ├── screens/
    │   ├── DashboardScreen.jsx   host + thread landing surface
    │   ├── SessionScreen.jsx     chat surface
    │   └── FilesScreen.jsx       standalone file browser
    ├── components/
    │   ├── Composer.jsx          chip row + textarea + send/stop
    │   ├── Pickers.jsx           model / effort / permission chips
    │   ├── SendOrStopButton.jsx
    │   ├── EventStream.jsx, EventRow.jsx
    │   ├── PendingPromptsPanel.jsx   approvals + user-input dialogs
    │   ├── JumpPicker.jsx        folder picker (search / teleport / browse)
    │   ├── WorkspaceFilesDrawer.jsx, FileRow.jsx
    │   ├── HostsSidebar.jsx, HostRow.jsx, DashboardHeader.jsx
    │   ├── RightSidebar.jsx, TelemetrySidebar.jsx, Sparkline.jsx
    │   ├── ResumingBanner.jsx, CopyButton.jsx, Toast.jsx
    └── util/
        markdown.jsx, path.js, slash.js, url.js, fuzzy.js,
        copy.js, time.js, host.js, folderHistory.js
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

Tracked in the root `README.md` and `services/docs/production_plan.md`:

- Real OIDC login, replacing the demo bearer token.
- Push notifications for approvals that arrive while the tab is closed.
