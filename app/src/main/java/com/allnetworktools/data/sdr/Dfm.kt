package com.allnetworktools.data.sdr

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * Graw DFM radiosondes (DFM-06, DFM-09, DFM-17), launched by Germany's DWD, Czechia and others: 2500 bit/s Manchester
 * FSK; a frame is the header 0x45CF and three blocks, a configuration block of 7 nibbles and two data blocks of 13,
 * each nibble in an interleaved Hamming(8,4) code word. The serial number comes in two halves in the configuration
 * blocks, the GPS fix spread over the data blocks 0 to 8.
 *
 * Format from the open-source rs1729/RS "dfm09mod" decoder (GPL-3.0), written afresh here.
 */
object Dfm {
    const val BAUD = 2500.0
    const val FRAME_BITS = 280
    /** The header bits 0x45CF. */
    val HEADER = intArrayOf(0, 1, 0, 0, 0, 1, 0, 1, 1, 1, 0, 0, 1, 1, 1, 1)
    private const val CONF = 16
    private const val DAT1 = 16 + 56
    private const val DAT2 = 16 + 160
    private val H = arrayOf(
        intArrayOf(0, 1, 1, 1, 1, 0, 0, 0),
        intArrayOf(1, 0, 1, 1, 0, 1, 0, 0),
        intArrayOf(1, 1, 0, 1, 0, 0, 1, 0),
        intArrayOf(1, 1, 1, 0, 0, 0, 0, 1),
    )
    private val HE = intArrayOf(0x7, 0xB, 0xD, 0xE, 0x8, 0x4, 0x2, 0x1)
    private val G = arrayOf(
        intArrayOf(1, 0, 0, 0), intArrayOf(0, 1, 0, 0), intArrayOf(0, 0, 1, 0), intArrayOf(0, 0, 0, 1),
        intArrayOf(0, 1, 1, 1), intArrayOf(1, 0, 1, 1), intArrayOf(1, 1, 0, 1), intArrayOf(1, 1, 1, 0),
    )

    /**
     * One interleaved block of [l] code words (7 or 13) from [frame] at [at]: its 4·[l] data bits, or null when a code
     * word has more than one wrong bit.
     */
    fun block(frame: IntArray, at: Int, l: Int): IntArray? {
        val out = IntArray(4 * l)
        for (i in 0 until l) {
            val cw = IntArray(8) { j -> frame[at + l * j + i] }
            var syn = 0
            for (r in 0 until 4) { var p = 0; for (j in 0 until 8) p = p xor (H[r][j] and cw[j]); syn = (syn shl 1) or p }
            if (syn != 0) { val j = HE.indexOf(syn); if (j < 0) return null; cw[j] = cw[j] xor 1 }
            for (j in 0 until 4) out[4 * i + j] = cw[j]
        }
        return out
    }

    fun bits(b: IntArray, from: Int, n: Int): Long { var v = 0L; for (i in 0 until n) v = (v shl 1) or b[from + i].toLong(); return v }

    /** The three blocks of a 280-bit frame (header included): configuration, data 1, data 2, each null when damaged. */
    fun blocks(frame: IntArray): Triple<IntArray?, IntArray?, IntArray?> =
        Triple(block(frame, CONF, 7), block(frame, DAT1, 13), block(frame, DAT2, 13))

    // ---- Encoder, for the tests --------------------------------------------------------------------------------

    private fun encodeBlock(data: IntArray, l: Int): IntArray {
        val cws = Array(l) { i -> IntArray(8) { r -> var v = 0; for (j in 0 until 4) v = v xor (G[r][j] and data[4 * i + j]); v } }
        return IntArray(8 * l) { k -> val j = k / l; val i = k % l; cws[i][j] }
    }

    fun nibbleBits(nibs: IntArray) = IntArray(nibs.size * 4) { (nibs[it / 4] shr (3 - it % 4)) and 1 }

    /** A frame from its three blocks of nibbles (7, 13 and 13). */
    fun frame(conf: IntArray, dat1: IntArray, dat2: IntArray): IntArray =
        HEADER + encodeBlock(nibbleBits(conf), 7) + encodeBlock(nibbleBits(dat1), 13) + encodeBlock(nibbleBits(dat2), 13)
}

/**
 * What the blocks of one sonde say, put together frame after frame: the serial number once both halves agree twice, the
 * fix once data block 8 closes the cycle. A frame comes out at each block 8 with a position and a serial.
 */
class DfmDecoder(private val onFrame: (SondeFrame) -> Unit) {
    private var nulCh = 0
    private var maxCh = 0
    private var sn6 = 0L
    private var snCh = -1
    private val chX = LongArray(2)
    private var chXbit = 0
    private var snX = 0L
    var serial: String? = null
        private set
    var model = "DFM"
        private set
    private var posMode = -1
    private var frameNumber: Int? = null
    private var lat: Double? = null
    private var lon: Double? = null
    private var alt: Double? = null
    private var speed: Double? = null
    private var course: Double? = null
    private var climb: Double? = null
    private var sats: Int? = null

    fun frame(bits: IntArray) {
        val (conf, d1, d2) = Dfm.blocks(bits)
        conf?.let(::conf)
        d1?.let(::dat)
        d2?.let(::dat)
    }

    private fun conf(c: IntArray) {
        val id = Dfm.bits(c, 0, 4).toInt()
        if (id > 4 && Dfm.bits(c, 8, 20) == 0L) nulCh = Dfm.bits(c, 0, 8).toInt()
        if (id > 5 && id > maxCh && Dfm.bits(c, 4, 4) == 0xCL) maxCh = id
        if (id <= 5 || (id != (nulCh shr 4) + 1 && id != maxCh)) return
        val sn2 = Dfm.bits(c, 0, 8).toInt()
        val ch = sn2 shr 4
        if (nulCh and 0x58 == 0x58) {
            // DFM-06: the serial whole in one block, trusted when two blocks agree.
            val v = Dfm.bits(c, 4, 24)
            if (v == sn6 && v != 0L) { serial = "%06X".format(v); model = "DFM-06" }
            sn6 = v
        } else if (sn2 and 0xF == 0xC || sn2 and 0xF == 0) {
            val v = Dfm.bits(c, 8, 20)
            val half = (v and 0xF).toInt()
            if (half >= 2) return
            if (snCh != ch) { chXbit = 0; chX.fill(0) }
            snCh = ch
            chX[half] = (v shr 4) and 0xFFFF
            chXbit = chXbit or (1 shl half)
            if (chXbit == 3) {
                val sn = (chX[0] shl 16) or chX[1]
                if (sn == snX || snX == 0L) {
                    serial = "$sn"
                    model = when (ch) { 0xA -> "DFM-09"; 0xB -> "DFM-17"; 0xC -> "DFM-09P"; 0xD -> "DFM-17P"; else -> "DFM" }
                }
                snX = sn
                chXbit = 0
            }
        }
    }

    private fun dat(d: IntArray) {
        val id = Dfm.bits(d, 48, 4).toInt()
        fun s16(at: Int) = Dfm.bits(d, at, 16).toInt().toShort().toInt()
        fun u16(at: Int) = Dfm.bits(d, at, 16).toInt()
        fun s32(at: Int) = Dfm.bits(d, at, 32).toInt()
        if (id == 0) {
            val m = Dfm.bits(d, 16, 8).toInt()
            posMode = if (m in 2..4) m else -1
            if (posMode <= 2) frameNumber = Dfm.bits(d, 24, 8).toInt()
        }
        if (posMode <= 2) when (id) {
            2 -> { lat = s32(0) / 1e7; speed = s16(32) / 100.0 }
            3 -> { lon = s32(0) / 1e7; course = u16(32) / 100.0 }
            4 -> { alt = s32(0) / 100.0; climb = s16(32) / 100.0 }
        } else when (id) {
            0 -> speed = s16(32) / 100.0
            1 -> { lat = s32(0) / 1e7; course = u16(32) / 100.0 }
            2 -> { lon = s32(0) / 1e7; climb = s16(32) / 100.0 }
            3 -> alt = s32(0) / 100.0
        }
        if (id == 8) {
            sats = Dfm.bits(d, 32, 8).toInt()
            val sn = serial ?: return
            val la = lat; val lo = lon; val al = alt
            // A fix still at zero (no GPS yet) is not a position.
            val fix = la != null && lo != null && al != null && !(la == 0.0 && lo == 0.0)
            SdrHealth.good()
            onFrame(SondeFrame(model, sn, frameNumber, null, if (fix) la else null, if (fix) lo else null, if (fix) al else null,
                speed, course, climb, sats))
        }
    }
}

/**
 * The DFM bit stream from the FM discriminator at [sampleRate]: the Manchester header (each bit two chips, 1 as "01",
 * 0 as "10") found by correlation at either polarity, then the 280 bits read chip pair by chip pair.
 */
class DfmDemodulator(private val sampleRate: Double, private val onFrame: (IntArray) -> Unit) {
    private val chip = sampleRate / (2 * Dfm.BAUD)
    private val win = chip.roundToInt().coerceAtLeast(1)
    private val headerChips = IntArray(32).also { h -> Dfm.HEADER.forEachIndexed { i, b -> h[2 * i] = if (b == 1) -1 else 1; h[2 * i + 1] = if (b == 1) 1 else -1 } }
    private val headerAt = IntArray(32) { ((it + 0.5) * chip).roundToInt() }
    private val frameSpan = ((2 * Dfm.FRAME_BITS + 1) * chip).toInt() + 2 * Dfm.FRAME_BITS / 50 + 4
    private val cap = 1 shl 16
    private val s = FloatArray(cap)
    private var count = 0
    private var scanFrom = 0
    private var pr = 0f
    private var pi = 0f
    private val box = FloatArray(win)
    private var boxSum = 0f
    private var boxIdx = 0

    fun feed(re: FloatArray, im: FloatArray, n: Int) {
        for (i in 0 until n) {
            val f = atan2(im[i] * pr - re[i] * pi, re[i] * pr + im[i] * pi)
            pr = re[i]; pi = im[i]
            boxSum += f - box[boxIdx]; box[boxIdx] = f
            if (++boxIdx == win) boxIdx = 0
            if (count == cap) compact()
            s[count++] = boxSum / win
        }
        scan()
    }

    private fun compact() {
        scan()
        val drop = if (scanFrom == 0) count / 2 else scanFrom
        System.arraycopy(s, drop, s, 0, count - drop)
        count -= drop
        scanFrom = 0
    }

    /**
     * Correlation of the header at sample [at], from -1 to 1, normalised by the energy under it: a header half in
     * silence, where one sample would match by itself, scores low.
     */
    private fun corr(at: Int): Float {
        var mean = 0f
        for (o in headerAt) mean += s[at + o]
        mean /= headerAt.size
        var c = 0f
        var e = 0f
        for (k in headerAt.indices) { val v = s[at + headerAt[k]] - mean; c += headerChips[k] * v; e += v * v }
        return if (e > 0) c / kotlin.math.sqrt(e * headerAt.size) else 0f
    }

    private fun scan() {
        var at = scanFrom
        while (at + frameSpan < count) {
            val r = corr(at)
            if (abs(r) > 0.8f) {
                // Refine on the next few samples: the best correlation is the chip centre.
                var best = at
                var bestR = abs(r)
                // The header looks like itself one bit over: the best of the next three bits is the real start.
                for (d in 1..6 * win) {
                    val r2 = abs(corr(at + d))
                    if (r2 > bestR) { bestR = r2; best = at + d }
                }
                if (bestR < 0.9f) { at++; continue }
                val sign = if (corr(best) > 0) 1 else -1
                // The bit clock follows the sonde: each bit is read where its two chips differ most, within two
                // samples of where it was expected, so a clock a few per mille off does not slip over 560 chips.
                var p = best + 0.5 * chip
                val bits = IntArray(Dfm.FRAME_BITS) { _ ->
                    var bestD = 0f
                    var bestP = p
                    for (d in -2..2) {
                        val q = p + d
                        val a = s[q.roundToInt()]
                        val b = s[(q + chip).roundToInt()]
                        if (abs(b - a) > bestD) { bestD = abs(b - a); bestP = q }
                    }
                    p = bestP
                    val a = s[p.roundToInt()]
                    val b = s[(p + chip).roundToInt()]
                    p += 2 * chip
                    if ((b - a) * sign > 0) 1 else 0
                }
                onFrame(bits)
                at = best + frameSpan - win
                continue
            }
            at++
        }
        scanFrom = at
    }
}
