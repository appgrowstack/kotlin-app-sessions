package com.appgrowstack.sessions

import java.io.ByteArrayOutputStream

/**
 * One frame of the recording (`ReplayFrame` of `sessions/segment.schema.json`). `t` counts from the
 * start of the session; `w` and `h` are the image's pixels (1 dp is 1 px); `masked` says something was
 * painted over.
 */
public sealed interface SessionReplayFrame {
    public val t: Long
    public val w: Int
    public val h: Int
    public val masked: Boolean

    /** A frame with its image (`image/webp` on Android). */
    public class Image(
        override val t: Long,
        override val w: Int,
        override val h: Int,
        override val masked: Boolean,
        public val mime: String,
        public val bytes: ByteArray,
    ) : SessionReplayFrame {
        override fun equals(other: Any?): Boolean =
            other is Image && t == other.t && w == other.w && h == other.h && masked == other.masked && mime == other.mime && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = listOf(t, w, h, masked, mime, bytes.contentHashCode()).hashCode()
        override fun toString(): String = "Image(t=$t, w=$w, h=$h, masked=$masked, mime=$mime, bytes=${bytes.size})"
    }

    /** Looks the same as the frame before it (no image). */
    public data class Same(override val t: Long, override val w: Int, override val h: Int, override val masked: Boolean) : SessionReplayFrame

    /** Not taken, for `reason` (no image). */
    public data class Blank(override val t: Long, override val w: Int, override val h: Int, val reason: SessionBlankReason, override val masked: Boolean = false) : SessionReplayFrame
}

/**
 * A segment of the recording, the `.agsr` file:
 * `"AGSR"` (4 bytes) | version 1 (1 byte) | header length (4 bytes, big-endian) | header (JSON, UTF-8) | the images.
 * The header is `ReplaySegmentHeader` of `sessions/segment.schema.json`: an image's `offset` counts from the end
 * of the header, and the images follow one another in the order of the frames. `cases/sessions/segment-*.json`
 * checks reading it against Swift and the server.
 */
public class SessionReplaySegment(public val sessionId: String, public val seq: Int, public val frames: List<SessionReplayFrame>) {
    init {
        require(frames.isNotEmpty()) { "a segment has at least one frame" }
        require(frames.size <= MAX_FRAMES) { "a segment has at most $MAX_FRAMES frames" }
    }

    /** The first frame's `t`. */
    public val tFrom: Long get() = frames.first().t

    /** The last frame's `t`. */
    public val tTo: Long get() = frames.last().t

    /** The bytes of the images together. */
    public val imageBytes: Int get() = frames.sumOf { (it as? SessionReplayFrame.Image)?.bytes?.size ?: 0 }

    /** The header as a JSON object, with each image's `offset` and `length`. */
    public fun header(): Map<String, Any?> {
        var offset = 0
        return linkedMapOf(
            "session_id" to sessionId,
            "seq" to seq,
            "t_from" to tFrom,
            "t_to" to tTo,
            "frames" to frames.map { frame ->
                val out = linkedMapOf<String, Any?>("t" to frame.t)
                when (frame) {
                    is SessionReplayFrame.Image -> {
                        out["kind"] = "image"
                        out["mime"] = frame.mime
                        out["offset"] = offset
                        out["length"] = frame.bytes.size
                        offset += frame.bytes.size
                    }
                    is SessionReplayFrame.Same -> out["kind"] = "same"
                    is SessionReplayFrame.Blank -> out["kind"] = "blank"
                }
                out["w"] = frame.w
                out["h"] = frame.h
                out["masked"] = frame.masked
                if (frame is SessionReplayFrame.Blank) out["reason"] = frame.reason.value
                out
            },
        )
    }

    /** The `.agsr` bytes. */
    public fun encode(): ByteArray {
        val header = SessionJson.encode(header()).toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(PREAMBLE + header.size + imageBytes)
        out.write(MAGIC)
        out.write(VERSION)
        out.write(header.size ushr 24 and 0xff)
        out.write(header.size ushr 16 and 0xff)
        out.write(header.size ushr 8 and 0xff)
        out.write(header.size and 0xff)
        out.write(header)
        frames.forEach { if (it is SessionReplayFrame.Image) out.write(it.bytes) }
        return out.toByteArray()
    }

    override fun equals(other: Any?): Boolean = other is SessionReplaySegment && sessionId == other.sessionId && seq == other.seq && frames == other.frames
    override fun hashCode(): Int = listOf(sessionId, seq, frames).hashCode()
    override fun toString(): String = "SessionReplaySegment(sessionId=$sessionId, seq=$seq, frames=${frames.size}, t=$tFrom..$tTo)"

    /** Why bytes are not a segment (the server's `parseSegment` gives the same names). */
    public enum class Error(public val wire: String) {
        BAD_MAGIC("bad_magic"), BAD_VERSION("bad_version"), BAD_LENGTH("bad_length"), BAD_HEADER("bad_header"),
        BAD_FRAME_RANGE("bad_frame_range"), TOO_LARGE("too_large"),
    }

    public class Invalid(public val error: Error) : IllegalArgumentException("not a replay segment: ${error.wire}")

    public companion object {
        /** A segment is at most 1 MB (the server takes no more). */
        public const val MAX_BYTES: Int = 1024 * 1024
        public const val MAX_FRAMES: Int = 600
        public const val VERSION: Int = 1
        /** `"AGSR"`, the version and the header length. */
        public const val PREAMBLE: Int = 9
        private val MAGIC = byteArrayOf(0x41, 0x47, 0x53, 0x52)
        private val SESSION_ID = Regex("^ses_[A-Za-z0-9]{20}$")
        private val MIMES = setOf("image/jpeg", "image/webp")

        /** Reads a segment. Throws [Invalid] for bytes that are not one. */
        public fun decode(bytes: ByteArray): SessionReplaySegment {
            if (bytes.size > MAX_BYTES) throw Invalid(Error.TOO_LARGE)
            val magic = bytes.size >= 4 && (0 until 4).all { bytes[it] == MAGIC[it] }
            if (!magic) throw Invalid(Error.BAD_MAGIC)
            if (bytes.size < PREAMBLE) throw Invalid(Error.BAD_LENGTH)
            if (bytes[4].toInt() != VERSION) throw Invalid(Error.BAD_VERSION)
            val headerLength = ((bytes[5].toLong() and 0xff) shl 24) or ((bytes[6].toLong() and 0xff) shl 16) or
                ((bytes[7].toLong() and 0xff) shl 8) or (bytes[8].toLong() and 0xff)
            if (PREAMBLE + headerLength > bytes.size) throw Invalid(Error.BAD_LENGTH)
            val end = PREAMBLE + headerLength.toInt()
            val json = try {
                val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes, PREAMBLE, headerLength.toInt())).toString()
                SessionJson.parse(text)
            } catch (_: Exception) {
                throw Invalid(Error.BAD_HEADER)
            }
            val header = readHeader(json) ?: throw Invalid(Error.BAD_HEADER)
            val imageBytes = bytes.size - end
            var at = 0
            val frames = header.frames.map { f ->
                if (f.kind != "image") return@map f.frame(null)
                val offset = f.offset!!
                val length = f.length!!
                if (offset.toLong() != at.toLong() || offset.toLong() + length > imageBytes) throw Invalid(Error.BAD_FRAME_RANGE)
                at += length
                f.frame(bytes.copyOfRange(end + offset, end + offset + length))
            }
            if (at != imageBytes) throw Invalid(Error.BAD_FRAME_RANGE)
            return SessionReplaySegment(header.sessionId, header.seq, frames)
        }

        private class RawFrame(
            val t: Long, val kind: String, val w: Int, val h: Int, val masked: Boolean,
            val mime: String?, val offset: Int?, val length: Int?, val reason: SessionBlankReason?,
        ) {
            fun frame(image: ByteArray?): SessionReplayFrame = when (kind) {
                "image" -> SessionReplayFrame.Image(t, w, h, masked, mime!!, image!!)
                "same" -> SessionReplayFrame.Same(t, w, h, masked)
                else -> SessionReplayFrame.Blank(t, w, h, reason!!, masked)
            }
        }

        private class RawHeader(val sessionId: String, val seq: Int, val frames: List<RawFrame>)

        private val HEADER_KEYS: Set<Any?> = setOf("session_id", "seq", "t_from", "t_to", "frames")
        private val FRAME_KEYS: Set<Any?> = setOf("t", "kind", "mime", "offset", "length", "w", "h", "masked", "reason")

        /** The header's shape as `segment.schema.json` has it, or null. */
        private fun readHeader(json: Any?): RawHeader? {
            val m = json as? Map<*, *> ?: return null
            if (!HEADER_KEYS.containsAll(m.keys) || !m.keys.containsAll(HEADER_KEYS)) return null
            val sessionId = (m["session_id"] as? String)?.takeIf { SESSION_ID.matches(it) } ?: return null
            val seq = whole(m["seq"])?.takeIf { it >= 0 && it <= Int.MAX_VALUE } ?: return null
            whole(m["t_from"])?.takeIf { it >= 0 } ?: return null
            whole(m["t_to"])?.takeIf { it >= 0 } ?: return null
            val list = m["frames"] as? List<*> ?: return null
            if (list.isEmpty() || list.size > MAX_FRAMES) return null
            val frames = list.map { readFrame(it) ?: return null }
            return RawHeader(sessionId, seq.toInt(), frames)
        }

        private fun readFrame(json: Any?): RawFrame? {
            val f = json as? Map<*, *> ?: return null
            val keys: Set<Any?> = f.keys
            if (!FRAME_KEYS.containsAll(keys)) return null
            val t = whole(f["t"])?.takeIf { it >= 0 } ?: return null
            val kind = f["kind"] as? String ?: return null
            val w = whole(f["w"])?.takeIf { it in 1..4096 } ?: return null
            val h = whole(f["h"])?.takeIf { it in 1..4096 } ?: return null
            val masked = f["masked"] as? Boolean ?: return null
            val hasImage = listOf("mime", "offset", "length").map { it in keys }
            return when (kind) {
                "image" -> {
                    if (!hasImage.all { it } || "reason" in keys) return null
                    val mime = (f["mime"] as? String)?.takeIf { it in MIMES } ?: return null
                    val offset = whole(f["offset"])?.takeIf { it in 0..Int.MAX_VALUE } ?: return null
                    val length = whole(f["length"])?.takeIf { it in 1..Int.MAX_VALUE } ?: return null
                    RawFrame(t, kind, w.toInt(), h.toInt(), masked, mime, offset.toInt(), length.toInt(), null)
                }
                "same" -> {
                    if (hasImage.any { it } || "reason" in keys) return null
                    RawFrame(t, kind, w.toInt(), h.toInt(), masked, null, null, null, null)
                }
                "blank" -> {
                    if (hasImage.any { it }) return null
                    val reason = SessionBlankReason.entries.firstOrNull { it.value == f["reason"] } ?: return null
                    RawFrame(t, kind, w.toInt(), h.toInt(), masked, null, null, null, reason)
                }
                else -> null
            }
        }

        /** A JSON whole number (a `Double` with no fraction counts, as in JSON Schema's `integer`). */
        private fun whole(value: Any?): Long? = when (value) {
            is Long -> value
            is Int -> value.toLong()
            is Double -> if (value == Math.floor(value) && !value.isInfinite()) value.toLong() else null
            else -> null
        }
    }
}
