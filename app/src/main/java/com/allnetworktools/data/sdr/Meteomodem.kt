package com.allnetworktools.data.sdr

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Meteomodem M10 and M20 radiosondes, launched by Météo-France. Frame formats as documented by the open-source
 * rs1729/RS decoders (m10mod, mXXmod): bits sent MSB first at 9615 bit/s (M10) or 9600 bit/s (M20), each bit as two
 * half-bit symbols, a bit being 1 when its two symbols differ. Byte 0 is the frame length, byte 1 the type, and the
 * last two bytes a 16-bit check over everything before them.
 */
object Meteomodem {
    const val M10_BAUD = 9615.0
    const val M20_BAUD = 9600.0
    const val M10_LEN = 0x64 + 1
    const val M20_LEN = 0x45 + 1

    /** Frame starts the demodulator looks for: length and type bytes, with the bit rate of that model. */
    val HEADERS = listOf(
        Triple(byteArrayOf(0x64, 0x9F.toByte()), M10_BAUD, M10_LEN), // M10, Trimble GPS
        Triple(byteArrayOf(0x64, 0xAF.toByte()), M10_BAUD, M10_LEN), // M10+, Gtop GPS
        Triple(byteArrayOf(0x45, 0x20), M20_BAUD, M20_LEN), // M20
    )

    private fun updateCheck(c: Int, byte: Int): Int {
        var b = byte and 0xFF
        val c1 = c and 0xFF
        b = (b shr 1) or ((b and 1) shl 7)
        b = b xor ((b shr 2) and 0xFF)
        val t6 = (c and 1) xor ((c shr 2) and 1) xor ((c shr 4) and 1)
        val t7 = ((c shr 1) and 1) xor ((c shr 3) and 1) xor ((c shr 5) and 1)
        val t = (c and 0x3F) or (t6 shl 6) or (t7 shl 7)
        var s = (c shr 7) and 0xFF
        s = s xor ((s shr 2) and 0xFF)
        val c0 = b xor t xor s
        return ((c1 shl 8) or c0) and 0xFFFF
    }

    /** The frame check over the first [len] bytes. */
    fun check(f: ByteArray, len: Int): Int {
        var cs = 0
        for (i in 0 until len) cs = updateCheck(cs, f[i].toInt())
        return cs and 0xFFFF
    }

    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    private fun be16s(b: ByteArray, o: Int) = ((u8(b, o) shl 8) or u8(b, o + 1)).toShort().toInt()
    private fun be24(b: ByteArray, o: Int) = (u8(b, o) shl 16) or (u8(b, o + 1) shl 8) or u8(b, o + 2)
    private fun be32(b: ByteArray, o: Int) = (u8(b, o) shl 24) or (u8(b, o + 1) shl 16) or (u8(b, o + 2) shl 8) or u8(b, o + 3)

    /** True when [f] is a complete frame of a known model whose check matches. */
    fun valid(f: ByteArray): Boolean {
        if (f.size < 3) return false
        val len = when {
            u8(f, 0) == 0x64 && (u8(f, 1) == 0x9F || u8(f, 1) == 0xAF) -> M10_LEN
            u8(f, 0) == 0x45 && u8(f, 1) == 0x20 -> M20_LEN
            else -> return false
        }
        if (f.size < len) return false
        val cs = (u8(f, len - 2) shl 8) or u8(f, len - 1)
        return check(f, len - 2) == cs
    }

    private fun course(ve: Double, vn: Double): Double {
        val d = Math.toDegrees(atan2(ve, vn))
        return if (d < 0) d + 360 else d
    }

    /** Decodes a frame that passed [valid]. */
    fun parse(f: ByteArray): SondeFrame? {
        if (!valid(f)) return null
        return when (u8(f, 1)) {
            0x9F -> {
                // Trimble GPS packet: ENU velocity in 0.005 m/s, position in 2^32/360 units, altitude in mm.
                val ve = be16s(f, 0x04) / 200.0
                val vn = be16s(f, 0x06) / 200.0
                val vu = be16s(f, 0x08) / 200.0
                val scale = (1 shl 30) / 90.0
                val lat = be32(f, 0x0E) / scale
                val lon = be32(f, 0x12) / scale
                val alt = be32(f, 0x16) / 1000.0
                val sats = u8(f, 0x1E)
                val ok = u8(f, 0x02) == 0x20 && lat in -90.0..90.0 && lon in -180.0..180.0 && alt in -500.0..60_000.0
                SondeFrame(
                    "M10", m10Serial(f), u8(f, 0x62), m10Battery(f),
                    lat.takeIf { ok }, lon.takeIf { ok }, alt.takeIf { ok },
                    sqrt(ve * ve + vn * vn).takeIf { ok }, course(ve, vn).takeIf { ok }, vu.takeIf { ok }, sats.takeIf { ok },
                )
            }
            0xAF -> {
                // Gtop GPS: position in 1e-6 degrees, altitude in cm (24 bits, signed), ENU velocity in cm/s.
                val lat = be32(f, 0x04) / 1e6
                val lon = be32(f, 0x08) / 1e6
                val alt = (be24(f, 0x0C).let { if (it and 0x800000 != 0) it - 0x1000000 else it }) / 100.0
                val ve = be16s(f, 0x0F) / 100.0
                val vn = be16s(f, 0x11) / 100.0
                val vu = be16s(f, 0x13) / 100.0
                val ok = lat in -90.0..90.0 && lon in -180.0..180.0 && (lat != 0.0 || lon != 0.0) && alt in -500.0..60_000.0
                SondeFrame(
                    "M10+", m10Serial(f), u8(f, 0x62), m10Battery(f),
                    lat.takeIf { ok }, lon.takeIf { ok }, alt.takeIf { ok },
                    sqrt(ve * ve + vn * vn).takeIf { ok }, course(ve, vn).takeIf { ok }, vu.takeIf { ok }, null,
                )
            }
            0x20 -> {
                // Position in 1e-6 degrees, altitude in cm, velocities in cm/s.
                val lat = be32(f, 0x1C) / 1e6
                val lon = be32(f, 0x20) / 1e6
                val alt = be24(f, 0x08) / 100.0
                val ve = be16s(f, 0x0B) / 100.0
                val vn = be16s(f, 0x0D) / 100.0
                val vu = be16s(f, 0x18) / 100.0
                val ok = lat in -90.0..90.0 && lon in -180.0..180.0 && (lat != 0.0 || lon != 0.0) && alt < 60_000
                SondeFrame(
                    "M20", m20Serial(f), u8(f, 0x15), u8(f, 0x26) * 3.3 / 255, // upper 8 bits of the ADC
                    lat.takeIf { ok }, lon.takeIf { ok }, alt.takeIf { ok },
                    sqrt(ve * ve + vn * vn).takeIf { ok }, course(ve, vn).takeIf { ok }, vu.takeIf { ok }, null,
                )
            }
            else -> null
        }
    }

    /** Battery voltage of an M10 (four AA cells): a 10-bit ADC reading behind a 2.709 divider, 2.5 V full scale. */
    fun m10Battery(f: ByteArray): Double = ((u8(f, 0x46) shl 8) or u8(f, 0x45)) * 2.709 * 2.5 / 1023

    /** Serial number as printed on the M10 label, e.g. "805-2-31234". */
    fun m10Serial(f: ByteArray): String {
        val b2 = u8(f, 0x5D + 2)
        val w = u8(f, 0x5D + 3) or (u8(f, 0x5D + 4) shl 8)
        return "%X%02d-%X-%d%04d".format((b2 shr 4) and 0xF, b2 and 0xF, u8(f, 0x5D) and 0xF, (w shr 13) and 7, w and 0x1FFF)
    }

    /** Serial number of an M20, e.g. "108-2-10123": year and month of make, then the unit. */
    fun m20Serial(f: ByteArray): String {
        val sn = u8(f, 0x12) or (u8(f, 0x13) shl 8) or (u8(f, 0x14) shl 16)
        val ym = sn and 0x7F
        return "%d%02d-%d-%d%04d".format(ym / 12, ym % 12 + 1, ((sn shr 7) and 7) + 1, (sn shr 23) and 1, (sn shr 10) and 0x1FFF)
    }
}

/**
 * Demodulator for Meteomodem sondes from complex baseband at [sampleRate] (about 5 samples per half-bit symbol):
 * FM discriminator smoothed over one symbol, correlation with the symbols of each model's first two bytes (either
 * polarity), then one sample per symbol at the symbol centres. Only frames whose check matches are passed on.
 */
class MeteomodemDemodulator(private val sampleRate: Double, private val onFrame: (ByteArray) -> Unit) {
    private class Pattern(val bytes: ByteArray, val baud: Double, val len: Int, sampleRate: Double) {
        val symLen = sampleRate / (2 * baud)
        val symbols: IntArray = run {
            // Each pair of symbols differs for a 1 and repeats for a 0, and the next pair starts on the other level.
            val out = IntArray(bytes.size * 16)
            var prev = 1
            var k = 0
            for (b in bytes) for (i in 7 downTo 0) {
                val bit = (b.toInt() shr i) and 1
                val s1 = -prev
                val s2 = if (bit == 1) -s1 else s1
                out[k++] = s1; out[k++] = s2
                prev = s2
            }
            out
        }
        val offsets = IntArray(symbols.size) { ((it + 0.5) * symLen).roundToInt() }
        val span: Int get() = ((len * 16 + 1) * symLen).toInt() + 2
    }

    private val patterns = Meteomodem.HEADERS.map { (b, baud, len) -> Pattern(b, baud, len, sampleRate) }
    private val win = (sampleRate / (2 * Meteomodem.M10_BAUD)).roundToInt().coerceAtLeast(1)
    private val cap = 1 shl 17
    private val s = FloatArray(cap)
    private var count = 0
    private var prevRe = 0f
    private var prevIm = 0f
    private val box = FloatArray(win)
    private var boxSum = 0f
    private var boxIdx = 0
    private var scanFrom = 0

    var frames = 0
        private set

    fun feed(re: FloatArray, im: FloatArray, n: Int) {
        for (i in 0 until n) {
            val r = re[i] * prevRe + im[i] * prevIm
            val q = im[i] * prevRe - re[i] * prevIm
            prevRe = re[i]; prevIm = im[i]
            val f = atan2(q, r)
            boxSum += f - box[boxIdx]
            box[boxIdx] = f
            boxIdx = (boxIdx + 1) % win
            if (count == cap) compact()
            s[count++] = boxSum / win
        }
        scan()
    }

    private fun compact() {
        scan()
        if (scanFrom == 0) scanFrom = count / 2
        val drop = scanFrom
        System.arraycopy(s, drop, s, 0, count - drop)
        count -= drop
        scanFrom = 0
    }

    /** Normalised correlation of [p] at sample [at], and the mean level under it. */
    private fun corr(p: Pattern, at: Int): Pair<Float, Float> {
        var mean = 0f
        for (o in p.offsets) mean += s[at + o]
        mean /= p.offsets.size
        var c = 0f
        var norm = 0f
        for (k in p.offsets.indices) {
            val v = s[at + p.offsets[k]] - mean
            c += p.symbols[k] * v
            norm += abs(v)
        }
        return (if (norm > 0) c / norm else 0f) to mean
    }

    private fun decode(p: Pattern, at: Int, mean: Float): ByteArray {
        val f = ByteArray(p.len)
        for (k in 0 until p.len * 8) {
            val a = s[at + ((2 * k + 0.5) * p.symLen).roundToInt()] - mean
            val b = s[at + ((2 * k + 1.5) * p.symLen).roundToInt()] - mean
            if (a * b < 0) f[k / 8] = (f[k / 8].toInt() or (0x80 shr (k % 8))).toByte()
        }
        return f
    }

    private fun scan() {
        val longest = patterns.maxOf { it.span }
        while (scanFrom + longest < count) {
            var found: ByteArray? = null
            var used: Pattern? = null
            for (p in patterns) {
                val (c, _) = corr(p, scanFrom)
                if (abs(c) < THRESHOLD) continue
                // Try the neighbouring samples too: the frame check picks the right timing.
                for (d in -win..win) {
                    val at = scanFrom + d
                    if (at < 0) continue
                    val (_, mean) = corr(p, at)
                    val f = decode(p, at, mean)
                    if (Meteomodem.valid(f)) { found = f; used = p; break }
                }
                if (found != null) break
            }
            if (found != null && used != null) {
                frames++
                onFrame(found)
                scanFrom += (used.len * 16 * used.symLen).toInt()
            } else scanFrom++
        }
    }

    private companion object {
        const val THRESHOLD = 0.7f
    }
}
