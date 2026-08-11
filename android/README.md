# Remotex Android

The native Android client is built with Kotlin and Jetpack Compose. OkHttp
handles REST and WebSocket transport; kotlinx.serialization handles relay
frames.

## Current coverage

The app supports the main web workflow:

- Provider setup at runtime, live host inventory, host-scoped Codex models,
  saved-thread listing, preview, resume, and paged transcript history.
- Streamed user, reasoning, command, file-change, MCP, dynamic-tool, and agent
  items, including progressive diffs and replay-gap markers.
- Text and image turns, active-turn steer or stop, and a removable local FIFO
  follow-up queue that snapshots its images and model/effort/permission
  settings.
- Model, reasoning-effort, and permission controls resolved against the
  selected host.
- Ordered approval and user-input queues, authoritative decision lists,
  secret input fields, and multi-client resolution handling.
- `/plan`, `/default`, `/cd`, `/pwd`, `/compact`, `/goal`, and `/collab`, plus
  goal get/set/pause/resume/clear handling.
- Jump-style workspace selection with recents and favorites, directory
  creation, upload, download, rename, and delete.
- Session replay and reconnect with stable sequence cursors, owner-scoped
  inventory invalidations, provider-scoped active-session restoration after
  process recreation, host telemetry, dark/light/high-contrast themes, a
  foreground service during turns, completion notifications, and notification
  actions to return to or stop a chat.

The remaining platform gaps are at the bottom of this file. The most important
one is remote push: local notifications work while Android is retaining the
session, but the relay has no FCM path for an approval arriving after the app
has been fully stopped.

## Provider and token storage

Public release APKs contain no operator URL and no demo credential. On first
launch, enter the base URL of the relay you intend to use and its user bearer
token. A release build requires an HTTPS relay; cleartext LAN and emulator
URLs are debug-only.

The relay URL is saved as an ordinary app preference because it is not a
secret. The token is encrypted with AES-GCM using a non-exportable key from
Android Keystore. Each normalized relay base URL gets a separate key and
ciphertext slot, so switching providers does not send one relay's token to
another. The token UI masks it by default and provides explicit reveal and
clear actions. Auth preferences are excluded from Android backup; if a
device-bound key is missing or invalid, the app fails signed out.

Changing the relay URL rebuilds the ViewModel and network clients against the
new provider. It does not require rebuilding or reinstalling the APK.
The URL is parsed at the network boundary before a bearer token can be
attached; embedded credentials, query strings, and fragments are rejected.

## Install a release APK

Download `remotex-<version>.apk` from
[GitHub Releases](https://github.com/CesarPetrescu/remotex/releases), then
install it with Android's package installer or ADB:

```bash
adb install remotex-v0.2.0.apk
```

Releases before `v0.2.0` were signed with Android's debug key. Android cannot
upgrade an installed package across signing identities, so users of one of
those builds must uninstall it once before installing the new release-signed
line. Uninstalling clears that app's local settings.

Verify the APK against `SHA256SUMS.txt` and the GitHub build-provenance
attestation published with the release.

## Requirements for local builds

- JDK 17 (OpenJDK or Temurin).
- Android SDK with `platforms;android-35` and `build-tools;34.0.0`.
- `adb` when installing to a device.

Set the SDK path once:

```bash
cp android/local.properties.example android/local.properties
```

### Use `./build.sh` for a phone build

The debug build's bare-Gradle default is `http://10.0.2.2:8080`, the Android
emulator alias for the host's loopback. `build.sh` instead detects a local
SparkTunnel hostname or a reachable LAN address and supplies it as the app's
initial URL:

```bash
cd android
./build.sh
./build.sh install
./build.sh install 192.168.10.50
RELAY_URL=https://relay.example.com ./build.sh install
```

The value is only a starting point and remains editable in the app. Bare
Gradle is appropriate for an emulator or an explicit test relay:

```bash
./gradlew assembleDebug
./gradlew assembleDebug -PrelayUrl=http://192.168.x.y:18080
./gradlew installDebug
./gradlew test
./gradlew lint
```

The release build's default `BuildConfig.RELAY_URL` is deliberately empty.
`-PversionName=<label>` sets the APK version name; semver names also derive a
monotonic version code, and `-PversionCode=<int>` can override it.

### Release signing

An installable production APK must use a long-lived private key. The Gradle
build reads signing material only from the environment:

```bash
export ANDROID_KEYSTORE_PATH=/secure/path/remotex-release.jks
export ANDROID_KEYSTORE_PASSWORD='...'
export ANDROID_KEY_ALIAS='...'
export ANDROID_KEY_PASSWORD='...'
./gradlew assembleRelease -PversionName=v0.2.0 -PrelayUrl=
```

Keep that keystore and its passwords out of the repository and back them up
securely. Losing the key means later APKs cannot upgrade existing installs.
The GitHub release workflow restores the same four values from repository
secrets, requires the release certificate's pinned SHA-256 fingerprint,
verifies APK Signature Scheme v2, and scans the artifact for the maintainer's
relay hostname before upload. The current release certificate fingerprint is
`CE:05:63:93:15:3F:7E:AA:66:69:BC:0E:8C:9B:C8:35:E0:D7:B6:2F:81:73:AA:F8:4B:EC:A6:9B:AE:AA:51:37`.
Forks must set the `ANDROID_RELEASE_CERT_SHA256` repository variable to their
own long-lived release certificate fingerprint instead of copying this value.

### Cleartext HTTP is debug-only

The release network-security config rejects plaintext HTTP. The debug build
overrides it so emulator and private-LAN development remain possible. Use
HTTPS for anything public; the bearer token, prompts, and output otherwise
travel in cleartext.

### Wireless ADB

```bash
adb connect 192.168.x.y:PORT
adb devices
./build.sh install
```

## Architecture

```text
MainActivity                         provider + theme preferences, deep links
└── RemotexApp                       screen navigation and app shell
    ├── HostsScreen                  relay/token setup + live inventory
    ├── ThreadsScreen                saved chats
    ├── FilesScreen                  working-directory picker
    └── SessionScreen                events, prompts, files, composer
         │
         ▼
    RemotexViewModel                 session state machine and reducer
    ├── RelayClient                  REST
    ├── InventorySocket              `/ws/inventory` invalidations
    ├── SessionSocket                `/ws/client` event stream
    ├── SecureTokenStore             relay-scoped Android Keystore storage
    ├── ActiveSessionStore           scoped session id/thread/replay cursor
    └── SessionForegroundService     running-turn lifecycle + notifications
```

`RemotexViewModel.kt` deliberately mirrors
`apps/web/src/hooks/useRemotex.js`: frame semantics, pending-prompt queues,
replay cursors, and turn state must remain compatible across clients.

## Tests and CI

The smallest complete local check is:

```bash
cd android
./gradlew test lint assembleDebug
```

JVM tests cover REST/WebSocket shapes, inventory invalidation, provider-scoped
token behavior, content-size handling, prompt ordering, reducer parity, and
composer behavior. `androidTest` contains release-critical Compose flows for a
device or emulator.

On pushes and pull requests, CI builds the debug APK, runs the JVM suite and
lint, and uploads the APK or lint report as appropriate. Stable tags and
nightly publishing run tests and lint again, restore the release key, build a
provider-neutral signed APK, and publish it through the single atomic release
job.

## Current limits

- Authentication is still a long-lived relay bearer token; OIDC is not
  implemented.
- There is no FCM delivery for approvals while the app is fully stopped.
- One native session is presented at a time, and its local follow-up queue is
  not shared with other attached clients.
- Releases currently publish a universal sideload APK, not a Play Store AAB.
