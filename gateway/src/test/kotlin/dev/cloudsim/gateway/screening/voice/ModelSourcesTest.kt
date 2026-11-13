package dev.cloudsim.gateway.screening.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class ModelSourcesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val github = "https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models/vocos_24khz.onnx"

    @Test fun `GitHub first, then the project's own copy`() {
        val urls = ModelSources.urls(github)
        assertEquals(listOf(github, "https://simnest-modes.oss-cn-shenzhen.aliyuncs.com/vocos_24khz.onnx"), urls)
        assertEquals(listOf("github", "oss"), urls.map(ModelSources::label))
    }

    private val bytes = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A place that sends [count] bytes from where it is asked (or from the start, without range support), then stops. */
    private fun place(count: Int = bytes.size, ranges: Boolean = true, bad: Boolean = false): (Long) -> ModelDownload = { from ->
        val start = if (ranges) from.toInt() else 0
        val end = minOf(bytes.size, start + count)
        val part = bytes.copyOfRange(start, end).also { if (bad && it.isNotEmpty()) it[0] = (it[0] + 1).toByte() }
        ModelDownload(ByteArrayInputStream(part), ranges && from > 0)
    }

    private fun fetch(places: Map<String, (Long) -> ModelDownload>, asked: MutableList<Pair<String, Long>>): File {
        val dir = tmp.newFolder()
        var out: File? = null
        fetchModel(dir, null, { url, from -> asked += url to from; (places[url] ?: throw IOException("unreachable"))(from) }, {},
            places.keys.toList() + "https://unused/vocos_24khz.onnx", sha, bytes.size.toLong(), {}) { out = it }
        return out!!
    }

    @Test fun `a place that breaks off is continued at the next, for the rest only`() {
        val asked = mutableListOf<Pair<String, Long>>()
        val file = fetch(linkedMapOf("https://github.com/a/vocos_24khz.onnx" to place(count = 100_000), "https://simnest-modes.oss-cn-shenzhen.aliyuncs.com/vocos_24khz.onnx" to place()), asked)
        assertTrue(file.readBytes().contentEquals(bytes))
        assertEquals(listOf("https://github.com/a/vocos_24khz.onnx" to 0L, "https://simnest-modes.oss-cn-shenzhen.aliyuncs.com/vocos_24khz.onnx" to 100_000L), asked)
    }

    @Test fun `a place without ranges starts the file over`() {
        val asked = mutableListOf<Pair<String, Long>>()
        val file = fetch(linkedMapOf("https://github.com/a/vocos_24khz.onnx" to place(count = 100_000), "http://h:8780/models/vocos_24khz.onnx" to place(ranges = false)), asked)
        assertTrue(file.readBytes().contentEquals(bytes))
    }

    @Test fun `wrong bytes are thrown away and the next place starts from zero`() {
        val asked = mutableListOf<Pair<String, Long>>()
        val file = fetch(linkedMapOf("https://github.com/b/vocos_24khz.onnx" to place(bad = true), "https://simnest-modes.oss-cn-shenzhen.aliyuncs.com/vocos_24khz.onnx" to place()), asked)
        assertTrue(file.readBytes().contentEquals(bytes))
        assertEquals(0L, asked[1].second)
    }

    @Test fun `nowhere to get it from fails`() {
        try {
            fetchModel(tmp.newFolder(), null, { _, _ -> throw IOException("unreachable") }, {}, listOf("https://github.com/a/vocos_24khz.onnx"), sha, bytes.size.toLong(), {}) { }
            fail("fetched from nowhere")
        } catch (e: IOException) {
            assertEquals("unreachable", e.message)
        }
    }

}
