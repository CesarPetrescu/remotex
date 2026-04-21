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

You need Android Studio (Giraffe+) or command-line Android tooling.

First time only, generate the Gradle wrapper:

```bash
cd android
# With Gradle 8.11.1 already installed on the host:
gradle wrapper --gradle-version 8.11.1
# ...or open the folder in Android Studio and it'll do this for you.
```

Then:

```bash
./gradlew assembleDebug                      # builds app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug                       # installs to a connected device/emulator
./gradlew lint                               # Android lint
./gradlew lint assembleDebug --no-daemon     # what the CI job runs
```

### Pointing at your relay

The debug build defaults to `http://10.0.2.2:8080` — the magic
address the Android emulator uses to reach the host machine's
loopback. So:

```bash
# Terminal 1 (on the host)
cd prototype
pip install -r requirements.txt
python3 relay/app.py    # 127.0.0.1:8080

# Then Android Studio → Run `app` on an emulator.
```

For a real device on your LAN or a deployed relay:

```bash
./gradlew assembleDebug -PrelayUrl=https://relay.example.com
```

The value is baked into `BuildConfig.RELAY_URL`.

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

`.github/workflows/ci.yml` has a gated Android job that auto-activates
as soon as `android/gradlew` exists. Run `gradle wrapper` once, commit
the wrapper files, and the next push to `main` / PR will build the
debug APK on every change.
