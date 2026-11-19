package com.allnetworktools.data.sdr

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Ubiquiti SuperLink, the sub-GHz link of UniFi Protect sensors, after the open reverse engineering in
 * github.com/alxgmpr/superlink (MIT): LoRa at SF5, 125 kHz uplinks, coding rate 4/5, explicit header, CRC on, private
 * sync word. Every frame opens with a clear 10-byte header: Mctrl, Dctrl (the message type), the sensor's MAC address
 * and a frame counter. What follows is encrypted (XSalsa20 with a session key) and stays so: the sensor is identified,
 * its readings are not.
 */
object SuperLink {
    const val SF = 5
    const val BW_HZ = 125_000.0

    /** The US uplinks, 915.6 to 917.0 MHz every 200 kHz (the gateway's channel plan). The EU plan is not published. */
    val US_UPLINKS_HZ = (0 until 8).map { 915_600_000L + 200_000L * it }

    /** Dctrl values seen on the air, with what they carry. */
    private val DCTRL = mapOf(
        0x40 to "discovery", 0x42 to "connection", 0x43 to "management", 0x44 to "setup", 0x53 to "management",
        0x54 to "data", 0x62 to "connection", 0x63 to "data", 0x74 to "setup",
    )

    class Frame(val dctrl: Int, val type: String, val mac: String, val counter: Int, val length: Int)

    /** A frame whose LoRa CRC passed; null unless it has SuperLink's header (a known Dctrl, a MAC address). */
    fun parse(p: ByteArray): Frame? {
        if (p.size < 14 || p.size > 96) return null
        val d = p[1].toInt() and 0xFF
        val type = DCTRL[d] ?: return null
        val mac = (2 until 8).joinToString(":") { "%02X".format(p[it].toInt() and 0xFF) }
        if (mac == "00:00:00:00:00:00" || mac == "FF:FF:FF:FF:FF:FF") return null
        return Frame(d, type, mac, p[8].toInt() and 0xFF, p.size)
    }

    fun reading(f: Frame, hz: Double) = IsmReading(
        model = "Ubiquiti SuperLink", kind = "Sensor", id = f.mac, mic = "CRC",
        channelName = String.format(Locale.getDefault(), "%.1f MHz", hz / 1e6),
        fields = listOf(
            IsmField("type", "", 0.0, 0, "${f.type} (0x%02X)".format(f.dctrl)),
            IsmField("counter", "", 0.0, 0, "#${f.counter}"),
        ),
    )
}

/**
 * Finds SuperLink frames anywhere in a wide capture, since the EU channel plan is unknown and the US one spans 1.4 MHz.
 *
 * Every symbol's worth of samples (512 at 2 MS/s) is dechirped with an SF5 upchirp swept over 125 kHz and transformed:
 * one bin per 3.9 kHz across the whole capture. A LoRa SF5 preamble repeats the same chirp, so it gives the same bin in
 * window after window; FSK sensors, carriers and LoRa at other spreading factors are smeared by the dechirp and do not.
 * The two tones a preamble gives (see [start]) place the transmitter; three narrow LoRa receivers 25 kHz apart start
 * around it (each takes ±31 kHz of offset), fed from a ring buffer back to before the preamble. The frame's CRC and
 * SuperLink's header decide.
 */
class SuperLinkScanner(
    private val rate: Double,
    private val tunedHz: Double,
    private val onFrame: (SuperLink.Frame, Double) -> Unit,
) {
    private val spc = (rate / SuperLink.BW_HZ).roundToInt()
    private val w = spc * (1 shl SuperLink.SF)
    private val fft = Fft(w)
    private val refRe = FloatArray(w)
    private val refIm = FloatArray(w)

    init {
        require(spc >= 2 && w and (w - 1) == 0) { "rate must be a power-of-two multiple of 125 kHz" }
        // The conjugate of the base upchirp: e^{-j2π(BW/2T·t² − BW/2·t)}.
        val t = w / rate
        for (i in 0 until w) {
            val s = i / rate
            val ph = 2 * PI * (SuperLink.BW_HZ / (2 * t) * s * s - SuperLink.BW_HZ / 2 * s)
            refRe[i] = cos(ph).toFloat(); refIm[i] = -sin(ph).toFloat()
        }
    }

    private val ring = 1 shl 18
    private val mask = ring - 1
    private val bufRe = FloatArray(ring)
    private val bufIm = FloatArray(ring)
    private var total = 0L
    private var nextWin = 0L

    private val wr = FloatArray(w)
    private val wi = FloatArray(w)
    private val pw = FloatArray(w)

    private class Track(var bin: Int, var count: Int, val firstPos: Long, var seen: Boolean)
    private val tracks = ArrayList<Track>()

    private inner class Session(val startPos: Long, binHz: Double) {
        var pos = startPos
        var done = false
        val chains = OFFSETS_HZ.map { binHz + it }.filter { abs(it) <= rate / 2 - 70_000 }.map { Chain(it) }
        fun run() {
            while (!done && pos < total) {
                val m = minOf(BLOCK.toLong(), total - pos).toInt()
                for (i in 0 until m) { val k = ((pos + i) and mask.toLong()).toInt(); blkRe[i] = bufRe[k]; blkIm[i] = bufIm[k] }
                pos += m
                for (c in chains) { c.run(m); if (done) break }
                if (pos - startPos > (rate * SESSION_S).toLong()) done = true
            }
        }
        inner class Chain(offHz: Double) {
            private val abs = tunedHz + offHz
            private val dec = FloatDecimator(spc / LoraReceiver.OS, 100_000.0 / rate, 128)
            private val sr = cos(-2 * PI * offHz / rate)
            private val sj = sin(-2 * PI * offHz / rate)
            private var pr = 1.0
            private var pi = 0.0
            private val rx = LoraReceiver(SuperLink.SF, SuperLink.BW_HZ, abs) { f ->
                if (!f.crcOk || done) return@LoraReceiver
                val sl = SuperLink.parse(f.payload) ?: return@LoraReceiver
                done = true
                // A receiver a whole bandwidth off can read the frame too (its samples alias): one report per frame.
                val key = f.payload.contentHashCode()
                if (recent.any { it.first == key && pos - it.second < rate.toLong() }) return@LoraReceiver
                recent.addLast(key to pos)
                while (recent.size > 16) recent.removeFirst()
                onFrame(sl, abs + f.cfoHz)
            }
            fun run(m: Int) {
                var p = pr; var q = pi
                for (i in 0 until m) {
                    val a = blkRe[i]; val b = blkIm[i]
                    mr[i] = (a * p - b * q).toFloat(); mi[i] = (a * q + b * p).toFloat()
                    val n = p * sr - q * sj; q = p * sj + q * sr; p = n
                }
                val g = 1 / hypot(p, q); pr = p * g; pi = q * g
                val k = dec.process(mr, mi, m, or, oi)
                rx.feed(or, oi, k)
            }
        }
    }

    private val blkRe = FloatArray(BLOCK)
    private val blkIm = FloatArray(BLOCK)
    private val mr = FloatArray(BLOCK)
    private val mi = FloatArray(BLOCK)
    private val or = FloatArray(BLOCK)
    private val oi = FloatArray(BLOCK)
    private val sessions = ArrayList<Pair<Session, Double>>()
    /** Frames reported lately (payload hash, where), so none is reported twice. */
    private val recent = ArrayDeque<Pair<Int, Long>>()

    /** Preambles found, for the tests. */
    @Volatile var preambles = 0; private set

    fun feed(re: FloatArray, im: FloatArray, n: Int) {
        var off = 0
        while (off < n) {
            val m = minOf(ring / 4, n - off)
            for (i in 0 until m) { val k = ((total + i) and mask.toLong()).toInt(); bufRe[k] = re[off + i]; bufIm[k] = im[off + i] }
            total += m
            off += m
            while (nextWin + w <= total) { scan(nextWin); nextWin += w }
            for ((s, _) in sessions) s.run()
            sessions.removeAll { it.first.done }
        }
    }

    /** HackRF bytes: interleaved signed 8-bit I/Q. */
    fun feedBytes(buf: ByteArray, len: Int) {
        val n = len / 2
        if (byteRe.size < n) { byteRe = FloatArray(n); byteIm = FloatArray(n) }
        for (i in 0 until n) { byteRe[i] = buf[2 * i].toFloat(); byteIm[i] = buf[2 * i + 1].toFloat() }
        feed(byteRe, byteIm, n)
    }
    private var byteRe = FloatArray(0)
    private var byteIm = FloatArray(0)

    private fun scan(at: Long) {
        for (i in 0 until w) {
            val k = ((at + i) and mask.toLong()).toInt()
            val a = bufRe[k]; val b = bufIm[k]
            wr[i] = a * refRe[i] - b * refIm[i]
            wi[i] = a * refIm[i] + b * refRe[i]
        }
        fft.forward(wr, wi)
        var sum = 0.0
        for (k in 0 until w) { pw[k] = wr[k] * wr[k] + wi[k] * wi[k]; sum += pw[k] }
        val thr = (sum / w * DETECT_RATIO).toFloat()
        for (t in tracks) t.seen = false
        // The strongest few local peaks over the threshold.
        var found = 0
        val used = BooleanArray(w)
        while (found < MAX_PEAKS) {
            var best = -1
            var bp = thr
            for (k in 0 until w) if (!used[k] && pw[k] > bp) { bp = pw[k]; best = k }
            if (best < 0) break
            for (d in -2..2) used[(best + d + w) % w] = true
            found++
            val tr = tracks.firstOrNull { !it.seen && circ(it.bin, best) <= 1 }
            if (tr == null) { tracks += Track(best, 1, at, true); continue }
            tr.bin = best; tr.count++; tr.seen = true
            if (tr.count == PREAMBLE_WINDOWS) start(tr)
        }
        tracks.removeAll { !it.seen }
    }

    private fun circ(a: Int, b: Int): Int { val d = Math.floorMod(a - b, w); return minOf(d, w - d) }

    /**
     * A window that cuts the preamble's chirps τ into one gives two tones a bandwidth apart: the rest of that chirp at
     * f0 + BW·τ/T, the start of the next at f0 + BW·τ/T − BW, their amplitudes in proportion to how long each lasts.
     * The pair gives f0: τ/T is the lower tone's share of the two amplitudes.
     */
    private fun start(tr: Track) {
        val span = (w * SuperLink.BW_HZ / rate).roundToInt() // bins per 125 kHz
        fun amp(b: Int): Double = kotlin.math.sqrt((-1..1).maxOf { pw[Math.floorMod(b + it, w)].toDouble() })
        val up = amp(tr.bin + span) > amp(tr.bin - span)
        val upper = if (up) tr.bin + span else tr.bin
        val lower = upper - span
        val share = amp(lower) / (amp(lower) + amp(upper))
        val ub = Math.floorMod(upper, w).let { if (it >= w / 2) it - w else it }
        val hz = ub * rate / w - SuperLink.BW_HZ * share
        if (sessions.any { abs(it.second - hz) < 150_000 } || sessions.size >= MAX_SESSIONS) return
        preambles++
        val from = maxOf(tr.firstPos - 2L * w, total - ring + BLOCK)
        sessions += Session(from, hz) to hz
    }

    companion object {
        /** Peak over the mean of all bins: 10.8 dB. A preamble still clears it a little under 0 dB SNR in its channel. */
        private const val DETECT_RATIO = 12.0
        private const val MAX_PEAKS = 4
        private const val PREAMBLE_WINDOWS = 4
        private const val MAX_SESSIONS = 4
        private const val BLOCK = 8192
        /** Long enough for SuperLink's longest frame (76 bytes, about 50 ms) from before its preamble. */
        private const val SESSION_S = 0.085
        /** Around the estimate, which noise can move: each receiver takes ±31 kHz. */
        private val OFFSETS_HZ = listOf(-25_000.0, 0.0, 25_000.0)
    }
}

/**
 * TP-Link Tapo sub-GHz sensors (T100, T110, T310, S200…, through an H100 / H200 hub): a proprietary GFSK link at
 * 50 kbit/s on 863.35, 864.35 and 868.35 MHz in Europe (the S200B's EU test report) and 920.9 to 922.3 MHz in the US
 * (FCC grants). Its frame format is not public, so a Tapo is recognised by its signature, not decoded: an FSK burst on
 * one of those channels, with an alternating preamble and every element a whole number of 20 µs bits.
 */
object Tapo {
    val CHANNELS_HZ = listOf(863_350_000.0, 864_350_000.0, 868_350_000.0, 920_900_000.0, 921_700_000.0, 922_300_000.0)
    private const val BIT_US = 20.0

    fun match(t: IsmTrain, hz: Double): IsmReading? {
        if (!t.fsk || t.p.size < 8) return null
        val ch = CHANNELS_HZ.indexOfFirst { abs(it - hz) <= 30_000 }
        if (ch < 0) return null
        val d = ArrayList<Float>(2 * t.p.size)
        for (q in t.p) { d += q.pulseUs; d += q.gapUs }
        d.removeAt(d.lastIndex)
        // The preamble: single bits, after a first element the squelch may have cut.
        var pre = 1
        while (pre < d.size && d[pre] in 14f..26f) pre++
        if (pre - 1 < 12) return null
        val unit = d.subList(1, pre).average()
        if (unit !in BIT_US * 0.94..BIT_US * 1.06) return null
        var bits = 0
        var fit = 0
        for (x in d) {
            val k = (x / unit).roundToInt()
            if (k >= 1 && abs(x - k * unit) <= 0.3 * unit) fit++
            bits += maxOf(1, k)
        }
        if (fit < d.size * 0.9 || bits < 80) return null
        return IsmReading(
            model = "TP-Link Tapo", kind = "Sensor", id = "—", mic = "signature", channel = ch + 1,
            channelName = String.format(Locale.getDefault(), "%.2f MHz", CHANNELS_HZ[ch] / 1e6),
            fields = listOf(
                IsmField("rate", "", 0.0, 0, String.format(Locale.getDefault(), "GFSK %.0f kbit/s", 1000 / unit)),
                IsmField("bits", "", 0.0, 0, "$bits bits"),
            ),
        )
    }
}
