package com.appgrowstack.sessions

import com.appgrowstack.sessions.SessionRules.EndDecision
import com.appgrowstack.sessions.SessionRules.EndInput

// SessionRules: Why the previous session ended.

public fun SessionRules.endReason(input: EndInput): EndDecision {
    val reason = baseEndReason(input)
    val revised = if (input.platform == SessionPlatform.IOS && input.lateMetricKitCrash && input.runningFlag &&
        (reason == SessionEndReason.KILLED || reason == SessionEndReason.HUNG)
    ) SessionEndReason.CRASHED else null
    return EndDecision(reason, revised)
}

private fun SessionRules.baseEndReason(input: EndInput): SessionEndReason {
    if (!input.previousRecord) return SessionEndReason.UNKNOWN
    val android = input.platform == SessionPlatform.ANDROID
    val modern = (input.androidApi ?: 30) >= 30
    if (android && modern && input.exitReason == "REASON_USER_REQUESTED") return SessionEndReason.USER_CLOSED
    if (!input.runningFlag) return SessionEndReason.BACKGROUND_TIMEOUT
    val crashed = if (android) {
        if (modern) input.exitReason == "REASON_CRASH" || input.exitReason == "REASON_CRASH_NATIVE" else input.crashHandler
    } else {
        input.metricKitCrash
    }
    if (crashed) return SessionEndReason.CRASHED
    if (input.lastEvent == "hang" || (android && modern && input.exitReason == "REASON_ANR")) return SessionEndReason.HUNG
    if (android && !modern) return SessionEndReason.UNKNOWN
    return SessionEndReason.KILLED
}

/**
 * The exit of the process the previous session ran in, from the newest exits of the app's processes:
 * one that ended at or after the session started ([sessionStartedAtMs]), not the current process
 * ([currentPid]) and of the same process name when both are known. Of those, the first to end: a
 * process that started later (a notification, a widget, a job) and ended too came after the session's.
 * Null when none holds (the record is not there, or it is of another run): the reason is then unknown
 * to the exit records.
 */
public fun SessionRules.previousExit(
    exits: List<SessionProcessExit>,
    sessionStartedAtMs: Long,
    currentPid: Int?,
    currentProcessName: String? = null,
): SessionProcessExit? = exits
    .filter { it.timestampMs >= sessionStartedAtMs }
    .filter { currentPid == null || it.pid != currentPid }
    .filter { currentProcessName == null || it.processName == null || it.processName == currentProcessName }
    .minByOrNull { it.timestampMs }
