package dev.cloudsim.gateway.calls

import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellSignalStrength
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthWcdma
import android.telephony.SignalStrength

/**
 * The gateway's own signal bars, 0–4, from what the modem measured (docs/architecture/SIGNAL_BARS.md). Android's
 * `level` is not used while a measurement can be read: its default 5G scale gives one bar at -93 dBm with an SINR of
 * 19 dB, and its 4G scale three at -100 dBm with an SNR of -2 dB (2026-11-18), the wrong way round.
 *
 * A cell's bars are the worse of its strength and its quality; a card's are those of its best cell (5G alongside its
 * 4G anchor shows the better, as the status bar does). With nothing measured, Android's level.
 */
object SignalScale {
    enum class Rat(val label: String) { NR("5G"), LTE("4G"), WCDMA("3G"), GSM("2G"), OTHER("") }

    /**
     * One cell as measured. [dbm]: SS-RSRP (5G), RSRP (4G), RSCP (3G) or RSSI (2G). [sinr]: SS-SINR (5G) or RS-SNR
     * (4G). [rsrq]: SS-RSRQ (5G) or RSRQ (4G). [ecno]: Ec/No (3G). Null for what was not reported.
     */
    data class Cell(val rat: Rat, val dbm: Int? = null, val sinr: Int? = null, val rsrq: Int? = null, val ecno: Int? = null, val androidLevel: Int = 0)

    /** A card's signal: the bars, and the cell they came from; [measured] false when they are Android's level. */
    data class Signal(val bars: Int, val rat: Rat, val dbm: Int?, val sinr: Int?, val rsrq: Int?, val androidLevel: Int, val measured: Boolean) {
        /** As the gateway's screens show it, like "5G · -93 dBm · SINR 19 dB"; empty with nothing measured. */
        fun detail(): String = listOfNotNull(rat.label.ifEmpty { null }, dbm?.let { "$it dBm" }, sinr?.let { "SINR $it dB" }).joinToString(" · ")
    }

    // Strength: the same scale for 5G SS-RSRP and 4G RSRP, both the power of one reference-signal resource element.
    private val RSRP = intArrayOf(-120, -110, -100, -90)
    // 3G RSCP and 2G RSSI: Android's own defaults (CarrierConfigManager, CellSignalStrengthGsm), which read right there.
    private val RSCP = intArrayOf(-115, -105, -95, -85)
    private val GSM_RSSI = intArrayOf(-107, -103, -97, -89)
    // Quality only ever takes a cell down to one bar: a cell with poor quality still carries a call; none is strength's.
    private val SINR = intArrayOf(Int.MIN_VALUE, 0, 13, 20)
    private val RSRQ = intArrayOf(Int.MIN_VALUE, -20, -15, -10)
    private val ECNO = intArrayOf(Int.MIN_VALUE, -15, -10, -6)

    /** Bars for [value] against four rising thresholds: at or above the nth, n + 1 bars. */
    private fun scale(value: Int, thresholds: IntArray): Int = thresholds.count { value >= it }

    /** This cell's bars, or null when nothing in it was measured. */
    fun bars(c: Cell): Int? {
        val strength = c.dbm?.let {
            when (c.rat) {
                Rat.NR, Rat.LTE -> scale(it, RSRP)
                Rat.WCDMA -> scale(it, RSCP)
                Rat.GSM -> scale(it, GSM_RSSI)
                Rat.OTHER -> null
            }
        } ?: return null
        val quality = listOfNotNull(c.sinr?.let { scale(it, SINR) }, c.rsrq?.let { scale(it, RSRQ) }, c.ecno?.let { scale(it, ECNO) }).minOrNull()
        return if (quality == null) strength else minOf(strength, quality)
    }

    /** A card's signal from its cells; [fallback] (Android's overall level) when none was measured, null with no cells. */
    fun of(cells: List<Cell>, fallback: Int? = null): Signal? {
        val measured = cells.mapNotNull { c -> bars(c)?.let { c to it } }
        // The better cell, and of equal ones the newer technology (5G before its 4G anchor).
        measured.maxWithOrNull(compareBy<Pair<Cell, Int>> { it.second }.thenBy { -it.first.rat.ordinal })?.let { (c, b) ->
            return Signal(b, c.rat, c.dbm, c.sinr, c.rsrq ?: c.ecno, c.androidLevel, true)
        }
        val best = cells.maxByOrNull { it.androidLevel }
        val level = best?.androidLevel ?: fallback ?: return null
        return Signal(level.coerceIn(0, 4), best?.rat ?: Rat.OTHER, null, null, null, level, false)
    }

    /** Only values in the range the standards allow; Android's "unavailable" (Int.MAX_VALUE) and odd ones are none. */
    private fun valid(v: Int, low: Int, high: Int): Int? = v.takeIf { it != CellInfo.UNAVAILABLE && it in low..high }

    /** What Android reports of one card's cells. */
    fun cells(s: SignalStrength): List<Cell> = s.cellSignalStrengths.map(::cell)

    fun cell(s: CellSignalStrength): Cell = when (s) {
        is CellSignalStrengthNr -> Cell(Rat.NR, valid(s.ssRsrp, -156, -31), valid(s.ssSinr, -23, 40), valid(s.ssRsrq, -43, 20), androidLevel = s.level)
        is CellSignalStrengthLte -> Cell(Rat.LTE, valid(s.rsrp, -140, -43), valid(s.rssnr, -20, 30), valid(s.rsrq, -34, 3), androidLevel = s.level)
        is CellSignalStrengthWcdma -> Cell(Rat.WCDMA, valid(s.dbm, -120, -24), ecno = if (Build.VERSION.SDK_INT >= 30) valid(s.ecNo, -24, 1) else null, androidLevel = s.level)
        is CellSignalStrengthGsm -> Cell(Rat.GSM, valid(s.dbm, -113, -51), androidLevel = s.level)
        else -> Cell(Rat.OTHER, androidLevel = s.level)
    }
}
