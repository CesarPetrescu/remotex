# Remotex for iPhone

The native iPhone client is built with SwiftUI, `URLSession`, and
`URLSessionWebSocketTask`; it has no third-party runtime dependencies.

## Current coverage

The app supports the main remote Codex session workflow:

- Runtime relay setup, online hosts, host-scoped model options, a remote
  working-directory picker, saved-thread preview/resume, and paged history.
- Streamed user, reasoning, command/file-change, and agent items with
  Markdown, progressive diffs, replay-gap markers, and a visible fallback row
  for other normalized Codex item types.
- Model, reasoning-effort, and permission controls; token usage and native
  goal progress in the session header.
- Text and Photos Picker image turns, active-turn steer or interrupt, and a
  removable local FIFO follow-up queue that snapshots images and settings.
- Ordered approval and user-input queues, authoritative decision choices,
  secret response fields, and multi-client resolution handling.
- `/plan`, `/default`, `/cd`, `/pwd`, `/compact`, `/goal`, and `/collab`, plus
  goal inspect/set/pause/resume/clear controls.
- Stable client identity, assigned replay cursors, heartbeat, bounded reconnect
  backoff, foreground recovery, fatal-error handling, explicit session close,
  and provider-scoped active-session restoration after process recreation. A
  recreated process requests the available relay tail from sequence zero so
  it can rebuild UI state without storing a plaintext transcript on-device;
  the normal replay-gap marker remains visible if the 1000-frame buffer wrapped.
- Live `/ws/inventory` invalidations backed by authoritative REST refreshes for
  hosts and saved threads.
- Host telemetry with history and all reported GPUs, system/light/dark/high-
  contrast themes, and writable workspace browsing: upload, share/save,
  create folder, rename, and confirmed file deletion.
- A local turn-completion notification while the app remains alive in the
  background. Permission is first requested only after a session connects.

The remaining differences from web and Android are listed below. In
particular, there is no APNs delivery when an approval arrives after the app
is suspended or fully stopped.

## Provider and token storage

A fresh install starts with no relay URL and no demo token. Enter the base URL
and bearer token supplied by the operator of the relay you intend to use.
Public relays should use HTTPS.

The relay URL is stored in `UserDefaults`; it is not a secret. The bearer token
is a Keychain generic-password item under an account derived from the
normalized relay URL. Switching providers therefore loads that provider's
saved token or an empty field instead of sending the previous relay's token to
a new server. **Sign out** deletes the current provider's token.

An older plaintext `UserDefaults` value is moved out immediately. Legacy
unscoped Keychain data is moved into a provider account only after a valid
relay scope exists, and an unscoped value is never sent.

## Run from Xcode

Requirements:

- Xcode 16 or newer.
- iOS 17 or newer.
- A running Remotex relay and at least one connected daemon.

```bash
open apple/Remotex.xcodeproj
```

Pick an iPhone simulator or a signing team/device and press Run. For a relay on
the simulator's Mac, enter `http://localhost:8080`. A physical iPhone can use
the Mac's LAN URL after granting the local-network permission, or an HTTPS
public URL.

App Transport Security is not globally disabled. `NSAllowsLocalNetworking`, a
`localhost` exception, and `NSLocalNetworkUsageDescription` cover loopback,
`.local`, and private-LAN development. Plain HTTP to a public address remains
blocked; put that relay behind TLS.

Demo credentials exist only for an explicitly loopback-only development relay
started with `RELAY_SEED_DEMO=1`; public app builds do not contain them.

## Install the release IPA

[GitHub Releases](https://github.com/CesarPetrescu/remotex/releases) publishes
`remotex-<version>.ipa`. It is an unsigned build artifact, not an App Store or
TestFlight package. Re-sign it with your own Apple identity and provisioning
profile using a sideloading workflow that you trust before installation.

Verify the IPA against `SHA256SUMS.txt` and the GitHub build-provenance
attestation. The project does not currently ship an Apple distribution
certificate, provisioning profile, or automatic update channel.

## Build and test from the command line

Build without code signing:

```bash
xcodebuild \
  -project apple/Remotex.xcodeproj \
  -scheme Remotex \
  -configuration Debug \
  -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO \
  build
```

Run the XCTest target on an installed simulator:

```bash
xcodebuild \
  -project apple/Remotex.xcodeproj \
  -scheme Remotex \
  -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 16 Pro,OS=latest' \
  CODE_SIGNING_ALLOWED=NO \
  test
```

The tests drive URL/query construction, relay-scoped credential helpers,
WebSocket frames and retry rules, reducer replay/prompt/settings behavior,
inventory URL/hello behavior, file size/name/multipart boundaries, persisted
session metadata, completion-notification gating, theme cycling, queue
acknowledgement, images, slash commands, goals, and resolved session state. CI
runs both simulator build and XCTest whenever `apple/**` or the iOS workflow
changes. Stable tags and nightly publishing repeat the tests before packaging
the unsigned device app as an IPA.

## Layout

```text
apple/
├── Remotex.xcodeproj/
├── RemotexTests/              reliability + frame/reducer tests
└── Remotex/
    ├── RemotexApp.swift
    ├── ContentView.swift      provider, hosts, session, composer
    ├── RemotexViewModel.swift session state machine and reducer
    ├── RelayClient.swift      REST
    ├── SessionSocket.swift    reconnecting `/ws/client`
    ├── InventorySocket.swift  reconnecting `/ws/inventory`
    ├── Keychain.swift         provider-scoped token storage
    ├── PendingPromptsView.swift
    ├── WorkspaceFilesView.swift
    ├── TelemetryView.swift
    ├── Markdown.swift
    ├── DiffView.swift
    ├── Models.swift
    ├── Theme.swift
    └── Info.plist
```

## Current limits

- Authentication is still a long-lived relay bearer token; OIDC is not
  implemented.
- There is no APNs delivery. Local completion notification works only while
  iOS keeps the app and WebSocket alive; suspension or termination prevents
  the completion event from reaching the phone.
- Workspace deletion intentionally covers files only because the relay refuses
  directory deletion. Start-directory selection remains browse-only.
- One native session is presented at a time, and its local follow-up queue is
  not shared with other attached clients.
