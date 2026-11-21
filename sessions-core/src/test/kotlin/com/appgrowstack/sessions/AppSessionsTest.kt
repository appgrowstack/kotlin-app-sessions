package com.appgrowstack.sessions

import com.appgrowstack.sessions.testing.DirectExecutor
import com.appgrowstack.sessions.testing.FakeSessionClock
import com.appgrowstack.sessions.testing.InMemorySessionStateStore
import com.appgrowstack.sessions.testing.RecordingSessionSink
import com.appgrowstack.sessions.testing.SequenceSessionIds
import com.appgrowstack.sessions.testing.test
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppSessionsTest {
    private val clock = FakeSessionClock()
    private val store = InMemorySessionStateStore()
    private val sink = RecordingSessionSink()
    private val ids = SequenceSessionIds()
    private val s1 = "ses_test0000000000000001"
    private val s2 = "ses_test0000000000000002"

    private fun make(platform: SessionPlatform = SessionPlatform.ANDROID, configuration: AppSessions.Configuration = AppSessions.Configuration(keepProps = mapOf("memo_created" to listOf("view")))) =
        AppSessions(sink, { SessionDeviceInfo.test(platform = platform) }, store, clock, ids, configuration, DirectExecutor)

    private fun launch(platform: SessionPlatform = SessionPlatform.ANDROID, exit: SessionExitInfo = SessionExitInfo.NONE) =
        make(platform).also { it.start(exit) }

    private fun events(session: String? = null) = sink.events.filter { session == null || it.first == session }.map { it.second }
    private fun kinds() = events().map { it.kind }

    private fun screen(t: Long, name: String, via: SessionScreenVia) = SessionEvent.Screen(t, name, via, 393.0, 852.0, SessionOrientation.PORTRAIT)

    // ---- Sessions

    @Test
    fun firstLaunchStartsTheFirstSession() {
        launch()
        val batch = sink.batches.single()
        assertEquals(0, batch.seq)
        assertEquals(s1, batch.session.sessionId)
        assertEquals("install-0001", batch.session.installId)
        assertEquals(clock.nowMs(), batch.session.startedAt)
        assertEquals(listOf<SessionEvent>(SessionEvent.Start(0, SessionEndReason.UNKNOWN, null, true)), batch.events)
        assertEquals(true, store.stored?.running)
    }

    @Test
    fun shortBackgroundKeepsTheSession() {
        val sessions = launch()
        sessions.screen("bookshelf")
        clock.advance(60_000)
        sessions.enterBackground()
        clock.advance(1_799_999)
        sessions.enterForeground()
        sessions.flush()
        assertEquals(listOf("start", "screen", "background", "foreground"), kinds())
        assertEquals(s1, sessions.currentSessionId)
    }

    @Test
    fun thirtyMinutesInTheBackgroundStartsANewSessionOnTheSameScreen() {
        val sessions = launch()
        sessions.screen("bookshelf")
        clock.advance(60_000)
        sessions.enterBackground()
        clock.advance(1_800_000)
        sessions.enterForeground()
        assertEquals(SessionEvent.End(60_000, SessionEndReason.BACKGROUND_TIMEOUT), events(s1).last())
        assertEquals(SessionEvent.Start(0, SessionEndReason.BACKGROUND_TIMEOUT, s1, false), events(s2).first())
        assertEquals(screen(0, "bookshelf", SessionScreenVia.UNKNOWN), events(s2)[1])
    }

    @Test
    fun relaunchAfterBackgroundEndsAsBackgroundTimeout() {
        val first = launch()
        first.screen("bookshelf")
        clock.advance(5000)
        first.enterBackground()
        clock.advance(3_600_000)
        launch()
        assertTrue(SessionEvent.End(5000, SessionEndReason.BACKGROUND_TIMEOUT) in events())
        assertEquals(SessionEvent.Start(0, SessionEndReason.BACKGROUND_TIMEOUT, s1, false), events().last())
    }

    @Test
    fun relaunchWhileRunningEndsAsKilledWithWhatWasLeft() {
        val first = launch()
        sink.failing = true
        first.screen("bookshelf")
        clock.advance(2000)
        first.tap(100.0, 100.0)
        first.tick()
        sink.failing = false
        launch()
        val batches = sink.batches.filter { it.session.sessionId == s1 }
        assertEquals(listOf(0, 1), batches.map { it.seq })
        assertEquals(listOf("screen", "tap", "end"), batches[1].events.map { it.kind })
        assertEquals(SessionEvent.End(2000, SessionEndReason.KILLED), batches[1].events.last())
    }

    @Test
    fun hangMarkerLeftByAKilledHangEndsAsHung() {
        launch()
        store.setHangMarker(true)
        launch()
        assertTrue(SessionEvent.End(0, SessionEndReason.HUNG) in events())
        assertFalse(store.hangMarker())
    }

    @Test
    fun exitInfoDecidesTheEnd() {
        launch()
        launch(exit = SessionExitInfo(exitReason = "REASON_CRASH"))
        launch(exit = SessionExitInfo(exitReason = "REASON_ANR"))
        launch(exit = SessionExitInfo(exitReason = "REASON_USER_REQUESTED"))
        assertEquals(
            listOf(SessionEndReason.CRASHED, SessionEndReason.HUNG, SessionEndReason.USER_CLOSED),
            events().filterIsInstance<SessionEvent.End>().map { it.reason },
        )
    }

    @Test
    fun uncaughtCrashBeforeApi30EndsAsCrashed() {
        launch().recordUncaughtCrash()
        launch(exit = SessionExitInfo(androidApi = 28))
        assertTrue(SessionEvent.End(0, SessionEndReason.CRASHED) in events())
        assertEquals(false, store.stored?.crashRecorded)
    }

    @Test
    fun lateCrashReportSendsTheEndAgain() {
        launch(SessionPlatform.IOS)
        val second = launch(SessionPlatform.IOS)
        second.previousSessionCrashReported()
        val previous = sink.batches.filter { it.session.sessionId == s1 }
        assertEquals(listOf(SessionEvent.End(0, SessionEndReason.KILLED), SessionEvent.End(0, SessionEndReason.CRASHED)),
            previous.flatMap { it.events }.filterIsInstance<SessionEvent.End>())
        assertEquals(listOf(0, 1, 2), previous.map { it.seq })
        second.previousSessionCrashReported()
        assertEquals(3, sink.batches.count { it.session.sessionId == s1 })
    }

    // ---- Screens and taps

    @Test
    fun screensInferPushPopAndQuickBack() {
        val sessions = launch()
        sessions.observe("screen_viewed", mapOf("screen" to SessionValue.Text("bookshelf")))
        clock.advance(5000)
        sessions.observe("screen_viewed", mapOf("screen" to SessionValue.Text("book_detail")))
        clock.advance(1500)
        sessions.noteBack(SessionBackVia.SYSTEM)
        sessions.observe("screen_viewed", mapOf("screen" to SessionValue.Text("bookshelf")))
        sessions.flush()
        assertEquals(
            listOf(
                screen(0, "bookshelf", SessionScreenVia.LAUNCH),
                screen(5000, "book_detail", SessionScreenVia.PUSH),
                SessionEvent.Back(6500, "book_detail", SessionBackVia.SYSTEM),
                SessionEvent.QuickBack(6500, "book_detail", 1500),
                screen(6500, "bookshelf", SessionScreenVia.POP),
            ),
            events().filter { it.kind in setOf("screen", "back", "quick_back") },
        )
    }

    @Test
    fun tabAndSheetAreNotInferred() {
        val sessions = launch()
        sessions.screen("bookshelf")
        sessions.screen("settings", SessionRules.NavigationOp.TAB)
        sessions.screen("bookshelf", SessionRules.NavigationOp.TAB)
        sessions.flush()
        assertEquals(listOf("start", "screen", "screen", "screen"), kinds())
    }

    @Test
    fun tapsAreNormalizedAndRageIsFound() {
        val sessions = launch()
        sessions.screen("book_detail")
        repeat(4) {
            clock.advance(200)
            sessions.tap(300.0, 700.0, target = "save_button")
        }
        clock.advance(1500)
        sessions.tick()
        sessions.flush()
        assertTrue(SessionEvent.Tap(200, 7634, 8216, "book_detail", "save_button") in events())
        assertEquals(listOf<SessionEvent>(SessionEvent.Rage(600, 7634, 8216, 4, "book_detail")), events().filter { it.kind == "rage" })
        assertTrue(events().none { it.kind == "dead" })
    }

    @Test
    fun tapsInASplitWindowAreMeasuredAgainstTheWindow() {
        val sessions = launch()
        sessions.screen("bookshelf")
        sessions.tap(1027.0, 512.0, SessionRect(688.0, 0.0, 678.0, 1024.0))
        sessions.flush()
        assertTrue(SessionEvent.Tap(0, 5000, 5000, "bookshelf", null) in events())
    }

    @Test
    fun scrollDepthIsSentWhenTheScreenIsLeft() {
        val sessions = launch()
        sessions.screen("bookshelf")
        sessions.scroll(SessionRules.ScrollSample(0.0, 700.0, 2400.0))
        sessions.scroll(SessionRules.ScrollSample(100.0, 700.0, 2400.0))
        sessions.scrollRow(12, "books")
        sessions.scrollRow(3, "books")
        clock.advance(4000)
        sessions.screen("book_detail")
        sessions.flush()
        assertEquals(
            listOf<SessionEvent>(SessionEvent.Scroll(4000, "bookshelf", null, 3333, null), SessionEvent.Scroll(4000, "bookshelf", "books", 0, 12)),
            events().filter { it.kind == "scroll" },
        )
    }

    @Test
    fun scrollDepthOfTheLastScreenIsSentAtTheNextLaunch() {
        val first = launch()
        first.screen("bookshelf")
        first.scroll(SessionRules.ScrollSample(0.0, 700.0, 600.0))
        first.enterBackground()
        launch()
        assertTrue(SessionEvent.Scroll(0, "bookshelf", null, 10000, null) in events())
    }

    // ---- Events and marks

    @Test
    fun appEventsKeepOnlyListedPropsAndMakeMarks() {
        val sessions = launch()
        sessions.observe("memo_created", mapOf("view" to SessionValue.Text("list"), "length" to SessionValue.Integer(120)))
        sessions.observe("book_added", mapOf("isbn" to SessionValue.Text("9784101010014")))
        sessions.observe("paywall_shown", mapOf("source" to SessionValue.Text("ai_summary"), "pw_id" to SessionValue.Text("ab12cd34")))
        sessions.observe("campaign_shown", mapOf("campaign" to SessionValue.Text("trial-2nd"), "side" to SessionValue.Text("treatment")))
        sessions.observe("onboarding_completed")
        sessions.mark(SessionMarkKind.TICKET, "tkt_01HZX3K9Q2")
        sessions.observe("screen_viewed", mapOf("screen" to SessionValue.Text("book_note"), "title" to SessionValue.Text("あ".repeat(49))))
        sessions.flush()
        assertEquals(
            listOf(
                SessionEvent.App(0, "memo_created", mapOf("view" to SessionValue.Text("list")), false),
                SessionEvent.App(0, "book_added", emptyMap(), false),
                SessionEvent.App(0, "paywall_shown", mapOf("pw_id" to SessionValue.Text("ab12cd34"), "source" to SessionValue.Text("ai_summary")), false),
                SessionEvent.Mark(0, SessionMarkKind.PAYWALL_SHOWN, "ai_summary", null),
                SessionEvent.App(0, "campaign_shown", mapOf("campaign" to SessionValue.Text("trial-2nd"), "side" to SessionValue.Text("treatment")), false),
                SessionEvent.Mark(0, SessionMarkKind.CAMPAIGN_SHOWN, "trial-2nd", "treatment"),
                SessionEvent.App(0, "onboarding_completed", emptyMap(), false),
                SessionEvent.Mark(0, SessionMarkKind.ONBOARDING_STEP, "completed", null),
                SessionEvent.Mark(0, SessionMarkKind.TICKET, "tkt_01HZX3K9Q2", null),
                SessionEvent.App(0, "screen_viewed", mapOf("screen" to SessionValue.Text("book_note")), true),
            ),
            events().filter { it.kind == "event" || it.kind == "mark" },
        )
    }

    @Test
    fun keepPropsFromTheSinkReplaceTheConfigured() {
        sink.enqueue(SessionSinkReply.Accepted(SessionReplayMode.RECORD, mapOf("book_added" to listOf("via"))))
        val sessions = launch()
        assertEquals(SessionReplayMode.RECORD, sessions.replayMode)
        sessions.observe("book_added", mapOf("via" to SessionValue.Text("search"), "isbn" to SessionValue.Text("1")))
        sessions.flush()
        assertEquals(SessionEvent.App(0, "book_added", mapOf("via" to SessionValue.Text("search")), false), events().last())
    }

    @Test
    fun hangIsRecordedAndClearsTheMarker() {
        val sessions = launch()
        clock.advance(10_000)
        store.setHangMarker(true)
        sessions.hang(clock.nowMs() - 3000, 2600)
        sessions.flush()
        assertTrue(SessionEvent.Hang(7000, 2600) in events())
        assertFalse(store.hangMarker())
    }

    // ---- Sending

    @Test
    fun batchesKeepTheirSeqWhenSentAgain() {
        sink.failing = true
        val sessions = launch()
        sessions.screen("bookshelf")
        sessions.flush()
        assertEquals(2, sessions.waitingBatches)
        sink.failing = false
        sessions.flush()
        assertEquals(listOf(0, 1), sink.batches.map { it.seq })
        assertEquals(0, sessions.waitingBatches)
    }

    @Test
    fun aBatchHoldsAtMost500Events() {
        val sessions = launch()
        repeat(600) { sessions.observe("book_added", mapOf("n" to SessionValue.Integer(it.toLong()))) }
        sessions.flush()
        assertEquals(listOf(1, 500, 100), sink.batches.map { it.events.size })
    }

    @Test
    fun disabledDropsTheSession() {
        sink.enqueue(SessionSinkReply.Disabled)
        val sessions = launch()
        sessions.screen("bookshelf")
        sessions.flush()
        assertEquals(1, sink.batches.size)
        assertEquals(0, sessions.waitingBatches)
    }

    @Test
    fun tickSendsOnlyWhenDue() {
        val sessions = launch()
        sessions.screen("bookshelf")
        clock.advance(10_000)
        sessions.tick()
        assertEquals(1, sink.batches.size)
        clock.advance(20_000)
        sessions.tick()
        assertEquals(2, sink.batches.size)
    }

    @Test
    fun batchJsonMatchesTheSchemaNames() {
        val device = SessionDevice(s1, 1, "install-0001", SessionDeviceInfo.test(uid = "u1"))
        val batch = SessionBatch(device, 0, listOf(
            SessionEvent.Start(0, SessionEndReason.UNKNOWN, null, true),
            SessionEvent.QuickBack(5, "a", 5),
            SessionEvent.App(6, "x", emptyMap(), false),
        ))
        val json = SessionJson.parse(batch.jsonText()).asMap()
        assertEquals(
            setOf("session_id", "started_at", "install_id", "uid", "platform", "app_version", "build", "channel", "lang", "region",
                "time_zone", "os_version", "device_model", "screen", "sdk_version"),
            json["session"].asMap().keys,
        )
        val events = json["events"].asList().map { it.asMap() }
        assertEquals(setOf("k", "t", "previous_end", "first"), events[0].keys)
        assertEquals("quick_back", events[1]["k"])
        assertEquals(setOf("k", "t", "name"), events[2].keys)
        assertEquals(batch, SessionBatch.fromJson(json))
    }

    // ---- Sinks and storage

    @Test
    fun fileSinkKeepsBatchesAndRemovesTheOldest() {
        val directory = Files.createTempDirectory("sessions-test").toFile()
        try {
            val fileSink = FileSessionSink(directory, maxBytes = 1500)
            fun batch(id: Int, startedAt: Long) = SessionBatch(
                SessionDevice("ses_test" + id.toString().padStart(16, '0'), startedAt, "install-0001", SessionDeviceInfo.test()),
                0, listOf(SessionEvent.Start(0, SessionEndReason.UNKNOWN, null, true)),
            )
            (1..4).forEach { fileSink.send(batch(it, 1000L + it)) }
            val kept = fileSink.batches()
            assertTrue(kept.size < 4)
            assertEquals(batch(4, 1004).toJson(), kept.last().toJson())
            fileSink.removeAll()
            assertTrue(fileSink.files().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun debugSinkKeepsTheLastBatches() {
        val debug = DebugSessionSink(limit = 2, log = null)
        val device = SessionDevice(s1, 1, "i-00000001", SessionDeviceInfo.test())
        repeat(3) { debug.send(SessionBatch(device, it, emptyList())) }
        assertEquals(listOf(1, 2), debug.batches.map { it.seq })
    }

    @Test
    fun fileStateStoreRoundTrips() {
        val directory = Files.createTempDirectory("sessions-state").toFile()
        try {
            val fileStore = FileSessionStateStore(directory)
            assertNull(fileStore.load())
            val sessions = AppSessions(sink, { SessionDeviceInfo.test() }, fileStore, clock, ids, sender = DirectExecutor)
            sink.failing = true
            sessions.start()
            sessions.screen("bookshelf")
            sessions.observe("paywall_shown", mapOf("source" to SessionValue.Text("limit"), "n" to SessionValue.Decimal(1.5)))
            sessions.scroll(SessionRules.ScrollSample(0.0, 700.0, 1400.0))
            sessions.enterBackground()
            val stored = fileStore.load()!!
            assertEquals(stored, SessionStoredState.fromJson(SessionJson.parse(SessionJson.encode(stored.toJson())).asMap()))
            assertEquals(listOf(0, 1), stored.outbox.map { it.seq })
            assertEquals(5000, stored.open!!.scrolls.single().maxRatio)
            fileStore.setHangMarker(true)
            assertTrue(fileStore.hangMarker())
            fileStore.setHangMarker(false)
            assertFalse(fileStore.hangMarker())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun sessionIdsHaveTheSchemaShape() {
        assertTrue(Regex("^ses_[A-Za-z0-9]{20}$").matches(RandomSessionIds.makeSessionId()))
        assertTrue(Regex("^[a-z0-9-]{8,64}$").matches(RandomSessionIds.makeInstallId()))
    }

    @Test
    fun jsonRoundTripsText() {
        val text = "\"quote\" \\ \n 改行 \u0001 🙂"
        assertEquals(text, SessionJson.parse(SessionJson.encode(text)))
        assertEquals(listOf(1L, 2.5, true, null), SessionJson.parse("[1, 2.5, true, null]"))
    }
}
