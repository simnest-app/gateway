package dev.cloudsim.gateway.screening

import dev.cloudsim.gateway.calls.Sealer
import dev.cloudsim.gateway.net.ControlClient
import dev.cloudsim.gateway.net.ControlClient.Companion.unb64
import dev.cloudsim.gateway.protocol.Protocol
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** Turns one clip as the owner's iPhone sent it (AAC in an M4A file) into 16 kHz mono PCM; null if it cannot. */
fun interface ClipDecoder {
    fun decode(encoded: ByteArray): ShortArray?
}

/**
 * The owner's screening requests (SCREENING_ASSISTANT.md 10.1), over the records channel as kind "screening", each
 * sealed by the owner's phone for this gateway with its request id inside and at most 2 minutes old:
 * - "key": this gateway's number-digest key, sealed back for the owner, who digests the allow list and contacts with it;
 * - "asset": one piece of a number's set (clips and lists, as JSON); once all are in, the clips are decoded and checked,
 *   the set installed, and its digest sent back, which the owner then signs into the policy;
 * - "remove": a number's set is deleted (screening turned off there);
 * - "transcript": a screened call's conversation (13.3), which family with the answer right on that call's number may
 *   read too (2026-11-11: they get the notice and may listen in, so they may read what is said).
 * Anything else from anyone but the paired owner is refused. Answers go as records parts sealed for the asking phone.
 */
class ScreeningRequests(
    private val client: ControlClient,
    private val sealer: Sealer,
    private val owner: () -> String?,
    private val store: ScreenAssetStore,
    private val digestKey: () -> ByteArray,
    private val decoder: ClipDecoder,
    private val remote: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    /** The owner's voice and the jobs that speak in it (SCREENING_ASSISTANT.md 12.2); null: clips come from the iPhone. */
    private val voice: VoiceSetup? = null,
    /** What was said on screened calls, and the models that write it down (SCREENING_ASSISTANT.md 13). */
    private val transcripts: dev.cloudsim.gateway.screening.transcript.Transcripts? = null,
    private val asr: dev.cloudsim.gateway.screening.transcript.AsrModels? = null,
    /** The owner-signed policy, for family asking for a conversation. */
    private val policy: () -> dev.cloudsim.gateway.calls.SharingPolicy? = { null },
    /** The gateway number a call was on: "" for the default one, null if this phone does not know the call. */
    private val numberOf: (String) -> String? = { null },
) {
    /** Lines followed live go out one by one, in order, off the transcriber's thread. */
    private val live = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "transcript-out") }

    /** What speaking in the owner's voice needs here. */
    class VoiceSetup(
        val profile: dev.cloudsim.gateway.screening.voice.VoiceProfile,
        val jobs: dev.cloudsim.gateway.screening.voice.SpeechJobs,
        val models: dev.cloudsim.gateway.screening.voice.VoiceModels,
        /** A recording from the owner's iPhone (M4A) as 24 kHz mono, or null. */
        val decode24k: (ByteArray) -> ShortArray?,
    )

    private class Upload(val number: String, val count: Int, val started: Long) {
        val pieces = arrayOfNulls<ByteArray>(count)
    }

    /** Sets being sent, by set id; dropped when unfinished for [UPLOAD_MS]. */
    private val uploads = HashMap<String, Upload>()

    @Synchronized
    fun onRequest(data: JSONObject) {
        val from = data.optString("device_id")
        val requestId = data.optString("request_id")
        if (requestId.length !in 8..64) return
        if (!remote()) return log("screening request from ${from.take(8)} refused: permissions unconfirmed for 5 minutes")
        val o = owner() ?: return
        val isOwner = from == o
        if (!isOwner && from !in (policy()?.answerers(o) ?: emptyList())) return log("screening request from ${from.take(8)} refused: no right")
        val at = data.optLong("_at_ms")
        if (at > 0 && now() - at > FRESH_MS) return log("screening request ${requestId.take(12)} is ${(now() - at) / 1000}s old, ignored")
        val key = unb64(data.optString("public_key"))?.takeIf { Protocol.deviceId(it) == from } ?: return log("screening request refused: wrong key")
        val env = runCatching { JSONObject(data.optString("envelope")) }.getOrNull() ?: return
        val plain = sealer.open(env, SEAL_KIND, key)?.let { runCatching { JSONObject(it.decodeToString()) }.getOrNull() }
            ?: return log("screening request from ${from.take(8)} refused: not sealed by it")
        if (plain.optString("request_id") != requestId) return log("screening request ${requestId.take(12)}: id does not match")
        if (!isOwner) {
            val callId = plain.optString("call_id")
            if (plain.optString("op") != "transcript") return log("screening request from ${from.take(8)} refused: not the owner")
            // Said, not left unanswered: family's details page asks about every call it has, screened or not.
            if (!mayRead(from, callId)) return answer(from, requestId, JSONObject().put("entries", JSONArray()).put("live", false).put("error", "no_right"))
            return transcript(from, requestId, callId)
        }
        when (plain.optString("op")) {
            "key" -> answer(from, requestId, JSONObject().put("key", Base64.getEncoder().encodeToString(digestKey())))
            "asset", "voice" -> piece(from, requestId, plain)
            "status" -> status(from, requestId, plain.optString("number"))
            "models" -> models(from, requestId, plain.optBoolean("download"))
            "sample" -> sample(from, requestId, plain.optString("text"), styleOf(plain))
            "style" -> setStyle(from, requestId, plain)
            "transcript" -> transcript(from, requestId, plain.optString("call_id"))
            "remove" -> {
                val number = plain.optString("number")
                if (!NUMBER.matches(number)) return
                store.remove(number)
                log("screening set for ${number.take(12)} removed")
                answer(from, requestId, JSONObject().put("removed", true))
            }
            else -> log("screening request ${requestId.take(12)}: unknown op")
        }
    }

    private fun piece(to: String, requestId: String, p: JSONObject) {
        val number = p.optString("number")
        val set = p.optString("set")
        val index = p.optInt("index", -1)
        val count = p.optInt("count", 0)
        val chunk = unb64(p.optString("chunk"))
        if (!NUMBER.matches(number) || set.length !in 8..64 || count !in 1..MAX_PIECES || index !in 0 until count || chunk == null) {
            return answer(to, requestId, JSONObject().put("error", "bad_piece"))
        }
        uploads.entries.removeIf { now() - it.value.started > UPLOAD_MS }
        val u = uploads.getOrPut(set) { Upload(number, count, now()) }
        if (u.number != number || u.count != count) return answer(to, requestId, JSONObject().put("error", "bad_piece"))
        u.pieces[index] = chunk
        if (u.pieces.any { it == null }) return answer(to, requestId, JSONObject().put("have", index))
        uploads.remove(set)
        val bundle = runCatching { JSONObject(u.pieces.fold(ByteArray(0)) { a, b -> a + b!! }.decodeToString()) }.getOrNull()
            ?: return answer(to, requestId, JSONObject().put("error", "bad_set"))
        val result = when {
            p.optString("op") == "voice" -> setVoice(bundle)
            bundle.has("texts") -> speak(number, bundle)
            else -> install(number, bundle)
        }
        answer(to, requestId, result)
    }

    /** Decodes and checks every clip, then installs the set; the answer carries its digest, or what was wrong. */
    private fun install(number: String, bundle: JSONObject): JSONObject {
        val clipsIn = bundle.optJSONObject("clips") ?: return JSONObject().put("error", "bad_set")
        val encoded = LinkedHashMap<String, ByteArray>()
        val decoded = LinkedHashMap<String, ShortArray>()
        for (name in clipsIn.keys()) {
            if (name !in ScreenAssetStore.NAMES && !ScreenAssetStore.REPLY.matches(name)) return JSONObject().put("error", "bad_clip").put("clip", name)
            val bytes = unb64(clipsIn.optString(name)) ?: return JSONObject().put("error", "bad_clip").put("clip", name)
            val pcm = decoder.decode(bytes) ?: return JSONObject().put("error", "undecodable").put("clip", name)
            val limit = if (name == "greeting") GREETING_MAX_SAMPLES else OTHER_MAX_SAMPLES
            if (pcm.isEmpty() || pcm.size > limit) return JSONObject().put("error", "too_long").put("clip", name)
            encoded[name] = bytes
            decoded[name] = pcm
        }
        if ("greeting" !in decoded) return JSONObject().put("error", "no_greeting")
        fun digests(field: String): List<ByteArray>? = bundle.optJSONArray(field)?.let { a ->
            (0 until a.length()).map { unb64(a.getString(it))?.takeIf { d -> d.size == 16 } ?: return null }
        } ?: emptyList()
        val allow = digests("allow") ?: return JSONObject().put("error", "bad_list")
        val contacts = digests("contacts") ?: return JSONObject().put("error", "bad_list")
        if (allow.size > MAX_ALLOW || contacts.size > MAX_CONTACTS) return JSONObject().put("error", "list_too_long")
        val d = store.install(number, encoded, decoded, allow, contacts)
        log("screening set for ${number.take(12)} installed: ${decoded.size} clip(s), ${allow.size} allowed, ${contacts.size} contact digest(s)")
        return JSONObject().put("digest", d)
    }

    // The owner's voice (SCREENING_ASSISTANT.md 12.2).

    /** The reference recording and its words: decoded, checked, kept; the answer names the voice. */
    private fun setVoice(bundle: JSONObject): JSONObject {
        val v = voice ?: return JSONObject().put("error", "unsupported")
        val audio = unb64(bundle.optString("audio")) ?: return JSONObject().put("error", "bad_voice")
        val text = bundle.optString("text").trim()
        if (text.length !in 4..200) return JSONObject().put("error", "bad_voice")
        val pcm = v.decode24k(audio) ?: return JSONObject().put("error", "undecodable")
        if (pcm.size !in VOICE_MIN_SAMPLES..VOICE_MAX_SAMPLES) return JSONObject().put("error", "voice_length")
        val id = v.profile.set(pcm, text)
        log("owner's voice set: ${pcm.size / 24}ms")
        // The models are needed next: start fetching them now, while the owner listens.
        if (!v.models.ready()) Thread({ v.models.ensure() }, "voice-models").start()
        return JSONObject().put("voice", id)
    }

    /** A set to speak in the owner's voice: checked, digested, and started; the iPhone follows it with "status". */
    private fun speak(number: String, bundle: JSONObject): JSONObject {
        val v = voice ?: return JSONObject().put("error", "unsupported")
        val current = v.profile.get() ?: return JSONObject().put("error", "no_voice")
        if (bundle.optString("voice") != current.id) return JSONObject().put("error", "voice_changed")
        val t = bundle.getJSONObject("texts")
        val texts = LinkedHashMap<String, String>()
        for (name in t.keys()) {
            if (name !in ScreenAssetStore.NAMES && !ScreenAssetStore.REPLY.matches(name)) return JSONObject().put("error", "bad_clip").put("clip", name)
            val text = t.getString(name).trim()
            if (text.isEmpty() || text.length > if (name == "greeting") 120 else 60) return JSONObject().put("error", "too_long").put("clip", name)
            texts[name] = text
        }
        if ("greeting" !in texts) return JSONObject().put("error", "no_greeting")
        fun digests(field: String): List<ByteArray>? = bundle.optJSONArray(field)?.let { a ->
            (0 until a.length()).map { unb64(a.getString(it))?.takeIf { d -> d.size == 16 } ?: return null }
        } ?: emptyList()
        val allow = digests("allow") ?: return JSONObject().put("error", "bad_list")
        val contacts = digests("contacts") ?: return JSONObject().put("error", "bad_list")
        if (allow.size > MAX_ALLOW || contacts.size > MAX_CONTACTS) return JSONObject().put("error", "list_too_long")
        val digest = ScreenAssetStore.textDigest(current.id, texts, allow, contacts)
        v.jobs.speakSet(number, current.id, texts) { clips -> store.installSpoken(number, digest, clips, allow, contacts, texts) }
        log("speaking ${texts.size} line(s) for ${number.take(12)}")
        return JSONObject().put("generating", true).put("digest", digest).put("total", texts.size)
    }

    private fun status(to: String, requestId: String, number: String) {
        val v = voice ?: return answer(to, requestId, JSONObject().put("error", "unsupported"))
        val p = v.jobs.progress(number)
        val m = v.models.state
        val out = JSONObject().put("models", when (m) {
            is dev.cloudsim.gateway.screening.voice.VoiceModels.State.Ready -> "ready"
            is dev.cloudsim.gateway.screening.voice.VoiceModels.State.Downloading -> "downloading"
            is dev.cloudsim.gateway.screening.voice.VoiceModels.State.Failed -> "failed"
            else -> "missing"
        })
        if (m is dev.cloudsim.gateway.screening.voice.VoiceModels.State.Downloading) out.put("percent", m.percent)
        // How the voice is spoken now, for the iPhone's sliders.
        v.profile.get()?.let { out.put("voice", it.id).put("style", it.style.json()) }
        // The set in place, so saving settings that leave the lines alone sends and speaks nothing (2026-11-10).
        if (number.isNotEmpty()) store.installed(number)?.let { out.put("installed", it) }
        if (p != null) {
            out.put("done", p.done).put("total", p.total)
            p.digest?.let { out.put("digest", it) }
            p.error?.let { out.put("error", it) }
        }
        answer(to, requestId, out)
    }

    /**
     * A screened call's transcript so far, as one answer, then each new line as it comes, under "<request id>-live"
     * (SCREENING_ASSISTANT.md 13.3), while the call goes on. A call long over answers what was kept, or nothing.
     */
    private fun transcript(to: String, requestId: String, callId: String) {
        val t = transcripts ?: return answer(to, requestId, JSONObject().put("error", "unsupported"))
        val follow = "$requestId-live".takeIf { it.length <= 64 }
        // Followed first, so no line falls between the answer and the first live one (the phone drops repeats by seq).
        val live = follow != null && t.follow(callId) { e ->
            this.live.execute { answerParts(to, follow, listOf(JSONObject().put("entry", e.json()))) }
        }
        val entries = t.entries(callId) ?: emptyList()
        val out = JSONObject().put("entries", JSONArray(entries.map { it.json() })).put("live", live)
        asr?.let { out.put("asr", asrState(it.state)) }
        answer(to, requestId, out)
    }

    /** Family may read a call's conversation with the answer right on the number it was on (on any, if not known). */
    private fun mayRead(device: String, callId: String): Boolean {
        val m = policy()?.members?.firstOrNull { it.deviceId == device } ?: return false
        val number = numberOf(callId) ?: return false
        return if (number.isEmpty()) m.answer else m.on(number).answer
    }

    private fun asrState(s: dev.cloudsim.gateway.screening.transcript.AsrModels.State) = when (s) {
        is dev.cloudsim.gateway.screening.transcript.AsrModels.State.Ready -> "ready"
        is dev.cloudsim.gateway.screening.transcript.AsrModels.State.Downloading -> "downloading"
        is dev.cloudsim.gateway.screening.transcript.AsrModels.State.Failed -> "failed"
        else -> "missing"
    }

    private fun models(to: String, requestId: String, download: Boolean) {
        // The transcription models come along, in the background (SCREENING_ASSISTANT.md 13.1).
        asr?.let { a -> if (download && !a.ready() && a.state !is dev.cloudsim.gateway.screening.transcript.AsrModels.State.Downloading) Thread({ a.ensure() }, "asr-models").start() }
        val v = voice ?: return answer(to, requestId, JSONObject().put("error", "unsupported"))
        if (download && !v.models.ready()) Thread({ v.models.ensure() }, "voice-models").start()
        status(to, requestId, "")
    }

    /** One line in the owner's voice to listen to, sent back as 8 kHz WAV (what a caller hears) in pieces. */
    private fun sample(to: String, requestId: String, text: String, style: dev.cloudsim.gateway.screening.voice.VoiceProfile.Style?) {
        val v = voice ?: return answer(to, requestId, JSONObject().put("error", "unsupported"))
        if (text.trim().length !in 1..120) return answer(to, requestId, JSONObject().put("error", "too_long"))
        v.jobs.sample(text.trim(), style) { pcm, why ->
            if (pcm == null) return@sample answer(to, requestId, JSONObject().put("error", why ?: "failed"))
            val wav = Base64.getEncoder().encodeToString(wav8k(pcm))
            answerParts(to, requestId, wav.chunked(SAMPLE_PART).map { JSONObject().put("chunk", it) })
        }
    }

    /** The style asked for with a sample or to keep: speed and guidance, both or neither. */
    private fun styleOf(plain: JSONObject): dev.cloudsim.gateway.screening.voice.VoiceProfile.Style? =
        if (plain.has("speed") || plain.has("guidance"))
            dev.cloudsim.gateway.screening.voice.VoiceProfile.Style.of(plain.optDouble("speed", 1.0), plain.optDouble("guidance", 1.0))
        else null

    /** Keeps how the owner's voice is spoken (speed, "like me"); the answer is the voice's new id, and the style. */
    private fun setStyle(to: String, requestId: String, plain: JSONObject) {
        val v = voice ?: return answer(to, requestId, JSONObject().put("error", "unsupported"))
        val style = styleOf(plain) ?: return answer(to, requestId, JSONObject().put("error", "bad_style"))
        val id = v.profile.setStyle(style) ?: return answer(to, requestId, JSONObject().put("error", "no_voice"))
        log("voice style: speed ${style.speed}, guidance ${style.guidance}")
        answer(to, requestId, JSONObject().put("voice", id).put("style", style.json()))
    }

    private fun answer(to: String, requestId: String, item: JSONObject) = answerParts(to, requestId, listOf(item))

    private fun answerParts(to: String, requestId: String, items: List<JSONObject>) {
        items.forEachIndexed { i, item ->
            val plain = JSONObject().put("kind", KIND).put("request_id", requestId).put("index", i).put("count", items.size).put("items", JSONArray().put(item))
            val env = sealer.seal("records", to, "$requestId-$i", plain.toString().toByteArray()) ?: return log("no recipient key for ${to.take(8)}")
            runCatching {
                client.call("POST", "/v1/records/deliver", JSONObject().put("to", to).put("kind", KIND).put("request_id", requestId)
                    .put("index", i).put("count", items.size).put("envelope", env.toString()))
            }.onFailure { return log("screening answer for ${to.take(8)} not delivered: ${it.message}") }
        }
    }

    companion object {
        const val KIND = "screening"
        const val SEAL_KIND = "screening"
        const val FRESH_MS = 2 * 60_000L
        const val UPLOAD_MS = 5 * 60_000L
        const val MAX_PIECES = 32
        /** 15 s greeting and 8 s for the rest at 16 kHz, with half a second to spare for the encoder's padding. */
        const val GREETING_MAX_SAMPLES = 16000 * 31 / 2
        const val OTHER_MAX_SAMPLES = 16000 * 17 / 2
        const val MAX_ALLOW = 200
        /** The owner's reference recording: 3 to 16 s at 24 kHz. */
        const val VOICE_MIN_SAMPLES = 24000 * 3
        const val VOICE_MAX_SAMPLES = 24000 * 16
        /** Base64 characters of a sample per answer part, well inside the service's 48 KiB. */
        const val SAMPLE_PART = 30_000

        /** 16 kHz to 8 kHz (low-passed first, 12.5) as a WAV file. */
        fun wav8k(pcm: ShortArray): ByteArray {
            val down = dev.cloudsim.gateway.screening.voice.Resample.resample(FloatArray(pcm.size) { pcm[it] / 32768f }, 16000, 8000)
            val n = down.size
            val data = java.nio.ByteBuffer.allocate(44 + n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            data.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16).put("data".toByteArray()).putInt(n * 2)
            for (v in down) data.putShort((v * 32768).toInt().coerceIn(-32768, 32767).toShort())
            return data.array()
        }
        const val MAX_CONTACTS = 5000
        /** A number id the owner's policy uses, or "*" for calls on an unknown number. */
        private val NUMBER = Regex("^(\\*|[A-Za-z0-9_-]{1,64})$")
    }
}
