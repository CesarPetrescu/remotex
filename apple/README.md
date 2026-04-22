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

The default relay URL is `http://127.0.0.1:8080`, which works for the iOS
simulator when the relay is running on the same Mac. For a real iPhone,
use a LAN or public relay URL from inside the app.

For a real iPhone on your LAN, start the relay on a reachable interface:

```bash
cd services
python3 relay/app.py --host 0.0.0.0 --port 8080
```

Then enter `http://<your-mac-lan-ip>:8080` in the app.

The default user token is the prototype token:

```text
demo-user-token
```

## Current Scope

Working starter pieces:

- Relay URL and user token fields
- Host list
- Session open and WebSocket attach
- Text prompt sending
- Stream rendering for user, reasoning, tool, agent, and system events
- Basic error handling

Still to add for Android parity:

- Thread list and resume
- Image attachments
- Model and reasoning effort controls
- Permissions controls
- Approval dialogs
- Turn interrupt
- Reconnect backoff
- Push notifications for approval requests

## Layout

```text
apple/
|-- Remotex.xcodeproj/
`-- Remotex/
    |-- RemotexApp.swift
    |-- ContentView.swift
    |-- RemotexViewModel.swift
    |-- RelayClient.swift
    |-- SessionSocket.swift
    |-- Models.swift
    |-- Theme.swift
    |-- Info.plist
    `-- Assets.xcassets/
```
