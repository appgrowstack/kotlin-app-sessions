package com.appgrowstack.sessions

import java.io.File

/**
 * The recording's segments on the device: builds a segment from the frames [AppSessions] hands it and
 * writes each closed one to `<directory>/sessions/replay/<session_id>/<seq>.agsr`. [AppSessions] decides
 * which are sent (to [sink]) and which are removed. Pass it as `AppSessions(replay = …)`; on Android give
 * it `context.noBackupFilesDir`.
 *
 * A segment closes at 60 seconds, at 900 KB of images (with its header it stays under the 1 MB a
 * segment may be) or at 600 frames. The folder keeps 50 MB at most (past it the oldest segments of
 * sessions that will not be sent go first), nothing older than 7 days, and one session records 30 minutes
 * or 20 MB at most.
 */
public class SessionReplayRecorder(
    directory: File,
    public val sink: SessionReplaySink,
    public val limits: Limits = Limits(),
) {
    public data class Limits(
        val segmentMs: Long = 60_000,
        val segmentImageBytes: Int = 900 * 1024,
        val segmentFrames: Int = SessionReplaySegment.MAX_FRAMES,
        val totalBytes: Long = 50L * 1024 * 1024,
        val maxAgeMs: Long = 7L * 24 * 60 * 60 * 1000,
        val sessionMs: Long = 30L * 60 * 1000,
        val sessionBytes: Long = 20L * 1024 * 1024,
        /** In `BUFFER`, what is older than this before the newest frame is removed. */
        val bufferMs: Long = 60_000,
        /** A device sends at most this much a day over a metered network (priority sessions only). */
        val meteredDailyBytes: Long = 3L * 1024 * 1024,
    )

    public val directory: File = File(directory, "sessions/replay")

    private val lock = Any()
    private var sessionId: String? = null
    private var nextSeq = 0
    private val frames = mutableListOf<SessionReplayFrame>()
    private var openImageBytes = 0
    private var sessionBytes = 0L
    private var lastFingerprint: String? = null
    /** The current session's closed segments still on the device: seq → `t_to`. */
    private val closedTo = sortedMapOf<Int, Long>()
    /** A segment was written since [add] last said so (closing in [wantsImage] is reported by the next [add]). */
    private var wroteUnreported = false

    /** What adding a frame did. */
    internal data class Added(val closed: Boolean, val sessionLimit: Boolean)

    // ---- Building (the capture's thread, and AppSessions)

    /** A new session: writes what was open of the one before. */
    internal fun begin(id: String): Unit = synchronized(lock) {
        writeOpen()
        wroteUnreported = false
        sessionId = id
        nextSeq = 0
        sessionBytes = 0
        lastFingerprint = null
        closedTo.clear()
    }

    /**
     * Whether a frame at `t` that looks like `fingerprint` needs its image: the first of a segment, the
     * first after a blank one, or one that changed. Closes the open segment first when it is due.
     */
    internal fun wantsImage(id: String, fingerprint: String, t: Long): Boolean = synchronized(lock) {
        if (id != sessionId) return false
        if (frames.isNotEmpty() && (t - frames.first().t >= limits.segmentMs || frames.size >= limits.segmentFrames)) writeOpen()
        frames.isEmpty() || lastFingerprint == null || fingerprint != lastFingerprint
    }

    /** Adds a frame of session `id`. A `same` frame that would begin a segment is left out (the segment must start with what it shows). */
    internal fun add(id: String, frame: SessionReplayFrame, fingerprint: String?): Added = synchronized(lock) {
        if (id != sessionId) return Added(false, false)
        append(frame, fingerprint)
        val closed = wroteUnreported
        wroteUnreported = false
        Added(closed, sessionBytes + openImageBytes > limits.sessionBytes)
    }

    private fun append(frame: SessionReplayFrame, fingerprint: String?) {
        if (frames.isNotEmpty() && (frame.t - frames.first().t >= limits.segmentMs || frames.size >= limits.segmentFrames)) writeOpen()
        if (frame is SessionReplayFrame.Image) {
            if (frame.bytes.size > limits.segmentImageBytes) return
            if (openImageBytes + frame.bytes.size > limits.segmentImageBytes) writeOpen()
        }
        if (frame is SessionReplayFrame.Same && frames.isEmpty()) return
        frames.add(frame)
        if (frame is SessionReplayFrame.Image) openImageBytes += frame.bytes.size
        lastFingerprint = if (frame is SessionReplayFrame.Blank) null else fingerprint
        if (frames.size >= limits.segmentFrames) writeOpen()
    }

    /** Closes the open segment of `id` when it spans `segmentMs` by `t`. True when one was written. */
    internal fun closeIfDue(id: String, t: Long): Boolean = synchronized(lock) {
        if (id != sessionId || frames.isEmpty() || t - frames.first().t < limits.segmentMs) return false
        writeOpen().also { wroteUnreported = false }
    }

    /** Writes the open segment now (going to the back, the session ending). True when one was written. */
    internal fun closeOpen(): Boolean = synchronized(lock) { writeOpen().also { wroteUnreported = false } }

    /** Drops the open segment without writing it. */
    internal fun discardOpen(): Unit = synchronized(lock) {
        frames.clear()
        openImageBytes = 0
        lastFingerprint = null
    }

    /** Frames waiting in the open segment. */
    public val openFrames: Int get() = synchronized(lock) { frames.size }

    // ---- The files

    /** The sessions with segments on the device. */
    public fun sessionIds(): List<String> = synchronized(lock) {
        directory.listFiles { f -> f.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
    }

    /** The `seq` of each closed segment of `id` on the device, oldest first. */
    public fun segments(id: String): List<Int> = synchronized(lock) {
        File(directory, id).listFiles { f -> f.name.endsWith(".agsr") }
            ?.mapNotNull { it.name.removeSuffix(".agsr").toIntOrNull() }?.sorted() ?: emptyList()
    }

    /** The bytes kept on the device. */
    public fun totalBytes(): Long = synchronized(lock) { allFiles().sumOf { it.length() } }

    internal fun size(id: String, seq: Int): Long = synchronized(lock) { file(id, seq).length() }

    internal fun read(id: String, seq: Int): ByteArray? = synchronized(lock) {
        runCatching { file(id, seq).takeIf { it.exists() }?.readBytes() }.getOrNull()
    }

    internal fun remove(id: String, seq: Int): Unit = synchronized(lock) {
        file(id, seq).delete()
        if (id == sessionId) closedTo.remove(seq)
        removeIfEmpty(id)
    }

    /** Removes every segment of `id` (and, for the current session, the open one). */
    internal fun removeSession(id: String): Unit = synchronized(lock) {
        if (id == sessionId) {
            frames.clear()
            openImageBytes = 0
            lastFingerprint = null
            closedTo.clear()
        }
        File(directory, id).deleteRecursively()
    }

    /** `BUFFER`: removes the current session's closed segments that end before `keepFromT`. */
    internal fun trim(id: String, keepFromT: Long): Unit = synchronized(lock) {
        if (id != sessionId) return
        closedTo.filter { it.value < keepFromT }.keys.toList().forEach {
            file(id, it).delete()
            closedTo.remove(it)
        }
        removeIfEmpty(id)
    }

    /**
     * Removes segments older than `maxAgeMs`, then the oldest until the folder fits `totalBytes`: those of
     * sessions not in `sending` first. True when a segment of the current session had to go.
     */
    internal fun sweep(nowMs: Long, sending: Set<String>): Boolean = synchronized(lock) {
        var tookCurrent = false
        val files = allFiles().toMutableList()
        files.filter { nowMs - it.lastModified() > limits.maxAgeMs }.forEach { f ->
            f.delete()
            files.remove(f)
            if (f.parentFile.name == sessionId) { closedTo.remove(seqOf(f)); tookCurrent = true }
        }
        var total = files.sumOf { it.length() }
        val order = files.sortedWith(compareBy<File>({ it.parentFile.name in sending }, { it.lastModified() }, { it.parentFile.name }, { seqOf(it) }))
        for (f in order) {
            if (total <= limits.totalBytes) break
            total -= f.length()
            f.delete()
            if (f.parentFile.name == sessionId) { closedTo.remove(seqOf(f)); tookCurrent = true }
        }
        sessionIds().forEach(::removeIfEmpty)
        tookCurrent
    }

    // ---- Inside (with the lock held)

    private fun file(id: String, seq: Int) = File(File(directory, id), "$seq.agsr")

    private fun seqOf(f: File): Int = f.name.removeSuffix(".agsr").toIntOrNull() ?: -1

    private fun allFiles(): List<File> =
        directory.listFiles { f -> f.isDirectory }?.flatMap { d -> d.listFiles { f -> f.name.endsWith(".agsr") }?.toList() ?: emptyList() } ?: emptyList()

    private fun removeIfEmpty(id: String) {
        if (id == sessionId) return
        val dir = File(directory, id)
        if (dir.listFiles()?.isEmpty() == true) dir.delete()
    }

    private fun writeOpen(): Boolean {
        val id = sessionId ?: return false
        if (frames.isEmpty()) return false
        val segment = SessionReplaySegment(id, nextSeq, frames.toList())
        frames.clear()
        openImageBytes = 0
        nextSeq++
        val bytes = segment.encode()
        if (bytes.size > SessionReplaySegment.MAX_BYTES) return false
        return try {
            val dir = File(directory, id)
            dir.mkdirs()
            val temporary = File(dir, "${segment.seq}.agsr.tmp")
            temporary.writeBytes(bytes)
            val target = file(id, segment.seq)
            if (!temporary.renameTo(target)) {
                target.delete()
                temporary.renameTo(target)
            }
            sessionBytes += bytes.size
            closedTo[segment.seq] = segment.tTo
            wroteUnreported = true
            true
        } catch (_: Exception) {
            // A full disk loses this segment, not the app
            false
        }
    }
}
