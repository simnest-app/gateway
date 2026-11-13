package dev.cloudsim.gateway.screening.voice

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The voice-cloning models on the gateway (SCREENING_ASSISTANT.md 12.2): ZipVoice distill int8, the 24 kHz vocos
 * vocoder and the GTCRN speech denoiser (for the owner's recording, 12.5) from sherpa-onnx's GitHub releases, each checked against its SHA-256 before use. A copy put in the app's
 * external files under models/ (for a phone that cannot reach GitHub) is used instead of downloading, if it checks out.
 * Never shipped with the app: the user's gateway fetches them for itself.
 */
class VoiceModels(
    private val dir: File,
    private val sideload: File?,
    private val open: (String) -> InputStream = { url -> (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 60_000; instanceFollowRedirects = true }.inputStream },
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

    val zipvoice get() = File(dir, ZIPVOICE_DIR)
    val vocoder get() = File(dir, VOCODER)
    val denoiser get() = File(dir, DENOISER)

    fun ready() = File(zipvoice, "encoder.int8.onnx").isFile && File(zipvoice, "decoder.int8.onnx").isFile &&
        File(zipvoice, "tokens.txt").isFile && vocoder.isFile && denoiser.isFile

    /** Fetches what is missing; blocks, so it runs on the job thread. Safe to call again after a failure. */
    @Synchronized
    fun ensure() {
        if (ready()) { state = State.Ready; return }
        dir.mkdirs()
        try {
            state = State.Downloading(0)
            if (!vocoder.isFile) fetch(VOCODER_URL, VOCODER_SHA, VOCODER_BYTES, 0, 33) { tmp -> tmp.renameTo(vocoder) }
            if (!denoiser.isFile) fetch(DENOISER_URL, DENOISER_SHA, DENOISER_BYTES, 33, 34) { tmp -> tmp.renameTo(denoiser) }
            if (!File(zipvoice, "encoder.int8.onnx").isFile) fetch(ZIPVOICE_URL, ZIPVOICE_SHA, ZIPVOICE_BYTES, 34, 100) { tmp ->
                val staging = File(dir, "$ZIPVOICE_DIR.part").apply { deleteRecursively() }
                untar(tmp, staging)
                tmp.delete()
                zipvoice.deleteRecursively()
                File(staging, ZIPVOICE_DIR).renameTo(zipvoice)
                staging.deleteRecursively()
            }
            state = if (ready()) State.Ready else State.Failed("incomplete")
            log("voice models ready")
        } catch (e: Exception) {
            state = State.Failed(e.message ?: e.javaClass.simpleName)
            log("voice models: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun fetch(url: String, sha: String, size: Long, from: Int, to: Int, place: (File) -> Unit) =
        fetchModel(dir, sideload, open, log, url, sha, size, { state = State.Downloading(from + ((to - from) * it / size).toInt().coerceIn(0, to - from)) }, place)

    private fun untar(archive: File, into: File) = untarModel(archive, into)

    companion object {
        const val ZIPVOICE_DIR = "sherpa-onnx-zipvoice-distill-int8-zh-en-emilia"
        const val ZIPVOICE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$ZIPVOICE_DIR.tar.bz2"
        const val ZIPVOICE_SHA = "77219c8b40f4ee8d73a7f902305ff6c1128ef9b54461c41b4ca6ed890b6c2803"
        const val ZIPVOICE_BYTES = 109_162_785L
        const val VOCODER = "vocos_24khz.onnx"
        const val VOCODER_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models/$VOCODER"
        const val VOCODER_SHA = "bcb3b970e384161c4d634f0bb9e999ff1c471b34c9bc0b1049a5014065ed3cc0"
        const val VOCODER_BYTES = 54_157_409L
        /** GTCRN (MIT), 16 kHz in and out. */
        const val DENOISER = "gtcrn_simple.onnx"
        const val DENOISER_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speech-enhancement-models/$DENOISER"
        const val DENOISER_SHA = "e77603ac0c23dac3227dd2d7135b3a585cbee2679048aecfa886657d3ae1b534"
        const val DENOISER_BYTES = 535_638L

        fun sha256(f: File): String = modelSha256(f)
    }
}

/** One model file from [url] (or a side-loaded copy under [sideload] that checks out), checked, then [place]d. */
internal fun fetchModel(dir: File, sideload: File?, open: (String) -> InputStream, log: (String) -> Unit, url: String, sha: String, size: Long,
                        progress: (Long) -> Unit, place: (File) -> Unit) {
    val name = url.substringAfterLast('/')
    val tmp = File(dir, "$name.download")
    val local = sideload?.let { File(it, name) }?.takeIf { it.isFile && modelSha256(it) == sha }
    if (local != null) {
        local.copyTo(tmp, overwrite = true)
        log("model $name taken from the side-loaded copy")
    } else {
        open(url).use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var got = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    got += n
                    progress(got)
                }
            }
        }
    }
    if (modelSha256(tmp) != sha) { tmp.delete(); throw IllegalStateException("checksum mismatch for $name") }
    place(tmp)
}

/** A tar.bz2 into [into]; nothing may land outside it (an archive's ../ entries). */
internal fun untarModel(archive: File, into: File) {
    into.mkdirs()
    val root = into.canonicalPath + File.separator
    TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tar ->
        while (true) {
            val e = tar.nextEntry ?: break
            val out = File(into, e.name)
            require(out.canonicalPath.startsWith(root)) { "bad entry ${e.name}" }
            if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { tar.copyTo(it) } }
        }
    }
}

internal fun modelSha256(f: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    f.inputStream().use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}
