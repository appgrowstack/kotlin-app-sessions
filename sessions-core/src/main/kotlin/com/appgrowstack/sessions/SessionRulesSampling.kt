package com.appgrowstack.sessions

import com.appgrowstack.sessions.SessionRules.Feature
import com.appgrowstack.sessions.SessionRules.PriorityReason
import com.appgrowstack.sessions.SessionRules.Sampling
import com.appgrowstack.sessions.SessionRules.SamplingInput
import com.appgrowstack.sessions.SessionRules.SessionReplayState
import com.appgrowstack.sessions.SessionRules.Upload
import kotlin.math.max

// SessionRules: Whether to send the recording, the priority conditions and the hash.

/** Whether to send the recording (sessions.md 5). */
public fun SessionRules.sampling(input: SamplingInput): Sampling {
    if (input.feature != Feature.ON) {
        return Sampling(false, null, false, upload = Upload.NONE, priority = emptyList(), previousSessionUpload = false, sessionReplay = null)
    }
    if (!input.replayEnabled) {
        return Sampling(true, SessionReplayMode.BUFFER, false, upload = Upload.NONE, priority = emptyList(),
            previousSessionUpload = false, sessionReplay = SessionReplayState.NONE)
    }
    val previous = input.events.any {
        it is SessionEvent.Start && it.previousEnd in setOf(SessionEndReason.CRASHED, SessionEndReason.HUNG, SessionEndReason.KILLED)
    }
    val hits = priorityHits(input.events)
    val priority = hits.keys.sortedBy { it.ordinal }
    if (input.internalDevice) {
        return Sampling(true, SessionReplayMode.RECORD, true, upload = Upload.ALL, priority = priority,
            previousSessionUpload = previous, sessionReplay = SessionReplayState.RECORD)
    }
    var hash: Long? = null
    if (input.recordedToday < input.dailyCap) {
        val value = hash32("${input.project}:${input.sessionId}:replay")
        hash = value
        if (value / 4_294_967_296.0 < input.sample) {
            return Sampling(true, SessionReplayMode.RECORD, true, value, Upload.ALL, null, priority, previous, SessionReplayState.RECORD)
        }
    }
    val hitT = hits.values.minOrNull()
    if (hitT != null) {
        return Sampling(true, SessionReplayMode.BUFFER, true, hash, Upload.BUFFERED, max(0, hitT - 60_000), priority, previous,
            SessionReplayState.PRIORITY)
    }
    return Sampling(true, SessionReplayMode.BUFFER, true, hash, Upload.NONE, null, emptyList(), previous, SessionReplayState.BUFFER)
}

/** The priority conditions met by the events, each with the time it was first met. */
public fun SessionRules.priorityHits(events: List<SessionEvent>): Map<PriorityReason, Long> {
    val hits = linkedMapOf<PriorityReason, Long>()
    fun hit(reason: PriorityReason, t: Long) { hits.putIfAbsent(reason, t) }
    var deadCount = 0
    var first = false
    var onboardingCompleted = false
    var campaignShownAt: Long? = null
    var paywallOpen = false
    for (event in events) {
        when (event) {
            is SessionEvent.Start -> first = event.first
            is SessionEvent.Rage -> hit(PriorityReason.RAGE, event.t)
            is SessionEvent.Dead -> { deadCount++; if (deadCount == 3) hit(PriorityReason.DEAD, event.t) }
            is SessionEvent.Hang -> hit(PriorityReason.HANG, event.t)
            is SessionEvent.Mark -> when (event.markKind) {
                SessionMarkKind.TICKET -> hit(PriorityReason.TICKET, event.t)
                SessionMarkKind.CAMPAIGN_SHOWN -> campaignShownAt = event.t
                SessionMarkKind.PAYWALL_SHOWN -> paywallOpen = true
                SessionMarkKind.PURCHASE_COMPLETED -> paywallOpen = false
                SessionMarkKind.ONBOARDING_STEP -> if (event.ref == "completed") onboardingCompleted = true
                else -> Unit
            }
            is SessionEvent.App -> if (event.name == "paywall_closed" && paywallOpen) {
                hit(PriorityReason.PAYWALL_NO_PURCHASE, event.t)
                paywallOpen = false
            }
            is SessionEvent.End -> {
                val shown = campaignShownAt
                if (shown != null && event.t - shown <= 60_000) hit(PriorityReason.CAMPAIGN_QUICK_END, event.t)
                if (paywallOpen) hit(PriorityReason.PAYWALL_NO_PURCHASE, event.t)
                if (first && !onboardingCompleted) hit(PriorityReason.ONBOARDING_UNFINISHED, event.t)
            }
            else -> Unit
        }
    }
    return hits
}

/** FNV-1a over UTF-16 code units with murmur3's final mix (the same `hash32` as the server's), as 0 … 2^32 − 1. */
public fun SessionRules.hash32(input: String): Long {
    var hash = 0x811c9dc5.toInt()
    for (unit in input) {
        hash = hash xor unit.code
        hash *= 0x01000193
    }
    hash = hash xor (hash ushr 16)
    hash *= 0x85ebca6b.toInt()
    hash = hash xor (hash ushr 13)
    hash *= 0xc2b2ae35.toInt()
    hash = hash xor (hash ushr 16)
    return hash.toLong() and 0xffffffffL
}
