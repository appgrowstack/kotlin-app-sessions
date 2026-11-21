package com.appgrowstack.sessions

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The rules of the light record as pure functions: no clock, storage or network. The recorder
 * uses the same code, and `schema/cases/sessions` checks every one against Swift and TypeScript.
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

    // ---- Split

    public enum class LifecycleStep { LAUNCH, BACKGROUND, FOREGROUND }

    public enum class SplitBy(public val wire: String) { BACKGROUND_TIMEOUT("background_timeout"), LAUNCH("launch") }

    public data class SplitSession(val start: Long, val end: Long? = null, val by: SplitBy? = null)

    public data class Split(val sessionOfStep: List<Int>, val sessions: List<SplitSession>)

    /**
     * Launch always starts a session. Coming to the foreground starts one when the app was in the
     * background for [BACKGROUND_TIMEOUT_MS] or longer. A change of date does not split.
     */
    public fun split(steps: List<Pair<Long, LifecycleStep>>): Split {
        val sessions = mutableListOf<SplitSession>()
        val sessionOf = mutableListOf<Int>()
        var backgroundAt: Long? = null
        for ((t, k) in steps) {
            when (k) {
                LifecycleStep.LAUNCH -> {
                    if (sessions.isNotEmpty()) sessions[sessions.lastIndex] = sessions.last().copy(by = SplitBy.LAUNCH)
                    sessions.add(SplitSession(t))
                    backgroundAt = null
                }
                LifecycleStep.BACKGROUND -> {
                    if (sessions.isEmpty()) sessions.add(SplitSession(t))
                    backgroundAt = t
                }
                LifecycleStep.FOREGROUND -> {
                    val at = backgroundAt
                    if (sessions.isEmpty()) {
                        sessions.add(SplitSession(t))
                    } else if (at != null && t - at >= BACKGROUND_TIMEOUT_MS) {
                        sessions[sessions.lastIndex] = sessions.last().copy(end = at, by = SplitBy.BACKGROUND_TIMEOUT)
                        sessions.add(SplitSession(t))
                    }
                    backgroundAt = null
                }
            }
            sessionOf.add(sessions.lastIndex)
        }
        return Split(sessionOf, sessions)
    }

    // ---- Tap position

    public data class NormalizedTap(val x: Int, val y: Int, val orientation: SessionOrientation, val col: Int, val row: Int, val cell: Int)

    /** A tap in screen dp and the window's frame in screen dp → 0–10000 of the window and the cell of the 40 × 80 grid. */
    public fun normalizeTap(x: Double, y: Double, window: SessionRect): NormalizedTap {
        val nx = fraction(x - window.x, window.w)
        val ny = fraction(y - window.y, window.h)
        val col = min(GRID_COLUMNS - 1, nx * GRID_COLUMNS / 10000)
        val row = min(GRID_ROWS - 1, ny * GRID_ROWS / 10000)
        return NormalizedTap(nx, ny, window.size.orientation, col, row, row * GRID_COLUMNS + col)
    }

    /** `round(value × 10000 / whole)` with .5 going up, kept in 0–10000. */
    internal fun fraction(value: Double, whole: Double): Int {
        if (whole <= 0) return 0
        val scaled = floor(value * 10000 / whole + 0.5)
        return min(10000.0, max(0.0, scaled)).toInt()
    }

    // ---- Rage

    public data class Tap(val t: Long, val x: Double, val y: Double)

    public data class Rage(val t: Long, val x: Int, val y: Int, val count: Int)

    /** Taps in window dp → rage taps. See [RageDetector]. */
    public fun rage(taps: List<Tap>, window: SessionSize): List<Rage> {
        val detector = RageDetector(window)
        val found = taps.mapNotNull { detector.add(it) }.toMutableList()
        detector.finish()?.let { found.add(it) }
        return found
    }

    // ---- Dead

    public enum class AfterKind { SCREEN, EVENT, FRAME }

    public data class After(val t: Long, val k: AfterKind, val fingerprint: String? = null)

    /**
     * Whether a tap did nothing: no screen, no app event and no change of the frame in
     * `(t, t + 1500]`. Without frames to compare (not capturing, or none in the window) it is not dead.
     */
    public fun isDead(capturing: Boolean, tapAt: Long, fingerprintBefore: String, after: List<After>): Boolean {
        if (!capturing) return false
        val inWindow = after.filter { it.t > tapAt && it.t <= tapAt + DEAD_WINDOW_MS }
        if (inWindow.any { it.k == AfterKind.SCREEN || it.k == AfterKind.EVENT }) return false
        val frames = inWindow.filter { it.k == AfterKind.FRAME }
        if (frames.isEmpty()) return false
        return frames.all { it.fingerprint == fingerprintBefore }
    }

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

    /** Moves between screens → the `screen`, `back` and `quick_back` events. See [Navigator]. */
    public fun navigate(steps: List<NavigationStep>): List<NavigationEvent> {
        val navigator = Navigator()
        return steps.flatMap { navigator.move(it) }
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

    public fun endReason(input: EndInput): EndDecision {
        val reason = baseEndReason(input)
        val revised = if (input.platform == SessionPlatform.IOS && input.lateMetricKitCrash && input.runningFlag &&
            (reason == SessionEndReason.KILLED || reason == SessionEndReason.HUNG)
        ) SessionEndReason.CRASHED else null
        return EndDecision(reason, revised)
    }

    private fun baseEndReason(input: EndInput): SessionEndReason {
        if (!input.previousRecord) return SessionEndReason.UNKNOWN
        val android = input.platform == SessionPlatform.ANDROID
        val modern = (input.androidApi ?: 30) >= 30
        if (android && modern && input.exitReason == "REASON_USER_REQUESTED") return SessionEndReason.USER_CLOSED
        if (!input.runningFlag) return SessionEndReason.BACKGROUND_TIMEOUT
        val crashed = if (android) {
            if (modern) input.exitReason == "REASON_CRASH" || input.exitReason == "REASON_CRASH_NATIVE" else input.crashHandler
        } else {
            input.metricKitCrash
        }
        if (crashed) return SessionEndReason.CRASHED
        if (input.lastEvent == "hang" || (android && modern && input.exitReason == "REASON_ANR")) return SessionEndReason.HUNG
        if (android && !modern) return SessionEndReason.UNKNOWN
        return SessionEndReason.KILLED
    }

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

    public fun scrollDepth(samples: List<ScrollSample>): ScrollDepth =
        ScrollDepth(samples.maxOfOrNull { it.ratio } ?: 0, samples.mapNotNull { it.row }.maxOrNull())

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

    /**
     * Which of an app event's props are kept (sessions.md 9.4). A null value stands for one that is
     * not text, a number or yes/no (a list, an object, null): it is dropped without the flag.
     */
    public fun keepProps(source: EventSource, name: String, props: Map<String, SessionValue?>, keepProps: Map<String, List<String>>): KeptProps {
        val chosen = when {
            source == EventSource.COMMON || name.startsWith("campaign_") || name.startsWith("campaigns_") -> props
            keepProps.containsKey(name) -> props.filterKeys { it in keepProps.getValue(name) }
            else -> emptyMap()
        }
        val kept = linkedMapOf<String, SessionValue>()
        var dropped = false
        for ((key, value) in chosen) {
            if (value == null) continue
            if (value is SessionValue.Text && value.value.length > MAX_PROP_LENGTH) {
                dropped = true
                continue
            }
            kept[key] = value
        }
        return KeptProps(kept, dropped)
    }

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

    /** Whether to send the recording (sessions.md 5). */
    public fun sampling(input: SamplingInput): Sampling {
        if (input.feature != Feature.ON) {
            return Sampling(false, null, false, upload = Upload.NONE, priority = emptyList(), previousSessionUpload = false, sessionReplay = null)
        }
        if (!input.replayEnabled) {
            return Sampling(true, SessionReplayMode.BUFFER, false, upload = Upload.NONE, priority = emptyList(),
                previousSessionUpload = false, sessionReplay = SessionReplayState.NONE)
        }
        val previous = input.events.any {
            it is SessionEvent.Start && it.previousEnd in setOf(SessionEndReason.CRASHED, SessionEndReason.HUNG, SessionEndReason.KILLED)
        }
        val hits = priorityHits(input.events)
        val priority = hits.keys.sortedBy { it.ordinal }
        if (input.internalDevice) {
            return Sampling(true, SessionReplayMode.RECORD, true, upload = Upload.ALL, priority = priority,
                previousSessionUpload = previous, sessionReplay = SessionReplayState.RECORD)
        }
        var hash: Long? = null
        if (input.recordedToday < input.dailyCap) {
            val value = hash32("${input.project}:${input.sessionId}:replay")
            hash = value
            if (value / 4_294_967_296.0 < input.sample) {
                return Sampling(true, SessionReplayMode.RECORD, true, value, Upload.ALL, null, priority, previous, SessionReplayState.RECORD)
            }
        }
        val hitT = hits.values.minOrNull()
        if (hitT != null) {
            return Sampling(true, SessionReplayMode.BUFFER, true, hash, Upload.BUFFERED, max(0, hitT - 60_000), priority, previous,
                SessionReplayState.PRIORITY)
        }
        return Sampling(true, SessionReplayMode.BUFFER, true, hash, Upload.NONE, null, emptyList(), previous, SessionReplayState.BUFFER)
    }

    /** The priority conditions met by the events, each with the time it was first met. */
    public fun priorityHits(events: List<SessionEvent>): Map<PriorityReason, Long> {
        val hits = linkedMapOf<PriorityReason, Long>()
        fun hit(reason: PriorityReason, t: Long) { hits.putIfAbsent(reason, t) }
        var deadCount = 0
        var first = false
        var onboardingCompleted = false
        var campaignShownAt: Long? = null
        var paywallOpen = false
        for (event in events) {
            when (event) {
                is SessionEvent.Start -> first = event.first
                is SessionEvent.Rage -> hit(PriorityReason.RAGE, event.t)
                is SessionEvent.Dead -> { deadCount++; if (deadCount == 3) hit(PriorityReason.DEAD, event.t) }
                is SessionEvent.Hang -> hit(PriorityReason.HANG, event.t)
                is SessionEvent.Mark -> when (event.markKind) {
                    SessionMarkKind.TICKET -> hit(PriorityReason.TICKET, event.t)
                    SessionMarkKind.CAMPAIGN_SHOWN -> campaignShownAt = event.t
                    SessionMarkKind.PAYWALL_SHOWN -> paywallOpen = true
                    SessionMarkKind.PURCHASE_COMPLETED -> paywallOpen = false
                    SessionMarkKind.ONBOARDING_STEP -> if (event.ref == "completed") onboardingCompleted = true
                    else -> Unit
                }
                is SessionEvent.App -> if (event.name == "paywall_closed" && paywallOpen) {
                    hit(PriorityReason.PAYWALL_NO_PURCHASE, event.t)
                    paywallOpen = false
                }
                is SessionEvent.End -> {
                    val shown = campaignShownAt
                    if (shown != null && event.t - shown <= 60_000) hit(PriorityReason.CAMPAIGN_QUICK_END, event.t)
                    if (paywallOpen) hit(PriorityReason.PAYWALL_NO_PURCHASE, event.t)
                    if (first && !onboardingCompleted) hit(PriorityReason.ONBOARDING_UNFINISHED, event.t)
                }
                else -> Unit
            }
        }
        return hits
    }

    // ---- Hash

    /** FNV-1a over UTF-16 code units with murmur3's final mix (the same `hash32` as the server's), as 0 … 2^32 − 1. */
    public fun hash32(input: String): Long {
        var hash = 0x811c9dc5.toInt()
        for (unit in input) {
            hash = hash xor unit.code
            hash *= 0x01000193
        }
        hash = hash xor (hash ushr 16)
        hash *= 0x85ebca6b.toInt()
        hash = hash xor (hash ushr 13)
        hash *= 0xc2b2ae35.toInt()
        hash = hash xor (hash ushr 16)
        return hash.toLong() and 0xffffffffL
    }
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
 * Moves between screens one at a time: the screen shown, and for `pop` and `dismiss` the `back`,
 * the `quick_back` when it came within 2000 ms of entering, and the screen it went back to.
 */
public class Navigator {
    public var current: String? = null
        private set
    public var enteredAt: Long? = null
        private set

    public fun move(step: SessionRules.NavigationStep): List<SessionRules.NavigationEvent> {
        val events = mutableListOf<SessionRules.NavigationEvent>()
        val via = when (step.op) {
            SessionRules.NavigationOp.LAUNCH -> SessionScreenVia.LAUNCH
            SessionRules.NavigationOp.PUSH -> SessionScreenVia.PUSH
            SessionRules.NavigationOp.TAB -> SessionScreenVia.TAB
            SessionRules.NavigationOp.SHEET -> SessionScreenVia.SHEET
            SessionRules.NavigationOp.POP, SessionRules.NavigationOp.DISMISS -> {
                val screen = current
                val entered = enteredAt
                if (screen != null) {
                    events.add(SessionRules.NavigationEvent.Back(step.t, screen, step.by ?: SessionBackVia.BUTTON))
                    if (entered != null && step.t - entered <= SessionRules.QUICK_BACK_MS) {
                        events.add(SessionRules.NavigationEvent.QuickBack(step.t, screen, step.t - entered))
                    }
                }
                SessionScreenVia.POP
            }
        }
        events.add(SessionRules.NavigationEvent.Screen(step.t, step.screen, via))
        current = step.screen
        enteredAt = step.t
        return events
    }

    /** Enters a screen without a known move (a new session on the same screen). */
    public fun enter(screen: String, t: Long) {
        current = screen
        enteredAt = t
    }
}
