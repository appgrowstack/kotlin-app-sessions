# kotlin-app-sessions

Androidアプリの中で人が何をしたかを、セッションごとに記録する。画面・タップ・rage tap・すぐ戻った操作・スクロールの深さ・止まった時間・セッションの終わり方を残す。

[English](README.md) | 日本語

## 機能

- **セッションの区切り。** 起動で1つ始まり、バックグラウンドに30分いた後に戻ったときにも始まる
- **画面は計測の出来事から。** `screen_viewed`でその画面へ動く。今の画面の下の画面へ戻るのは`pop`で、`back`と、入ってから2秒以内なら`quick_back`も記録する。戻るキーは`via`として残す
- **タッチを止めないタップの記録。** 各アクティビティのウィンドウで`Window.Callback`を通してタップを読む。タッチとキーは全部そのままアプリに渡る。位置はウィンドウに対する0〜10000。`Modifier.sessionTarget("…")`で要素に名前を付ける
- **rage tap。** 最初のタップから1秒以内・30dp以内に3回以上
- **スクロールの深さ。** `verticalScroll`と`LazyColumn`に`Modifier.sessionScroll(state, "…")`を付ける
- **終わり方。** API 30からは`ApplicationExitInfo`、それより前はSDKのクラッシュのハンドラー、止まりは監視のスレッドで見て、次の起動で決める
- **アプリの出来事は残す物を絞る。** 共通のカタログの出来事は値を残す。アプリ独自の出来事は`keepProps`に書いたプロパティだけを残す。48文字を超える文字列は捨てる
- **再起動やオフラインでも消えない。** 送れていない物は保存し、同じ`seq`で後から送る。受ける側は重なりを捨てられる
- **iOSのパッケージと同じ判定。** `SessionRules`は共通の表でswift-app-sessionsと同じ答えを出す
- **依存が少ない。** `sessions-core`はKotlinの標準ライブラリだけ。`sessions-compose`はAndroidのSDKとComposeのuiとfoundationを足す

軽い記録は画素を持たない。`Modifier.sessionMask()`と`Modifier.sessionNoCapture()`はマークを置くだけ。

### 記録の送り先

`AppSessions`はバッチを1つずつ、自分の送信用のスレッドで`SessionSink`に渡す。

| 送り先 | すること |
|---|---|
| `FileSessionSink` | セッションごとのファイルに、バッチを1行のJSONで足す。20MBを超えたら古い物から消す。テスター向けの画面は`files()`を共有できる |
| `DebugSessionSink` | 直近のバッチをメモリに持ち（`batches`）、1つずつ出力もする |
| 自前の物 | `fun send(batch: SessionBatch): SessionSinkReply`。投げるとバッチを残して後で送り直す。`SessionSinkReply.Disabled`を返すとそのセッションを止める |

### テスト

`sessions-core`はJVMで動くので、アプリの画面なしのテストからも使える。テスト用のフェイクは`com.appgrowstack.sessions.testing`にある。

## はじめに

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
        val store = FileSessionStateStore(noBackupFilesDir)
        val sessions = AppSessions(
            sink = FileSessionSink(noBackupFilesDir), // keep the records on the device
            deviceInfo = { SessionDeviceInfo.current(this, channel = "play") },
            store = store,
        )
        SessionCapture.install(this, sessions, store) // starts the session
        sessions.observe("screen_viewed", mapOf("screen" to SessionValue.Text("home")))
    }
}
```

### サーバーにつなぐ

このパッケージは単体で使えます。サーバーにつなぐときは、このパッケージで送り先を書く代わりに、コネクターのパッケージ [kotlin-appgrowstack](https://github.com/appgrowstack/kotlin-appgrowstack) の対応するモジュールを足します。

## 入れ方

モジュールはJitPackで公開している。`settings.gradle.kts`にリポジトリを足す。

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

アプリの`build.gradle.kts`に、使うモジュールを足す。

```kotlin
dependencies {
    // Android: capture and the Compose modifiers (includes sessions-core)
    implementation("com.github.appgrowstack.kotlin-app-sessions:sessions-compose:0.1.0")
    // Kotlin/JVM only, without Android
    // implementation("com.github.appgrowstack.kotlin-app-sessions:sessions-core:0.1.0")
}
```

### モジュール

| モジュール | 中身 |
|---|---|
| `sessions-core` | セッションの区切り・判定（`SessionRules`）・記録する`AppSessions`・バッチ・`SessionSink`と`FileSessionSink`と`DebugSessionSink`・`FileSessionStateStore`・`HangMonitor`・テスト用のフェイク |
| `sessions-compose` | `SessionCapture`・`SessionDeviceInfo.current`・`Modifier.sessionTarget`・`sessionScroll`・`sessionMask`・`sessionNoCapture` |

## 動く環境

- `sessions-core`：JDK 17以上
- `sessions-compose`：Android API 26以上（minSdk 26）とJetpack Compose

## ライセンス

MIT。[LICENSE](LICENSE)を見る。
