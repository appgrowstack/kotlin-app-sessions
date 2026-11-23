package com.appgrowstack.sessions

// ---- Sending segments

/**
 * One segment to send. `uploadToken` is the one the answer to the session's first batch carried
 * ([SessionSinkReply.Accepted.uploadToken]); null when no answer carried one (a sink that needs none).
 */
public class SessionReplayUpload(
    public val sessionId: String,
    public val seq: Int,
    public val installId: String,
    public val uploadToken: String?,
    /** The `.agsr` bytes ([SessionReplaySegment.encode]). */
    public val bytes: ByteArray,
) {
    override fun toString(): String = "SessionReplayUpload(sessionId=$sessionId, seq=$seq, bytes=${bytes.size})"
}

/** What a replay sink answers for a segment. To send it again later, throw instead. */
public enum class SessionReplayUploadReply {
    /** Taken. The file is removed. */
    UPLOADED,

    /** This segment will never pass (a 400, 404, 413 or 415). Only it is removed. */
    REJECTED,

    /** The token is not this session's or ran out (a 403). Every segment of the session is removed. */
    FORBIDDEN,
}

/**
 * Where the recording's segments go. Called on the recorder's sending thread, one segment at a
 * time, oldest first; the call may block. Throw (no connection, a 401, 408, 429 or 5xx) to keep the
 * segment and send it again later.
 */
public fun interface SessionReplaySink {
    public fun upload(segment: SessionReplayUpload): SessionReplayUploadReply
}

// ---- The network

public enum class SessionNetworkKind {
    /** No connection. */
    NONE,

    /** Wi-Fi or another network not billed by the amount. Every segment goes. */
    UNMETERED,

    /** A metered network. Only a session that met a priority condition sends, up to 3 MB a day per device. */
    METERED,
}

/** The network the device is on now. `sessions-compose` reads Android's `NET_CAPABILITY_NOT_METERED`. */
public fun interface SessionNetwork {
    public fun current(): SessionNetworkKind

    public companion object {
        /** Every network counts as unmetered (tests, and a JVM without a way to tell). */
        public val UNMETERED: SessionNetwork = SessionNetwork { SessionNetworkKind.UNMETERED }
    }
}

// ---- Live

/**
 * Someone is watching this device (`SessionLiveOffer` of the batch answer). `headers` are what the
 * connection carries besides the token (the sink's key and the install id); the token goes in
 * `Sec-WebSocket-Protocol`.
 */
public data class SessionLiveOffer(val url: String, val token: String, val headers: Map<String, String> = emptyMap())

/**
 * Opens a WebSocket for the live view. The package has none: Android has no WebSocket of its own,
 * and the package takes no dependency. An app that wants the live view wraps its own client (OkHttp's
 * `WebSocket`, for one) and passes it as `AppSessions(liveTransport = …)`.
 */
public fun interface SessionLiveTransport {
    /**
     * Starts opening `offer.url` with `offer.token` as the `Sec-WebSocket-Protocol` and `offer.headers`.
     * Returns at once; `listener` hears what comes back, on any thread.
     */
    public fun open(offer: SessionLiveOffer, listener: SessionLiveListener): SessionLiveConnection
}

/** An open (or opening) live connection. */
public interface SessionLiveConnection {
    /** Sends a text message. False when it could not be queued. */
    public fun sendText(text: String): Boolean

    /** Sends a binary message (the image right after its `frame`). */
    public fun sendBinary(bytes: ByteArray): Boolean

    public fun close()
}

/** What a live connection hears. */
public interface SessionLiveListener {
    /** The connection is open. */
    public fun opened()

    /** A text message from the server (`stop` or `rate` of `sessions/live.schema.json`). */
    public fun message(text: String)

    /** The connection closed or failed. */
    public fun closed()
}
