package com.appgrowstack.sessions

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// SessionRules: the parts of the recording that are pure (what to paint over, and the frame's fingerprint).

/** A rectangle in whole pixels of an image; `x` and `y` are the top left corner. */
public data class SessionPixelRect(val x: Int, val y: Int, val w: Int, val h: Int)

/** The size of an image in pixels. */
public data class SessionPixelSize(val w: Int, val h: Int)

/**
 * What to paint over in a frame. `drop`: the frame cannot be masked and must not be kept (a blank
 * frame for `reason` instead).
 */
public data class SessionMaskPlan(val drop: Boolean, val rects: List<SessionPixelRect>, val reason: SessionBlankReason? = null)

/**
 * The rectangles to paint, in whole pixels of `image`: each of `rects` (in points or dp) times
 * `scale`, rounded outward (the top left down, the bottom right up) so no edge leaks, and clipped to
 * the image. A rectangle wholly outside is left out; overlapping ones are kept as they are, in order.
 * A null in `rects` is a mark whose place could not be found: the frame is dropped (`masking_failed`).
 * Pass `scale` 1 for rectangles already in the image's pixels. `cases/sessions/mask-rects-*.json`.
 */
public fun SessionRules.maskRects(scale: Double, image: SessionPixelSize, rects: List<SessionRect?>): SessionMaskPlan {
    if (rects.any { it == null }) return SessionMaskPlan(true, emptyList(), SessionBlankReason.MASKING_FAILED)
    val out = rects.filterNotNull().mapNotNull { r ->
        val left = max(0.0, floor(r.x * scale))
        val top = max(0.0, floor(r.y * scale))
        val right = min(image.w.toDouble(), ceil((r.x + r.w) * scale))
        val bottom = min(image.h.toDouble(), ceil((r.y + r.h) * scale))
        if (right <= left || bottom <= top) null
        else SessionPixelRect(left.toInt(), top.toInt(), (right - left).toInt(), (bottom - top).toInt())
    }
    return SessionMaskPlan(false, out)
}

/**
 * A frame's fingerprint from its brightness shrunk to 16 × 32 cells (`luma`, 512 values of 0–255, row
 * by row from the top left): one hex digit per cell (the top 4 bits). Two frames with the same
 * fingerprint look the same; the later one is kept as a `same` frame without its image, and dead taps
 * compare them ([isDead]).
 */
public fun SessionRules.frameFingerprint(luma: IntArray): String {
    require(luma.size == FINGERPRINT_COLUMNS * FINGERPRINT_ROWS) { "luma has ${FINGERPRINT_COLUMNS * FINGERPRINT_ROWS} cells" }
    val out = CharArray(luma.size)
    for (i in luma.indices) out[i] = HEX[(min(255, max(0, luma[i])) ushr 4)]
    return String(out)
}

/** The brightness (0–255) of an ARGB color, as Rec. 601 weighs it. */
public fun SessionRules.luma(argb: Int): Int {
    val r = argb ushr 16 and 0xff
    val g = argb ushr 8 and 0xff
    val b = argb and 0xff
    return (299 * r + 587 * g + 114 * b) / 1000
}

private val HEX = "0123456789abcdef".toCharArray()
