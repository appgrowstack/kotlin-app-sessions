package com.appgrowstack.sessions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The tap's name from the marks under the screen ([SessionRules.tapTarget]), back to front. Same cases as Swift's TapTargetTests. */
class TapTargetTest {
    private fun rect(x: Double, y: Double, w: Double, h: Double) = SessionRect(x, y, w, h)
    private fun target(name: String, frame: SessionRect, vararg layers: String) = SessionRules.TapMark(name, frame, layers.toList())
    private fun layer(frame: SessionRect, vararg layers: String) = SessionRules.TapMark(null, frame, layers.toList())

    /** A home of rows 400 × 100, and over it in a Box a dialog layer covering the screen with an `agree` button. */
    private val home = (0 until 8).map { target("habit_row", rect(0.0, it * 100.0, 400.0, 100.0)) }
    private val dialog = listOf(layer(rect(0.0, 0.0, 400.0, 800.0), "dialog"), target("agree", rect(160.0, 370.0, 80.0, 50.0), "dialog"))

    @Test
    fun theSmallestTargetUnderTheFingerNamesTheTap() {
        val marks = listOf(target("habit_row", rect(0.0, 0.0, 400.0, 100.0)), target("habit_check", rect(340.0, 20.0, 40.0, 40.0)))
        assertEquals("habit_check", SessionRules.tapTarget(350.0, 30.0, marks))
        assertEquals("habit_row", SessionRules.tapTarget(100.0, 30.0, marks))
        assertNull(SessionRules.tapTarget(100.0, 130.0, marks))
    }

    @Test
    fun aButtonOnALayerTakesItsOwnName() {
        assertEquals("agree", SessionRules.tapTarget(200.0, 400.0, home + dialog))
    }

    // A tap on the dialog (or its dimmed backdrop) where it has no target used to take the row's name under it.
    @Test
    fun aRowUnderALayerDoesNotNameATapOnTheLayer() {
        assertNull(SessionRules.tapTarget(200.0, 600.0, home + dialog))
        assertNull(SessionRules.tapTarget(20.0, 50.0, home + dialog))
    }

    @Test
    fun outsideTheLayerTheScreenUnderItCounts() {
        val sheet = listOf(layer(rect(0.0, 500.0, 400.0, 300.0), "sheet"), target("habit_save", rect(300.0, 520.0, 80.0, 40.0), "sheet"))
        assertEquals("habit_save", SessionRules.tapTarget(340.0, 540.0, home + sheet))
        assertNull(SessionRules.tapTarget(340.0, 700.0, home + sheet))
        assertEquals("habit_row", SessionRules.tapTarget(200.0, 150.0, home + sheet))
    }

    @Test
    fun aLayerInsideALayerIsInFront() {
        val picker = listOf(layer(rect(50.0, 300.0, 300.0, 200.0), "dialog", "picker"), target("time_ok", rect(250.0, 450.0, 80.0, 40.0), "dialog", "picker"))
        val marks = home + picker + dialog
        assertEquals("time_ok", SessionRules.tapTarget(280.0, 460.0, marks))
        assertNull(SessionRules.tapTarget(200.0, 400.0, marks))
        assertNull(SessionRules.tapTarget(200.0, 700.0, marks))
    }

    @Test
    fun ofTwoLayersAsDeepTheLaterIsInFront() {
        val tabs = listOf(layer(rect(0.0, 720.0, 400.0, 80.0), "tabs"), target("tab_home", rect(0.0, 720.0, 200.0, 80.0), "tabs"))
        assertEquals("tab_home", SessionRules.tapTarget(100.0, 760.0, home + tabs))
        assertNull(SessionRules.tapTarget(100.0, 760.0, home + tabs + dialog))
    }

    @Test
    fun ofTwoTargetsAsSmallTheLaterIsInFront() {
        val marks = listOf(target("back", rect(0.0, 0.0, 100.0, 100.0)), target("front", rect(0.0, 0.0, 100.0, 100.0)))
        assertEquals("front", SessionRules.tapTarget(50.0, 50.0, marks))
    }
}
