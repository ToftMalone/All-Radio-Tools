package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin

/**
 * LoRaWAN (LoRa Alliance TS001): what a device's uplink says in the clear around its encrypted payload: message type,
 * the device address (which names the network), frame counter, port; and for a join request the device and join EUIs.
 */
object LoraWan {
    enum class Type { JoinRequest, JoinAccept, UnconfirmedUp, UnconfirmedDown, ConfirmedUp, ConfirmedDown, Rejoin, Proprietary }

    data class Frame(
        val type: Type,
        /** Device address (data frames), as usually written: most significant byte first. */
        val devAddr: Int?,
        val fCnt: Int?,
        val fPort: Int?,
        val payloadLen: Int,
        val adr: Boolean,
        val ack: Boolean,
        val devEui: String?,
        val joinEui: String?,
    ) {
        val uplink get() = type == Type.JoinRequest || type == Type.UnconfirmedUp || type == Type.ConfirmedUp || type == Type.Rejoin
    }

    private fun le(b: ByteArray, o: Int, n: Int): Long { var v = 0L; for (i in n - 1 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF); return v }

    /** Null when the frame cannot be LoRaWAN (wrong major version, too short for its type). */
    fun parse(p: ByteArray): Frame? {
        if (p.size < 12) return null
        val mhdr = p[0].toInt() and 0xFF
        if (mhdr and 3 != 0) return null
        val type = Type.entries[mhdr shr 5]
        return when (type) {
            Type.JoinRequest -> if (p.size != 23) null else Frame(type, null, null, null, 0, false, false, "%016X".format(le(p, 9, 8)), "%016X".format(le(p, 1, 8)))
            Type.JoinAccept, Type.Rejoin, Type.Proprietary -> Frame(type, null, null, null, p.size - 5, false, false, null, null)
            else -> {
                val fctrl = p[5].toInt() and 0xFF
                val foptsLen = fctrl and 15
                val rest = p.size - 8 - foptsLen - 4
                if (rest < 0) return null
                val port = if (rest > 0) p[8 + foptsLen].toInt() and 0xFF else null
                Frame(type, le(p, 1, 4).toInt(), le(p, 6, 2).toInt(), port, maxOf(0, rest - 1), fctrl and 0x80 != 0, fctrl and 0x20 != 0, null, null)
            }
        }
    }

    /** The network that gave out a device address, for the type 0 and 1 NetIDs the big public networks use. */
    fun network(devAddr: Int): String? {
        val top = devAddr ushr 25
        if (devAddr ushr 31 != 0) return null
        return when (top) {
            0x13 -> "The Things Network"
            0x0F -> "Orange"
            0x24 -> "Helium"
            0x0B -> "Bouygues / Objenious"
            else -> null
        }
    }

    /** The NwkID of a type 0 address (6 bits), shown when the network is not known. */
    fun nwkId(devAddr: Int) = if (devAddr ushr 31 == 0) (devAddr ushr 25) and 0x3F else null
}

/**
 * Sigfox uplink frames (EP-SPECS Rev. 1.3, the public radio specification): 19 preamble bits, a 13-bit frame type that
 * gives the container size and the frame's rank among three repetitions, then the container (length indicator,
 * message counter, device ID, payload, authentication) and a CRC, convolution-coded by rank. The authentication needs
 * the device's secret key and is never checked. Ported from OnAir (core/src/sigfox, GPL-3.0).
 */
object Sigfox {
    const val PREAMBLE = 19
    const val FT_BITS = 13

    class FrameType(val ft: Int, val container: Int, val rank: Int, val control: Boolean)
    val types = listOf(
        FrameType(0x006B, 8, 1, false), FrameType(0x06E0, 8, 2, false), FrameType(0x0034, 8, 3, false),
        FrameType(0x008D, 9, 1, false), FrameType(0x00D2, 9, 2, false), FrameType(0x0302, 9, 3, false),
        FrameType(0x035F, 12, 1, false), FrameType(0x0598, 12, 2, false), FrameType(0x05A3, 12, 3, false),
        FrameType(0x0611, 16, 1, false), FrameType(0x06BF, 16, 2, false), FrameType(0x072C, 16, 3, false),
        FrameType(0x094C, 20, 1, false), FrameType(0x0971, 20, 2, false), FrameType(0x0997, 20, 3, false),
        FrameType(0x0F67, 16, 1, true), FrameType(0x0FC9, 16, 2, true), FrameType(0x11BE, 16, 3, true),
    )

    fun typeOf(ft: Int, maxErrors: Int): FrameType? {
        val d = types.map { Integer.bitCount((it.ft xor ft) and 0x1FFF) }
        val best = d.min()
        if (best > maxErrors || d.count { it == best } > 1) return null
        return types[d.indexOf(best)]
    }

    /** Payload bytes for a container and length indicator; -1 when invalid; -2 for a one-bit message. */
    fun payloadBytes(container: Int, li: Int) = when (container) {
        8 -> if (li == 0) 0 else if (li >= 2) -2 else -1
        9 -> if (li == 0) 1 else -1
        12 -> when (li) { 2 -> 2; 1 -> 3; 0 -> 4; else -> -1 }
        16 -> 8 - li
        20 -> 12 - li
        else -> -1
    }

    fun crc16(b: IntArray): Int {
        var r = 0
        for (v in b) { r = r xor (v shl 8); repeat(8) { r = if (r and 0x8000 != 0) (r shl 1) xor 0x1021 else r shl 1 }; r = r and 0xFFFF }
        return r xor 0xFFFF
    }

    fun convEncode(bits: IntArray, rank: Int): IntArray {
        if (rank <= 1) return bits
        var s1 = 0; var s2 = 0
        return IntArray(bits.size) { i -> val o = (bits[i] xor (if (rank == 2) s1 else 0) xor s2) and 1; s2 = s1; s1 = bits[i]; o }
    }

    fun convDecode(bits: IntArray, rank: Int): IntArray {
        if (rank <= 1) return bits
        var s1 = 0; var s2 = 0
        return IntArray(bits.size) { i -> val o = (bits[i] xor (if (rank == 2) s1 else 0) xor s2) and 1; s2 = s1; s1 = o; o }
    }

    data class Uplink(val id: Int, val counter: Int, val payload: ByteArray, val singleBit: Boolean, val bitValue: Boolean, val downlinkRequest: Boolean, val rank: Int, val control: Boolean) {
        val idHex get() = "%08X".format(id)
    }

    /** Bits of a frame (tests): made-up authentication bytes, since real ones need the device key. */
    fun buildFrame(id: Int, counter: Int, payload: ByteArray, rank: Int, downlinkRequest: Boolean = false): IntArray {
        val n = payload.size
        var container = 0
        var li = 0
        loop@ for (c in intArrayOf(8, 9, 12, 16, 20)) for (l in 0 until 4) if (payloadBytes(c, l) == n) { container = c; li = l; break@loop }
        val c = ArrayList<Int>()
        c += (li shl 6) or ((if (downlinkRequest) 1 else 0) shl 5) or ((counter shr 8) and 15)
        c += counter and 0xFF
        for (k in 0 until 4) c += (id ushr (8 * k)) and 0xFF
        payload.forEach { c += it.toInt() and 0xFF }
        while (c.size < container) c += (c.size * 73 + id) and 0xFF
        val crc = crc16(c.toIntArray())
        val content = ArrayList<Int>()
        for (v in c) for (i in 7 downTo 0) content += (v shr i) and 1
        for (i in 15 downTo 0) content += (crc shr i) and 1
        val ft = types.first { it.container == container && it.rank == rank && !it.control }.ft
        return IntArray(PREAMBLE) { if (it % 2 == 0) 1 else 0 } + IntArray(FT_BITS) { (ft shr (FT_BITS - 1 - it)) and 1 } + convEncode(content.toIntArray(), rank)
    }

    /** Reads a frame whose frame type starts at [pos]; null unless the CRC matches. */
    fun parse(bits: IntArray, pos: Int): Uplink? {
        if (pos + FT_BITS > bits.size) return null
        var ft = 0
        for (i in 0 until FT_BITS) ft = (ft shl 1) or bits[pos + i]
        val f = typeOf(ft, 1) ?: return null
        val n = 8 * f.container + 16
        if (pos + FT_BITS + n > bits.size) return null
        val d = convDecode(bits.copyOfRange(pos + FT_BITS, pos + FT_BITS + n), f.rank)
        val c = IntArray(f.container) { i -> var v = 0; for (k in 0 until 8) v = (v shl 1) or d[8 * i + k]; v }
        var crc = 0
        for (k in 0 until 16) crc = (crc shl 1) or d[8 * f.container + k]
        if (crc16(c) != crc) return null
        val li = c[0] shr 6
        val p = payloadBytes(f.container, li)
        if (p == -1) return null
        val id = c[2] or (c[3] shl 8) or (c[4] shl 16) or (c[5] shl 24)
        val payload = if (p > 0) ByteArray(p) { c[6 + it].toByte() } else ByteArray(0)
        return Uplink(id, ((c[0] and 15) shl 8) or c[1], payload, p == -2, p == -2 && li and 1 == 1, (c[0] shr 5) and 1 == 1, f.rank, f.control)
    }
}

/**
 * Sigfox ultra-narrowband uplinks, 100 bit/s D-BPSK anywhere in 192 kHz: a spectrogram finds narrow carriers that last
 * a fraction of a second to a few seconds, and each one is then demodulated on its own: mixed down, the fine frequency
 * from the squared signal, symbol timing from the strongest sums, differential bits; both bit polarities and a few
 * starts are tried, and the frame type and CRC decide.
 */
class SigfoxReceiver(private val fs: Double, private val onUplink: (Sigfox.Uplink, Double) -> Unit) {
    private val ring = FloatArray(2 * (3.8 * fs).toInt())
    private val ringN = ring.size / 2
    private var total = 0L
    private val nfft: Int = run { var n = 256; while (n < 65536 && fs / (n * 2) >= 80.0) n *= 2; n }
    private val hop = nfft / 2
    private val binHz = fs / nfft
    private val fft = Fft(nfft)
    private val win = FloatArray(nfft) { (0.5 - 0.5 * cos(2 * PI * (it + 0.5) / nfft)).toFloat() }
    private val fr = FloatArray(nfft)
    private val fi = FloatArray(nfft)
    private val half = (98_000.0 / binHz).toInt()
    private val nb = 2 * half + 1
    private val hist = Array(AVG) { FloatArray(nb) }
    private val sum = FloatArray(nb)
    private val e = FloatArray(nb)
    private var hpos = 0
    private var frames = 0L

    private class Track(var f: Double, val start: Long, var last: Long, var peak: Float)
    private val tracks = ArrayList<Track>()

    var carriers = 0
        private set

    fun feed(re: FloatArray, im: FloatArray, n: Int) {
        for (i in 0 until n) {
            val k = (total % ringN).toInt()
            ring[2 * k] = re[i]; ring[2 * k + 1] = im[i]
            total++
            if (total % hop == 0L && total >= nfft) frame()
        }
    }

    private fun sec(samples: Long) = samples / fs

    private fun frame() {
        val base = total - nfft
        for (i in 0 until nfft) { val k = ((base + i) % ringN).toInt(); fr[i] = ring[2 * k] * win[i]; fi[i] = ring[2 * k + 1] * win[i] }
        fft.forward(fr, fi)
        val h = hist[hpos]
        for (j in 0 until nb) {
            val k = (j - half + nfft) % nfft
            val p = fr[k] * fr[k] + fi[k] * fi[k]
            sum[j] += p - h[j]; h[j] = p
        }
        hpos = (hpos + 1) % AVG
        if (++frames < AVG + 1) return
        for (j in 0 until nb) e[j] = maxOf(0f, sum[j]) / AVG
        val noise = maxOf(1e-20f, e.sorted()[nb / 2])
        val thr = THR * noise
        val hit = BooleanArray(tracks.size)
        val peaks = (1 until nb - 1).filter { j -> e[j] > thr && e[j] >= e[j - 1] && e[j] >= e[j + 1] }
        val near = (8_000 / binHz).toInt()
        for (j in peaks) {
            // A strong burst's side lobes, 10 dB or more under it a few kHz away, are not carriers.
            if (peaks.any { o -> o != j && abs(o - j) <= near && e[o] > 10 * e[j] }) continue
            val a = ln(e[j - 1] + 1e-30); val b = ln(e[j] + 1e-30); val c = ln(e[j + 1] + 1e-30)
            val den = a - 2 * b + c
            val d = if (den < -1e-9) 0.5 * (a - c) / den else 0.0
            val f = (j - half + d.coerceIn(-1.0, 1.0)) * binHz
            val ti = tracks.indexOfFirst { abs(it.f - f) <= 350 }
            if (ti < 0) {
                tracks += Track(f, maxOf(0L, total - (nfft + (AVG + 2) * hop)), total, e[j])
            } else {
                val t = tracks[ti]
                if (e[j] >= 0.7f * t.peak) t.f = 0.5 * t.f + 0.5 * f
                t.peak = maxOf(t.peak * 0.995f, e[j]); t.last = total
                if (ti < hit.size) hit[ti] = true
            }
        }
        val it = tracks.iterator()
        while (it.hasNext()) {
            val t = it.next()
            val dur = sec(t.last - t.start)
            val gap = if (dur >= 0.3) maxOf(4.0 * hop, 0.15 * fs) else 4.0 * hop
            if (total - t.last > gap) {
                if (dur in 0.1..3.2) finish(t)
                it.remove()
            } else if (dur > 3.2) it.remove()
        }
    }

    private fun finish(t: Track) {
        carriers++
        val pad = (0.06 * fs).toLong()
        var s0 = maxOf(0L, t.start - pad)
        val s1 = minOf(total, t.last + pad)
        if (total > ringN && s0 < total - ringN) s0 = total - ringN
        if (s1 <= s0 + 1000) return
        val l = (s1 - s0).toInt()
        val sr = FloatArray(l)
        val si = FloatArray(l)
        for (i in 0 until l) { val k = ((s0 + i) % ringN).toInt(); sr[i] = ring[2 * k]; si[i] = ring[2 * k + 1] }
        val (up, f) = demod(sr, si, t.f) ?: return
        // A strong burst's side lobes stand out as carriers of their own: one transmission is reported once.
        val now = sec(t.start)
        recent.removeAll { now - it.third > 5 }
        if (recent.any { it.first.id == up.id && it.first.counter == up.counter && it.first.payload.contentEquals(up.payload) && abs(it.second - f) < 8_000 && abs(now - it.third) < 1.0 }) return
        recent += Triple(up, f, now)
        onUplink(up, f)
    }

    private val recent = ArrayList<Triple<Sigfox.Uplink, Double, Double>>()

    /** One carrier at [f0] Hz: the uplink and the carrier's frequency, or null. */
    fun demod(sr: FloatArray, si: FloatArray, f0: Double): Pair<Sigfox.Uplink, Double>? {
        val baud = 100
        val l = sr.size
        val r1 = 20.0 * baud
        val step = fs / r1
        val nOut = (l / step).toInt()
        if (nOut < 20 * 40) return null
        // Mixed down, then a cumulative sum to average 20 samples a symbol with fractional edges.
        val cr = DoubleArray(l + 1)
        val ci = DoubleArray(l + 1)
        var or = 1.0; var oi = 0.0
        val wr = cos(-2 * PI * f0 / fs); val wi = sin(-2 * PI * f0 / fs)
        for (i in 0 until l) {
            val nr = or * wr - oi * wi; oi = or * wi + oi * wr; or = nr
            if (i and 1023 == 1023) { val g = 1 / hypot(or, oi); or *= g; oi *= g }
            cr[i + 1] = cr[i] + sr[i] * or - si[i] * oi
            ci[i + 1] = ci[i] + sr[i] * oi + si[i] * or
        }
        fun at(c: DoubleArray, tt: Double): Double { val i = minOf(l - 1, tt.toInt()); return c[i] + (c[i + 1] - c[i]) * (tt - i) }
        val yr = DoubleArray(nOut) { (at(cr, (it + 1) * step) - at(cr, it * step)) / step }
        val yi = DoubleArray(nOut) { (at(ci, (it + 1) * step) - at(ci, it * step)) / step }
        // Fine frequency: the square of BPSK is a line at twice the carrier.
        var nf = 4096
        while (nf < nOut * 4) nf *= 2
        val f = Fft(nf)
        val zr = FloatArray(nf)
        val zi = FloatArray(nf)
        for (k in 0 until nOut) {
            val w = 0.5 - 0.5 * cos(2 * PI * (k + 0.5) / nOut)
            zr[k] = ((yr[k] * yr[k] - yi[k] * yi[k]) * w).toFloat(); zi[k] = (2 * yr[k] * yi[k] * w).toFloat()
        }
        f.forward(zr, zi)
        val df = r1 / nf
        val kMax = (0.24 * r1 / df).toInt()
        val mag = FloatArray(2 * kMax + 1) { val j = (it - kMax + nf) % nf; zr[j] * zr[j] + zi[j] * zi[j] }
        val kb = mag.indices.maxBy { mag[it] }
        var frq = 0.0
        if (mag[kb] > 25f * mag.sorted()[mag.size / 2] && kb > 0 && kb < mag.size - 1) {
            val a = ln(mag[kb - 1] + 1e-30); val b = ln(mag[kb] + 1e-30); val c = ln(mag[kb + 1] + 1e-30)
            val den = a - 2 * b + c
            val d = if (den < -1e-9) 0.5 * (a - c) / den else 0.0
            frq = (kb - kMax + d.coerceIn(-1.0, 1.0)) * df / 2
        }
        for (k in 0 until nOut) {
            val ph = -2 * PI * frq * k / r1
            val c = cos(ph); val s = sin(ph)
            val nr = yr[k] * c - yi[k] * s; yi[k] = yr[k] * s + yi[k] * c; yr[k] = nr
        }
        // Symbol timing: the alignment whose symbol sums are strongest.
        val sps = 20
        val bp = (0 until sps).maxBy { p ->
            var m = 0.0
            var k = p
            while (k + sps <= nOut) { var a = 0.0; var b = 0.0; for (i in 0 until sps) { a += yr[k + i]; b += yi[k + i] }; m += a * a + b * b; k += sps }
            m
        }
        val sR = ArrayList<Double>(); val sI = ArrayList<Double>()
        run { var k = bp; while (k + sps <= nOut) { var a = 0.0; var b = 0.0; for (i in 0 until sps) { a += yr[k + i]; b += yi[k + i] }; sR += a / sps; sI += b / sps; k += sps } }
        val ns = sR.size
        if (ns < 40) return null
        val pw = DoubleArray(ns) { sR[it] * sR[it] + sI[it] * sI[it] }
        val gate = 0.3 * pw.sorted()[ns * 6 / 10]
        val n0 = (0 until ns - 1).firstOrNull { pw[it] > gate && pw[it + 1] > gate } ?: return null
        val n1 = (ns - 1 downTo 1).firstOrNull { pw[it] > gate && pw[it - 1] > gate } ?: return null
        if (n1 <= n0 + Sigfox.PREAMBLE) return null
        // Differential products, turned back by the local drift (from their squares averaged over 31 symbols).
        val dR = DoubleArray(ns - 1) { sR[it + 1] * sR[it] + sI[it + 1] * sI[it] }
        val dI = DoubleArray(ns - 1) { sI[it + 1] * sR[it] - sR[it + 1] * sI[it] }
        val c2r = DoubleArray(ns); val c2i = DoubleArray(ns)
        for (i in 0 until ns - 1) {
            val m = hypot(dR[i], dI[i]) + 1e-30
            c2r[i + 1] = c2r[i] + (dR[i] * dR[i] - dI[i] * dI[i]) / m
            c2i[i + 1] = c2i[i] + 2 * dR[i] * dI[i] / m
        }
        val bits = IntArray(ns - 1) { i ->
            val lo = maxOf(0, i - 15); val hi = minOf(ns - 1, i + 16)
            val turn = 0.5 * atan2(c2i[hi] - c2i[lo], c2r[hi] - c2r[lo])
            if (dR[i] * cos(-turn) - dI[i] * sin(-turn) > 0) 1 else 0
        }
        for (pol in 0..1) {
            val bb = if (pol == 0) bits else IntArray(bits.size) { bits[it] xor 1 }
            for (sh in -2..5) {
                val st = n0 + sh
                if (st < 0 || st + Sigfox.PREAMBLE > bb.size) continue
                if ((0 until Sigfox.PREAMBLE).count { bb[st + it] == (if (it % 2 == 0) 1 else 0) } < 17) continue
                Sigfox.parse(bb, st + Sigfox.PREAMBLE)?.let { return it to f0 + frq }
            }
        }
        return null
    }

    companion object {
        private const val AVG = 8
        private const val THR = 3.1f
    }
}

/** What the 868 MHz band gave: a LoRa frame on a channel, or a Sigfox uplink. */
sealed class LpwanEvent {
    class Lora(val hz: Long, val sf: Int, val frame: LoraFrame, val lorawan: LoraWan.Frame?) : LpwanEvent()
    class SigfoxUp(val hz: Double, val up: Sigfox.Uplink) : LpwanEvent()
}

/**
 * HackRF bytes at 2 MS/s, 868.3 MHz at +fs/4 → 1 MS/s centred on 868.3 MHz. The three LoRaWAN channels every EU868
 * device uses (868.1, 868.3, 868.5 MHz, 125 kHz) go to LoRa receivers for SF7 to SF12; the Sigfox macro channel
 * (868.034 to 868.226 MHz) to the Sigfox receiver at 200 kS/s.
 */
class LpwanReceiver(private val onEvent: (LpwanEvent) -> Unit) {
    private val d1 = Decimator(2, 450_000.0 / 2_000_000.0, 32)
    private val re = FloatArray(65536 + 8)
    private val im = FloatArray(re.size)

    private inner class Mixer(offHz: Double, decim: Int, cutoffHz: Double, taps: Int) {
        val dec = FloatDecimator(decim, cutoffHz / 1_000_000.0, taps)
        private val sr = cos(-2 * PI * offHz / 1_000_000.0)
        private val sj = sin(-2 * PI * offHz / 1_000_000.0)
        private var pr = 1.0
        private var pi = 0.0
        val mr = FloatArray(re.size)
        val mi = FloatArray(re.size)
        val or = FloatArray(re.size)
        val oi = FloatArray(re.size)
        fun run(k: Int): Int {
            var p = pr; var q = pi
            for (i in 0 until k) {
                mr[i] = (re[i] * p - im[i] * q).toFloat(); mi[i] = (re[i] * q + im[i] * p).toFloat()
                val n = p * sr - q * sj; q = p * sj + q * sr; p = n
            }
            val g = 1 / hypot(p, q); pr = p * g; pi = q * g
            return dec.process(mr, mi, k, or, oi)
        }
    }

    private inner class Channel(val hz: Long) {
        val mixer = Mixer((hz - CENTRE_HZ).toDouble(), 4, 70_000.0, 96) // steep: the next channel, 200 kHz away, must not fold in
        val rx = (7..12).map { sf -> LoraReceiver(sf, 125_000.0, hz.toDouble()) { f -> onEvent(LpwanEvent.Lora(hz, sf, f, if (f.crcOk) LoraWan.parse(f.payload) else null)) } }
    }

    private val channels = LORAWAN_HZ.map { Channel(it) }
    private val sigMixer = Mixer((SIGFOX_HZ - CENTRE_HZ).toDouble(), 5, 100_000.0, 48)
    val sigfox = SigfoxReceiver(200_000.0) { up, f -> onEvent(LpwanEvent.SigfoxUp(SIGFOX_HZ + f, up)) }
    val lora get() = channels.flatMap { it.rx }

    fun feed(buf: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val m = minOf(2 * 2 * 65536, len - off)
            val k = d1.process(buf.copyOfRange(off, off + m), m, re, im, 0)
            feedFloat(k)
            off += m
        }
    }

    private fun feedFloat(k: Int) {
        for (c in channels) {
            val n = c.mixer.run(k)
            for (r in c.rx) r.feed(c.mixer.or, c.mixer.oi, n)
        }
        val n = sigMixer.run(k)
        sigfox.feed(sigMixer.or, sigMixer.oi, n)
    }

    /** Samples at 1 MS/s centred on 868.3 MHz (tests). */
    fun feedComplex(r: FloatArray, i: FloatArray, n: Int) {
        var off = 0
        while (off < n) {
            val m = minOf(65536, n - off)
            System.arraycopy(r, off, re, 0, m); System.arraycopy(i, off, im, 0, m)
            feedFloat(m)
            off += m
        }
    }

    companion object {
        const val SAMPLE_RATE = 2_000_000
        const val CENTRE_HZ = 868_300_000L
        val LORAWAN_HZ = listOf(868_100_000L, 868_300_000L, 868_500_000L)
        const val SIGFOX_HZ = 868_130_000.0
    }
}
