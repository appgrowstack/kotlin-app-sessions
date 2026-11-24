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
 * A null in `rects` is a mark whose place could not be found, and so is one that is not a finite number:
 * the frame is dropped (`masking_failed`).
 * Pass `scale` 1 for rectangles already in the image's pixels. `cases/sessions/mask-rects-*.json`.
 */
public fun SessionRules.maskRects(scale: Double, image: SessionPixelSize, rects: List<SessionRect?>): SessionMaskPlan {
    // A place that is not a number cannot be painted: it must not leave its part showing.
    if (rects.any { it == null || !(it.x.isFinite() && it.y.isFinite() && it.w.isFinite() && it.h.isFinite()) } || !scale.isFinite() || scale <= 0) {
        return SessionMaskPlan(true, emptyList(), SessionBlankReason.MASKING_FAILED)
    }
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

/** Finding the parts to hide takes longer than this (one frame at 60 Hz): the frame is dropped. */
public const val SESSION_MASK_BUDGET_MS: Double = 16.0

/**
 * [maskRects] of what `find` returns, fail closed (SC8): `find` throwing, or finding and placing the parts
 * taking longer than `budgetMs` by `nowMs`, drops the frame (`masking_failed`), so a frame is never kept with
 * a part left unpainted.
 */
public fun SessionRules.maskRects(
    scale: Double,
    image: SessionPixelSize,
    budgetMs: Double = SESSION_MASK_BUDGET_MS,
    nowMs: () -> Double,
    find: () -> List<SessionRect?>,
): SessionMaskPlan {
    val dropped = SessionMaskPlan(true, emptyList(), SessionBlankReason.MASKING_FAILED)
    val started = nowMs()
    val plan = try {
        maskRects(scale, image, find())
    } catch (_: Throwable) {
        return dropped
    }
    return if (nowMs() - started > budgetMs) dropped else plan
}

/**
 * A view as the search for the parts to hide walks it (sessions.md 3.3): Android's views and Compose's
 * semantics nodes in `sessions-compose`; fakes in the tests.
 */
public interface SessionMaskNode {
    /** Drawn (`View.VISIBLE`). A node that is not shown is skipped with everything inside it. */
    public val shown: Boolean

    /**
     * Painted over whole, with everything inside it: a `Modifier.sessionMask()`, a password field (always
     * hidden), a surface or texture view.
     */
    public val hidesWhole: Boolean

    /**
     * Its visible place in the window's pixels; an empty rectangle when nothing of it is visible. Null when
     * it cannot be told: the frame is then dropped (`masking_failed`).
     */
    public val bounds: SessionRect?

    public val children: List<SessionMaskNode>
}

/**
 * The parts of a window to paint over: every shown node that [SessionMaskNode.hidesWhole], in its pixels.
 * An empty one is left out. A null stands for a part whose place could not be told, and so does a hierarchy
 * of more than `limit` nodes (a cycle in a broken one): the frame is then dropped.
 */
public fun SessionRules.partsToHide(root: SessionMaskNode, limit: Int = 10_000): List<SessionRect?> {
    val parts = mutableListOf<SessionRect?>()
    var visited = 0
    var tooMany = false
    fun visit(node: SessionMaskNode) {
        visited++
        if (visited > limit) {
            tooMany = true
            return
        }
        if (!node.shown) return
        if (!node.hidesWhole) {
            node.children.forEach { visit(it) }
            return
        }
        val b = node.bounds
        if (b == null || !(b.x.isFinite() && b.y.isFinite() && b.w.isFinite() && b.h.isFinite())) {
            parts.add(null)
            return
        }
        if (b.w > 0 && b.h > 0) parts.add(b)
    }
    visit(root)
    if (tooMany) parts.add(null)
    return parts
}

/**
 * An `android.text.InputType` of a password: text whose variation is a password, a visible password (still a
 * password field, hidden while shown in clear) or a web password, or a number password.
 */
public fun SessionRules.isPasswordInputType(inputType: Int): Boolean {
    val klass = inputType and INPUT_MASK_CLASS
    val variation = inputType and INPUT_MASK_VARIATION
    return when (klass) {
        INPUT_CLASS_TEXT -> variation == INPUT_TEXT_PASSWORD || variation == INPUT_TEXT_VISIBLE_PASSWORD || variation == INPUT_TEXT_WEB_PASSWORD
        INPUT_CLASS_NUMBER -> variation == INPUT_NUMBER_PASSWORD
        else -> false
    }
}

/**
 * An autofill hint of a password: `View.AUTOFILL_HINT_PASSWORD` (`password`) or androidx's
 * `HintConstants.AUTOFILL_HINT_NEW_PASSWORD` (`newPassword`), as an `EditText`'s `autofillHints` or Compose's
 * `ContentType.Password` and `ContentType.NewPassword` carry them.
 */
public fun SessionRules.isPasswordHint(hint: String): Boolean = hint == "password" || hint == "newPassword"

// android.text.InputType's values (the core does not depend on Android).
private const val INPUT_MASK_CLASS = 0x0000000f
private const val INPUT_MASK_VARIATION = 0x00000ff0
private const val INPUT_CLASS_TEXT = 0x00000001
private const val INPUT_CLASS_NUMBER = 0x00000002
private const val INPUT_TEXT_PASSWORD = 0x00000080
private const val INPUT_TEXT_VISIBLE_PASSWORD = 0x00000090
private const val INPUT_TEXT_WEB_PASSWORD = 0x000000e0
private const val INPUT_NUMBER_PASSWORD = 0x00000010

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
