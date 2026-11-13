package dev.cloudsim.gateway.recording

import dev.cloudsim.gateway.calls.RecRights
import java.io.InputStream

/**
 * What a family phone may get of a recording (RECORDING_V7.md 4 and 15), worked out afresh for every request:
 *
 * > the time this phone was in the call ∩ the time the recording was running
 *
 * unless the owner gave it the number's whole recordings from a date on, which then come whole. A phone with neither
 * play nor export gets nothing; a recording from before presence was kept (7a) only comes whole.
 */
object RecordingAccess {
    /** Whole: the recording as it is. */
    val WHOLE: List<LongRange>? = null

    /**
     * The audio-time stretches (ms from the recording's start, end exclusive) [device] may get of [r]; [WHOLE] for all
     * of it, empty for none.
     */
    fun ranges(r: Recording, device: String, rights: RecRights): List<LongRange>? {
        if (!rights.any || !r.hasAudio) return emptyList()
        if (rights.all && r.started >= rights.allFrom) return WHOLE
        val mine = r.presence.filter { it.device == device }
        if (mine.isEmpty()) return emptyList()
        val out = mutableListOf<LongRange>()
        for (span in r.spans) {
            if (span.wallEnd <= span.wallStart) continue
            for (p in mine) {
                val from = maxOf(span.wallStart, p.from)
                val to = minOf(span.wallEnd, p.to)
                if (to <= from) continue
                out += (span.audioStart + (from - span.wallStart)) until (span.audioStart + (to - span.wallStart))
            }
        }
        return merge(out)
    }

    /** How long [ranges] last, in ms; [WHOLE] is the recording's own length. */
    fun length(r: Recording, ranges: List<LongRange>?): Long = ranges?.sumOf { it.last + 1 - it.first } ?: r.audioMs

    /** Overlapping or touching stretches made one, in order. */
    fun merge(ranges: List<LongRange>): List<LongRange> {
        val sorted = ranges.filter { !it.isEmpty() }.sortedBy { it.first }
        val out = mutableListOf<LongRange>()
        for (x in sorted) {
            val last = out.lastOrNull()
            if (last != null && x.first <= last.last + 1) out[out.size - 1] = last.first..maxOf(last.last, x.last) else out += x
        }
        return out
    }

    /** Whether the AAC frame starting at [index] (each [Recorder.FRAME_MS] long) falls in [ranges]. */
    fun keeps(ranges: List<LongRange>, index: Long): Boolean {
        val at = index * Recorder.FRAME_MS
        return ranges.any { at in it }
    }
}

/**
 * An ADTS stream with only the frames [keep] says, the rest left out: cutting needs no re-encoding, as every frame is
 * 64 ms and stands alone. A damaged header ends the stream there.
 */
class ClippedAdts(private val source: InputStream, private val keep: (Long) -> Boolean) : InputStream() {
    private var frame = ByteArray(0)
    private var pos = 0
    private var index = 0L
    private var done = false

    private fun fill(): Boolean {
        while (pos >= frame.size) {
            if (done) return false
            val header = ByteArray(7)
            if (!readFully(header, 0, 7)) { done = true; return false }
            if ((header[0].toInt() and 0xff) != 0xff || (header[1].toInt() and 0xf0) != 0xf0) { done = true; return false }
            val length = ((header[3].toInt() and 0x03) shl 11) or ((header[4].toInt() and 0xff) shl 3) or ((header[5].toInt() and 0xe0) ushr 5)
            if (length < 7) { done = true; return false }
            val body = ByteArray(length)
            System.arraycopy(header, 0, body, 0, 7)
            if (!readFully(body, 7, length - 7)) { done = true; return false }
            val kept = keep(index++)
            if (kept) { frame = body; pos = 0 }
        }
        return true
    }

    private fun readFully(b: ByteArray, off: Int, len: Int): Boolean {
        var n = 0
        while (n < len) {
            val k = source.read(b, off + n, len - n)
            if (k < 0) return false
            n += k
        }
        return true
    }

    override fun read(): Int = if (!fill()) -1 else frame[pos++].toInt() and 0xff

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val n = minOf(len, frame.size - pos)
        System.arraycopy(frame, pos, b, off, n)
        pos += n
        return n
    }

    override fun close() = source.close()

    companion object {
        /** The size the clipped stream comes to, read through once. */
        fun size(source: InputStream, keep: (Long) -> Boolean): Long {
            var total = 0L
            ClippedAdts(source, keep).use { s ->
                val buf = ByteArray(16 * 1024)
                while (true) { val k = s.read(buf); if (k < 0) break; total += k }
            }
            return total
        }
    }
}
