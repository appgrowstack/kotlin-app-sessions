package com.appgrowstack.sessions

import com.appgrowstack.sessions.testing.DirectExecutor
import com.appgrowstack.sessions.testing.FakeSessionClock
import com.appgrowstack.sessions.testing.InMemorySessionStateStore
import com.appgrowstack.sessions.testing.RecordingSessionSink
import com.appgrowstack.sessions.testing.SequenceSessionIds
import com.appgrowstack.sessions.testing.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureTest {
    private val sessions = AppSessions(RecordingSessionSink(), { SessionDeviceInfo.test() }, InMemorySessionStateStore(), FakeSessionClock(), SequenceSessionIds(), AppSessions.Configuration(), DirectExecutor)

    // ---- N18: a screen pushed on a marked screen

    @Test
    fun aMarkedScreenHoldsWhileShown() {
        sessions.holdNoCapture()
        assertTrue(sessions.noCaptureHeld)
    }

    @Test
    fun theHoldStaysWhenAScreenIsPushedOnTheMarkedOne() {
        // Navigation Compose takes the marked screen out of the composition; its back stack entry is no longer resumed.
        val parent = sessions.holdNoCapture()
        parent.left(ownerResumed = false)
        assertTrue(sessions.noCaptureHeld)
    }

    @Test
    fun theHoldEndsWhenTheMarkedScreenIsPopped() {
        val parent = sessions.holdNoCapture()
        parent.left(ownerResumed = false)
        parent.ownerDestroyed()
        assertFalse(sessions.noCaptureHeld)
    }

    @Test
    fun comingBackOnTopReplacesTheOldHold() {
        val parent = sessions.holdNoCapture()
        parent.left(ownerResumed = false)
        val again = sessions.holdNoCapture()
        parent.ownerResumed()
        assertTrue(parent.ended)
        assertTrue(sessions.noCaptureHeld)
        again.left(ownerResumed = true)
        assertFalse(sessions.noCaptureHeld)
    }

    @Test
    fun takenOffAScreenStillShownEndsAtOnce() {
        val mark = sessions.holdNoCapture()
        mark.left(ownerResumed = true)
        assertFalse(sessions.noCaptureHeld)
    }

    @Test
    fun resumingBeforeLeavingDoesNotEndTheHold() {
        // Adding the lifecycle observer replays ON_RESUME to it.
        val mark = sessions.holdNoCapture()
        mark.ownerResumed()
        assertTrue(sessions.noCaptureHeld)
    }

    @Test
    fun endingTwiceKeepsTheOtherHolds() {
        val a = sessions.holdNoCapture()
        sessions.holdNoCapture()
        a.ownerDestroyed()
        a.ownerDestroyed()
        assertTrue(sessions.noCaptureHeld)
    }

    // ---- The reason of a blank frame (N20: dialogs and sheets)

    @Test
    fun aPlainScreenIsTaken() {
        assertNull(SessionRules.blankReason(SessionCaptureScene()))
    }

    @Test
    fun aHoldIsNoCapture() {
        assertEquals(SessionBlankReason.NO_CAPTURE, SessionRules.blankReason(SessionCaptureScene(noCaptureHeld = true)))
    }

    @Test
    fun aDialogOrASheetOverTheWindowIsNoCapture() {
        assertEquals(SessionBlankReason.NO_CAPTURE, SessionRules.blankReason(SessionCaptureScene(overlayShown = true)))
    }

    @Test
    fun aSecureWindowIsSecure() {
        assertEquals(SessionBlankReason.SECURE, SessionRules.blankReason(SessionCaptureScene(noCaptureHeld = true, secure = true)))
    }

    @Test
    fun aScreenOfTheManifestIsNoCapture() {
        assertEquals(SessionBlankReason.NO_CAPTURE, SessionRules.blankReason(SessionCaptureScene(screen = "page_capture"), setOf("page_capture")))
        assertNull(SessionRules.blankReason(SessionCaptureScene(screen = "bookshelf"), setOf("page_capture")))
    }

    @Test
    fun reasonsAreTheSchemaValues() {
        assertEquals(listOf("no_capture", "secure", "masking_failed", "unsupported"), SessionBlankReason.entries.map { it.value })
    }
}
