package com.appgrowstack.sessions

import java.util.concurrent.atomic.AtomicLong

/**
 * Watches the main thread from a thread of its own: every `intervalMs` it posts the main thread a
 * small task (`postToMain`, on Android `Handler(Looper.getMainLooper())::post`), and when the task
 * does not run for `thresholdMs` the main thread is hung. While it is hung the store's hang marker is
 * set, so a session killed in the hang still ends as `hung`; when the main thread answers again,
 * `report` gets when it stopped and for how long.
 */
public class HangMonitor(
    private val postToMain: (Runnable) -> Unit,
    private val store: SessionStateStore,
    private val report: (startedAtMs: Long, ms: Long) -> Unit,
    public val thresholdMs: Long = 2000,
    public val intervalMs: Long = 500,
    private val clock: SessionClock = SystemSessionClock,
) {
    /** Watches `sessions` and reports to it. */
    public constructor(sessions: AppSessions, store: SessionStateStore, postToMain: (Runnable) -> Unit, thresholdMs: Long = 2000) :
        this(postToMain, store, { startedAt, ms -> sessions.hang(startedAt, ms) }, thresholdMs)

    @Volatile private var running = false
    private val answeredAt = AtomicLong(NOT_YET)

    public fun start() {
        synchronized(this) {
            if (running) return
            running = true
        }
        Thread({ loop() }, "app-sessions.hang-monitor").apply { isDaemon = true }.start()
    }

    public fun stop() { running = false }

    private fun loop() {
        while (running) {
            val sentAt = clock.nowMs()
            answeredAt.set(NOT_YET)
            postToMain(Runnable { answeredAt.set(clock.nowMs()) })
            Thread.sleep(intervalMs)
            var marked = false
            while (running) {
                val answered = answeredAt.get()
                if (answered != NOT_YET) {
                    val ms = answered - sentAt
                    if (marked || ms >= thresholdMs) postToMain(Runnable { report(sentAt, ms) })
                    break
                }
                if (!marked && clock.nowMs() - sentAt >= thresholdMs) {
                    store.setHangMarker(true)
                    marked = true
                }
                Thread.sleep(50)
            }
        }
    }

    private companion object {
        const val NOT_YET = -1L
    }
}
