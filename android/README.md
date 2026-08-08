# Remotex Android

Native Android client for the Remotex relay. Kotlin + Jetpack Compose,
OkHttp for REST + WebSocket, kotlinx.serialization for JSON.

## Status

Feature-complete against the web client apart from push notifications.
What works:

- Host list with online state, `POST /api/sessions`, and thread list /
  resume with transcript replay and a "resuming" banner.
- Full session surface: streamed reasoning, tool calls, file changes,
  MCP tool calls, and agent messages, grouped per turn with markdown
  rendering.
- Composer with model, reasoning-effort, and permission pickers, image
  attachments, send/stop, and turn interrupt.
- Approval dialogs and Codex user-input dialogs.
- Slash commands (`/plan`, `/default`, `/cd`, `/pwd`, `/compact`,
  `/goal`, `/collab`) plus direct `goal-get` / `goal-set` / `goal-clear`
  frames.
- Workspace file browsing with the Jump folder picker (search-first, with
  recents and favorites), folder creation, and file upload.
- Reconnect with `last_seq` replay; a foreground service and
  notifications so a long turn survives the app being backgrounded, with
  a cancel-turn action from the notification.

Not done — tracked in the root `README.md` and
`services/docs/production_plan.md`:

- FCM push for approvals that arrive while the app is closed.
- OIDC login — the token is still a plain bearer string.
- Runtime relay switching (the URL is a compile-time constant; see below).

## Build

Requirements:

- JDK 17 (OpenJDK or Temurin).
- Android SDK with `platforms;android-35` + `build-tools;34.0.0`.
  Android Studio installs both; headless machines can use
  `cmdline-tools/latest/bin/sdkmanager`.

Set `sdk.dir` by copying the template:

```bash
cp android/local.properties.example android/local.properties
# Edit if your SDK lives somewhere other than /opt/android-sdk.
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
        ├── test/java/app/remotex/     JVM tests: RelayClient, SessionSocket, Markdown
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
                    ├── components/            pickers, token field, status bar
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

Failing the Android build fails the PR status check. Note that CI builds
the APK but does **not** run `./gradlew test`; run the unit tests locally.
