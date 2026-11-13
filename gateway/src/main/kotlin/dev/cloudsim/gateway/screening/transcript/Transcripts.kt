package dev.cloudsim.gateway.screening.transcript

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What was said on screened calls (SCREENING_ASSISTANT.md 13): the caller's sentences as transcribed and the
 * assistant's own lines, in order. Held in memory while the call goes on, for the owner's iPhone and family with the
 * answer right on the call's number to follow; kept on this phone afterwards, the last [KEEP] calls, never sent
 * anywhere but sealed to those phones.
 */
class Transcripts(private val dir: File, private val now: () -> Long = System::currentTimeMillis) {
    data class Entry(val seq: Int, val who: String, val text: String, val atMs: Long) {
        fun json(): JSONObject = JSONObject().put("seq", seq).put("who", who).put("text", text).put("at_ms", atMs)

        companion object {
            fun of(o: JSONObject) = Entry(o.getInt("seq"), o.getString("who"), o.getString("text"), o.getLong("at_ms"))
        }
    }

    private class Live(val startedMs: Long) {
        val entries = mutableListOf<Entry>()
        val listeners = mutableListOf<(Entry) -> Unit>()
    }

    private val live = HashMap<String, Live>()

    @Synchronized
    fun begin(callId: String) {
        live.getOrPut(callId) { Live(now()) }
    }

    @Synchronized
    fun isLive(callId: String) = callId in live

    /** A line said on [callId]: "caller" or "assistant". Its followers hear it, outside the lock. */
    fun add(callId: String, who: String, text: String): Entry? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val (entry, listeners) = synchronized(this) {
            val l = live[callId] ?: return null
            val e = Entry(l.entries.size + 1, who, t, now() - l.startedMs)
            l.entries += e
            e to l.listeners.toList()
        }
        listeners.forEach { runCatching { it(entry) } }
        return entry
    }

    /** Follows [callId] while it goes on; false when it is over (nothing more will come). */
    @Synchronized
    fun follow(callId: String, listener: (Entry) -> Unit): Boolean {
        val l = live[callId] ?: return false
        l.listeners += listener
        return true
    }

    /** Everything said on [callId] so far, live or kept; null if this phone has none. */
    @Synchronized
    fun entries(callId: String): List<Entry>? {
        live[callId]?.let { return it.entries.toList() }
        val f = file(callId) ?: return null
        return runCatching {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { Entry.of(a.getJSONObject(it)) }
        }.getOrNull()
    }

    /** The call is over: what was said is kept, and nobody follows it any more. */
    @Synchronized
    fun end(callId: String) {
        val l = live.remove(callId) ?: return
        if (l.entries.isEmpty()) return
        val f = file(callId) ?: return
        dir.mkdirs()
        runCatching { f.writeText(JSONArray(l.entries.map { it.json() }).toString()) }
        val all = dir.listFiles { x -> x.name.endsWith(".json") }?.sortedBy { it.lastModified() } ?: return
        all.dropLast(KEEP).forEach { it.delete() }
    }

    private fun file(callId: String): File? = callId.takeIf { ID.matches(it) }?.let { File(dir, "$it.json") }

    companion object {
        const val KEEP = 300
        private val ID = Regex("[0-9a-f-]{8,64}")
    }
}
