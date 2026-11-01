package com.allnetworktools

import com.allnetworktools.data.sdr.QpskDemodulator
import com.allnetworktools.data.sdr.rootRaisedCosine
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** QPSK transmitter: root-raised-cosine pulses evaluated at arbitrary instants, so timing and clock errors are exact. */
object QpskTx {
    private fun rrc(t: Double, alpha: Double): Double = when {
        abs(t) < 1e-9 -> 1 + alpha * (4 / PI - 1)
        abs(abs(t) - 1 / (4 * alpha)) < 1e-9 -> alpha / sqrt(2.0) * ((1 + 2 / PI) * sin(PI / (4 * alpha)) + (1 - 2 / PI) * cos(PI / (4 * alpha)))
        else -> (sin(PI * t * (1 - alpha)) + 4 * alpha * t * cos(PI * t * (1 + alpha))) / (PI * t * (1 - (4 * alpha * t) * (4 * alpha * t)))
    }

    /** Pairs of bits per symbol: bit 1 → +, bit 0 → −, on I then Q; with [oqpsk] Q lags I by half a symbol. */
    fun waveform(bits: IntArray, sps: Double, offset: Double, ppm: Double, cfoHz: Double, fs: Double, snrDb: Double, seed: Long, lead: Int = 0, oqpsk: Boolean = false): Pair<FloatArray, FloatArray> {
        val rnd = Random(seed)
        val ns = bits.size / 2
        val total = ((ns + 20) * sps).toInt() + lead
        val re = FloatArray(total)
        val im = FloatArray(total)
        // Unit-energy pulse, symbols at ±1/√2 ± j/√2: the signal power per sample is 1/sps.
        val norm = 1.0 / 1.0
        val sigma = sqrt(1.0 / sps / Math.pow(10.0, snrDb / 10) / 2) * sqrt(sps) // noise measured in the symbol bandwidth
        val tsym = sps / fs
        for (i in 0 until total) {
            val t = ((i - lead) * (1 + ppm * 1e-6) + offset) / fs
            var sr = 0.0
            var si = 0.0
            if (i >= lead) {
                val kc = Math.floor(t / tsym).toInt()
                for (k in kc - 4..kc + 5) {
                    if (k < 0 || k >= ns) continue
                    val p = rrc(t / tsym - k, 0.6) * norm
                    val pq = if (oqpsk) rrc(t / tsym - k - 0.5, 0.6) * norm else p
                    sr += p * (if (bits[2 * k] == 1) 1 else -1) * 0.7071
                    si += pq * (if (bits[2 * k + 1] == 1) 1 else -1) * 0.7071
                }
            }
            val ph = 2 * PI * cfoHz * (i / fs)
            val cr = cos(ph)
            val ci = sin(ph)
            re[i] = ((sr * cr - si * ci) + rnd.nextGaussian() * sigma).toFloat()
            im[i] = ((sr * ci + si * cr) + rnd.nextGaussian() * sigma).toFloat()
        }
        return re to im
    }
}

class LrptQpskTest {
    @Test fun rrcHasUnitEnergyAndIsSymmetric() {
        val h = rootRaisedCosine(0.6, 4, 8)
        assertEquals(33, h.size)
        assertEquals(1.0, h.sumOf { it.toDouble() * it }, 1e-6)
        for (i in 0 until 16) assertEquals(h[i], h[32 - i], 1e-6f)
    }

    /** Estimated SNR read while the signal is still there (the last symbols of a capture are noise only). */
    private var snrMidway = 0.0

    private fun run(re: FloatArray, im: FloatArray, out: ArrayList<IntArray>): QpskDemodulator {
        lateinit var d: QpskDemodulator
        d = QpskDemodulator { i, q -> out += intArrayOf(i, q); if (out.size == 15000) snrMidway = d.snrDb }
        var k = 0
        while (k < re.size) {
            val n = minOf(4096, re.size - k)
            d.feed(re.copyOfRange(k, k + n), im.copyOfRange(k, k + n), n)
            k += n
        }
        return d
    }

    /** Best fraction of agreeing hard decisions over the four rotations and lags near the expected start. */
    private fun agreement(bits: IntArray, got: List<IntArray>, from: Int, count: Int): Triple<Double, Int, Int> {
        var best = Triple(0.0, 0, 0)
        for (lag in 0..got.size.coerceAtMost(12000) - 1) {
            // got[from + k] ↔ transmitted symbol (lag + k)
            for (rot in 0 until 4) {
                var ok = 0
                var tot = 0
                for (k in 0 until count) {
                    val g = got.getOrNull(from + k) ?: break
                    val j = lag + from + k
                    if (2 * j + 1 >= bits.size) break
                    var i = if (g[0] > 0) 1 else 0
                    var q = if (g[1] > 0) 1 else 0
                    repeat(rot) { val ni = 1 - q; q = i; i = ni } // rotate the decision by 90°
                    if (i == bits[2 * j] && q == bits[2 * j + 1]) ok++
                    tot++
                }
                if (tot > 0 && ok.toDouble() / tot > best.first) best = Triple(ok.toDouble() / tot, lag, rot)
            }
            if (best.first > 0.97) break
        }
        return best
    }

    private fun check(cfo: Double, offset: Double, ppm: Double, snr: Double, seed: Long) {
        val rnd = Random(seed)
        val bits = IntArray(2 * 30000) { rnd.nextInt(2) }
        val (re, im) = QpskTx.waveform(bits, 4.0, offset, ppm, cfo, 288_000.0, snr, seed)
        val got = ArrayList<IntArray>()
        val d = run(re, im, got)
        assertTrue("tracking cfo=$cfo (line ratio ${d.lineRatio})", d.tracking)
        assertEquals("cfo estimate", cfo, d.cfoHz, 60.0)
        assertTrue("only ${got.size} symbols", got.size > 15000)
        val (frac, lag, rot) = agreement(bits, got, 3000, 3000)
        assertTrue("cfo=$cfo off=$offset ppm=$ppm snr=$snr: agreement $frac (lag $lag rot $rot)", frac > 0.995)
        // The estimate is in the symbol bandwidth, 6 dB above the wideband figure of the test signal (288 kHz / 72 kHz).
        assertTrue("snr estimate $snrMidway for $snr dB", abs(snrMidway - (snr + 6.0)) < 3.0)
    }

    @Test fun locksOnCleanSignalWithOffsetAndTiming() = check(cfo = 3200.0, offset = 0.37, ppm = 0.0, snr = 25.0, seed = 1)

    @Test fun toleratesNegativeOffsetClockErrorAndNoise() = check(cfo = -6500.0, offset = 1.6, ppm = 60.0, snr = 12.0, seed = 2)

    @Test fun locksAtLowSignalToNoise() = check(cfo = 1500.0, offset = 0.9, ppm = -40.0, snr = 7.0, seed = 3)

    @Test fun staysQuietOnNoise() {
        val rnd = Random(7)
        val re = FloatArray(288_000) { rnd.nextGaussian().toFloat() }
        val im = FloatArray(288_000) { rnd.nextGaussian().toFloat() }
        val got = ArrayList<IntArray>()
        val d = run(re, im, got)
        assertTrue(!d.tracking)
        assertEquals(0, got.size)
    }

    /** Out of a pass: noise, or a steady tone (birdie, interferer), must not be taken for the satellite. */
    @Test fun noiseAndTonesAreNotASignal() {
        val fs = 288_000.0
        val rnd = Random(11)
        val n = 288_000 * 4
        for (toneHz in listOf<Double?>(null, 4_000.0, 23_000.0, -41_500.0)) {
            val re = FloatArray(n)
            val im = FloatArray(n)
            for (i in 0 until n) {
                val ph = if (toneHz == null) 0.0 else 2 * PI * toneHz * i / fs
                val a = if (toneHz == null) 0.0 else 0.5
                re[i] = (a * cos(ph) + rnd.nextGaussian() * 0.3).toFloat()
                im[i] = (a * sin(ph) + rnd.nextGaussian() * 0.3).toFloat()
            }
            val d = run(re, im, ArrayList())
            assertTrue("tone $toneHz taken for QPSK", !d.confirmed)
        }
    }

    /** A satellite next to a carrier (birdie) in the same window, stronger at its own frequency, is still found. */
    @Test fun satelliteNextToATone() {
        val fs = 288_000.0
        val rnd = Random(5)
        val bits = IntArray(2 * 150_000) { rnd.nextInt(2) }
        val (re, im) = QpskTx.waveform(bits, 4.0, 0.3, 20.0, 3_000.0, fs, 6.0, 9)
        for (i in re.indices) {
            val ph = 2 * PI * -7_000.0 * i / fs
            re[i] += (0.4 * cos(ph)).toFloat(); im[i] += (0.4 * sin(ph)).toFloat()
        }
        val d = QpskDemodulator { _, _ -> }
        var found = false
        var k = 0
        while (k < re.size) {
            val n = minOf(4096, re.size - k)
            d.feed(re.copyOfRange(k, k + n), im.copyOfRange(k, k + n), n)
            if (d.confirmed && abs(d.cfoHz - 3_000.0) < 300) found = true
            k += n
        }
        assertTrue("never locked on the satellite", found)
    }
}
