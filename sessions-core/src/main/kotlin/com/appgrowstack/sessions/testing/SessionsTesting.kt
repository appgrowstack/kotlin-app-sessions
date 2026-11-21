package com.appgrowstack.sessions.testing

import com.appgrowstack.sessions.SessionBatch
import com.appgrowstack.sessions.SessionClock
import com.appgrowstack.sessions.SessionDeviceInfo
import com.appgrowstack.sessions.SessionEvent
import com.appgrowstack.sessions.SessionIdGenerator
import com.appgrowstack.sessions.SessionPlatform
import com.appgrowstack.sessions.SessionScreen
import com.appgrowstack.sessions.SessionSink
import com.appgrowstack.sessions.SessionSinkReply
import com.appgrowstack.sessions.SessionStateStore
import com.appgrowstack.sessions.SessionStoredState
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** A clock that moves only when told. */
public class FakeSessionClock(start: Long = 1_795_230_000_000) : SessionClock {
    private val millis = AtomicLong(start)
    override fun nowMs(): Long = millis.get()
    public fun advance(ms: Long) { millis.addAndGet(ms) }
    public fun set(nowMs: Long) { millis.set(nowMs) }
}

/** Session ids `ses_test…0001`, `ses_test…0002` … and install ids `install-0001` … in order. */
public class SequenceSessionIds : SessionIdGenerator {
    private val sessions = AtomicInteger()
    private val installs = AtomicInteger()
    override fun makeSessionId(): String = "ses_test" + sessions.incrementAndGet().toString().padStart(16, '0')
    override fun makeInstallId(): String = "install-" + installs.incrementAndGet().toString().padStart(4, '0')
}

/** Keeps the state in memory. Share one between two recorders to act out a relaunch. */
public class InMemorySessionStateStore(initial: SessionStoredState? = null) : SessionStateStore {
    @Volatile public var stored: SessionStoredState? = initial
        private set
    @Volatile private var hang = false
    override fun load(): SessionStoredState? = stored
    override fun save(state: SessionStoredState) { stored = state }
    override fun setHangMarker(on: Boolean) { hang = on }
    override fun hangMarker(): Boolean = hang
}

/** Keeps every batch it is given and answers from a queue (with nothing queued, `Accepted`). `failing` throws like a device offline. */
public class RecordingSessionSink : SessionSink {
    public val batches: MutableList<SessionBatch> = CopyOnWriteArrayList()
    private val replies = ConcurrentLinkedQueue<SessionSinkReply>()
    @Volatile public var failing: Boolean = false

    public fun enqueue(reply: SessionSinkReply) { replies.add(reply) }

    override fun send(batch: SessionBatch): SessionSinkReply {
        if (failing) throw IllegalStateException("offline")
        batches.add(batch)
        return replies.poll() ?: SessionSinkReply.Accepted()
    }

    /** Every event sent, in order, with the session it belongs to. */
    public val events: List<Pair<String, SessionEvent>> get() = batches.flatMap { b -> b.events.map { b.session.sessionId to it } }
}

/** Runs the sending on the calling thread. */
public val DirectExecutor: Executor = Executor { it.run() }

/** A phone with a 393 × 852 dp screen. */
public fun SessionDeviceInfo.Companion.test(installId: String? = null, uid: String? = null, platform: SessionPlatform = SessionPlatform.ANDROID): SessionDeviceInfo =
    SessionDeviceInfo(
        installId = installId, uid = uid, platform = platform, appVersion = "1.8.0", build = "42", channel = "debug",
        lang = "ja", region = "JP", timeZone = "Asia/Tokyo", osVersion = "15", deviceModel = "Pixel 9",
        screen = SessionScreen(393.0, 852.0, 3.0),
    )
