package dev.cloudsim.gateway.recording

import dev.cloudsim.gateway.calls.CallWatcher
import dev.cloudsim.gateway.calls.MediaBridge
import org.json.JSONObject
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.Base64
import java.util.UUID

/**
 * Records the call phones talk in, as RECORDING_V7.md says (7a): off unless the number records automatically or
 * someone allowed starts it; started and stopped as the last request says; a stop is kept for the rest of the call.
 * The audio bridge hands over the two sides as 16 kHz stereo; here they are encoded, encrypted and written in parts of
 * about a minute, and each number's quota and the phone's free space are kept. A recording that cannot go on stops;
 * the call never does.
 *
 * Before a call's first recording the far party is told (RECORDING_NOTICE.md), unless the number's notice is off or
 * the screening assistant answered the call: the notice clip goes to the bridge, and the recording starts only once
 * the bridge says it was played. A notice that cannot be played means no recording, never a silent one. Once told, a
 * call is not told again.
 */
class Recorder(
    private val store: RecordingStore,
    private val vault: Vault,
    private val encoder: () -> AacEncoder,
    private val settings: RecordingSettings,
    private val freeSpace: () -> Long,
    private val bridge: MediaBridge,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    /** A recording started or stopped in the current call: tell the phones in it. */
    private val onState: (JSONObject) -> Unit = {},
    /** The family phones talking in the call now (the owner left out): kept with the recording, so each gets its part. */
    private val participants: () -> Set<String> = { emptySet() },
    /** The notice clip in a language of [RecordingSettings.NOTICE_LANGS], 16 kHz mono; null when it cannot be read. */
    private val notice: (String) -> ShortArray? = { null },
    /** Runs a task on this recorder's thread after a delay; the returned function cancels it. */
    private val after: (delayMs: Long, task: () -> Unit) -> () -> Unit = { _, _ -> {} },
) : CallWatcher {
    /** The call being recorded, or that may be, from connected until over. */
    private class Live(val callId: String, val numberId: String?, val peer: String?, val outgoing: Boolean) {
        var want = false
        /** Why the recording is on (auto, manual) or off (stopped, no_space, failed, media). */
        var reason = ""
        var since = 0L
        var rec: Recording? = null
        var enc: AacEncoder? = null
        var writer: PartWriter? = null
        var seq = 0
        var partFrames = 0
        var partPlain = 0L
        var frames = 0L
        var spanOpen = false
        var spanStart = 0L
        var spanAudio = 0L
        /** Each family phone's stretches in the call while recording, as [from, to] wall-clock ms. */
        val presence = mutableListOf<Presence>()
        /** How the far party was told in this call: "" not yet, played, off (the number does not tell), assistant (answered by the assistant, not told). */
        var notice = ""
        /** The notice being played now, by its id; the recording starts when the bridge says it went out. */
        var notifying: String? = null
        var noticeTimer: (() -> Unit)? = null
        /** Why the recording was asked for, kept while the notice plays. */
        var pendingReason = ""
        /** The screening assistant answered this call: its recording needs no notice. */
        var assisted = false
    }

    private var noticeSeq = 0

    /** Notes who is in the call at [t]: a phone seen again within a second carries its stretch on. */
    private fun seen(l: Live, t: Long) {
        val now = runCatching { participants() }.getOrNull() ?: return
        for (device in now) {
            val i = l.presence.indexOfLast { it.device == device }
            if (i >= 0 && t - l.presence[i].to <= PRESENCE_GAP_MS) l.presence[i] = l.presence[i].copy(to = t)
            else l.presence += Presence(device, t, t)
        }
    }

    private var live: Live? = null

    init {
        // The app stopped while recording: keep what can be read, as partial.
        for (r in store.unfinished()) {
            val parts = r.parts.toMutableList()
            val seen = parts.map { it.seq }.toSet()
            var seq = (parts.maxOfOrNull { it.seq } ?: -1) + 1
            while (true) {
                val readable = runCatching { vault.readable(r.id, seq) }.getOrDefault(-1)
                if (readable < 0 || seq in seen) break
                if (readable > 0) parts += Part(seq, readable, vault.stored(r.id, seq))
                seq++
            }
            val plain = parts.sumOf { it.plain }
            val spans = r.spans.map { if (it.wallEnd == 0L) it.copy(wallEnd = r.started + plain * 1000 / BYTES_PER_SECOND_GUESS, reason = "crashed") else it }
            store.put(r.copy(state = Recording.PARTIAL, parts = parts, plainBytes = plain, storedBytes = parts.sumOf { it.stored },
                ended = spans.maxOfOrNull { it.wallEnd } ?: r.started, spans = spans))
            log("recording ${r.id.take(8)} left unfinished: kept ${parts.size} part(s) as partial")
        }
    }

    @Synchronized
    override fun started(callId: String, numberId: String?, peer: String?, outgoing: Boolean, assisted: Boolean) {
        live?.let { finish(it) }
        val l = Live(callId, numberId, peer, outgoing)
        live = l
        l.assisted = assisted
        if (settings.of(numberId).auto) want(l, true, "auto")
    }

    /** The bridge played the notice [id] to the far party ([ok]), or could not. */
    @Synchronized
    fun onNoticed(callId: String, id: String, ok: Boolean, reason: String = "") {
        val l = live?.takeIf { it.callId == callId && it.notifying == id } ?: return
        l.notifying = null
        l.noticeTimer?.invoke()
        l.noticeTimer = null
        if (ok) {
            l.notice = "played"
            log("recording ${callId.take(8)}: the far party was told")
            want(l, true, l.pendingReason)
        } else noticeFailed(l, reason.ifEmpty { "bridge" })
    }

    private fun noticeFailed(l: Live, why: String) {
        l.notifying = null
        l.noticeTimer?.invoke()
        l.noticeTimer = null
        l.want = false
        l.reason = "notice_failed"
        log("recording ${l.callId.take(8)} not started: the notice was not played ($why)")
        onState(stateOf(l))
    }

    /** Whether the far party still has to be told before this call records. */
    private fun needsNotice(l: Live): Boolean {
        if (l.notice == "played") return false
        // The assistant said who it is and asked for a message: the user's 2026-11-11 choice is no notice on top.
        if (l.assisted) { l.notice = "assistant"; return false }
        if (!settings.tells(l.numberId)) { l.notice = "off"; return false }
        l.notice = ""
        return true
    }

    /** Hands the clip to the bridge; the recording waits for "noticed". */
    private fun tell(l: Live, reason: String) {
        val clip = runCatching { notice(settings.language(l.numberId)) }.getOrNull()
        if (clip == null || clip.isEmpty()) return noticeFailed(l, "no clip")
        val id = "n${++noticeSeq}"
        l.notifying = id
        l.pendingReason = reason
        l.want = false
        l.reason = "notifying"
        var i = 0
        var sent = true
        while (i < clip.size && sent) {
            val n = minOf(NOTICE_CHUNK, clip.size - i)
            val b = java.nio.ByteBuffer.allocate(n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (k in i until i + n) b.putShort(clip[k])
            i += n
            val m = JSONObject().put("type", "notice").put("call_id", l.callId).put("data", Base64.getEncoder().encodeToString(b.array()))
            if (i >= clip.size) m.put("end", id)
            sent = bridge.send(m)
        }
        if (!sent) return noticeFailed(l, "bridge not reachable")
        l.noticeTimer = after(NOTICE_TIMEOUT_MS) { synchronized(this) { if (live === l && l.notifying == id) { stopNotice(l); noticeFailed(l, "timeout") } } }
        log("recording ${l.callId.take(8)}: telling the far party first ($reason)")
        onState(stateOf(l))
    }

    private fun stopNotice(l: Live) {
        l.notifying = null
        l.noticeTimer?.invoke()
        l.noticeTimer = null
        bridge.send(JSONObject().put("type", "notice_stop").put("call_id", l.callId))
    }

    @Synchronized
    override fun ended(callId: String) {
        val l = live?.takeIf { it.callId == callId } ?: return
        finish(l)
        live = null
    }

    /** The call going on now, for the owner who is not in it (RECORDING_V7.md 15): who, on which number, and its state. */
    @Synchronized
    fun current(): JSONObject? = live?.let { l ->
        stateOf(l).put("op", "live").put("number_id", l.numberId ?: "").put("peer", l.peer ?: "").put("outgoing", l.outgoing)
    }

    /** The number the current call [callId] is on, while it goes on; null for none or another call. */
    @Synchronized
    fun liveNumber(callId: String): String? = live?.takeIf { it.callId == callId }?.numberId

    /** Whether [callId] is the call going on now. */
    @Synchronized
    fun isLive(callId: String): Boolean = live?.callId == callId

    /** The current call's recording state, for a phone that asks (it joined later, or the screen opened). */
    @Synchronized
    fun state(callId: String): JSONObject? = live?.takeIf { it.callId == callId }?.let(::stateOf)

    /**
     * Someone allowed asks to start or stop the current call's recording. Returns the state after, or why not:
     * no_call (not this call, or over), no_space.
     */
    @Synchronized
    fun control(callId: String, on: Boolean): Result<JSONObject> {
        val l = live?.takeIf { it.callId == callId } ?: return Result.failure(Refused("no_call"))
        if (on && !room(l.numberId, l)) return Result.failure(Refused("no_space"))
        want(l, on, if (on) "manual" else "stopped")
        return Result.success(stateOf(l))
    }

    private fun want(l: Live, on: Boolean, reason: String) {
        if (on && !room(l.numberId, l)) { l.reason = "no_space"; onState(stateOf(l)); return }
        if (!on && l.notifying != null) {
            // Stopped while the notice plays: nothing is recorded.
            stopNotice(l)
            l.reason = reason
            log("recording ${l.callId.take(8)} off ($reason, during the notice)")
            onState(stateOf(l))
            return
        }
        if (on && !l.want) {
            if (l.notifying != null) return
            if (needsNotice(l)) return tell(l, reason)
        }
        if (l.want == on && l.reason == reason) return
        l.want = on
        l.reason = reason
        if (on) l.since = now()
        val sent = bridge.send(JSONObject().put("type", "record").put("call_id", l.callId).put("on", on))
        if (on && !sent) { l.want = false; l.reason = "failed" }
        log("recording ${l.callId.take(8)} ${if (l.want) "on" else "off"} ($reason${if (on && !sent) ", bridge not reachable" else ""})")
        onState(stateOf(l))
    }

    private fun stateOf(l: Live) = JSONObject().put("op", "state").put("call_id", l.callId).put("recording", l.want && l.reason != "failed")
        .put("since", if (l.want) l.since else 0).put("reason", l.reason).put("recording_id", l.rec?.id ?: "")
        .put("notifying", l.notifying != null).put("notice", l.notice)

    /** A 100 ms block of the call's two sides, from the bridge. */
    @Synchronized
    fun onPcm(callId: String, data: String) {
        val l = live?.takeIf { it.callId == callId && it.want } ?: return
        val raw = runCatching { Base64.getDecoder().decode(data) }.getOrNull() ?: return
        val pcm = ShortArray(raw.size / 2) { i -> ((raw[i * 2].toInt() and 0xff) or (raw[i * 2 + 1].toInt() shl 8)).toShort() }
        try {
            if (l.rec == null) {
                l.rec = Recording(UUID.randomUUID().toString(), l.callId, l.numberId, l.peer, l.outgoing, now(), notice = l.notice).also(store::put)
                l.enc = encoder()
            }
            if (!l.spanOpen) {
                l.spanOpen = true
                l.spanStart = now()
                l.spanAudio = l.frames * FRAME_MS
                // Written down at once, open, so a stretch the app never got to close is still known.
                l.rec = l.rec!!.let { it.copy(spans = it.spans + Span(l.spanStart, 0, l.spanAudio, "")) }.also(store::put)
            }
            seen(l, now())
            l.enc!!.encode(pcm).forEach { write(l, it) }
        } catch (e: Exception) {
            log("recording ${callId.take(8)} write failed: ${e.javaClass.simpleName}")
            closeSpan(l, "failed")
            stop(l, "failed")
        }
    }

    /** The bridge stopped handing over audio: as asked, or the call's media went. */
    @Synchronized
    fun onPcmEnd(callId: String, reason: String) {
        val l = live?.takeIf { it.callId == callId } ?: return
        if (!l.spanOpen) return
        closeSpan(l, if (l.want) "media" else l.reason)
        if (l.want && reason.startsWith("media_")) { l.want = false; l.reason = "media"; onState(stateOf(l)) }
    }

    private fun write(l: Live, frame: ByteArray) {
        val rec = l.rec!!
        val w = l.writer ?: vault.writer(rec.id, l.seq).also { l.writer = it; l.partFrames = 0; l.partPlain = 0 }
        w.write(frame)
        l.partFrames++
        l.partPlain += frame.size
        l.frames++
        if (l.partFrames >= FRAMES_PER_PART) {
            closePart(l)
            // Each minute: the quota and the phone's space; when neither can be met, the recording stops, the call goes on.
            if (!room(l.numberId, l)) stop(l, "no_space")
        }
    }

    private fun closePart(l: Live) {
        val w = l.writer ?: return
        l.writer = null
        val stored = w.close()
        val rec = l.rec!!
        val part = Part(l.seq++, l.partPlain, stored)
        l.rec = rec.copy(parts = rec.parts + part, plainBytes = rec.plainBytes + part.plain, storedBytes = rec.storedBytes + stored,
            audioMs = l.frames * FRAME_MS, presence = l.presence.toList()).also(store::put)
    }

    private fun closeSpan(l: Live, reason: String) {
        if (!l.spanOpen) return
        l.spanOpen = false
        val rec = l.rec ?: return
        val t = now()
        val closed = Span(l.spanStart, t, l.spanAudio, reason)
        // Those in the call to the end of the stretch: the last block's note may be up to 100 ms short of it.
        val present = runCatching { participants() }.getOrDefault(emptySet())
        val presence = l.presence.map { p -> if (p.device in present && t - p.to <= PRESENCE_GAP_MS) p.copy(to = t) else p }
        l.presence.clear(); l.presence += presence
        l.rec = rec.copy(spans = rec.spans.filter { it.wallEnd != 0L } + closed, presence = presence).also(store::put)
    }

    private fun stop(l: Live, reason: String) {
        l.want = false
        l.reason = reason
        bridge.send(JSONObject().put("type", "record").put("call_id", l.callId).put("on", false))
        log("recording ${l.callId.take(8)} stopped: $reason")
        onState(stateOf(l))
    }

    /** The call is over: the rest of the audio written, the recording saved, then the number's quota kept. */
    private fun finish(l: Live) {
        l.noticeTimer?.invoke()
        l.noticeTimer = null
        l.notifying = null
        closeSpan(l, "ended")
        val rec = l.rec ?: return
        val failed = rec.spans.any { it.reason == "failed" }
        try {
            l.enc?.flush()?.forEach { write(l, it) }
        } catch (e: Exception) {
            log("recording ${l.callId.take(8)} last frames lost: ${e.javaClass.simpleName}")
        }
        runCatching { closePart(l) }
        runCatching { l.enc?.close() }
        val done = l.rec!!.copy(ended = now(), state = if (failed) Recording.PARTIAL else Recording.SAVED, audioMs = l.frames * FRAME_MS,
            presence = l.presence.toList())
        store.put(done)
        log("recording ${done.id.take(8)} saved: ${done.audioMs / 1000}s in ${done.parts.size} part(s), ${done.storedBytes / 1024} KB, ${done.spans.size} stretch(es)")
        keepQuota(done.numberId, null)
    }

    /** Space for this number to record on: its quota, and the phone's reserve, cleaning its own oldest if needed. */
    private fun room(numberId: String?, l: Live?): Boolean {
        keepQuota(numberId, l?.rec?.id)
        if (freeSpace() >= SettingsReserve.bytes) return true
        for (r in store.byNumber(numberId)) {
            if (freeSpace() >= SettingsReserve.bytes) break
            if (r.id != l?.rec?.id && !r.locked && r.hasAudio) clean(r, "space")
        }
        return freeSpace() >= SettingsReserve.bytes
    }

    /** This number's recordings within its quota: the oldest unlocked ones cleaned first, never another number's. */
    @Synchronized
    fun keepQuota(numberId: String?, current: String?): Int {
        val quota = settings.of(numberId).quotaMb.toLong() shl 20
        val mine = store.byNumber(numberId)
        var used = mine.sumOf { if (it.hasAudio || it.state == Recording.RECORDING) it.storedBytes else 0 }
        var cleaned = 0
        for (r in mine) {
            if (used <= quota) break
            if (r.id == current || r.locked || !r.hasAudio) continue
            used -= r.storedBytes
            clean(r, "quota")
            cleaned++
        }
        return cleaned
    }

    /** What lowering a number's quota to [quotaMb] would clean, before the owner confirms. */
    @Synchronized
    fun preview(numberId: String?, quotaMb: Int): JSONObject {
        val quota = quotaMb.toLong() shl 20
        val mine = store.byNumber(numberId)
        var used = mine.sumOf { if (it.hasAudio) it.storedBytes else 0 }
        val gone = mutableListOf<Recording>()
        for (r in mine) {
            if (used <= quota) break
            if (r.id == live?.rec?.id || r.locked || !r.hasAudio) continue
            used -= r.storedBytes
            gone += r
        }
        return JSONObject().put("count", gone.size).put("bytes", gone.sumOf { it.storedBytes })
            .put("from", gone.firstOrNull()?.started ?: 0).put("to", gone.lastOrNull()?.started ?: 0).put("reachable", used <= quota)
    }

    private fun clean(r: Recording, why: String) {
        vault.delete(r.id)
        store.put(r.copy(state = Recording.CLEANED, storedBytes = 0, parts = emptyList()))
        log("recording ${r.id.take(8)} cleaned to make room ($why)")
    }

    /** Usage by number: recordings with audio, and their size on disk. */
    @Synchronized
    fun usage(numberId: String?): Pair<Int, Long> = store.byNumber(numberId).filter { it.hasAudio || it.state == Recording.RECORDING }.let { it.size to it.sumOf { r -> r.storedBytes } }

    @Synchronized
    fun lock(id: String, locked: Boolean): Recording? = store.get(id)?.copy(locked = locked)?.also(store::put)

    /** The owner deletes the source: the call keeps its record, marked deleted. Not while it is being recorded. */
    @Synchronized
    fun delete(id: String): Result<Recording> {
        val r = store.get(id) ?: return Result.failure(Refused("not_found"))
        if (r.id == live?.rec?.id) return Result.failure(Refused("recording"))
        vault.delete(r.id)
        return Result.success(r.copy(state = Recording.DELETED, storedBytes = 0, parts = emptyList(), locked = false).also(store::put))
    }

    /** The whole AAC stream of a finished recording from [offset]; null when there is no audio to give. */
    @Synchronized
    fun open(id: String, offset: Long): InputStream? {
        val r = store.get(id)?.takeIf { it.hasAudio && it.id != live?.rec?.id } ?: return null
        var skip = offset
        val streams = mutableListOf<InputStream>()
        for (p in r.parts) {
            if (skip >= p.plain) { skip -= p.plain; continue }
            streams += vault.reader(r.id, p.seq, skip)
            skip = 0
        }
        return SequenceInputStream(java.util.Collections.enumeration(streams))
    }

    fun get(id: String): Recording? = store.get(id)

    /** Started in [from, to), newest first, at most 500. */
    fun list(from: Long, to: Long): List<Recording> = store.list(from, to, 500)

    class Refused(val code: String) : Exception(code)

    /** The reserve, settable in tests. */
    object SettingsReserve { @Volatile var bytes = RecordingSettings.RESERVE_BYTES }

    companion object {
        /** One AAC frame: 1024 samples at 16 kHz. */
        const val FRAME_MS = 64L
        /** About a minute a part. */
        const val FRAMES_PER_PART = 938
        /** A phone not seen in the call for longer than this has left it: its next stretch is a new one. */
        const val PRESENCE_GAP_MS = 1_000L
        /** The notice goes to the bridge in pieces of 100 ms. */
        const val NOTICE_CHUNK = 1600
        /** The bridge waits 8 s for the uplink and the notice is at most 12 s: no word after this long is a failure. */
        const val NOTICE_TIMEOUT_MS = 25_000L
        /** For the length of a recording cut short by the app stopping: about 40 kbps. */
        private const val BYTES_PER_SECOND_GUESS = 5000L
    }
}
