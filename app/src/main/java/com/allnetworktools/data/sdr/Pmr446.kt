package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * PMR446, the licence-free walkie-talkies: 16 analogue FM channels 12.5 kHz apart from 446.00625 MHz, and in the upper
 * half the digital ones, DMR446 (on the same centres) and dPMR446 (6.25 kHz, from 446.103125 MHz). The whole band is
 * 200 kHz wide, so one tuning hears every channel at once.
 */
object Pmr446 {
    const val CENTRE_HZ = 446_100_000L
    fun analogueHz(ch: Int) = 446_006_250L + 12_500L * (ch - 1)
    fun dpmrHz(ch: Int) = 446_103_125L + 6_250L * (ch - 1)

    /** The 38 CTCSS tones of the usual walkie-talkie codes 1 to 38. */
    val ctcss = doubleArrayOf(
        67.0, 71.9, 74.4, 77.0, 79.7, 82.5, 85.4, 88.5, 91.5, 94.8, 97.4, 100.0, 103.5, 107.2, 110.9, 114.8, 118.8, 123.0, 127.3,
        131.8, 136.5, 141.3, 146.2, 151.4, 156.7, 162.2, 167.9, 173.8, 179.9, 186.2, 192.8, 203.5, 210.7, 218.1, 225.7, 233.6, 241.8, 250.3,
    )
}

/**
 * 4FSK from the FM discriminator (Hz) at [rate]: every sample, the last [syncs] lengths of symbols are correlated with
 * each sync pattern, mean and scale removed, so a frequency error and the deviation need no setting. At a peak the
 * levels are fitted on the sync, and the frame's symbols from [before] symbols ahead of it to [after] past it are read,
 * normalised to ±1/±3.
 */
class FourFskSync(
    private val rate: Double,
    symRate: Double,
    private val syncs: List<IntArray>,
    private val before: Int,
    private val after: IntArray,
    private val threshold: Float = 0.86f,
    private val onFrame: (FourFskSync.Frame) -> Unit,
) {
    /** A frame read: its sync (or the tag of a scheduled read), symbols normalised to ±1/±3, where symbol 0 lies, levels. */
    class Frame(val sync: Int, val symbols: FloatArray, val first: Double, val a: Double, val b: Double)

    private class Read(val tag: Int, var first: Double, val n: Int, var a: Double, var b: Double, val refine: IntArray?)
    private val reads = ArrayList<Read>()
    /** Called with each scheduled read once its samples are in. */
    var onRead: ((Frame) -> Unit)? = null

    /**
     * Reads [n] symbols from sample [first] with the levels [a], [b] once they have arrived. With [refine], the symbols
     * the read opens with are known: the timing is moved by up to a symbol and a half to fit them best, and the levels
     * fitted on them, so long calls do not drift off the symbol clock.
     */
    fun schedule(tag: Int, first: Double, n: Int, a: Double, b: Double, refine: IntArray? = null) { reads += Read(tag, first, n, a, b, refine) }

    fun cancelReads() = reads.clear()

    val samplesPerSymbol get() = sps

    private val sps = rate / symRate
    private val len = syncs.first().size.also { l -> require(syncs.all { it.size == l }) }
    private val xs = DoubleArray(len)
    private val ring = FloatArray(RING)
    private var n = 0L
    private val tm = syncs.map { t -> t.average() }
    private val tn = syncs.mapIndexed { i, t -> sqrt(t.sumOf { (it - tm[i]) * (it - tm[i]) }) }
    private var peakOn = false
    private var peakRho = 0f
    private var peakAt = 0L
    private var peakSync = 0
    private val pending = ArrayDeque<Triple<Int, Long, Pair<Double, Double>>>()
    private val box = FloatArray(maxOf(1, (sps / 2).roundToInt()))
    private var boxPos = 0
    private var boxSum = 0f

    fun push(hz: Float) {
        boxSum += hz - box[boxPos]; box[boxPos] = hz; boxPos = (boxPos + 1) % box.size
        ring[(n and MASK).toInt()] = boxSum / box.size
        n++
        if (n < 2048) return
        // The samples one symbol apart that end here, gathered once: every pattern has the same length.
        for (k in 0 until len) xs[k] = at(n - 1 - (len - 1 - k) * sps).toDouble()
        var sx = 0.0
        var sxx = 0.0
        for (k in 0 until len) { sx += xs[k]; sxx += xs[k] * xs[k] }
        val den = sxx - sx * sx / len
        var best = 0f
        var bs = 0
        if (den > 0) for (s in syncs.indices) {
            val tt = syncs[s]
            var sxt = 0.0
            for (k in 0 until len) sxt += xs[k] * tt[k]
            val rho = ((sxt - sx * tm[s]) / (sqrt(den) * tn[s])).toFloat()
            if (rho > best) { best = rho; bs = s }
        }
        if (best >= threshold && (!peakOn || best > peakRho)) { peakRho = best; peakAt = n - 1; peakSync = bs; peakOn = true }
        if (peakOn && n - 1 > peakAt + sps / 2) {
            peakOn = false
            // Levels: least squares of the samples on the sync's symbol values.
            val t = syncs[peakSync]
            val l = t.size
            var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
            for (k in 0 until l) { val y = at(peakAt - ((l - 1 - k) * sps)); val x = t[k].toDouble(); sx += x; sy += y; sxx += x * x; sxy += x * y }
            val a = (l * sxy - sx * sy) / (l * sxx - sx * sx)
            val b = (sy - a * sx) / l
            if (a > 0) pending.addLast(Triple(peakSync, peakAt, a to b))
        }
        while (pending.isNotEmpty()) {
            val (s, at0, ab) = pending.first()
            if (n - 1 < at0 + (after[s] + 1) * sps) break
            pending.removeFirst()
            val l = syncs[s].size
            val total = before + l + after[s]
            val first = at0 - (l - 1 + before) * sps
            if (first < n - RING + 4) continue
            onFrame(Frame(s, FloatArray(total) { k -> ((at(first + k * sps) - ab.second) / ab.first).toFloat() }, first, ab.first, ab.second))
        }
        if (reads.isNotEmpty()) runReads()
    }

    private fun runReads() {
        if (reads.none { n - 1 >= it.first + (it.n + 3) * sps }) return
        val due = reads.filter { n - 1 >= it.first + (it.n + 3) * sps }
        reads.removeAll(due)
        for (r in due) {
            if (r.first < n - RING + 4 * sps) continue
            val tpl = r.refine
            if (tpl != null) {
                var best = Double.NEGATIVE_INFINITY
                var bestOff = 0.0
                var off = -1.5 * sps
                while (off <= 1.5 * sps) {
                    var c = 0.0
                    for (k in tpl.indices) c += (at(r.first + off + k * sps) - r.b) * tpl[k]
                    if (c > best) { best = c; bestOff = off }
                    off += 1.0
                }
                r.first += bestOff
                var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
                val l = tpl.size
                for (k in 0 until l) { val y = at(r.first + k * sps).toDouble(); val x = tpl[k].toDouble(); sx += x; sy += y; sxx += x * x; sxy += x * y }
                val a = (l * sxy - sx * sy) / (l * sxx - sx * sx)
                if (a > 0.5 * r.a && a < 2 * r.a) { r.a = a; r.b = (sy - a * sx) / l }
            }
            onRead?.invoke(Frame(r.tag, FloatArray(r.n) { k -> ((at(r.first + k * sps) - r.b) / r.a).toFloat() }, r.first, r.a, r.b))
        }
    }

    private fun at(pos: Double): Float {
        val i = kotlin.math.floor(pos).toLong()
        val f = (pos - i).toFloat()
        return ring[(i and MASK).toInt()] * (1 - f) + ring[((i + 1) and MASK).toInt()] * f
    }
    private fun at(pos: Long) = ring[(pos and MASK).toInt()]

    companion object {
        private const val RING = 1 shl 14
        private const val MASK = (RING - 1).toLong()
        fun hard(v: Float) = when { v >= 2f -> 3; v >= 0f -> 1; v >= -2f -> -1; else -> -3 }
    }
}

/** What was heard on a channel. */
sealed class PmrEvent(val hz: Long) {
    class Dmr(hz: Long, val burst: com.allnetworktools.data.sdr.Dmr.Burst, val slot: Int) : PmrEvent(hz)
    class DmrVoice(hz: Long, val slot: Int) : PmrEvent(hz)
    class DpmrHeader(hz: Long, val cc: Int, val header: Dpmr.Header) : PmrEvent(hz)
    class DpmrEnd(hz: Long, val own: Int) : PmrEvent(hz)
    /** Digital voice decoded: 160 samples at 8 kHz per 20 ms; [slot] 0 for dPMR. */
    class Voice(hz: Long, val slot: Int, val pcm: FloatArray) : PmrEvent(hz)
}

/**
 * One carrier: mixed to 0 Hz from the 500 kS/s band, filtered to the channel at 50 kS/s, FM-demodulated; the DMR and
 * dPMR sync searches run on it, the CTCSS tone is measured over two seconds, and the audio can be handed out.
 */
class PmrChannel(val hz: Long, private val offHz: Double, narrow: Boolean, private val onEvent: (PmrEvent) -> Unit) {
    private val d1 = FloatDecimator(10, 20_000.0 / 500_000.0, 48)
    private val d2 = FloatDecimator(1, (if (narrow) 3_600.0 else 6_500.0) / 50_000.0, 63)
    private var pr = 1.0
    private var pim = 0.0
    private val stepR = cos(-2 * PI * offHz / 500_000.0)
    private val stepI = sin(-2 * PI * offHz / 500_000.0)
    private val mr = FloatArray(16384)
    private val mi = FloatArray(16384)
    private val ar = FloatArray(2048)
    private val ai = FloatArray(2048)
    private val br = FloatArray(2048)
    private val bi = FloatArray(2048)
    private var lr = 1f
    private var li = 0f
    private val dmr = FourFskSync(50_000.0, 4800.0, Dmr.SYNC.indices.map { Dmr.syncSymbols(it) }, 54, IntArray(9) { 54 }) { dmrFrame(it) }.also { s -> s.onRead = { dmrVoiceBurst(it) } }
    private val dpmr = FourFskSync(50_000.0, 2400.0, listOf(Dpmr.FS1, Dpmr.FS3, Dpmr.FS4), 0, intArrayOf(132, 36, 132)) { dpmrFrame(it) }.also { s -> s.onRead = { dpmrPayload(it) } }
    // AMBE+2 decoders: one per DMR time slot (0 when the slot is not known), one for dPMR.
    private val ambe = Array(3) { AmbeDecoder() }
    private val ambeDpmr = AmbeDecoder()
    /** Voice is played unless the call says it is encrypted (DMR privacy bit). */
    private val privacy = BooleanArray(3)
    private var dpmrCc = 0
    private var dpmrBad = 0
    /** Sample count up to which this channel carries digital signalling: its FM audio is then noise, not voice. */
    private var digitalUntil = 0L
    private var nOut = 0L
    // CTCSS: the discriminator averaged to 1 kS/s, kept for two seconds.
    private val low = FloatArray(2000)
    private var lowN = 0
    private var lowAcc = 0f
    private var lowCnt = 0
    var power = 0.0
        private set
    private var powerN = 0
    /** Audio at 25 kS/s (Hz of deviation), for the one channel being listened to. */
    var audio: ((FloatArray, Int) -> Unit)? = null
    private val aud = FloatArray(1024)

    fun feed(re: FloatArray, im: FloatArray, k: Int) {
        var off = 0
        while (off < k) {
            val m = minOf(16384, k - off)
            var p = pr; var q = pim
            for (i in 0 until m) {
                val cr = p.toFloat(); val ci = q.toFloat()
                mr[i] = re[off + i] * cr - im[off + i] * ci
                mi[i] = re[off + i] * ci + im[off + i] * cr
                val nr = p * stepR - q * stepI; q = p * stepI + q * stepR; p = nr
            }
            val g = 1 / hypot(p, q); pr = p * g; pim = q * g
            val n1 = d1.process(mr, mi, m, ar, ai)
            val n2 = d2.process(ar, ai, n1, br, bi)
            var na = 0
            for (i in 0 until n2) {
                val yr = br[i]; val yi = bi[i]
                power += yr * yr + yi * yi; powerN++
                val zr = yr * lr + yi * li
                val zi = yi * lr - yr * li
                lr = yr; li = yi
                val hz = (kotlin.math.atan2(zi, zr) * 50_000.0 / (2 * PI)).toFloat()
                nOut++
                dmr.push(hz)
                dpmr.push(hz)
                lowAcc += hz; lowCnt++
                if (lowCnt == 50) { low[lowN % low.size] = lowAcc / 50; lowN++; lowAcc = 0f; lowCnt = 0 }
                if (audio != null && i % 2 == 1 && nOut > digitalUntil) { aud[na++] = hz; if (na == aud.size) { audio?.invoke(aud, na); na = 0 } }
            }
            if (na > 0) audio?.invoke(aud, na)
            off += m
        }
    }

    /** Mean power since the last call, in dB (full scale = 0). */
    fun takePowerDb(): Double { val v = if (powerN > 0) 10 * log10(power / powerN + 1e-15) else -150.0; power = 0.0; powerN = 0; return v }

    /** The CTCSS tone, when one stands out of the last two seconds. */
    fun ctcss(): Double? {
        if (lowN < low.size) return null
        val x = FloatArray(low.size) { low[(lowN + it) % low.size] }
        val mean = x.average().toFloat()
        var total = 0.0
        for (i in x.indices) { x[i] -= mean; total += x[i] * x[i] }
        if (total <= 0) return null
        var best = 0.0
        var bf = 0.0
        for (f in Pmr446.ctcss) {
            val w = 2 * PI * f / 1000.0
            val c = 2 * cos(w)
            var s1 = 0.0; var s2 = 0.0
            for (v in x) { val s0 = v + c * s1 - s2; s2 = s1; s1 = s0 }
            val p = (s1 * s1 + s2 * s2 - c * s1 * s2) * 2 / x.size
            if (p > best) { best = p; bf = f }
        }
        // A tone holds most of what lies below 300 Hz once the voice is filtered by the averaging.
        return if (best > 0.25 * total) bf else null
    }

    fun resetTone() { lowN = 0 }

    private fun dibits(sym: FloatArray, from: Int, nSym: Int, out: IntArray = IntArray(2 * nSym), at: Int = 0): IntArray {
        for (k in 0 until nSym) { val d = Dmr.symbolToDibit(FourFskSync.hard(sym[from + k])); out[at + 2 * k] = d shr 1; out[at + 2 * k + 1] = d and 1 }
        return out
    }

    private fun dmrFrame(f: FourFskSync.Frame) {
        val s = f.sync
        val bits = dibits(f.symbols, 0, 132)
        digitalUntil = nOut + 50_000
        val slot = Dmr.syncSlot(s)
        if (Dmr.syncIsVoice(s)) {
            onEvent(PmrEvent.DmrVoice(hz, slot))
            // Burst A of a superframe: B to F follow on the same slot every 60 ms (288 symbols), with embedded
            // signalling where the sync was; they are read with A's timing and levels.
            dmr.cancelReads()
            for (k in 1..5) dmr.schedule(slot, f.first + 288 * k * dmr.samplesPerSymbol, 132, f.a, f.b)
            dmrVoice(slot, bits)
            return
        }
        Dmr.readData(bits)?.let { b ->
            when (b) {
                is Dmr.Burst.Header -> privacy[slot] = b.lc.privacy
                is Dmr.Burst.Terminator -> { privacy[slot] = false; dmr.cancelReads() }
                else -> {}
            }
            onEvent(PmrEvent.Dmr(hz, b, slot))
        }
    }

    private fun dmrVoiceBurst(f: FourFskSync.Frame) {
        digitalUntil = nOut + 50_000
        dmrVoice(f.sync, dibits(f.symbols, 0, 132))
    }

    /** The three AMBE+2 frames of a voice burst: its 216 voice bits around the centre field. */
    private fun dmrVoice(slot: Int, burst: IntArray) {
        if (privacy[slot]) return
        val vs = IntArray(216)
        burst.copyInto(vs, 0, 0, 108)
        burst.copyInto(vs, 108, 156, 264)
        for (k in 0 until 3) {
            val pcm = FloatArray(160)
            ambe[slot].decode(vs, 72 * k, pcm)
            onEvent(PmrEvent.Voice(hz, slot, pcm))
        }
    }

    private fun dpmrFrame(f: FourFskSync.Frame) {
        val sym = f.symbols
        digitalUntil = nOut + 50_000
        when (f.sync) {
            0, 2 -> {
                // HI0 then the colour code then HI1, the same header twice: either copy may do.
                val h = Dpmr.blockDecode(dibits(sym, 24, 60), 10)?.let(Dpmr::headerParse) ?: Dpmr.blockDecode(dibits(sym, 96, 60), 10)?.let(Dpmr::headerParse) ?: return
                dpmrCc = Dpmr.ccDecode(sym, 84)
                onEvent(PmrEvent.DpmrHeader(hz, dpmrCc, h))
                // Payload frames follow back to back, 192 symbols each, from the end of the header.
                if (h.commMode <= 1 || h.commMode == 5) {
                    dpmrBad = 0
                    dpmr.cancelReads()
                    dpmr.schedule(0, f.first + 156 * dpmr.samplesPerSymbol, 192, f.a, f.b, Dpmr.FS2)
                }
            }
            1 -> {
                dpmr.cancelReads()
                Dpmr.blockDecode(dibits(sym, 24, 36), 6)?.let(Dpmr::endParse)?.let { onEvent(PmrEvent.DpmrEnd(hz, it)) }
            }
        }
    }

    /** One payload frame ([f].sync is its number in the superframe): control channel, then four voice frames. */
    private fun dpmrPayload(f: FourFskSync.Frame) {
        val sym = f.symbols
        val cchOk = Dpmr.blockDecode(dibits(sym, 12, 36), 6)?.let { Dpmr.cchCrcOk(it) } == true
        dpmrBad = if (cchOk) 0 else dpmrBad + 1
        if (dpmrBad >= 3) return // the call has ended (or is lost): no more frames
        digitalUntil = nOut + 50_000
        val tch = dibits(sym, 48, 144)
        val pn = Dpmr.pn9(288)
        for (i in 0 until 288) tch[i] = tch[i] xor pn[i]
        for (k in 0 until 4) {
            val pcm = FloatArray(160)
            ambeDpmr.decode(tch, 72 * k, pcm)
            onEvent(PmrEvent.Voice(hz, 0, pcm))
        }
        val next = (f.sync + 1) % 4
        dpmr.schedule(next, f.first + 192 * dpmr.samplesPerSymbol, 192, f.a, f.b, if (next % 2 == 0) Dpmr.FS2 else Dpmr.ccSymbols(dpmrCc))
    }
}

/**
 * The band at 500 kS/s centred on 446.1 MHz: its power per 6.25 kHz slot from an FFT every 32 ms, and channels that
 * carry something are demodulated (at most [maxChains] at once, the strongest first).
 */
class Pmr446Receiver(private val onEvent: (PmrEvent) -> Unit) {
    private val d1 = Decimator(4, 0.075, taps = 48)
    private val re = FloatArray(32768)
    private val im = FloatArray(32768)
    private val fft = Fft(FFT)
    private val fr = FloatArray(FFT)
    private val fi = FloatArray(FFT)
    private val win = FloatArray(FFT) { (0.5 - 0.5 * cos(2 * PI * it / (FFT - 1))).toFloat() }
    private var fill = 0
    /** Power per 6.25 kHz slot, 446.0 to 446.2 MHz, smoothed; and its noise floor. */
    val slotDb = DoubleArray(32) { -150.0 }
    private var floorDb = -150.0
    private var frames = 0
    val chains = LinkedHashMap<Long, PmrChannel>()
    private val quietSince = HashMap<Long, Long>()
    private var t = 0L
    var maxChains = 6

    fun feed(buf: ByteArray, len: Int) {
        val k = d1.process(buf, len, re, im, 0)
        feedFloat(re, im, k)
    }

    fun feedFloat(re: FloatArray, im: FloatArray, k: Int) {
        for (i in 0 until k) {
            fr[fill] = re[i] * win[fill]; fi[fill] = im[i] * win[fill]
            if (++fill == FFT) { fill = 0; spectrum() }
        }
        t += k
        // A channel found active is first given the last quarter second, so the call's opening (DMR header, dPMR
        // header) that made it active is not lost.
        for (c in fresh) {
            val n = minOf(hist, H)
            var at = ((histPos - n) % H + H) % H
            var left = n
            while (left > 0) {
                val m = minOf(left, H - at, 16384)
                c.feed(hr.copyOfRange(at, at + m), hi.copyOfRange(at, at + m), m)
                at = (at + m) % H; left -= m
            }
        }
        fresh.clear()
        for (c in chains.values) c.feed(re, im, k)
        for (i in 0 until k) { hr[histPos] = re[i]; hi[histPos] = im[i]; histPos = (histPos + 1) % H }
        hist = minOf(hist + k, H)
    }

    private val hr = FloatArray(H)
    private val hi = FloatArray(H)
    private var histPos = 0
    private var hist = 0
    private val fresh = ArrayList<PmrChannel>()

    /** Where a 6.25 kHz slot's centre lies: slot 0 is 446.003125 MHz. */
    private fun slotHz(s: Int) = 446_003_125L + 6_250L * s

    private fun spectrum() {
        fft.forward(fr, fi)
        val binHz = 500_000.0 / FFT
        val p = DoubleArray(32)
        for (s in 0 until 32) {
            val off = slotHz(s) - Pmr446.CENTRE_HZ
            val c = (off / binHz).roundToInt()
            val half = (2_800.0 / binHz).roundToInt()
            var acc = 0.0
            for (b in c - half..c + half) { val j = (b + FFT) % FFT; acc += fr[j] * fr[j] + fi[j] * fi[j] }
            p[s] = 10 * log10(acc + 1e-15)
        }
        frames++
        for (s in 0 until 32) slotDb[s] = if (frames == 1) p[s] else slotDb[s] * 0.6 + p[s] * 0.4
        val sorted = slotDb.sorted()
        floorDb = sorted[8]
        manageChains()
    }

    /** Slots more than 10 dB over the floor, as carriers: an analogue or DMR channel covers two slots, a dPMR one. */
    fun activeCarriers(): List<Pair<Long, Boolean>> {
        val on = BooleanArray(32) { slotDb[it] > floorDb + 10 }
        val out = ArrayList<Pair<Long, Boolean>>()
        var s = 0
        while (s < 32) {
            if (on[s] && s + 1 < 32 && on[s + 1] && s % 2 == 0) { out += Pmr446.analogueHz(s / 2 + 1) to false; s += 2; continue }
            if (on[s] && s >= 16) out += Pmr446.dpmrHz(s - 16 + 1) to true
            else if (on[s]) out += Pmr446.analogueHz(s / 2 + 1) to false
            s++
        }
        return out.distinctBy { it.first }
    }

    private fun manageChains() {
        val now = t
        val want = activeCarriers()
        for ((hz, narrow) in want) {
            quietSince.remove(hz)
            if (hz !in chains && chains.size < maxChains) chains[hz] = PmrChannel(hz, (hz - Pmr446.CENTRE_HZ).toDouble(), narrow, onEvent).also { fresh += it }
        }
        val wanted = want.map { it.first }.toSet()
        for (hz in chains.keys.toList()) {
            if (hz in wanted) continue
            val since = quietSince.getOrPut(hz) { now }
            // Kept a little after the carrier drops, for the terminator and the end frame.
            if (now - since > 500_000 * 3 && chains[hz]?.audio == null) { chains.remove(hz); quietSince.remove(hz) }
        }
    }

    /** Keeps a chain on [hz] (for listening) even when quiet. */
    fun ensure(hz: Long, narrow: Boolean): PmrChannel = chains.getOrPut(hz) { PmrChannel(hz, (hz - Pmr446.CENTRE_HZ).toDouble(), narrow, onEvent) }

    companion object {
        const val SAMPLE_RATE = 2_000_000
        private const val FFT = 16384
        private const val H = 1 shl 17
    }
}
