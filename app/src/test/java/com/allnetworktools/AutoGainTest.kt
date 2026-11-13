package com.allnetworktools

import com.allnetworktools.data.sdr.AutoGain
import com.allnetworktools.data.sdr.Fft
import com.allnetworktools.data.sdr.GainProfile
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A HackRF front end for the automatic gain to work against: receiver noise whose figure falls as the LNA gain rises,
 * the converter's own noise, a DC offset, signals (a weak one to receive, strong ones to cope with), and an 8-bit
 * converter that rounds and clips.
 */
class FakeFrontEnd(seed: Long = 1) {
    var lna = 24
    var vga = 20
    var amp = false
    /** Antenna noise over the captured band, dBm (2 MHz at room temperature). */
    var noiseDbm = -111.0
    /** Tones: power in dBm and frequency as a share of the sample rate. */
    val tones = ArrayList<Pair<Double, Double>>()
    var dc = 4.0
    /** A pulsed signal (ADS-B-like): power in dBm, frequency share, on for [pulseOn] samples every [pulseEvery]. */
    var pulse: Pair<Double, Double>? = null
    var pulseOn = 120
    var pulseEvery = 4000
    private val rnd = Random(seed)
    private var n = 0L

    /** Noise figure: the LNA's gain hides what comes after it. */
    fun nf() = 6 + 0.4 * (40 - lna)

    /** dBm at the antenna to dBFS at the converter. */
    fun gainDb() = lna + vga + (if (amp) 14 else 0) - 10.0

    fun buffer(samples: Int = 4096): ByteArray {
        val g = gainDb()
        val sigma = 10.0.pow((noiseDbm + nf() + g) / 20) / sqrt(2.0) * 128
        val amps = tones.map { (dbm, f) -> 10.0.pow((dbm + g) / 20) * 128 to f }
        val pa = pulse?.let { (dbm, f) -> 10.0.pow((dbm + g) / 20) * 128 to f }
        val out = ByteArray(2 * samples)
        for (k in 0 until samples) {
            var i = sigma * rnd.nextGaussian() + 0.5 * rnd.nextGaussian() + dc
            var q = sigma * rnd.nextGaussian() + 0.5 * rnd.nextGaussian() - dc / 2
            for ((a, f) in amps) { val ph = 2 * PI * f * n; i += a * cos(ph); q += a * sin(ph) }
            if (pa != null && n % pulseEvery < pulseOn) { val ph = 2 * PI * pa.second * n; i += pa.first * cos(ph); q += pa.first * sin(ph) }
            out[2 * k] = Math.round(i).toInt().coerceIn(-127, 127).toByte()
            out[2 * k + 1] = Math.round(q).toInt().coerceIn(-127, 127).toByte()
            n++
        }
        return out
    }

    /** SNR of the tone at [f] in an averaged spectrum: its bin over the median bin, in dB. */
    fun snr(f: Double): Double {
        val size = 4096
        val fft = Fft(size)
        val acc = DoubleArray(size)
        repeat(8) {
            val b = buffer(size)
            val re = FloatArray(size) { b[2 * it].toFloat() * (0.5 - 0.5 * cos(2 * PI * it / size)).toFloat() }
            val im = FloatArray(size) { b[2 * it + 1].toFloat() * (0.5 - 0.5 * cos(2 * PI * it / size)).toFloat() }
            fft.forward(re, im)
            for (k in 0 until size) acc[k] += re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
        }
        val bin = Math.round(f * size).toInt()
        val sig = (bin - 1..bin + 1).maxOf { acc[Math.floorMod(it, size)] }
        val noise = acc.filterIndexed { k, _ -> kotlin.math.abs(k - bin) > 8 && k in 8 until size - 8 }.sorted().let { it[it.size / 2] }
        return 10 * log10(sig / noise)
    }

    /** Share of samples at full scale. */
    fun clipShare(): Double {
        val b = buffer(16384)
        return b.count { it >= 127 || it <= -127 }.toDouble() / b.size
    }
}

class AutoGainTest {
    private val weak = -128.0 to 0.1

    /** Runs the loop for [seconds] with 50 ms buffers; returns how many changes it made. */
    private fun run(fe: FakeFrontEnd, g: AutoGain, seconds: Double, t0: Double = 0.0, onTick: (Double) -> Unit = {}): Int {
        var changes = 0
        var t = t0
        while (t < t0 + seconds) {
            onTick(t)
            val step = g.feed(fe.buffer(), 8192, (t * 1e9).toLong())
            if (step != null) { fe.lna = step.first; fe.vga = step.second; changes++ }
            t += 0.05
        }
        return changes
    }

    /** The best SNR any LNA/VGA pair gives the weak tone, for comparison. */
    private fun bestSnr(fe: FakeFrontEnd, cap: Int = 40): Double {
        val (l, v) = fe.lna to fe.vga
        var best = -99.0
        for (total in 0..(cap + 62) step 4) {
            val (a, b) = AutoGain.split(total, cap)
            fe.lna = a; fe.vga = b
            if (fe.clipShare() > 0.001) continue
            best = maxOf(best, fe.snr(weak.second))
        }
        fe.lna = l; fe.vga = v
        return best
    }

    @Test fun splitPutsTheLnaFirst() {
        assertEquals(0 to 8, AutoGain.split(8, 40))
        assertEquals(8 to 10, AutoGain.split(18, 40))
        assertEquals(40 to 30, AutoGain.split(70, 40))
        assertEquals(40 to 62, AutoGain.split(102, 40))
        // Strong signals: the LNA stops at 24, the VGA takes the rest.
        assertEquals(24 to 46, AutoGain.split(70, 24))
    }

    /** The old loop stopped at LNA 24 / VGA 20: a weak signal must get the gain it needs. */
    @Test fun weakSignalClimbsToTheBestSensitivity() {
        val fe = FakeFrontEnd().apply { tones += weak }
        val best = bestSnr(fe)
        val g = AutoGain(GainProfile.Narrow, 24, 20)
        run(fe, g, 20.0)
        val got = fe.snr(weak.second)
        assertTrue("gain ${fe.lna}/${fe.vga}: SNR $got vs best $best", got >= best - 2)
        assertTrue("LNA ${fe.lna}", fe.lna >= 32)
        assertTrue(fe.clipShare() < 0.0005)
    }

    /** Wherever it starts, it ends at about the same gain, within 12 seconds. */
    @Test fun convergesFromAnyStart() {
        val ends = listOf(0 to 0, 24 to 20, 40 to 40, 40 to 62).map { (l, v) ->
            val fe = FakeFrontEnd(7).apply { lna = l; vga = v; tones += weak }
            run(fe, AutoGain(GainProfile.Narrow, l, v), 12.0)
            fe.lna + fe.vga
        }
        assertTrue("ends $ends", ends.max() - ends.min() <= 8)
    }

    /** A strong signal: down at once, no clipping, the level inside the dead band. */
    @Test fun strongSignalComesDownWithoutClipping() {
        val fe = FakeFrontEnd().apply { lna = 40; vga = 62; tones += -35.0 to 0.2 }
        val g = AutoGain(GainProfile.Narrow, 40, 62)
        run(fe, g, 12.0)
        assertTrue("clip ${fe.clipShare()} at ${fe.lna}/${fe.vga}", fe.clipShare() < 0.0005)
        assertTrue("rms ${g.last?.rmsDbfs}", g.last!!.rmsDbfs in -31.0..-21.0)
    }

    /** Steady input with its noise: once settled, the gain stays put for five minutes. */
    @Test fun noHuntingOnSteadyInput() {
        val fe = FakeFrontEnd(3).apply { tones += weak }
        val g = AutoGain(GainProfile.Narrow, 24, 20)
        run(fe, g, 15.0)
        val changes = run(fe, g, 300.0, t0 = 15.0)
        assertTrue("$changes changes", changes <= 1)
    }

    /** A strong burst (a nearby transmitter keying up): cut, then back to where it was once it is gone. */
    @Test fun recoversAfterABurst() {
        val fe = FakeFrontEnd(5).apply { tones += weak }
        val g = AutoGain(GainProfile.Narrow, 24, 20)
        run(fe, g, 20.0)
        val before = fe.lna + fe.vga
        val burst = -30.0 to 0.3
        var cut = Int.MAX_VALUE
        run(fe, g, 3.0, t0 = 20.0) { if (it >= 20.0 && burst !in fe.tones) fe.tones += burst }
        cut = fe.lna + fe.vga
        fe.tones -= burst
        run(fe, g, 40.0, t0 = 23.0)
        val after = fe.lna + fe.vga
        assertTrue("cut to $cut from $before", cut <= before - 6)
        assertTrue("back to $after, was $before", after >= before - 4)
    }

    /** Broadcast FM: the LNA, first to overload on strong stations, stays at 24 dB at most. */
    @Test fun strongProfileSparesTheLna() {
        val fe = FakeFrontEnd().apply { noiseDbm = -115.0; tones += -70.0 to 0.1 }
        val g = AutoGain(GainProfile.Strong, 24, 20)
        run(fe, g, 20.0)
        assertTrue("LNA ${fe.lna}", fe.lna <= 24)
        assertTrue(fe.clipShare() < 0.0005)
    }

    /** A big DC offset is not signal: it must not stop the climb. */
    @Test fun dcOffsetDoesNotFoolTheLevel() {
        val fe = FakeFrontEnd().apply { dc = 12.0; tones += weak }
        val g = AutoGain(GainProfile.Narrow, 24, 20)
        run(fe, g, 20.0)
        assertTrue("${fe.lna}/${fe.vga}", fe.lna + fe.vga >= 70)
    }

    /** A new profile (Listen switching from WFM to NFM) starts a fresh acquisition from the gain in use. */
    @Test fun reprofileKeepsTheGainAndReacquires() {
        val fe = FakeFrontEnd().apply { tones += weak }
        val g = AutoGain(GainProfile.Strong, 24, 20)
        run(fe, g, 10.0)
        g.reprofile(GainProfile.Narrow)
        run(fe, g, 15.0, t0 = 10.0)
        assertTrue("${fe.lna}/${fe.vga}", fe.lna >= 32)
    }

    /** The weak-signal result holds whatever the noise draws. */
    @Test fun weakSignalOnOtherDraws() {
        for (seed in 11L..14L) {
            val fe = FakeFrontEnd(seed).apply { tones += weak }
            val best = bestSnr(fe)
            run(fe, AutoGain(GainProfile.Narrow, 16, 20), 20.0)
            val got = fe.snr(weak.second)
            assertTrue("seed $seed: ${fe.lna}/${fe.vga} SNR $got vs $best", got >= best - 2)
        }
    }

    /** ADS-B: short strong pulses decide (the average says little); they stay clear of full scale, the noise above the converter's. */
    @Test fun pulsesAreJudgedOnTheirPeaks() {
        val fe = FakeFrontEnd().apply { pulse = -50.0 to 0.15; tones += weak }
        val g = AutoGain(GainProfile.Pulsed, 40, 62)
        run(fe, g, 20.0)
        assertTrue("clip ${fe.clipShare()} at ${fe.lna}/${fe.vga}", fe.clipShare() < 0.0005)
        assertTrue("peak ${g.last?.peak}", g.last!!.peak < 0.95)
        assertTrue("gain ${fe.lna}/${fe.vga}", fe.lna + fe.vga >= 40)
    }

    /** A wide channel (spectrum, Zigbee): headroom first, and no hunting between the level and the margin rules. */
    @Test fun widebandSettlesAndStays() {
        val fe = FakeFrontEnd(9).apply { tones += -90.0 to 0.25 }
        val g = AutoGain(GainProfile.Wideband, 24, 20)
        run(fe, g, 15.0)
        val changes = run(fe, g, 120.0, t0 = 15.0)
        assertTrue("$changes changes", changes <= 1)
        assertTrue(fe.clipShare() < 0.0005)
    }

    /** No antenna signal at all: the gain still climbs until the receiver's own noise rises well above the converter's. */
    @Test fun noiseOnlyStillGetsSensitivity() {
        val fe = FakeFrontEnd(4)
        val g = AutoGain(GainProfile.Narrow, 16, 20)
        run(fe, g, 20.0)
        assertTrue("margin ${g.last?.marginDb} at ${fe.lna}/${fe.vga}", (g.last?.marginDb ?: 0.0) >= 10)
    }
}
