package com.appgrowstack.sessions.compose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.PixelCopy
import android.view.ViewTreeObserver
import android.view.Window
import com.appgrowstack.sessions.AppSessions
import com.appgrowstack.sessions.SessionBlankReason
import com.appgrowstack.sessions.SessionMaskMode
import com.appgrowstack.sessions.SessionMaskPlan
import com.appgrowstack.sessions.SessionNetwork
import com.appgrowstack.sessions.SessionNetworkKind
import com.appgrowstack.sessions.SessionPixelSize
import com.appgrowstack.sessions.SessionReplayStopReason
import com.appgrowstack.sessions.SessionRules
import com.appgrowstack.sessions.frameFingerprint
import com.appgrowstack.sessions.luma
import com.appgrowstack.sessions.maskRects
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How the recording takes frames. `mask` is the manifest's `mask`: `TEXT_AND_IMAGES` (the default) paints
 * every text and image too; `INPUTS` only the text fields, password fields, `Modifier.sessionMask()`, web
 * views and views inside Compose, which are always painted. `noCaptureScreens` is the manifest's
 * `no_capture_screens`.
 */
public data class SessionReplayOptions(
    val mask: SessionMaskMode = SessionMaskMode.TEXT_AND_IMAGES,
    val noCaptureScreens: Set<String> = emptySet(),
)

/** Android's network: unmetered when the active network has `NET_CAPABILITY_NOT_METERED` (needs `ACCESS_NETWORK_STATE`, declared by this module). */
public class AndroidSessionNetwork(context: Context) : SessionNetwork {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun current(): SessionNetworkKind = try {
        val network = manager?.activeNetwork
        val capabilities = network?.let { manager?.getNetworkCapabilities(it) }
        when {
            capabilities == null -> SessionNetworkKind.NONE
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> SessionNetworkKind.UNMETERED
            else -> SessionNetworkKind.METERED
        }
    } catch (_: Exception) {
        // Without the permission, count it as metered: only priority sessions send, and little
        SessionNetworkKind.METERED
    }
}

/**
 * Takes the recording's frames of the activity in the front (sessions.md 3.2): `PixelCopy` of its window
 * on a thread of its own, painted black where [SessionMaskFinder] says before it is shrunk to 1 dp = 1 px,
 * then WebP (lossy, quality 35). At most one a second (two while watched live, one every two when frames
 * weigh); first 0.3 seconds after a screen is entered and 0.5 seconds after a tap; not while a finger is down
 * and not until the screen has stopped moving for 0.2 seconds after a drag. Not in battery saver, when the
 * device is hot or under 20% and not charging. A frame that cannot be painted is blank (`masking_failed`).
 */
internal class SessionReplayCapture(context: Context, private val sessions: AppSessions, private val options: SessionReplayOptions) {
    private val main = Handler(Looper.getMainLooper())
    private val worker: Handler = Handler(HandlerThread("app-sessions.replay").apply { start() }.looper)
    private val power = PowerCheck(context.applicationContext)
    private val black = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }

    private var window: Window? = null
    private var drawListener: ViewTreeObserver.OnDrawListener? = null
    private var scheduledAt: Long? = null
    private var lastShotAt = 0L
    private var touching = false
    private var settling = false
    private var lastScreen: String? = null
    @Volatile private var busy = false
    /** The full-size bitmap `PixelCopy` writes to, kept between frames (only one is in flight). */
    private var full: Bitmap? = null

    private val shot = Runnable { shoot() }

    // ---- From the activities and the window callback (main thread)

    fun attach(window: Window) {
        if (this.window === window) return
        detach()
        val decor = window.peekDecorView() ?: return
        this.window = window
        val listener = ViewTreeObserver.OnDrawListener { drawn() }
        decor.viewTreeObserver.addOnDrawListener(listener)
        drawListener = listener
        lastScreen = sessions.currentScreen
        schedule(SCREEN_DELAY_MS)
    }

    fun detach(window: Window) {
        if (this.window === window) detach()
    }

    fun touchDown() {
        touching = true
    }

    fun touchUp(dragged: Boolean) {
        touching = false
        if (dragged) {
            settling = true
            reschedule(SETTLE_MS)
        } else {
            reschedule(TAP_DELAY_MS)
        }
    }

    private fun detach() {
        val current = window ?: return
        drawListener?.let { listener ->
            current.peekDecorView()?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnDrawListener(listener)
        }
        drawListener = null
        window = null
        main.removeCallbacks(shot)
        scheduledAt = null
    }

    /** Something was drawn: a new screen goes first, a moving one waits until it stops, anything else within the interval. */
    private fun drawn() {
        val screen = sessions.currentScreen
        if (screen != lastScreen) {
            lastScreen = screen
            reschedule(SCREEN_DELAY_MS)
            return
        }
        if (settling) {
            reschedule(SETTLE_MS)
            return
        }
        schedule(0)
    }

    /** A shot at `delayMs` from now (or when the interval allows), unless one is due sooner. */
    private fun schedule(delayMs: Long) {
        val now = SystemClock.uptimeMillis()
        val at = max(now + delayMs, lastShotAt + sessions.replayFrameIntervalMs)
        val pending = scheduledAt
        if (pending != null && pending <= at) return
        main.removeCallbacks(shot)
        main.postAtTime(shot, at)
        scheduledAt = at
    }

    /** A shot at `delayMs` from now (or when the interval allows), replacing the one planned. */
    private fun reschedule(delayMs: Long) {
        main.removeCallbacks(shot)
        scheduledAt = null
        schedule(delayMs)
    }

    // ---- Taking a frame (main thread, then the worker)

    private fun shoot() {
        scheduledAt = null
        val window = window ?: return
        if (touching) return
        if (busy) {
            schedule(SETTLE_MS)
            return
        }
        settling = false
        val pause = power.reason()
        sessions.replayPaused(pause)
        if (pause != null || !sessions.replayActive) return
        lastShotAt = SystemClock.uptimeMillis()
        runCatching { take(window) }.onFailure { busy = false }
    }

    private fun take(window: Window) {
        val started = SystemClock.elapsedRealtimeNanos()
        val decor = window.peekDecorView() ?: return
        val wPx = decor.width
        val hPx = decor.height
        if (wPx <= 0 || hPx <= 0) return
        val density = decor.resources.displayMetrics.density.takeIf { it > 0 } ?: 1f
        val outW = min(4096, max(1, (wPx / density).roundToInt()))
        val outH = min(4096, max(1, (hPx / density).roundToInt()))
        val blank = SessionCapture.blankReason(window, sessions, options.noCaptureScreens)
        if (blank != null) {
            sessions.replayBlank(outW, outH, blank, ms(started))
            return
        }
        // Fail closed: masks that cannot be found, or take longer than a frame (16 ms), drop the frame
        val plan: SessionMaskPlan = try {
            SessionRules.maskRects(1.0, SessionPixelSize(wPx, hPx), SessionMaskFinder.rects(window, options.mask))
        } catch (_: Throwable) {
            SessionMaskPlan(true, emptyList(), SessionBlankReason.MASKING_FAILED)
        }
        if (plan.drop || ms(started) > MASK_BUDGET_MS) {
            sessions.replayBlank(outW, outH, SessionBlankReason.MASKING_FAILED, ms(started))
            return
        }
        val bitmap = full?.takeIf { it.width == wPx && it.height == hPx && !it.isRecycled }
            ?: Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888).also { full?.recycle(); full = it }
        busy = true
        val mainMs = ms(started)
        try {
            PixelCopy.request(window, bitmap, { result -> developed(result, bitmap, plan, outW, outH, mainMs) }, worker)
        } catch (_: IllegalArgumentException) {
            // The window has no surface yet (or any more)
            busy = false
        }
    }

    /** On the worker: paint, shrink, fingerprint, and hand the frame over. */
    private fun developed(result: Int, bitmap: Bitmap, plan: SessionMaskPlan, outW: Int, outH: Int, mainMs: Double) {
        try {
            if (result != PixelCopy.SUCCESS) {
                sessions.replayBlank(outW, outH, SessionBlankReason.UNSUPPORTED, mainMs)
                return
            }
            try {
                val canvas = Canvas(bitmap)
                for (r in plan.rects) canvas.drawRect(r.x.toFloat(), r.y.toFloat(), (r.x + r.w).toFloat(), (r.y + r.h).toFloat(), black)
            } catch (_: Throwable) {
                sessions.replayBlank(outW, outH, SessionBlankReason.MASKING_FAILED, mainMs)
                return
            }
            val small = Bitmap.createScaledBitmap(bitmap, outW, outH, true)
            try {
                val fingerprint = fingerprint(small)
                sessions.replayImage(outW, outH, plan.rects.isNotEmpty(), fingerprint, mainMs, "image/webp") { webp(small) }
            } finally {
                if (small !== bitmap) small.recycle()
            }
        } catch (_: Throwable) {
            // A frame lost is better than an app crashed
        } finally {
            busy = false
        }
    }

    private fun fingerprint(bitmap: Bitmap): String {
        val grid = Bitmap.createScaledBitmap(bitmap, SessionRules.FINGERPRINT_COLUMNS, SessionRules.FINGERPRINT_ROWS, true)
        val pixels = IntArray(SessionRules.FINGERPRINT_COLUMNS * SessionRules.FINGERPRINT_ROWS)
        grid.getPixels(pixels, 0, SessionRules.FINGERPRINT_COLUMNS, 0, 0, SessionRules.FINGERPRINT_COLUMNS, SessionRules.FINGERPRINT_ROWS)
        if (grid !== bitmap) grid.recycle()
        return SessionRules.frameFingerprint(IntArray(pixels.size) { SessionRules.luma(pixels[it]) })
    }

    private fun webp(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        check(bitmap.compress(format, WEBP_QUALITY, out)) { "WebP did not encode" }
        return out.toByteArray()
    }

    private fun ms(startedNanos: Long): Double = (SystemClock.elapsedRealtimeNanos() - startedNanos) / 1_000_000.0

    private companion object {
        const val SCREEN_DELAY_MS = 300L
        const val TAP_DELAY_MS = 500L
        const val SETTLE_MS = 200L
        const val MASK_BUDGET_MS = 16.0
        const val WEBP_QUALITY = 35
    }
}

/** Why the device asks the recording to rest, read at most every 10 seconds. */
private class PowerCheck(private val context: Context) {
    private var checkedAt = Long.MIN_VALUE / 2
    private var last: SessionReplayStopReason? = null

    fun reason(): SessionReplayStopReason? {
        val now = SystemClock.uptimeMillis()
        if (now - checkedAt < 10_000) return last
        checkedAt = now
        last = runCatching { read() }.getOrNull()
        return last
    }

    private fun read(): SessionReplayStopReason? {
        val power = context.getSystemService(PowerManager::class.java)
        if (power?.isPowerSaveMode == true) return SessionReplayStopReason.LOW_POWER
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && (power?.currentThermalStatus ?: 0) >= PowerManager.THERMAL_STATUS_SEVERE) {
            return SessionReplayStopReason.THERMAL
        }
        val battery = context.getSystemService(BatteryManager::class.java) ?: return null
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level in 0 until 20 && !battery.isCharging) return SessionReplayStopReason.BATTERY
        return null
    }
}
