package dev.cloudsim.gateway.screening.voice

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Where a model file is fetched from, in the order the user set (2026-11-13): sherpa-onnx's GitHub release, then the
 * project's own copy on Alibaba Cloud OSS (Shenzhen). GitHub alone often crawled or timed out from mainland China (a
 * Mate40 gateway never got its voice models, report 4WBFDX). Every copy is checked against the same SHA-256 before
 * use, so neither place needs to be trusted.
 */
object ModelSources {
    /** The project's own copies, under the same file names as the GitHub release. */
    const val OWN = "https://simnest-modes.oss-cn-shenzhen.aliyuncs.com/"

    /** Every place to try for [github] (a sherpa-onnx release address), in order. */
    fun urls(github: String): List<String> = listOf(github, OWN + github.substringAfterLast('/'))

    /** A short name for a place, for the log. */
    fun label(url: String): String = if (url.startsWith(OWN)) "oss" else if (url.startsWith("https://github.com/")) "github" else "other"
}

/** A model download as opened: its bytes, and whether they continue the [from] bytes already held (an HTTP 206). */
class ModelDownload(val input: InputStream, val resumed: Boolean)

/** Opens [url] asking for the bytes from [from] on; a server that ignores the range sends the whole file. */
fun openModel(url: String, from: Long): ModelDownload {
    val c = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        if (from > 0) setRequestProperty("Range", "bytes=$from-")
    }
    val code = c.responseCode
    if (code !in 200..299) {
        c.disconnect()
        throw IOException("HTTP $code")
    }
    return ModelDownload(c.inputStream, code == HttpURLConnection.HTTP_PARTIAL)
}
