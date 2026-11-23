package com.appgrowstack.sessions

public data class SessionScreen(val w: Double, val h: Double, val scale: Double)

/** What the platform knows about the device and the app. `sessions-compose` fills it on Android. */
public data class SessionDeviceInfo(
    /** The app's own install id when it has one. Without it the recorder keeps its own (a lowercase UUID). */
    val installId: String? = null,
    val uid: String? = null,
    val platform: SessionPlatform = SessionPlatform.ANDROID,
    val appVersion: String,
    val build: String,
    /** `play`, `play_test` or `debug`. */
    val channel: String,
    val lang: String,
    val region: String,
    val timeZone: String,
    val osVersion: String,
    val deviceModel: String,
    val screen: SessionScreen,
    val sdkVersion: String = SessionsSdk.VERSION,
) {
    public companion object
}

/** The device's values for one session, fixed at its start (`SessionDevice` in the batch schema). */
public data class SessionDevice(
    val sessionId: String,
    /** When the session started (milliseconds since 1970). Event times count from here. */
    val startedAt: Long,
    val installId: String,
    val info: SessionDeviceInfo,
) {
    public fun toJson(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "session_id" to sessionId,
        "started_at" to startedAt,
        "install_id" to installId,
    ).apply {
        info.uid?.let { put("uid", it) }
        put("platform", info.platform.wire)
        put("app_version", info.appVersion)
        put("build", info.build)
        put("channel", info.channel)
        put("lang", info.lang)
        put("region", info.region)
        put("time_zone", info.timeZone)
        put("os_version", info.osVersion)
        put("device_model", info.deviceModel)
        put("screen", mapOf("w" to info.screen.w, "h" to info.screen.h, "scale" to info.screen.scale))
        put("sdk_version", info.sdkVersion)
    }

    public companion object {
        public fun fromJson(json: Map<String, Any?>): SessionDevice {
            val screen = json["screen"].asMap()
            return SessionDevice(
                sessionId = json["session_id"] as String,
                startedAt = json["started_at"].asLong(),
                installId = json["install_id"] as String,
                info = SessionDeviceInfo(
                    uid = json["uid"] as String?,
                    platform = SessionPlatform.of(json["platform"] as String),
                    appVersion = json["app_version"] as String,
                    build = json["build"] as String,
                    channel = json["channel"] as String,
                    lang = json["lang"] as String,
                    region = json["region"] as String,
                    timeZone = json["time_zone"] as String,
                    osVersion = json["os_version"] as String,
                    deviceModel = json["device_model"] as String,
                    screen = SessionScreen(screen["w"].asDouble(), screen["h"].asDouble(), screen["scale"].asDouble()),
                    sdkVersion = json["sdk_version"] as String,
                ),
            )
        }
    }
}

/**
 * One batch of a session's events (`SessionBatch`). `seq` counts from 0 within the session; a
 * batch sent again keeps its `seq`, so the receiver can drop the second copy.
 */
public data class SessionBatch(val session: SessionDevice, val seq: Int, val events: List<SessionEvent>) {
    public fun toJson(): Map<String, Any?> = linkedMapOf(
        "session" to session.toJson(),
        "seq" to seq,
        "events" to events.map { it.toJson() },
    )

    /** The batch as the JSON body of the schema. */
    public fun jsonText(): String = SessionJson.encode(toJson())

    public companion object {
        public fun fromJson(json: Map<String, Any?>): SessionBatch = SessionBatch(
            SessionDevice.fromJson(json["session"].asMap()),
            json["seq"].asInt(),
            json["events"].asList().map { SessionEvent.fromJson(it.asMap()) },
        )
    }
}

/** What a sink answers for a batch. */
public sealed interface SessionSinkReply {
    /**
     * Taken. `replay` is the receiver's decision for the recording (null: no decision, stay on
     * `BUFFER`; `NONE` stays for the rest of the session). `keepProps` replaces the kept properties when
     * the receiver has newer ones. `uploadToken` (the answer to the session's first batch only) is kept
     * with the session and sent with each of its segments; it does not come again. `live`: someone is
     * watching this device.
     */
    public data class Accepted(
        val replay: SessionReplayMode? = null,
        val keepProps: Map<String, List<String>>? = null,
        val uploadToken: String? = null,
        val live: SessionLiveOffer? = null,
    ) : SessionSinkReply

    /**
     * The receiver does not take sessions (turned off or paused). The recorder drops what it holds
     * for this session and records nothing more until the next session.
     */
    public data object Disabled : SessionSinkReply
}

/**
 * Where batches go. The package has two that need nothing else: [FileSessionSink] (keeps them on
 * the device) and [DebugSessionSink] (keeps the last ones in memory to look at). `send` is called
 * on the recorder's sending thread; a throw keeps the batch to send again later.
 */
public fun interface SessionSink {
    public fun send(batch: SessionBatch): SessionSinkReply
}
