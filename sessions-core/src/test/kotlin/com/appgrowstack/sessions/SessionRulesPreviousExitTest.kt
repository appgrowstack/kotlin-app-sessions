package com.appgrowstack.sessions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionRulesPreviousExitTest {
    private val start = 1_795_230_000_000L

    private fun exit(reason: String, at: Long, pid: Int, process: String? = "com.example") = SessionProcessExit(reason, at, pid, process)

    @Test
    fun anExitAfterTheSessionStartedIsItsEnd() {
        val exits = listOf(exit("REASON_CRASH", start + 5000, 200))
        assertEquals("REASON_CRASH", SessionRules.previousExit(exits, start, currentPid = 300)?.reason)
    }

    @Test
    fun anExitAtTheSameMillisecondCounts() {
        val exits = listOf(exit("REASON_ANR", start, 200))
        assertEquals("REASON_ANR", SessionRules.previousExit(exits, start, currentPid = 300)?.reason)
    }

    @Test
    fun anExitBeforeTheSessionStartedIsOfAnotherRun() {
        val exits = listOf(exit("REASON_CRASH", start - 1, 200))
        assertNull(SessionRules.previousExit(exits, start, currentPid = 300))
    }

    @Test
    fun theCurrentProcessIsNeverThePreviousSessions() {
        val exits = listOf(exit("REASON_CRASH", start + 5000, 300))
        assertNull(SessionRules.previousExit(exits, start, currentPid = 300))
    }

    @Test
    fun ofSeveralTheFirstToEndAfterTheStartIsTheSessions() {
        // The session's process ended, then a process woken by a notification ended too
        val exits = listOf(
            exit("REASON_OTHER", start + 90_000, 400),
            exit("REASON_LOW_MEMORY", start + 30_000, 200),
            exit("REASON_CRASH", start - 10_000, 100),
        )
        assertEquals("REASON_LOW_MEMORY", SessionRules.previousExit(exits, start, currentPid = 500)?.reason)
    }

    @Test
    fun anotherProcessOfTheAppIsSkippedWhenNamesAreKnown() {
        val exits = listOf(
            exit("REASON_CRASH", start + 10_000, 250, "com.example:sync"),
            exit("REASON_USER_REQUESTED", start + 20_000, 200, "com.example"),
        )
        assertEquals("REASON_USER_REQUESTED", SessionRules.previousExit(exits, start, currentPid = 300, currentProcessName = "com.example")?.reason)
    }

    @Test
    fun noExitsSayNothing() {
        assertNull(SessionRules.previousExit(emptyList(), start, currentPid = 300))
    }
}
