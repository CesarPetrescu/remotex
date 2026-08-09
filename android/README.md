# Remotex Android

The native Android client is built with Kotlin and Jetpack Compose. OkHttp
handles REST and WebSocket transport; kotlinx.serialization handles relay
frames.

It is a working client, not a skeleton.

## Status

Feature-complete against the web client apart from push notifications.
What works:

- Host list with online state, `POST /api/sessions`, and thread list /
  resume with transcript replay and a "resuming" banner.
- Full session surface: streamed reasoning, tool calls, file changes,
  MCP tool calls, and agent messages, grouped per turn with markdown
  rendering.
- Composer with model, reasoning-effort, and permission pickers, image
  attachments, send/steer/stop, and turn interrupt. The model list comes
  from `GET /api/hosts/{host_id}/models`; if the host cannot supply it,
  the default entry lets Codex choose.
- Approval dialogs and Codex user-input dialogs.
- Slash commands (`/plan`, `/default`, `/cd`, `/pwd`, `/compact`,
  `/goal`, `/collab`) plus direct `goal-get` / `goal-set` / `goal-clear`
  frames.
- Workspace file browsing with the Jump folder picker (search-first, with
  recents and favorites), folder creation, and file upload.
- Reconnect with `last_seq` replay; a foreground service and
  notifications so a long turn survives the app being backgrounded, with
  a cancel-turn action from the notification.

Not done — tracked in the root `README.md` and under "Known gaps" in
`services/docs/architecture.md`:

- FCM push for approvals that arrive while the app is closed.
- OIDC login — the token is still a plain bearer string.
- Runtime relay switching (the URL is a compile-time constant; see below).

## Architecture

```text
MainActivity
└── RemotexApp                         screen navigation and app shell
    ├── HostsScreen
    ├── ThreadsScreen
    ├── FilesScreen                    working-directory picker
    └── SessionScreen                  events, prompts, files, composer
         │
         ▼
    RemotexViewModel                   state machine and event reducer
    ├── RelayClient                    REST calls
    ├── SessionSocket                  `/ws/client` WebSocket → SharedFlow
    └── SessionForegroundService       lifecycle and notifications
```

The ViewModel mirrors the web client's normalized session state. It owns the
active socket, remembers the last event sequence for replay, and exposes UI
actions; Compose screens remain presentation-focused.

## Requirements

- JDK 17 (OpenJDK or Temurin).
- Android SDK with `platforms;android-35` + `build-tools;34.0.0`.
  Android Studio installs both; headless machines can use
  `cmdline-tools/latest/bin/sdkmanager`.
- `adb` when installing to a device.

Set the SDK path once:

```bash
cp android/local.properties.example android/local.properties
```

### Use `./build.sh`, not bare Gradle

The debug build's default relay URL is `http://10.0.2.2:8080` — the magic
address the **emulator** uses to reach the host's loopback. A real phone
can't resolve it, so the app sits on "connecting…" forever. `build.sh`
picks the right URL for you from the host's first non-loopback IPv4
address and `RELAY_HOST_PORT` in `deploy/.env` (default 8080):

```bash
cd android
./build.sh                          # build only — prints the chosen URL
./build.sh install                  # also `adb install -r` to the connected device
./build.sh install 192.168.10.50    # override the auto-detected IP
RELAY_URL=https://relay.example.com ./build.sh   # full URL override
```

Bare Gradle is fine for an emulator, or when you pass the URL yourself:

```bash
./gradlew assembleDebug             # emulator only (10.0.2.2:8080)
./gradlew assembleDebug -PrelayUrl=http://192.168.x.y:18080
./gradlew installDebug
./gradlew test                      # JVM unit tests
./gradlew lint                      # Android lint
```

The value is baked into `BuildConfig.RELAY_URL` at compile time — there's
no runtime relay picker, so switching servers means rebuild + reinstall.

`-PversionName=<label>` sets the APK's internal version (the release
workflow passes the tag or the nightly label); without it the build falls
back to `0.1.0`. `versionCode` is derived from a `vX.Y.Z` name
(`v1.2.3` → `10203`) and stays `1` for nightlies and local builds;
`-PversionCode=<int>` overrides it.

### Cleartext HTTP is debug-only

The app ships a network security config instead of a blanket
`usesCleartextTraffic`: release builds refuse plaintext HTTP entirely
(`src/main/res/xml/network_security_config.xml`), and the debug build type
overrides it (`src/debug/res/xml/network_security_config.xml`) so the
`http://<LAN-IP>:<PORT>` and `10.0.2.2` workflows above keep working.
Android's config matches literal hosts only — it has no CIDR syntax — so
the debug override permits cleartext broadly rather than trying to
enumerate RFC1918 ranges. A relay reachable over `https://` needs no
exception in either build type.

Find the pieces by hand if you need them:

```bash
ip -4 addr show scope global | awk '/inet / {print $2}' | cut -d/ -f1
grep RELAY_HOST_PORT deploy/.env      # if you've set one; otherwise 8080
```

### Connecting ADB to a phone over Wi-Fi

```bash
adb connect 192.168.x.y:PORT     # PORT is shown in Wireless debugging on the phone
adb devices                      # confirm the device shows up
./build.sh install
```

## Project layout

```
android/
├── build.sh                    relay-URL-aware build wrapper — prefer this
├── settings.gradle.kts
├── build.gradle.kts            root (plugin versions only)
├── gradle.properties
├── gradle/wrapper/
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/
        ├── debug/res/xml/             debug-only network security config
        ├── test/java/app/remotex/     JVM tests: RelayClient, SessionSocket,
        │                              PromptQueue, Markdown
        └── main/
            ├── AndroidManifest.xml
            ├── res/                    values (strings, colors, themes), xml, mipmaps
            └── java/app/remotex/
                ├── MainActivity.kt
                ├── model/              Models, Thread, SessionEvent, FsEntry, Telemetry
                ├── net/
                │   ├── RelayClient.kt      REST
                │   └── SessionSocket.kt    WebSocket → Flow<SocketEvent>
                ├── service/
                │   ├── SessionForegroundService.kt   keeps turns alive in background
                │   ├── SessionNotifier.kt            turn + approval notifications
                │   ├── CancelTurnReceiver.kt         notification action
                │   └── RemotexEvents.kt
                └── ui/
                    ├── RemotexApp.kt          nav shell
                    ├── RemotexViewModel.kt    mirrors apps/web useRemotex.js
                    ├── Markdown.kt
                    ├── app/                   RemotexBar, StatusBadge
                    ├── components/            token field, status bar, formatters
                    ├── theme/Theme.kt
                    └── screens/
                        ├── hosts/             HostsScreen, HostRow
                        ├── threads/           ThreadsScreen, ThreadRow
                        ├── files/             FilesScreen, Breadcrumbs, NewFolderRow
                        └── session/
                            ├── SessionScreen.kt, MetaBar, ResumingBanner
                            ├── ApprovalDialog.kt, UserInputDialog.kt
                            ├── composer/      ComposerBar, CompactPickers, SendOrStopButton
                            ├── events/        EventList, AgentGroup, Renderers, UserBubble
                            └── files/         WorkspaceFilesPanel
```

`RemotexViewModel.kt` is a deliberate mirror of
`apps/web/src/hooks/useRemotex.js` — same event reducer, same state
shape. Changing frame handling in one usually means changing it in both.
That includes the pending-prompt queues (`pendingApprovals` /
`pendingUserInputs`: every unanswered prompt is kept in arrival order and
only the head is rendered) and the `replay-gap` marker the relay sends
when its replay buffer no longer covers a reconnecting client's cursor.

## CI

`.github/workflows/ci.yml` has an `android` job on every push / PR to
`main`:

1. `actions/setup-java@v4` with Temurin 17.
2. `android-actions/setup-android@v3`, then `sdkmanager` installs
   `platforms;android-35`, `build-tools;34.0.0`, `platform-tools`.
3. `~/.gradle` cache keyed on the wrapper + gradle file hashes.
4. `./gradlew assembleDebug --no-daemon --stacktrace`.
5. Uploads `app-debug.apk` as a workflow artifact — grab it from the
   Actions run summary without a local toolchain.
6. `./gradlew test` (JVM unit tests) and `./gradlew lint`. A lint failure
   uploads `android/app/build/reports/` as an artifact, since the summary
   line alone never says which file.

Failing any of those fails the PR status check.

## Current limits

- Authentication is still a compiled relay URL plus manually entered bearer
  token; OIDC is not implemented.
- Completion notifications are local. There is no FCM service for approval
  requests while the app is fully offline.
- The client presents one active session at a time.
- Relay changes require rebuilding and reinstalling the APK.
