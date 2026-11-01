package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Root-raised-cosine impulse response [span] symbols long, [sps] samples per symbol, unit energy. */
fun rootRaisedCosine(alpha: Double, sps: Int, span: Int): FloatArray {
    val n = span * sps + 1
    val h = DoubleArray(n) { i ->
        val t = (i - (n - 1) / 2.0) / sps
        when {
            abs(t) < 1e-9 -> 1 + alpha * (4 / PI - 1)
            abs(abs(t) - 1 / (4 * alpha)) < 1e-9 ->
                alpha / sqrt(2.0) * ((1 + 2 / PI) * sin(PI / (4 * alpha)) + (1 - 2 / PI) * cos(PI / (4 * alpha)))
            else -> (sin(PI * t * (1 - alpha)) + 4 * alpha * t * cos(PI * t * (1 + alpha))) / (PI * t * (1 - (4 * alpha * t) * (4 * alpha * t)))
        }
    }
    val norm = sqrt(h.sumOf { it * it })
    return FloatArray(n) { (h[it] / norm).toFloat() }
}

/**
 * QPSK or offset QPSK ([offsetQ]) demodulator for the 72 ksymbol/s Meteor-M LRPT downlink, complex baseband at an
 * integer number of samples per symbol (4 at 288 kS/s). The original Meteor-M2 sent QPSK; M2-2 and later send OQPSK,
 * whose Q branch lags I by half a symbol: I is read at the symbol centre and Q half a symbol later, each after the
 * carrier phase is taken off, and Gardner's timing error is taken on each branch at its own instants.
 *
 * Acquisition: the matched-filtered signal is raised to the fourth power, which removes the modulation and leaves a line
 * at four times the carrier offset; an FFT locates it, only where Doppler and the clock error can put the satellite. A tone
 * gives that line too: up to three lines are tried in turn, and one whose decisions barely change is dropped. Tracking: the offset is removed, a Gardner detector with cubic
 * interpolation recovers the symbol clock and a decision-directed Costas loop the carrier phase. The four-fold phase
 * ambiguity is left to the frame synchroniser.
 *
 * Output: soft values of the I and Q components, −127…127 (about ±70 for a clean symbol), one pair per symbol.
 */
class QpskDemodulator(
    private val sampleRate: Double = 288_000.0,
    symbolRate: Double = 72_000.0,
    private val kp: Double = KP,
    private val ki: Double = KI,
    private val alpha: Float = ALPHA,
    private val beta: Float = BETA,
    private val offsetQ: Boolean = false,
    private val onSymbol: (Int, Int) -> Unit,
) {
    private val sps = sampleRate / symbolRate
    private val symbolBin = (symbolRate / sampleRate * ACQ_N).toInt()
    private val rrc = rootRaisedCosine(0.6, sps.toInt(), 8)
    private val taps = rrc.size

    // Matched filter: doubled history so a window is contiguous.
    private val hRe = FloatArray(2 * taps)
    private val hIm = FloatArray(2 * taps)
    private var hPos = 0

    // Acquisition.
    private val acqN = ACQ_N
    private val pw = FloatArray(ACQ_N)
    private val candidates = DoubleArray(MAX_CANDIDATES)
    private var nCand = 0
    private var candIdx = 0
    private val acqRe = FloatArray(acqN)
    private val acqIm = FloatArray(acqN)
    private var acqFill = 0
    private val fft = Fft(acqN)
    private val fr = FloatArray(acqN)
    private val fi = FloatArray(acqN)
    private val window = FloatArray(acqN) { (0.5 - 0.5 * cos(2 * PI * it / (acqN - 1))).toFloat() }

    var tracking = false
        private set

    /** Carrier offset found by the acquisition, Hz. */
    var cfoHz = 0.0
        private set

    /** Ratio of the fourth-power line to the average of the spectrum at the last acquisition attempt. */
    var lineRatio = 0.0
        private set

    /** Signal-to-noise ratio of the symbols, dB, from their distance to the nearest constellation point. */
    var snrDb = 0.0
        private set

    var symbols = 0L
        private set

    // Unmodulated carriers: the decisions of a tone barely change from one symbol to the next.
    private var repeat = 0.25
    private var prevQuad = -1

    /** A QPSK signal followed for long enough, with symbols clean enough to be decoded. */
    val confirmed: Boolean get() = tracking && symbols > CONFIRM_SYMBOLS && evm < LOST_EVM && repeat < 0.35

    // Carrier removal (NCO) and the Costas loop.
    private var ncoPhase = 0.0
    private var ncoInc = 0.0
    private var theta = 0.0
    private var freq = 0.0

    // Symbol clock.
    private val ring = 16
    private val rRe = FloatArray(ring)
    private val rIm = FloatArray(ring)
    private var n = 0L // samples seen since tracking started
    private var strobe = 0.0
    private var integ = 0.0
    private var prevRe = 0f
    private var prevIm = 0f
    private var power = 1.0
    private var evm = 0.5
    private var bad = 0

    /** Ratio of the symbol-rate line of |x|² to the average of its spectrum at the last acquisition attempt (shown only). */
    var symbolLineRatio = 0.0
        private set

    /** Forgets the current signal and looks for a new one. */
    fun restart() = reset()

    private fun reset() {
        tracking = false; acqFill = 0; hRe.fill(0f); hIm.fill(0f)
    }

    fun feed(re: FloatArray, im: FloatArray, count: Int) {
        for (i in 0 until count) {
            // Matched filter.
            hRe[hPos] = re[i]; hRe[hPos + taps] = re[i]
            hIm[hPos] = im[i]; hIm[hPos + taps] = im[i]
            hPos++
            if (hPos == taps) hPos = 0
            var yr = 0f
            var yi = 0f
            for (k in 0 until taps) { yr += rrc[k] * hRe[hPos + k]; yi += rrc[k] * hIm[hPos + k] }
            if (!tracking) {
                acqRe[acqFill] = yr; acqIm[acqFill] = yi
                if (++acqFill == acqN) { acquire(); acqFill = 0 }
            } else {
                track(yr, yi)
            }
        }
    }

    private fun acquire() {
        for (k in 0 until acqN) {
            val m = sqrt(acqRe[k] * acqRe[k] + acqIm[k] * acqIm[k]) + 1e-12f
            val a = acqRe[k] / m
            val b = acqIm[k] / m
            val a2 = a * a - b * b
            val b2 = 2 * a * b
            fr[k] = (a2 * a2 - b2 * b2) * window[k]
            fi[k] = (2 * a2 * b2) * window[k]
        }
        fft.forward(fr, fi)
        var sum = 0.0
        for (k in 0 until acqN) { pw[k] = fr[k] * fr[k] + fi[k] * fi[k]; sum += pw[k] }
        val avg = (sum / acqN).toFloat()
        // Only offsets the satellite can have (Doppler and the HackRF clock): a birdie elsewhere in the band is ignored.
        val maxBin = (4 * MAX_CFO_HZ / sampleRate * acqN).toInt()
        nCand = 0
        var bestRatio = 0.0
        val taken = IntArray(MAX_CANDIDATES)
        repeat(MAX_CANDIDATES) {
            var best = -1
            var bestP = 0f
            for (s in -maxBin..maxBin) {
                val k = Math.floorMod(s, acqN)
                if (pw[k] <= bestP) continue
                if ((0 until nCand).any { c -> abs(Math.floorMod(k - taken[c] + acqN / 2, acqN) - acqN / 2) < 16 }) continue
                if (pw[k] < pw[Math.floorMod(k - 1, acqN)] || pw[k] < pw[Math.floorMod(k + 1, acqN)]) continue
                best = k; bestP = pw[k]
            }
            if (best < 0) return@repeat
            val ratio = (bestP / avg).toDouble()
            if (nCand == 0) bestRatio = ratio
            if (ratio < LINE_THRESHOLD) return@repeat
            // Parabolic interpolation of the peak position.
            fun lp(k: Int) = Math.log(pw[Math.floorMod(k, acqN)].toDouble() + 1e-30)
            val a = lp(best - 1)
            val b = lp(best)
            val c = lp(best + 1)
            val d = a - 2 * b + c
            val delta = if (abs(d) > 1e-12) (0.5 * (a - c) / d).coerceIn(-0.5, 0.5) else 0.0
            var bin = best + delta
            if (bin >= acqN / 2) bin -= acqN
            taken[nCand] = best
            candidates[nCand++] = bin * sampleRate / acqN / 4
        }
        lineRatio = bestRatio
        // For the display: |x|² carries a line at the symbol rate (raised-cosine pulses) when a modulated signal is there.
        var mean = 0f
        for (k in 0 until acqN) { val m = acqRe[k] * acqRe[k] + acqIm[k] * acqIm[k]; fr[k] = m; mean += m }
        mean /= acqN
        for (k in 0 until acqN) { fr[k] = (fr[k] - mean) * window[k]; fi[k] = 0f }
        fft.forward(fr, fi)
        var line = 0f
        var sAvg = 0.0
        for (k in 1 until acqN / 2) {
            val p = fr[k] * fr[k] + fi[k] * fi[k]
            sAvg += p
            if (abs(k - symbolBin) <= 3 && p > line) line = p
        }
        symbolLineRatio = line / (sAvg / (acqN / 2 - 1))
        if (nCand == 0) return
        candIdx = 0
        cfoHz = candidates[0]
        startTracking()
    }

    /** The line followed was not the satellite (a tone, or noise): try the next one, else search again. */
    private fun nextCandidate() {
        if (++candIdx < nCand) { cfoHz = candidates[candIdx]; startTracking() } else reset()
    }

    private fun startTracking() {
        tracking = true
        repeat = 0.25; prevQuad = -1
        ncoPhase = 0.0
        ncoInc = 2 * PI * cfoHz / sampleRate
        theta = 0.0; freq = 0.0; integ = 0.0
        n = 0; strobe = 4.0; symbols = 0
        prevRe = 0f; prevIm = 0f
        power = 1.0; evm = 0.5; bad = 0
        rRe.fill(0f); rIm.fill(0f)
    }

    private fun interp(t: Double, re: FloatArray, im: FloatArray, out: FloatArray) {
        val i = Math.floor(t).toLong()
        val mu = (t - i).toFloat()
        val w0 = -mu * (mu - 1) * (mu - 2) / 6
        val w1 = (mu + 1) * (mu - 1) * (mu - 2) / 2
        val w2 = -(mu + 1) * mu * (mu - 2) / 2
        val w3 = (mu + 1) * mu * (mu - 1) / 6
        val a = ((i - 1) and (ring - 1).toLong()).toInt()
        val b = (i and (ring - 1).toLong()).toInt()
        val c = ((i + 1) and (ring - 1).toLong()).toInt()
        val d = ((i + 2) and (ring - 1).toLong()).toInt()
        out[0] = w0 * re[a] + w1 * re[b] + w2 * re[c] + w3 * re[d]
        out[1] = w0 * im[a] + w1 * im[b] + w2 * im[c] + w3 * im[d]
    }

    private val tmp = FloatArray(2)
    private val mid = FloatArray(2)
    private val late = FloatArray(2)

    private fun track(yr: Float, yi: Float) {
        // Remove the carrier offset.
        val c = cos(ncoPhase).toFloat()
        val s = sin(ncoPhase).toFloat()
        ncoPhase -= ncoInc
        if (ncoPhase < -PI) ncoPhase += 2 * PI else if (ncoPhase > PI) ncoPhase -= 2 * PI
        val xr = yr * c - yi * s
        val xi = yr * s + yi * c
        val slot = (n and (ring - 1).toLong()).toInt()
        rRe[slot] = xr; rIm[slot] = xi
        n++
        val ahead = if (offsetQ) sps / 2 else 0.0
        while (strobe + ahead + 2 <= n - 1) {
            interp(strobe, rRe, rIm, tmp)
            interp(strobe - sps / 2, rRe, rIm, mid)
            val gain = (1 / sqrt(power + 1e-12)).toFloat()
            val cr = cos(theta).toFloat()
            val sn = sin(theta).toFloat()
            val pr: Float
            val pi: Float
            val e: Float
            if (!offsetQ) {
                val symRe = tmp[0] * gain
                val symIm = tmp[1] * gain
                val midRe = mid[0] * gain
                val midIm = mid[1] * gain
                // Gardner timing error: positive when the strobe is late.
                e = (symRe - prevRe) * midRe + (symIm - prevIm) * midIm
                prevRe = symRe; prevIm = symIm
                pr = symRe * cr + symIm * sn
                pi = -symRe * sn + symIm * cr
            } else {
                // The carrier phase off first: then I is real at the strobe, Q imaginary half a symbol later, and each
                // branch crosses zero where the other one is read.
                interp(strobe + sps / 2, rRe, rIm, late)
                pr = (tmp[0] * cr + tmp[1] * sn) * gain
                pi = (-late[0] * sn + late[1] * cr) * gain
                val iMid = (mid[0] * cr + mid[1] * sn) * gain
                val qMid = (-tmp[0] * sn + tmp[1] * cr) * gain
                e = (pr - prevRe) * iMid + (pi - prevIm) * qMid
                prevRe = pr; prevIm = pi
            }
            if (!e.isFinite()) { reset(); return }
            integ = (integ + ki * e).coerceIn(-0.1 * sps, 0.1 * sps)
            // The symbol period can wander a little, never collapse: that would stall the loop.
            strobe += (sps - (kp * e + integ)).coerceIn(0.5 * sps, 1.5 * sps)
            // Costas carrier loop.
            val err = (if (pr > 0) 1f else -1f) * pi - (if (pi > 0) 1f else -1f) * pr
            freq += beta * err
            theta += freq + alpha * err
            if (theta > PI) theta -= 2 * PI else if (theta < -PI) theta += 2 * PI
            // Amplitude and quality.
            val mag2 = (tmp[0] * tmp[0] + tmp[1] * tmp[1]).toDouble()
            power += (mag2 - power) * (if (symbols < 300) 0.05 else 0.002)
            val dr = pr - (if (pr > 0) 0.7071f else -0.7071f)
            val di = pi - (if (pi > 0) 0.7071f else -0.7071f)
            evm += ((dr * dr + di * di) - evm) * 0.005
            snrDb = -10 * log10(evm.coerceAtLeast(1e-4)) + 0.0
            symbols++
            onSymbol((pr * SOFT_SCALE).toInt().coerceIn(-127, 127), (pi * SOFT_SCALE).toInt().coerceIn(-127, 127))
            // Lost the signal (or a false acquisition): start over.
            val quad = (if (pr > 0) 2 else 0) + (if (pi > 0) 1 else 0)
            repeat += ((if (quad == prevQuad) 1.0 else 0.0) - repeat) * 0.002
            prevQuad = quad
            // Random data repeats a decision a quarter of the time, a tone nearly always.
            if (symbols > TONE_CHECK_SYMBOLS && repeat > 0.45) { nextCandidate(); return }
            // Noise alone sits at an error of about 0.40 (2 dB): above 0.36 nothing can be decoded.
            if (symbols > 4000) { if (evm > LOST_EVM) bad++ else bad = 0; if (bad > 20_000) { nextCandidate(); return } }
        }
    }

    companion object {
        private const val ACQ_N = 32768
        private const val LINE_THRESHOLD = 20.0
        private const val MAX_CANDIDATES = 3
        private const val MAX_CFO_HZ = 15_000.0
        private const val TONE_CHECK_SYMBOLS = 3000L
        private const val CONFIRM_SYMBOLS = 8000L
        private const val LOST_EVM = 0.36
        private const val KP = 0.02
        private const val KI = 2e-5
        private const val ALPHA = 0.04f
        private const val BETA = 0.0004f
        private const val SOFT_SCALE = 100f
    }
}
