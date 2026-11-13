package dev.cloudsim.gateway.records

import dev.cloudsim.gateway.calls.Sealer
import dev.cloudsim.gateway.calls.SharingPolicy
import dev.cloudsim.gateway.net.ControlClient
import org.json.JSONArray
import org.json.JSONObject

/** What this phone holds, read for a phone that asked; the system providers in the app, fixed lists in tests. */
interface RecordSource {
    /** Each contact: {name, numbers: [{label, number}]}. */
    fun contacts(): List<JSONObject>
    /** Calls in [from, to) ms, newest first: {number, at, duration_s, type, number_id?}. */
    fun calls(from: Long, to: Long): List<JSONObject>
    /** Adds one new contact on this phone (IOS_UI_REDESIGN.md 12.4). */
    fun addContact(contact: NewContact): Unit = throw UnsupportedOperationException()
    /**
     * Adds numbers to a contact this phone already has (the user's 2026-11-08 decision): only numbers, the name and the
     * numbers there stay; one it already has is not added again. Never removes anything. [ContactGone] when it is not there.
     */
    fun addNumbers(contactId: String, numbers: List<ContactNumber>): Unit = throw UnsupportedOperationException()
}

/** One number to save: [type] mobile, iphone, home, work, main, other or custom (then [label] is the user's own text). */
data class ContactNumber(val type: String, val label: String, val number: String)

/** A new contact as the phone sends it: family and given name and company (any may be empty), and the whole name. */
data class NewContact(val name: String, val family: String, val given: String, val company: String, val numbers: List<ContactNumber>)

/** The contact to add numbers to is not on this phone (any more). */
class ContactGone : Exception()

/**
 * The gateway's side of contacts and the call log (IOS_UI_REDESIGN.md 4, 9.2–9.3): a phone asks through the service,
 * this checks the owner-signed policy again (contacts: the owner and members with the contacts right; the call log:
 * the owner only), reads, and sends the data sealed for that phone alone, in parts the service can carry.
 */
class RecordsRelay(
    private val client: ControlClient,
    private val sealer: Sealer,
    private val owner: () -> String?,
    private val policy: () -> SharingPolicy?,
    private val source: RecordSource,
    private val remote: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    fun onRequest(data: JSONObject) {
        val kind = data.optString("kind")
        val to = data.optString("device_id")
        val requestId = data.optString("request_id")
        if (requestId.length !in 8..64) return
        if (!remote()) return log("$kind request from ${to.take(8)} refused: permissions unconfirmed for 5 minutes")
        val o = owner() ?: return
        val allowed = when (kind) {
            CONTACTS, CONTACT_ADD -> to in (policy()?.contactReaders(o) ?: listOf(o))
            CALL_LOG -> to == o
            else -> false
        }
        if (!allowed) return log("$kind request from ${to.take(8)} refused: no right")
        if (kind == CONTACT_ADD) return add(data, to, requestId)
        var unavailable: String? = null
        val items = runCatching {
            if (kind == CONTACTS) source.contacts() else source.calls(data.optLong("from"), data.optLong("to")).take(MAX_CALLS)
        }.getOrElse {
            // Say why rather than leave the phone waiting: the permission not allowed on this phone, or the read failed.
            unavailable = if (it is MissingPermission || it is SecurityException) NO_PERMISSION else UNREADABLE
            log("$kind could not be read: ${it.javaClass.simpleName}, told ${to.take(8)}")
            emptyList()
        }
        val parts = split(items)
        parts.forEachIndexed { i, part ->
            val plain = JSONObject().put("kind", kind).put("request_id", requestId).put("index", i).put("count", parts.size)
                .put("items", JSONArray(part)).apply { if (i == parts.lastIndex && truncated) put("truncated", true) }
                .apply { unavailable?.let { put("unavailable", it) } }
            val env = sealer.seal("records", to, "$requestId-$i", plain.toString().toByteArray()) ?: return log("no recipient key for ${to.take(8)}")
            runCatching {
                client.call("POST", "/v1/records/deliver", JSONObject().put("to", to).put("kind", kind).put("request_id", requestId)
                    .put("index", i).put("count", parts.size).put("envelope", env.toString()))
            }.onFailure { return log("$kind part $i for ${to.take(8)} not delivered: ${it.message}") }
        }
        if (unavailable != null) return
        // Counts only: never names or numbers.
        log("$kind for ${to.take(8)}: ${items.size} item(s) in ${parts.size} part(s)")
    }

    /** Request ids already written, so a repeat of the same request adds nothing twice. */
    private val added = LinkedHashSet<String>()

    /**
     * A phone allowed to see the contacts asks to add one (IOS_UI_REDESIGN.md 12.4): sealed by that phone for this
     * gateway, with the request id inside, at most 2 minutes old. Answers with one sealed part: done, or why not.
     */
    private fun add(data: JSONObject, to: String, requestId: String) {
        val at = data.optLong("_at_ms")
        if (at > 0 && now() - at > FRESH_MS) return log("contact.add ${requestId.take(12)} is ${(now() - at) / 1000}s old, ignored")
        val key = ControlClient.unb64(data.optString("public_key")) ?: return log("contact.add from ${to.take(8)} refused: no key")
        val env = runCatching { JSONObject(data.optString("envelope")) }.getOrNull() ?: return log("contact.add from ${to.take(8)} refused: no envelope")
        val plain = sealer.open(env, "contactadd", key)?.let { runCatching { JSONObject(it.decodeToString()) }.getOrNull() }
            ?: return log("contact.add from ${to.take(8)} refused: not sealed by its sender")
        if (plain.optString("request_id") != requestId || env.optString("id") != requestId) return log("contact.add from ${to.take(8)} refused: id mismatch")
        fun field(key: String) = plain.optString(key).trim().take(MAX_NAME)
        val list = plain.optJSONArray("numbers") ?: JSONArray()
        val numbers = (0 until minOf(list.length(), MAX_NUMBERS)).mapNotNull { i ->
            val n = list.optJSONObject(i) ?: return@mapNotNull null
            val number = n.optString("number").trim().take(40)
            val label = n.optString("label").trim().take(30)
            // A phone before 0.30 sends the label only ("手机" for a mobile).
            val type = n.optString("type").takeIf { it in TYPES } ?: if (label.isEmpty() || label == "手机" || label.equals("mobile", true)) "mobile" else "custom"
            if (number.none(Char::isDigit)) null else ContactNumber(type, label, number)
        }
        val existing = plain.optString("contact_id").trim().take(40)
        val contact = NewContact(field("name"), field("family"), field("given"), field("company"), numbers)
        val named = listOf(contact.name, contact.family, contact.given, contact.company).any { it.isNotEmpty() }
        if (numbers.isEmpty() || (existing.isEmpty() && !named)) return log("contact.add from ${to.take(8)} refused: empty")
        var unavailable: String? = null
        if (requestId !in added) {
            runCatching { if (existing.isEmpty()) source.addContact(contact) else source.addNumbers(existing, numbers) }.onSuccess {
                added += requestId
                if (added.size > 64) added.remove(added.first())
            }.onFailure {
                unavailable = when (it) {
                    is MissingPermission, is SecurityException -> NO_PERMISSION
                    is ContactGone -> NOT_FOUND
                    else -> UNWRITABLE
                }
                log("contact.add could not be written: ${it.javaClass.simpleName}, told ${to.take(8)}")
            }
        }
        val reply = JSONObject().put("kind", CONTACT_ADD).put("request_id", requestId).put("index", 0).put("count", 1)
            .put("items", JSONArray()).apply { unavailable?.let { put("unavailable", it) } }
        val sealed = sealer.seal("records", to, "$requestId-0", reply.toString().toByteArray()) ?: return log("no recipient key for ${to.take(8)}")
        runCatching {
            client.call("POST", "/v1/records/deliver", JSONObject().put("to", to).put("kind", CONTACT_ADD).put("request_id", requestId)
                .put("index", 0).put("count", 1).put("envelope", sealed.toString()))
        }.onFailure { return log("contact.add answer for ${to.take(8)} not delivered: ${it.message}") }
        // Never the name or the number.
        if (unavailable == null) log("contact.add for ${to.take(8)}: ${if (existing.isEmpty()) "added" else "numbers added to a contact"}")
    }

    private var truncated = false

    /** Groups items so each sealed part stays under the service's limit; at most [MAX_PARTS] parts. */
    private fun split(items: List<JSONObject>): List<List<JSONObject>> {
        truncated = false
        val out = mutableListOf<MutableList<JSONObject>>(mutableListOf())
        var size = 0
        for (item in items) {
            val n = item.toString().toByteArray().size + 1
            if (size + n > PART_BYTES && out.last().isNotEmpty()) {
                if (out.size == MAX_PARTS) { truncated = true; break }
                out += mutableListOf<JSONObject>()
                size = 0
            }
            out.last() += item
            size += n
        }
        return out
    }

    companion object {
        const val CONTACTS = "contacts"
        /** Why nothing came: the system permission is not allowed on this phone, or reading failed. */
        const val NO_PERMISSION = "no_permission"
        const val UNREADABLE = "unreadable"
        const val CALL_LOG = "calllog"
        const val CONTACT_ADD = "contact.add"
        /** The phone's contacts provider refused the new contact. */
        const val UNWRITABLE = "unwritable"
        /** The contact to add numbers to is not on this phone. */
        const val NOT_FOUND = "not_found"
        val TYPES = setOf("mobile", "iphone", "home", "work", "main", "other", "custom")
        const val FRESH_MS = 120_000L
        const val MAX_NAME = 100
        const val MAX_NUMBERS = 5
        /** Plaintext per part: sealed and base64'd it stays well under the service's 48 KiB. */
        const val PART_BYTES = 24_000
        const val MAX_PARTS = 64
        const val MAX_CALLS = 2_000
    }
}

/** A record source without the system permission it needs. */
class MissingPermission(what: String) : Exception(what)
