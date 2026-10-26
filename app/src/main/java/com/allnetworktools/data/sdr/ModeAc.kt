package com.allnetworktools.data.sdr

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.roundToInt

/*
 * Mode A/C, the old secondary-radar replies most transponders still send beside Mode S on 1090 MHz: the squawk (four
 * octal digits) and the pressure altitude (Gillham code, 100 ft steps). Ported from OnAir (core/src/modeac, GPL-3.0).
 * A reply is 15 pulse positions 1.45 µs apart, 0.45 µs wide: F1, C1 A1 C2 A2 C4 A4 X B1 D1 B2 D2 B4 D4, F2; the ident
 * (SPI) pulse follows F2 by 4.35 µs.
 */
object ModeAc {
    const val SLOT_US = 1.45
    const val SPAN_US = 20.3

    /** Slot → octal digit (0 A, 1 B, 2 C, 3 D) and weight; slot 7 is X. */
    private val digit = intArrayOf(-1, 2, 0, 2, 0, 2, 0, -1, 1, 3, 1, 3, 1, 3)
    private val weight = intArrayOf(0, 1, 1, 2, 2, 4, 4, 0, 1, 1, 2, 2, 4, 4)

    fun slotBit(bits: Int, slot: Int) = (bits shr (13 - slot)) and 1 == 1
    fun setSlot(bits: Int, slot: Int) = bits or (1 shl (13 - slot))

    fun squawkOf(bits: Int): Int {
        val d = IntArray(4)
        for (s in 1..13) if (digit[s] >= 0 && slotBit(bits, s)) d[digit[s]] += weight[s]
        return d[0] * 1000 + d[1] * 100 + d[2] * 10 + d[3]
    }

    fun bitsOfSquawk(squawk: Int): Int {
        val d = intArrayOf(squawk / 1000 % 10, squawk / 100 % 10, squawk / 10 % 10, squawk % 10)
        var b = 0
        for (s in 1..13) if (digit[s] >= 0 && d[digit[s]] and weight[s] != 0) b = setSlot(b, s)
        return b
    }

    /** D1 D2 D4 A1 A2 A4 B1 B2 B4: the Gray-coded 500 ft part, most significant first. */
    private val graySlots = intArrayOf(9, 11, 13, 2, 4, 6, 8, 10, 12)
    /** C1 C2 C4 for the 100 ft steps 1 to 5. */
    private val stepC = arrayOf(intArrayOf(0, 0, 0), intArrayOf(0, 0, 1), intArrayOf(0, 1, 1), intArrayOf(0, 1, 0), intArrayOf(1, 1, 0), intArrayOf(1, 0, 0))

    /** Pressure altitude in feet, or null when the pattern cannot be one. */
    fun altitudeOf(bits: Int): Int? {
        if (slotBit(bits, 7)) return null
        val c = intArrayOf(if (slotBit(bits, 1)) 1 else 0, if (slotBit(bits, 3)) 1 else 0, if (slotBit(bits, 5)) 1 else 0)
        var step = (1..5).firstOrNull { s -> stepC[s].contentEquals(c) } ?: return null
        var gray = 0
        for (s in graySlots) gray = (gray shl 1) or (if (slotBit(bits, s)) 1 else 0)
        var n = 0
        var g = gray
        while (g != 0) { n = n xor g; g = g shr 1 }
        if (n and 1 == 1) step = 6 - step
        val ft = n * 500 + step * 100 - 1300
        return ft.takeIf { it in -1200..126_700 }
    }

    fun bitsOfAltitude(feet: Int): Int {
        val i = ((feet + 1200) / 100.0).roundToInt().coerceIn(0, 1279)
        val n = i / 5
        var step = i % 5 + 1
        if (n and 1 == 1) step = 6 - step
        val gray = n xor (n shr 1)
        var b = 0
        for (k in 0 until 9) if ((gray shr (8 - k)) and 1 == 1) b = setSlot(b, graySlots[k])
        if (stepC[step][0] == 1) b = setSlot(b, 1)
        if (stepC[step][1] == 1) b = setSlot(b, 3)
        if (stepC[step][2] == 1) b = setSlot(b, 5)
        return b
    }

    fun isEmergency(squawk: Int) = squawk == 7500 || squawk == 7600 || squawk == 7700
}

/** One reply: its 13 code bits, whether the ident pulse followed, and its level. */
data class ModeAcReply(val bits: Int, val spi: Boolean, val levelDb: Double)

/**
 * Finds replies in the power of 8-bit I/Q at [rate]: two pulses 20.3 µs apart, then the 13 positions measured against
 * the weaker framing pulse, with X, the gaps and the positions around the frame empty. A Mode S message, with pulses on a
 * 0.5 µs grid, fills the gaps and is rejected. Windows are fractional, so 2 MS/s works (with some sensitivity lost).
 */
class ModeAcDemodulator(private val rate: Double = 2_000_000.0, private val onReply: (ModeAcReply) -> Unit) {
    private val spus = rate * 1e-6
    private var pw = FloatArray(1 shl 18)
    private var cum = DoubleArray((1 shl 18) + 1)
    private var size = 0
    private var scanPos = 0.0
    private var dcI = 0f
    private var dcQ = 0f
    private var dcInit = false
    private val blocks = FloatArray(24)
    private var blockN = 0
    private var blockPos = 0
    private var blockSum = 0.0
    private var blockCount = 0
    private var noise = 0f

    var replies = 0
        private set

    fun feed(buf: ByteArray, len: Int) {
        val n = len / 2
        if (size + n + 1 > pw.size) {
            val cap = Integer.highestOneBit(size + n) * 2
            pw = pw.copyOf(cap); cum = cum.copyOf(cap + 1)
        }
        for (k in 0 until n) {
            val re = buf[2 * k] / 128f
            val im = buf[2 * k + 1] / 128f
            if (!dcInit) { dcI = re; dcQ = im; dcInit = true }
            dcI += (re - dcI) / 4096f; dcQ += (im - dcQ) / 4096f
            val a = re - dcI
            val b = im - dcQ
            val p = a * a + b * b
            pw[size] = p
            cum[size + 1] = cum[size] + p
            size++
            blockSum += p
            if (++blockCount == 512) {
                blocks[blockPos] = (blockSum / blockCount).toFloat()
                blockPos = (blockPos + 1) % blocks.size; blockN = minOf(blockN + 1, blocks.size)
                blockSum = 0.0; blockCount = 0
                // The lower quartile: Mode S traffic and replies lift the others.
                if (blockN >= 8) noise = maxOf(blocks.copyOf(blockN).also { it.sort() }[blockN / 4], 1e-12f)
            }
        }
        scan()
        compact()
    }

    /** Mean power over [x, x + w), in samples, with fractional edges. */
    private fun win(x: Double, w: Double): Double {
        fun c(pos: Double): Double {
            if (pos <= 0) return cum[0]
            val i = pos.toInt()
            if (i >= size) return cum[size]
            return cum[i] + (pos - i) * pw[i]
        }
        return (c(x + w) - c(x)) / w
    }

    private fun scan() {
        val end = size - 30.0 * spus
        if (noise <= 0f) { scanPos = maxOf(scanPos, end); return }
        val w = 0.5 * spus
        val step = 0.125 * spus
        val d2 = ModeAc.SPAN_US * spus
        val thr = THRESHOLD * noise
        while (scanPos < end) {
            val s = scanPos
            if (win(s, w) < thr || win(s + d2, w) < thr) { scanPos += step; continue }
            // Timing: the offset that makes the weaker framing pulse strongest.
            var best = -1.0
            var sBest = s
            for (k in -8..8) {
                val sx = s + k * 0.05 * spus
                val sc = minOf(win(sx, w), win(sx + d2, w))
                if (sc > best) { best = sc; sBest = sx }
            }
            val r = test(sBest)
            if (r != null) {
                replies++
                onReply(r)
                scanPos = sBest + 21.0 * spus
            } else {
                scanPos = sBest + 0.6 * spus
            }
        }
    }

    private fun test(s0: Double): ModeAcReply? {
        val w = 0.5 * spus
        val off = -0.025 * spus
        val e = DoubleArray(18)
        for (k in 0..14) e[k] = win(s0 + off + k * ModeAc.SLOT_US * spus, w)
        e[17] = win(s0 + off + 17 * ModeAc.SLOT_US * spus, w)
        e[15] = win(s0 + off + 15 * ModeAc.SLOT_US * spus, w)
        e[16] = win(s0 + off + 16 * ModeAc.SLOT_US * spus, w)
        val l = minOf(e[0], e[14])
        if (l < THRESHOLD * noise || maxOf(e[0], e[14]) > 4.0 * l) return null // both framing pulses from one transmitter
        val on = 0.4 * l
        if (e[7] > on) return null // X is empty
        if (e[15] > on * 0.8 || e[16] > on * 0.8) return null // nothing between F2 and SPI
        if (win(s0 + off - ModeAc.SLOT_US * spus, w) > on) return null // nor right before F1
        var gmax = 0.0
        var gsum = 0.0
        for (k in 0 until 14) {
            val g = win(s0 + (k * ModeAc.SLOT_US + 0.62) * spus, 0.7 * spus)
            gmax = maxOf(gmax, g); gsum += g
        }
        if (gmax > 0.45 * l || gsum / 14 > 0.18 * l) return null // little energy between the pulses
        var bits = 0
        var psum = e[0] + e[14]
        var pn = 2
        for (s in 1..13) if (e[s] > on) { bits = ModeAc.setSlot(bits, s); psum += e[s]; pn++ }
        return ModeAcReply(bits, e[17] > on, 10 * log10(maxOf(psum / pn, 1e-12)))
    }

    /** Keeps what the scan has not passed yet, plus room to look a little back. */
    private fun compact() {
        val drop = floor(scanPos - 40.0 * spus - 4).toInt()
        if (drop < 65_536) return
        val c0 = cum[drop]
        System.arraycopy(pw, drop, pw, 0, size - drop)
        for (i in 0..size - drop) cum[i] = cum[i + drop] - c0
        size -= drop
        scanPos -= drop
    }

    companion object {
        /** Pulse power over the noise (10 dB). */
        private const val THRESHOLD = 10.0
    }
}

/** A code seen at least twice, with what it may be: a squawk, an altitude, and the Mode S aircraft it matches. */
class ModeAcCode(val bits: Int) {
    var count = 0
    var spi = false
    var lastSeenMs = 0L
    var levelDb = -99.0
    val squawk get() = ModeAc.squawkOf(bits)
    val altitudeFt get() = ModeAc.altitudeOf(bits)
}

class ModeAcTracker {
    val codes = HashMap<Int, ModeAcCode>()

    fun update(r: ModeAcReply, nowMs: Long) {
        val c = codes.getOrPut(r.bits) { ModeAcCode(r.bits) }
        c.count++; c.spi = r.spi; c.lastSeenMs = nowMs; c.levelDb = r.levelDb
    }

    /** Codes silent for a minute are dropped; one reply alone is never shown. */
    fun prune(nowMs: Long) { codes.values.removeAll { nowMs - it.lastSeenMs > 60_000 } }

    fun confirmed() = codes.values.filter { it.count >= 2 }
}
