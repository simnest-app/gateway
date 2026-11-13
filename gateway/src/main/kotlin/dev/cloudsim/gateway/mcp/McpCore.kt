package dev.cloudsim.gateway.mcp

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The gateway's MCP server (docs/architecture/GATEWAY_MCP.md), the part that knows nothing of Android: who may connect
 * (a token per tool, granted on this phone's screen), what each may use, and the log of what they did. Text only: no
 * call audio goes through here.
 */

/** What a tool may be given, each ticked on its own (GATEWAY_MCP.md 4.2). M1: the ones that only read. */
enum class Cap(val id: String) {
    STATUS("status"), CALLS("calls"), SMS("sms"), CONTACTS("contacts"), SCREENING("screening");

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id }
    }
}

/**
 * One connected tool. Its token is shown once, when it is made; only the hash is kept. [numbers] narrows it to some of
 * the gateway's numbers (null: all); [hideCodes] and [since] are the two switches for someone who wants it tighter.
 */
data class Grant(
    val id: String, val name: String, val tokenHash: String, val caps: Set<Cap>, val numbers: Set<String>? = null,
    val hideCodes: Boolean = false, val since: Long = 0, val created: Long = 0, val lastSeen: Long = 0,
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("token_hash", tokenHash).put("caps", JSONArray(caps.map { it.id }))
        .put("hide_codes", hideCodes).put("since", since).put("created", created).put("last_seen", lastSeen)
        .apply { numbers?.let { put("numbers", JSONArray(it.toList())) } }

    /** Whether something on gateway number [numberId] is this tool's to see; one from before there were two numbers only without a limit. */
    fun covers(numberId: String?) = numbers == null || (numberId != null && numberId in numbers)

    companion object {
        fun of(o: JSONObject) = Grant(
            o.getString("id"), o.getString("name"), o.getString("token_hash"),
            o.getJSONArray("caps").let { a -> (0 until a.length()).mapNotNull { Cap.of(a.getString(it)) }.toSet() },
            o.optJSONArray("numbers")?.let { a -> (0 until a.length()).map(a::getString).toSet() },
            o.optBoolean("hide_codes"), o.optLong("since"), o.optLong("created"), o.optLong("last_seen"),
        )
    }
}

/** Where the grants are kept across restarts. */
interface GrantStore {
    fun load(): String?
    fun save(json: String)
}

/** The tools granted on this phone. Nothing here asks the owner's iPhone or the service: the grant is the gateway's own. */
class Grants(private val store: GrantStore, private val now: () -> Long = System::currentTimeMillis, private val random: SecureRandom = SecureRandom()) {
    private var list: List<Grant> = runCatching {
        store.load()?.let { JSONArray(it) }?.let { a -> (0 until a.length()).map { Grant.of(a.getJSONObject(it)) } }
    }.getOrNull() ?: emptyList()

    @Synchronized fun all(): List<Grant> = list

    /** A new tool and its token; the token is not kept and cannot be shown again. */
    @Synchronized
    fun add(name: String, caps: Set<Cap>, numbers: Set<String>? = null, hideCodes: Boolean = false, since: Long = 0): Pair<Grant, String> {
        val token = newToken()
        val g = Grant(ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }, name.trim().take(MAX_NAME).ifEmpty { "tool" }, hash(token),
            caps, numbers?.takeIf { it.isNotEmpty() }, hideCodes, since, now())
        list = list + g
        persist()
        return g to token
    }

    /** What an existing tool may use, changed in place; its token stays. */
    @Synchronized
    fun change(id: String, caps: Set<Cap>, numbers: Set<String>?, hideCodes: Boolean, since: Long): Grant? {
        val g = list.firstOrNull { it.id == id } ?: return null
        val n = g.copy(caps = caps, numbers = numbers?.takeIf { it.isNotEmpty() }, hideCodes = hideCodes, since = since)
        list = list.map { if (it.id == id) n else it }
        persist()
        return n
    }

    /** A new token for a tool whose old one was lost or seen by someone else; the old one stops working at once. */
    @Synchronized
    fun renew(id: String): String? {
        if (list.none { it.id == id }) return null
        val token = newToken()
        list = list.map { if (it.id == id) it.copy(tokenHash = hash(token)) else it }
        persist()
        return token
    }

    @Synchronized
    fun revoke(id: String): Boolean {
        val had = list.any { it.id == id }
        list = list.filter { it.id != id }
        if (had) persist()
        return had
    }

    /** The tool this token is for. Compared whole against every grant, so the time taken says nothing of how close a guess was. */
    @Synchronized
    fun byToken(token: String?): Grant? {
        if (token.isNullOrEmpty() || token.length > 200) return null
        val h = hash(token).toByteArray()
        var found: Grant? = null
        for (g in list) if (MessageDigest.isEqual(h, g.tokenHash.toByteArray())) found = g
        return found
    }

    /** Remembered at most once a minute: a tool that asks ten times a second must not write the store ten times a second. */
    @Synchronized
    fun seen(id: String) {
        val t = now()
        val g = list.firstOrNull { it.id == id } ?: return
        if (t - g.lastSeen < 60_000) return
        list = list.map { if (it.id == id) it.copy(lastSeen = t) else it }
        persist()
    }

    private fun persist() = store.save(JSONArray(list.map { it.json() }).toString())
    private fun newToken() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

    companion object {
        const val MAX_NAME = 40
        fun hash(token: String) = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** What tools did, without what was said: for the gateway's screen, and each tool's own lines for itself (GATEWAY_MCP.md 4.7). */
interface AuditLog {
    fun add(entry: JSONObject)
    /** Newest first; [tool] for one tool's lines only. */
    fun recent(tool: String?, limit: Int): List<JSONObject>
}

class MemoryAudit(private val keep: Int = 500) : AuditLog {
    private val lines = ArrayDeque<JSONObject>()
    @Synchronized override fun add(entry: JSONObject) { lines.addFirst(entry); while (lines.size > keep) lines.removeLast() }
    @Synchronized override fun recent(tool: String?, limit: Int) = lines.filter { tool == null || it.optString("tool") == tool }.take(limit)
}

/** So many a minute for each tool, against a program stuck in a loop; not a limit on how much may be read. */
class RateLimit(private val perMinute: Int = 600, private val now: () -> Long = System::currentTimeMillis) {
    private val windows = HashMap<String, Pair<Long, Int>>()

    @Synchronized
    fun allow(tool: String): Boolean {
        val minute = now() / 60_000
        val (at, n) = windows[tool] ?: (minute to 0)
        val count = if (at == minute) n + 1 else 1
        windows[tool] = minute to count
        return count <= perMinute
    }
}

/** A message as tools see it. */
data class McpSms(val id: String, val peer: String, val body: String, val at: Long, val dir: String, val status: String, val numberId: String?)

/** Which messages: newest first, at most [limit]. */
data class SmsQuery(val peer: String? = null, val text: String? = null, val from: Long = 0, val to: Long = Long.MAX_VALUE, val limit: Int = 50)

/** This phone's own data, read when asked; nothing is copied or kept by the MCP side. */
interface McpData {
    /** Battery, each SIM's service, what is not set up, whether the audio bridge runs. */
    fun status(): JSONObject
    /** Each number: number_id, slot, carrier, active, number (when the SIM gives it). */
    fun numbers(): List<JSONObject>
    /** The call log, newest first: id, number, at, duration_s, type, number_id; screen, spoke and call_id for calls the assistant took. */
    fun calls(from: Long, to: Long): List<JSONObject>
    /** The call going on now, or null. */
    fun activeCall(): JSONObject?
    /** What was said on a call the assistant took: seq, who, text, at_ms; null if this phone has none for it. */
    fun transcript(callId: String): List<JSONObject>?
    fun sms(q: SmsQuery): List<McpSms>
    /** Contacts on this phone: id, name, numbers (label, number). */
    fun contacts(): List<JSONObject>
    /** How each number's calls are screened, as the owner set it. */
    fun screeningRules(): JSONObject
}

/** Something on this phone is not ready (a permission taken back, a part not set up): said as it is, with the reason. */
class NotReady(val reason: String) : Exception(reason)

/** Other people's words made safe to hand to a model's client: nothing invisible, nothing endless (GATEWAY_MCP.md 4.5). */
object Untrusted {
    const val MAX = 2000
    private val hidden = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\u2066-\\u2069\\uFEFF]")

    fun clean(s: String): String {
        val t = hidden.replace(s, "")
        return if (t.length > MAX) t.take(MAX) + "…[cut, ${t.length} characters]" else t
    }

    private val codeWord = Regex("验证码|校验码|动态码|动态密码|驗證碼|校驗碼|verification|passcode|one[- ]time|\\bOTP\\b|\\bcode\\b", RegexOption.IGNORE_CASE)
    private val digits = Regex("(?<!\\d)\\d{4,8}(?!\\d)")

    /** A message carrying a sign-in or payment code, judged widely; it will miss some and hide a few that are not. */
    fun looksLikeCode(body: String) = digits.containsMatchIn(body) && codeWord.containsMatchIn(body)
}

/** What happened on this phone that a tool may want to hear of, kept a short while for tools that ask. */
class Events(private val keep: Int = 500, private val now: () -> Long = System::currentTimeMillis) {
    class Event(val seq: Long, val type: String, val cap: Cap, val numberId: String?, val at: Long, val data: JSONObject)

    private val list = ArrayDeque<Event>()
    private var seq = 0L
    private val lock = Object()

    fun add(type: String, cap: Cap, numberId: String?, data: JSONObject) = synchronized(lock) {
        list.addLast(Event(++seq, type, cap, numberId, now(), data))
        while (list.size > keep) list.removeFirst()
        lock.notifyAll()
    }

    val last: Long get() = synchronized(lock) { seq }

    /** Events after [after] that [allowed] lets through, waiting up to [waitMs] for the first. */
    fun wait(after: Long, waitMs: Long, allowed: (Event) -> Boolean): List<Event> = synchronized(lock) {
        val until = System.nanoTime() + waitMs * 1_000_000
        while (true) {
            val got = list.filter { it.seq > after && allowed(it) }
            if (got.isNotEmpty()) return got.take(100)
            val left = (until - System.nanoTime()) / 1_000_000
            if (left <= 0) return emptyList()
            lock.wait(left)
        }
        @Suppress("UNREACHABLE_CODE") emptyList()
    }
}
