package dev.cloudsim.gateway.calls

import dev.cloudsim.gateway.calls.SignalScale.Cell
import dev.cloudsim.gateway.calls.SignalScale.Rat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalScaleTest {
    // As the gateway read them on 2026-11-18 (dumpsys telephony.registry).
    private val card2 = Cell(Rat.NR, dbm = -93, sinr = 19, rsrq = -10, androidLevel = 1)
    private val card1 = Cell(Rat.LTE, dbm = -100, sinr = -2, rsrq = -14, androidLevel = 3)

    @Test
    fun theTwoCardsAsMeasured() {
        // 5G at -93 to -96 dBm with an SINR of 19 dB: three bars, where Android said one.
        assertEquals(3, SignalScale.bars(card2))
        assertEquals(3, SignalScale.bars(card2.copy(dbm = -96)))
        // 4G at -100 dBm with an SNR of -2 dB: one bar for its poor quality, where Android said three.
        assertEquals(1, SignalScale.bars(card1))
        val s = SignalScale.of(listOf(card2))!!
        assertEquals("5G · -93 dBm · SINR 19 dB", s.detail())
        assertTrue(s.measured)
        assertEquals(1, s.androidLevel)
    }

    @Test
    fun strengthAlone() {
        assertEquals(4, SignalScale.bars(Cell(Rat.NR, dbm = -85)))
        assertEquals(4, SignalScale.bars(Cell(Rat.LTE, dbm = -90)))
        assertEquals(3, SignalScale.bars(Cell(Rat.LTE, dbm = -91)))
        assertEquals(2, SignalScale.bars(Cell(Rat.LTE, dbm = -105)))
        assertEquals(1, SignalScale.bars(Cell(Rat.NR, dbm = -115)))
        assertEquals(0, SignalScale.bars(Cell(Rat.NR, dbm = -121)))
        assertEquals(4, SignalScale.bars(Cell(Rat.WCDMA, dbm = -80)))
        assertEquals(1, SignalScale.bars(Cell(Rat.WCDMA, dbm = -110)))
        assertEquals(4, SignalScale.bars(Cell(Rat.GSM, dbm = -85)))
        assertEquals(0, SignalScale.bars(Cell(Rat.GSM, dbm = -110)))
    }

    @Test
    fun theWorseOfStrengthAndQuality() {
        assertEquals(4, SignalScale.bars(Cell(Rat.NR, dbm = -80, sinr = 25, rsrq = -8)))
        assertEquals(2, SignalScale.bars(Cell(Rat.NR, dbm = -80, sinr = 5)))
        assertEquals(2, SignalScale.bars(Cell(Rat.LTE, dbm = -80, rsrq = -18)))
        // Quality takes a cell down to one bar, never to none.
        assertEquals(1, SignalScale.bars(Cell(Rat.LTE, dbm = -80, sinr = -15, rsrq = -30)))
        // None is strength's alone.
        assertEquals(0, SignalScale.bars(Cell(Rat.LTE, dbm = -125, sinr = 25)))
        assertEquals(3, SignalScale.bars(Cell(Rat.WCDMA, dbm = -80, ecno = -8)))
    }

    @Test
    fun theBestCellOfACard() {
        // 5G alongside its 4G anchor: the better.
        val s = SignalScale.of(listOf(card1, card2))!!
        assertEquals(Rat.NR, s.rat)
        assertEquals(3, s.bars)
        // Equal bars: the 5G one.
        assertEquals(Rat.NR, SignalScale.of(listOf(Cell(Rat.LTE, dbm = -95), Cell(Rat.NR, dbm = -95)))!!.rat)
    }

    @Test
    fun androidsLevelWhenNothingIsMeasured() {
        // Nothing usable read: Android's level stands.
        assertNull(SignalScale.bars(Cell(Rat.LTE, sinr = 10, androidLevel = 2)))
        val s = SignalScale.of(listOf(Cell(Rat.LTE, androidLevel = 2)))!!
        assertEquals(2, s.bars)
        assertFalse(s.measured)
        assertEquals("4G", s.detail())
        assertEquals(1, SignalScale.of(listOf(Cell(Rat.OTHER, androidLevel = 1)))!!.bars)
        // No cells listed at all: the overall level.
        assertEquals(4, SignalScale.of(emptyList(), 4)!!.bars)
        assertNull(SignalScale.of(emptyList()))
        // A measured cell wins over an unmeasured one with a higher level.
        assertEquals(1, SignalScale.of(listOf(Cell(Rat.GSM, androidLevel = 4), card1))!!.bars)
    }

    @Test
    fun historyLinesEveryFiveMinutesAndOnBigChanges() {
        var t = 1_000_000L
        val out = mutableListOf<String>()
        val h = SignalHistory({ t }, { out += it })
        val sig = { c: Cell -> SignalScale.of(listOf(c)) }
        h.observe(2, "in_service", sig(card2))
        assertEquals(1, out.size)
        assertTrue(out[0], out[0].endsWith("signal slot=2 state=in_service rat=5G dbm=-93 sinr=19 rsrq=-10 bars=3 android=1 why=first"))
        // A few dB and one bar: not yet.
        t += 60_000; h.observe(2, "in_service", sig(card2.copy(dbm = -101)))
        assertEquals(1, out.size)
        // Five minutes on: again.
        t += SignalHistory.PERIOD_MS; h.observe(2, "in_service", sig(card2))
        assertTrue(out.last().endsWith("why=periodic"))
        // Two bars apart (a poor SINR) with the same strength: at once.
        t += 60_000; h.observe(2, "in_service", sig(card2.copy(sinr = -1)))
        assertEquals(3, out.size)
        assertTrue(out.last().endsWith("bars=1 android=1 why=change"))
        // 10 dB: at once.
        t += 60_000; h.observe(2, "in_service", sig(card2.copy(dbm = -103, sinr = -1)))
        assertEquals(4, out.size)
        // Another technology, out of service: each at once.
        t += 60_000; h.observe(2, "in_service", sig(Cell(Rat.LTE, dbm = -103)))
        t += 60_000; h.observe(2, "no_service", null)
        assertEquals(6, out.size)
        assertTrue(out.last(), out.last().endsWith("signal slot=2 state=no_service why=change"))
        // Cards are kept apart.
        h.observe(1, "in_service", sig(card1))
        assertEquals(7, out.size)
        assertTrue(out.last().contains("slot=1 state=in_service rat=4G dbm=-100 sinr=-2 rsrq=-14 bars=1 android=3"))
    }
}
