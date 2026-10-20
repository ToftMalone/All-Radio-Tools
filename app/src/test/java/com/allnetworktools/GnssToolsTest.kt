package com.allnetworktools

import com.allnetworktools.data.CompassCheck
import com.allnetworktools.data.Constellation
import com.allnetworktools.data.GnssBand
import com.allnetworktools.data.GnssIntegrity
import com.allnetworktools.data.GnssRaw
import com.allnetworktools.data.IntegritySample
import com.allnetworktools.data.RawAgc
import com.allnetworktools.data.RawClock
import com.allnetworktools.data.RawEpoch
import com.allnetworktools.data.RawMeasurement
import com.allnetworktools.data.Rinex
import com.allnetworktools.data.Satellite
import com.allnetworktools.data.SkyMask
import com.allnetworktools.data.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Raw epochs built backwards from known ranges, for tests and screenshots. */
object GnssFake {
    /** 2026-10-20 10:15:12 GPS time, as nanoseconds since the GPS epoch. */
    val gpsNs: Long = (1_792_491_312_000L - GnssRaw.GPS_EPOCH_MS + 18_000) * 1_000_000L
    const val TIME_NANOS = 123_456_789_000L

    fun clock() = RawClock(TIME_NANOS, fullBiasNanos = TIME_NANOS - gpsNs, biasNanos = 0.0, biasUncertaintyNanos = 21.0, driftNanosPerSecond = 412.0, leapSecond = 18, elapsedRealtimeNanos = 9_000_000_000L)

    /** A measurement whose pseudorange is [rangeM] at the clock's epoch. */
    fun meas(c: Constellation, svid: Int, rangeM: Double, carrierHz: Double = 1575.42e6, cn0: Double = 40.0): RawMeasurement {
        val travel = (rangeM / GnssRaw.C * 1e9).toLong()
        val tx = when (c) {
            Constellation.Glonass -> Math.floorMod(gpsNs - 18_000_000_000L + 3 * 3_600_000_000_000L, GnssRaw.DAY_NS) - travel
            Constellation.BeiDou -> Math.floorMod(gpsNs - 14_000_000_000L, GnssRaw.WEEK_NS) - travel
            else -> Math.floorMod(gpsNs, GnssRaw.WEEK_NS) - travel
        }
        val state = if (c == Constellation.Glonass) GnssRaw.STATE_GLO_TOD_DECODED else GnssRaw.STATE_TOW_DECODED
        return RawMeasurement(c, svid, carrierHz, cn0, state or GnssRaw.STATE_CODE_LOCK, tx, adrState = 1, adrMeters = 1234.5, pseudorangeRateMps = -380.0)
    }

    fun epoch(sats: List<Satellite>): RawEpoch {
        val ms = sats.filter { it.cn0 > 20 }.flatMap { s ->
            val range = 20_200_000.0 + (90 - s.elevation) * 60_000.0
            listOfNotNull(
                meas(s.constellation, s.svid, range, cn0 = s.cn0.toDouble()),
                if (s.constellation in setOf(Constellation.GPS, Constellation.Galileo) && s.cn0 > 30) meas(s.constellation, s.svid, range + 2.1, 1176.45e6, s.cn0 - 4.0) else null,
            )
        }
        return RawEpoch(clock(), ms, listOf(RawAgc(Constellation.GPS, 1575.42e6, 4.2)), 1_792_491_294_000L, 9_000_000_000L)
    }
}

class GnssToolsTest {
    @Test fun bandsByFrequency() {
        assertEquals(GnssBand.L1, GnssRaw.band(Constellation.GPS, 1575.42e6))
        assertEquals(GnssBand.L5, GnssRaw.band(Constellation.GPS, 1176.45e6))
        assertEquals(GnssBand.E5a, GnssRaw.band(Constellation.Galileo, 1176.45e6))
        assertEquals(GnssBand.B1I, GnssRaw.band(Constellation.BeiDou, 1561.098e6))
        assertEquals(GnssBand.G1, GnssRaw.band(Constellation.Glonass, 1602.5625e6))
        assertTrue(GnssBand.L5.isLower)
    }

    @Test fun pseudorangesInEverySystemTime() {
        val clock = GnssFake.clock()
        listOf(Constellation.GPS, Constellation.Galileo, Constellation.BeiDou, Constellation.Glonass, Constellation.QZSS).forEach { c ->
            val pr = GnssRaw.pseudorangeM(clock, GnssFake.meas(c, 7, 21_345_678.0))
            assertEquals("$c", 21_345_678.0, pr!!, 0.5)
        }
        // Transmit time not decoded: no pseudorange.
        assertNull(GnssRaw.pseudorangeM(clock, GnssFake.meas(Constellation.GPS, 7, 2.1e7).copy(state = GnssRaw.STATE_CODE_LOCK)))
        // UTC from the GNSS clock.
        assertEquals(1_792_491_312_000.0, GnssRaw.utcMillis(clock)!!, 0.001)
    }

    @Test fun rinexObservationFile() {
        val e = RawEpoch(GnssFake.clock(), listOf(GnssFake.meas(Constellation.GPS, 5, 2.2e7), GnssFake.meas(Constellation.GPS, 5, 2.2e7 + 1, 1176.45e6), GnssFake.meas(Constellation.Galileo, 11, 2.4e7)), emptyList(), 0, 0)
        val text = Rinex.format(listOf(e), nowMs = 1_792_491_312_000L)
        val lines = text.lines()
        assertTrue(lines[0].endsWith("RINEX VERSION / TYPE") && lines[0].startsWith("     3.04"))
        assertTrue(lines.any { it.startsWith("G    8 C1C L1C D1C S1C C5Q L5Q D5Q S5Q") && it.endsWith("SYS / # / OBS TYPES") })
        assertTrue(lines.any { it.startsWith("E    4 C1C L1C D1C S1C") })
        assertTrue(lines.all { !it.contains("END OF HEADER") || it.length == 73 })
        val epoch = lines.first { it.startsWith(">") }
        assertEquals("> 2026 10 20 10 15 30.0000000  0  2", epoch)
        val g05 = lines.first { it.startsWith("G05") }
        assertEquals(22_000_000.0, g05.substring(3, 17).trim().toDouble(), 0.5)
        // Second signal (L5) starts after the four L1 observables.
        assertEquals(22_000_001.0, g05.substring(3 + 64, 17 + 64).trim().toDouble(), 0.5)
    }

    private fun sky(cn0: (Int, Float) -> Float) = (0 until 10).map { i ->
        val el = 15f + i * 7f
        Satellite(Constellation.GPS, i + 1, cn0(i, el), el, i * 36f, true, null)
    }

    private fun sample(sats: List<Satellite>, agc: Double = 5.0, lat: Double = 48.85, netLat: Double = 48.851, t: Long = 0) =
        IntegritySample(t, sats, listOf(RawAgc(Constellation.GPS, 1575.42e6, agc)), lat, 2.35, 40.0, t, false, 120.0, netLat, 2.35, 30f)

    @Test fun quietSkyRaisesNothing() {
        val g = GnssIntegrity()
        var r = g.feed(sample(sky { _, el -> 28f + el / 4 }))
        repeat(20) { r = g.feed(sample(sky { _, el -> 28f + el / 4 }, t = it * 1000L)) }
        assertEquals(Verdict.Ok, r.jamming)
        assertEquals(Verdict.Ok, r.spoofing)
    }

    @Test fun detectsJamming() {
        val g = GnssIntegrity()
        repeat(30) { g.feed(sample(sky { _, el -> 28f + el / 4 }, t = it * 1000L)) }
        // A jammer: AGC falls 10 dB, every signal drops ~14 dB, half the sky lost.
        val r = g.feed(sample(sky { i, el -> if (i % 2 == 0) 0f else 14f + el / 4 }, agc = -5.0, t = 31_000))
        assertEquals(Verdict.Alert, r.jamming)
    }

    @Test fun detectsSpoofing() {
        val g = GnssIntegrity()
        repeat(10) { g.feed(sample(sky { _, el -> 28f + el / 4 }, t = it * 1000L)) }
        // All signals equally strong, unrelated to elevation, and the fix 30 km from the network position.
        val r = g.feed(sample(sky { i, _ -> 44f + (i % 3) * 0.3f }, lat = 49.12, t = 11_000))
        assertEquals(Verdict.Alert, r.spoofing)
        assertTrue(r.checks.first { it.title == "Sauts de position" }.verdict == Verdict.Alert)
    }

    @Test fun compassOffset() {
        assertEquals(-170f, CompassCheck.wrap(190f), 1e-4f)
        val c = CompassCheck()
        assertNull(c.feed(90f, 0.5f, 5f, 100f)) // standing still: no course
        repeat(10) { c.feed(358f + it * 0.2f, 1.6f, 5f, 8f + it * 0.2f) }
        assertEquals(10f, c.offset()!!.first, 0.01f)
    }

    @Test fun skyMaskFindsTheBuilding() {
        val m = SkyMask()
        repeat(5) {
            m.feed(
                listOf(
                    Satellite(Constellation.GPS, 1, 0f, 20f, 5f, false, null), // north, low: behind the building
                    Satellite(Constellation.GPS, 2, 0f, 40f, 10f, false, null),
                    Satellite(Constellation.GPS, 3, 42f, 25f, 185f, true, null), // south: clear
                    Satellite(Constellation.GPS, 4, 47f, 45f, 190f, true, null),
                    Satellite(Constellation.GPS, 5, 48f, 70f, 200f, true, null),
                ),
            )
        }
        assertEquals(1f, m.blockedShare(0, 0)!!, 0f)
        assertEquals(0f, m.blockedShare(0, 6)!!, 0f)
        assertNull(m.blockedShare(0, 3))
        assertTrue(6 in m.clearDirections() && 0 !in m.clearDirections())
        assertTrue(m.openSky()!! in 0.3f..0.8f)
    }
}
