package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Kenwood FleetSync and FleetSync II: the 1200 bit/s FFSK burst (1200 Hz mark, 1800 Hz space) a Kenwood radio sends on
 * an FM voice channel when its key is pressed, carrying its fleet and unit number.
 *
 * Format facts (sync word, frame layout, block check, FleetSync II code tables, fleet and unit fields) from the
 * description in GopherTrunk by Matt Cheramie (Apache-2.0, docs/fleetsync.md and internal/radio/fleetsync), itself
 * taken from multimon-ng's fsync decoder; written afresh here. Checked against the two real Kenwood captures that
 * project publishes (fleet 107, unit 1772).
 */
object FleetSync {
    const val SYNC = 0xA23E
    /** Bits between the sync word and the first data word. */
    private const val LEAD = 4
    const val FS1_BITS = LEAD + 64
    /** The longest frame read after the sync: FleetSync II's four 64-bit blocks. */
    const val FRAME_BITS = LEAD + 4 * 64
    private const val POLY = 0x6815
    private val FS2_CHECK = intArrayOf(0x4045, 0x2067, 0x1076, 0x083b, 0x0458, 0x022c, 0x0116, 0x008b)
    private val FS2_POS = intArrayOf(0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x17, 0x2e, 0x5c, 0xb8, 0x67, 0xce, 0x8b)

    data class Message(val fleet: Int, val unit: Int, val fs2: Boolean, val word1: Long, val word2: Long) {
        val raw: String get() = "%08X%08X".format(word1, word2)
    }

    /** The block check of the two data words: a bit-serial register over 48 bits, a parity over the rest, plus 2. */
    fun crc(w1: Long, w2: Long): Int {
        var parity = 0
        var sr = 0
        for (bit in 0 until 48) {
            val cur = if (bit < 32) ((w1 ushr (31 - bit)) and 1).toInt() else ((w2 ushr (63 - bit)) and 1).toInt()
            parity = parity xor cur
            if ((cur xor ((sr ushr 15) and 1)) != 0) sr = sr xor POLY
            sr = (sr shl 1) and 0xFFFF
        }
        for (bit in 48 until 63) parity = parity xor ((w2 ushr (63 - bit)) and 1).toInt()
        sr = sr xor 0x0002
        return (sr + parity) and 0xFFFF
    }

    /** One FleetSync II code word mended (one wrong bit at most), or null. */
    fun repair(v: Int): Int? {
        var syn = 0
        for (i in 0 until 8) if (Integer.bitCount(FS2_CHECK[i] and v) and 1 == 1) syn = syn or (1 shl i)
        if (syn == 0) return v
        val i = FS2_POS.indexOf(syn)
        return if (i < 0) null else v xor (1 shl (14 - i))
    }

    private fun word(bits: IntArray, from: Int): Long {
        var v = 0L
        for (i in 0 until 32) v = (v shl 1) or (bits[from + i] and 1).toLong()
        return v
    }

    /** The bits after the sync word ([FRAME_BITS] of them, or at least [FS1_BITS]) as a message, when one checks out. */
    fun decode(bits: IntArray): Message? {
        if (bits.size < FS1_BITS) return null
        val w1 = word(bits, LEAD)
        val w2 = word(bits, LEAD + 32)
        if (crc(w1, w2) == (w2 and 0xFFFF).toInt()) return message(w1, w2, false)
        if (bits.size < FRAME_BITS) return null
        // FleetSync II: each 16-bit half carries one nibble of the data, in a code that mends one bit.
        val nibbles = IntArray(16)
        for (blk in 0 until 4) {
            val a = word(bits, LEAD + blk * 64)
            val b = word(bits, LEAD + blk * 64 + 32)
            val halves = longArrayOf(a and 0xFFFF, a ushr 16, b and 0xFFFF, b ushr 16)
            for (k in 0 until 4) {
                val fixed = repair(halves[k].toInt()) ?: return null
                nibbles[blk * 4 + k] = (fixed ushr 8) and 0xF
            }
        }
        var f1 = 0L
        var f2 = 0L
        for (k in 0 until 8) { f1 = (f1 shl 4) or nibbles[k].toLong(); f2 = (f2 shl 4) or nibbles[8 + k].toLong() }
        return if (crc(f1, f2) == (f2 and 0xFFFF).toInt()) message(f1, f2, true) else null
    }

    private fun message(w1: Long, w2: Long, fs2: Boolean) = Message(
        fleet = ((w1 ushr 8) and 0xFF).toInt() + 99,
        unit = ((w1 and 0xFF).toInt() shl 4) + ((w2 ushr 28) and 0xF).toInt() + 999,
        fs2 = fs2, word1 = w1, word2 = w2,
    )

    // ---- Encoder, for the tests --------------------------------------------------------------------------------

    /** The two data words of an identity burst (the block check solved for). */
    fun words(fleet: Int, unit: Int): Pair<Long, Long> {
        val w1 = (((fleet - 99) and 0xFF) shl 8 or (((unit - 999) ushr 4) and 0xFF)).toLong()
        val hi = (((unit - 999) and 0xF).toLong() shl 28)
        for (c in 0..0xFFFF) { val w2 = hi or c.toLong(); if (crc(w1, w2) == c) return w1 to w2 }
        error("no block check")
    }

    /** A whole burst as bits: 32 bits of dotting, the sync word, then the FleetSync or FleetSync II frame. */
    fun burst(fleet: Int, unit: Int, fs2: Boolean): IntArray {
        val (w1, w2) = words(fleet, unit)
        val out = ArrayList<Int>()
        repeat(32) { out += (it + 1) and 1 }
        for (i in 15 downTo 0) out += (SYNC ushr i) and 1
        repeat(LEAD) { out += 0 }
        fun put(w: Long) { for (i in 31 downTo 0) out += ((w ushr i) and 1).toInt() }
        if (!fs2) { put(w1); put(w2) } else {
            val n = IntArray(16) { k -> (((if (k < 8) w1 else w2) ushr (28 - 4 * (k % 8))) and 0xF).toInt() }
            fun cw(nib: Int): Long = (0 until 0x8000).first { v -> (v ushr 8) and 0xF == nib && repair(v) == v }.toLong()
            for (blk in 0 until 4) {
                put((cw(n[blk * 4 + 1]) shl 16) or cw(n[blk * 4]))
                put((cw(n[blk * 4 + 3]) shl 16) or cw(n[blk * 4 + 2]))
            }
        }
        return out.toIntArray()
    }
}

/**
 * The sync hunt: 24 bits of alternating dotting (three wrong at most, either phase) then the sync word (one wrong at
 * most); a complemented sync word means the tones arrive the other way round, and the frame is read inverted.
 */
class FleetSyncFramer(private val onMessage: (FleetSync.Message) -> Unit) {
    private var reg = 0L
    private var capturing = false
    private var invert = 0
    private val frame = IntArray(FleetSync.FRAME_BITS)
    private var n = 0

    fun bit(b: Int) {
        if (capturing) {
            frame[n++] = b xor invert
            // FleetSync I is whole after 68 bits; FleetSync II needs all 260.
            if (n == FleetSync.FS1_BITS) FleetSync.decode(frame.copyOf(n))?.takeIf { !it.fs2 }?.let { onMessage(it); capturing = false; return }
            if (n == frame.size) { capturing = false; FleetSync.decode(frame)?.let(onMessage) }
            return
        }
        reg = ((reg shl 1) or (b and 1).toLong()) and 0xFF_FFFF_FFFFL
        val pre = (reg ushr 16) and 0xFFFFFF
        val dots = minOf(java.lang.Long.bitCount(pre xor 0xAAAAAA), java.lang.Long.bitCount(pre xor 0x555555))
        if (dots > 3) return
        val sync = (reg and 0xFFFF).toInt()
        invert = when {
            Integer.bitCount(sync xor FleetSync.SYNC) <= 1 -> 0
            Integer.bitCount(sync xor (FleetSync.SYNC xor 0xFFFF)) <= 1 -> 1
            else -> return
        }
        capturing = true
        n = 0
    }
}

/**
 * FFSK at 1200 bit/s (1200 Hz mark for 1, 1800 Hz space for 0) from an FM discriminator at [sampleRate]: one-bit tone
 * correlators, an amplitude-normalised decision, and a bit clock pulled to each tone change.
 */
class FfskDemodulator(sampleRate: Double = 50_000.0, private val onBit: (Int) -> Unit) {
    private val len = (sampleRate / 1200).roundToInt()
    private val step = 1200 / sampleRate
    private val mre = FloatArray(len)
    private val mim = FloatArray(len)
    private val sre = FloatArray(len)
    private val sim = FloatArray(len)
    private var sMr = 0.0
    private var sMi = 0.0
    private var sSr = 0.0
    private var sSi = 0.0
    // The two reference tones as phasors turned one step per sample (no sine per sample: up to 16 channels run this).
    private var mc = 1.0
    private var ms = 0.0
    private var sc = 1.0
    private var ss0 = 0.0
    private val mStepC = cos(2 * PI * 1200 / sampleRate)
    private val mStepS = sin(2 * PI * 1200 / sampleRate)
    private val sStepC = cos(2 * PI * 1800 / sampleRate)
    private val sStepS = sin(2 * PI * 1800 / sampleRate)
    private var renorm = 0
    private var mean = 0f
    private var peakM = 1e-9
    private var peakS = 1e-9
    private var k = 0
    private var last = 0
    private var clock = 0.0

    fun push(f: Float) {
        mean += (f - mean) / 200f
        val x = f - mean
        val cm = (x * mc).toFloat(); val sm = (-x * ms).toFloat()
        val cs = (x * sc).toFloat(); val ss = (-x * ss0).toFloat()
        sMr += cm - mre[k]; sMi += sm - mim[k]; mre[k] = cm; mim[k] = sm
        sSr += cs - sre[k]; sSi += ss - sim[k]; sre[k] = cs; sim[k] = ss
        if (++k == len) k = 0
        val nm = mc * mStepC - ms * mStepS; ms = mc * mStepS + ms * mStepC; mc = nm
        val ns = sc * sStepC - ss0 * sStepS; ss0 = sc * sStepS + ss0 * sStepC; sc = ns
        if (++renorm == 4096) {
            renorm = 0
            val gm = 1 / kotlin.math.hypot(mc, ms); mc *= gm; ms *= gm
            val gs = 1 / kotlin.math.hypot(sc, ss0); sc *= gs; ss0 *= gs
            // The running sums drift with float rounding: started again from the window.
            sMr = mre.sum().toDouble(); sMi = mim.sum().toDouble(); sSr = sre.sum().toDouble(); sSi = sim.sum().toDouble()
        }
        val em = sMr * sMr + sMi * sMi
        val es = sSr * sSr + sSi * sSi
        // The tone levels are forgotten slowly (about 2 s): a FleetSync II frame opens with some 68 bits of the space
        // tone, and a faster decay let the mark's noise climb over it and slip the clock.
        peakM = maxOf(em, peakM * 0.99998)
        peakS = maxOf(es, peakS * 0.99998)
        val level = if (em / peakM - es / peakS > 0) 1 else 0
        if (level != last) { val err = if (clock < 0.5) clock else clock - 1; clock -= 0.3 * err; if (clock < 0) clock += 1 }
        last = level
        val before = clock
        clock += step
        if (before < 0.5 && clock >= 0.5) onBit(level)
        if (clock >= 1) clock -= 1
    }
}
