package com.allnetworktools.data.sdr

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Automatic gain for the HackRF's 8-bit converter: keeps the average level a few steps above the converter's own noise and
 * backs off as soon as samples clip. It only moves along the LNA and VGA values the tool offers, one step at a time, so
 * the setting in use is always one the screen shows.
 */
class AutoGain(lnaSteps: List<Int>, vgaSteps: List<Int>, lna: Int, vga: Int) {
    /** Every offered pair, by total gain; for a total reached twice, the one with more LNA (the lower noise figure). */
    val ladder: List<Pair<Int, Int>> = lnaSteps.flatMap { l -> vgaSteps.map { v -> l to v } }
        .sortedWith(compareBy<Pair<Int, Int>> { it.first + it.second }.thenByDescending { it.first })
        .distinctBy { it.first + it.second }

    var index = ladder.indices.minBy { abs(ladder[it].first - lna) * 2 + abs(ladder[it].second - vga) }
        private set

    val current: Pair<Int, Int> get() = ladder[index]

    private var sumSq = 0.0
    private var count = 0L
    private var clipped = 0L
    private var windowStartNs = Long.MIN_VALUE
    private var holdUntilNs = Long.MIN_VALUE

    /** Level of the last full window, in dB below full scale, and the share of clipped samples. */
    var levelDbfs = -99.0
        private set
    var clipShare = 0.0
        private set

    /** Interleaved 8-bit I/Q; returns the new gains when a step is due. */
    fun feed(buf: ByteArray, len: Int, nowNs: Long): Pair<Int, Int>? {
        if (nowNs < holdUntilNs) return null
        if (windowStartNs == Long.MIN_VALUE) windowStartNs = nowNs
        var i = 0
        while (i < len) {
            val s = buf[i].toInt()
            sumSq += s * s
            if (s >= 126 || s <= -127) clipped++
            count++
            i += STRIDE
        }
        if (nowNs - windowStartNs < WINDOW_NS || count == 0L) return null
        levelDbfs = 20 * log10(maxOf(sqrt(sumSq / count), 1e-3) / 128)
        clipShare = clipped.toDouble() / count
        sumSq = 0.0; count = 0; clipped = 0; windowStartNs = nowNs
        val next = when {
            clipShare > CLIP_DOWN || levelDbfs > HIGH_DBFS -> index - 1
            levelDbfs < LOW_DBFS && clipShare < CLIP_UP -> index + 1
            else -> index
        }.coerceIn(ladder.indices)
        if (next == index) return null
        index = next
        // What arrives right after a change still carries the old gain.
        holdUntilNs = nowNs + SETTLE_NS
        windowStartNs = Long.MIN_VALUE
        return ladder[index]
    }

    companion object {
        private const val STRIDE = 7 // every 7th byte, alternately I and Q
        private const val WINDOW_NS = 300_000_000L
        private const val SETTLE_NS = 250_000_000L
        /** Clipping: over 0.3 % of samples steps down; it must be under 0.05 % to step up. */
        private const val CLIP_DOWN = 0.003
        private const val CLIP_UP = 0.0005
        /** Average level kept between about 2.5 and 30 converter steps. */
        private const val LOW_DBFS = -34.0
        private const val HIGH_DBFS = -12.0
    }
}
