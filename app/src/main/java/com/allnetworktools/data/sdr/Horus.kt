package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Project Horus amateur high-altitude balloons (usually 434.2 - 434.9 MHz): Horus Binary v1 and v2, 100 baud 4FSK, Golay
 * (23,12), interleaver, scrambler and CRC16. Ported from OnAir (core/src/horus, GPL-3.0), whose layer 2 constants follow
 * horusdemodlib's horus_l2.c / golay23.c (David Rowe, Tomas Härdin).
 */

data class HorusPacket(
    val version: Int,
    val payloadId: Int,
    val seq: Int,
    val lat: Double,
    val lon: Double,
    val altitudeM: Double,
    val speedKmh: Int,
    val sats: Int,
    val tempC: Int,
    val batteryV: Double,
    val corrected: Int,
)

object Horus {
    const val V1_BYTES = 22
    const val V2_BYTES = 32
    private const val GOLAY_POLY = 0xC75

    fun crc16(p: ByteArray, n: Int): Int {
        var c = 0xFFFF
        for (i in 0 until n) {
            c = c xor ((p[i].toInt() and 0xFF) shl 8)
            repeat(8) { c = if (c and 0x8000 != 0) ((c shl 1) xor 0x1021) and 0xFFFF else (c shl 1) and 0xFFFF }
        }
        return c
    }

    private fun syndrome(w0: Int): Int {
        var w = w0
        for (x in 11 downTo 0) if (w and (1 shl (11 + x)) != 0) w = w xor (GOLAY_POLY shl x)
        return w and 0x7FF
    }

    /** Error pattern by syndrome: every error of up to three bits in 23. */
    private val golayErr: IntArray by lazy {
        val err = IntArray(2048) { -1 }
        err[0] = 0
        for (a in 0 until 23) {
            err[syndrome(1 shl a)] = 1 shl a
            for (b in a + 1 until 23) {
                err[syndrome((1 shl a) or (1 shl b))] = (1 shl a) or (1 shl b)
                for (c in b + 1 until 23) err[syndrome((1 shl a) or (1 shl b) or (1 shl c))] = (1 shl a) or (1 shl b) or (1 shl c)
            }
        }
        err
    }

    fun golayEncode(data12: Int): Int { val w = (data12 and 0xFFF) shl 11; return w or syndrome(w) }

    /** 12 data bits and the number of bits fixed. */
    fun golayDecode(w0: Int): Pair<Int, Int> {
        var w = w0 and 0x7FFFFF
        val e = golayErr[syndrome(w)]
        if (e != -1) w = w xor e
        return (w ushr 11) to (if (e == -1) 0 else Integer.bitCount(e))
    }

    fun frameBytes(n: Int): Int {
        val cw = (8 * n + 11) / 12
        return (16 + 8 * n + 11 * cw + 7) / 8
    }

    private fun isPrime(n: Int): Boolean { if (n < 2) return false; var d = 2; while (d * d <= n) { if (n % d == 0) return false; d++ }; return true }

    fun interleaverStep(nbytes: Int): Int {
        if (nbytes == 43) return 337
        if (nbytes == 63) return 389
        var b = floor(nbytes * 8 / 1.62).toInt() + 1
        while (!isPrime(b)) b++
        return b
    }

    private fun bitLsb(b: ByteArray, i: Int) = (b[i shr 3].toInt() shr (i and 7)) and 1
    private fun setLsb(b: ByteArray, i: Int, v: Int) { b[i shr 3] = if (v != 0) (b[i shr 3].toInt() or (1 shl (i and 7))).toByte() else (b[i shr 3].toInt() and (1 shl (i and 7)).inv()).toByte() }
    private fun bitMsb(b: ByteArray, i: Int) = (b[i shr 3].toInt() shr (7 - (i and 7))) and 1

    private fun interleave(d: ByteArray, off: Int, nbytes: Int, inverse: Boolean) {
        val nbits = nbytes * 8
        val step = interleaverStep(nbytes)
        val src = d.copyOfRange(off, off + nbytes)
        val out = ByteArray(nbytes)
        for (i in 0 until nbits) {
            val j = ((step.toLong() * i) % nbits).toInt()
            if (!inverse) setLsb(out, j, bitLsb(src, i)) else setLsb(out, i, bitLsb(src, j))
        }
        out.copyInto(d, off)
    }

    /** Additive scrambler, its own inverse: 15-bit state from 0x4a80, output bit 1 xor bit 0. */
    private fun scramble(d: ByteArray, off: Int, nbytes: Int) {
        var s = 0x4a80
        for (i in 0 until nbytes * 8) {
            val o = ((s shr 1) xor s) and 1
            if (o != 0) d[off + (i shr 3)] = (d[off + (i shr 3)].toInt() xor (1 shl (i and 7))).toByte()
            s = (s shr 1) or (o shl 14)
        }
    }

    /** Unique word, payload, Golay parity, interleaved and scrambled: what a payload sends after its preamble. */
    fun encodeFrame(payload: ByteArray): ByteArray {
        val n = payload.size
        val total = frameBytes(n)
        val cw = (8 * n + 11) / 12
        val f = ByteArray(total)
        f[0] = 0x24; f[1] = 0x24
        payload.copyInto(f, 2)
        var pbit = (2 + n) * 8
        for (c in 0 until cw) {
            var d = 0
            for (k in 0 until 12) { val bi = c * 12 + k; d = (d shl 1) or (if (bi < 8 * n) bitMsb(payload, bi) else 0) }
            val par = golayEncode(d) and 0x7FF
            for (k in 10 downTo 0) { if ((par shr k) and 1 != 0) f[pbit shr 3] = (f[pbit shr 3].toInt() or (0x80 ushr (pbit and 7))).toByte(); pbit++ }
        }
        interleave(f, 2, total - 2, false)
        scramble(f, 2, total - 2)
        return f
    }

    /** The payload bytes from a frame body (after the unique word), and the bits Golay fixed. */
    fun decodeFrame(body: ByteArray, n: Int): Pair<ByteArray, Int> {
        val nb = frameBytes(n) - 2
        val cw = (8 * n + 11) / 12
        val d = body.copyOf(nb)
        scramble(d, 0, nb)
        interleave(d, 0, nb, true)
        val out = ByteArray(n)
        var fixes = 0
        var pbit = n * 8
        for (c in 0 until cw) {
            var w = 0
            for (k in 0 until 12) { val bi = c * 12 + k; w = (w shl 1) or (if (bi < 8 * n) bitMsb(d, bi) else 0) }
            for (k in 0 until 11) { w = (w shl 1) or bitMsb(d, pbit); pbit++ }
            val (dat, fx) = golayDecode(w)
            fixes += fx
            for (k in 0 until 12) { val bi = c * 12 + k; if (bi < 8 * n && (dat shr (11 - k)) and 1 != 0) out[bi shr 3] = (out[bi shr 3].toInt() or (0x80 ushr (bi and 7))).toByte() }
        }
        return out to fixes
    }

    private fun u8(p: ByteArray, i: Int) = p[i].toInt() and 0xFF
    private fun le16(p: ByteArray, i: Int) = u8(p, i) or (u8(p, i + 1) shl 8)
    private fun lef(p: ByteArray, i: Int) = Float.fromBits(u8(p, i) or (u8(p, i + 1) shl 8) or (u8(p, i + 2) shl 16) or (u8(p, i + 3) shl 24)).toDouble()

    fun parse(p: ByteArray, corrected: Int = 0): HorusPacket? {
        val n = p.size
        if (n != V1_BYTES && n != V2_BYTES) return null
        if (crc16(p, n - 2) != le16(p, n - 2)) return null
        val v2 = n == V2_BYTES
        val o = if (v2) 1 else 0
        val lat = lef(p, 6 + o)
        val lon = lef(p, 10 + o)
        if (!lat.isFinite() || !lon.isFinite() || abs(lat) > 90 || abs(lon) > 180) return null
        return HorusPacket(
            if (v2) 2 else 1, if (v2) le16(p, 0) else u8(p, 0), le16(p, 1 + o), lat, lon, le16(p, 14 + o).toDouble(),
            u8(p, 16 + o), u8(p, 17 + o), p[18 + o].toInt(), u8(p, 19 + o) * 5.0 / 255.0, corrected,
        )
    }

    /** A packet as a payload builds it: for tests. */
    fun build(id: Int, seq: Int, lat: Double, lon: Double, altM: Int, speedKmh: Int, sats: Int, tempC: Int, battV: Double, v2: Boolean): ByteArray {
        val n = if (v2) V2_BYTES else V1_BYTES
        val o = if (v2) 1 else 0
        val b = ByteArray(n)
        fun put16(i: Int, v: Int) { b[i] = v.toByte(); b[i + 1] = (v shr 8).toByte() }
        fun putf(i: Int, f: Float) { val u = f.toRawBits(); for (k in 0 until 4) b[i + k] = (u shr (8 * k)).toByte() }
        if (v2) put16(0, id) else b[0] = id.toByte()
        put16(1 + o, seq)
        putf(6 + o, lat.toFloat()); putf(10 + o, lon.toFloat())
        put16(14 + o, altM)
        b[16 + o] = speedKmh.toByte(); b[17 + o] = sats.toByte(); b[18 + o] = tempC.toByte()
        b[19 + o] = (battV * 255 / 5).roundToInt().toByte()
        put16(n - 2, crc16(b, n - 2))
        return b
    }
}

/**
 * Horus 4FSK from complex baseband at [fs] (about 10 kS/s): an averaged spectrum finds the four evenly spaced tones,
 * sliding correlators one symbol long measure them, the symbol clock sits where one tone stands out most, and four
 * tone-to-bit mappings are watched for the last preamble byte and the unique word.
 */
class HorusDemodulator(private val fs: Double = 10_000.0, private val onPacket: (HorusPacket) -> Unit) {
    private val baud = 100.0
    private val len = (fs / baud).roundToInt()

    // Tone estimator: Hann-windowed FFT power, averaged over about 3 s.
    private val nfft = Integer.highestOneBit((fs / 10).toInt()) * 2
    private val fft = Fft(nfft)
    private val win = FloatArray(nfft) { (0.5 - 0.5 * cos(2 * PI * it / nfft)).toFloat() }
    private val avg = FloatArray(nfft)
    private val estRe = FloatArray(nfft)
    private val estIm = FloatArray(nfft)
    private var estFill = 0
    private var frames = 0
    private var samples = 0L
    private var nextEst = (fs / 2).toLong()

    // Correlators: the last [len] samples mixed with each tone, running sums.
    private val tones = DoubleArray(4)
    private var haveTones = false
    private val w = DoubleArray(4)
    private val ph = DoubleArray(4)
    private val rawRe = FloatArray(len)
    private val rawIm = FloatArray(len)
    private val mixRe = Array(4) { FloatArray(len) }
    private val mixIm = Array(4) { FloatArray(len) }
    private val sumRe = DoubleArray(4)
    private val sumIm = DoubleArray(4)
    private var pos = 0
    private var sinceExact = 0
    private val timing = FloatArray(len)
    private var phase = 0
    private var best = 0
    private var sinceEmit = 0

    // Deframer.
    private val syms = ArrayDeque<Int>()
    private var base = 0L
    private val reg = IntArray(4)
    private var nbits = 0L
    private class Pend(val variant: Int, val start: Long) { var triedV1 = false }
    private val pend = ArrayList<Pend>()

    fun feed(re: FloatArray, im: FloatArray, n: Int) {
        val e = FloatArray(4)
        for (i in 0 until n) {
            estimate(re[i], im[i])
            push(re[i], im[i], e)
            if (!haveTones) continue
            var m = 0
            for (k in 1 until 4) if (e[k] > e[m]) m = k
            val tot = e[0] + e[1] + e[2] + e[3]
            val metric = if (tot > 0) (e[m] - (tot - e[m]) / 3f) / tot else 0f
            timing[phase] += (metric - timing[phase]) / 24f
            if (timing[phase] > timing[best]) best = phase
            sinceEmit++
            if (phase == best && sinceEmit > len / 2) { sinceEmit = 0; symbol(m) }
            if (++phase == len) phase = 0
        }
    }

    private fun estimate(r: Float, i: Float) {
        estRe[estFill] = r; estIm[estFill] = i
        if (++estFill == nfft) {
            val a = FloatArray(nfft) { estRe[it] * win[it] }
            val b = FloatArray(nfft) { estIm[it] * win[it] }
            fft.forward(a, b)
            val alpha = if (frames < 4) 1f / (frames + 1) else minOf(1f, (nfft / 2 / fs / 3).toFloat())
            for (k in 0 until nfft) {
                val src = (k + nfft / 2) % nfft
                avg[k] += alpha * (a[src] * a[src] + b[src] * b[src] - avg[k])
            }
            frames++
            // Half overlap.
            System.arraycopy(estRe, nfft / 2, estRe, 0, nfft / 2); System.arraycopy(estIm, nfft / 2, estIm, 0, nfft / 2)
            estFill = nfft / 2
        }
        if (++samples >= nextEst && frames >= 4) { nextEst = samples + (fs / 2).toLong(); updateTones() }
    }

    private fun updateTones() {
        val bin = fs / nfft
        val maxHz = 0.40 * fs
        val lo = maxOf(1, ceil(nfft / 2 - maxHz / bin).toInt())
        val hi = minOf(nfft - 2, floor(nfft / 2 + maxHz / bin).toInt())
        // Each 10 ms symbol spreads a tone over about 100 Hz: smooth over that before the search.
        val h = maxOf(1, (45 / bin).roundToInt())
        val sm = FloatArray(nfft)
        var run = 0.0
        for (k in 0 until nfft) {
            run += avg[k]
            if (k - 2 * h - 1 >= 0) run -= avg[k - 2 * h - 1]
            if (k - h >= 0) sm[k - h] = (run / (2 * h + 1)).toFloat()
        }
        val p = sm.copyOfRange(lo, hi + 1).also { it.sort() }
        if (p.size < 8) return
        val thr = p[p.size / 2] * 1.5f
        val cand = (lo..hi).filter { sm[it] > thr && sm[it] > sm[it - 1] && sm[it] >= sm[it + 1] }.sortedByDescending { sm[it] }
        val sep = maxOf(2, (80 / bin).roundToInt())
        val pick = ArrayList<Int>()
        for (c in cand) { if (pick.none { abs(it - c) < sep }) pick += c; if (pick.size == 4) break }
        if (pick.size < 4) return
        pick.sort()
        val f = DoubleArray(4) { i ->
            val c = pick[i]
            val a = sm[c - 1].toDouble(); val b = sm[c].toDouble(); val d = sm[c + 1].toDouble()
            val den = a - 2 * b + d
            val off = if (den != 0.0) (0.5 * (a - d) / den).coerceIn(-0.5, 0.5) else 0.0
            (c + off - nfft / 2) * bin
        }
        val gap = (f[3] - f[0]) / 3
        if ((0 until 3).any { abs(f[it + 1] - f[it] - gap) >= 0.25 * gap }) return // four evenly spaced tones only
        val moved = !haveTones || (0 until 4).any { abs(f[it] - tones[it]) > 0.25 * bin + 1.5 }
        if (moved) setTones(f)
    }

    private fun setTones(f: DoubleArray) {
        for (k in 0 until 4) { tones[k] = f[k]; w[k] = 2 * PI * f[k] / fs }
        // The stored samples mixed again with the new tones, oldest first.
        for (k in 0 until 4) {
            var sr = 0.0; var si = 0.0; var p = 0.0
            for (i in 0 until len) {
                val at = (pos + i) % len
                val c = cos(-p).toFloat(); val s = sin(-p).toFloat()
                mixRe[k][at] = rawRe[at] * c - rawIm[at] * s; mixIm[k][at] = rawRe[at] * s + rawIm[at] * c
                sr += mixRe[k][at]; si += mixIm[k][at]
                p = Math.IEEEremainder(p + w[k], 2 * PI)
            }
            sumRe[k] = sr; sumIm[k] = si; ph[k] = p
        }
        haveTones = true
    }

    private fun push(r: Float, i: Float, e: FloatArray) {
        rawRe[pos] = r; rawIm[pos] = i
        for (k in 0 until 4) {
            val c = cos(-ph[k]).toFloat(); val s = sin(-ph[k]).toFloat()
            val mr = r * c - i * s
            val mi = r * s + i * c
            sumRe[k] += mr - mixRe[k][pos]; sumIm[k] += mi - mixIm[k][pos]
            mixRe[k][pos] = mr; mixIm[k][pos] = mi
            ph[k] = Math.IEEEremainder(ph[k] + w[k], 2 * PI)
        }
        if (++pos == len) pos = 0
        if (++sinceExact >= 4 * len) {
            sinceExact = 0
            for (k in 0 until 4) { sumRe[k] = mixRe[k].sum().toDouble(); sumIm[k] = mixIm[k].sum().toDouble() }
        }
        for (k in 0 until 4) e[k] = (sumRe[k] * sumRe[k] + sumIm[k] * sumIm[k]).toFloat()
    }

    /** Variant bit 0: tones mirrored; bit 1: the two bits of a symbol swapped. */
    private fun bitOf(v: Int, bit: Long): Int {
        val si = (bit / 2 - base).toInt()
        if (si < 0 || si >= syms.size) return 0
        var s = syms[si]
        if (v and 1 != 0) s = 3 - s
        val first = if (v and 2 != 0) s and 1 else s shr 1
        val second = if (v and 2 != 0) s shr 1 else s and 1
        return if (bit and 1L == 1L) second else first
    }

    private fun tryDecode(p: Pend, bytes: Int): HorusPacket? {
        val payload = if (bytes == Horus.frameBytes(Horus.V1_BYTES) - 2) Horus.V1_BYTES else Horus.V2_BYTES
        for (lsb in 0..1) {
            val body = ByteArray(bytes)
            for (i in 0 until bytes * 8) if (bitOf(p.variant, p.start + i) == 1) {
                body[i shr 3] = (body[i shr 3].toInt() or (if (lsb == 1) 1 shl (i and 7) else 0x80 ushr (i and 7))).toByte()
            }
            val (pl, fx) = Horus.decodeFrame(body, payload)
            Horus.parse(pl, fx)?.let { return it }
        }
        return null
    }

    private fun symbol(sym: Int) {
        syms.addLast(sym)
        val need = (Horus.frameBytes(Horus.V2_BYTES) - 2) * 8L
        val v1Need = (Horus.frameBytes(Horus.V1_BYTES) - 2) * 8L
        for (b in 0..1) {
            val bit = (base + syms.size - 1) * 2 + b
            for (v in 0 until 4) {
                reg[v] = ((reg[v] shl 1) or bitOf(v, bit)) and 0xFFFFFF
                if (Integer.bitCount(reg[v] xor 0x1B2424) <= 3 && pend.none { it.start == bit + 1 }) pend += Pend(v, bit + 1)
            }
            nbits = bit + 1
        }
        var i = 0
        while (i < pend.size) {
            val p = pend[i]
            val have = nbits - p.start
            var done = false
            var decoded: HorusPacket? = null
            if (!p.triedV1 && have >= v1Need) {
                p.triedV1 = true
                decoded = tryDecode(p, v1Need.toInt() / 8)
                if (decoded != null) done = true
            }
            if (!done && have >= need) {
                decoded = tryDecode(p, need.toInt() / 8)
                done = true
            }
            if (done) {
                pend.removeAt(i)
                if (decoded != null) {
                    onPacket(decoded)
                    // The other sync matches inside a decoded frame were its own bits.
                    pend.removeAll { it.start > p.start - 24 && it.start < p.start + need }
                    i = 0
                }
            } else i++
        }
        while (syms.size > 1200) { syms.removeFirst(); base++ }
        if (pend.size > 64) repeat(32) { pend.removeAt(0) }
    }
}

/**
 * Horus balloons inside the radiosonde capture: 500 kS/s complex baseband centred on the tuned frequency in, down to
 * 10 kS/s, so a payload within about ±4 kHz of it is heard, as frames for the sonde tracker.
 */
class HorusReceiver(onFrame: (SondeFrame) -> Unit) {
    private val d1 = FloatDecimator(10, 0.045)
    private val d2 = FloatDecimator(5, 0.08, taps = 96)
    private val aRe = FloatArray(16384)
    private val aIm = FloatArray(16384)
    private val bRe = FloatArray(4096)
    private val bIm = FloatArray(4096)
    private val last = HashMap<Int, Pair<Double, Long>>()
    private val demod = HorusDemodulator(10_000.0) { p ->
        val now = System.currentTimeMillis()
        val prev = last[p.payloadId]
        last[p.payloadId] = p.altitudeM to now
        val climb = prev?.let { (a, t) -> if (now - t in 1_000..600_000) (p.altitudeM - a) / ((now - t) / 1000.0) else null }
        onFrame(SondeFrame("Horus", "ID ${p.payloadId}", p.seq, p.batteryV, p.lat, p.lon, p.altitudeM, p.speedKmh / 3.6, null, climb, p.sats))
    }

    fun feed(re: FloatArray, im: FloatArray, n: Int) {
        val k = d1.process(re, im, n, aRe, aIm)
        val m = d2.process(aRe, aIm, k, bRe, bIm)
        demod.feed(bRe, bIm, m)
    }

    companion object {
        /** The usual Horus frequencies. */
        const val PROJECT_HORUS_HZ = 434_660_000L
    }
}
