# kotlin-app-sessions

Androidアプリの中で人が何をしたかを、セッションごとに記録する。画面・タップ・rage tap・dead tap・すぐ戻った操作・スクロールの深さ・止まった時間・セッションの終わり方を残し、望めば人に見せない所を黒く塗った画面の録画も撮る。

[English](README.md) | 日本語

## 機能

- **セッションの区切り。** 起動して最初のアクティビティが前に出たときに1つ始まり、バックグラウンドに30分いた後に戻ったときにも始まる。画面を出さないプロセス（通知・ウィジェット・WorkManager・アラーム）では始まらない。最初のセッションの`start`は`first: true`を持つ。これは「このSDKがこの端末で記録する最初のセッション」で、SDKを入れたバージョンに上げた前からの利用者でもtrueになる。新しい人かどうかは、アプリの初回起動の出来事で見分ける
- **前に出ていた時間。** `background`と`end`は`foreground_ms`（セッションが前に出ていた時間）を持つ。バックグラウンドに置いたままの時間でセッションが長く見えない。アプリがバックグラウンドに行って30分を過ぎてから、バックグラウンドの仕事が記録した物はセッションに入れない
- **画面は計測の出来事から。** `screen_viewed`でその画面へ動く。今の画面の下の画面へ戻るのは`pop`で、`back`と、入ってから2秒以内なら`quick_back`も記録する。戻るキーは`via`として残す。アプリが自分で閉じる画面（長押しが終わると閉じるシートなど）は、閉じる直前に`noteDismissByApp()`を呼ぶと`quick_back`にしない
- **タッチを止めないタップの記録。** 各アクティビティのウィンドウで`Window.Callback`を通してタップを読む。タッチとキーは全部そのままアプリに渡る。位置はウィンドウに対する0〜10000。`Modifier.sessionTarget("…")`で要素に名前を付ける
- **rage tap。** 最初のタップから1秒以内・30dp以内に3回以上
- **スクロールの深さ。** `verticalScroll`と`LazyColumn`に`Modifier.sessionScroll(state, "…")`を付ける
- **終わり方。** API 30からは`ApplicationExitInfo`、それより前はSDKのクラッシュのハンドラー、止まりは監視のスレッドで見て、次の起動で決める
- **アプリの出来事は残す物を絞る。** 共通のカタログの出来事は値を残す。アプリ独自の出来事は`keepProps`に書いたプロパティだけを残す。48文字を超える文字列は捨てる
- **再起動やオフラインでも消えない。** 送れていない物は保存し、同じ`seq`で後から送る。受ける側は重なりを捨てられる
- **iOSのパッケージと同じ判定。** `SessionRules`は共通の表でswift-app-sessionsと同じ答えを出す
- **マークした所を隠す画面の録画。** 窓を`PixelCopy`で1秒に1枚まで撮り、縮める前に黒く塗ってから1dp＝1pxにしてWebPで持つ。塗るのは`Modifier.sessionMask()`とパスワードの欄だけ（`SessionMaskMode.MARKED`。モードはこれだけ）。ほかは全部写るので、人が書いた文にはマークを付ける。SurfaceViewとTextureViewは丸ごと塗る。塗れなかったコマは捨てる
- **送る価値がある時だけ送る。** サーバーの答えで決める。`RECORD`は塊を全部送る。`BUFFER`は直近60秒だけを端末に残し、優先の条件（rage tapやdead tap・止まり・クラッシュ・問い合わせ・買わずに閉じたペイウォールなど）に当たったら、残した分とその後を送る。`NONE`は撮らない。塊は従量でない回線で送り、優先のセッションは従量の回線でも1日3MBまで送る
- **端末に軽い。** 撮るのにメインスレッドを使うコマは間を空ける（p95が16msを超えたら2秒に1枚、33msを超えたら5秒に1枚）。100msを超えたらそのセッションの録画を止める。指が触れている間・リストの惰性の間・省電力・発熱・電池20%未満で充電していない時は撮らない。前と同じに見えるコマは画像を持たない。溜めるのは全体で50MB・7日まで、1つのセッションは30分か20MBまで
- **依存が少ない。** `sessions-core`はKotlinの標準ライブラリだけ。`sessions-compose`はAndroidのSDKとComposeのuiとfoundationを足す

軽い記録は画素を持たない。録画は`AppSessions(replay = …)`を渡した時だけ撮る。`Modifier.sessionNoCapture()`を付けた画面は、見えている間と、Navigation Composeでその上に別の画面を開いている間（戻って閉じるまで）、録画を撮らない。ダイアログ・`ModalBottomSheet`など窓のフォーカスを取る物が出ている間も撮らない。今撮ると撮らない理由は`SessionCapture.blankReason(window, sessions, noCaptureScreens)`で分かる。

### タップした物の名前

タップには、指の下にある一番小さい`Modifier.sessionTarget("…")`の名前が付く。名前は指が触れた時に取る。長押しでシートが開いても、短いタップと同じ名前が付く。`Dialog`と`Popup`は別の窓なので、その下の行の名前は付かない。同じ窓の中に重ねて表示したレイヤー（`Box`で上に置くダイアログ・オンボーディング・ピッカー）は`SessionLayer { }`で包む。大きさは、そのレイヤーが覆う範囲（暗くした背景も含む）にする。指の下にレイヤーがある間は、その中の名前だけを見る。レイヤーの中で名前の無い所のタップは、下の行の名前でなく、名前なしになる。

```kotlin
Box {
    HabitList()   // rows marked Modifier.sessionTarget("habit_row")
    if (showTerms) {
        SessionLayer(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f))) {
            TermsDialog()   // its button marked Modifier.sessionTarget("terms_agree")
        }
    }
}
```

### アプリが自分で閉じる画面

本人の操作の後に画面が自分で閉じるときは、閉じる直前に`noteDismissByApp()`を呼ぶ。

```kotlin
Modifier.combinedClickable(onClick = {}, onLongClick = {
    store.achieve(habit)
    AppSessions.current?.noteDismissByApp()  // the sheet closes itself: not a quick back
    onDismiss()
})
```

### 記録の送り先

`AppSessions`はバッチを1つずつ、自分の送信用のスレッドで`SessionSink`に渡す。

| 送り先 | すること |
|---|---|
| `FileSessionSink` | セッションごとのファイルに、バッチを1行のJSONで足す。20MBを超えたら古い物から消す。テスター向けの画面は`files()`を共有できる |
| `DebugSessionSink` | 直近のバッチをメモリに持ち（`batches`）、1つずつ出力もする |
| 自前の物 | `fun send(batch: SessionBatch): SessionSinkReply`。投げるとバッチを残して後で送り直す。`SessionSinkReply.Disabled`を返すとそのセッションを止める |

録画の塊（`.agsr`。JSONの頭と画像を並べた物。`SessionReplaySegment`）は`<directory>/sessions/replay/<session_id>/<seq>.agsr`で待ち、`SessionReplayRecorder`の`SessionReplaySink`に渡る。`fun upload(segment: SessionReplayUpload): SessionReplayUploadReply`。`UPLOADED`か`REJECTED`を返すとその塊を消し、`FORBIDDEN`はそのセッションの塊を全部消す。投げると後で送り直す。最初の答えの`uploadToken`（`SessionSinkReply.Accepted.uploadToken`）は再起動してもセッションと一緒に残り、塊ごとに付く。`sessions.uploadToken(sessionId)`で読める。

答えに`live`があると、`AppSessions(liveTransport = …)`に渡した`SessionLiveTransport`でライブを開く（このパッケージはWebSocketを持たない。OkHttpなど手元のクライアントを包んで渡す）。

### テスト

`sessions-core`はJVMで動くので、アプリの画面なしのテストからも使える。テスト用のフェイクは`com.appgrowstack.sessions.testing`にある。

## はじめに

```kotlin
import android.app.Application
import com.appgrowstack.sessions.AppSessions
import com.appgrowstack.sessions.FileSessionSink
import com.appgrowstack.sessions.FileSessionStateStore
import com.appgrowstack.sessions.SessionDeviceInfo
import com.appgrowstack.sessions.SessionReplayRecorder
import com.appgrowstack.sessions.SessionValue
import com.appgrowstack.sessions.compose.SessionCapture
import com.appgrowstack.sessions.compose.SessionReplayOptions
import com.appgrowstack.sessions.compose.current

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val store = FileSessionStateStore(noBackupFilesDir)
        val sessions = AppSessions(
            sink = FileSessionSink(noBackupFilesDir), // keep the records on the device
            deviceInfo = { SessionDeviceInfo.current(this, channel = "play") },
            store = store,
            // optional: the recording, with where its segments go
            replay = SessionReplayRecorder(noBackupFilesDir, sink = mySegmentSink),
        )
        SessionCapture.install(this, sessions, store, replayOptions = SessionReplayOptions()) // the first activity starts the session
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
    implementation("com.github.appgrowstack.kotlin-app-sessions:sessions-compose:0.2.0")
    // Kotlin/JVM only, without Android
    // implementation("com.github.appgrowstack.kotlin-app-sessions:sessions-core:0.2.0")
}
```

### モジュール

| モジュール | 中身 |
|---|---|
| `sessions-core` | セッションの区切り・判定（`SessionRules`）・記録する`AppSessions`・バッチ・`SessionSink`と`FileSessionSink`と`DebugSessionSink`・`FileSessionStateStore`・`HangMonitor`・録画の塊（`SessionReplayRecorder`・`SessionReplaySegment`・`SessionReplaySink`）・ライブの口（`SessionLiveTransport`）・テスト用のフェイク |
| `sessions-compose` | `SessionCapture`（録画のコマと`SessionReplayOptions`）・`AndroidSessionNetwork`・`SessionDeviceInfo.current`・`Modifier.sessionTarget`・`SessionLayer`・`sessionScroll`・`sessionMask`・`sessionNoCapture` |

## 動く環境

- `sessions-core`：JDK 17以上
- `sessions-compose`：Android API 26以上（minSdk 26）とJetpack Compose

## ライセンス

MIT。[LICENSE](LICENSE)を見る。
