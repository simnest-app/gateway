package dev.cloudsim.gateway.screening.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sample-rate conversion with a low-pass first (windowed sinc, Kaiser β 8), so what lies above the new rate's
 * half does not fold back into the voice. The plain linear step it replaces did fold it back, which the owner heard
 * as grit and breaking on the gateway's samples (2026-11-10, comparison in SCREENING_ASSISTANT.md 12.5).
 */
object Resample {
    /** Cut-off at 45 % of the lower rate: the top of the band rolls off rather than aliasing. */
    private const val CUTOFF = 0.45
    /** Zero crossings of the sinc on each side. */
    private const val ZEROS = 16
    private const val BETA = 8.0

    fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || x.isEmpty()) return x.copyOf()
        val n = (x.size.toLong() * to / from).toInt()
        // Cut-off in cycles per input sample, and the filter's half-width in input samples.
        val fc = CUTOFF * minOf(from, to) / from
        val half = ZEROS / (2 * fc)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() * from / to
            val lo = maxOf(0, ceil(t - half).toInt())
            val hi = minOf(x.size - 1, floor(t + half).toInt())
            var acc = 0.0
            var sum = 0.0
            for (k in lo..hi) {
                val d = t - k
                val w = 2 * fc * sinc(2 * fc * d) * kaiser(d / half)
                acc += w * x[k]
                sum += w
            }
            // Normalised per output, so the level is kept even at the ends.
            out[i] = if (abs(sum) > 1e-9) (acc / sum).toFloat() else 0f
        }
        return out
    }

    /** Float samples to 16-bit with a peak of -1 dBFS (the level found clear on a call). */
    fun toPcm(x: FloatArray): ShortArray {
        if (x.isEmpty()) return ShortArray(0)
        val peak = x.maxOf { abs(it) }.coerceAtLeast(1e-6f)
        val gain = 0.891f / peak
        return ShortArray(x.size) { (x[it] * gain * 32767).toInt().coerceIn(-32768, 32767).toShort() }
    }

    private fun sinc(v: Double) = if (abs(v) < 1e-12) 1.0 else sin(PI * v) / (PI * v)

    private fun kaiser(r: Double): Double = if (abs(r) > 1) 0.0 else i0(BETA * sqrt(1 - r * r)) / I0_BETA

    private val I0_BETA = i0(BETA)

    /** The modified Bessel function of the first kind, order 0, by its series. */
    private fun i0(v: Double): Double {
        var sum = 1.0
        var term = 1.0
        val q = v * v / 4
        var k = 1
        while (term > 1e-12 * sum) {
            term *= q / (k.toDouble() * k)
            sum += term
            k++
        }
        return sum
    }
}
