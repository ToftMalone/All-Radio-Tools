package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * The tone signalling of analogue FM channels: 5-tone selective calls (ZVEI1, CCIR, EEA) and DTMF, from the FM
 * discriminator (Hz of deviation, 50 kS/s). The audio is averaged down to 10 kS/s. Selective calls are one tone at a
 * time: every 5 ms block whose zero crossings are regular gives a frequency, blocks in a row on the same frequency make
 * a tone, and four tones or more back to back, all of about the same length and all on one standard's table, make a
 * call. DTMF is two tones at once: Goertzel filters on the eight frequencies every 20 ms.
 */
class ToneSignalling(
    private val onSelcall: (standard: String, digits: String) -> Unit,
    private val onDtmf: (digits: String) -> Unit,
) {
    /** One standard: its tones for 0–9, the repeat tone, and how long a tone lasts. */
    class Standard(val name: String, val digits: DoubleArray, val repeat: Double, val toneMs: Int)

    companion object {
        const val RATE = 10_000
        private const val DECIM = 5
        private const val BLOCK = 50 // 5 ms
        private const val DTMF_BLOCK = 200 // 20 ms
        val ZVEI1 = Standard("ZVEI1", doubleArrayOf(2400.0, 1060.0, 1160.0, 1270.0, 1400.0, 1530.0, 1670.0, 1830.0, 2000.0, 2200.0), 2600.0, 70)
        val CCIR = Standard("CCIR", doubleArrayOf(1981.0, 1124.0, 1197.0, 1275.0, 1358.0, 1446.0, 1540.0, 1640.0, 1747.0, 1860.0), 2110.0, 100)
        val EEA = Standard("EEA", CCIR.digits, CCIR.repeat, 40)
        val STANDARDS = listOf(ZVEI1, CCIR, EEA)
        val DTMF_ROWS = doubleArrayOf(697.0, 770.0, 852.0, 941.0)
        val DTMF_COLS = doubleArrayOf(1209.0, 1336.0, 1477.0, 1633.0)
        private val DTMF_KEYS = arrayOf("123A", "456B", "789C", "*0#D")
        /** A DTMF string ends after this much quiet. */
        const val DTMF_IDLE_MS = 1_500

        /** The digits of a sequence of tone frequencies on a standard, or null when one is not on its table. */
        fun digitsOf(std: Standard, freqs: List<Double>): String? {
            val sb = StringBuilder()
            for (f in freqs) {
                if (abs(f - std.repeat) <= std.repeat * 0.015) {
                    if (sb.isEmpty()) return null
                    sb.append(sb.last()); continue
                }
                val k = std.digits.indices.minByOrNull { abs(std.digits[it] - f) }!!
                if (abs(std.digits[k] - f) > std.digits[k] * 0.015) return null
                sb.append('0' + k)
            }
            return sb.toString()
        }

        /**
         * Test helper: the tone frequencies of a digit string, the repeat tone for a digit like the one before (never two
         * repeat tones in a row: "111" is 1, repeat, 1).
         */
        fun tonesOf(std: Standard, digits: String): List<Double> {
            val out = ArrayList<Double>()
            digits.forEachIndexed { i, c -> out += if (i > 0 && digits[i - 1] == c && out.last() != std.repeat) std.repeat else std.digits[c - '0'] }
            return out
        }
    }

    // ---- 50 -> 10 kS/s, DC out ------------------------------------------------------------------------------------
    private var acc = 0f
    private var accN = 0
    private var dc = 0f
    private var nSamples = 0L

    fun push(hz: Float) {
        acc += hz
        if (++accN < DECIM) return
        val x = acc / DECIM
        acc = 0f; accN = 0
        dc += 0.01f * (x - dc)
        sample(x - dc)
    }

    private fun sample(x: Float) {
        nSamples++
        blk[bn++] = x
        if (bn == BLOCK) { block(); bn = 0 }
        dtmf[dn++] = x
        if (dn == DTMF_BLOCK) { dtmfBlock(); dn = 0 }
    }

    // ---- Selective calls --------------------------------------------------------------------------------------------
    private val blk = FloatArray(BLOCK)
    private var bn = 0
    /** The tone being followed: its samples (for the exact frequency), blocks and rough frequency. */
    private val seg = FloatArray(RATE / 4)
    private var segN = 0
    private var segBlocks = 0
    private var segHz = 0.0
    private class Tone(val hz: Double, val ms: Int, val endSample: Long)
    private val tones = ArrayList<Tone>()

    /** The frequency of a 5 ms block from its zero crossings, or 0 when they are not regular enough to be a tone. */
    private fun blockHz(): Double {
        var e = 0.0
        for (v in blk) e += v * v
        if (sqrt(e / BLOCK) < 150) return 0.0
        var first = -1.0; var last = -1.0; var n = 0
        var minGap = Double.MAX_VALUE; var maxGap = 0.0
        for (i in 1 until BLOCK) {
            val a = blk[i - 1]; val b = blk[i]
            if ((a < 0) != (b < 0)) {
                val t = i - 1 + a / (a - b).toDouble()
                if (last >= 0) { val g = t - last; if (g < minGap) minGap = g; if (g > maxGap) maxGap = g }
                if (first < 0) first = t
                last = t; n++
            }
        }
        if (n < 8) return 0.0
        val half = (last - first) / (n - 1)
        // A tone: every half period the same within 30 %.
        if (maxGap > half * 1.3 || minGap < half * 0.7) return 0.0
        return RATE / (2 * half)
    }

    private fun block() {
        val f = blockHz()
        if (f > 0 && segBlocks > 0 && abs(f - segHz) < segHz * 0.03) {
            segHz = (segHz * segBlocks + f) / (segBlocks + 1)
            segBlocks++
            appendSeg()
            return
        }
        endSegment()
        if (f in 900.0..2800.0) { segHz = f; segBlocks = 1; segN = 0; appendSeg() }
    }

    private fun appendSeg() {
        val m = minOf(BLOCK, seg.size - segN)
        System.arraycopy(blk, 0, seg, segN, m)
        segN += m
    }

    private fun endSegment() {
        // Long enough (25 ms or more): a tone, its frequency to a few hertz by Goertzel around the rough one. Shorter
        // runs are the edges between tones, or noise.
        if (segBlocks >= 5) tones += Tone(fineHz(), segBlocks * BLOCK * 1000 / RATE, nSamples)
        segBlocks = 0
        // More than 20 ms without a tone: the sequence is over.
        if (tones.isNotEmpty() && nSamples - tones.last().endSample > BLOCK * 4) flushTones()
    }

    private fun fineHz(): Double {
        var best = segHz; var bestP = -1.0
        var f = segHz - 40
        while (f <= segHz + 40) {
            val p = goertzel(seg, segN, f)
            if (p > bestP) { bestP = p; best = f }
            f += 2.0
        }
        return best
    }

    private fun flushTones() {
        if (tones.size >= 4) {
            val ms = tones.map { it.ms }.sorted()
            val med = ms[ms.size / 2]
            if (tones.all { it.ms in (med * 6 / 10)..(med * 16 / 10) }) {
                // The standard whose tone length is nearest and whose table fits every tone.
                val freqs = tones.map { it.hz }
                STANDARDS.sortedBy { abs(it.toneMs - med) }.firstNotNullOfOrNull { s -> digitsOf(s, freqs)?.let { s to it } }
                    ?.let { (s, d) -> onSelcall(s.name, d) }
            }
        }
        tones.clear()
    }

    // ---- DTMF ---------------------------------------------------------------------------------------------------------
    private val dtmf = FloatArray(DTMF_BLOCK)
    private var dn = 0
    private var dtmfLast: Char? = null
    private var dtmfCount = 0
    private var dtmfHeld: Char? = null
    private val dtmfDigits = StringBuilder()
    private var dtmfQuietBlocks = 0

    private fun dtmfKey(): Char? {
        var e = 0.0
        for (v in dtmf) e += v * v
        if (sqrt(e / DTMF_BLOCK) < 150) return null
        val r = DoubleArray(4) { goertzel(dtmf, DTMF_BLOCK, DTMF_ROWS[it]) }
        val c = DoubleArray(4) { goertzel(dtmf, DTMF_BLOCK, DTMF_COLS[it]) }
        val ri = r.indices.maxByOrNull { r[it] }!!
        val ci = c.indices.maxByOrNull { c[it] }!!
        // Each well above the others in its group, the two together most of the energy, twist under 8 dB.
        if (r.indices.any { it != ri && r[it] * 4 > r[ri] } || c.indices.any { it != ci && c[it] * 4 > c[ci] }) return null
        if ((r[ri] + c[ci]) / (DTMF_BLOCK * e / 2) < 0.6) return null
        val tw = r[ri] / c[ci]
        if (tw > 6.3 || tw < 1 / 6.3) return null
        return DTMF_KEYS[ri][ci]
    }

    private fun dtmfBlock() {
        val k = dtmfKey()
        if (k != null && k == dtmfLast) dtmfCount++ else { dtmfLast = k; dtmfCount = 1 }
        // A key: two blocks (40 ms) the same; another press of the same key needs a break first.
        if (k != null && dtmfCount == 2 && dtmfHeld != k) { dtmfHeld = k; dtmfDigits.append(k); if (dtmfDigits.length >= 32) flushDtmf() }
        if (k == null) { dtmfHeld = null; dtmfQuietBlocks++ } else dtmfQuietBlocks = 0
        if (dtmfDigits.isNotEmpty() && dtmfQuietBlocks * 20 >= DTMF_IDLE_MS) flushDtmf()
    }

    private fun flushDtmf() { onDtmf(dtmfDigits.toString()); dtmfDigits.clear() }

    /** The power of [n] samples at [f] Hz. */
    private fun goertzel(x: FloatArray, n: Int, f: Double): Double {
        val w = 2 * cos(2 * PI * f / RATE)
        var s1 = 0.0; var s2 = 0.0
        for (i in 0 until n) { val s = x[i] + w * s1 - s2; s2 = s1; s1 = s }
        return s1 * s1 + s2 * s2 - w * s1 * s2
    }
}
