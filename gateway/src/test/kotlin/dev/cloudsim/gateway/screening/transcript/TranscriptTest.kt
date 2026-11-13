package dev.cloudsim.gateway.screening.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TranscriptTest {
    @get:Rule val tmp = TemporaryFolder()
    private var clock = 1_000L
    private val call = "0123abcd-0000"

    @Test
    fun linesAreNumberedFollowedAndKept() {
        val t = Transcripts(tmp.root) { clock }
        assertNull(t.add(call, "caller", "没开始"))
        t.begin(call)
        clock += 2_000
        t.add(call, "assistant", "您好，我是机主的自动接听助手。")
        val heard = mutableListOf<Transcripts.Entry>()
        assertTrue(t.follow(call) { heard += it })
        clock += 3_000
        t.add(call, "caller", "我是快递，放门口可以吗")
        assertNull(t.add(call, "caller", "   "))
        assertEquals(listOf(1, 2), t.entries(call)!!.map { it.seq })
        assertEquals(listOf("caller"), heard.map { it.who })
        assertEquals(5_000L, heard[0].atMs)
        t.end(call)
        assertFalse(t.isLive(call))
        assertFalse(t.follow(call) {})
        // Kept on this phone after the call.
        assertEquals("我是快递，放门口可以吗", Transcripts(tmp.root).entries(call)!![1].text)
    }

    @Test
    fun aCallWithNothingSaidLeavesNothing() {
        val t = Transcripts(tmp.root)
        t.begin(call)
        t.end(call)
        assertNull(t.entries(call))
    }

    private class FakeAsr(private val sentences: List<String>) : Asr {
        var opened = 0
        var closed = 0
        override fun open(): Asr.Session {
            opened++
            var fed = 0
            var said = 0
            return object : Asr.Session {
                // A sentence ends every 16000 samples fed.
                override fun accept(pcm: FloatArray): List<FloatArray> {
                    fed += pcm.size
                    val out = mutableListOf<FloatArray>()
                    while (fed >= 16000) { fed -= 16000; out += FloatArray(16000) }
                    return out
                }
                override fun finish() = if (fed > 0) listOf(FloatArray(fed)).also { fed = 0 } else emptyList()
                override fun recognize(sentence: FloatArray) = sentences.getOrElse(said++) { "" }
                override fun close() { closed++ }
            }
        }
    }

    @Test
    fun theCallersSentencesAreWrittenDownAndTheModelsGoAtTheEnd() {
        val t = Transcripts(tmp.root)
        val asr = FakeAsr(listOf("喂你好", "我找一下机主"))
        val w = Transcriber(asr, t)
        w.prepare(call)
        repeat(12) { w.heard(call, ShortArray(1600)) }   // 1.2 s: one sentence, a bit left over
        w.stop(call, over = true)
        val done = CountDownLatch(1)
        w.stop("ffff0000-0000", over = false)
        // The worker runs in order; wait for it to get past this call.
        Thread { while (t.isLive(call)) Thread.sleep(5); done.countDown() }.start()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("喂你好", "我找一下机主"), t.entries(call)!!.map { it.text })
        assertEquals(1, asr.opened)
        assertEquals(1, asr.closed)
    }

    @Test
    fun withoutModelsTheCallGoesUntranscribed() {
        val t = Transcripts(tmp.root)
        val w = Transcriber(object : Asr { override fun open(): Asr.Session? = null }, t)
        w.prepare(call)
        w.heard(call, ShortArray(32000))
        t.add(call, "assistant", "您好")
        w.stop(call, over = true)
        Thread.sleep(100)
        assertEquals(listOf("assistant"), t.entries(call)!!.map { it.who })
    }
}
