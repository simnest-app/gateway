package dev.cloudsim.gateway.screening

import android.content.Context
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/**
 * Screening on the gateway phone (SCREENING_ASSISTANT.md 10.3): the owner's clips and lists in the app's private
 * files, who is known on this phone (its contacts and call log), whether the audio bridge answers, and where each
 * screening's summary is kept. The caller's number is looked up here and never logged.
 */
class SystemScreener(
    private val context: Context,
    private val bridgeReady: () -> Boolean,
    private val record: (String) -> Unit,
    private val log: (String) -> Unit,
) : Screener {
    val rules = ScreeningRules()
    val assets = ScreenAssetStore(File(context.filesDir, "screening/assets"))
    val history = ScreenHistory(File(context.filesDir, "screening/history.jsonl"))
    val transcripts = dev.cloudsim.gateway.screening.transcript.Transcripts(File(context.filesDir, "screening/transcripts"))
    val asrModels = dev.cloudsim.gateway.screening.transcript.AsrModels(File(context.filesDir, "asr-models"), context.getExternalFilesDir("models"), log = log)
    private val transcriber = dev.cloudsim.gateway.screening.transcript.Transcriber(dev.cloudsim.gateway.screening.transcript.SherpaAsr(asrModels), transcripts, log,
        // Not waited for: the models load during the rings, and the page coming up must not hold the call back.
        boost = { dev.cloudsim.gateway.Performance.hold(context, "transcribe", wait = false) })

    /** This gateway's key for number digests; the owner's phone gets it sealed, the service never sees it. */
    val digestKey: ByteArray by lazy {
        val prefs = context.getSharedPreferences("screening", Context.MODE_PRIVATE)
        prefs.getString("digest_key", null)?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
            ?: ByteArray(32).also(SecureRandom()::nextBytes).also { prefs.edit().putString("digest_key", android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)).apply() }
    }

    override fun decide(settings: ScreenSettings?, numberId: String?, caller: String?, emergencyCaller: Boolean, remote: Boolean): Decision {
        val key = numberId ?: "*"
        val on = settings?.takeIf { it.on }
        // Only looked up when it matters: a number screening is on for.
        val known = if (on == null || caller.isNullOrBlank()) Known.UNKNOWN else known(key, caller, on)
        val ready = on != null && assets.ready(key, on.assets)
        return rules.decide(RingContext(settings, caller, known, emergencyCaller, remote, on != null && bridgeReady(), ready))
    }

    private fun known(key: String, caller: String, s: ScreenSettings): Known {
        val country = runCatching { context.getSystemService(TelephonyManager::class.java).simCountryIso.uppercase() }.getOrNull()?.ifEmpty { null } ?: "CN"
        assets.known(key, ScreenAssetStore.numberDigest(digestKey, e164(caller, country)), s.knownContacts)?.let { return it }
        if (inContacts(caller) || inCallLog(caller)) return Known.KNOWN
        return Known.UNKNOWN
    }

    private fun inContacts(caller: String): Boolean = runCatching {
        context.contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(caller)),
            arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { it.moveToFirst() } ?: false
    }.getOrDefault(false)

    /**
     * A number this phone has called, or a call from it that was answered: someone the owner deals with. A call only
     * the assistant answered does not count: the system logs it as answered, and a stranger calling back a minute
     * later rang the phones as "known" (2026-11-12 14:40, 14:41).
     */
    private fun inCallLog(caller: String): Boolean = runCatching {
        val tail = caller.filter(Char::isDigit).takeLast(7)
        if (tail.length < 7) return@runCatching false
        context.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DURATION, CallLog.Calls.DATE),
            "${CallLog.Calls.NUMBER} LIKE ?", arrayOf("%$tail"), "${CallLog.Calls.DATE} DESC LIMIT 50")?.use { c ->
            val assistantOnly by lazy { history.assistantOnly() }
            while (c.moveToNext()) {
                val type = c.getInt(1)
                val talked = type == CallLog.Calls.OUTGOING_TYPE ||
                    (type == CallLog.Calls.INCOMING_TYPE && c.getLong(2) > 0 && assistantOnly.none { c.getLong(3) in it })
                if (talked && PhoneNumberUtils.compare(c.getString(0), caller)) return@runCatching true
            }
            false
        } ?: false
    }.getOrDefault(false)

    override fun clips(numberId: String?): ScreenClips = assets.clips(numberId ?: "*")

    override fun emergency() { rules.lastEmergency = System.currentTimeMillis() }

    override fun recordMessage(callId: String) = record(callId)

    override fun prepare(callId: String) {
        // Not here yet (a side-loaded copy put in since, or the network back): fetched for the next call.
        if (!asrModels.ready() && asrModels.state !is dev.cloudsim.gateway.screening.transcript.AsrModels.State.Downloading) {
            Thread({ asrModels.ensure() }, "asr-models").apply { priority = Thread.MIN_PRIORITY }.start()
        }
        transcriber.prepare(callId)
    }

    override fun heard(callId: String, pcm: ShortArray) = transcriber.heard(callId, pcm)

    override fun said(callId: String, numberId: String?, clip: String) {
        assets.text(numberId ?: "*", clip)?.let { transcripts.add(callId, "assistant", it) }
    }

    override fun stopTranscribing(callId: String, over: Boolean) = transcriber.stop(callId, over)

    override fun finished(callId: String, numberId: String?, summary: JSONObject) {
        history.add(summary.put("call_id", callId).put("number_id", numberId ?: "").put("at", System.currentTimeMillis()))
        log("screening $callId: ${summary.optString("result")} after ${summary.optLong("ms") / 1000}s")
    }
}

/** Each screening's summary, newest last, the last [MAX] kept: what the phones show next to the call. */
class ScreenHistory(private val file: File) {
    @Synchronized
    fun add(entry: JSONObject) {
        file.parentFile?.mkdirs()
        val lines = (if (file.exists()) file.readLines() else emptyList()) + entry.toString()
        file.writeText(lines.takeLast(MAX).joinToString("\n", postfix = "\n"))
    }

    /**
     * When the calls only the assistant spoke on began ringing, as windows to match the system call log's start times:
     * the assistant took each call at most [ANSWER_WINDOW_MS] after it began to ring. Calls taken over by a phone, or
     * that rang the phones because the assistant could not be heard, are not among them.
     */
    @Synchronized
    fun assistantOnly(): List<LongRange> = list(0, Long.MAX_VALUE).filter { it.optString("result") !in TALKED }.map { e ->
        val answered = e.optLong("at") - e.optLong("ms")
        (answered - ANSWER_WINDOW_MS)..answered
    }

    /** The gateway number a screened call was on ("" for the default one), or null if not kept here. */
    @Synchronized
    fun numberOf(callId: String): String? = if (!file.exists()) null else
        file.readLines().asReversed().firstNotNullOfOrNull { l -> runCatching { JSONObject(l) }.getOrNull()?.takeIf { it.optString("call_id") == callId }?.optString("number_id") }

    @Synchronized
    fun list(from: Long, to: Long): List<JSONObject> = if (!file.exists()) emptyList() else
        file.readLines().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }.filter { it.optLong("at") in from until to }

    companion object {
        const val MAX = 500
        /** Results after which someone may have spoken with the caller. */
        private val TALKED = setOf("taken", "unheard")
        /** The longest a call rings before the assistant answers (after-ring at most 60 s), with room to spare. */
        const val ANSWER_WINDOW_MS = 90_000L
    }
}

/**
 * Decodes a clip from the owner's iPhone (AAC in M4A, any rate, mono or stereo) with the phone's own codecs, to the
 * 16 kHz mono the assistant plays. Written to a private temporary file for MediaExtractor, deleted at once.
 */
class MediaCodecClipDecoder(private val cacheDir: File) : ClipDecoder {
    override fun decode(encoded: ByteArray): ShortArray? = decodeAt(encoded, 16000)

    /** The same at another rate: the owner's voice recording is kept at 24 kHz for cloning. */
    fun decodeAt(encoded: ByteArray, target: Int): ShortArray? {
        val tmp = File.createTempFile("clip", ".m4a", cacheDir)
        val extractor = android.media.MediaExtractor()
        var codec: android.media.MediaCodec? = null
        return try {
            tmp.writeBytes(encoded)
            extractor.setDataSource(tmp.path)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            var rate = format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
            val c = android.media.MediaCodec.createDecoderByType(format.getString(android.media.MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(format, null, null, 0)
            c.start()
            val out = java.io.ByteArrayOutputStream()
            val info = android.media.MediaCodec.BufferInfo()
            var inputDone = false
            var deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = extractor.readSampleData(c.getInputBuffer(i)!!, 0)
                        if (n < 0) { c.queueInputBuffer(i, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { c.queueInputBuffer(i, 0, n, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = c.outputFormat.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                    channels = c.outputFormat.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    val b = c.getOutputBuffer(o)!!
                    val chunk = ByteArray(info.size)
                    b.position(info.offset)
                    b.get(chunk)
                    out.write(chunk)
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                if (out.size() > 16000 * 2 * 2 * 60) return null
            }
            toMono(ScreenAssetStore.pcm(out.toByteArray()), rate, channels, target)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
            tmp.delete()
        }
    }

    companion object {
        /** Interleaved PCM at any rate to 16 kHz mono: channels averaged, then linear interpolation. */
        fun toMono16k(pcm: ShortArray, rate: Int, channels: Int): ShortArray = toMono(pcm, rate, channels, 16000)

        fun toMono(pcm: ShortArray, rate: Int, channels: Int, target: Int): ShortArray {
            val ch = maxOf(1, channels)
            val frames = pcm.size / ch
            val mono = ShortArray(frames) { i -> (0 until ch).sumOf { pcm[i * ch + it].toInt() }.div(ch).toShort() }
            if (rate == target || frames == 0) return mono
            val n = (frames.toLong() * target / rate).toInt()
            return ShortArray(n) { i ->
                val t = i.toDouble() * rate / target
                val k = t.toInt().coerceAtMost(frames - 1)
                val a = mono[k]
                val b = mono[minOf(k + 1, frames - 1)]
                (a + (b - a) * (t - k)).toInt().toShort()
            }
        }
    }
}

/**
 * The gateway phone's call log with each screened call marked (SCREENING_ASSISTANT.md 10.4): "screen" is how the
 * screening ended, "spoke" whether the caller said anything, for the owner's Recents. A screening belongs to the call
 * whose time it ended within (from the call's start to two minutes after its end).
 */
class ScreenedCalls(private val base: dev.cloudsim.gateway.records.RecordSource, private val history: ScreenHistory) :
    dev.cloudsim.gateway.records.RecordSource by base {
    override fun calls(from: Long, to: Long): List<JSONObject> {
        val list = base.calls(from, to)
        val screened = runCatching { history.list(from, to + 30 * 60_000L) }.getOrDefault(emptyList())
        if (screened.isEmpty()) return list
        for (c in list) {
            val start = c.optLong("at")
            val end = start + c.optLong("duration_s") * 1000 + 120_000
            screened.firstOrNull { it.optLong("at") in start..end }?.let { s ->
                c.put("screen", s.optString("result")).put("spoke", s.optBoolean("caller_spoke")).put("call_id", s.optString("call_id"))
            }
        }
        return list
    }
}
