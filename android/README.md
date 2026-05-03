# Remotex Android

Native Android client for the Remotex relay. Kotlin + Jetpack Compose,
OkHttp for REST + WebSocket, kotlinx.serialization for JSON.

## Status

**Skeleton**, not a finished app. What works right now:

- Single-activity Compose UI in Remotex's dark/amber palette.
- `RelayClient` talks to `/api/hosts` and `/api/sessions` over HTTP.
- `openSessionSocket` attaches to `/ws/client`, sends the hello
  envelope, and emits raw JSON frames as a Kotlin Flow.
- `RemotexViewModel` wires it up with a simple state machine.

What's **not** done (tracked in the root `README.md` roadmap):

- Parsing `session-event` frames into structured items (reasoning,
  tool call, agent message) and rendering them like the web app.
- Composer / turn submission UI.
- Approval flow with FCM push.
- OIDC login — the token is still a plain `Bearer`.

## Build

Requirements:

- JDK 17 (OpenJDK or Temurin works).
- Android SDK with `platforms;android-35` + `build-tools;34.0.0`.
  Android Studio installs both; headless machines can use
  `cmdline-tools/latest/bin/sdkmanager`.

Set `sdk.dir` by copying the template:

```bash
cp android/local.properties.example android/local.properties
# Edit if your SDK lives somewhere other than /opt/android-sdk.
```

Then (wrapper is already committed, no bootstrap needed):

```bash
cd android
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk  (~17 MB)
./gradlew installDebug       # to a connected device/emulator
./gradlew lint               # Android lint
```

CI builds `assembleDebug` on every PR + push to main and uploads the
APK as a workflow artifact — grab it from the Actions run summary
without needing a local toolchain.

### Pointing at your relay

The debug build defaults to `http://10.0.2.2:8080` — the magic
address the Android emulator uses to reach the host machine's
loopback. **For a real phone on your LAN this default is wrong** —
the device can't resolve `10.0.2.2`, so the app shows "connecting…"
forever.

The wrapper script `./build.sh` figures out the right URL for you
by reading the host's first non-loopback IPv4 address and the relay
port from `deploy/.env`:

```bash
cd android
./build.sh              # build only — prints the chosen URL
./build.sh install      # also `adb install -r` to the connected device
./build.sh install 192.168.10.50    # override the IP
RELAY_URL=https://relay.example.com ./build.sh    # full URL override
```

If you'd rather drive Gradle directly:

```bash
# Find your LAN IP:
ip -4 addr show scope global | awk '/inet / {print $2}' | cut -d/ -f1
# Find the relay port (default 8080, our deploy/.env pins 18080):
grep RELAY_HOST_PORT deploy/.env

./gradlew assembleDebug -PrelayUrl=http://192.168.x.y:18080
```

The value is baked into `BuildConfig.RELAY_URL` at compile time —
there's no runtime relay-picker, so you have to rebuild + reinstall
to point at a different server.

#### Connecting ADB to a phone over Wi-Fi

```bash
adb connect 192.168.x.y:PORT     # PORT is shown in Wireless debugging on the phone
adb devices                      # confirm the device shows up
./build.sh install
```

## Project layout

```
android/
├── settings.gradle.kts
├── build.gradle.kts            root (plugin versions only)
├── gradle.properties
├── gradle/wrapper/
│   └── gradle-wrapper.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/
        │   ├── values/ (strings, colors, themes)
        │   └── xml/    (backup + data-extraction rules)
        └── java/app/remotex/
            ├── MainActivity.kt
            ├── model/Models.kt          Serializable DTOs
            ├── net/RelayClient.kt       REST
            ├── net/SessionSocket.kt     WebSocket → Flow<SocketEvent>
            └── ui/
                ├── RemotexApp.kt
                ├── RemotexViewModel.kt
                └── theme/Theme.kt
```

## CI

`.github/workflows/ci.yml` has an `android` job that runs on every
push / PR to `main`:

1. `actions/setup-java@v4` with Temurin 17.
2. `android-actions/setup-android@v3` installs
   `platforms;android-35`, `build-tools;34.0.0`, `platform-tools`.
3. `~/.gradle` cache keyed on the wrapper + gradle file hashes.
4. `./gradlew assembleDebug --no-daemon --stacktrace`.
5. Uploads `app-debug.apk` as a workflow artifact.

Failing the Android build fails the PR status check.
