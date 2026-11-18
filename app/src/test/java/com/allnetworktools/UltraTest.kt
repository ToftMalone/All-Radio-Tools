package com.allnetworktools

import com.allnetworktools.data.ultra.Sonar
import com.allnetworktools.data.ultra.UltraDetector
import com.allnetworktools.data.ultra.UltraKind
import com.allnetworktools.data.ultra.UltraLevel
import com.allnetworktools.data.ultra.UltraSpectrum
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraTest {
    private val rate = 48_000
    private val rnd = Random(7)

    /** Runs [seconds] of audio made by [sample] (time in seconds → value) through the spectrum and the detector. */
    private fun detect(seconds: Double, sample: (Double) -> Double): List<UltraKind> {
        lateinit var det: UltraDetector
        var t = 0L
        val spec = UltraSpectrum(rate) { db -> det.feed(db, t) }
        det = UltraDetector(spec.binHz, 23_500.0)
        val n = (seconds * rate).toInt()
        val chunk = FloatArray(960)
        var i = 0
        while (i < n) {
            for (j in chunk.indices) chunk[j] = (sample((i + j).toDouble() / rate) + rnd.nextGaussian() * 0.001).toFloat()
            t = (i * 1000L) / rate
            spec.feed(chunk, chunk.size)
            i += chunk.size
        }
        return det.findings(t).map { it.kind }
    }

    @Test fun silenceFindsNothing() {
        assertTrue(detect(5.0) { 0.0 }.isEmpty())
    }

    @Test fun aSteadyStrongToneIsARepellent() {
        assertEquals(listOf(UltraKind.Repellent), detect(6.0) { t -> 0.3 * sin(2 * PI * 17_400 * t) })
    }

    @Test fun anOldScreenWhinesAt15734Hz() {
        assertEquals(listOf(UltraKind.ScreenWhine), detect(6.0) { t -> 0.01 * sin(2 * PI * 15_734 * t) })
    }

    @Test fun aSweepIsASweep() {
        // 18 to 20 kHz and back, every second.
        val k = detect(4.0) { t ->
            val ph = t % 1.0
            val f = 18_000 + 2_000 * (if (ph < 0.5) ph * 2 else 2 - ph * 2)
            0.2 * sin(2 * PI * (18_000 * t + 2_000 * (ph * ph)) * 0 + 2 * PI * f * t)
        }
        assertTrue(k.toString(), UltraKind.Sweep in k)
    }

    @Test fun shortBurstsAtSeveralFrequenciesAreABeacon() {
        // 300 ms bursts alternating 18.5 and 19.5 kHz with silences, like data sent as tones.
        val k = detect(4.0) { t ->
            val slot = (t / 0.5).toInt()
            val on = (t % 0.5) < 0.3
            if (!on) 0.0 else 0.05 * sin(2 * PI * (if (slot % 2 == 0) 18_500.0 else 19_500.0) * t)
        }
        assertTrue(k.toString(), UltraKind.TrackingBeacon in k)
    }

    @Test fun broadbandNoiseAtTheTopIsAJammer() {
        // Noise filtered to the top of the band: the sum of many tones between 21.5 and 23.5 kHz with random phases.
        val tones = (0 until 80).map { 21_500.0 + it * 25.0 to rnd.nextDouble() * 2 * PI }
        val k = detect(3.0) { t -> tones.sumOf { (f, p) -> 0.01 * sin(2 * PI * f * t + p) } }
        assertTrue(k.toString(), UltraKind.Jammer in k)
    }

    @Test fun theLeakLevelRisesWithUltrasound() {
        var quiet = 0f
        var loud = 0f
        for ((amp, set) in listOf(0.0 to { v: Float -> quiet = v }, 0.05 to { v: Float -> loud = v })) {
            lateinit var lvl: UltraLevel
            val spec = UltraSpectrum(rate) { db -> lvl.feed(db) }
            lvl = UltraLevel(spec.binHz, 19_000.0, 23_500.0)
            val x = FloatArray(rate) { i -> (amp * sin(2 * PI * 21_000 * i / rate) + rnd.nextGaussian() * 0.001).toFloat() }
            spec.feed(x, x.size)
            set(lvl.levelDb)
            if (amp > 0) assertEquals(21_000.0, lvl.peakHz, 50.0)
        }
        assertTrue(loud - quiet > 20)
    }

    /** A recording: some noise, the direct chirp, then its echo [metres] away, weaker, with the direct sound's ringing. */
    private fun recording(metres: Double, echo: Double = 0.15): FloatArray {
        val c = Sonar.chirp(rate)
        val rec = FloatArray(rate / 4) { (rnd.nextGaussian() * 0.002).toFloat() }
        val d0 = 3_000
        val d1 = d0 + (2 * metres / Sonar.SPEED_OF_SOUND * rate).toInt()
        for (j in c.indices) {
            rec[d0 + j] += c[j]
            rec[d1 + j] += (c[j] * echo).toFloat()
        }
        // The speaker rings a little after the chirp.
        for (j in 0 until 400) rec[d0 + c.size + j] += (0.05 * Math.exp(-j / 60.0) * sin(2 * PI * 19_000 * j / rate)).toFloat()
        return rec
    }

    @Test fun sonarFindsTheWall() {
        for (m in listOf(0.5, 1.0, 2.2)) {
            val e = Sonar.measure(recording(m), rate, Sonar.chirp(rate))
            assertNotNull("$m m", e)
            assertEquals(m, e!!.distanceM, 0.05)
        }
    }

    @Test fun sonarWithoutAnEchoSaysNothing() {
        assertNull(Sonar.measure(recording(1.0, echo = 0.0), rate, Sonar.chirp(rate)))
    }
}
