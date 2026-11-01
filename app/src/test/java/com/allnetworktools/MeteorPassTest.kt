package com.allnetworktools

import com.allnetworktools.data.orbit.GnssSystem
import com.allnetworktools.data.orbit.MeteorPredictor
import com.allnetworktools.data.orbit.MeteorSat
import com.allnetworktools.data.orbit.MeteorSats
import com.allnetworktools.data.orbit.Observer
import com.allnetworktools.data.orbit.OrbitSat
import com.allnetworktools.data.orbit.PassPredictor
import com.allnetworktools.data.orbit.Sgp4
import com.allnetworktools.data.orbit.Tle
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeteorPassTest {
    // A sun-synchronous orbit at about 830 km, like Meteor-M's, at epoch 2026 day 271.5.
    private val tle = Tle(
        "SYNTH-SSO",
        "1 99999U 24001A   26271.50000000  .00000100  00000-0  50000-4 0  9995",
        "2 99999  98.7700 100.0000 0005000  90.0000   0.0000 14.23000000 10004",
    )
    private val sat = MeteorSat("Synth", 99999, 137.9)
    private val paris = Observer(48.8566, 2.3522, 35.0)
    private val start = ((Sgp4(tle).jdEpoch - 2440587.5) * 86_400_000.0).toLong()

    @Test fun passesAreCoherent() {
        val passes = MeteorPredictor.passes(tle, sat, paris, start, start + 24 * 3_600_000L, maskDeg = 5.0, minPeakDeg = 0.0)
        assertTrue("${passes.size} passes", passes.size in 3..8)
        val s = Sgp4(tle)
        fun el(t: Long): Double {
            val jd = Sgp4.jdFromUnixMs(t)
            return paris.look(s.position((jd - s.jdEpoch) * 1440.0)!!, jd).elevation
        }
        for (p in passes) {
            val minutes = (p.setMs - p.riseMs) / 60_000.0
            assertTrue("pass of $minutes min", minutes in 1.0..18.0) // a low-Earth-orbit pass never lasts longer
            assertEquals(5.0, el(p.riseMs), 0.3)
            assertEquals(5.0, el(p.setMs), 0.3)
            assertTrue(p.maxAtMs in p.riseMs..p.setMs)
            assertEquals(p.maxElevation, el(p.maxAtMs), 0.2)
            for (k in 0..10) assertTrue(el(p.riseMs + (p.setMs - p.riseMs) * k / 10) <= p.maxElevation + 0.2)
        }
        // Consecutive passes are an orbit apart (about 101 minutes), or more.
        for (i in 1 until passes.size) assertTrue((passes[i].riseMs - passes[i - 1].setMs) > 60_000L)
    }

    @Test fun agreesWithTheGnssPassPredictor() {
        val mine = MeteorPredictor.passes(tle, sat, paris, start, start + 24 * 3_600_000L, maskDeg = 10.0, minPeakDeg = 0.0)
        val ref = PassPredictor.passes(OrbitSat(tle, GnssSystem.GPS, "synth", null), paris, start, start + 24 * 3_600_000L, 10.0, stepMs = 30_000L)
        assertEquals(ref.size, mine.size)
        for ((a, b) in mine.zip(ref)) {
            assertTrue(abs(a.riseMs - (b.rise ?: a.riseMs)) <= 2000)
            assertTrue(abs(a.setMs - (b.set ?: a.setMs)) <= 2000)
            assertEquals(b.maxElevation, a.maxElevation, 0.2)
        }
    }

    @Test fun lowPassesAreDropped() {
        val all = MeteorPredictor.passes(tle, sat, paris, start, start + 24 * 3_600_000L, 5.0, 0.0)
        val good = MeteorPredictor.passes(tle, sat, paris, start, start + 24 * 3_600_000L, 5.0, 25.0)
        assertTrue(good.size < all.size)
        assertTrue(good.all { it.maxElevation >= 25.0 })
    }

    @Test fun theBroadcastFrequenciesAreTheDocumentedOnes() {
        assertEquals(137.900, MeteorSats.all.first { it.catalog == 57166 }.freqMhz, 1e-9)
        assertEquals(137.900, MeteorSats.all.first { it.catalog == 59051 }.freqMhz, 1e-9)
        assertEquals(listOf(137.900, 137.100), MeteorSats.frequencies)
    }
}
