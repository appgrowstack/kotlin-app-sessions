package com.appgrowstack.sessions

import java.io.File
import java.security.SecureRandom
import java.util.UUID

// ---- Clock and ids

public fun interface SessionClock {
    /** Milliseconds since 1970. */
    public fun nowMs(): Long
}

public object SystemSessionClock : SessionClock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

public interface SessionIdGenerator {
    /** `ses_` and 20 letters and digits. */
    public fun makeSessionId(): String

    /** A lowercase UUID, for a device that has no install id from the app. */
    public fun makeInstallId(): String
}

public object RandomSessionIds : SessionIdGenerator {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private val random = SecureRandom()

    override fun makeSessionId(): String = "ses_" + (1..20).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
    override fun makeInstallId(): String = UUID.randomUUID().toString().lowercase()
}

// ---- What the next launch reads

/** The session that is still open, kept so the next launch can end it and send what was left. */
public data class OpenSessionRecord(
    val device: SessionDevice,
    /** The `seq` the next batch of this session gets. */
    val nextSeq: Int,
    val lastKind: String,
    val lastT: Long,
    /** Events not yet put into a batch. */
    val pending: List<SessionEvent>,
    /** The current screen's scroll depths, sent when it is left (or when the session is ended at the next launch). */
    val scrolls: List<SessionEvent.Scroll> = emptyList(),
)

/** The last session that ended, kept to send its `end` again when a late crash report comes. */
public data class ClosedSessionRecord(
    val device: SessionDevice,
    val nextSeq: Int,
    val endT: Long,
    val reason: SessionEndReason,
    val lastKind: String?,
    val runningFlag: Boolean,
)

public data class SessionStoredState(
    val installId: String? = null,
    /** A session has started on this install before (`first` is false from then on). */
    val hadSession: Boolean = false,
    /** Written when the app comes to the front, cleared when it goes to the back. */
    val running: Boolean = false,
    /** Android before API 30: the uncaught exception handler wrote that the app crashed. */
    val crashRecorded: Boolean = false,
    val open: OpenSessionRecord? = null,
    val lastClosed: ClosedSessionRecord? = null,
    /** Batches made but not taken by the sink yet, oldest first. */
    val outbox: List<SessionBatch> = emptyList(),
) {
    public fun toJson(): Map<String, Any?> = linkedMapOf(
        "install_id" to installId,
        "had_session" to hadSession,
        "running" to running,
        "crash_recorded" to crashRecorded,
        "open" to open?.let {
            mapOf(
                "device" to it.device.toJson(), "next_seq" to it.nextSeq, "last_kind" to it.lastKind, "last_t" to it.lastT,
                "pending" to it.pending.map(SessionEvent::toJson), "scrolls" to it.scrolls.map(SessionEvent::toJson),
            )
        },
        "last_closed" to lastClosed?.let {
            mapOf(
                "device" to it.device.toJson(), "next_seq" to it.nextSeq, "end_t" to it.endT, "reason" to it.reason.wire,
                "last_kind" to it.lastKind, "running_flag" to it.runningFlag,
            )
        },
        "outbox" to outbox.map(SessionBatch::toJson),
    )

    public companion object {
        public fun fromJson(json: Map<String, Any?>): SessionStoredState = SessionStoredState(
            installId = json["install_id"] as String?,
            hadSession = json["had_session"] == true,
            running = json["running"] == true,
            crashRecorded = json["crash_recorded"] == true,
            open = (json["open"] as Map<*, *>?)?.let {
                val m = it.asMap()
                OpenSessionRecord(
                    SessionDevice.fromJson(m["device"].asMap()), m["next_seq"].asInt(), m["last_kind"] as String, m["last_t"].asLong(),
                    m["pending"].asList().map { e -> SessionEvent.fromJson(e.asMap()) },
                    (m["scrolls"] as List<*>?)?.map { e -> SessionEvent.fromJson(e.asMap()) as SessionEvent.Scroll } ?: emptyList(),
                )
            },
            lastClosed = (json["last_closed"] as Map<*, *>?)?.let {
                val m = it.asMap()
                ClosedSessionRecord(
                    SessionDevice.fromJson(m["device"].asMap()), m["next_seq"].asInt(), m["end_t"].asLong(),
                    SessionEndReason.of(m["reason"] as String), m["last_kind"] as String?, m["running_flag"] == true,
                )
            },
            outbox = (json["outbox"] as List<*>?)?.map { SessionBatch.fromJson(it.asMap()) } ?: emptyList(),
        )
    }
}

/**
 * Where the recorder keeps its state between launches. `setHangMarker` is called from the hang
 * monitor's thread while the main thread is stuck, so a session killed in a hang still ends as `hung`.
 */
public interface SessionStateStore {
    public fun load(): SessionStoredState?
    public fun save(state: SessionStoredState)
    public fun setHangMarker(on: Boolean)
    public fun hangMarker(): Boolean
}

/**
 * `<directory>/sessions/state.json` and the hang marker `<directory>/sessions/hang`. On Android pass
 * `context.noBackupFilesDir`: the state holds the install id and the open session, which must not come back on
 * another device from the automatic backup.
 */
public class FileSessionStateStore(directory: File) : SessionStateStore {
    public val directory: File = File(directory, "sessions")
    private val file = File(this.directory, "state.json")
    private val hang = File(this.directory, "hang")

    override fun load(): SessionStoredState? = try {
        if (file.exists()) SessionStoredState.fromJson(SessionJson.parse(file.readText()).asMap()) else null
    } catch (_: Exception) {
        null
    }

    @Synchronized
    override fun save(state: SessionStoredState) {
        directory.mkdirs()
        val temporary = File(directory, "state.json.tmp")
        temporary.writeText(SessionJson.encode(state.toJson()))
        if (!temporary.renameTo(file)) {
            file.delete()
            temporary.renameTo(file)
        }
    }

    override fun setHangMarker(on: Boolean) {
        if (on) {
            directory.mkdirs()
            hang.writeText("")
        } else {
            hang.delete()
        }
    }

    override fun hangMarker(): Boolean = hang.exists()
}

// ---- Exit information

/** What the platform knows at launch about how the previous run ended. */
public data class SessionExitInfo(
    /** iOS: MetricKit already has a crash diagnostic for the previous run. */
    val metricKitCrash: Boolean = false,
    /** Android 30+: the newest `ApplicationExitInfo` reason (`REASON_CRASH` …). */
    val exitReason: String? = null,
    /** Android: the API level. Null means 30 or later. */
    val androidApi: Int? = null,
) {
    public companion object {
        public val NONE: SessionExitInfo = SessionExitInfo()
    }
}
