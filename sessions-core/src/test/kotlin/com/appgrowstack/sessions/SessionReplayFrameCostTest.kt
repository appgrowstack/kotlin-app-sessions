package com.appgrowstack.sessions

import com.appgrowstack.sessions.SessionReplayFrameCost.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
    fun overThirtyThreeMsSpacesFramesToFiveSecondsAndDoesNotStop() {
        val cost = SessionReplayFrameCost()
        repeat(40) { cost.add(50.0) }
        assertEquals(Verdict.VERY_SLOW, cost.verdict)
        assertEquals(5000L, cost.intervalMs(live = false))
        assertEquals(500L, cost.intervalMs(live = true))
        repeat(30) { cost.add(5.0) }
        assertEquals(Verdict.NORMAL, cost.verdict)
    }

    @Test
    fun overOneHundredMsStopsAndStaysStoppedUntilReset() {
        val cost = SessionReplayFrameCost()
        repeat(13) { cost.add(150.0) }
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
        repeat(40) { cost.add(200.0) }
        assertEquals(Verdict.VERY_SLOW, cost.verdict)
    }

    @Test
    fun fewerThanTenCountedFramesDecideNothing() {
        val cost = SessionReplayFrameCost()
        repeat(12) { cost.add(150.0) }
        assertEquals(Verdict.NORMAL, cost.verdict)
        cost.add(150.0)
        assertEquals(Verdict.DEGRADED, cost.verdict)
    }

    @Test
    fun timesThatAreNotFiniteOrBelowZeroAreIgnored() {
        val cost = SessionReplayFrameCost(warmUpFrames = 0)
        repeat(20) { cost.add(Double.NaN); cost.add(Double.POSITIVE_INFINITY); cost.add(-1.0) }
        assertEquals(Verdict.NORMAL, cost.verdict)
        repeat(9) { cost.add(150.0) }
        assertEquals(Verdict.NORMAL, cost.verdict)
        cost.add(150.0)
        assertEquals(Verdict.DEGRADED, cost.verdict)
    }

    @Test
    fun percentilesOfTheCountedFrames() {
        val cost = SessionReplayFrameCost(warmUpFrames = 0)
        assertNull(cost.percentiles)
        for (ms in 1..20) cost.add(ms.toDouble())
        assertEquals(11.0, cost.percentiles?.p50)
        assertEquals(20.0, cost.percentiles?.p95)
    }
}
