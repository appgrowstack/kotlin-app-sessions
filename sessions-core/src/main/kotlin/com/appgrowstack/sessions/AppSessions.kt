package com.appgrowstack.sessions

import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * The light record of an app's sessions. Feed it the app's lifecycle, screens, taps, scrolls and
 * analytics events (`sessions-compose` does most of it on Android); it splits sessions, applies the
 * rules, keeps what it has between launches and hands batches to the sink on `sender`.
 *
 * ```kotlin
 * val sessions = AppSessions(FileSessionSink(context.noBackupFilesDir), deviceInfo = { SessionDeviceInfo.current(context, "play") },
 *     store = FileSessionStateStore(context.noBackupFilesDir))
 * SessionCapture.install(application, sessions)          // sessions-compose; starts the session
 * analytics.observe { name, props -> sessions.observe(name, props) }
 * ```
 *
 * Every method may be called from any thread.
 */
public class AppSessions(
    public val sink: SessionSink,
    private val deviceInfo: () -> SessionDeviceInfo,
    private val store: SessionStateStore,
    private val clock: SessionClock = SystemSessionClock,
    private val ids: SessionIdGenerator = RandomSessionIds,
    configuration: Configuration = Configuration(),
    /** Where batches are handed to the sink. Tests pass `Executor { it.run() }`. */
    private val sender: Executor = Executors.newSingleThreadExecutor { Thread(it, "app-sessions.sender").apply { isDaemon = true } },
) {
    public data class Configuration(
        /** The app events whose props are kept, and which (the manifest's `events.keep_props`). */
        val keepProps: Map<String, List<String>> = emptyMap(),
        val marks: SessionMarkRules = SessionMarkRules.STANDARD,
        /** While in the front, a batch goes out at most this often (when there is something new). */
        val flushIntervalMs: Long = 30_000,
        /** Events kept for one session before the oldest are dropped (the sink is unreachable). */
        val maxPendingEvents: Int = 5000,
        /** Batches kept for the sink before the oldest are dropped. */
        val maxOutboxBatches: Int = 200,
        val maxEventsPerBatch: Int = 500,
        /** A back gesture or key reported this long before the screen change is its `via`. */
        val backHintMs: Long = 1000,
    )

    private val lock = Any()
    public var configuration: Configuration = configuration
        private set
    private var state = SessionStoredState()
    private var started = false
    private var backgroundAt: Long? = null
    private var lastFlushAt = 0L
    private var flushing = false
    private var dirty = false
    private var disabled = false
    private var window: SessionRect
    private var navigator = Navigator()
    private val stack = mutableListOf<String>()
    private var rage: RageDetector
    private var backHint: Pair<SessionBackVia, Long>? = null
    private val scrolls = sortedMapOf<String, SessionRules.ScrollDepth>()
    private var noCaptureDepth = 0

    init {
        val screen = deviceInfo().screen
        window = SessionRect(0.0, 0.0, screen.w, screen.h)
        rage = RageDetector(window.size)
    }

    /** The recording's decision from the sink for this session. Without one, `BUFFER`. */
    @Volatile public var replayMode: SessionReplayMode = SessionReplayMode.BUFFER
        private set

    public val currentSessionId: String? get() = synchronized(lock) { state.open?.device?.sessionId }
    public val currentSessionStartedAt: Long? get() = synchronized(lock) { state.open?.device?.startedAt }
    public val previousSessionStartedAt: Long? get() = synchronized(lock) { state.lastClosed?.device?.startedAt }
    public val currentScreen: String? get() = synchronized(lock) { navigator.current }
    /** The light record never captures frames (the recording does), so it never reports dead taps. */
    public val isCapturing: Boolean get() = false
    /** A screen marked `Modifier.sessionNoCapture()` is on screen. */
    public val isCaptureSuppressed: Boolean get() = synchronized(lock) { noCaptureDepth > 0 }
    /** Batches waiting for the sink. */
    public val waitingBatches: Int get() = synchronized(lock) { state.outbox.size + if (state.open?.pending?.isNotEmpty() == true) 1 else 0 }

    // ---- Lifecycle

    /** Once, at launch: ends the previous session with what is known about it and starts a new one. */
    public fun start(exit: SessionExitInfo = SessionExitInfo.NONE) {
        synchronized(lock) {
            if (started) return
            started = true
            current = this
            state = store.load() ?: SessionStoredState()
            val hung = store.hangMarker()
            store.setHangMarker(false)
            var previousEnd = SessionEndReason.UNKNOWN
            var previousId: String? = null
            val open = state.open
            val closed = state.lastClosed
            if (open != null) {
                val lastKind = if (hung) "hang" else open.lastKind
                val decision = SessionRules.endReason(
                    SessionRules.EndInput(
                        platform = open.device.info.platform, androidApi = exit.androidApi, previousRecord = true,
                        runningFlag = state.running, lastEvent = lastKind, metricKitCrash = exit.metricKitCrash,
                        exitReason = exit.exitReason, crashHandler = state.crashRecorded,
                    ),
                )
                close(open, open.lastT, decision.reason, lastKind, state.running, open.scrolls)
                previousEnd = decision.reason
                previousId = open.device.sessionId
            } else if (closed != null) {
                previousEnd = closed.reason
                previousId = closed.device.sessionId
            }
            state = state.copy(crashRecorded = false)
            begin(previousEnd, previousId)
        }
        flush()
    }

    public fun enterBackground() {
        synchronized(lock) {
            if (state.open == null || backgroundAt != null) return
            closeRage()
            val now = clock.nowMs()
            record(SessionEvent.Background(t(now)))
            backgroundAt = now
            state = state.copy(running = false)
            save()
        }
        flush()
    }

    public fun enterForeground() {
        synchronized(lock) {
            val open = state.open ?: return
            val at = backgroundAt ?: return
            backgroundAt = null
            val now = clock.nowMs()
            if (now - at >= SessionRules.BACKGROUND_TIMEOUT_MS) {
                val screen = navigator.current
                close(open, at - open.device.startedAt, SessionEndReason.BACKGROUND_TIMEOUT, "background", false, pendingScrolls(at - open.device.startedAt))
                begin(SessionEndReason.BACKGROUND_TIMEOUT, open.device.sessionId)
                if (screen != null) enterScreen(screen, SessionScreenVia.UNKNOWN)
            } else {
                record(SessionEvent.Foreground(t(now)))
                state = state.copy(running = true)
            }
            save()
        }
        flush()
    }

    /**
     * Call every few seconds while in the front (`sessions-compose` does): closes a finished rage
     * cluster, keeps the state and sends a batch when one is due.
     */
    public fun tick() {
        val due = synchronized(lock) {
            if (state.open == null || backgroundAt != null) return
            rage.expire(t(clock.nowMs()))?.let(::recordRage)
            if (dirty) save()
            clock.nowMs() - lastFlushAt >= configuration.flushIntervalMs && state.open?.pending?.isNotEmpty() == true
        }
        if (due) flush()
    }

    // ---- Screens

    /**
     * A screen was shown. Without `op`, going to the screen under the current one is `pop`, the
     * first screen of a session is `launch`, and anything else is `push`.
     */
    public fun screen(name: String, op: SessionRules.NavigationOp? = null, by: SessionBackVia? = null): Unit = synchronized(lock) {
        if (state.open == null || name == navigator.current) return
        val now = clock.nowMs()
        val move = op ?: when {
            navigator.current == null -> SessionRules.NavigationOp.LAUNCH
            stack.size >= 2 && stack[stack.size - 2] == name -> SessionRules.NavigationOp.POP
            else -> SessionRules.NavigationOp.PUSH
        }
        leaveScreen(now)
        var via = by
        val hint = backHint
        val goingBack = move == SessionRules.NavigationOp.POP || move == SessionRules.NavigationOp.DISMISS
        if (via == null && goingBack && hint != null && now - hint.second <= configuration.backHintMs) via = hint.first
        backHint = null
        val events = navigator.move(SessionRules.NavigationStep(t(now), move, name, via))
        when (move) {
            SessionRules.NavigationOp.LAUNCH, SessionRules.NavigationOp.TAB -> { stack.clear(); stack.add(name) }
            SessionRules.NavigationOp.PUSH, SessionRules.NavigationOp.SHEET -> stack.add(name)
            SessionRules.NavigationOp.POP, SessionRules.NavigationOp.DISMISS -> {
                if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                if (stack.lastOrNull() != name) { if (stack.isEmpty()) stack.add(name) else stack[stack.lastIndex] = name }
            }
        }
        for (event in events) {
            when (event) {
                is SessionRules.NavigationEvent.Screen ->
                    record(SessionEvent.Screen(event.t, event.screen, event.via, window.w, window.h, window.size.orientation))
                is SessionRules.NavigationEvent.Back -> record(SessionEvent.Back(event.t, event.screen, event.via))
                is SessionRules.NavigationEvent.QuickBack -> record(SessionEvent.QuickBack(event.t, event.screen, event.ms))
            }
        }
    }

    /** A back gesture (`GESTURE`) or the back key (`SYSTEM`) is happening; the next screen change that goes back takes it as its `via`. */
    public fun noteBack(via: SessionBackVia): Unit = synchronized(lock) { backHint = via to clock.nowMs() }

    /** The window the app is shown in, in screen dp (taps are measured against it). */
    public fun updateWindow(frame: SessionRect): Unit = synchronized(lock) {
        if (frame.w <= 0 || frame.h <= 0) return
        window = frame
        rage.window = frame.size
    }

    // ---- Touches, scrolls, keyboard

    /** A finger left the screen at `x`, `y` (screen dp) in `window` (null: the last one given). */
    public fun tap(x: Double, y: Double, window: SessionRect? = null, target: String? = null): Unit = synchronized(lock) {
        if (state.open == null || backgroundAt != null) return
        if (window != null) updateWindow(window)
        val now = t(clock.nowMs())
        val frame = this.window
        val position = SessionRules.normalizeTap(x, y, frame)
        record(SessionEvent.Tap(now, position.x, position.y, screenName, target))
        rage.add(SessionRules.Tap(now, x - frame.x, y - frame.y))?.let(::recordRage)
    }

    /** The shape of a scroll view on the current screen. `list` is a list marked `Modifier.sessionScroll`; null is the screen's main one. */
    public fun scroll(sample: SessionRules.ScrollSample, list: String? = null): Unit = synchronized(lock) {
        if (state.open == null) return
        val key = list ?: ""
        val previous = scrolls[key]
        val rows = listOfNotNull(previous?.maxRow, sample.row)
        val depth = SessionRules.ScrollDepth(max(previous?.maxRatio ?: 0, sample.ratio), rows.maxOrNull())
        if (depth != previous) {
            scrolls[key] = depth
            dirty = true
        }
    }

    /** A row of the list `list` came into view. */
    public fun scrollRow(row: Int, list: String): Unit = synchronized(lock) {
        if (state.open == null || row < 0) return
        val previous = scrolls[list]
        val depth = SessionRules.ScrollDepth(previous?.maxRatio ?: 0, max(previous?.maxRow ?: row, row))
        if (depth != previous) {
            scrolls[list] = depth
            dirty = true
        }
    }

    public fun keyboard(shown: Boolean): Unit = synchronized(lock) {
        if (state.open == null) return
        record(SessionEvent.Keyboard(t(clock.nowMs()), shown))
    }

    // ---- App events and marks

    /**
     * An analytics event. `screen_viewed` also moves to its `screen`. Some events also make a mark
     * ([Configuration.marks]). `source` defaults to `COMMON` for swift-analytics' shared catalog.
     * A null value stands for one that is not text, a number or yes/no; it is not kept.
     */
    public fun observe(name: String, props: Map<String, SessionValue?> = emptyMap(), source: SessionRules.EventSource? = null): Unit = synchronized(lock) {
        if (state.open == null) return
        if (name == "screen_viewed") {
            val screen = (props["screen"] as? SessionValue.Text)?.value
            if (screen != null && SCREEN_NAME.matches(screen)) screen(screen)
        }
        val now = t(clock.nowMs())
        val from = source ?: if (name in SessionRules.COMMON_CATALOG) SessionRules.EventSource.COMMON else SessionRules.EventSource.APP
        val kept = SessionRules.keepProps(from, name, props, configuration.keepProps)
        val limited = kept.props.toSortedMap().entries.take(32).associate { it.key to it.value }
        record(SessionEvent.App(now, name, limited, kept.propsDropped))
        configuration.marks.mark(name, props, now)?.let(::record)
    }

    /** A mark the app knows of without an event (a ticket sent from the support SDK). */
    public fun mark(kind: SessionMarkKind, ref: String, side: String? = null): Unit = synchronized(lock) {
        if (state.open == null || ref.isEmpty()) return
        record(SessionEvent.Mark(t(clock.nowMs()), kind, ref.take(80), side))
    }

    // ---- Hangs and crashes

    /** The main thread did not answer from `startedAtMs` for `ms`. Reported by [HangMonitor]. */
    public fun hang(startedAtMs: Long, ms: Long): Unit = synchronized(lock) {
        store.setHangMarker(false)
        if (state.open == null || ms < 2000) return
        record(SessionEvent.Hang(t(startedAtMs), ms))
        save()
    }

    /**
     * Android before API 30: call from the uncaught exception handler (`sessions-compose` does). Saves at
     * once, on the crashing thread, that the app crashed, so the next launch ends the session as `crashed`.
     */
    public fun recordUncaughtCrash(): Unit = synchronized(lock) {
        state = state.copy(crashRecorded = true)
        save()
    }

    /** A crash report for the previous session came after this launch. Sends that session's `end` again with the revised reason. */
    public fun previousSessionCrashReported() {
        synchronized(lock) {
            val closed = state.lastClosed ?: return
            val decision = SessionRules.endReason(
                SessionRules.EndInput(
                    platform = closed.device.info.platform, previousRecord = true, runningFlag = closed.runningFlag,
                    lastEvent = closed.lastKind, lateMetricKitCrash = true,
                ),
            )
            val revised = decision.revised ?: return
            if (revised == closed.reason) return
            appendToOutbox(SessionBatch(closed.device, closed.nextSeq, listOf(SessionEvent.End(closed.endT, revised))))
            state = state.copy(lastClosed = closed.copy(nextSeq = closed.nextSeq + 1, reason = revised))
            save()
        }
        flush()
    }

    // ---- Capture marks

    public fun beginNoCapture(): Unit = synchronized(lock) { noCaptureDepth++ }
    public fun endNoCapture(): Unit = synchronized(lock) { noCaptureDepth = max(0, noCaptureDepth - 1) }

    // ---- Sending

    /** Puts what is pending into a batch and hands every waiting batch to the sink on `sender`, oldest first. */
    public fun flush() {
        sender.execute { sendAll() }
    }

    private fun sendAll() {
        synchronized(lock) {
            if (flushing) return
            flushing = true
            lastFlushAt = clock.nowMs()
            batchPending()
            save()
        }
        try {
            while (true) {
                val batch = synchronized(lock) { state.outbox.firstOrNull() } ?: break
                val reply = try {
                    sink.send(batch)
                } catch (_: Exception) {
                    break
                }
                synchronized(lock) {
                    val same = { b: SessionBatch -> b.session.sessionId == batch.session.sessionId }
                    state = state.copy(outbox = state.outbox.filterNot { same(it) && it.seq == batch.seq })
                    val current = batch.session.sessionId == state.open?.device?.sessionId
                    when (reply) {
                        is SessionSinkReply.Accepted -> {
                            if (current && reply.replay != null) replayMode = reply.replay
                            reply.keepProps?.let { configuration = configuration.copy(keepProps = it) }
                        }
                        SessionSinkReply.Disabled -> {
                            state = state.copy(outbox = state.outbox.filterNot(same))
                            if (current) {
                                disabled = true
                                state = state.copy(open = state.open?.copy(pending = emptyList()))
                            }
                        }
                    }
                    save()
                }
            }
        } finally {
            synchronized(lock) { flushing = false }
        }
    }

    // ---- Inside (called with the lock held)

    private val screenName: String get() = navigator.current ?: "unknown"

    private fun t(now: Long): Long {
        val start = state.open?.device?.startedAt ?: return 0
        return max(0, now - start)
    }

    private fun begin(previousEnd: SessionEndReason, previousId: String?) {
        val now = clock.nowMs()
        val info = deviceInfo()
        val installId = (info.installId ?: state.installId ?: ids.makeInstallId()).lowercase()
        val device = SessionDevice(ids.makeSessionId(), now, installId, info)
        val first = !state.hadSession
        state = state.copy(
            installId = installId, hadSession = true, running = true,
            open = OpenSessionRecord(device, 0, "start", 0, listOf(SessionEvent.Start(0, previousEnd, previousId, first))),
        )
        disabled = false
        replayMode = SessionReplayMode.BUFFER
        navigator = Navigator()
        stack.clear()
        scrolls.clear()
        backHint = null
        rage = RageDetector(window.size)
        save()
    }

    /** Ends `open`: the scroll depths it still had, `end`, and its batches into the outbox. */
    private fun close(open: OpenSessionRecord, endT: Long, reason: SessionEndReason, lastKind: String?, runningFlag: Boolean, scrolls: List<SessionEvent.Scroll>) {
        var pending = open.pending
        if (!disabled) pending = pending + scrolls.map { it.copy(t = endT) } + SessionEvent.End(endT, reason)
        state = state.copy(open = open.copy(pending = pending))
        batchPending()
        val next = state.open?.nextSeq ?: open.nextSeq
        state = state.copy(open = null, lastClosed = ClosedSessionRecord(open.device, next, endT, reason, lastKind, runningFlag))
    }

    private fun leaveScreen(now: Long) {
        closeRage()
        if (navigator.current == null) return
        pendingScrolls(t(now)).forEach(::record)
        scrolls.clear()
    }

    private fun enterScreen(name: String, via: SessionScreenVia) {
        val now = t(clock.nowMs())
        navigator.enter(name, now)
        if (stack.isEmpty()) stack.add(name)
        record(SessionEvent.Screen(now, name, via, window.w, window.h, window.size.orientation))
    }

    private fun pendingScrolls(at: Long): List<SessionEvent.Scroll> {
        val screen = navigator.current ?: return emptyList()
        return scrolls.map { (key, depth) -> SessionEvent.Scroll(at, screen, key.ifEmpty { null }, depth.maxRatio, depth.maxRow) }
    }

    private fun closeRage() { rage.finish()?.let(::recordRage) }

    private fun recordRage(found: SessionRules.Rage) {
        record(SessionEvent.Rage(found.t, found.x, found.y, found.count, screenName))
    }

    private fun record(event: SessionEvent) {
        if (disabled) return
        val open = state.open ?: return
        var pending = open.pending + event
        if (pending.size > configuration.maxPendingEvents) pending = pending.takeLast(configuration.maxPendingEvents)
        state = state.copy(open = open.copy(pending = pending, lastKind = event.kind, lastT = max(open.lastT, event.t)))
        dirty = true
    }

    private fun batchPending() {
        val open = state.open ?: return
        if (open.pending.isEmpty()) return
        var seq = open.nextSeq
        open.pending.chunked(max(1, configuration.maxEventsPerBatch)).forEach { chunk ->
            appendToOutbox(SessionBatch(open.device, seq, chunk))
            seq++
        }
        state = state.copy(open = state.open?.copy(pending = emptyList(), nextSeq = seq))
    }

    private fun appendToOutbox(batch: SessionBatch) {
        var outbox = state.outbox + batch
        if (outbox.size > configuration.maxOutboxBatches) outbox = outbox.takeLast(configuration.maxOutboxBatches)
        state = state.copy(outbox = outbox)
    }

    private fun save() {
        val open = state.open
        if (open != null) state = state.copy(open = open.copy(scrolls = pendingScrolls(open.lastT)))
        // A full disk or an app's store that throws must not crash the app from tick or enterBackground (on the
        // main thread). The state stays dirty, so the next tick writes it again.
        dirty = try {
            store.save(state)
            false
        } catch (_: Exception) {
            true
        }
    }

    public companion object {
        private val SCREEN_NAME = Regex("^[a-z][a-z0-9_]{0,47}$")

        /** The recorder that was started last. The Compose marks report to it. */
        @Volatile public var current: AppSessions? = null
            private set
    }
}
