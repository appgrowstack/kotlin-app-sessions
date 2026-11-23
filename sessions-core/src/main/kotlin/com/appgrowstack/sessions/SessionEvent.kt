package com.appgrowstack.sessions

/** A value kept from an app event's properties: text, a number or a yes/no. Nothing else is kept. */
public sealed interface SessionValue {
    public data class Text(val value: String) : SessionValue
    public data class Integer(val value: Long) : SessionValue
    public data class Decimal(val value: Double) : SessionValue
    public data class Flag(val value: Boolean) : SessionValue

    public val json: Any
        get() = when (this) {
            is Text -> value
            is Integer -> value
            is Decimal -> value
            is Flag -> value
        }

    public companion object {
        /** A JSON value as a kept value; anything but text, a number or yes/no is null. */
        public fun of(value: Any?): SessionValue? = when (value) {
            is String -> Text(value)
            is Boolean -> Flag(value)
            is Int, is Long, is Short, is Byte -> Integer((value as Number).toLong())
            is Double, is Float -> Decimal((value as Number).toDouble())
            else -> null
        }
    }
}

public enum class SessionPlatform(public val wire: String) {
    IOS("ios"), ANDROID("android");

    public companion object {
        public fun of(wire: String): SessionPlatform = entries.first { it.wire == wire }
    }
}

/** How a session ended (sessions.md 3.1). */
public enum class SessionEndReason(public val wire: String) {
    BACKGROUND_TIMEOUT("background_timeout"),
    USER_CLOSED("user_closed"),
    CRASHED("crashed"),
    HUNG("hung"),
    KILLED("killed"),
    UNKNOWN("unknown");

    public companion object {
        public fun of(wire: String): SessionEndReason = entries.first { it.wire == wire }
    }
}

/** How a screen was entered. Closing a sheet back to the screen under it is `pop`. */
public enum class SessionScreenVia(public val wire: String) {
    LAUNCH("launch"), PUSH("push"), POP("pop"), TAB("tab"), SHEET("sheet"), UNKNOWN("unknown");

    public companion object {
        public fun of(wire: String): SessionScreenVia = entries.first { it.wire == wire }
    }
}

/** How the person went back: an edge swipe, a back or close button, or the back key or gesture. */
public enum class SessionBackVia(public val wire: String) {
    GESTURE("gesture"), BUTTON("button"), SYSTEM("system");

    public companion object {
        public fun of(wire: String): SessionBackVia = entries.first { it.wire == wire }
    }
}

public enum class SessionOrientation(public val wire: String) {
    PORTRAIT("portrait"), LANDSCAPE("landscape");

    public companion object {
        public fun of(wire: String): SessionOrientation = entries.first { it.wire == wire }
    }
}

/** The kinds of marks the server indexes sessions by. */
public enum class SessionMarkKind(public val wire: String) {
    CAMPAIGN_SHOWN("campaign_shown"),
    CAMPAIGN_ELIGIBLE("campaign_eligible"),
    PAYWALL_SHOWN("paywall_shown"),
    PURCHASE_COMPLETED("purchase_completed"),
    TICKET("ticket"),
    NOTIFY_OPENED("notify_opened"),
    ONBOARDING_STEP("onboarding_step");

    public companion object {
        public fun of(wire: String): SessionMarkKind = entries.first { it.wire == wire }
    }
}

/**
 * Where the sink's answer says to take the session's recording. Without an answer, `BUFFER`.
 * - `RECORD`: every segment is sent
 * - `BUFFER`: only the last 60 seconds are kept on the device; when a priority condition is met
 *   ([SessionRules.priorityHits]) they are sent, and so is what follows
 * - `NONE`: the policy turned the recording off (`replay.enabled` false). No frames are taken
 */
public enum class SessionReplayMode(public val wire: String) {
    RECORD("record"), BUFFER("buffer"), NONE("none");

    public companion object {
        public fun of(wire: String): SessionReplayMode = entries.first { it.wire == wire }
    }
}

/** Why the recording stopped (`ReplayStopReason` of `sessions/batch.schema.json`). */
public enum class SessionReplayStopReason(public val wire: String) {
    /** Battery saver is on. */
    LOW_POWER("low_power"),
    /** The device is hot (Android's thermal status `SEVERE` or worse). */
    THERMAL("thermal"),
    /** Under 20% and not charging. */
    BATTERY("battery"),
    /** Taking frames weighs on the main thread (p95 over 33 ms). Stops for the rest of the session. */
    DEGRADED("degraded"),
    /** The session reached 30 minutes or 20 MB of recording. Stops for the rest of the session. */
    SESSION_LIMIT("session_limit"),
    /** The policy turned the recording off. */
    DISABLED("disabled"),
    /** The segments kept on the device reached their limit. */
    STORAGE("storage");

    public companion object {
        public fun of(wire: String): SessionReplayStopReason = entries.first { it.wire == wire }
    }
}

/**
 * What the recording paints over (`SessionMaskMode` of the manifest). Text fields, password fields,
 * `Modifier.sessionMask()`, web views and views inside Compose are painted in both.
 */
public enum class SessionMaskMode(public val wire: String) {
    /** Text fields and the marked elements only. */
    INPUTS("inputs"),
    /** Every text and image too. The default. */
    TEXT_AND_IMAGES("text_and_images");

    public companion object {
        public fun of(wire: String): SessionMaskMode = entries.first { it.wire == wire }
    }
}

/** A size in dp (points on iOS). */
public data class SessionSize(val w: Double, val h: Double) {
    val orientation: SessionOrientation get() = if (w > h) SessionOrientation.LANDSCAPE else SessionOrientation.PORTRAIT
}

/** A rectangle in dp; `x` and `y` are the top left corner. */
public data class SessionRect(val x: Double, val y: Double, val w: Double, val h: Double) {
    val size: SessionSize get() = SessionSize(w, h)
}

/**
 * One thing that happened in a session (`SessionEvent` in `schema/sessions/batch.schema.json`).
 * `t` is milliseconds from the start of the session. Positions are 0–10000 of the window.
 */
public sealed interface SessionEvent {
    public val t: Long
    public val kind: String

    public data class Start(override val t: Long, val previousEnd: SessionEndReason, val previousSessionId: String?, val first: Boolean) : SessionEvent {
        override val kind: String get() = "start"
    }
    public data class Screen(override val t: Long, val screen: String, val via: SessionScreenVia, val w: Double, val h: Double, val orientation: SessionOrientation) : SessionEvent {
        override val kind: String get() = "screen"
    }
    public data class Tap(override val t: Long, val x: Int, val y: Int, val screen: String, val target: String?) : SessionEvent {
        override val kind: String get() = "tap"
    }
    public data class Rage(override val t: Long, val x: Int, val y: Int, val count: Int, val screen: String) : SessionEvent {
        override val kind: String get() = "rage"
    }
    public data class Dead(override val t: Long, val x: Int, val y: Int, val screen: String) : SessionEvent {
        override val kind: String get() = "dead"
    }
    public data class Back(override val t: Long, val screen: String, val via: SessionBackVia) : SessionEvent {
        override val kind: String get() = "back"
    }
    public data class QuickBack(override val t: Long, val screen: String, val ms: Long) : SessionEvent {
        override val kind: String get() = "quick_back"
    }
    public data class Scroll(override val t: Long, val screen: String, val list: String?, val maxRatio: Int, val maxRow: Int?) : SessionEvent {
        override val kind: String get() = "scroll"
    }
    public data class Keyboard(override val t: Long, val shown: Boolean) : SessionEvent {
        override val kind: String get() = "keyboard"
    }
    public data class App(override val t: Long, val name: String, val props: Map<String, SessionValue>, val propsDropped: Boolean) : SessionEvent {
        override val kind: String get() = "event"
    }
    public data class Mark(override val t: Long, val markKind: SessionMarkKind, val ref: String, val side: String?) : SessionEvent {
        override val kind: String get() = "mark"
    }
    public data class Hang(override val t: Long, val ms: Long) : SessionEvent {
        override val kind: String get() = "hang"
    }
    public data class Background(override val t: Long) : SessionEvent {
        override val kind: String get() = "background"
    }
    public data class Foreground(override val t: Long) : SessionEvent {
        override val kind: String get() = "foreground"
    }
    public data class End(override val t: Long, val reason: SessionEndReason) : SessionEvent {
        override val kind: String get() = "end"
    }
    /**
     * The recording since the previous batch: frames with an image (`captured`), frames without one
     * by why (`dropped`), the main thread's time per frame and why it stopped, if it did.
     */
    public data class ReplayStats(
        override val t: Long,
        val captured: Int,
        val dropped: Dropped,
        val mainMsP50: Double,
        val mainMsP95: Double,
        val stopped: SessionReplayStopReason? = null,
    ) : SessionEvent {
        override val kind: String get() = "replay_stats"

        public data class Dropped(val same: Int = 0, val maskingFailed: Int = 0, val noCapture: Int = 0, val secure: Int = 0)
    }

    /** The event as the batch schema's JSON object. */
    public fun toJson(): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>("k" to kind, "t" to t)
        when (this) {
            is Start -> {
                out["previous_end"] = previousEnd.wire
                previousSessionId?.let { out["previous_session_id"] = it }
                out["first"] = first
            }
            is Screen -> { out["screen"] = screen; out["via"] = via.wire; out["w"] = w; out["h"] = h; out["orientation"] = orientation.wire }
            is Tap -> { out["x"] = x; out["y"] = y; out["screen"] = screen; target?.let { out["target"] = it } }
            is Rage -> { out["x"] = x; out["y"] = y; out["count"] = count; out["screen"] = screen }
            is Dead -> { out["x"] = x; out["y"] = y; out["screen"] = screen }
            is Back -> { out["screen"] = screen; out["via"] = via.wire }
            is QuickBack -> { out["screen"] = screen; out["ms"] = ms }
            is Scroll -> { out["screen"] = screen; list?.let { out["list"] = it }; out["max_ratio"] = maxRatio; maxRow?.let { out["max_row"] = it } }
            is Keyboard -> out["shown"] = shown
            is App -> {
                out["name"] = name
                if (props.isNotEmpty()) out["props"] = props.mapValues { it.value.json }
                if (propsDropped) out["props_dropped"] = true
            }
            is Mark -> { out["kind"] = markKind.wire; out["ref"] = ref; side?.let { out["side"] = it } }
            is Hang -> out["ms"] = ms
            is Background, is Foreground -> Unit
            is End -> out["reason"] = reason.wire
            is ReplayStats -> {
                out["captured"] = captured
                out["dropped"] = mapOf(
                    "same" to dropped.same, "masking_failed" to dropped.maskingFailed,
                    "no_capture" to dropped.noCapture, "secure" to dropped.secure,
                )
                out["main_ms_p50"] = mainMsP50
                out["main_ms_p95"] = mainMsP95
                stopped?.let { out["stopped"] = it.wire }
            }
        }
        return out
    }

    public companion object {
        /** Reads an event from the batch schema's JSON object. */
        public fun fromJson(json: Map<String, Any?>): SessionEvent {
            val t = json["t"].asLong()
            fun s(key: String) = json[key] as String
            fun so(key: String) = json[key] as String?
            return when (val k = s("k")) {
                "start" -> Start(t, SessionEndReason.of(s("previous_end")), so("previous_session_id"), json["first"] as Boolean)
                "screen" -> Screen(t, s("screen"), SessionScreenVia.of(s("via")), json["w"].asDouble(), json["h"].asDouble(), SessionOrientation.of(s("orientation")))
                "tap" -> Tap(t, json["x"].asInt(), json["y"].asInt(), s("screen"), so("target"))
                "rage" -> Rage(t, json["x"].asInt(), json["y"].asInt(), json["count"].asInt(), s("screen"))
                "dead" -> Dead(t, json["x"].asInt(), json["y"].asInt(), s("screen"))
                "back" -> Back(t, s("screen"), SessionBackVia.of(s("via")))
                "quick_back" -> QuickBack(t, s("screen"), json["ms"].asLong())
                "scroll" -> Scroll(t, s("screen"), so("list"), json["max_ratio"].asInt(), json["max_row"]?.asInt())
                "keyboard" -> Keyboard(t, json["shown"] as Boolean)
                "event" -> App(t, s("name"), (json["props"] as Map<*, *>?)?.entries?.mapNotNull { (key, value) ->
                    SessionValue.of(value)?.let { key as String to it }
                }?.toMap() ?: emptyMap(), json["props_dropped"] == true)
                "mark" -> Mark(t, SessionMarkKind.of(s("kind")), s("ref"), so("side"))
                "hang" -> Hang(t, json["ms"].asLong())
                "background" -> Background(t)
                "foreground" -> Foreground(t)
                "end" -> End(t, SessionEndReason.of(s("reason")))
                "replay_stats" -> {
                    val d = json["dropped"].asMap()
                    ReplayStats(
                        t, json["captured"].asInt(),
                        ReplayStats.Dropped(d["same"].asInt(), d["masking_failed"].asInt(), d["no_capture"].asInt(), d["secure"].asInt()),
                        json["main_ms_p50"].asDouble(), json["main_ms_p95"].asDouble(), so("stopped")?.let(SessionReplayStopReason::of),
                    )
                }
                else -> throw IllegalArgumentException("unknown session event k: $k")
            }
        }
    }
}
