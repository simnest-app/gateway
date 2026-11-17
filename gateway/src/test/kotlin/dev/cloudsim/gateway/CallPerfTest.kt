package dev.cloudsim.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The per-call line of cores and load (docs/architecture/ANSWER_DELAY.md 14.4). */
class CallPerfTest {
    @Test
    fun busyShareFromProcStat() {
        val a = CallPerf.cpu("cpu  100 0 100 700 100 0 0 0 0 0\ncpu0 1 2 3 4\n")!!
        val b = CallPerf.cpu("cpu  200 0 200 1300 100 0 0 0 0 0\n")!!
        assertEquals(200L to 1000L, a)
        assertEquals(25.0, CallPerf.busyPercent(a, b)!!, 0.01)
        assertNull(CallPerf.busyPercent(a, a))
        assertNull(CallPerf.cpu("nothing here"))
    }

    @Test
    fun appTicksSkipTheNameEvenWithSpaces() {
        val stat = "1234 (dev.cloudsim gw) S 1 2 3 0 -1 4194560 10 0 0 0 150 50 0 0 20 0 30 0 1 2 3"
        assertEquals(200L, CallPerf.appTicks(stat))
        assertNull(CallPerf.appTicks("1234 (x) S 1"))
    }

    @Test
    fun aLineTellsCoresTimeHeldBackAndLoad() {
        var now = 0L
        var allowed = "0-3"
        var stat = "cpu  0 0 0 1000 0 0 0 0\n"
        var self = "1 (app) S 1 1 1 0 -1 0 0 0 0 0 0 0 0 0"
        val files = { path: String ->
            when (path) {
                "/sys/devices/system/cpu/possible" -> "0-7"
                "/proc/self/status" -> "Name:\tapp\nCpus_allowed_list:\t$allowed\n"
                "/proc/stat" -> stat
                "/proc/self/stat" -> self
                else -> null
            }
        }
        val perf = CallPerf(files) { now }
        now = 2_000
        allowed = "0-7"
        perf.sample()
        now = 5_000
        stat = "cpu  400 0 0 1600 0 0 0 0\n"
        self = "1 (app) S 1 1 1 0 -1 0 0 0 0 0 100 0 0 0"
        val line = perf.line("abcd1234")
        assertTrue(line, line.startsWith("call_perf call=abcd1234 ms=5000 cores=4/4/8 of 8 limited_ms=2000"))
        assertTrue(line, line.contains("cpu_busy=40%"))
        // 100 of 1000 ticks over 8 cores: 80% of one core.
        assertTrue(line, line.contains("app_cpu=80%"))
    }
}
