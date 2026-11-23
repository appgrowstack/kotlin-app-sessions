package com.appgrowstack.sessions

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The rules of the light record as pure functions: no clock, storage or network. The recorder
 * uses the same code, and `schema/cases/sessions` checks every one against Swift and TypeScript.
 * This file holds the constants and the types; the functions are extensions in `SessionRules<context>.kt`
 * (Split, Taps, Navigation, EndReason, Scroll, Props, Sampling, Capture, Replay), the same split as Swift's `SessionRules+<context>.swift`.
 */
public object SessionRules {
    /** 30 minutes in the background starts a new session (30 minutes exactly included). */
    public const val BACKGROUND_TIMEOUT_MS: Long = 1_800_000
    public const val RAGE_WINDOW_MS: Long = 1000
    public const val RAGE_RADIUS: Double = 30.0
    public const val RAGE_MIN_TAPS: Int = 3
    public const val DEAD_WINDOW_MS: Long = 1500
    public const val QUICK_BACK_MS: Long = 2000
    /** A text value longer than this (UTF-16 code units) is dropped from kept props. */
    public const val MAX_PROP_LENGTH: Int = 48
    public const val GRID_COLUMNS: Int = 40
    public const val GRID_ROWS: Int = 80
    /** The fingerprint of a frame of the recording: the frame shrunk to 16 × 32 cells ([frameFingerprint]). */
    public const val FINGERPRINT_COLUMNS: Int = 16
    public const val FINGERPRINT_ROWS: Int = 32

    // ---- Split

    public enum class LifecycleStep { LAUNCH, BACKGROUND, FOREGROUND }

    public enum class SplitBy(public val wire: String) { BACKGROUND_TIMEOUT("background_timeout"), LAUNCH("launch") }

    public data class SplitSession(val start: Long, val end: Long? = null, val by: SplitBy? = null)

    public data class Split(val sessionOfStep: List<Int>, val sessions: List<SplitSession>)

    // ---- Tap position

    public data class NormalizedTap(val x: Int, val y: Int, val orientation: SessionOrientation, val col: Int, val row: Int, val cell: Int)

    // ---- Rage

    public data class Tap(val t: Long, val x: Double, val y: Double)

    public data class Rage(val t: Long, val x: Int, val y: Int, val count: Int)

    // ---- Dead

    public enum class AfterKind { SCREEN, EVENT, FRAME }

    public data class After(val t: Long, val k: AfterKind, val fingerprint: String? = null)

    // ---- Navigation and quick back

    public enum class NavigationOp(public val wire: String) {
        LAUNCH("launch"), PUSH("push"), TAB("tab"), SHEET("sheet"), POP("pop"), DISMISS("dismiss");

        public companion object {
            public fun of(wire: String): NavigationOp = entries.first { it.wire == wire }
        }
    }

    /** `screen` is the screen shown after the move. */
    public data class NavigationStep(val t: Long, val op: NavigationOp, val screen: String, val by: SessionBackVia? = null)

    public sealed interface NavigationEvent {
        public data class Screen(val t: Long, val screen: String, val via: SessionScreenVia) : NavigationEvent
        public data class Back(val t: Long, val screen: String, val via: SessionBackVia) : NavigationEvent
        public data class QuickBack(val t: Long, val screen: String, val ms: Long) : NavigationEvent
    }

    // ---- End reason

    /** What the next launch knows about the previous session. */
    public data class EndInput(
        val platform: SessionPlatform,
        /** Android only. Null means 30 or later. */
        val androidApi: Int? = null,
        val previousRecord: Boolean,
        /** The "running" flag written when the app came to the front and cleared when it went to the back. */
        val runningFlag: Boolean,
        /** The `k` of the previous session's last event. */
        val lastEvent: String? = null,
        /** iOS: MetricKit had a crash diagnostic for it at launch. */
        val metricKitCrash: Boolean = false,
        /** iOS: MetricKit delivered a crash diagnostic for it after the launch. */
        val lateMetricKitCrash: Boolean = false,
        /** Android 30+: the newest `ApplicationExitInfo` reason, as `REASON_…`. */
        val exitReason: String? = null,
        /** Android before 30: the SDK's uncaught exception handler wrote a record. */
        val crashHandler: Boolean = false,
    )

    /** `revised` is the reason after a late MetricKit crash diagnostic; the recorder sends `end` again with it. */
    public data class EndDecision(val reason: SessionEndReason, val revised: SessionEndReason? = null)

    // ---- Scroll depth

    public data class ScrollSample(val offsetY: Double, val height: Double, val contentHeight: Double, val row: Int? = null) {
        /** How deep the bottom edge of what is visible is (0–10000). Content shorter than the view is 10000. */
        val ratio: Int
            get() {
                if (contentHeight <= height) return 10000
                val value = floor((offsetY + height) * 10000 / contentHeight)
                return min(10000.0, max(0.0, value)).toInt()
            }
    }

    public data class ScrollDepth(val maxRatio: Int, val maxRow: Int? = null)

    // ---- Kept props

    public enum class EventSource(public val wire: String) {
        /** The shared catalog of swift-analytics ([COMMON_CATALOG]). */
        COMMON("common"),
        APP("app");

        public companion object {
            public fun of(wire: String): EventSource = entries.first { it.wire == wire }
        }
    }

    /** The events of swift-analytics' shared catalog. Their values are choices, counts and yes/no. */
    public val COMMON_CATALOG: Set<String> = setOf(
        "first_open", "internal_device", "screen_viewed", "tapped", "onboarding_step", "onboarding_completed",
        "signed_in", "experiment_assigned", "paywall_shown", "paywall_loaded", "paywall_scrolled",
        "paywall_plan_selected", "paywall_purchase_tapped", "paywall_purchase_result", "paywall_backgrounded",
        "paywall_closed", "purchase_completed", "restore_completed", "review_requested", "whats_new_shown",
        "whats_new_closed",
    )

    /** `propsDropped`: a text value longer than 48 was dropped (a sign that something someone wrote got in). */
    public data class KeptProps(val props: Map<String, SessionValue>, val propsDropped: Boolean)

    // ---- Sampling

    public enum class Feature(public val wire: String) {
        ON("on"), OFF("off"), PAUSED("paused");

        public companion object {
            public fun of(wire: String): Feature = entries.first { it.wire == wire }
        }
    }

    /** In this order (the schema's enum order). */
    public enum class PriorityReason(public val wire: String) {
        RAGE("rage"), DEAD("dead"), HANG("hang"), TICKET("ticket"),
        CAMPAIGN_QUICK_END("campaign_quick_end"), PAYWALL_NO_PURCHASE("paywall_no_purchase"),
        ONBOARDING_UNFINISHED("onboarding_unfinished"),
    }

    public enum class Upload(public val wire: String) { NONE("none"), ALL("all"), BUFFERED("buffered") }

    /** `sessions_session.replay`. */
    public enum class SessionReplayState(public val wire: String) { NONE("none"), BUFFER("buffer"), RECORD("record"), PRIORITY("priority") }

    public data class SamplingInput(
        val project: String,
        val sessionId: String,
        val feature: Feature,
        val replayEnabled: Boolean,
        val sample: Double,
        val dailyCap: Int,
        val internalDevice: Boolean,
        val recordedToday: Int,
        val events: List<SessionEvent>,
    )

    public data class Sampling(
        val accept: Boolean,
        val answer: SessionReplayMode?,
        val capture: Boolean,
        val hash: Long? = null,
        val upload: Upload,
        val uploadFromT: Long? = null,
        val priority: List<PriorityReason>,
        val previousSessionUpload: Boolean,
        val sessionReplay: SessionReplayState?,
    )
}
