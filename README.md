English | [日本語](./README.ja.md)

# kotlin-app-sessions

See what people do inside your Android app, one session at a time: which screens they went through
and how, where they tapped, where they tapped again and again or went straight back, how far they
scrolled, when the app stopped answering, and how each session ended. The records stay on the
device or go wherever you send them. The same rules and tables as swift-app-sessions.

> Developed in a private monorepo and copied here one way. Issues are welcome; pull requests are
> not accepted.

## Modules

| Module | What | Needs |
|---|---|---|
| `sessions-core` | Splitting sessions, the rules (`SessionRules`), the recorder `AppSessions`, batches, `SessionSink` with `FileSessionSink` and `DebugSessionSink`, `FileSessionStateStore`, `HangMonitor`, test doubles in `com.appgrowstack.sessions.testing` | Kotlin's standard library only (Kotlin/JVM; also for the app's headless tests) |
| `sessions-compose` | `SessionCapture` (taps through `Window.Callback`, front and back by started activities, the back key, `ApplicationExitInfo`, a crash handler before API 30, hangs), `SessionDeviceInfo.current`, `Modifier.sessionTarget`, `sessionScroll`, `sessionMask`, `sessionNoCapture` | Android SDK, Compose ui and foundation |

## Quick Start

```kotlin
// build.gradle.kts (JitPack)
implementation("com.github.appgrowstack.kotlin-app-sessions:sessions-compose:<version>")

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val store = FileSessionStateStore(filesDir)
        val sessions = AppSessions(
            sink = FileSessionSink(filesDir),
            deviceInfo = { SessionDeviceInfo.current(this, channel = "play") },
            store = store,
            configuration = AppSessions.Configuration(keepProps = mapOf("memo_created" to listOf("view"))),
        )
        SessionCapture.install(this, sessions, store)        // starts the session
        analytics.onEvent { name, props -> sessions.observe(name, props.mapValues { SessionValue.of(it.value) }) }
    }
}

// Compose
Button(onClick = save, modifier = Modifier.sessionTarget("save_button")) { Text("Save") }
LazyColumn(state = listState, modifier = Modifier.sessionScroll(listState, "books")) { … }
```

A sink is `fun send(batch: SessionBatch): SessionSinkReply`, called on the recorder's own thread.
Throw to keep the batch for later (it is sent again with the same `seq`); answer `Disabled` to stop
the session.

## Building

`./gradlew test` needs only a JDK. The Android module is included when `local.properties` has
`sdk.dir` (Android Studio writes it) or with `-Psessions.withAndroid=true`.

## License

MIT
