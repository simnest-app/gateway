package dev.cloudsim.gateway.screening.transcript

import dev.cloudsim.gateway.screening.voice.ModelDownload
import dev.cloudsim.gateway.screening.voice.ModelSources
import dev.cloudsim.gateway.screening.voice.fetchModel
import dev.cloudsim.gateway.screening.voice.openModel
import dev.cloudsim.gateway.screening.voice.untarModel
import java.io.File

/**
 * The models for transcribing a screened call (SCREENING_ASSISTANT.md 13): silero VAD (MIT) to cut the caller's
 * speech into sentences and SenseVoice int8 (FunASR model licence) to write each one down, from sherpa-onnx's
 * GitHub releases, each checked against its SHA-256; a copy under the app's external files models/ is used instead
 * of downloading if it checks out. Never shipped with the app: the user's gateway fetches them for itself.
 */
class AsrModels(
    private val dir: File,
    private val sideload: File?,
    private val open: (String, Long) -> ModelDownload = ::openModel,
    /** Where each file may come from, in order (ModelSources). */
    private val sources: (String) -> List<String> = { ModelSources.urls(it) },
    private val log: (String) -> Unit = {},
) {
    sealed interface State {
        data object Missing : State
        data class Downloading(val percent: Int) : State
        data object Ready : State
        data class Failed(val why: String) : State
    }

    @Volatile var state: State = if (ready()) State.Ready else State.Missing
        private set

    val vad get() = File(dir, VAD)
    val sensevoice get() = File(dir, SENSEVOICE_DIR)

    fun ready() = vad.isFile && File(sensevoice, "model.int8.onnx").isFile && File(sensevoice, "tokens.txt").isFile

    /** Fetches what is missing; blocks. Safe to call again after a failure. */
    @Synchronized
    fun ensure() {
        if (ready()) { state = State.Ready; return }
        dir.mkdirs()
        try {
            state = State.Downloading(0)
            if (!vad.isFile) fetchModel(dir, sideload, open, log, sources(VAD_URL), VAD_SHA, VAD_BYTES, {}) { it.renameTo(vad) }
            if (!File(sensevoice, "model.int8.onnx").isFile) fetchModel(dir, sideload, open, log, sources(SENSEVOICE_URL), SENSEVOICE_SHA, SENSEVOICE_BYTES,
                { state = State.Downloading((100 * it / SENSEVOICE_BYTES).toInt().coerceIn(0, 99)) }) { tmp ->
                val staging = File(dir, "$SENSEVOICE_DIR.part").apply { deleteRecursively() }
                untarModel(tmp, staging)
                tmp.delete()
                sensevoice.deleteRecursively()
                File(staging, SENSEVOICE_DIR).renameTo(sensevoice)
                staging.deleteRecursively()
            }
            state = if (ready()) State.Ready else State.Failed("incomplete")
            log("transcription models ready")
        } catch (e: Exception) {
            state = State.Failed(e.message ?: e.javaClass.simpleName)
            log("transcription models: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    companion object {
        const val VAD = "silero_vad.onnx"
        const val VAD_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$VAD"
        const val VAD_SHA = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"
        const val VAD_BYTES = 643_854L
        const val SENSEVOICE_DIR = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09"
        const val SENSEVOICE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$SENSEVOICE_DIR.tar.bz2"
        const val SENSEVOICE_SHA = "7305f7905bfcf77fa0b39388a313f3da35c68d971661a65475b56fb2162c8e63"
        const val SENSEVOICE_BYTES = 165_783_878L
    }
}
