package dev.cloudsim.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceGateTest {
    private var clock = 0L
    private var limited = true
    private val fronts = mutableListOf<String>()
    private var backs = 0
    /** The page gives every core as soon as it is shown, unless [stubborn]. */
    private var stubborn = false

    private fun gate() = PerformanceGate(
        limited = { limited },
        front = { fronts += it; if (!stubborn) limited = false },
        back = { backs++; limited = true },
        now = { clock },
        sleep = { clock += it },
        watching = false)

    @Test
    fun countsTheCoresOfAList() {
        assertEquals(4, PerformanceGate.count("0-3"))
        assertEquals(8, PerformanceGate.count("0-7\n"))
        assertEquals(5, PerformanceGate.count("0-3,6"))
        assertEquals(1, PerformanceGate.count("5"))
        assertEquals(0, PerformanceGate.count(""))
    }

    @Test
    fun limitedWhenFewerCoresThanThePhoneHas() {
        val status = "Name:\tx\nCpus_allowed:\t0f\nCpus_allowed_list:\t0-3\nMems_allowed:\t1\n"
        assertTrue(PerformanceGate.limited(status, "0-7\n"))
        assertFalse(PerformanceGate.limited(status.replace("0-3", "0-7"), "0-7\n"))
        // Not readable: nothing is done rather than a screen lit for nothing.
        assertFalse(PerformanceGate.limited(null, "0-7"))
        assertFalse(PerformanceGate.limited(status, null))
        assertFalse(PerformanceGate.limited("Name:\tx\n", "0-7"))
    }

    @Test
    fun nothingShowsOnAPhoneThatGivesEveryCore() {
        limited = false
        val g = gate()
        g.hold("speech").close()
        clock += 10_000
        g.step()
        assertTrue(fronts.isEmpty())
        assertEquals(0, backs)
    }

    @Test
    fun showsThePageWhileHeldAndTakesItAwayALittleAfter() {
        val g = gate()
        val h = g.hold("speech")
        assertEquals(listOf("speech"), fronts)
        assertFalse(limited)
        h.close()
        // Between two lines of a set the page stays.
        clock += PerformanceGate.LINGER_MS - 1
        assertTrue(g.step())
        assertEquals(0, backs)
        val again = g.hold("speech")
        assertEquals(1, fronts.size)
        again.close()
        clock += PerformanceGate.LINGER_MS
        assertFalse(g.step())
        assertEquals(1, backs)
    }

    @Test
    fun staysWhileAnyoneHolds() {
        val g = gate()
        val a = g.hold("speech")
        val b = g.hold("transcribe", wait = false)
        assertEquals(listOf("transcribe", "speech"), g.reasons())
        a.close()
        clock += 60_000
        assertTrue(g.step())
        assertEquals(0, backs)
        b.close()
        clock += PerformanceGate.LINGER_MS
        g.step()
        assertEquals(1, backs)
        assertTrue(g.reasons().isEmpty())
    }

    @Test
    fun triesAgainWhenThePageDidNotGiveTheCoresAndGivesUpWaiting() {
        stubborn = true
        val g = gate()
        val started = clock
        val h = g.hold("speech")
        // The work starts after the wait at most, on what cores there are.
        assertTrue(clock - started <= PerformanceGate.WAIT_MS + 200)
        assertEquals(1, fronts.size)
        clock += PerformanceGate.RETRY_MS
        g.step()
        assertEquals(2, fronts.size)
        h.close()
    }

    @Test
    fun aHolderThatNeverLetGoDoesNotKeepTheScreenOnForEver() {
        val g = gate()
        g.hold("transcribe", wait = false)
        clock += PerformanceGate.MAX_HOLD_MS + 1
        g.step()
        clock += PerformanceGate.LINGER_MS
        assertFalse(g.step())
        assertEquals(1, backs)
    }

    @Test
    fun closingTwiceIsHarmless() {
        val g = gate()
        val h = g.hold("speech")
        h.close()
        h.close()
        clock += PerformanceGate.LINGER_MS
        g.step()
        assertEquals(1, backs)
    }
}
