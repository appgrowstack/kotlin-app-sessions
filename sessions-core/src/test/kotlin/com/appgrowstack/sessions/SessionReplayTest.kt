package com.appgrowstack.sessions

import com.appgrowstack.sessions.testing.DirectExecutor
import com.appgrowstack.sessions.testing.FakeSessionClock
import com.appgrowstack.sessions.testing.InMemorySessionStateStore
import com.appgrowstack.sessions.testing.RecordingLiveTransport
import com.appgrowstack.sessions.testing.RecordingSessionReplaySink
import com.appgrowstack.sessions.testing.RecordingSessionSink
import com.appgrowstack.sessions.testing.SequenceSessionIds
import com.appgrowstack.sessions.testing.test
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionReplayTest {
    private val clock = FakeSessionClock()
    private val store = InMemorySessionStateStore()
    private val sink = RecordingSessionSink()
    private val replaySink = RecordingSessionReplaySink()
    private val ids = SequenceSessionIds()
    private val dir: File = Files.createTempDirectory("replay").toFile()
    private val s1 = "ses_test0000000000000001"
    private val s2 = "ses_test0000000000000002"
    private val token = "1795920000." + "a".repeat(64)

    @AfterTest
    fun removeFiles() {
        dir.deleteRecursively()
    }

    private fun make(live: SessionLiveTransport? = null) =
        AppSessions(sink, { SessionDeviceInfo.test() }, store, clock, ids, AppSessions.Configuration(), DirectExecutor, SessionReplayRecorder(dir, replaySink), live)

    private fun launch(
        reply: SessionSinkReply.Accepted = SessionSinkReply.Accepted(SessionReplayMode.BUFFER, uploadToken = token),
        live: SessionLiveTransport? = null,
    ): AppSessions {
        sink.enqueue(reply)
        return make(live).also { it.start() }
    }

    private var shade = 0

    private fun frame(sessions: AppSessions, fingerprint: String = "fp${shade++}", size: Int = 1000, mainMs: Double = 2.0) =
        sessions.replayImage(393, 852, true, fingerprint, mainMs, "image/webp") { ByteArray(size) { 7 } }

    /** One frame a second, each different from the one before. */
    private fun film(sessions: AppSessions, seconds: Int, size: Int = 1000) = repeat(seconds) {
        frame(sessions, size = size)
        clock.advance(1000)
    }

    private fun events() = sink.events.map { it.second }

    private fun rage(sessions: AppSessions) {
        repeat(3) {
            sessions.tap(100.0, 100.0)
            clock.advance(100)
        }
        clock.advance(1000)
        sessions.tick()
    }

    // ---- Segments

    @Test
    fun aSegmentClosesAtSixtySeconds() {
        val sessions = launch(SessionSinkReply.Accepted(SessionReplayMode.RECORD, uploadToken = token))
        film(sessions, 125)
        sessions.flush()
        assertEquals(listOf(0, 1), replaySink.uploads.map { it.seq })
        val first = replaySink.segments[0]
        assertEquals(s1, first.sessionId)
        assertEquals(60, first.frames.size)
        assertEquals(0L, first.tFrom)
        assertEquals(59_000L, first.tTo)
        assertTrue(first.frames.all { it is SessionReplayFrame.Image && it.masked && it.w == 393 && it.h == 852 })
        assertEquals(60_000L, replaySink.segments[1].tFrom)
    }

    @Test
    fun aSegmentClosesBeforeItsImagesPass900KB() {
        val recorder = SessionReplayRecorder(dir, replaySink)
        recorder.begin(s1)
        repeat(3) { i -> recorder.add(s1, SessionReplayFrame.Image(i * 1000L, 393, 852, true, "image/webp", ByteArray(400 * 1024)), "f$i") }
        recorder.closeOpen()
        assertEquals(listOf(0, 1), recorder.segments(s1))
        val bytes = recorder.read(s1, 0)!!
        assertTrue(bytes.size <= SessionReplaySegment.MAX_BYTES)
        assertEquals(2, SessionReplaySegment.decode(bytes).frames.size)
        assertEquals(1, SessionReplaySegment.decode(recorder.read(s1, 1)!!).frames.size)
    }

    @Test
    fun aSegmentClosesAt600Frames() {
        val recorder = SessionReplayRecorder(dir, replaySink)
        recorder.begin(s1)
        recorder.add(s1, SessionReplayFrame.Image(0, 393, 852, false, "image/webp", ByteArray(10)), "a")
        for (i in 1 until 600) recorder.add(s1, SessionReplayFrame.Same(i * 10L, 393, 852, false), "a")
        assertEquals(listOf(0), recorder.segments(s1))
        recorder.add(s1, SessionReplayFrame.Image(6000, 393, 852, false, "image/webp", ByteArray(10)), "b")
        assertEquals(600, SessionReplaySegment.decode(recorder.read(s1, 0)!!).frames.size)
        assertEquals(1, recorder.openFrames)
    }

    @Test
    fun aSegmentBeginsWithItsImage() {
        val recorder = SessionReplayRecorder(dir, replaySink)
        recorder.begin(s1)
        assertTrue(recorder.wantsImage(s1, "a", 0))
        recorder.add(s1, SessionReplayFrame.Image(0, 393, 852, false, "image/webp", ByteArray(10)), "a")
        assertFalse(recorder.wantsImage(s1, "a", 1000))
        assertTrue(recorder.wantsImage(s1, "b", 1000))
        recorder.add(s1, SessionReplayFrame.Blank(2000, 393, 852, SessionBlankReason.SECURE), null)
        // After a blank frame the next one shows itself again
        assertTrue(recorder.wantsImage(s1, "a", 3000))
        recorder.add(s1, SessionReplayFrame.Image(3000, 393, 852, false, "image/webp", ByteArray(10)), "a")
        // Past 60 seconds the segment closes, and the next one starts with an image
        assertTrue(recorder.wantsImage(s1, "a", 60_000))
        assertEquals(listOf(0), recorder.segments(s1))
    }

    @Test
    fun sameFramesKeepNoImage() {
        val sessions = launch(SessionSinkReply.Accepted(SessionReplayMode.RECORD, uploadToken = token))
        var encoded = 0
        repeat(3) {
            sessions.replayImage(393, 852, false, "still", 1.0, "image/webp") { encoded++; ByteArray(10) }
            clock.advance(1000)
        }
        sessions.enterBackground()
        assertEquals(1, encoded)
        val frames = replaySink.segments.single().frames
        assertEquals(listOf("Image", "Same", "Same"), frames.map { it::class.simpleName })
    }

    @Test
    fun theFolderDropsWhatIsOlderThanSevenDaysAndKeeps50MB() {
        val recorder = SessionReplayRecorder(dir, replaySink, SessionReplayRecorder.Limits(totalBytes = 3000))
        recorder.begin(s1)
        for (i in 0 until 3) {
            recorder.add(s1, SessionReplayFrame.Image(i * 60_000L, 393, 852, false, "image/webp", ByteArray(1000)), "f$i")
        }
        recorder.closeOpen()
        recorder.begin(s2)
        recorder.add(s2, SessionReplayFrame.Image(0, 393, 852, false, "image/webp", ByteArray(1000)), "a")
        recorder.closeOpen()
        val now = clock.nowMs()
        File(File(recorder.directory, s1), "0.agsr").setLastModified(now - 8L * 24 * 60 * 60 * 1000)
        for (seq in 1..2) File(File(recorder.directory, s1), "$seq.agsr").setLastModified(now - 1000L * (3 - seq))
        File(File(recorder.directory, s2), "0.agsr").setLastModified(now - 10_000)
        // s2 is sent: s1's go first even though s2's is older
        recorder.sweep(now, sending = setOf(s2))
        assertEquals(emptyList(), recorder.segments(s1).filter { it == 0 })
        assertEquals(listOf(0), recorder.segments(s2))
        assertTrue(recorder.totalBytes() <= 3000)
    }

    // ---- What is sent

    @Test
    fun bufferKeepsOnlyTheLastMinute() {
        val sessions = launch()
        assertEquals(SessionReplayMode.BUFFER, sessions.replayMode)
        film(sessions, 181)
        sessions.flush()
        assertTrue(replaySink.uploads.isEmpty())
        assertEquals(listOf(2), sessions.replay!!.segments(s1))
    }

    @Test
    fun aPriorityConditionSendsTheBufferAndWhatFollows() {
        val sessions = launch()
        film(sessions, 181)
        rage(sessions)
        assertTrue(events().any { it is SessionEvent.Rage })
        sessions.flush()
        assertEquals(listOf(2), replaySink.uploads.map { it.seq })
        film(sessions, 60)
        sessions.enterBackground()
        assertEquals(listOf(2, 3, 4), replaySink.uploads.map { it.seq })
        assertTrue(replaySink.uploads.all { it.uploadToken == token && it.installId == "install-0001" })
    }

    @Test
    fun aBufferedSessionThatEndsWithoutPriorityLeavesNothing() {
        val sessions = launch()
        sessions.observe("onboarding_completed")
        film(sessions, 61)
        sessions.enterBackground()
        clock.advance(SessionRules.BACKGROUND_TIMEOUT_MS)
        sessions.enterForeground()
        assertEquals(s2, sessions.currentSessionId)
        assertEquals(emptyList(), sessions.replay!!.segments(s1))
        assertTrue(replaySink.uploads.isEmpty())
        assertNull(sessions.uploadToken(s1))
    }

    @Test
    fun theUploadTokenIsKeptAcrossALaunchAndSendsWhatWasLeft() {
        val first = launch(SessionSinkReply.Accepted(SessionReplayMode.RECORD, uploadToken = token))
        assertEquals(token, first.uploadToken(s1))
        replaySink.failing = true
        film(first, 61)
        first.enterBackground()
        assertTrue(replaySink.uploads.isEmpty())

        replaySink.failing = false
        val second = make().also { it.start() }
        assertEquals(token, second.uploadToken(s1))
        assertEquals(listOf(s1 to 0, s1 to 1), replaySink.uploads.map { it.sessionId to it.seq })
        assertTrue(replaySink.uploads.all { it.uploadToken == token })
    }

    @Test
    fun aSessionThatCrashedSendsItsBufferAtTheNextLaunch() {
        launch().also { film(it, 61) }
        assertTrue(replaySink.uploads.isEmpty())
        make().start(SessionExitInfo(exitReason = "REASON_CRASH", androidApi = 34))
        assertEquals(listOf(s1 to 0), replaySink.uploads.map { it.sessionId to it.seq })
    }

    @Test
    fun forbiddenRemovesEverySegmentOfTheSession() {
        val sessions = launch(SessionSinkReply.Accepted(SessionReplayMode.RECORD, uploadToken = token))
        film(sessions, 181)
        replaySink.enqueue(SessionReplayUploadReply.FORBIDDEN)
        sessions.flush()
        assertEquals(1, replaySink.uploads.size)
        assertEquals(emptyList(), sessions.replay!!.segments(s1))
        assertEquals(SessionReplayMode.NONE, sessions.replayMode)
        assertFalse(sessions.replayActive)
    }

    @Test
    fun rejectedRemovesThatSegmentAndAThrowKeepsIt() {
        val sessions = launch(SessionSinkReply.Accepted(SessionReplayMode.RECORD, uploadToken = token))
        film(sessions, 121)
        replaySink.failing = true
        sessions.flush()
        assertEquals(listOf(0, 1), sessions.replay!!.segments(s1))
        replaySink.failing = false
        replaySink.enqueue(SessionReplayUploadReply.REJECTED)
        sessions.flush()
        assertEquals(listOf(0, 1), replaySink.uploads.map { it.seq })
        assertEquals(emptyList(), sessions.replay!!.segments(s1))
    }

    @Test
    fun nothingIsSentBeforeTheFirstAnswer() {
        sink.failing = true
        val sessions = make().also { it.start() }
        assertEquals(SessionReplayMode.BUFFER, sessions.replayMode)
        film(sessions, 61)
        rage(sessions)
        sessions.flush()
        assertTrue(replaySink.uploads.isEmpty())
        sink.failing = false
        sink.enqueue(SessionSinkReply.Accepted(SessionReplayMode.BUFFER, uploadToken = token))
        sessions.flush()
        assertEquals(listOf(0), replaySink.uploads.map { it.seq })
    }

    @Test
    fun aMeteredNetworkSendsOnlyPrioritySessionsUpTo3MBADay() {
        val sessions = launch(SessionSinkReply.Accepted(SessionReplayMode.RECORD, uploadToken = token))
        sessions.network = SessionNetwork { SessionNetworkKind.METERED }
        film(sessions, 5, size = 800 * 1024)
        sessions.flush()
        assertTrue(replaySink.uploads.isEmpty())

        rage(sessions)
        sessions.flush()
        // One 800 KB image per segment: three fit in 3 MB, the fourth waits for tomorrow or Wi-Fi
        assertEquals(3, replaySink.uploads.size)
        assertTrue(replaySink.uploads.sumOf { it.bytes.size } <= 3 * 1024 * 1024)

        sessions.network = SessionNetwork.UNMETERED
        sessions.flush()
        assertEquals(4, replaySink.uploads.size)
    }

    @Test
    fun noNetworkSendsNothing() {
        val sessions = launch(SessionSinkReply.Accepted(SessionReplayMode.RECORD, uploadToken = token))
        sessions.network = SessionNetwork { SessionNetworkKind.NONE }
        film(sessions, 61)
        sessions.flush()
        assertTrue(replaySink.uploads.isEmpty())
    }

    @Test
    fun noneTakesNoFramesForTheRestOfTheSession() {
        val sessions = launch(SessionSinkReply.Accepted(SessionReplayMode.NONE, uploadToken = token))
        assertEquals(SessionReplayMode.NONE, sessions.replayMode)
        assertFalse(sessions.replayActive)
        assertEquals(SessionReplayStopReason.DISABLED, sessions.replayStopped)
        frame(sessions)
        assertEquals(0, sessions.replay!!.openFrames)

        sink.enqueue(SessionSinkReply.Accepted(SessionReplayMode.BUFFER))
        sessions.observe("book_added")
        sessions.flush()
        assertEquals(SessionReplayMode.NONE, sessions.replayMode)
    }

    // ---- What the batches say

    @Test
    fun replayStatsGoWithTheNextBatch() {
        val sessions = launch()
        frame(sessions, "a")
        clock.advance(1000)
        frame(sessions, "a")
        sessions.replayBlank(393, 852, SessionBlankReason.SECURE)
        sessions.replayBlank(393, 852, SessionBlankReason.MASKING_FAILED)
        sessions.replayBlank(393, 852, SessionBlankReason.NO_CAPTURE)
        sessions.flush()
        val stats = events().filterIsInstance<SessionEvent.ReplayStats>().single()
        assertEquals(1, stats.captured)
        assertEquals(SessionEvent.ReplayStats.Dropped(same = 1, maskingFailed = 1, noCapture = 1, secure = 1), stats.dropped)
        assertEquals(2.0, stats.mainMsP50)
        assertEquals(2.0, stats.mainMsP95)
        assertNull(stats.stopped)
        assertEquals(stats, SessionEvent.fromJson(SessionJson.parse(SessionJson.encode(stats.toJson())).asMap()))

        sessions.flush()
        assertEquals(1, events().filterIsInstance<SessionEvent.ReplayStats>().size)
    }

    @Test
    fun framesThatWeighOnTheMainThreadStopTheRecording() {
        val sessions = launch()
        repeat(10) {
            frame(sessions, mainMs = 40.0)
            clock.advance(1000)
        }
        assertEquals(SessionReplayStopReason.DEGRADED, sessions.replayStopped)
        assertFalse(sessions.replayActive)
        sessions.flush()
        assertEquals(SessionReplayStopReason.DEGRADED, events().filterIsInstance<SessionEvent.ReplayStats>().single().stopped)
    }

    @Test
    fun aSessionRecordsThirtyMinutesAtMost() {
        val sessions = launch()
        frame(sessions)
        clock.advance(30L * 60 * 1000)
        frame(sessions)
        assertEquals(SessionReplayStopReason.SESSION_LIMIT, sessions.replayStopped)
    }

    @Test
    fun lowPowerPausesTheRecording() {
        val sessions = launch()
        sessions.replayPaused(SessionReplayStopReason.LOW_POWER)
        assertFalse(sessions.replayActive)
        sessions.replayPaused(null)
        assertTrue(sessions.replayActive)
        sessions.flush()
        assertEquals(SessionReplayStopReason.LOW_POWER, events().filterIsInstance<SessionEvent.ReplayStats>().single().stopped)
    }

    @Test
    fun aTapThatChangedNothingIsDead() {
        val sessions = launch()
        sessions.screen("home")
        frame(sessions, "still")
        assertTrue(sessions.isCapturing)
        sessions.tap(100.0, 200.0)
        clock.advance(500)
        frame(sessions, "still")
        clock.advance(1100)
        frame(sessions, "still")
        sessions.flush()
        val dead = events().filterIsInstance<SessionEvent.Dead>().single()
        assertEquals(0L, dead.t)
        assertEquals("home", dead.screen)
        assertEquals(SessionRules.normalizeTap(100.0, 200.0, SessionRect(0.0, 0.0, 393.0, 852.0)).x, dead.x)
    }

    @Test
    fun aTapThatChangedTheFrameIsNotDead() {
        val sessions = launch()
        sessions.screen("home")
        frame(sessions, "before")
        sessions.tap(100.0, 200.0)
        clock.advance(500)
        frame(sessions, "after")
        clock.advance(1100)
        sessions.tick()
        sessions.flush()
        assertTrue(events().none { it is SessionEvent.Dead })
    }

    @Test
    fun withoutFramesNoTapIsDead() {
        val sessions = launch()
        assertFalse(sessions.isCapturing)
        sessions.tap(100.0, 200.0)
        clock.advance(2000)
        sessions.tick()
        sessions.flush()
        assertTrue(events().none { it is SessionEvent.Dead })
    }

    @Test
    fun theReplayStateSurvivesJson() {
        val sessions = launch(SessionSinkReply.Accepted(SessionReplayMode.RECORD, uploadToken = token))
        rage(sessions)
        val stored = store.stored!!
        assertEquals(SessionReplayRecord(s1, stored.open!!.device.startedAt, "install-0001", SessionReplayMode.RECORD, true, token, true), stored.replays.single())
        assertTrue(stored.open!!.priorityEvents.any { it is SessionEvent.Rage })
        assertEquals(stored, SessionStoredState.fromJson(SessionJson.parse(SessionJson.encode(stored.toJson())).asMap()))
    }

    // ---- Masking

    @Test
    fun theOnlyMaskModeIsMarked() {
        assertEquals(SessionMaskMode.MARKED, SessionMaskMode.of("marked"))
        assertEquals(listOf(SessionMaskMode.MARKED), SessionMaskMode.entries.toList())
        assertFailsWith<NoSuchElementException> { SessionMaskMode.of("inputs") }
        assertFailsWith<NoSuchElementException> { SessionMaskMode.of("text_and_images") }
    }

    // ---- Live

    @Test
    fun liveSendsHelloEventsAndFramesUntilStop() {
        val transport = RecordingLiveTransport()
        val offer = SessionLiveOffer("wss://live.example/v1/sessions/live", token, mapOf("X-Session-Install" to "install-0001"))
        val sessions = launch(SessionSinkReply.Accepted(SessionReplayMode.BUFFER, uploadToken = token, live = offer), transport)
        assertTrue(sessions.liveOpen)
        assertEquals(offer, transport.offers.single())
        val hello = SessionJson.parse(transport.texts.first()).asMap()
        assertEquals(mapOf("type" to "hello", "session_id" to s1, "screen" to "unknown", "w" to 393L, "h" to 852L), hello)
        assertEquals(500L, sessions.replayFrameIntervalMs)

        sessions.screen("home")
        assertEquals("event", SessionJson.parse(transport.texts.last()).asMap()["type"])

        frame(sessions, "a", size = 12)
        val frame = SessionJson.parse(transport.texts.last()).asMap()
        assertEquals("frame", frame["type"])
        assertEquals("image/webp", frame["mime"])
        assertEquals(12, transport.binaries.single().size)

        // A batch every 5 seconds while watched
        val before = sink.batches.size
        sessions.observe("book_added")
        clock.advance(5000)
        sessions.tick()
        assertEquals(before + 1, sink.batches.size)

        transport.receive("""{"type":"rate","max_fps":0.5}""")
        assertEquals(1000L, sessions.replayFrameIntervalMs)
        transport.receive("""{"type":"stop","reason":"no_watcher"}""")
        assertFalse(sessions.liveOpen)
        assertTrue(transport.closed)
    }

    @Test
    fun liveClosesWhenTheAppGoesToTheBack() {
        val transport = RecordingLiveTransport()
        val sessions = launch(SessionSinkReply.Accepted(SessionReplayMode.BUFFER, live = SessionLiveOffer("wss://live.example", token)), transport)
        assertTrue(sessions.liveOpen)
        sessions.enterBackground()
        assertFalse(sessions.liveOpen)
        assertTrue(transport.closed)
    }
}
