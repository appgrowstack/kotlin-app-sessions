package com.appgrowstack.sessions

import com.appgrowstack.sessions.SessionRules.After
import com.appgrowstack.sessions.SessionRules.AfterKind
import com.appgrowstack.sessions.SessionRules.NormalizedTap
import com.appgrowstack.sessions.SessionRules.Rage
import com.appgrowstack.sessions.SessionRules.Tap
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// SessionRules: Where a tap landed, rage taps and dead taps.

/** A tap in screen dp and the window's frame in screen dp → 0–10000 of the window and the cell of the 40 × 80 grid. */
public fun SessionRules.normalizeTap(x: Double, y: Double, window: SessionRect): NormalizedTap {
    val nx = fraction(x - window.x, window.w)
    val ny = fraction(y - window.y, window.h)
    val col = min(GRID_COLUMNS - 1, nx * GRID_COLUMNS / 10000)
    val row = min(GRID_ROWS - 1, ny * GRID_ROWS / 10000)
    return NormalizedTap(nx, ny, window.size.orientation, col, row, row * GRID_COLUMNS + col)
}

/** `round(value × 10000 / whole)` with .5 going up, kept in 0–10000. */
internal fun SessionRules.fraction(value: Double, whole: Double): Int {
    if (whole <= 0) return 0
    val scaled = floor(value * 10000 / whole + 0.5)
    return min(10000.0, max(0.0, scaled)).toInt()
}

/** Taps in window dp → rage taps. See [RageDetector]. */
public fun SessionRules.rage(taps: List<Tap>, window: SessionSize): List<Rage> {
    val detector = RageDetector(window)
    val found = taps.mapNotNull { detector.add(it) }.toMutableList()
    detector.finish()?.let { found.add(it) }
    return found
}

/**
 * Whether a tap did nothing: no screen, no app event and no change of the frame in
 * `(t, t + 1500]`. Without frames to compare (not capturing, or none in the window) it is not dead.
 */
public fun SessionRules.isDead(capturing: Boolean, tapAt: Long, fingerprintBefore: String, after: List<After>): Boolean {
    if (!capturing) return false
    val inWindow = after.filter { it.t > tapAt && it.t <= tapAt + DEAD_WINDOW_MS }
    if (inWindow.any { it.k == AfterKind.SCREEN || it.k == AfterKind.EVENT }) return false
    val frames = inWindow.filter { it.k == AfterKind.FRAME }
    if (frames.isEmpty()) return false
    return frames.all { it.fingerprint == fingerprintBefore }
}

/**
 * Rage taps one tap at a time. A cluster starts at a tap; a later tap within 1000 ms and 30 dp of
 * the cluster's first tap joins it, any other tap closes it and starts the next. A closed cluster
 * of 3 or more taps is one rage at its third tap, positioned at its first tap.
 */
public class RageDetector(public var window: SessionSize) {
    private val cluster = mutableListOf<SessionRules.Tap>()

    /** Adds a tap. Returns the rage of the cluster it closed, if any. */
    public fun add(tap: SessionRules.Tap): SessionRules.Rage? {
        val origin = cluster.firstOrNull()
        if (origin != null && tap.t - origin.t <= SessionRules.RAGE_WINDOW_MS &&
            hypot(tap.x - origin.x, tap.y - origin.y) <= SessionRules.RAGE_RADIUS
        ) {
            cluster.add(tap)
            return null
        }
        val closed = finish()
        cluster.add(tap)
        return closed
    }

    /** Closes the cluster when no later tap can join it any more (`now` past its first tap + 1000 ms). */
    public fun expire(now: Long): SessionRules.Rage? {
        val origin = cluster.firstOrNull() ?: return null
        return if (now - origin.t > SessionRules.RAGE_WINDOW_MS) finish() else null
    }

    /** Closes the cluster now (leaving the screen, going to the background). */
    public fun finish(): SessionRules.Rage? {
        val taps = cluster.toList()
        cluster.clear()
        if (taps.size < SessionRules.RAGE_MIN_TAPS) return null
        val origin = taps.first()
        return SessionRules.Rage(taps[2].t, SessionRules.fraction(origin.x, window.w), SessionRules.fraction(origin.y, window.h), taps.size)
    }
}

/**
 * The name of a tap at ([x], [y]) from the marks under the screen, back to front (the order they are
 * drawn in). The front layer is the deepest layer whose mark holds the point (the later one when two
 * are as deep); only the targets of that layer count, or the targets outside every layer when no layer
 * holds it. Of those, the smallest target holding the point names the tap (the later one when two are
 * as small). A tap on a layer where none of its targets is under the finger has no name: a row under a
 * dialog does not name a tap on the dialog.
 */
public fun SessionRules.tapTarget(x: Double, y: Double, marks: List<SessionRules.TapMark>): String? {
    val under = marks.filter { it.contains(x, y) }
    var front: List<String> = emptyList()
    for (mark in under) if (mark.name == null && mark.layers.size >= front.size) front = mark.layers
    var best: SessionRules.TapMark? = null
    for (mark in under) {
        if (mark.name == null || mark.layers != front) continue
        val area = mark.frame.w * mark.frame.h
        if (area <= (best?.let { it.frame.w * it.frame.h } ?: Double.MAX_VALUE)) best = mark
    }
    return best?.name
}
