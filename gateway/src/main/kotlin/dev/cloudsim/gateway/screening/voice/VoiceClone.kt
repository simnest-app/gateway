package dev.cloudsim.gateway.screening.voice

import dev.cloudsim.gateway.screening.ScreenAssetStore
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Speaks [text] in the voice of [ref] (with its words [refText]); 16 kHz mono out. */
fun interface Cloner {
    fun speak(text: String, ref: FloatArray, refRate: Int, refText: String): ShortArray
}

/**
 * ZipVoice through sherpa-onnx (SCREENING_ASSISTANT.md 12.2). Loaded on first use (about 450 MB), released after
 * each job so it never sits in memory next to a call. 4 flow-matching steps and 4 threads, as measured on the gateway
 * phone: about 21 s for the greeting, 12–14 s for a short line.
 */
class SherpaCloner(private val models: VoiceModels) : Cloner {
    private var tts: com.k2fsa.sherpa.onnx.OfflineTts? = null

    private var denoiser: com.k2fsa.sherpa.onnx.OfflineSpeechDenoiser? = null
    /** The reference with the room's noise taken out, for the voice it was made from (by the samples' identity). */
    private var cleaned: Triple<FloatArray, FloatArray, Int>? = null

    @Synchronized
    override fun speak(text: String, ref: FloatArray, refRate: Int, refText: String): ShortArray {
        val t = tts ?: com.k2fsa.sherpa.onnx.OfflineTts(null, com.k2fsa.sherpa.onnx.OfflineTtsConfig(
            model = com.k2fsa.sherpa.onnx.OfflineTtsModelConfig(
                zipvoice = com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig(
                    tokens = File(models.zipvoice, "tokens.txt").path,
                    encoder = File(models.zipvoice, "encoder.int8.onnx").path,
                    decoder = File(models.zipvoice, "decoder.int8.onnx").path,
                    vocoder = models.vocoder.path,
                    dataDir = File(models.zipvoice, "espeak-ng-data").path,
                    lexicon = File(models.zipvoice, "lexicon.txt").path,
                ),
                numThreads = 4,
            ),
        )).also { tts = it }
        val (clean, cleanRate) = clean(ref, refRate)
        // 8 steps rather than 4: the owner chose it on a comparison of the same lines (12.5), at about half again the time.
        val out = t.generateWithConfig(text, com.k2fsa.sherpa.onnx.GenerationConfig(
            referenceAudio = clean, referenceSampleRate = cleanRate, referenceText = refText, numSteps = STEPS))
        return to16k(out.samples, out.sampleRate)
    }

    /**
     * The owner's recording without the room's hiss, which the model would otherwise copy into every line (12.5):
     * GTCRN at 16 kHz, done once per voice. Unchanged when the denoiser is missing or fails.
     */
    private fun clean(ref: FloatArray, rate: Int): Pair<FloatArray, Int> {
        cleaned?.let { (src, out, r) -> if (src === ref || src.contentEquals(ref)) return out to r }
        val result = runCatching {
            val d = denoiser ?: com.k2fsa.sherpa.onnx.OfflineSpeechDenoiser(null, com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserConfig(
                model = com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig(
                    gtcrn = com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig(models.denoiser.path), numThreads = 2),
            )).also { denoiser = it }
            val a = d.run(ref, rate)
            a.samples to a.sampleRate
        }.getOrNull()?.takeIf { it.first.isNotEmpty() } ?: (ref to rate)
        cleaned = Triple(ref, result.first, result.second)
        return result
    }

    @Synchronized
    fun release() {
        tts?.release()
        tts = null
        denoiser?.release()
        denoiser = null
    }

    companion object {
        const val STEPS = 8

        /** Float samples at [rate] to 16 kHz 16-bit, low-passed first, with a peak of -1 dBFS. */
        fun to16k(x: FloatArray, rate: Int): ShortArray = Resample.toPcm(Resample.resample(x, rate, 16000))
    }
}

/**
 * The owner's voice on the gateway: the reference recording (24 kHz) and its words. Its id is the SHA-256 of both, so
 * the owner's iPhone and the policy name the same voice. Only the paired owner's iPhone can set it; it never leaves
 * this phone.
 */
class VoiceProfile(private val dir: File) {
    data class Voice(val id: String, val text: String, val samples: FloatArray, val rate: Int)

    @Synchronized
    fun set(pcm24k: ShortArray, text: String): String {
        val id = id(ScreenAssetStore.bytes(pcm24k), text)
        dir.mkdirs()
        File(dir, "ref.pcm").writeBytes(ScreenAssetStore.bytes(pcm24k))
        File(dir, "voice.json").writeText(JSONObject().put("id", id).put("text", text).put("rate", RATE).toString())
        return id
    }

    @Synchronized
    fun get(): Voice? = runCatching {
        val m = JSONObject(File(dir, "voice.json").readText())
        val pcm = ScreenAssetStore.pcm(File(dir, "ref.pcm").readBytes())
        Voice(m.getString("id"), m.getString("text"), FloatArray(pcm.size) { pcm[it] / 32768f }, m.optInt("rate", RATE))
    }.getOrNull()

    @Synchronized
    fun clear() { dir.deleteRecursively() }

    companion object {
        const val RATE = 24000

        fun id(pcm: ByteArray, text: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(pcm)
            md.update("\n$text".toByteArray())
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Speaking the owner's lines in the owner's voice, one job at a time on its own thread (SCREENING_ASSISTANT.md 12.2):
 * a number's set (greeting, hold, goodbye, replies) or one sample to listen to. Waits while a call is going on, so the
 * phone's processor is the call's. A line already spoken in this voice comes from the cache.
 */
class SpeechJobs(
    private val models: VoiceModels,
    private val profile: VoiceProfile,
    private val cloner: Cloner,
    private val cacheDir: File,
    private val busy: () -> Boolean,
    private val log: (String) -> Unit = {},
    private val release: () -> Unit = {},
    /** Held while a line is being spoken, so the phone gives every core (Performance.kt). */
    private val boost: () -> AutoCloseable = { AutoCloseable {} },
) {
    data class Progress(val done: Int, val total: Int, val digest: String? = null, val error: String? = null)

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "speech").apply { priority = Thread.MIN_PRIORITY } }
    private val progress = java.util.concurrent.ConcurrentHashMap<String, Progress>()

    fun progress(number: String): Progress? = progress[number]

    /** Speaks [texts] in the current voice for number [number], then hands the clips to [done]. */
    fun speakSet(number: String, voiceId: String, texts: Map<String, String>, done: (Map<String, ShortArray>) -> String) {
        progress[number] = Progress(0, texts.size)
        worker.execute {
            try {
                val v = ready(voiceId)
                val clips = LinkedHashMap<String, ShortArray>()
                for ((i, e) in texts.entries.sortedBy { it.key }.withIndex()) {
                    clips[e.key] = line(v, e.value)
                    progress[number] = Progress(i + 1, texts.size)
                }
                progress[number] = Progress(texts.size, texts.size, digest = done(clips))
                log("speech for ${number.take(12)}: ${texts.size} line(s) ready")
            } catch (e: Exception) {
                progress[number] = Progress(0, texts.size, error = e.message ?: e.javaClass.simpleName)
                log("speech for ${number.take(12)} failed: ${e.message}")
            } finally {
                release()
            }
        }
    }

    /** One line to listen to; [done] gets it, or null with why. */
    fun sample(text: String, done: (ShortArray?, String?) -> Unit) {
        worker.execute {
            try {
                val v = ready(null)
                done(line(v, text), null)
            } catch (e: Exception) {
                done(null, e.message ?: e.javaClass.simpleName)
            } finally {
                release()
            }
        }
    }

    companion object {
        /**
         * How lines are made; part of the cache key, so lines made the old way are spoken again. 2: denoised
         * reference, 8 steps, low-passed resampling (12.5).
         */
        const val RECIPE = "2"
    }

    private fun ready(voiceId: String?): VoiceProfile.Voice {
        models.ensure()
        check(models.ready()) { "models_unavailable" }
        val v = profile.get() ?: throw IllegalStateException("no_voice")
        check(voiceId == null || v.id == voiceId) { "voice_changed" }
        return v
    }

    private fun line(v: VoiceProfile.Voice, text: String): ShortArray {
        val key = MessageDigest.getInstance("SHA-256").digest("$RECIPE\n${v.id}\n$text".toByteArray()).joinToString("") { "%02x".format(it) }
        val cached = File(cacheDir, "$key.pcm")
        if (cached.isFile) return ScreenAssetStore.pcm(cached.readBytes())
        // The call has the processor: wait for it to end, up to an hour.
        var waited = 0
        while (busy() && waited < 3600) { Thread.sleep(1000); waited++ }
        val started = System.currentTimeMillis()
        val pcm = boost().use { cloner.speak(text, v.samples, v.rate, v.text) }
        log("spoke ${text.length} character(s) in ${(System.currentTimeMillis() - started) / 1000}s")
        cacheDir.mkdirs()
        cached.writeBytes(ScreenAssetStore.bytes(pcm))
        return pcm
    }
}
