package com.appgrowstack.sessions

/**
 * How long taking a frame holds the main thread, and what the recording does about it
 * (sessions.md 3.2): over [slowP95Ms] at the 95th percentile a frame is taken every 2 seconds,
 * over [verySlowP95Ms] every 5 seconds, and over [stopP95Ms] the session's recording stops
 * ([Verdict.DEGRADED]).
 *
 * A device that takes 35 ms a frame is not stopped: it is spaced out to one frame in 5 seconds, a
 * hitch of two display frames now and then. Stopping at 33 ms left whole sessions with only their
 * first frames. Same rule as swift-app-sessions.
 *
 * - The first [warmUpFrames] frames are not counted: they are taken while the app is still laying
 *   out its first screens and say nothing about the frames after them
 * - The percentile is read over the last [window] frames, once [minFrames] are counted
 * - [stopP95Ms] null never stops (an emulator draws on the host; its times are not a device's)
 *
 * Not thread-safe: [AppSessions] keeps it under its lock.
 */
public class SessionReplayFrameCost(
    public val slowP95Ms: Double = 16.0,
    public val verySlowP95Ms: Double = 33.0,
    public var stopP95Ms: Double? = 100.0,
    public val warmUpFrames: Int = 3,
    public val window: Int = 30,
    public val minFrames: Int = 10,
) {
    public enum class Verdict {
        /** One frame a second. */
        NORMAL,
        /** One frame every 2 seconds. */
        SLOW,
        /** One frame every 5 seconds. */
        VERY_SLOW,
        /** Stop the session's recording. */
        DEGRADED,
    }

    private var skipped = 0
    private val durations = ArrayDeque<Double>()

    public var verdict: Verdict = Verdict.NORMAL
        private set

    /** The milliseconds between frames the verdict allows (`live` takes two a second). */
    public fun intervalMs(live: Boolean): Long = when {
        live -> 500L
        else -> when (verdict) {
            Verdict.NORMAL -> 1000L
            Verdict.SLOW -> 2000L
            Verdict.VERY_SLOW, Verdict.DEGRADED -> 5000L
        }
    }

    /** Counts one frame's main-thread time. A [Verdict.DEGRADED] verdict stays until [reset]. */
    public fun add(ms: Double): Verdict {
        if (verdict == Verdict.DEGRADED) return verdict
        if (!ms.isFinite() || ms < 0) return verdict
        if (skipped < warmUpFrames) {
            skipped++
            return verdict
        }
        durations.addLast(ms)
        while (durations.size > window) durations.removeFirst()
        if (durations.size < minFrames) return verdict
        val sorted = durations.sorted()
        val p95 = sorted[minOf(sorted.size - 1, (sorted.size * 0.95).toInt())]
        val stop = stopP95Ms
        verdict = when {
            stop != null && p95 > stop -> Verdict.DEGRADED
            p95 > verySlowP95Ms -> Verdict.VERY_SLOW
            p95 > slowP95Ms -> Verdict.SLOW
            else -> Verdict.NORMAL
        }
        return verdict
    }

    /** The 50th and 95th percentile of the frames counted so far (in the window), or null before the first. */
    public val percentiles: Percentiles?
        get() {
            if (durations.isEmpty()) return null
            val sorted = durations.sorted()
            return Percentiles(
                p50 = sorted[minOf(sorted.size - 1, sorted.size / 2)],
                p95 = sorted[minOf(sorted.size - 1, (sorted.size * 0.95).toInt())],
            )
        }

    public data class Percentiles(val p50: Double, val p95: Double)

    /** Starts over (a new session). */
    public fun reset() {
        skipped = 0
        durations.clear()
        verdict = Verdict.NORMAL
    }
}
