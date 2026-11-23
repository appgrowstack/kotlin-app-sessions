package com.appgrowstack.sessions

import com.appgrowstack.sessions.SessionReplayFrameCost.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals

/** The same cases as swift-app-sessions' `SessionReplayFrameCostTests`. */
class SessionReplayFrameCostTest {
    @Test
    fun framesUnderSixteenMsKeepOneASecond() {
        val cost = SessionReplayFrameCost()
        repeat(40) { cost.add(8.0) }
        assertEquals(Verdict.NORMAL, cost.verdict)
        assertEquals(1000L, cost.intervalMs(live = false))
    }

    @Test
    fun overSixteenMsSpacesFramesToTwoSecondsAndUnderItGoesBack() {
        val cost = SessionReplayFrameCost()
        repeat(13) { cost.add(20.0) }
        assertEquals(Verdict.SLOW, cost.verdict)
        assertEquals(2000L, cost.intervalMs(live = false))
        assertEquals(500L, cost.intervalMs(live = true))
        repeat(30) { cost.add(5.0) }
        assertEquals(Verdict.NORMAL, cost.verdict)
    }

    @Test
    fun overThirtyThreeMsStopsAndStaysStoppedUntilReset() {
        val cost = SessionReplayFrameCost()
        repeat(13) { cost.add(50.0) }
        assertEquals(Verdict.DEGRADED, cost.verdict)
        repeat(40) { cost.add(1.0) }
        assertEquals(Verdict.DEGRADED, cost.verdict)
        cost.reset()
        assertEquals(Verdict.NORMAL, cost.verdict)
    }

    @Test
    fun theFirstFramesAfterLaunchAreNotCounted() {
        val cost = SessionReplayFrameCost()
        // Three slow frames while the first screen lays out, then fast ones
        repeat(3) { cost.add(3000.0) }
        repeat(10) { cost.add(6.0) }
        assertEquals(Verdict.NORMAL, cost.verdict)
    }

    @Test
    fun resetCountsTheWarmUpAgain() {
        val cost = SessionReplayFrameCost()
        repeat(13) { cost.add(6.0) }
        cost.reset()
        repeat(3) { cost.add(3000.0) }
        repeat(10) { cost.add(6.0) }
        assertEquals(Verdict.NORMAL, cost.verdict)
    }

    @Test
    fun noStopThresholdOnlySpacesFramesOut() {
        val cost = SessionReplayFrameCost(stopP95Ms = null)
        repeat(40) { cost.add(80.0) }
        assertEquals(Verdict.SLOW, cost.verdict)
    }

    @Test
    fun fewerThanTenCountedFramesDecideNothing() {
        val cost = SessionReplayFrameCost()
        repeat(12) { cost.add(100.0) }
        assertEquals(Verdict.NORMAL, cost.verdict)
        cost.add(100.0)
        assertEquals(Verdict.DEGRADED, cost.verdict)
    }

    @Test
    fun timesThatAreNotFiniteOrBelowZeroAreIgnored() {
        val cost = SessionReplayFrameCost(warmUpFrames = 0)
        repeat(20) { cost.add(Double.NaN); cost.add(Double.POSITIVE_INFINITY); cost.add(-1.0) }
        assertEquals(Verdict.NORMAL, cost.verdict)
        repeat(9) { cost.add(100.0) }
        assertEquals(Verdict.NORMAL, cost.verdict)
        cost.add(100.0)
        assertEquals(Verdict.DEGRADED, cost.verdict)
    }
}
