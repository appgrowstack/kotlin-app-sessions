package com.appgrowstack.sessions

import java.io.File

/**
 * Which app events become marks (`mark`), the index the receiver finds sessions by. A mark is
 * made from the event's own values before the kept props are chosen.
 */
public data class SessionMarkRules(val rules: Map<String, Rule>) {
    /** `ref` is the value of `refProp`, or `fixedRef`, or the event's name. `sideProp` is for `campaign_*` only. */
    public data class Rule(val kind: SessionMarkKind, val refProp: String? = null, val fixedRef: String? = null, val sideProp: String? = null)

    /** The mark for an event, if it makes one. */
    public fun mark(name: String, props: Map<String, SessionValue?>, t: Long): SessionEvent.Mark? {
        val rule = rules[name] ?: return null
        val fromProp = rule.refProp?.let { props[it] }?.let(::text)
        val ref = (fromProp ?: rule.fixedRef ?: name).take(80).ifEmpty { name }
        val side = rule.sideProp?.let { props[it] }?.let { (it as? SessionValue.Text)?.value }?.takeIf { it == "treatment" || it == "holdout" }
        return SessionEvent.Mark(t, rule.kind, ref, side)
    }

    public companion object {
        /**
         * The events of swift-analytics' shared catalog and of the campaign SDK. A ticket has no event
         * in either: the app calls [AppSessions.mark] when one is sent.
         */
        public val STANDARD: SessionMarkRules = SessionMarkRules(
            mapOf(
                "campaign_shown" to Rule(SessionMarkKind.CAMPAIGN_SHOWN, refProp = "campaign", sideProp = "side"),
                "campaign_eligible" to Rule(SessionMarkKind.CAMPAIGN_ELIGIBLE, refProp = "campaign", sideProp = "side"),
                "paywall_shown" to Rule(SessionMarkKind.PAYWALL_SHOWN, refProp = "source"),
                "purchase_completed" to Rule(SessionMarkKind.PURCHASE_COMPLETED, refProp = "product"),
                "onboarding_step" to Rule(SessionMarkKind.ONBOARDING_STEP, refProp = "step"),
                "onboarding_completed" to Rule(SessionMarkKind.ONBOARDING_STEP, fixedRef = "completed"),
                "notify_opened" to Rule(SessionMarkKind.NOTIFY_OPENED, refProp = "notification"),
            ),
        )

        private fun text(value: SessionValue): String = when (value) {
            is SessionValue.Text -> value.value
            is SessionValue.Integer -> value.value.toString()
            is SessionValue.Decimal -> value.value.toString()
            is SessionValue.Flag -> value.value.toString()
        }
    }
}

/**
 * Keeps every batch on the device, one JSON line per batch in a file per session
 * (`<directory>/sessions/batches/<started_at>-<session_id>.jsonl`). Past `maxBytes` the oldest
 * sessions' files are removed. Nothing leaves the device; a tester screen can share [files].
 */
public class FileSessionSink(directory: File, public val maxBytes: Long = 20L * 1024 * 1024) : SessionSink {
    public val directory: File = File(directory, "sessions/batches")

    @Synchronized
    override fun send(batch: SessionBatch): SessionSinkReply {
        directory.mkdirs()
        val file = File(directory, "${batch.session.startedAt}-${batch.session.sessionId}.jsonl")
        file.appendText(batch.jsonText() + "\n")
        trim(file)
        return SessionSinkReply.Accepted()
    }

    /** The files, oldest session first. */
    @Synchronized
    public fun files(): List<File> = directory.listFiles { f -> f.name.endsWith(".jsonl") }?.sortedBy { it.name } ?: emptyList()

    /** Every batch kept, oldest first. */
    @Synchronized
    public fun batches(): List<SessionBatch> = files().flatMap { file ->
        file.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
            runCatching { SessionBatch.fromJson(SessionJson.parse(line).asMap()) }.getOrNull()
        }
    }

    @Synchronized
    public fun removeAll() { files().forEach { it.delete() } }

    private fun trim(current: File) {
        val all = files().toMutableList()
        var total = all.sumOf { it.length() }
        while (total > maxBytes && all.isNotEmpty() && all.first() != current) {
            val oldest = all.removeAt(0)
            total -= oldest.length()
            oldest.delete()
        }
    }
}

/**
 * Keeps the last batches in memory to look at while developing (a tester screen can list
 * [batches]) and hands each to `log` as JSON text (on Android, `android.util.Log`).
 */
public class DebugSessionSink(private val limit: Int = 50, private val log: ((String) -> Unit)? = { println("[sessions] $it") }) : SessionSink {
    private val kept = ArrayDeque<SessionBatch>()

    public val batches: List<SessionBatch> get() = synchronized(kept) { kept.toList() }

    override fun send(batch: SessionBatch): SessionSinkReply {
        synchronized(kept) {
            kept.addLast(batch)
            while (kept.size > limit) kept.removeFirst()
        }
        log?.invoke(batch.jsonText())
        return SessionSinkReply.Accepted()
    }

    public fun removeAll() { synchronized(kept) { kept.clear() } }
}
