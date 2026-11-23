package com.appgrowstack.sessions

/**
 * How long taking a frame holds the main thread, and what the recording does about it
 * (sessions.md 3.2): over [slowP95Ms] at the 95th percentile a frame is taken every 2 seconds;
 * over [stopP95Ms] the session's recording stops ([Verdict.DEGRADED]).
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
    public var stopP95Ms: Double? = 33.0,
    public val warmUpFrames: Int = 3,
    public val window: Int = 30,
    public val minFrames: Int = 10,
) {
    public enum class Verdict {
        /** One frame a second. */
        NORMAL,
        /** One frame every 2 seconds. */
        SLOW,
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
        verdict == Verdict.SLOW -> 2000L
        else -> 1000L
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
            p95 > slowP95Ms -> Verdict.SLOW
            else -> Verdict.NORMAL
        }
        return verdict
    }

    /** Starts over (a new session). */
    public fun reset() {
        skipped = 0
        durations.clear()
        verdict = Verdict.NORMAL
    }
}
