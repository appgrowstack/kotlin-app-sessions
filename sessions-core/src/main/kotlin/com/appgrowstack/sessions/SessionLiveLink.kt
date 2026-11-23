package com.appgrowstack.sessions

/**
 * One live connection (`sessions/live.schema.json`): `hello` once it opens, each event at once, and
 * frames (a `frame` text message, then the image as a binary one) up to `max_fps` (2 to begin with). The
 * server's `stop` closes it and `rate` changes `max_fps`. [AppSessions] opens it from a batch answer's
 * `live` and closes it when the app goes to the back or the session ends.
 */
internal class SessionLiveLink(
    private val transport: SessionLiveTransport,
    private val offer: SessionLiveOffer,
    private val sessionId: String,
    private val hello: () -> Triple<String, Double, Double>,
    private val nowMs: () -> Long,
    private val onEnd: (SessionLiveLink) -> Unit,
) : SessionLiveListener {
    @Volatile var open: Boolean = false
        private set
    @Volatile private var ended = false
    @Volatile private var maxFps = 2.0
    @Volatile private var lastFrameAt = Long.MIN_VALUE / 2
    /** The viewer has no image yet: the next frame goes with its image even if it looks the same. */
    @Volatile var needsImage: Boolean = true
        private set
    @Volatile private var connection: SessionLiveConnection? = null
    @Volatile private var helloSent = false

    /** The shortest time between frames now. */
    val frameIntervalMs: Long get() = (1000 / maxFps).toLong()

    /** Starts opening. Kept out of the constructor: a transport may answer `opened` before `open` returns. */
    fun start() {
        connection = transport.open(offer, this)
        if (open) sendHello()
    }

    override fun opened() {
        if (ended) return
        open = true
        sendHello()
    }

    private fun sendHello() {
        val connection = connection ?: return
        synchronized(this) {
            if (helloSent) return
            helloSent = true
        }
        val (screen, w, h) = hello()
        connection.sendText(SessionJson.encode(mapOf("type" to "hello", "session_id" to sessionId, "screen" to screen, "w" to w, "h" to h)))
    }

    override fun message(text: String) {
        val json = runCatching { SessionJson.parse(text) }.getOrNull() as? Map<*, *> ?: return
        when (json["type"]) {
            "stop" -> close()
            "rate" -> (json["max_fps"] as? Number)?.toDouble()?.takeIf { it > 0 && it <= 2 }?.let { maxFps = it }
        }
    }

    override fun closed() {
        open = false
        end()
    }

    fun event(event: SessionEvent) {
        if (!open || !helloSent) return
        connection?.sendText(SessionJson.encode(mapOf("type" to "event", "event" to event.toJson())))
    }

    /** A frame is due for the viewer. */
    fun wantsFrame(): Boolean = open && helloSent && nowMs() - lastFrameAt >= frameIntervalMs

    fun frame(t: Long, w: Int, h: Int, masked: Boolean, mime: String, bytes: ByteArray) {
        if (!wantsFrame()) return
        val connection = connection ?: return
        lastFrameAt = nowMs()
        val sent = connection.sendText(SessionJson.encode(mapOf("type" to "frame", "t" to t, "mime" to mime, "w" to w, "h" to h, "masked" to masked)))
        if (sent && connection.sendBinary(bytes)) needsImage = false
    }

    fun close() {
        open = false
        runCatching { connection?.close() }
        end()
    }

    private fun end() {
        if (ended) return
        ended = true
        onEnd(this)
    }
}
