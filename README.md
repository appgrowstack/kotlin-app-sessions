# kotlin-app-sessions

Records what people do inside an Android app, one session at a time: screens, taps, rage taps, quick backs, scroll depth, hangs and how each session ended.

English | [Japanese](README.ja.md)

## Features

- **Sessions split the usual way.** A launch starts one, and so does coming back after 30 minutes in the background
- **Screens from your analytics events.** `screen_viewed` moves to its screen. Going back to the screen under the current one is a `pop`, recorded with `back` and, within 2 seconds of entering, `quick_back`. The back key is noted as the `via`
- **Taps without touching your touches.** Taps are read through `Window.Callback` on every activity's window, and every touch and key goes on to the app unchanged. Positions are 0–10000 of the window. `Modifier.sessionTarget("…")` names an element
- **Rage taps.** Three or more taps within 1 second and 30 dp of the first
- **Scroll depth.** `Modifier.sessionScroll(state, "…")` for a `verticalScroll` or a `LazyColumn`
- **How it ended.** `ApplicationExitInfo` from API 30, the SDK's crash handler before it, and a watchdog thread for hangs, decided at the next launch
- **Your app's events, kept carefully.** Events of the shared catalog keep their values; your own events keep only the properties you list in `keepProps`. Text longer than 48 characters is dropped
- **Survives restarts and no network.** What is not sent yet is kept and sent later with the same `seq`, so the receiver can drop duplicates
- **Same rules as the iOS package.** `SessionRules` gives the same answers as swift-app-sessions on the shared tables
- **Few dependencies.** `sessions-core` uses Kotlin's standard library only; `sessions-compose` adds the Android SDK and Compose ui and foundation

The light record keeps no pixels. `Modifier.sessionMask()` and `Modifier.sessionNoCapture()` only place marks.

### Where the records go

`AppSessions` hands each batch to a `SessionSink`, on its own sending thread:

| Sink | What it does |
|---|---|
| `FileSessionSink` | One JSON line per batch in a file per session, oldest removed past 20 MB. `files()` for a tester screen to share |
| `DebugSessionSink` | The last batches in memory (`batches`), each also printed |
| your own | `fun send(batch: SessionBatch): SessionSinkReply`. Throw to keep the batch for later; answer `SessionSinkReply.Disabled` to stop the session |

### Testing

`sessions-core` runs on the JVM, so the app's headless tests can use it. Test doubles are in `com.appgrowstack.sessions.testing`.

## Quick Start

```kotlin
import android.app.Application
import com.appgrowstack.sessions.AppSessions
import com.appgrowstack.sessions.FileSessionSink
import com.appgrowstack.sessions.FileSessionStateStore
import com.appgrowstack.sessions.SessionDeviceInfo
import com.appgrowstack.sessions.SessionValue
import com.appgrowstack.sessions.compose.SessionCapture
import com.appgrowstack.sessions.compose.current

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val store = FileSessionStateStore(filesDir)
        val sessions = AppSessions(
            sink = FileSessionSink(filesDir), // keep the records on the device
            deviceInfo = { SessionDeviceInfo.current(this, channel = "play") },
            store = store,
        )
        SessionCapture.install(this, sessions, store) // starts the session
        sessions.observe("screen_viewed", mapOf("screen" to SessionValue.Text("home")))
    }
}
```

### Connecting to a server

The package works on its own. To connect it to a server, add the matching module of the connector package [kotlin-appgrowstack](https://github.com/appgrowstack/kotlin-appgrowstack) instead of wiring a server here.

## Installation

The modules are published through JitPack. Add the repository in `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

Then add the module you use in the app's `build.gradle.kts`:

```kotlin
dependencies {
    // Android: capture and the Compose modifiers (includes sessions-core)
    implementation("com.github.appgrowstack.kotlin-app-sessions:sessions-compose:0.1.0")
    // Kotlin/JVM only, without Android
    // implementation("com.github.appgrowstack.kotlin-app-sessions:sessions-core:0.1.0")
}
```

### Modules

| Module | What it has |
|---|---|
| `sessions-core` | Splitting sessions, the rules (`SessionRules`), the recorder `AppSessions`, batches, `SessionSink` with `FileSessionSink` and `DebugSessionSink`, `FileSessionStateStore`, `HangMonitor` and test doubles |
| `sessions-compose` | `SessionCapture`, `SessionDeviceInfo.current`, `Modifier.sessionTarget`, `sessionScroll`, `sessionMask` and `sessionNoCapture` |

## Requirements

- `sessions-core`: JDK 17+
- `sessions-compose`: Android API 26+ (minSdk 26) and Jetpack Compose

## License

MIT. See [LICENSE](LICENSE).
