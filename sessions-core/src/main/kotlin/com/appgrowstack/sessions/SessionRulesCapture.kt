package com.appgrowstack.sessions

// SessionRules: whether a frame of the recording is taken, and the hold a `Modifier.sessionNoCapture()` keeps.

/** Why a frame of the recording is not taken (`ReplayBlankReason` of `sessions/segment.schema.json`). */
public enum class SessionBlankReason(public val value: String) {
    /** A screen marked not to be captured is shown or lies under the shown one, a dialog or a sheet is up, or the screen's name is in `no_capture_screens`. */
    NO_CAPTURE("no_capture"),
    /** A window with `FLAG_SECURE`. */
    SECURE("secure"),
    /** The parts to hide could not be found. */
    MASKING_FAILED("masking_failed"),
    /** Something the app cannot draw is over it. */
    UNSUPPORTED("unsupported"),
}

/** What decides whether a frame is taken. */
public data class SessionCaptureScene(
    /** A `Modifier.sessionNoCapture()` holds ([NoCaptureHold]). */
    val noCaptureHeld: Boolean = false,
    /**
     * The activity's window has lost the focus while the activity is resumed: a dialog (`AlertDialog`), a
     * `ModalBottomSheet`, a focusable popup, the system's dialog or the notification shade is over it.
     * Their text fields are in windows of their own, out of reach of the marks.
     */
    val overlayShown: Boolean = false,
    /** The window has `FLAG_SECURE`. */
    val secure: Boolean = false,
    /** The last `screen_viewed` value. */
    val screen: String? = null,
)

/** Why the frame is not taken, or null to take it. `noCaptureScreens` is the manifest's `no_capture_screens`. */
public fun SessionRules.blankReason(scene: SessionCaptureScene, noCaptureScreens: Set<String> = emptySet()): SessionBlankReason? = when {
    scene.secure -> SessionBlankReason.SECURE
    scene.noCaptureHeld || scene.overlayShown -> SessionBlankReason.NO_CAPTURE
    scene.screen != null && scene.screen in noCaptureScreens -> SessionBlankReason.NO_CAPTURE
    else -> null
}

/**
 * The hold of one `Modifier.sessionNoCapture()`, from when it enters the composition. Leaving the
 * composition does not end it by itself: Navigation Compose takes a screen out of the composition when
 * another is pushed on it, and the pushed screen must stay blank too. The hold ends when
 * - it leaves while its lifecycle owner (the back stack entry, or the activity) is still resumed: it was
 *   taken off a screen that is still shown,
 * - its owner is resumed again after it left: the screen came back on top with a new hold of its own, or
 * - its owner is destroyed: the screen was popped.
 */
public class NoCaptureHold internal constructor(private val onEnd: (NoCaptureHold) -> Unit) {
    private var hasLeft = false

    @Volatile public var ended: Boolean = false
        private set

    /** It left the composition. `ownerResumed`: its lifecycle owner is still resumed. */
    public fun left(ownerResumed: Boolean) {
        hasLeft = true
        if (ownerResumed) end()
    }

    /** Its lifecycle owner was resumed (`ON_RESUME`). */
    public fun ownerResumed() {
        if (hasLeft) end()
    }

    /** Its lifecycle owner was destroyed (`ON_DESTROY`). */
    public fun ownerDestroyed(): Unit = end()

    private fun end() {
        if (ended) return
        ended = true
        onEnd(this)
    }
}
