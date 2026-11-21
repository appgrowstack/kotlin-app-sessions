package com.appgrowstack.sessions

import com.appgrowstack.sessions.SessionRules.LifecycleStep
import com.appgrowstack.sessions.SessionRules.Split
import com.appgrowstack.sessions.SessionRules.SplitBy
import com.appgrowstack.sessions.SessionRules.SplitSession

// SessionRules: Splitting the app's lifecycle into sessions.

/**
 * Launch always starts a session. Coming to the foreground starts one when the app was in the
 * background for [BACKGROUND_TIMEOUT_MS] or longer. A change of date does not split.
 */
public fun SessionRules.split(steps: List<Pair<Long, LifecycleStep>>): Split {
    val sessions = mutableListOf<SplitSession>()
    val sessionOf = mutableListOf<Int>()
    var backgroundAt: Long? = null
    for ((t, k) in steps) {
        when (k) {
            LifecycleStep.LAUNCH -> {
                if (sessions.isNotEmpty()) sessions[sessions.lastIndex] = sessions.last().copy(by = SplitBy.LAUNCH)
                sessions.add(SplitSession(t))
                backgroundAt = null
            }
            LifecycleStep.BACKGROUND -> {
                if (sessions.isEmpty()) sessions.add(SplitSession(t))
                backgroundAt = t
            }
            LifecycleStep.FOREGROUND -> {
                val at = backgroundAt
                if (sessions.isEmpty()) {
                    sessions.add(SplitSession(t))
                } else if (at != null && t - at >= BACKGROUND_TIMEOUT_MS) {
                    sessions[sessions.lastIndex] = sessions.last().copy(end = at, by = SplitBy.BACKGROUND_TIMEOUT)
                    sessions.add(SplitSession(t))
                }
                backgroundAt = null
            }
        }
        sessionOf.add(sessions.lastIndex)
    }
    return Split(sessionOf, sessions)
}
