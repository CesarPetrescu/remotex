# remotex

codex but remote

## Wireframes

Low-fi design exploration for Remotex — a remote-control client for your Codex
daemons. Four surfaces (Systems architecture · Desktop Web · Mobile Web ·
Android), three variants each.

```bash
npm install
npm run dev      # http://localhost:5173
npm run build
```

### Layout

- `src/App.jsx` — tabbed shell (Systems / Desktop / Mobile / Android) + tweaks
  panel (accent color, grain, density, hand-font annotations, pins).
- `src/panels/SystemsPanel.jsx` — topology, sequence flow, trust/credential map.
- `src/panels/DesktopPanel.jsx` — tri-pane workspace, cockpit, document layout.
- `src/panels/MobilePanel.jsx` — list→session, approval bottom sheet, terminal
  cockpit.
- `src/panels/AndroidPanel.jsx` — Material 3 hosts, lock-screen push + sheet,
  QR pairing + settings.
- `src/styles.css` — shared tokens, frame chrome, annotation marks.

Stack: Vite + React 18.
