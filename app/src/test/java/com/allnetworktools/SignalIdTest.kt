package com.allnetworktools

import com.allnetworktools.data.sdr.SignalId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalIdTest {
    /** A flat floor at -90 dBFS with one signal [widthHz] wide at [hz], on a view of [span] around [center]. */
    private fun spectrum(center: Double, span: Double, hz: Double, widthHz: Double, n: Int = 1024): FloatArray = FloatArray(n) { i ->
        val f = center - span / 2 + (i + 0.5) * span / n
        if (kotlin.math.abs(f - hz) <= widthHz / 2) -50f else -90f + ((i * 7919) % 5) * 0.3f
    }

    private fun at(center: Double, span: Double, hz: Double) = ((hz - (center - span / 2)) / span).toFloat()

    @Test fun anFmStationIsBroadcastFm() {
        val c = 98e6; val s = 10e6
        val m = SignalId.measure(spectrum(c, s, 99.5e6, 180e3), at(c, s, 99.52e6), c, s)
        assertTrue(m.present)
        assertEquals(180e3, m.bwHz!!, 25e3)
        assertEquals("fm", SignalId.identify(m).first().kind.key)
    }

    @Test fun meshtasticAt869IsNotTakenForAnyIsmDevice() {
        val c = 868.5e6; val s = 2e6
        val m = SignalId.measure(spectrum(c, s, 869.525e6, 250e3), at(c, s, 869.525e6), c, s)
        val g = SignalId.identify(m)
        assertEquals("meshtastic", g.first().kind.key)
        assertTrue(g.first().confidence > 0.4)
    }

    @Test fun aWideCarrierAt1090IsAdsb() {
        val c = 1090e6; val s = 10e6
        val m = SignalId.measure(spectrum(c, s, 1090e6, 2e6), at(c, s, 1090e6), c, s)
        assertEquals("adsb", SignalId.identify(m).first().kind.key)
    }

    @Test fun nothingStandsOutGivesTheBandOnly() {
        val c = 162e6; val s = 2e6
        val flat = FloatArray(1024) { -90f }
        val m = SignalId.measure(flat, at(c, s, 161.975e6), c, s)
        assertTrue(!m.present)
        assertNull(m.bwHz)
        val g = SignalId.identify(m)
        assertNotNull(g.firstOrNull { it.kind.key == "ais" })
    }

    @Test fun aNarrowSignalOnAWideViewSaysZoomIn() {
        val c = 145e6; val s = 20e6
        val m = SignalId.measure(spectrum(c, s, 144.8e6, 12e3), at(c, s, 144.8e6), c, s)
        assertTrue(m.tooNarrow)
        assertEquals("aprs", SignalId.identify(m).first().kind.key)
    }

    @Test fun aSignalPastTheEdgeIsAtLeastThatWide() {
        val c = 2442e6; val s = 20e6
        val m = SignalId.measure(spectrum(c, s, 2437e6, 22e6), at(c, s, 2437e6), c, s)
        assertTrue(m.truncated)
        assertEquals("wifi24", SignalId.identify(m).first().kind.key)
    }

    @Test fun everyKindHasSaneRanges() {
        SignalId.Kinds.forEach { k ->
            assertTrue(k.key, k.ranges.all { it.first < it.last && it.first > 0 })
            assertTrue(k.key, k.bwMin <= k.bwMax)
        }
        assertEquals(SignalId.Kinds.size, SignalId.Kinds.map { it.key }.distinct().size)
    }

    /**
     * Issue #17: a DMR signal (4FSK, four humps with dips between them) read its width from the hump nearest the
     * marker, so the width changed as the marker moved. It must not, wherever the marker sits on the signal.
     */
    @Test fun aDmrWidthDoesNotDependOnTheMarker() {
        val c = 446.1e6
        val s = 200e3
        val n = 1024
        val rnd = java.util.Random(4)
        val hz0 = 446.08e6
        val db = FloatArray(n) { i ->
            val f = c - s / 2 + (i + 0.5) * s / n - hz0
            var p = 0.0
            for (dev in doubleArrayOf(-1944.0, -648.0, 648.0, 1944.0)) p += Math.exp(-(f - dev) * (f - dev) / (2 * 350.0 * 350.0))
            (-90 + 10 * kotlin.math.log10(1 + 1e4 * p) + rnd.nextGaussian()).toFloat()
        }
        val widths = (-2500..2500 step 125).map { off -> SignalId.measure(db, at(c, s, hz0 + off), c, s) }
        widths.forEach { assertTrue(it.present) }
        val bws = widths.map { it.bwHz!! }
        assertTrue("widths $bws", bws.max() - bws.min() <= 2 * s / n)
        assertEquals(5.4e3, bws.average(), 1.5e3)
        // The centre found is the signal's, not the marker's.
        widths.forEach { assertEquals(hz0, it.hz.toDouble(), 600.0) }
    }
}
