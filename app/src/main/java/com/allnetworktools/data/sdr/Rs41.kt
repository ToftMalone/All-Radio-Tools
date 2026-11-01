package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** What one radiosonde frame (RS41, M10 or M20) told about its sonde. */
data class SondeFrame(
    /** Model: "RS41", "M10", "M10+" or "M20". */
    val model: String,
    val serial: String?,
    val frameNumber: Int?,
    val batteryV: Double?,
    val lat: Double?,
    val lon: Double?,
    val altitudeM: Double?,
    /** Horizontal speed (m/s), course (degrees) and climb rate (m/s). */
    val speedMs: Double?,
    val courseDeg: Double?,
    val climbMs: Double?,
    val satellites: Int?,
)

/**
 * Vaisala RS41 radiosonde frames (format as decoded by the open-source rs1729/RS "rs41mod" decoder):
 * GFSK 4800 bit/s, bytes sent LSB first, the whole frame XOR-scrambled by a 64-byte mask, an 8-byte header,
 * then blocks [id][length][data][CRC-16/CCITT-FALSE].
 */
object Rs41 {
    const val BAUD = 4800
    const val STD_LEN = 320
    const val EXT_LEN = 518

    /** The header as transmitted, bit by bit in time order. */
    const val HEADER_BITS = "0000100001101101010100111000100001000100011010010100100000011111"

    val MASK = intArrayOf(
        0x96, 0x83, 0x3E, 0x51, 0xB1, 0x49, 0x08, 0x98, 0x32, 0x05, 0x59, 0x0E, 0xF9, 0x44, 0xC6, 0x26,
        0x21, 0x60, 0xC2, 0xEA, 0x79, 0x5D, 0x6D, 0xA1, 0x54, 0x69, 0x47, 0x0C, 0xDC, 0xE8, 0x5C, 0xF1,
        0xF7, 0x76, 0x82, 0x7F, 0x07, 0x99, 0xA2, 0x2C, 0x93, 0x7C, 0x30, 0x63, 0xF5, 0x10, 0x2E, 0x61,
        0xD0, 0xBC, 0xB4, 0xB6, 0x06, 0xAA, 0xF4, 0x23, 0x78, 0x6E, 0x3B, 0xAE, 0xBF, 0x7B, 0x4C, 0xC1,
    )

    fun crc16(b: ByteArray, from: Int, len: Int): Int {
        var r = 0xFFFF
        for (i in from until from + len) {
            r = r xor ((b[i].toInt() and 0xFF) shl 8)
            repeat(8) { r = if (r and 0x8000 != 0) (r shl 1) xor 0x1021 else r shl 1; r = r and 0xFFFF }
        }
        return r
    }

    /** Frame length announced by the byte before the first block: 0x0F or 0xF0, whichever is nearer bit for bit. */
    fun length(frame: ByteArray): Int {
        val b = frame[0x38].toInt() and 0xFF
        return if (Integer.bitCount(b xor 0xF0) < Integer.bitCount(b xor 0x0F)) EXT_LEN else STD_LEN
    }

    // Reed-Solomon RS(255, 231) over GF(2^8) with x^8+x^4+x^3+x^2+1, roots α^0..α^23, as rs41mod uses it. Two
    // codewords are interleaved: the even and odd bytes from 0x38 to the end of a long frame (a short one is zero
    // padded), with their 24 parity bytes each at 0x08 and 0x20. Codeword byte i is the coefficient of x^i.
    private const val RS_R = 24
    private const val RS_K = 231
    private const val MSG_POS = 0x38
    private const val PAR_POS = 0x08
    private val EXP = IntArray(512)
    private val LOG = IntArray(256)
    private val GEN: IntArray

    init {
        var x = 1
        for (i in 0 until 255) { EXP[i] = x; LOG[x] = i; x = x shl 1; if (x and 0x100 != 0) x = x xor 0x11D }
        for (i in 255 until 512) EXP[i] = EXP[i - 255]
        var g = intArrayOf(1)
        for (i in 0 until RS_R) {
            val n = IntArray(g.size + 1)
            for (k in g.indices) { n[k + 1] = n[k + 1] xor g[k]; n[k] = n[k] xor mul(g[k], EXP[i]) }
            g = n
        }
        GEN = g
    }

    private fun mul(a: Int, b: Int) = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]
    private fun inv(a: Int) = EXP[255 - LOG[a]]
    private fun pow(i: Int) = EXP[((i % 255) + 255) % 255]

    /** The 24 parity bytes of a 231-byte message (for tests and for checking). */
    fun rsParity(msg: IntArray): IntArray {
        val r = IntArray(255)
        for (i in 0 until RS_K) r[RS_R + i] = msg[i]
        for (d in 254 downTo RS_R) {
            val c = r[d]
            if (c != 0) for (k in 0..RS_R) r[d - RS_R + k] = r[d - RS_R + k] xor mul(c, GEN[k])
        }
        return r.copyOf(RS_R)
    }

    /** Corrects up to 12 bad bytes of [cw] in place; returns how many were bad, or −1 if it cannot. */
    fun rsDecode(cw: IntArray): Int {
        val s = IntArray(RS_R) { j -> var v = 0; for (i in 0 until 255) if (cw[i] != 0) v = v xor mul(cw[i], pow(i * j)); v }
        if (s.all { it == 0 }) return 0
        // Berlekamp-Massey for the error locator.
        var c = IntArray(RS_R + 1).also { it[0] = 1 }
        var bb = IntArray(RS_R + 1).also { it[0] = 1 }
        var l = 0
        var m = 1
        var bd = 1
        for (n in 0 until RS_R) {
            var d = s[n]
            for (i in 1..l) d = d xor mul(c[i], s[n - i])
            if (d == 0) { m++; continue }
            val coef = mul(d, inv(bd))
            val t = c.copyOf()
            for (i in 0..RS_R - m) c[i + m] = c[i + m] xor mul(coef, bb[i])
            if (2 * l <= n) { l = n + 1 - l; bb = t; bd = d; m = 1 } else m++
        }
        if (l > RS_R / 2) return -1
        // Error evaluator Ω = S·Λ mod x^24, then Chien search and Forney (first root α^0: e = X·Ω(X⁻¹)/Λ'(X⁻¹)).
        val om = IntArray(RS_R)
        for (i in 0 until RS_R) for (k in 0..minOf(i, l)) om[i] = om[i] xor mul(c[k], s[i - k])
        val pos = ArrayList<Int>()
        val vals = ArrayList<Int>()
        for (i in 0 until 255) {
            val xi = pow(-i)
            var lam = 0
            var xp = 1
            for (k in 0..l) { lam = lam xor mul(c[k], xp); xp = mul(xp, xi) }
            if (lam != 0) continue
            var o = 0
            xp = 1
            for (k in 0 until RS_R) { o = o xor mul(om[k], xp); xp = mul(xp, xi) }
            var dl = 0
            xp = 1
            for (k in 1..l step 2) { dl = dl xor mul(c[k], xp); xp = mul(xp, mul(xi, xi)) }
            if (dl == 0) return -1
            pos += i
            vals += mul(pow(i), mul(o, inv(dl)))
        }
        if (pos.size != l) return -1
        val fixed = cw.copyOf()
        for (k in pos.indices) fixed[pos[k]] = fixed[pos[k]] xor vals[k]
        for (j in 0 until RS_R) { var v = 0; for (i in 0 until 255) if (fixed[i] != 0) v = v xor mul(fixed[i], pow(i * j)); if (v != 0) return -1 }
        fixed.copyInto(cw)
        return l
    }

    /**
     * Applies the frame's Reed-Solomon correction. Returns the corrected frame and how many bytes were mended, or
     * null if either codeword is beyond repair (the blocks' own CRCs then decide what is kept).
     */
    fun correct(frame: ByteArray): Pair<ByteArray, Int>? {
        if (frame.size < STD_LEN) return null
        val buf = frame.copyOf(EXT_LEN)
        var total = 0
        for (w in 0..1) {
            val cw = IntArray(255)
            for (i in 0 until RS_R) cw[i] = buf[PAR_POS + RS_R * w + i].toInt() and 0xFF
            for (i in 0 until RS_K) cw[RS_R + i] = buf[MSG_POS + 2 * i + w].toInt() and 0xFF
            val before = cw.copyOf()
            val n = rsDecode(cw)
            if (n < 0) return null
            for (i in 0 until 255) if (cw[i] != before[i]) {
                val at = if (i < RS_R) PAR_POS + RS_R * w + i else MSG_POS + 2 * (i - RS_R) + w
                if (at >= frame.size) return null // a "fix" in the padding of a short frame: not this frame
                buf[at] = cw[i].toByte()
            }
            total += n
        }
        return buf.copyOf(frame.size) to total
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun s16(b: ByteArray, o: Int) = u16(b, o).toShort().toInt()
    private fun s32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)

    /** WGS84 ECEF (m) to latitude, longitude (degrees) and ellipsoidal height (m). */
    fun ecefToGeo(x: Double, y: Double, z: Double): Triple<Double, Double, Double> {
        val a = 6378137.0
        val b = 6356752.31424518
        val e2 = (a * a - b * b) / (a * a)
        val ee2 = (a * a - b * b) / (b * b)
        val lam = atan2(y, x)
        val p = sqrt(x * x + y * y)
        val t = atan2(z * a, p * b)
        val phi = atan2(z + ee2 * b * sin(t) * sin(t) * sin(t), p - e2 * a * cos(t) * cos(t) * cos(t))
        val r = a / sqrt(1 - e2 * sin(phi) * sin(phi))
        return Triple(Math.toDegrees(phi), Math.toDegrees(lam), p / cos(phi) - r)
    }

    /**
     * Parses a descrambled frame after its Reed-Solomon correction; blocks whose CRC still fails are skipped. Null if
     * no block was valid.
     */
    fun parse(raw: ByteArray): SondeFrame? {
        val frame = correct(raw)?.first ?: raw
        val len = minOf(length(frame), frame.size)
        var pos = 0x39
        var serial: String? = null
        var nb: Int? = null
        var batt: Double? = null
        var geo: Triple<Double, Double, Double>? = null
        var speed: Double? = null
        var course: Double? = null
        var climb: Double? = null
        var sats: Int? = null
        var valid = 0
        while (pos + 4 <= len) {
            val id = frame[pos].toInt() and 0xFF
            val n = frame[pos + 1].toInt() and 0xFF
            if (pos + 2 + n + 2 > len) break
            val ok = crc16(frame, pos + 2, n) == u16(frame, pos + 2 + n)
            val d = pos + 2
            if (ok) {
                valid++
                when (id) {
                    0x79 -> if (n >= 11) {
                        nb = u16(frame, d)
                        serial = String(frame, d + 2, 8, Charsets.US_ASCII).trim { it <= ' ' }.takeIf { s -> s.all { it.isLetterOrDigit() } }
                        batt = (frame[d + 10].toInt() and 0xFF) / 10.0
                    }
                    0x7B, 0x82 -> if (n >= 18) {
                        val x = s32(frame, d) / 100.0
                        val y = s32(frame, d + 4) / 100.0
                        val z = s32(frame, d + 8) / 100.0
                        val g = ecefToGeo(x, y, z)
                        if (g.third in -1000.0..80_000.0) {
                            geo = g
                            val vx = s16(frame, d + 12) / 100.0
                            val vy = s16(frame, d + 14) / 100.0
                            val vz = s16(frame, d + 16) / 100.0
                            val phi = Math.toRadians(g.first)
                            val lam = Math.toRadians(g.second)
                            val vN = -vx * sin(phi) * cos(lam) - vy * sin(phi) * sin(lam) + vz * cos(phi)
                            val vE = -vx * sin(lam) + vy * cos(lam)
                            climb = vx * cos(phi) * cos(lam) + vy * cos(phi) * sin(lam) + vz * sin(phi)
                            speed = sqrt(vN * vN + vE * vE)
                            course = (Math.toDegrees(atan2(vE, vN)) + 360) % 360
                            if (id == 0x7B && n >= 19) sats = frame[d + 18].toInt() and 0xFF
                        }
                    }
                }
            }
            pos += 2 + n + 2
        }
        if (valid == 0) return null
        return SondeFrame("RS41", serial, nb, batt, geo?.first, geo?.second, geo?.third, speed, course, climb, sats)
    }
}

/**
 * GFSK demodulator for complex baseband at [sampleRate] (about 10 samples per bit): FM discriminator, moving
 * average over one bit, correlation with the 64-bit header (either polarity) to find the frame, then one sample per
 * bit at the bit centres.
 */
class Rs41Demodulator(private val sampleRate: Double, private val onFrame: (ByteArray) -> Unit) {
    private val sps = sampleRate / Rs41.BAUD
    private val header = IntArray(64) { if (Rs41.HEADER_BITS[it] == '1') 1 else -1 }
    private val offsets = IntArray(64) { ((it + 0.5) * sps).roundToInt() }
    private val win = sps.roundToInt().coerceAtLeast(1)

    private val cap = 1 shl 17
    private val s = FloatArray(cap) // smoothed discriminator
    private var count = 0
    private var prevRe = 0f
    private var prevIm = 0f
    private val box = FloatArray(win)
    private var boxSum = 0f
    private var boxIdx = 0
    private var scanFrom = 0

    var frames = 0
        private set

    private fun samplesFor(bytes: Int) = ((bytes * 8 + 1) * sps).toInt() + win

    fun feed(re: FloatArray, im: FloatArray, n: Int) {
        for (i in 0 until n) {
            // angle(x[n] · conj(x[n−1])): instantaneous frequency.
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

    /** Drops what has been scanned already; if nothing could be scanned yet, the older half goes. */
    private fun compact() {
        scan()
        if (scanFrom == 0) scanFrom = count / 2
        val drop = scanFrom
        System.arraycopy(s, drop, s, 0, count - drop)
        count -= drop
        scanFrom = 0
    }

    private var lastRaw = 0f

    /** Normalised header correlation (−1…1) and the mean level; [lastRaw] keeps the unnormalised value. */
    private fun corr(p: Int): Pair<Float, Float> {
        var mean = 0f
        for (k in 0 until 64) mean += s[p + offsets[k]]
        mean /= 64
        var c = 0f
        var norm = 0f
        for (k in 0 until 64) {
            val v = s[p + offsets[k]] - mean
            c += header[k] * v
            norm += abs(v)
        }
        lastRaw = c
        return (if (norm > 0) c / norm else 0f) to mean
    }

    private fun bytes(start: Int, mean: Float, pol: Int, len: Int): ByteArray {
        val frame = ByteArray(len)
        for (k in 0 until len * 8) {
            val v = (s[start + ((k + 0.5) * sps).roundToInt()] - mean) * pol
            if (v > 0) frame[k / 8] = (frame[k / 8].toInt() or (1 shl (k % 8))).toByte()
        }
        for (i in frame.indices) frame[i] = (frame[i].toInt() xor Rs41.MASK[i % 64]).toByte()
        return frame
    }

    private fun scan() {
        val headerSpan = offsets.last() + 2 * win
        while (scanFrom + headerSpan < count) {
            val (c, _) = corr(scanFrom)
            if (abs(c) < 0.8f) { scanFrom++; continue }
            // Refine on the raw correlation: it peaks at bit centres, where the eye is widest.
            var best = scanFrom
            var bestC = -1f
            for (p in scanFrom - win..scanFrom + 2 * win) {
                if (p < 0) continue
                corr(p)
                if (abs(lastRaw) > bestC) { bestC = abs(lastRaw); best = p }
            }
            if (best + samplesFor(Rs41.STD_LEN) >= count) return // wait for the rest of the frame
            val (cb, mean) = corr(best)
            val pol = if (cb > 0) 1 else -1
            var frame = bytes(best, mean, pol, Rs41.STD_LEN)
            if (Rs41.length(frame) == Rs41.EXT_LEN) {
                if (best + samplesFor(Rs41.EXT_LEN) >= count) return
                frame = bytes(best, mean, pol, Rs41.EXT_LEN)
            }
            frames++
            onFrame(frame)
            scanFrom = best + (frame.size * 8 * sps).toInt()
        }
    }
}

/** Plain complex FIR low-pass and decimator for float samples. */
class FloatDecimator(private val decim: Int, cutoff: Double, taps: Int = 64) {
    private val h: FloatArray
    private val n = taps
    private val hr = FloatArray(taps * 2)
    private val hi = FloatArray(taps * 2)
    private var pos = 0
    private var phase = 0

    init {
        val raw = DoubleArray(taps) { i ->
            val m = i - (taps - 1) / 2.0
            val sinc = if (m == 0.0) 2 * cutoff else sin(2 * PI * cutoff * m) / (PI * m)
            sinc * (0.42 - 0.5 * cos(2 * PI * i / (taps - 1)) + 0.08 * cos(4 * PI * i / (taps - 1)))
        }
        val sum = raw.sum()
        h = FloatArray(taps) { (raw[it] / sum).toFloat() }
    }

    fun process(re: FloatArray, im: FloatArray, len: Int, outRe: FloatArray, outIm: FloatArray): Int {
        var o = 0
        for (i in 0 until len) {
            hr[pos] = re[i]; hr[pos + n] = re[i]
            hi[pos] = im[i]; hi[pos + n] = im[i]
            pos++
            if (pos == n) pos = 0
            if (++phase == decim) {
                phase = 0
                var ar = 0f
                var ai = 0f
                for (k in 0 until n) { ar += h[k] * hr[pos + k]; ai += h[k] * hi[pos + k] }
                outRe[o] = ar; outIm[o] = ai; o++
            }
        }
        return o
    }
}

/** What is known about one sonde, built from its successive frames. */
class Sonde(val serial: String, val model: String) {
    var frameNumber: Int? = null
    var batteryV: Double? = null
    var lat: Double? = null
    var lon: Double? = null
    var altitudeM: Double? = null
    var maxAltitudeM: Double? = null
    var speedMs: Double? = null
    var courseDeg: Double? = null
    var climbMs: Double? = null
    var satellites: Int? = null
    var frames = 0
    var lastSeenMs = 0L
    val track = ArrayList<DoubleArray>() // lat, lon, altitude
    /** Wind profile from the sonde's own drift: altitude, east and north speed, one sample per 100 m layer. */
    val winds = LinkedHashMap<Int, DoubleArray>()
}

/** Groups frames by serial number. A frame whose identity was lost goes to the last sonde heard. */
class SondeTracker {
    val sondes = LinkedHashMap<String, Sonde>()
    private var last: String? = null

    fun update(f: SondeFrame, nowMs: Long) {
        val serial = f.serial ?: last ?: return
        last = serial
        val s = sondes.getOrPut(serial) { Sonde(serial, f.model) }
        s.frames++
        s.lastSeenMs = nowMs
        f.frameNumber?.let { s.frameNumber = it }
        f.batteryV?.let { s.batteryV = it }
        f.satellites?.let { s.satellites = it }
        if (f.lat != null && f.lon != null && f.altitudeM != null) {
            s.lat = f.lat; s.lon = f.lon; s.altitudeM = f.altitudeM
            s.maxAltitudeM = maxOf(s.maxAltitudeM ?: f.altitudeM, f.altitudeM)
            s.speedMs = f.speedMs; s.courseDeg = f.courseDeg; s.climbMs = f.climbMs
            if (f.speedMs != null && f.courseDeg != null) {
                val (e, n) = SondeLanding.components(f.speedMs, f.courseDeg)
                s.winds[(f.altitudeM / 100).toInt()] = doubleArrayOf(f.altitudeM, e, n)
            }
            // One point every ~10 m of altitude change keeps a whole flight (up to ~35 km) to a few thousand points.
            val prev = s.track.lastOrNull()
            if (prev == null || kotlin.math.abs(prev[2] - f.altitudeM) >= 10 || s.track.size < 2) {
                s.track += doubleArrayOf(f.lat, f.lon, f.altitudeM)
                if (s.track.size > 5000) s.track.removeAt(0)
            }
        }
    }

    /** Sondes not heard for 30 min are forgotten. */
    fun prune(nowMs: Long) {
        sondes.values.removeAll { nowMs - it.lastSeenMs > 30 * 60_000 }
    }
}
