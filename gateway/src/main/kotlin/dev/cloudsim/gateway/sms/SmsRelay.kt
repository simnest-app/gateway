package dev.cloudsim.gateway.sms

import dev.cloudsim.gateway.calls.Scheduler
import dev.cloudsim.gateway.calls.Sealer
import dev.cloudsim.gateway.calls.SharingPolicy
import dev.cloudsim.gateway.net.ControlClient
import dev.cloudsim.gateway.net.ServiceException
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * One form per number, so a conversation is the same whether the number came from the network ("+8617…") or was typed
 * ("17…", "0086 17…"): digits and a leading +; mainland mobile numbers get +86 (M1 runs on a mainland SIM). Short codes
 * such as 10086 stay as they are. Phones apply the same rule (Messages.swift).
 */
fun canonicalPeer(raw: String): String {
    val plus = raw.trim().startsWith("+")
    var d = raw.filter { it.isDigit() }
    if (!plus && d.startsWith("0086")) return canonicalPeer("+" + d.removePrefix("00"))
    if (plus) return "+$d"
    if (d.length == 11 && d.startsWith("1")) return "+86$d"
    return d.ifEmpty { raw.trim() }
}

/** State of one segment of a message the gateway sent. */
enum class Part { SENDING, SENT, FAILED, UNKNOWN }

/**
 * One SMS as the gateway keeps it. [seq] orders messages (and starts a member's right); [rev] grows with every change,
 * so a phone's sync cursor also catches status changes of messages it already has. Outgoing ones carry the device that
 * sent them, the command that asked for them and each segment's state.
 */
data class Sms(
    val seq: Long, val id: String, val peer: String, val body: String, val at: Long,
    val dir: String = "in", val sender: String? = null, val commandId: String? = null,
    val segments: List<String> = emptyList(), val parts: List<Part> = emptyList(), val rev: Long = seq,
    /** The gateway number it came in on or went out from (DUAL_SIM.md); null from before there were two. */
    val numberId: String? = null,
) {
    /** Sending until every segment has a result; then sent, failed, partly sent, or unknown. */
    val status: String get() = when {
        dir == "in" -> "received"
        parts.any { it == Part.SENDING } -> "sending"
        parts.all { it == Part.SENT } -> "sent"
        parts.all { it == Part.FAILED } -> "failed"
        parts.any { it == Part.UNKNOWN } -> "unknown"
        else -> "partial"
    }
}

/** Where the gateway keeps SMS: SQLite in the app, a list in tests. */
interface SmsLog {
    fun add(id: String, peer: String, body: String, at: Long): Sms
    fun addOutgoing(id: String, peer: String, body: String, at: Long, sender: String, commandId: String, segments: List<String>): Sms
    /** The same, on gateway number [numberId] (DUAL_SIM.md). */
    fun add(id: String, peer: String, body: String, at: Long, numberId: String?): Sms = add(id, peer, body, at)
    fun addOutgoing(id: String, peer: String, body: String, at: Long, sender: String, commandId: String, segments: List<String>, numberId: String?): Sms =
        addOutgoing(id, peer, body, at, sender, commandId, segments)
    fun update(id: String, parts: List<Part>): Sms?
    fun byId(id: String): Sms?
    fun byCommand(commandId: String): Sms?
    /** Records changed after [rev], oldest change first. */
    fun changedAfter(rev: Long, limit: Int): List<Sms>
    fun lastSeq(): Long
    fun sending(): List<Sms>
    /** Messages with [Sms.at] in [from, to), oldest first (history, SMS_4C.md). */
    fun between(from: Long, to: Long, limit: Int): List<Sms>
    /** When the gateway started keeping messages; the system store covers what came before. */
    fun firstAt(): Long?
    fun cancel(commandId: String)
    fun cancelled(commandId: String): Boolean
}

/** Remembers from which message each family phone may read: its view right counts from when the gateway applied it. */
interface Since {
    fun get(device: String): Long?
    fun put(device: String, seq: Long)
    fun remove(device: String)
    fun devices(): Set<String>
}

/** The system's SMS service: split, send, report each segment; and look in the Sent box when a result is unknown. */
interface SmsRadio {
    fun divide(body: String): List<String>
    /** Sends [segments] of message [id]; [onPart] is called once per segment index with whether it reached the SMSC. */
    fun send(id: String, peer: String, segments: List<Pair<Int, String>>): Boolean
    /** The same, on the SIM of gateway number [numberId]; null is the system's default. */
    fun send(id: String, peer: String, segments: List<Pair<Int, String>>, numberId: String?): Boolean = send(id, peer, segments)
    /** Whether the system's Sent box has [body] to [peer] since [sinceMs]; null if it cannot tell (no READ_SMS). */
    fun inSentBox(peer: String, body: String, sinceMs: Long): Boolean?
    fun inSentBox(peer: String, body: String, sinceMs: Long, numberId: String?): Boolean? = inSentBox(peer, body, sinceMs)
    /** A message reached a final state: as the default SMS app, record it in the system's store. */
    fun persist(sms: Sms) {}
    /** The system store's messages in [from, to), from before the gateway kept its own (READ_SMS). */
    fun systemHistory(from: Long, to: Long, limit: Int): List<Sms> = emptyList()
}

/**
 * Gateway side of receiving (docs/architecture/SMS_4A.md) and sending (SMS_4B.md) SMS. Every message is stored here
 * first; each phone allowed to see it gets its own sealed copy through the service, which sees only ciphertext. Phones
 * send sealed commands; the gateway is the only judge of who may send, who holds a conversation for replying, and what
 * was actually sent: a repeated command never sends twice, a stale one is ignored, and an unknown result is checked in
 * the system's Sent box before anything is sent again. Calls run on one background thread.
 */
class SmsRelay(
    private val client: ControlClient,
    private val log: SmsLog,
    private val since: Since,
    private val sealer: Sealer,
    private val radio: SmsRadio,
    private val scheduler: Scheduler,
    private val owner: () -> String?,
    private val policy: () -> SharingPolicy?,
    private val now: () -> Long = System::currentTimeMillis,
    private val trace: (String) -> Unit = {},
    /** Whether remote access is allowed now (REVOKE_LEASE_5.md); while not, messages are kept here and sent on later. */
    private val remote: () -> Boolean = { true },
) {
    /** Received while remote access was paused, delivered when it resumes. */
    private val held = mutableListOf<String>()

    /** Remote access is allowed again: what arrived meanwhile goes out now, without lock-screen notices. */
    fun onResume() {
        val ids = held.toList().also { held.clear() }
        for (id in ids) log.byId(id)?.let { deliver(audience(it), listOf(it), notify = false) }
        if (ids.isNotEmpty()) trace("sms: ${ids.size} message(s) held during the pause sent on")
    }

    private class Hold(val device: String, var lastEditMs: Long)
    /** Reply occupation per conversation: the gateway number and the other party's number. */
    private val holds = mutableMapOf<String, Hold>()

    private fun conversation(numberId: String?, peer: String) = "${numberId ?: ""}|$peer"

    /** The policy as it applies on gateway number [n] (DUAL_SIM.md); without a number, as a whole. */
    private fun rules(n: String?): SharingPolicy? = policy()?.let { p -> n?.let(p::forNumber) ?: p }

    /** A view right counts from when it was given, number by number once the policy has numbers. */
    private fun sinceKey(device: String, n: String?) = if (n == null || policy()?.numbers.isNullOrEmpty()) device else "$device|$n"

    /** Devices allowed to read on number [n], each with the first sequence number it may see (the owner, everything). */
    private fun readers(n: String?): Map<String, Long> {
        val o = owner() ?: return emptyMap()
        val out = mutableMapOf(o to 0L)
        for (m in rules(n)?.members ?: emptyList()) if (m.smsView) since.get(sinceKey(m.deviceId, n))?.let { out[m.deviceId] = it }
        return out
    }

    private fun senders(n: String?): Set<String> {
        val o = owner() ?: return emptySet()
        return setOf(o) + (rules(n)?.members?.filter { it.smsSend }?.map { it.deviceId } ?: emptyList())
    }

    /** Who may see [sms]: readers of its number from their start, and the device that sent it. */
    private fun audience(sms: Sms): Set<String> {
        val out = readers(sms.numberId).filterValues { sms.seq >= it }.keys.toMutableSet()
        if (sms.dir == "out" && sms.sender != null && sms.sender in senders(sms.numberId)) out += sms.sender
        return out
    }

    /** A newer policy: a member newly allowed to read starts at the next message; one no longer allowed is forgotten. */
    fun onPolicy() {
        val p = policy()
        val numbers: List<String?> = p?.numbers?.takeIf { it.isNotEmpty() } ?: listOf(null)
        val allowed = numbers.flatMap { n -> (rules(n)?.members ?: emptyList()).filter { it.smsView }.map { sinceKey(it.deviceId, n) } }.toSet()
        val next = log.lastSeq() + 1
        for (k in allowed) if (since.get(k) == null) since.put(k, next)
        for (k in since.devices() - allowed) since.remove(k)
        // A holder who lost the send right on that number lets go.
        holds.entries.removeAll { (key, hold) -> hold.device !in senders(key.substringBefore("|").ifEmpty { null }) }
    }

    /** At start: anything still marked as sending was cut off by a restart; check it rather than send it again. */
    fun onStart() {
        for (sms in log.sending()) verify(sms.id)
    }

    /** A message from the system's SMS broadcast (long messages already joined). */
    fun onReceived(peer: String, body: String, at: Long, numberId: String? = null) {
        val sms = log.add(UUID.randomUUID().toString(), if (peer.any { it.isDigit() }) canonicalPeer(peer) else peer, body, at, numberId)
        trace("sms ${sms.seq} received")
        if (!remote()) {
            held += sms.id
            return trace("sms ${sms.seq} held: permissions unconfirmed for 5 minutes")
        }
        deliver(audience(sms), listOf(sms), notify = true)
    }

    /** A phone asks for what it has not seen after its cursor; only messages within its current right. */
    fun onSync(device: String, after: Long) {
        if (!remote()) return trace("sms sync from ${device.take(8)} refused: permissions unconfirmed for 5 minutes")
        val numbers: List<String?> = policy()?.numbers?.takeIf { it.isNotEmpty() }?.plus(null) ?: listOf(null)
        if (numbers.none { readers(it).containsKey(device) }) return trace("sms sync from ${device.take(8)} refused: no view right")
        var cursor = after
        while (true) {
            val batch = log.changedAfter(cursor, 50)
            if (batch.isEmpty()) break
            val visible = batch.filter { m -> readers(m.numberId)[device]?.let { m.seq >= it } == true }
            if (visible.isNotEmpty() && !deliver(setOf(device), visible, notify = false)) break
            cursor = batch.last().rev
        }
        trace("sms sync for ${device.take(8)} after $after done at $cursor")
    }

    /** A sealed command from a phone (SMS_4B.md): send, retry, occupy, release or takeover. */
    fun onCommand(device: String, publicKey: ByteArray, commandId: String, envelope: JSONObject, atMs: Long) {
        if (!remote()) return trace("sms command ${commandId.take(8)} refused: permissions unconfirmed for 5 minutes")
        if (atMs > 0 && now() - atMs > FRESH_MS) return trace("sms command ${commandId.take(8)} is ${(now() - atMs) / 1000}s old, ignored")
        val plain = sealer.open(envelope, "smscmd", publicKey) ?: return trace("sms command ${commandId.take(8)} refused: not sealed by its sender")
        val cmd = runCatching { JSONObject(plain.decodeToString()) }.getOrNull() ?: return
        if (envelope.optString("id") != commandId) return
        val peer = canonicalPeer(cmd.optString("peer"))
        // The gateway number the command is about (DUAL_SIM.md); a retry or cancel goes by its message's number.
        val n = cmd.optString("number_id").ifEmpty { null } ?: cmd.optString("message").ifEmpty { null }?.let { log.byId(it)?.numberId }
        if (device !in senders(n)) return trace("sms command from ${device.take(8)} refused: no send right on ${n?.take(12) ?: "this number"}")
        val conv = conversation(n, peer)
        when (cmd.optString("op")) {
            "send" -> send(device, commandId, peer, cmd.optString("body"), n)
            "retry" -> retry(device, cmd.optString("message"), cmd.optBoolean("confirm_unknown"))
            "occupy" -> occupy(device, peer, n, takeover = false)
            "takeover" -> occupy(device, peer, n, takeover = true)
            "release" -> if (holds[conv]?.device == device) holds.remove(conv)
            "cancel" -> cancel(device, cmd.optString("target"))
        }
    }

    /**
     * Cancelling a send the gateway has not carried out (SMS_4C.md): the target command is marked void, so it is never
     * executed if it arrives later, and the phone is told it is cancelled. One already carried out cannot be undone; the
     * phone gets its real state instead.
     */
    private fun cancel(device: String, target: String) {
        if (target.length < 8) return
        log.byCommand(target)?.let { sms ->
            trace("sms cancel ${target.take(8)}: already ${sms.status}")
            return deliver(audience(sms), listOf(sms), notify = false).let {}
        }
        log.cancel(target)
        trace("sms cancel ${target.take(8)}: cancelled before sending")
        controlTarget(device, "cancelled", target)
    }

    /**
     * History for [device] in [from, to) (SMS_4C.md): the owner any range; a family member only within the range the
     * owner granted, and only with the view right. Messages the gateway kept, plus the system store from before that.
     */
    fun onHistory(device: String, from: Long, to: Long, numberId: String? = null) {
        if (!remote()) return trace("sms history for ${device.take(8)} refused: permissions unconfirmed for 5 minutes")
        val o = owner() ?: return
        val (lo, hi) = if (device == o) from to to else {
            val m = rules(numberId)?.members?.find { it.deviceId == device && it.smsView && it.historyTo > it.historyFrom }
                ?: return trace("sms history for ${device.take(8)} refused: no history right")
            maxOf(from, m.historyFrom) to minOf(to, m.historyTo)
        }
        if (hi <= lo) return trace("sms history for ${device.take(8)}: nothing in range")
        val start = log.firstAt() ?: Long.MAX_VALUE
        // On two numbers, history is per number: only messages on the one asked for.
        val onIt = { m: Sms -> numberId == null || m.numberId == null || m.numberId == numberId }
        val kept = log.between(lo, hi, HISTORY_MAX).filter(onIt)
        // The system store also holds the messages the gateway kept before it became the default SMS app; those are
        // the same message (same number and text, moments apart), so the gateway's own record wins.
        val older = (if (lo < start) radio.systemHistory(lo, minOf(hi, start), HISTORY_MAX - kept.size) else emptyList())
            .filter(onIt).filter { o -> kept.none { it.peer == o.peer && it.body == o.body && kotlin.math.abs(it.at - o.at) < 60_000 } }
        val all = (older + kept).take(HISTORY_MAX)
        all.chunked(50).forEach { deliver(setOf(device), it, notify = false) }
        trace("sms history for ${device.take(8)}: ${all.size} message(s), ${older.size} from the system store before ${start}")
    }

    private fun controlTarget(device: String, op: String, target: String) {
        val id = UUID.randomUUID().toString()
        val plain = JSONObject().put("op", op).put("target", target).put("at", now()).toString().toByteArray()
        val env = runCatching { sealer.seal("smsctl", device, id, plain) }.getOrNull() ?: return
        post(JSONArray().put(JSONObject().put("to", device).put("envelope", env.toString()).put("notify", false)))
    }

    private fun held(conv: String): Hold? = holds[conv]?.takeIf { now() - it.lastEditMs < HOLD_MS } ?: run { holds.remove(conv); null }

    private fun occupy(device: String, peer: String, n: String?, takeover: Boolean) {
        if (!PEER.matches(peer)) return
        val conv = conversation(n, peer)
        val hold = held(conv)
        if (hold != null && hold.device != device && !takeover) {
            trace("sms reply hold: ${device.take(8)} refused, held by another")
            return control(device, "busy", peer, n)
        }
        if (hold != null && hold.device != device) {
            trace("sms reply hold: taken over by ${device.take(8)}")
            control(hold.device, "taken", peer, n)
        } else if (hold == null) trace("sms reply hold: ${device.take(8)}")
        holds[conv] = Hold(device, now())
        control(device, "occupied", peer, n)
    }

    private fun send(device: String, commandId: String, peer: String, body: String, n: String?) {
        // The same command again (a retried request, a replay): report what happened, never send twice.
        log.byCommand(commandId)?.let { return deliver(audience(it), listOf(it), notify = false).let {} }
        if (log.cancelled(commandId)) {
            trace("sms command ${commandId.take(8)} was cancelled, not sent")
            return controlTarget(device, "cancelled", commandId)
        }
        if (!PEER.matches(peer) || body.isEmpty() || body.length > MAX_BODY) return control(device, "invalid", peer, n)
        val conv = conversation(n, peer)
        val hold = held(conv)
        if (hold != null && hold.device != device) return control(device, "busy", peer, n)
        val segments = radio.divide(body)
        val sms = log.addOutgoing(UUID.randomUUID().toString(), peer, body, now(), device, commandId, segments, n)
        holds.remove(conv)
        trace("sms ${sms.seq} sending in ${segments.size} part(s) for ${device.take(8)}")
        deliver(audience(sms), listOf(sms), notify = false)
        transmit(sms, segments.indices.toList())
    }

    private fun retry(device: String, id: String, confirmUnknown: Boolean) {
        val sms = log.byId(id) ?: return
        if (sms.dir != "out" || sms.sender != device) return
        val again = sms.parts.indices.filter { sms.parts[it] == Part.FAILED || (confirmUnknown && sms.parts[it] == Part.UNKNOWN) }
        if (again.isEmpty()) return deliver(audience(sms), listOf(sms), notify = false).let {}
        val parts = sms.parts.toMutableList().also { p -> again.forEach { p[it] = Part.SENDING } }
        log.update(id, parts)?.let { deliver(audience(it), listOf(it), notify = false) }
        trace("sms ${sms.seq} resending part(s) $again")
        transmit(sms, again)
    }

    private fun transmit(sms: Sms, indices: List<Int>) {
        if (!radio.send(sms.id, sms.peer, indices.map { it to sms.segments[it] }, sms.numberId)) {
            indices.forEach { onPart(sms.id, it, false) }
            return
        }
        scheduler.after(RESULT_MS) { verify(sms.id) }
    }

    /** The system's report for one segment. */
    fun onPart(id: String, index: Int, ok: Boolean) {
        val sms = log.byId(id) ?: return
        // A report that comes after the Sent box check still tells the truth, so it replaces "unknown".
        if (index !in sms.parts.indices || (sms.parts[index] != Part.SENDING && sms.parts[index] != Part.UNKNOWN)) return
        val parts = sms.parts.toMutableList().also { it[index] = if (ok) Part.SENT else Part.FAILED }
        val updated = log.update(id, parts) ?: return
        if (updated.status != "sending") {
            trace("sms ${sms.seq} ${updated.status}")
            radio.persist(updated)
        }
        deliver(audience(updated), listOf(updated), notify = false)
    }

    /** Segments with no report in time: look in the Sent box before calling them unknown; never send them again here. */
    private fun verify(id: String) {
        val sms = log.byId(id) ?: return
        if (sms.parts.none { it == Part.SENDING }) return
        val found = radio.inSentBox(sms.peer, sms.body, sms.at - 10_000, sms.numberId)
        val parts = sms.parts.map { if (it == Part.SENDING) if (found == true) Part.SENT else Part.UNKNOWN else it }
        trace("sms ${sms.seq} had no report; sent box says ${found ?: "nothing (no permission)"}")
        log.update(id, parts)?.let { deliver(audience(it), listOf(it), notify = false) }
    }

    private fun record(m: Sms, to: String) = JSONObject().put("id", m.id).put("seq", m.seq).put("rev", m.rev).put("peer", m.peer).put("body", m.body)
        .put("at", m.at).put("dir", m.dir).put("status", m.status).put("mine", m.sender == to).put("command", if (m.sender == to) m.commandId ?: "" else "")
        .put("parts", JSONArray(m.parts.map { it.name.lowercase() })).put("number_id", m.numberId ?: "")

    private fun control(device: String, op: String, peer: String, n: String? = null) {
        val id = UUID.randomUUID().toString()
        val plain = JSONObject().put("op", op).put("peer", peer).put("number_id", n ?: "").put("at", now()).toString().toByteArray()
        val env = runCatching { sealer.seal("smsctl", device, id, plain) }.getOrNull() ?: return
        post(org.json.JSONArray().put(JSONObject().put("to", device).put("envelope", env.toString()).put("notify", false)))
    }

    private fun deliver(to: Set<String>, messages: List<Sms>, notify: Boolean): Boolean {
        val items = JSONArray()
        for (d in to) for (m in messages) {
            val env = runCatching { sealer.seal("sms", d, m.id, record(m, d).toString().toByteArray()) }.getOrNull()
            if (env == null) { trace("sms ${m.seq}: ${d.take(8)} has no recipient key yet"); continue }
            // Only a received message makes a lock-screen notice, and not for catching up.
            items.put(JSONObject().put("to", d).put("envelope", env.toString()).put("notify", notify && m.dir == "in").put("number_id", m.numberId ?: ""))
        }
        return items.length() == 0 || post(items)
    }

    private fun post(items: JSONArray): Boolean = try {
        client.call("POST", "/v1/sms/deliver", JSONObject().put("items", items))
        true
    } catch (e: ServiceException) {
        trace("sms deliver failed: ${e.code} ${e.message}")
        false
    }

    companion object {
        /** A command older than this when it arrives is not acted on. */
        const val FRESH_MS = 60_000L
        /** How long a segment may go without a system report before the Sent box is checked. */
        const val RESULT_MS = 60_000L
        /** Reply occupation lapses after this long without editing (CURRENT_BASELINE.md: 2 minutes). */
        const val HOLD_MS = 120_000L
        const val MAX_BODY = 2_000
        /** At most this many messages per history request. */
        const val HISTORY_MAX = 500
        private val PEER = Regex("^\\+?[0-9]{3,20}$")
    }
}
