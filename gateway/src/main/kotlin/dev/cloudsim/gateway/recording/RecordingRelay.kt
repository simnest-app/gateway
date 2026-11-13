package dev.cloudsim.gateway.recording

import dev.cloudsim.gateway.calls.RecRights
import dev.cloudsim.gateway.calls.Sealer
import dev.cloudsim.gateway.net.ControlClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.security.SecureRandom
import java.util.Base64

/** Lets the self-test's data connection carry a recording instead (RECORDING_V7.md 5). */
fun interface FileTickets {
    /** True when [probeId] is a download ticket this gateway gave [device] a moment ago; it is used up. */
    fun take(probeId: String, device: String): Boolean
}

/**
 * The phones' side of recordings (RECORDING_V7.md 5 and 15). Every request is sealed by the phone for this gateway,
 * with its request id inside and at most 2 minutes old, and goes through the service's "recording" records kind; the
 * answer is sealed for that phone alone.
 *
 * The owner may do everything, also on a call it is not in (op "live" shows it). A family phone, by its rights on the
 * call's number in the owner's signed policy: start and stop only while it is in the call; list and download only
 * what [RecordingAccess] gives it, cut to the stretches it was in unless it has the number's whole recordings.
 * Settings, quotas, locking and deleting stay the owner's, and so does whether a number tells the far party before
 * recording (RECORDING_NOTICE.md 5): turning the notice off is only taken with the owner's acknowledgement. Every phone in the call is told when recording starts or
 * stops. Downloads go over a data connection the phone opens with a one-time ticket, never through the service.
 */
class RecordingRelay(
    private val client: ControlClient,
    private val sealer: Sealer,
    private val owner: () -> String?,
    private val recorder: Recorder,
    private val settings: RecordingSettings,
    /** The gateway's numbers now, so each shows even before its first recording. */
    private val numbers: () -> List<String>,
    private val participants: () -> Set<String>,
    private val busy: () -> Boolean,
    private val freeSpace: () -> Long,
    private val bridge: (JSONObject) -> Boolean,
    private val remote: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    /** A family phone's recording rights on a number, from the current policy; none for a phone not in it. */
    private val rights: (device: String, numberId: String?) -> RecRights = { _, _ -> RecRights() },
) : FileTickets {
    private val random = SecureRandom()
    /** [ranges]: what of the recording this download carries ([RecordingAccess.WHOLE] for all of it). */
    private class Ticket(val device: String, val recordingId: String, val expires: Long, val ranges: List<LongRange>?)
    private val tickets = mutableMapOf<String, Ticket>()
    /** A download under way; a cut one keeps its stream open, as finding a place in it means reading up to there. */
    private class Download(val recordingId: String, val ranges: List<LongRange>?) {
        var stream: InputStream? = null
        var at = 0L
    }
    /** Downloads under way, by probe ID. */
    private val downloads = mutableMapOf<String, Download>()
    /** Request ids already carried out, so a repeat changes nothing twice. */
    private val done = LinkedHashSet<String>()
    private var stateSeq = 0

    fun onRequest(data: JSONObject) {
        val from = data.optString("device_id")
        val requestId = data.optString("request_id")
        if (requestId.length !in 8..64) return
        if (!remote()) return log("recording request from ${from.take(8)} refused: permissions unconfirmed for 5 minutes")
        val at = data.optLong("_at_ms")
        if (at > 0 && now() - at > FRESH_MS) return log("recording request ${requestId.take(12)} is ${(now() - at) / 1000}s old, ignored")
        val key = ControlClient.unb64(data.optString("public_key")) ?: return log("recording request from ${from.take(8)} refused: no key")
        val env = runCatching { JSONObject(data.optString("envelope")) }.getOrNull() ?: return log("recording request from ${from.take(8)} refused: no envelope")
        val ask = sealer.open(env, SEAL_KIND, key)?.let { runCatching { JSONObject(it.decodeToString()) }.getOrNull() }
            ?: return log("recording request from ${from.take(8)} refused: not sealed by its sender")
        if (ask.optString("request_id") != requestId || env.optString("id") != requestId) return log("recording request from ${from.take(8)} refused: id mismatch")
        val op = ask.optString("op")
        val isOwner = from == owner()
        val allowed = isOwner || when (op) {
            "state" -> from in participants()
            // Only while in the call, and with the right on its number.
            "control" -> from in participants() && recorder.isLive(ask.optString("call_id")) && rights(from, recorder.liveNumber(ask.optString("call_id"))).control
            // What the phone may get is worked out per recording.
            "list", "fetch" -> true
            else -> false
        }
        if (!allowed) return answer(from, requestId, error = "no_permission").also { log("recording $op from ${from.take(8)} refused: no right") }
        if (requestId in done && op != "state" && op != "status" && op != "list") return log("recording $op ${requestId.take(12)} repeated, ignored")
        val result = runCatching { handle(op, ask, from, isOwner) }.getOrElse {
            val code = (it as? Recorder.Refused)?.code ?: "failed"
            if (it !is Recorder.Refused) log("recording $op failed: ${it.javaClass.simpleName}")
            return answer(from, requestId, error = code)
        }
        done += requestId
        if (done.size > 128) done.remove(done.first())
        answer(from, requestId, items = result)
        log("recording $op for ${from.take(8)}: ${result.size} item(s)")
    }

    /** What [device] may get of [r]: the owner all of it, a family phone by [RecordingAccess]. */
    private fun access(r: Recording, device: String, isOwner: Boolean): List<LongRange>? =
        if (isOwner) RecordingAccess.WHOLE else RecordingAccess.ranges(r, device, rights(device, r.numberId))

    /** A recording as [device] sees it: a family phone sees its part's length and size, and what it may do with it. */
    private fun itemFor(r: Recording, device: String, isOwner: Boolean, ranges: List<LongRange>?): JSONObject {
        val o = r.toJson()
        if (isOwner) return o.put("may_play", true).put("may_export", true)
        val rr = rights(device, r.numberId)
        val ms = RecordingAccess.length(r, ranges)
        // The exact size comes with the download; here a share of the whole, by time.
        val bytes = if (ranges == null || r.audioMs <= 0) r.plainBytes else r.plainBytes * ms / r.audioMs
        return o.put("audio_ms", ms).put("bytes", bytes).put("part", ranges != null).put("locked", false)
            .put("may_play", rr.play).put("may_export", rr.export)
    }

    private fun handle(op: String, ask: JSONObject, from: String, isOwner: Boolean): List<JSONObject> {
        fun number() = ask.optString("number_id").ifEmpty { null }
        return when (op) {
            "status" -> listOf(status())
            "preview" -> listOf(recorder.preview(number(), quota(ask)))
            "set" -> {
                val n = number()
                val old = settings.of(n)
                val quota = if (ask.has("quota_mb")) quota(ask) else old.quotaMb
                if (quota < old.quotaMb && !ask.optBoolean("confirm")) {
                    val p = recorder.preview(n, quota)
                    if (p.optInt("count") > 0 || !p.optBoolean("reachable")) return listOf(p.put("needs_confirm", true))
                }
                var notice = old.notice
                if (ask.has("notice")) {
                    val tell = ask.optBoolean("notice")
                    // Recording without telling the far party is the owner's to choose, knowingly (RECORDING_NOTICE.md 6).
                    if (!tell && settings.tells(n) && !ask.optBoolean("ack")) throw Recorder.Refused("needs_ack")
                    notice = tell
                }
                var lang = old.noticeLang
                if (ask.has("notice_lang")) {
                    lang = ask.optString("notice_lang")
                    if (lang !in RecordingSettings.NOTICE_LANGS) throw Recorder.Refused("bad_language")
                }
                settings.set(n, RecordingSettings.Number(if (ask.has("auto")) ask.optBoolean("auto") else old.auto, quota, notice, lang))
                val cleaned = recorder.keepQuota(n, null)
                log("recording settings for ${n?.take(12) ?: "the default number"}: auto=${settings.of(n).auto} quota=${quota}MB notice=${settings.tells(n)} in ${settings.language(n)}, cleaned $cleaned")
                listOf(status())
            }
            "control" -> listOf(recorder.control(ask.optString("call_id"), ask.optBoolean("on")).getOrThrow())
            "state" -> listOf((recorder.state(ask.optString("call_id")) ?: throw Recorder.Refused("no_call")).also { mark(it, from) })
            "live" -> listOfNotNull(recorder.current()?.also { mark(it, from) })
            "list" -> {
                val to = ask.optLong("to").takeIf { it > 0 } ?: Long.MAX_VALUE
                recorder.list(ask.optLong("from"), to).mapNotNull { r ->
                    if (isOwner) return@mapNotNull itemFor(r, from, true, null)
                    // A family phone sees only recordings it may get something of; kept-only records (cleaned, deleted) not at all.
                    val ranges = access(r, from, false)
                    if (ranges != null && ranges.isEmpty()) null else itemFor(r, from, false, ranges)
                }
            }
            "lock" -> listOf((recorder.lock(ask.optString("id"), ask.optBoolean("locked")) ?: throw Recorder.Refused("not_found")).toJson())
            "delete" -> listOf(recorder.delete(ask.optString("id")).getOrThrow().toJson())
            "fetch" -> {
                if (busy()) throw Recorder.Refused("busy")
                val r = recorder.get(ask.optString("id"))?.takeIf { it.hasAudio } ?: throw Recorder.Refused("no_audio")
                // Checked again now: rights may have changed since the list.
                val ranges = access(r, from, isOwner)
                if (ranges != null && ranges.isEmpty()) throw Recorder.Refused("no_permission")
                // A cut one's size is known only by reading it through once.
                val bytes = if (ranges == null) r.plainBytes else
                    recorder.open(r.id, 0)?.let { ClippedAdts.size(it) { i -> RecordingAccess.keeps(ranges, i) } } ?: throw Recorder.Refused("no_audio")
                // Lowercase hex, as the service wants a probe ID.
                val ticket = ByteArray(18).also(random::nextBytes).joinToString("") { "%02x".format(it) }
                synchronized(tickets) {
                    tickets.entries.removeAll { it.value.expires < now() }
                    tickets[ticket] = Ticket(from, r.id, now() + TICKET_MS, ranges)
                }
                listOf(JSONObject().put("ticket", ticket).put("bytes", bytes).put("audio_ms", RecordingAccess.length(r, ranges)).put("id", r.id)
                    .put("part", ranges != null))
            }
            else -> throw Recorder.Refused("bad_request")
        }
    }

    private fun quota(ask: JSONObject): Int = ask.optInt("quota_mb").also {
        if (it !in RecordingSettings.MIN_QUOTA_MB..RecordingSettings.MAX_QUOTA_MB) throw Recorder.Refused("bad_request")
    }

    private fun status(): JSONObject {
        val ids = (numbers() + settings.all().keys.filter { it.isNotEmpty() }).distinct()
        val list = JSONArray(ids.map { id ->
            val s = settings.of(id)
            val (count, bytes) = recorder.usage(id)
            JSONObject().put("number_id", id).put("auto", s.auto).put("quota_mb", s.quotaMb).put("count", count).put("bytes", bytes).put("notice", settings.tells(id))
                .put("notice_lang", settings.language(id))
        })
        return JSONObject().put("op", "status").put("numbers", list).put("free_bytes", freeSpace()).put("reserve_bytes", RecordingSettings.RESERVE_BYTES)
            .put("notice_langs", JSONArray(RecordingSettings.NOTICE_LANGS))
    }

    /** Whether [device] may start and stop the recording of the call [state] is about: told with the state. */
    private fun mark(state: JSONObject, device: String) {
        val callId = state.optString("call_id")
        val may = device == owner() || (device in participants() && rights(device, recorder.liveNumber(callId)).control)
        state.put("can_control", may)
    }

    /** The current call's recording started or stopped: each phone in it is told, sealed for it alone. */
    fun broadcast(state: JSONObject) {
        val to = participants()
        val id = "recstate-${state.optString("call_id").take(8)}-${synchronized(this) { ++stateSeq }}"
        for (device in to) answer(device, id, items = listOf(JSONObject(state.toString()).also { mark(it, device) }))
    }

    override fun take(probeId: String, device: String): Boolean {
        val t = synchronized(tickets) { tickets.remove(probeId) } ?: return false
        if (t.device != device || t.expires < now()) return false
        synchronized(downloads) { downloads[probeId] = Download(t.recordingId, t.ranges) }
        log("recording ${t.recordingId.take(8)} download opened for ${device.take(8)}")
        return true
    }

    /** The bridge wants the next piece of a download: read it from the encrypted parts and hand it over. */
    fun onFileNeed(m: JSONObject) {
        val probe = m.optString("probe_id")
        val d = synchronized(downloads) { downloads[probe] } ?: return
        val id = d.recordingId
        val offset = m.optLong("offset")
        val r = recorder.get(id)
        val reply = JSONObject().put("type", "file_chunk").put("probe_id", probe).put("offset", offset)
        fun gone() {
            bridge(reply.put("error", "no_audio"))
            synchronized(downloads) { downloads.remove(probe) }?.stream?.let { runCatching { it.close() } }
        }
        if (r == null || offset > r.plainBytes) return gone()
        val buf = ByteArray(CHUNK)
        var n = 0
        val ranges = d.ranges
        if (ranges == null) {
            val stream = recorder.open(id, offset) ?: return gone()
            stream.use { s -> while (n < CHUNK) { val k = s.read(buf, n, CHUNK - n); if (k < 0) break; n += k } }
        } else {
            // A cut stream: carried on from where the last piece ended; a resumed download reads up to its place again.
            if (d.stream == null || d.at != offset) {
                d.stream?.let { runCatching { it.close() } }
                val source = recorder.open(id, 0) ?: return gone()
                val s = ClippedAdts(source) { i -> RecordingAccess.keeps(ranges, i) }
                var skip = offset
                val scratch = ByteArray(16 * 1024)
                while (skip > 0) { val k = s.read(scratch, 0, minOf(scratch.size.toLong(), skip).toInt()); if (k < 0) break; skip -= k }
                d.stream = s
                d.at = offset - skip
            }
            val s = d.stream!!
            while (n < CHUNK) { val k = s.read(buf, n, CHUNK - n); if (k < 0) break; n += k }
            d.at += n
        }
        val last = n < CHUNK || (ranges == null && offset + n >= r.plainBytes)
        bridge(reply.put("data", Base64.getEncoder().encodeToString(buf.copyOf(n))).put("last", last))
        if (last) synchronized(downloads) { downloads.remove(probe) }.also {
            it?.stream?.let { st -> runCatching { st.close() } }
            log("recording ${id.take(8)} handed over to ${probe.take(8)}${if (ranges != null) " (cut to ${ranges.size} stretch(es))" else ""}")
        }
    }

    private fun answer(to: String, requestId: String, items: List<JSONObject> = emptyList(), error: String? = null) {
        val parts = if (items.isEmpty()) listOf(emptyList()) else items.chunked(PER_PART)
        parts.forEachIndexed { i, part ->
            val plain = JSONObject().put("kind", KIND).put("request_id", requestId).put("index", i).put("count", parts.size).put("items", JSONArray(part))
                .apply { error?.let { put("unavailable", it) } }
            val env = sealer.seal("records", to, "$requestId-$i", plain.toString().toByteArray()) ?: return log("no recipient key for ${to.take(8)}")
            runCatching {
                client.call("POST", "/v1/records/deliver", JSONObject().put("to", to).put("kind", KIND).put("request_id", requestId)
                    .put("index", i).put("count", parts.size).put("envelope", env.toString()))
            }.onFailure { return log("recording answer for ${to.take(8)} not delivered: ${it.message}") }
        }
    }

    companion object {
        const val KIND = "recording"
        const val SEAL_KIND = "recording"
        const val FRESH_MS = 120_000L
        const val TICKET_MS = 120_000L
        /** Raw bytes a chunk carries to the bridge: base64 on one line of about 64 KB. */
        const val CHUNK = 48 * 1024
        /** List items a sealed part holds, well within the service's 48 KB a part. */
        const val PER_PART = 80
    }
}
