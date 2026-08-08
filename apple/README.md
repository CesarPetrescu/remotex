# Remotex iPhone

Native iPhone client for the Remotex relay. This starter app mirrors the
core web and Android flow:

1. Enter a relay URL and user token.
2. Load online hosts from `/api/hosts`.
3. Open a session with `POST /api/sessions`.
4. Attach to `/ws/client`.
5. Send `turn-start` frames and render streamed `session-event` frames.

The app is intentionally dependency-free. It uses SwiftUI, `URLSession`
for REST, and `URLSessionWebSocketTask` for WebSocket transport.

## Requirements

- Xcode 16 or newer
- iOS 17 or newer target
- A running Remotex relay and daemon

## Run

```bash
open apple/Remotex.xcodeproj
```

Pick an iPhone simulator and press Run.

The default relay URL is `http://localhost:8080`, which works for the iOS
simulator when the relay is running on the same Mac. For a real iPhone,
use a LAN or public relay URL from inside the app.

App Transport Security is **not** globally disabled. The app sets
`NSAllowsLocalNetworking` plus an exception for `localhost`, which is what
lets plain `http://` reach a relay on the same Mac, on a `.local` name, or
on a private LAN address (`10.x`, `172.16-31.x`, `192.168.x`). A plain-http
relay on a *public* address is blocked by design — put it behind HTTPS.
That is also why the default is the `localhost` host name rather than the
`127.0.0.1` literal: unqualified host names are unambiguously covered by
`NSAllowsLocalNetworking`.

For a real iPhone on your LAN, start the relay on a reachable interface.
The relay needs a Postgres DSN — it refuses to start without one:

```bash
cd services
export RELAY_DATABASE_URL=postgresql://remotex:remotex-dev@127.0.0.1:5432/remotex
python3 relay/app.py --host 0.0.0.0 --port 8080
```

See `services/README.md` for a one-liner that brings up the database.
Then enter `http://<your-mac-lan-ip>:8080` in the app.

The default user token is the prototype token:

```text
demo-user-token
```

It only works against a relay started with `RELAY_SEED_DEMO=1`. The token
you type is stored in the Keychain, not in UserDefaults; a value left over
in UserDefaults by an older build is migrated across on first launch and
then deleted.

## Current Scope

Working starter pieces:

- Relay URL and user token fields (token kept in the Keychain)
- Host list
- Session open and WebSocket attach
- Text prompt sending, with send disabled while a turn is in flight
- Stream rendering for user, reasoning, tool, agent, and system events
- Approval and user-input prompts, queued so a second prompt never hides
  an unanswered one
- Replay-gap markers when the relay's buffer no longer covers a reconnect
- Basic error handling

Still to add for Android parity:

- Thread list and resume
- Image attachments
- Model and reasoning effort controls
- Permissions controls
- Turn interrupt
- Reconnect backoff
- Push notifications for approval requests

## CI

The `ios` job in `.github/workflows/ci.yml` builds against an iPhone
simulator on `macos-15`. Because those minutes are expensive it's gated
by a paths filter — it only runs when `apple/**` or the workflow file
itself changed — and caches DerivedData between runs.

## Layout

```text
apple/
|-- Remotex.xcodeproj/
`-- Remotex/
    |-- RemotexApp.swift
    |-- ContentView.swift
    |-- PendingPromptsView.swift
    |-- RemotexViewModel.swift
    |-- RelayClient.swift
    |-- SessionSocket.swift
    |-- Keychain.swift
    |-- Models.swift
    |-- Theme.swift
    |-- Info.plist
    `-- Assets.xcassets/
```
