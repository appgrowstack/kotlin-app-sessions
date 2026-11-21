[English](./README.md) | 日本語

# kotlin-app-sessions

Android のアプリの中で人が何をしたかを、セッションごとに見る。どの画面をどう通ったか・どこをタップしたか・
何度もタップした所やすぐ戻った所・どこまでスクロールしたか・アプリが止まった時間・セッションの終わり方。
記録は端末に溜めるか、アプリが決めた送り先へ渡す。判定と表は swift-app-sessions と同じ。

> 非公開のモノリポで開発し、ここへは片方向にコピーしている。Issue は歓迎、プルリクエストは受け付けない。

## モジュール

| モジュール | 中身 | 要る物 |
|---|---|---|
| `sessions-core` | セッションの区切り・判定（`SessionRules`）・記録 `AppSessions`・バッチ・`SessionSink` と `FileSessionSink`・`DebugSessionSink`・`FileSessionStateStore`・`HangMonitor`・偽の物（`com.appgrowstack.sessions.testing`） | Kotlin の標準だけ（Kotlin/JVM。アプリの headless のテストからも使える） |
| `sessions-compose` | `SessionCapture`（`Window.Callback` のタップ・始まった Activity の数で前と裏・戻るのキー・`ApplicationExitInfo`・API 30 より前のクラッシュの受け手・止まり）・`SessionDeviceInfo.current`・`Modifier.sessionTarget`・`sessionScroll`・`sessionMask`・`sessionNoCapture` | Android の SDK・Compose の ui と foundation |

## 使い方

```kotlin
// build.gradle.kts（JitPack）
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
        SessionCapture.install(this, sessions, store)        // セッションも始まる
        analytics.onEvent { name, props -> sessions.observe(name, props.mapValues { SessionValue.of(it.value) }) }
    }
}

// Compose
Button(onClick = save, modifier = Modifier.sessionTarget("save_button")) { Text("保存") }
LazyColumn(state = listState, modifier = Modifier.sessionScroll(listState, "books")) { … }
```

送り先は `fun send(batch: SessionBatch): SessionSinkReply`。記録の送るスレッドで呼ばれる。投げると後で同じ `seq` で送り直し、`Disabled` を返すとそのセッションを止める。

## 組み方

`./gradlew test` は JDK だけで通る。Android のモジュールは `local.properties` に `sdk.dir` がある時（Android Studio が書く）か、`-Psessions.withAndroid=true` で入る。

## ライセンス

MIT
