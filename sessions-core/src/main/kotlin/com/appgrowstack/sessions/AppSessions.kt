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
 * With `replay`, it also keeps the recording: `sessions-compose` hands it frames ([replayImage],
 * [replayBlank]), and it decides which segments are sent from the sink's answer ([replayMode]): `RECORD`
 * sends every one, `BUFFER` keeps the last 60 seconds and sends them, and what follows, once a priority
 * condition is met ([SessionRules.priorityHits], or the session crashed, hung or was killed), `NONE`
 * takes none. Segments go on an unmetered network; a priority session's also on a metered one, up to
 * 3 MB a day. They go with each batch (every 30 seconds in the front), when the app goes to the back and
 * at the next launch, with the `upload_token` the session's first answer carried ([uploadToken]).
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
    /** The recording's segments and where they go. Null: the light record only, no frames. */
    public val replay: SessionReplayRecorder? = null,
    /** Opens the live view when an answer says someone is watching. Null: no live view. */
    private val liveTransport: SessionLiveTransport? = null,
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
        /** While someone watches live, a batch goes out this often instead. */
        val liveFlushIntervalMs: Long = 5_000,
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
    private val noCaptureHolds = mutableSetOf<NoCaptureHold>()

    // The recording
    private var lastFingerprint: String? = null
    private val deadCandidates = mutableListOf<DeadCandidate>()
    private val afterTaps = mutableListOf<SessionRules.After>()
    private var counts = ReplayCounts()
    private val recentMainMs = ArrayDeque<Double>()
    private var stoppedForSession: SessionReplayStopReason? = null
    private var paused: SessionReplayStopReason? = null
    private var stoppedToReport: SessionReplayStopReason? = null
    private var segmentWaiting = false
    private var live: SessionLiveLink? = null

    /** Where the device is: an unmetered network sends every segment. `sessions-compose` sets Android's. */
    @Volatile public var network: SessionNetwork = SessionNetwork.UNMETERED

    init {
        val screen = deviceInfo().screen
        window = SessionRect(0.0, 0.0, screen.w, screen.h)
        rage = RageDetector(window.size)
    }

    /** The recording's decision from the sink for this session. Without one, `BUFFER`. `NONE` stays to the end of the session. */
    @Volatile public var replayMode: SessionReplayMode = SessionReplayMode.BUFFER
        private set

    public val currentSessionId: String? get() = synchronized(lock) { state.open?.device?.sessionId }
    public val currentSessionStartedAt: Long? get() = synchronized(lock) { state.open?.device?.startedAt }
    public val previousSessionStartedAt: Long? get() = synchronized(lock) { state.lastClosed?.device?.startedAt }
    public val currentScreen: String? get() = synchronized(lock) { navigator.current }
    /**
     * Frames of this session are being taken and the last one had a fingerprint: taps are then judged
     * dead when nothing changed within 1.5 seconds ([SessionRules.isDead]).
     */
    public val isCapturing: Boolean get() = synchronized(lock) { capturingNow() && lastFingerprint != null }

    /** `sessions-compose` should take frames now: a recording, a session in the front, a mode other than `NONE`, nothing stopping it. */
    public val replayActive: Boolean get() = synchronized(lock) { capturingNow() }

    /** Why the recording is stopped now (for the session, or while the device is low on power), or null. */
    public val replayStopped: SessionReplayStopReason? get() = synchronized(lock) { stoppedForSession ?: paused }

    /** The shortest time between two frames now: 1 second, 2 when taking them weighs (p95 over 16 ms), 0.5 while someone watches live. */
    public val replayFrameIntervalMs: Long
        get() = synchronized(lock) {
            val link = live
            when {
                link != null && link.open -> minOf(1000L, link.frameIntervalMs)
                percentile(recentMainMs.toList(), 0.95) > 16 -> 2000
                else -> 1000
            }
        }

    /** The `upload_token` kept for `sessionId` (null until the answer to its first batch carries one). */
    public fun uploadToken(sessionId: String): String? = synchronized(lock) { replayRecord(sessionId)?.uploadToken }

    /** Someone is watching live now. */
    public val liveOpen: Boolean get() = synchronized(lock) { live?.open == true }
    /** A `Modifier.sessionNoCapture()` holds: its screen is shown or lies under the shown one ([NoCaptureHold]). */
    public val noCaptureHeld: Boolean get() = synchronized(lock) { noCaptureHolds.isNotEmpty() }
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
            replay?.let { recorder ->
                val kept = state.replays.map { it.sessionId }.toSet()
                recorder.sessionIds().filter { it !in kept }.forEach(recorder::removeSession)
                recorder.sweep(clock.nowMs(), sendingIds())
            }
            save()
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
            live?.close()
            deadCandidates.clear()
            afterTaps.clear()
            lastFingerprint = null
            if (replay?.closeOpen() == true) segmentClosed(t(now))
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
            val open = state.open
            if (open == null || backgroundAt != null) return
            val now = t(clock.nowMs())
            rage.expire(now)?.let(::recordRage)
            resolveDead(now)
            if (replay?.closeIfDue(open.device.sessionId, now) == true) segmentClosed(now)
            if (dirty) save()
            val interval = if (live?.open == true) configuration.liveFlushIntervalMs else configuration.flushIntervalMs
            clock.nowMs() - lastFlushAt >= interval && (state.open?.pending?.isNotEmpty() == true || segmentWaiting)
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
        val fingerprint = lastFingerprint
        if (fingerprint != null && capturingNow()) deadCandidates.add(DeadCandidate(now, position.x, position.y, screenName, fingerprint))
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

    /** A `Modifier.sessionNoCapture()` entered the composition. The hold lasts until [NoCaptureHold] ends it. */
    public fun holdNoCapture(): NoCaptureHold = synchronized(lock) {
        NoCaptureHold { hold -> synchronized(lock) { noCaptureHolds.remove(hold) } }.also { noCaptureHolds.add(it) }
    }

    // ---- The recording (sessions-compose calls these from its capture thread)

    /**
     * A frame was taken now: `w` × `h` pixels (1 dp is 1 px), already painted over where it must be
     * (`masked`), with its [SessionRules.frameFingerprint]. `encode` gives its image (`mime`) and is called
     * only when the frame changed (or the live viewer needs one), on this thread. `mainMs` is the time the
     * main thread spent on it.
     */
    public fun replayImage(w: Int, h: Int, masked: Boolean, fingerprint: String, mainMs: Double, mime: String, encode: () -> ByteArray) {
        val recorder = replay ?: return
        val (id, t, link) = synchronized(lock) {
            if (!capturingNow()) return
            val t = t(clock.nowMs())
            if (!noteFrame(t, mainMs)) return
            Triple(state.open!!.device.sessionId, t, live)
        }
        val changed = recorder.wantsImage(id, fingerprint, t)
        val forLive = link != null && link.wantsFrame() && (changed || link.needsImage)
        val bytes = if (changed || forLive) {
            runCatching { encode() }.getOrNull() ?: return
        } else {
            null
        }
        val frame = if (bytes != null) SessionReplayFrame.Image(t, w, h, masked, mime, bytes) else SessionReplayFrame.Same(t, w, h, masked)
        val added = recorder.add(id, frame, fingerprint)
        if (bytes != null && forLive) link?.frame(t, w, h, masked, mime, bytes)
        synchronized(lock) {
            if (state.open?.device?.sessionId != id) return
            if (bytes != null) counts.captured++ else counts.same++
            lastFingerprint = fingerprint
            if (deadCandidates.isNotEmpty()) afterTaps.add(SessionRules.After(t, SessionRules.AfterKind.FRAME, fingerprint))
            resolveDead(t)
            afterAdd(added, t)
        }
    }

    /** A frame was not taken now, for `reason` ([SessionRules.blankReason], or the painting failed: `MASKING_FAILED`). */
    public fun replayBlank(w: Int, h: Int, reason: SessionBlankReason, mainMs: Double = 0.0) {
        val recorder = replay ?: return
        val (id, t) = synchronized(lock) {
            if (!capturingNow()) return
            val t = t(clock.nowMs())
            if (!noteFrame(t, mainMs)) return
            state.open!!.device.sessionId to t
        }
        val added = recorder.add(id, SessionReplayFrame.Blank(t, w, h, reason), null)
        synchronized(lock) {
            if (state.open?.device?.sessionId != id) return
            when (reason) {
                SessionBlankReason.MASKING_FAILED -> counts.maskingFailed++
                SessionBlankReason.NO_CAPTURE -> counts.noCapture++
                SessionBlankReason.SECURE -> counts.secure++
                SessionBlankReason.UNSUPPORTED -> Unit
            }
            lastFingerprint = null
            afterAdd(added, t)
        }
    }

    /**
     * The device asks the recording to rest (`LOW_POWER`, `THERMAL`, `BATTERY`), or no longer (null).
     * `sessions-compose` takes no frames meanwhile; the next batch's `replay_stats` says why.
     */
    public fun replayPaused(reason: SessionReplayStopReason?): Unit = synchronized(lock) {
        if (paused == reason) return
        paused = reason
        if (reason != null) stoppedToReport = reason
    }

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
                            accepted(batch, reply, current)
                            reply.keepProps?.let { configuration = configuration.copy(keepProps = it) }
                        }
                        SessionSinkReply.Disabled -> {
                            state = state.copy(outbox = state.outbox.filterNot(same))
                            dropReplay(batch.session.sessionId)
                            if (current) {
                                disabled = true
                                state = state.copy(open = state.open?.copy(pending = emptyList()))
                                live?.close()
                            }
                        }
                    }
                    save()
                }
            }
            uploadSegments()
        } finally {
            synchronized(lock) { flushing = false }
        }
    }

    /** Hands the closed segments of the sessions that send to [SessionReplayRecorder.sink], oldest first, until one must wait. */
    private fun uploadSegments() {
        val recorder = replay ?: return
        synchronized(lock) {
            segmentWaiting = false
            val current = state.open?.device?.sessionId
            val kept = state.replays.filter { it.sessionId == current || recorder.segments(it.sessionId).isNotEmpty() }
            if (kept.size != state.replays.size) {
                state = state.copy(replays = kept)
                save()
            }
        }
        while (true) {
            val kind = network.current()
            if (kind == SessionNetworkKind.NONE) return
            val (record, seq) = synchronized(lock) { nextUpload(recorder, kind) } ?: return
            // Read off the lock: the main thread's taps and frames must not wait on the disk
            val bytes = recorder.read(record.sessionId, seq)
            if (bytes == null) {
                synchronized(lock) { recorder.remove(record.sessionId, seq) }
                continue
            }
            val reply = try {
                recorder.sink.upload(SessionReplayUpload(record.sessionId, seq, record.installId, record.uploadToken, bytes))
            } catch (_: Exception) {
                return
            }
            synchronized(lock) {
                when (reply) {
                    SessionReplayUploadReply.UPLOADED -> {
                        recorder.remove(record.sessionId, seq)
                        if (kind == SessionNetworkKind.METERED) state = state.copy(meteredBytes = state.meteredBytes + bytes.size)
                    }
                    SessionReplayUploadReply.REJECTED -> recorder.remove(record.sessionId, seq)
                    SessionReplayUploadReply.FORBIDDEN -> {
                        // The token is wrong or ran out: nothing of this session will pass any more
                        recorder.removeSession(record.sessionId)
                        updateReplay(record.sessionId) { it.copy(mode = SessionReplayMode.NONE, uploadToken = null) }
                        if (record.sessionId == state.open?.device?.sessionId) replayMode = SessionReplayMode.NONE
                    }
                }
                save()
            }
        }
    }

    /** The next segment to send on `kind`: its session sends, has its first answer, and a metered network still has room today. */
    private fun nextUpload(recorder: SessionReplayRecorder, kind: SessionNetworkKind): Pair<SessionReplayRecord, Int>? {
        val day = clock.nowMs() / 86_400_000
        if (state.meteredDay != day) state = state.copy(meteredDay = day, meteredBytes = 0)
        for (record in state.replays) {
            if (!record.sends || (record.uploadToken == null && !record.answered)) continue
            if (kind == SessionNetworkKind.METERED && !record.priority) continue
            val seq = recorder.segments(record.sessionId).firstOrNull() ?: continue
            if (kind == SessionNetworkKind.METERED && state.meteredBytes + recorder.size(record.sessionId, seq) > recorder.limits.meteredDailyBytes) return null
            return record to seq
        }
        return null
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
            open = SessionEvent.Start(0, previousEnd, previousId, first).let { start ->
                OpenSessionRecord(device, 0, "start", 0, listOf(start), priorityEvents = listOf(start))
            },
        )
        disabled = false
        replayMode = SessionReplayMode.BUFFER
        live?.close()
        live = null
        lastFingerprint = null
        deadCandidates.clear()
        afterTaps.clear()
        counts = ReplayCounts()
        recentMainMs.clear()
        stoppedForSession = null
        stoppedToReport = null
        replay?.let {
            it.begin(device.sessionId)
            state = state.copy(replays = state.replays + SessionReplayRecord(device.sessionId, now, installId))
        }
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
        if (!disabled) pending = pending + listOfNotNull(replayStats(endT)) + scrolls.map { it.copy(t = endT) } + SessionEvent.End(endT, reason)
        state = state.copy(open = open.copy(pending = pending))
        batchPending()
        val next = state.open?.nextSeq ?: open.nextSeq
        state = state.copy(open = null, lastClosed = ClosedSessionRecord(open.device, next, endT, reason, lastKind, runningFlag))
        closeReplay(open, endT, reason)
    }

    /**
     * The recording of a session that ended: it is sent when it records, when a priority condition was met
     * (with its end, now) or when it crashed, hung or was killed; anything else of it is removed.
     */
    private fun closeReplay(open: OpenSessionRecord, endT: Long, reason: SessionEndReason) {
        val recorder = replay ?: return
        val id = open.device.sessionId
        live?.close()
        recorder.closeOpen()
        val hits = SessionRules.priorityHits(open.priorityEvents + SessionEvent.End(endT, reason))
        val crashed = reason == SessionEndReason.CRASHED || reason == SessionEndReason.HUNG || reason == SessionEndReason.KILLED
        if (hits.isNotEmpty() || crashed) updateReplay(id) { it.copy(priority = true) }
        val record = replayRecord(id)
        if (record == null || !record.sends) dropReplay(id)
        else segmentWaiting = true
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
        val relevant = forPriority(event)
        val priorityEvents = if (relevant) (open.priorityEvents + event).takeLast(MAX_PRIORITY_EVENTS) else open.priorityEvents
        state = state.copy(open = open.copy(pending = pending, lastKind = event.kind, lastT = max(open.lastT, event.t), priorityEvents = priorityEvents))
        dirty = true
        if (deadCandidates.isNotEmpty()) {
            when (event) {
                is SessionEvent.Screen -> afterTaps.add(SessionRules.After(event.t, SessionRules.AfterKind.SCREEN))
                is SessionEvent.App -> afterTaps.add(SessionRules.After(event.t, SessionRules.AfterKind.EVENT))
                else -> Unit
            }
        }
        live?.event(event)
        if (relevant) checkPriority()
    }

    // ---- The recording (with the lock held)

    private class DeadCandidate(val t: Long, val x: Int, val y: Int, val screen: String, val fingerprint: String)

    private class ReplayCounts {
        var captured = 0
        var same = 0
        var maskingFailed = 0
        var noCapture = 0
        var secure = 0
        val mainMs = mutableListOf<Double>()
        val empty: Boolean get() = captured + same + maskingFailed + noCapture + secure == 0
    }

    private fun capturingNow(): Boolean =
        replay != null && state.open != null && backgroundAt == null && !disabled && replayMode != SessionReplayMode.NONE &&
            stoppedForSession == null && paused == null

    private fun replayRecord(id: String): SessionReplayRecord? = state.replays.firstOrNull { it.sessionId == id }

    private fun updateReplay(id: String, change: (SessionReplayRecord) -> SessionReplayRecord) {
        state = state.copy(replays = state.replays.map { if (it.sessionId == id) change(it) else it })
    }

    private fun sendingIds(): Set<String> = state.replays.filter { it.sends }.map { it.sessionId }.toSet()

    /** Removes the segments of `id` and what the device kept of its recording. */
    private fun dropReplay(id: String) {
        replay?.removeSession(id)
        state = state.copy(replays = state.replays.filterNot { it.sessionId == id })
    }

    /** The answer to one of `batch`'s session's batches. */
    private fun accepted(batch: SessionBatch, reply: SessionSinkReply.Accepted, current: Boolean) {
        val id = batch.session.sessionId
        if (replay == null) {
            if (current && reply.replay != null && replayMode != SessionReplayMode.NONE) replayMode = reply.replay
        } else {
            updateReplay(id) { record ->
                var r = record
                if (batch.seq == 0 || reply.uploadToken != null) r = r.copy(answered = true)
                if (reply.uploadToken != null) r = r.copy(uploadToken = reply.uploadToken)
                if (reply.replay != null && r.mode != SessionReplayMode.NONE) r = r.copy(mode = reply.replay)
                r
            }
            val record = replayRecord(id)
            if (record?.mode == SessionReplayMode.NONE) {
                replay.removeSession(id)
                if (current) stopReplay(SessionReplayStopReason.DISABLED)
            }
            if (current && record != null) replayMode = record.mode
            if (record?.sends == true) segmentWaiting = true
        }
        if (current && reply.live != null) openLive(reply.live)
    }

    private fun openLive(offer: SessionLiveOffer) {
        val transport = liveTransport ?: return
        val open = state.open ?: return
        if (live != null || backgroundAt != null || disabled) return
        val link = SessionLiveLink(
            transport, offer, open.device.sessionId,
            hello = { synchronized(lock) { Triple(screenName, window.w, window.h) } },
            nowMs = clock::nowMs,
            onEnd = { ended -> synchronized(lock) { if (live === ended) live = null } },
        )
        live = link
        try {
            link.start()
        } catch (_: Exception) {
            live = null
        }
    }

    /** Notes a frame's main thread time. False when the frame is not kept: the session reached 30 minutes, or taking frames weighs too much. */
    private fun noteFrame(t: Long, mainMs: Double): Boolean {
        val recorder = replay ?: return false
        if (t >= recorder.limits.sessionMs) {
            stopReplay(SessionReplayStopReason.SESSION_LIMIT)
            return false
        }
        if (mainMs > 0 && mainMs.isFinite()) {
            if (counts.mainMs.size < 2000) counts.mainMs.add(mainMs)
            recentMainMs.addLast(mainMs)
            while (recentMainMs.size > 30) recentMainMs.removeFirst()
            if (recentMainMs.size >= 10 && percentile(recentMainMs.toList(), 0.95) > 33) {
                stopReplay(SessionReplayStopReason.DEGRADED)
                return false
            }
        }
        return true
    }

    private fun afterAdd(added: SessionReplayRecorder.Added, t: Long) {
        if (added.closed) segmentClosed(t)
        if (added.sessionLimit) stopReplay(SessionReplayStopReason.SESSION_LIMIT)
    }

    /** A segment of the current session was written: `BUFFER` keeps the last 60 seconds only, and the folder its limits. */
    private fun segmentClosed(t: Long) {
        val recorder = replay ?: return
        val id = state.open?.device?.sessionId ?: return
        val record = replayRecord(id) ?: return
        if (record.sends) segmentWaiting = true
        else recorder.trim(id, t - recorder.limits.bufferMs)
        if (recorder.sweep(clock.nowMs(), sendingIds()) && record.sends) stopReplay(SessionReplayStopReason.STORAGE)
    }

    /** Stops the recording for the rest of the session. What was taken is kept. */
    private fun stopReplay(reason: SessionReplayStopReason) {
        if (stoppedForSession != null) return
        stoppedForSession = reason
        stoppedToReport = reason
        if (replay?.closeOpen() == true) state.open?.let { segmentClosed(it.lastT) }
    }

    /** A priority condition met in a `BUFFER` session: its buffered segments and the following are sent. */
    private fun checkPriority() {
        val open = state.open ?: return
        val record = replayRecord(open.device.sessionId) ?: return
        if (record.priority || record.mode == SessionReplayMode.NONE) return
        if (SessionRules.priorityHits(open.priorityEvents).isEmpty()) return
        updateReplay(record.sessionId) { it.copy(priority = true) }
        segmentWaiting = true
    }

    /** Taps that have had their 1.5 seconds become `dead` when nothing changed. */
    private fun resolveDead(now: Long) {
        if (deadCandidates.isEmpty()) return
        val due = deadCandidates.filter { now > it.t + SessionRules.DEAD_WINDOW_MS }
        if (due.isEmpty()) return
        deadCandidates.removeAll(due)
        val after = afterTaps.toList()
        for (c in due) {
            if (SessionRules.isDead(true, c.t, c.fingerprint, after)) record(SessionEvent.Dead(c.t, c.x, c.y, c.screen))
        }
        val oldest = deadCandidates.minOfOrNull { it.t }
        if (oldest == null) afterTaps.clear() else afterTaps.removeAll { it.t <= oldest }
    }

    /** The recording since the last batch, or null when there is nothing to say. */
    private fun replayStats(t: Long): SessionEvent.ReplayStats? {
        if (replay == null) return null
        val c = counts
        val stopped = stoppedToReport
        if (c.empty && stopped == null) return null
        counts = ReplayCounts()
        stoppedToReport = null
        return SessionEvent.ReplayStats(
            t, c.captured, SessionEvent.ReplayStats.Dropped(c.same, c.maskingFailed, c.noCapture, c.secure),
            percentile(c.mainMs, 0.5), percentile(c.mainMs, 0.95), stopped,
        )
    }

    private fun batchPending() {
        if (state.open != null) replayStats(t(clock.nowMs()))?.let(::record)
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
        private const val MAX_PRIORITY_EVENTS = 200

        /** The events [SessionRules.priorityHits] reads. */
        private fun forPriority(event: SessionEvent): Boolean = when (event) {
            is SessionEvent.Start, is SessionEvent.Rage, is SessionEvent.Dead, is SessionEvent.Hang -> true
            is SessionEvent.Mark -> event.markKind != SessionMarkKind.CAMPAIGN_ELIGIBLE && event.markKind != SessionMarkKind.NOTIFY_OPENED
            is SessionEvent.App -> event.name == "paywall_closed"
            else -> false
        }

        /** The `p` quantile (nearest rank) of `values`, 0 for none. */
        private fun percentile(values: List<Double>, p: Double): Double {
            if (values.isEmpty()) return 0.0
            val sorted = values.sorted()
            val rank = kotlin.math.ceil(p * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }

        /** The recorder that was started last. The Compose marks report to it. */
        @Volatile public var current: AppSessions? = null
            private set
    }
}
