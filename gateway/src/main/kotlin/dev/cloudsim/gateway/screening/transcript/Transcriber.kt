package dev.cloudsim.gateway.screening.transcript

import java.io.File
import java.util.concurrent.Executors

/** Speech cut into sentences and written down; one per call, opened and closed on the transcriber's thread. */
interface Asr {
    interface Session {
        /** 16 kHz mono; the sentences that ended in it. */
        fun accept(pcm: FloatArray): List<FloatArray>
        /** What is left at the end of the call. */
        fun finish(): List<FloatArray>
        fun recognize(sentence: FloatArray): String
        fun close()
    }

    /** null when the models are not here (the call goes untranscribed). */
    fun open(): Session?
}

/**
 * Writes down what the caller says while the assistant has the call (SCREENING_ASSISTANT.md 13), on its own thread
 * so neither the call nor the bridge waits: the models load during the rings before the assistant answers, each
 * sentence is written down when the caller pauses, and the models go when the call ends.
 */
class Transcriber(private val asr: Asr, private val transcripts: Transcripts, private val log: (String) -> Unit = {},
                  /** Held from the rings to the end of the assistant's part, so the phone gives every core (Performance.kt). */
                  private val boost: () -> AutoCloseable = { AutoCloseable {} }) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "transcribe").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val sessions = HashMap<String, Asr.Session>()
    private val boosts = HashMap<String, AutoCloseable>()

    /** The call may be screened: load the models now. */
    fun prepare(callId: String) {
        transcripts.begin(callId)
        worker.execute {
            if (callId in sessions) return@execute
            if (callId !in boosts) runCatching { boost() }.getOrNull()?.let { boosts[callId] = it }
            val started = System.currentTimeMillis()
            val s = runCatching { asr.open() }.onFailure { log("transcription unavailable: ${it.javaClass.simpleName} ${it.message}") }.getOrNull() ?: return@execute
            sessions[callId] = s
            log("transcription ready in ${System.currentTimeMillis() - started} ms")
        }
    }

    /** The caller's audio, 16 kHz mono, as the assistant hears it. */
    fun heard(callId: String, pcm: ShortArray) {
        val f = FloatArray(pcm.size) { pcm[it] / 32768f }
        worker.execute {
            val s = sessions[callId] ?: return@execute
            write(callId, s, runCatching { s.accept(f) }.getOrDefault(emptyList()))
        }
    }

    /** The assistant no longer has the call (taken, or over): the last words, then the models go. */
    fun stop(callId: String, over: Boolean) {
        worker.execute {
            sessions.remove(callId)?.let { s ->
                write(callId, s, runCatching { s.finish() }.getOrDefault(emptyList()))
                runCatching { s.close() }
            }
            boosts.remove(callId)?.let { runCatching { it.close() } }
            if (over) transcripts.end(callId)
        }
    }

    private fun write(callId: String, s: Asr.Session, sentences: List<FloatArray>) {
        for (seg in sentences) {
            val started = System.currentTimeMillis()
            val text = runCatching { s.recognize(seg) }.getOrDefault("")
            if (transcripts.add(callId, "caller", text) != null) log("transcribed ${seg.size / 16} ms in ${System.currentTimeMillis() - started} ms")
        }
    }
}

/** silero VAD and SenseVoice through sherpa-onnx (already in the app for the owner's voice). */
class SherpaAsr(private val models: AsrModels) : Asr {
    override fun open(): Asr.Session? {
        if (!models.ready()) return null
        val vad = com.k2fsa.sherpa.onnx.Vad(null, com.k2fsa.sherpa.onnx.VadModelConfig(
            sileroVadModelConfig = com.k2fsa.sherpa.onnx.SileroVadModelConfig(model = models.vad.path, threshold = 0.5f,
                minSilenceDuration = 0.5f, minSpeechDuration = 0.25f, windowSize = 512, maxSpeechDuration = 15f),
            sampleRate = 16000, numThreads = 1))
        val rec = com.k2fsa.sherpa.onnx.OfflineRecognizer(null, com.k2fsa.sherpa.onnx.OfflineRecognizerConfig(
            modelConfig = com.k2fsa.sherpa.onnx.OfflineModelConfig(
                senseVoice = com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig(model = File(models.sensevoice, "model.int8.onnx").path,
                    language = "auto", useInverseTextNormalization = true),
                tokens = File(models.sensevoice, "tokens.txt").path, numThreads = 2)))
        return object : Asr.Session {
            override fun accept(pcm: FloatArray): List<FloatArray> {
                vad.acceptWaveform(pcm)
                return drain()
            }

            override fun finish(): List<FloatArray> {
                vad.flush()
                return drain()
            }

            private fun drain(): List<FloatArray> {
                val out = mutableListOf<FloatArray>()
                while (!vad.empty()) {
                    out += vad.front().samples
                    vad.pop()
                }
                return out
            }

            override fun recognize(sentence: FloatArray): String {
                val stream = rec.createStream()
                try {
                    stream.acceptWaveform(sentence, 16000)
                    rec.decode(stream)
                    return rec.getResult(stream).text
                } finally {
                    stream.release()
                }
            }

            override fun close() {
                vad.release()
                rec.release()
            }
        }
    }
}
