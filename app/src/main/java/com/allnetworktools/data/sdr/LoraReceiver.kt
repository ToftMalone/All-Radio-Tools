package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin

/** A LoRa frame whose header checksum passed; [crcOk] says whether the payload survived too. */
class LoraFrame(
    val payload: ByteArray,
    val crcOk: Boolean,
    val hasCrc: Boolean,
    val cr: Int,
    /** LoRa-style SNR: signal over the noise in the channel, median over the frame's symbols. */
    val snrDb: Double,
    /** Frequency offset of the transmitter from the tuned frequency. */
    val cfoHz: Double,
    /** Network id read from the two sync symbols (0x2B for Meshtastic, 0x34 for LoRaWAN, 0x12 for private LoRa). */
    val syncWord: Int,
)

/**
 * Software LoRa receiver for complex baseband at [OS] samples per chip, following the frame synchronisation of
 * gr-lora_sdr (Tapparel et al., EPFL), which interoperates with Semtech chips:
 *
 * 1. Preamble: windows one symbol apart whose dechirped spectrum peaks on the same bin. The upchirps repeat, so this
 *    holds whatever the window's alignment; the bin is the timing offset plus the frequency offset.
 * 2. Fractional frequency offset from the phase turn of that peak from one window to the next, and fractional timing
 *    from the sub-sample start that concentrates the most energy.
 * 3. The windows are shifted so the preamble reads bin 0. A downchirp then reads twice the integer frequency offset,
 *    which separates it from the timing offset and places the true symbol boundary.
 * 4. Header and payload symbols are read with the full frequency offset removed and the timing drift it implies
 *    (carrier and chip clock come from the same crystal), then decoded by [LoraPhy].
 *
 * The sync word is reported, not required: the header checksum and the CRC decide whether a frame is good.
 */
class LoraReceiver(
    private val sf: Int,
    bandwidthHz: Double,
    private val centerHz: Double,
    private val ldro: Boolean = LoraPhy.lowDataRate(sf, bandwidthHz),
    private val onFrame: (LoraFrame) -> Unit,
) {
    private val n = 1 shl sf
    private val bw = bandwidthHz
    private val symLen = OS * n
    private val fft = Fft(n)

    // The base upchirp e^{j(πm²/N − πm)}, one sample per chip.
    private val upRe = FloatArray(n)
    private val upIm = FloatArray(n)

    init {
        for (m in 0 until n) {
            val ph = (PI * m.toDouble() * m / n - PI * m) % (2 * PI)
            upRe[m] = cos(ph).toFloat(); upIm[m] = sin(ph).toFloat()
        }
    }

    // 8-tap windowed-sinc fractional delay filters, INTERP phases between two samples.
    private val taps = Array(INTERP + 1) { p ->
        val mu = p.toDouble() / INTERP
        val t = DoubleArray(8) { k ->
            val x = (k - 3) - mu
            val sinc = if (abs(x) < 1e-12) 1.0 else sin(PI * x) / (PI * x)
            sinc * (0.42 + 0.5 * cos(PI * x / 4.5) + 0.08 * cos(2 * PI * x / 4.5))
        }
        val s = t.sum()
        FloatArray(8) { (t[it] / s).toFloat() }
    }

    // Ring buffer of the most recent samples, addressed by absolute sample index.
    // 32 symbols: the preamble windows looked back on and the header retried a slot early, with room to spare.
    // Many receivers run at once (every spreading factor of every channel), so no more.
    private val cap = maxOf(1 shl 14, symLen * 32)
    private val mask = cap - 1
    private val bufRe = FloatArray(cap)
    private val bufIm = FloatArray(cap)
    private var total = 0L

    private val wRe = FloatArray(n)
    private val wIm = FloatArray(n)

    // Result of the last [dechirp].
    private var pkBin = 0
    private var pkPow = 0.0
    private var pkMean = 1.0
    private var pkRe = 0.0
    private var pkIm = 0.0

    /** Counters for the reception diagnosis; written by the DSP thread, read by the UI. */
    @Volatile var preambles = 0; private set
    @Volatile var sfdLost = 0; private set
    @Volatile var headerErrors = 0; private set
    @Volatile var framesOk = 0; private set
    @Volatile var framesBad = 0; private set
    @Volatile var lastSyncWord = -1; private set

    private enum class Stage { Detect, Seek, Payload }

    private var stage = Stage.Detect

    // Detection.
    private var next = 0L
    private class Win(val pos: Long, val bins: IntArray, val pows: DoubleArray)
    private val run = ArrayList<Win>()

    // Synchronisation.
    private var frac = 0.0          // fractional frequency offset, bins
    private var grid = 0.0          // slot 0 of the preamble-aligned grid
    private var slot = 0
    private var firstDown = -1

    // Frame.
    private var cfo = 0.0           // total frequency offset, bins
    private var rho = 1.0           // receiver samples per transmitted sample
    private var start = 0.0         // first header symbol
    private var syncWord = -1
    private var header: LoraPhy.Header? = null
    private var needed = 8
    private val symbols = ArrayList<Int>()
    private val snrs = ArrayList<Double>()

    fun feed(re: FloatArray, im: FloatArray, count: Int) {
        // In pieces well under the ring, so nothing waiting is overwritten before it is read.
        var off = 0
        while (off < count) {
            val m = minOf(cap / 8, count - off)
            for (i in 0 until m) {
                val k = (total + i).toInt() and mask
                bufRe[k] = re[off + i]; bufIm[k] = im[off + i]
            }
            total += m
            off += m
            while (step()) Unit
        }
    }

    private fun step(): Boolean = when (stage) {
        Stage.Detect -> detect()
        Stage.Seek -> seek()
        Stage.Payload -> payload()
    }

    private fun available(at: Double): Boolean = total >= floor(at).toLong() + symLen + MARGIN

    private fun tooOld(at: Double): Boolean = floor(at).toLong() - MARGIN < total - cap

    /**
     * Dechirps the symbol starting at sample [at] (fractional positions are interpolated) after removing [shift] bins
     * of frequency offset, and leaves the strongest bin in [pkBin]/[pkPow]/[pkRe]/[pkIm]. [down] matches downchirps.
     */
    private fun dechirp(at: Double, shift: Double, down: Boolean) {
        var base = floor(at).toLong()
        var ph = ((at - base) * INTERP).roundToInt()
        if (ph == INTERP) { ph = 0; base++ }
        val t = taps[ph]
        val step = -2 * PI * shift / n
        val cr = cos(step)
        val ci = sin(step)
        var rr = 1.0
        var ri = 0.0
        for (m in 0 until n) {
            val at0 = base + OS.toLong() * m
            var xr: Float
            var xi: Float
            if (ph == 0) {
                val k = at0.toInt() and mask
                xr = bufRe[k]; xi = bufIm[k]
            } else {
                xr = 0f; xi = 0f
                for (j in 0 until 8) {
                    val k = (at0 + j - 3).toInt() and mask
                    xr += t[j] * bufRe[k]
                    xi += t[j] * bufIm[k]
                }
            }
            if (shift != 0.0) {
                val a = (xr * rr - xi * ri).toFloat()
                xi = (xr * ri + xi * rr).toFloat()
                xr = a
                val nr = rr * cr - ri * ci
                ri = rr * ci + ri * cr
                rr = nr
            }
            val ur = upRe[m]
            val ui = if (down) upIm[m] else -upIm[m]
            wRe[m] = xr * ur - xi * ui
            wIm[m] = xr * ui + xi * ur
        }
        fft.forward(wRe, wIm)
        var best = 0
        var bestP = -1.0
        var sum = 0.0
        for (k in 0 until n) {
            val p = wRe[k].toDouble() * wRe[k] + wIm[k].toDouble() * wIm[k]
            sum += p
            if (p > bestP) { bestP = p; best = k }
        }
        pkBin = best
        pkPow = bestP
        pkMean = ((sum - bestP) / (n - 1)).coerceAtLeast(1e-20)
        pkRe = wRe[best].toDouble()
        pkIm = wIm[best].toDouble()
    }

    private fun ratio() = pkPow / pkMean

    private fun signed(bin: Int): Int = if (bin > n / 2) bin - n else bin

    private fun circ(a: Int, b: Int): Int = abs(signed(Math.floorMod(a - b, n)))

    // ---- 1. preamble ------------------------------------------------------------------------------------

    private fun detect(): Boolean {
        if (next < total - cap + 4L * symLen) { next = total - symLen; run.clear() }
        if (!available(next.toDouble() + OS)) return false
        val bins = IntArray(OS)
        val pows = DoubleArray(OS)
        var bestP = -1.0
        var bestBin = 0
        var bestRatio = 0.0
        for (p in 0 until OS) {
            dechirp((next + p).toDouble(), 0.0, down = false)
            bins[p] = pkBin; pows[p] = pkPow
            if (pkPow > bestP) { bestP = pkPow; bestBin = pkBin; bestRatio = ratio() }
        }
        val w = Win(next, bins, pows)
        next += symLen
        if (bestRatio < DETECT_RATIO) { run.clear(); return true }
        if (run.isNotEmpty() && circ(bestBin, run.last().bins[run.last().pows.indices.maxBy { run.last().pows[it] }]) > BIN_TOLERANCE) run.clear()
        run += w
        if (run.size >= PREAMBLE_MIN) lock()
        return true
    }

    private fun lock() {
        preambles++
        val wins = run.takeLast(PREAMBLE_MIN)
        run.clear()
        // Sample phase with the most preamble energy, then the fractional frequency offset: the peak turns by
        // 2π·frac from one symbol to the next.
        val phase = (0 until OS).maxBy { p -> wins.sumOf { it.pows[p] } }
        val ref = wins.map { it.bins[phase] }.groupingBy { it }.eachCount().maxBy { it.value }.key
        var accRe = 0.0
        var accIm = 0.0
        var prevRe = 0.0
        var prevIm = 0.0
        wins.forEachIndexed { i, w ->
            dechirpAt(w.pos + phase.toDouble(), ref)
            if (i > 0) {
                accRe += pkRe * prevRe + pkIm * prevIm
                accIm += pkIm * prevRe - pkRe * prevIm
            }
            prevRe = pkRe; prevIm = pkIm
        }
        frac = atan2(accIm, accRe) / (2 * PI)
        // Fine timing: the sub-sample start where the last windows concentrate the most energy.
        var bestOff = 0.0
        var bestE = -1.0
        var off = -0.5
        while (off <= 0.5 + 1e-9) {
            var e = 0.0
            for (w in wins.takeLast(3)) { dechirp(w.pos + phase + off, frac, down = false); e += pkPow }
            if (e > bestE) { bestE = e; bestOff = off }
            off += 0.125
        }
        val last = wins.last().pos + phase + bestOff
        dechirp(last, frac, down = false)
        grid = last - signed(pkBin) * OS
        slot = 1
        firstDown = -1
        stage = Stage.Seek
    }

    /** Dechirps at [at] and reads the complex value of bin [bin] instead of the strongest one. */
    private fun dechirpAt(at: Double, bin: Int) {
        dechirp(at, 0.0, down = false)
        pkRe = wRe[bin].toDouble(); pkIm = wIm[bin].toDouble()
    }

    // ---- 2. start of frame ------------------------------------------------------------------------------

    private fun seek(): Boolean {
        val at = grid + slot.toDouble() * symLen
        if (!available(at)) return false
        if (tooOld(at)) { restart(total - symLen); return true }
        dechirp(at, frac, down = false)
        val up = pkPow
        dechirp(at, frac, down = true)
        val isDown = pkPow > 2 * up && ratio() > DETECT_RATIO
        if (isDown && firstDown >= 0 && slot == firstDown + 1) {
            synchronise(pkBin)
            return true
        }
        firstDown = if (isDown) slot else -1
        if (++slot > SEEK_SLOTS) { sfdLost++; restart(at.toLong()) }
        return true
    }

    private fun restart(from: Long) {
        stage = Stage.Detect
        next = from
        run.clear()
    }

    /** [downBin] is the second downchirp read on the preamble-aligned grid: twice the integer frequency offset. */
    private fun synchronise(downBin: Int) {
        // The chip clock has drifted since the preamble windows, by up to a chip at 20 ppm: read the downchirp again
        // where the first estimate of the offset puts it.
        var cInt = (signed(downBin) / 2.0).roundToInt()
        rho = 1 - ((cInt + frac) * bw / n) / centerHz
        dechirp(grid + (firstDown + 1).toDouble() * symLen * rho, frac, down = true)
        cInt = (signed(pkBin) / 2.0).roundToInt()
        cfo = cInt + frac
        rho = 1 - (cfo * bw / n) / centerHz
        val d1 = grid + firstDown.toDouble() * symLen * rho + cInt * OS
        // The two sync symbols just before the downchirps carry the network id, 8 bins per nibble.
        dechirp(d1 - 2 * symLen * rho, cfo, down = false)
        val s1 = pkBin
        dechirp(d1 - symLen * rho, cfo, down = false)
        val s2 = pkBin
        syncWord = ((((s1 + 4) / 8) and 0x0F) shl 4) or (((s2 + 4) / 8) and 0x0F)
        lastSyncWord = syncWord
        start = d1 + 2.25 * symLen * rho
        header = null
        needed = 8
        symbols.clear(); snrs.clear()
        stage = Stage.Payload
    }

    // ---- 3. header and payload --------------------------------------------------------------------------

    private fun symbolAt(i: Int): Double = start + i.toDouble() * symLen * rho

    private fun readSymbol(at: Double): Int {
        dechirp(at, cfo, down = false)
        snrs += 10 * log10(ratio() / n)
        return pkBin
    }

    private fun payload(): Boolean {
        if (header == null) {
            // The header must decode at the expected place; a slot early or late is tried before giving up.
            val last = symbolAt(8) + symLen
            if (!available(last)) return false
            if (tooOld(symbolAt(0) - symLen)) { restart(total - symLen); return true }
            for (shift in intArrayOf(0, 1, -1)) {
                val bins = List(8) { readSymbol(symbolAt(it) + shift.toDouble() * symLen * rho) }
                val h = LoraPhy.readHeader(bins, sf)?.first ?: continue
                start += shift.toDouble() * symLen * rho
                header = h
                symbols.clear(); symbols.addAll(bins)
                needed = 8 + LoraPhy.payloadSymbols(sf, h.payloadLen, h.cr, h.hasCrc, ldro)
                break
            }
            if (header == null) {
                headerErrors++
                restart(symbolAt(2).toLong())
            }
            return true
        }
        if (symbols.size >= needed) {
            finish()
            restart(symbolAt(needed).toLong())
            return true
        }
        val at = symbolAt(symbols.size)
        if (!available(at)) return false
        if (tooOld(at)) { restart(total - symLen); return true }
        symbols += readSymbol(at)
        return true
    }

    private fun finish() {
        val d = LoraPhy.decode(symbols, sf, ldro) ?: return
        if (d.crcOk) framesOk++ else framesBad++
        val snr = snrs.sorted()[snrs.size / 2]
        onFrame(LoraFrame(d.payload, d.crcOk, d.header.hasCrc, d.header.cr, snr, cfo * bw / n, syncWord))
    }

    companion object {
        /** Samples per chip expected on input. */
        const val OS = 2
        private const val INTERP = 64
        private const val MARGIN = 8
        private const val PREAMBLE_MIN = 5
        /** Peak over the mean of the other bins; noise alone rarely reaches it on several windows on the same bin. */
        private const val DETECT_RATIO = 6.0
        private const val BIN_TOLERANCE = 2
        /** Preamble (up to 16 symbols after the first 5 seen), sync word and a margin. */
        private const val SEEK_SLOTS = 32
    }
}
