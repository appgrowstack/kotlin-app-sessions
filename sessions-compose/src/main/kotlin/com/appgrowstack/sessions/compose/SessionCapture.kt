package com.appgrowstack.sessions.compose

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import android.view.WindowManager
import com.appgrowstack.sessions.AppSessions
import com.appgrowstack.sessions.HangMonitor
import com.appgrowstack.sessions.SessionBackVia
import com.appgrowstack.sessions.SessionBlankReason
import com.appgrowstack.sessions.SessionCaptureScene
import com.appgrowstack.sessions.SessionDeviceInfo
import com.appgrowstack.sessions.SessionExitInfo
import com.appgrowstack.sessions.SessionPlatform
import com.appgrowstack.sessions.SessionProcessExit
import com.appgrowstack.sessions.SessionRect
import com.appgrowstack.sessions.SessionRules
import com.appgrowstack.sessions.SessionScreen
import com.appgrowstack.sessions.SessionStateStore
import com.appgrowstack.sessions.blankReason
import java.util.Locale
import java.util.TimeZone
import kotlin.math.hypot

/**
 * Feeds an [AppSessions] from Android: the app going to the back and the front (by its started
 * activities), taps on every activity's window, the back key, how the previous process ended
 * (`ApplicationExitInfo` from API 30, the SDK's crash handler before it) and hangs of the main
 * thread. Every touch and key goes on to the app unchanged. The session starts when the first activity
 * comes to the front: a process that shows nothing (a notification, a widget, WorkManager, an alarm)
 * only ends the previous session and records nothing. When the recorder has a recording
 * (`AppSessions(replay = …)`), it also takes the recording's frames of the activity in the front
 * (`PixelCopy`, painted over as `replayOptions` says) and reads the network with [AndroidSessionNetwork].
 *
 * ```kotlin
 * class App : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         val store = FileSessionStateStore(noBackupFilesDir)
 *         val sessions = AppSessions(FileSessionSink(noBackupFilesDir), { SessionDeviceInfo.current(this, "play") }, store)
 *         SessionCapture.install(this, sessions, store)   // the first activity starts the session
 *     }
 * }
 * ```
 */
public object SessionCapture {
    private val main = Handler(Looper.getMainLooper())

    /** `store` must be the store the recorder uses (the hang monitor marks a hang in it). */
    public fun install(
        application: Application,
        sessions: AppSessions,
        store: SessionStateStore,
        watchHangs: Boolean = true,
        replayOptions: SessionReplayOptions = SessionReplayOptions(),
    ) {
        // A second process of the app (`android:process=":sync"`, a widget's) has no screen of the app's and
        // shares the state file: it must not close the session the main process is in.
        if (!isMainProcess(application)) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                runCatching { sessions.recordUncaughtCrash() }
                previous?.uncaughtException(thread, error)
            }
        }
        val replay = sessions.replay?.let {
            sessions.network = AndroidSessionNetwork(application)
            // An emulator's frame times say nothing of a device's: there heavy frames only space frames out
            sessions.replayStopsOnSlowFrames = !AndroidEmulator.current
            SessionReplayCapture(application, sessions, replayOptions)
        }
        // `Application.onCreate` runs for every process, with a screen or without one
        sessions.closePrevious(exitInfo(application))
        application.registerActivityLifecycleCallbacks(Lifecycle(sessions, replay))
        val tick = object : Runnable {
            override fun run() {
                sessions.tick()
                main.postDelayed(this, 5000)
            }
        }
        main.postDelayed(tick, 5000)
        if (watchHangs) HangMonitor(sessions, store, { main.post(it) }).start()
    }

    /**
     * Why a frame of `window` taken now would be blank, or null to take it: `FLAG_SECURE`, a
     * `Modifier.sessionNoCapture()` that holds, the window without the focus (a dialog, a
     * `ModalBottomSheet`, a focusable popup, the system's dialog or the notification shade over it:
     * their text fields are out of reach of the marks), or the screen in `noCaptureScreens` (the
     * manifest's `no_capture_screens`).
     */
    public fun blankReason(window: Window, sessions: AppSessions, noCaptureScreens: Set<String> = emptySet()): SessionBlankReason? {
        val decor = window.peekDecorView()
        val scene = SessionCaptureScene(
            noCaptureHeld = sessions.noCaptureHeld,
            overlayShown = decor == null || !decor.hasWindowFocus(),
            secure = (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0,
            screen = sessions.currentScreen,
        )
        return SessionRules.blankReason(scene, noCaptureScreens)
    }

    /**
     * The newest exits of this app's processes (API 30 and later). The recorder takes the one of the
     * previous session's process ([com.appgrowstack.sessions.previousExit]): ended after that session
     * started, and not this process.
     */
    public fun exitInfo(context: Context): SessionExitInfo {
        val api = Build.VERSION.SDK_INT
        if (api < Build.VERSION_CODES.R) return SessionExitInfo(androidApi = api)
        val manager = context.getSystemService(ActivityManager::class.java)
        val exits = runCatching {
            manager?.getHistoricalProcessExitReasons(context.packageName, 0, EXITS_READ).orEmpty().map { info ->
                SessionProcessExit(reasonName(info.reason), info.timestamp, info.pid, info.processName)
            }
        }.getOrNull()
        return SessionExitInfo(
            androidApi = api,
            exits = exits,
            currentPid = Process.myPid(),
            currentProcessName = Application.getProcessName(),
        )
    }

    /** The app's main process (its name is the package's). Before API 28 the name cannot be read: taken as main. */
    private fun isMainProcess(application: Application): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true
        return Application.getProcessName() == application.packageName
    }

    /** Processes that started without a screen (a notification, a job) end too: one record is not enough. */
    private const val EXITS_READ = 8

    internal fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_USER_REQUESTED -> "REASON_USER_REQUESTED"
        ApplicationExitInfo.REASON_CRASH -> "REASON_CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "REASON_CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "REASON_ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "REASON_LOW_MEMORY"
        ApplicationExitInfo.REASON_SIGNALED -> "REASON_SIGNALED"
        ApplicationExitInfo.REASON_EXIT_SELF -> "REASON_EXIT_SELF"
        ApplicationExitInfo.REASON_OTHER -> "REASON_OTHER"
        else -> "REASON_UNKNOWN"
    }

    private class Lifecycle(private val sessions: AppSessions, private val replay: SessionReplayCapture?) : Application.ActivityLifecycleCallbacks {
        private var started = 0

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = attach(activity)
        override fun onActivityResumed(activity: Activity) {
            attach(activity)
            updateWindow(activity)
            activity.window?.let { window -> runCatching { replay?.attach(window) } }
        }

        override fun onActivityStarted(activity: Activity) {
            started++
            if (started == 1) {
                runCatching { replay?.enterForeground() }
                sessions.enterForeground()
            }
        }

        override fun onActivityStopped(activity: Activity) {
            started = maxOf(0, started - 1)
            if (started == 0 && !activity.isChangingConfigurations) sessions.enterBackground()
        }

        override fun onActivityPaused(activity: Activity) {
            activity.window?.let { window -> runCatching { replay?.detach(window) } }
        }
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit

        private fun attach(activity: Activity) {
            val window = activity.window ?: return
            val callback = window.callback ?: return
            if (callback !is SessionWindowCallback) window.callback = SessionWindowCallback(callback, window, sessions, replay)
        }

        private fun updateWindow(activity: Activity) {
            windowFrame(activity.window)?.let(sessions::updateWindow)
        }
    }

    /** The window's frame on the screen in dp. */
    internal fun windowFrame(window: Window?): SessionRect? {
        val decor = window?.peekDecorView() ?: return null
        if (decor.width == 0 || decor.height == 0) return null
        val density = decor.resources.displayMetrics.density
        val location = IntArray(2)
        decor.getLocationOnScreen(location)
        return SessionRect(location[0] / density.toDouble(), location[1] / density.toDouble(), decor.width / density.toDouble(), decor.height / density.toDouble())
    }
}

/** Passes every call on to the app's own callback, and reports taps and the back key on the way. */
internal class SessionWindowCallback(
    private val delegate: Window.Callback,
    private val window: Window,
    private val sessions: AppSessions,
    private val replay: SessionReplayCapture? = null,
) : Window.Callback by delegate {
    private var downX = 0f
    private var downY = 0f

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        if (event != null) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    runCatching { replay?.touchDown() }
                }
                MotionEvent.ACTION_UP -> {
                    runCatching { report(event) }
                    runCatching { replay?.touchUp(dragged = dragged(event)) }
                }
                MotionEvent.ACTION_CANCEL -> runCatching { replay?.touchUp(dragged = true) }
            }
        }
        return delegate.dispatchTouchEvent(event)
    }

    private fun dragged(event: MotionEvent): Boolean {
        val density = window.decorView.resources.displayMetrics.density
        return hypot(event.x - downX, event.y - downY) / density >= 10
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event != null && event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            sessions.noteBack(SessionBackVia.SYSTEM)
        }
        return delegate.dispatchKeyEvent(event)
    }

    private fun report(event: MotionEvent) {
        val density = window.decorView.resources.displayMetrics.density
        // A drag is a scroll, not a tap.
        if (dragged(event)) return
        val frame = SessionCapture.windowFrame(window) ?: return
        val target = SessionMarks.target(window, event.x, event.y)
        sessions.tap(frame.x + event.x / density, frame.y + event.y / density, frame, target)
    }
}

/** The device and the app as Android sees them. `channel` is `play`, `play_test` or `debug`. */
public fun SessionDeviceInfo.Companion.current(context: Context, channel: String, uid: String? = null, installId: String? = null): SessionDeviceInfo {
    val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    val version = info?.versionName?.takeIf { Regex("^\\d+\\.\\d+(\\.\\d+)?$").matches(it) } ?: "0.0"
    val build = info?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toString() else @Suppress("DEPRECATION") it.versionCode.toString() } ?: "0"
    val locale = Locale.getDefault()
    var lang = locale.language.ifEmpty { "en" }
    if (lang == "zh" && locale.script.isNotEmpty()) lang += "-${locale.script}"
    val region = locale.country.uppercase().takeIf { Regex("^[A-Z0-9]{2,3}$").matches(it) } ?: "ZZ"
    val metrics = context.resources.displayMetrics
    return SessionDeviceInfo(
        installId = installId, uid = uid, platform = SessionPlatform.ANDROID, appVersion = version, build = build.take(32),
        channel = channel, lang = lang, region = region, timeZone = TimeZone.getDefault().id,
        osVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
        deviceModel = (Build.MODEL ?: "unknown").take(64),
        screen = SessionScreen(metrics.widthPixels / metrics.density.toDouble(), metrics.heightPixels / metrics.density.toDouble(), metrics.density.toDouble()),
    )
}
