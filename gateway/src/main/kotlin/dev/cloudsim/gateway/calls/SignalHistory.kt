package dev.cloudsim.gateway.calls

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * A record of each card's signal (SIGNAL_BARS.md 5): one line every [periodMs] per card, and one at once when it
 * changes a lot. Only the slot, the technology, the measurements and the bars: no number, IMSI or cell id.
 */
class SignalHistory(private val now: () -> Long, private val write: (String) -> Unit, private val periodMs: Long = PERIOD_MS) {
    private data class Seen(val state: String, val bars: Int?, val rat: SignalScale.Rat?, val dbm: Int?)

    private val last = HashMap<Int, Pair<Long, Seen>>()

    /** [slot] counts from 1; [signal] null when the card is not in service (or has no signal read). */
    @Synchronized
    fun observe(slot: Int, state: String, signal: SignalScale.Signal?) {
        val seen = Seen(state, signal?.bars, signal?.rat, signal?.dbm)
        val at = now()
        val before = last[slot]
        val why = when {
            before == null -> "first"
            changed(before.second, seen) -> "change"
            at - before.first >= periodMs -> "periodic"
            else -> return
        }
        last[slot] = at to seen
        write(line(at, slot, state, signal, why))
    }

    /** A slot no longer listed starts afresh if it comes back. */
    @Synchronized
    fun keepOnly(slots: Set<Int>) {
        last.keys.retainAll(slots)
    }

    companion object {
        const val PERIOD_MS = 5 * 60_000L

        /** A lot: in or out of service, another technology, bars two apart or to or from none, or 10 dB. */
        private fun changed(a: Seen, b: Seen): Boolean {
            if (a.state != b.state || a.rat != b.rat) return true
            val (x, y) = (a.bars ?: return b.bars != null) to (b.bars ?: return true)
            if (abs(x - y) >= 2 || (x == 0) != (y == 0)) return true
            val (p, q) = (a.dbm ?: return b.dbm != null) to (b.dbm ?: return true)
            return abs(p - q) >= 10
        }

        fun line(at: Long, slot: Int, state: String, s: SignalScale.Signal?, why: String): String {
            val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(at))
            val parts = mutableListOf("$time signal slot=$slot", "state=$state")
            if (s != null) {
                if (s.rat.label.isNotEmpty()) parts += "rat=${s.rat.label}"
                s.dbm?.let { parts += "dbm=$it" }
                s.sinr?.let { parts += "sinr=$it" }
                s.rsrq?.let { parts += "rsrq=$it" }
                parts += "bars=${s.bars}"
                parts += "android=${s.androidLevel}"
                if (!s.measured) parts += "measured=no"
            }
            parts += "why=$why"
            return parts.joinToString(" ")
        }
    }
}

/** The history's lines, kept in the app's own files whatever the log level, about three days of them. */
class SignalLog(private val file: File, private val keep: Int = 2000) {
    constructor(context: android.content.Context) : this(File(context.filesDir, FILE))

    companion object {
        const val FILE = "signal-history.log"
    }

    private var count = -1

    @Synchronized
    fun append(line: String) {
        runCatching {
            if (count < 0) count = if (file.exists()) file.readLines().size else 0
            file.appendText(line + "\n")
            // Cut back now and then rather than on every line: up to half as many again first.
            if (++count > keep * 3 / 2) {
                file.writeText(file.readLines().takeLast(keep).joinToString("\n", postfix = "\n"))
                count = keep
            }
        }
    }

    @Synchronized
    fun tail(lines: Int): String = runCatching { file.takeIf { it.exists() }?.readLines()?.takeLast(lines)?.joinToString("\n") }.getOrNull().orEmpty()
}
