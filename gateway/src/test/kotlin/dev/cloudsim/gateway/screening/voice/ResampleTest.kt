package dev.cloudsim.gateway.screening.voice

import dev.cloudsim.gateway.screening.ScreeningRequests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class ResampleTest {
    private fun tone(hz: Double, rate: Int, seconds: Double = 1.0) = FloatArray((rate * seconds).toInt()) { (0.5 * sin(2 * PI * hz * it / rate)).toFloat() }
    private fun rms(x: FloatArray, skip: Int) = x.drop(skip).dropLast(skip).let { s -> sqrt(s.sumOf { it.toDouble() * it } / s.size) }

    @Test
    fun theVoiceBandKeepsItsLevel() {
        val out = Resample.resample(tone(1000.0, 24000), 24000, 16000)
        assertEquals(16000, out.size)
        assertEquals(0.5 / sqrt(2.0), rms(out, 400), 0.01)
    }

    /** 10 kHz at 24 kHz has no place at 16 kHz: the old linear step folded it to 6 kHz, this takes it out. */
    @Test
    fun whatTheNewRateCannotHoldDoesNotFoldBack() {
        val out = Resample.resample(tone(10000.0, 24000), 24000, 16000)
        assertTrue("left ${rms(out, 400)}", rms(out, 400) < 0.005)
        val down = Resample.resample(tone(5000.0, 16000), 16000, 8000)
        assertTrue("left ${rms(down, 200)}", rms(down, 200) < 0.005)
    }

    @Test
    fun theSampleIsAn8kHzWav() {
        val pcm = ShortArray(16000) { (8000 * sin(2 * PI * 440 * it / 16000)).toInt().toShort() }
        val wav = ScreeningRequests.wav8k(pcm)
        assertEquals(44 + 8000 * 2, wav.size)
        assertEquals(8000, java.nio.ByteBuffer.wrap(wav, 24, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int)
    }

    @Test
    fun pcmPeaksAtMinusOneDb() {
        val p = Resample.toPcm(floatArrayOf(0.1f, -0.2f, 0.05f))
        assertEquals(-29196.0, p[1].toDouble(), 2.0)
    }
}
