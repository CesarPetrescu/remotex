# Remotex web client

The real control-plane web app — the one daemons report to and the
one you actually drive sessions from. Replaces the single-file
`services/web/index.html` with a proper React + Vite project split
across components, hooks, and a thin API layer.

## What it does today

- Loads hosts from `/api/hosts` (demo token pre-filled).
- Lets you pick an online host and open a session against it.
- Attaches to `/ws/client`, sends `turn-start` frames, renders the
  streamed Codex events (reasoning, tool calls, agent messages with
  delta streaming).
- Persists the user token in `localStorage`.

## What's still TODO

Tracked in the root `README.md` roadmap:

- Real OIDC login (Keycloak) replacing the demo bearer token.
- Approval UX wired to daemon approval frames.
- Session resume.
- Multi-session tabs (the wireframes in `src/panels/DesktopPanel.jsx`
  explore the UX; not implemented here yet).

## Dev

```bash
cd apps/web
npm install
npm run dev
```

The dev server runs on `http://localhost:5174` and proxies
`/api/*` + `/ws/*` to a relay on `http://127.0.0.1:8080`.

To run the relay locally first:

```bash
cd services
pip install -r requirements.txt
python3 relay/app.py                # binds 127.0.0.1:8080 by default
# (then, in another terminal, start a daemon; see services/README.md)
```

## Build

```bash
npm run build
```

Writes static assets into `apps/web/dist/`. Serve them behind the
relay in production (Caddy can do both in the same vhost — see
`deploy/README.md`).

## Project layout

```
apps/web/
├── index.html
├── package.json
├── vite.config.js
├── eslint.config.js
└── src/
    ├── main.jsx
    ├── App.jsx                app shell + state wiring
    ├── styles.css
    ├── api.js                 REST client against the relay
    ├── ws.js                  WebSocket client for /ws/client
    ├── components/
    │   ├── Header.jsx
    │   ├── HostList.jsx
    │   ├── EventStream.jsx
    │   ├── Composer.jsx
    │   └── Toast.jsx
    └── hooks/
        ├── useHosts.js
        └── useSession.js
```
