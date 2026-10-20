package com.allnetworktools

import com.allnetworktools.data.Ad
import com.allnetworktools.data.BeaconKind
import com.allnetworktools.data.Beacons
import com.allnetworktools.data.Earbuds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BeaconsTest {
    @Test fun iBeacon() {
        val b = Beacons.parse(Ad.parseHex("1AFF4C000215" + "B9407F30F5F8466EAFF925556B57FE6D" + "0001" + "0002" + "C5"))!!
        assertEquals(BeaconKind.IBeacon, b.kind)
        assertEquals("B9407F30-F5F8-466E-AFF9-25556B57FE6D · 1 / 2", b.id)
        assertEquals("Estimote", b.maker)
        assertEquals(-59, b.txAt1m)
        assertEquals(1.0, b.distanceM(-59)!!, 1e-9)
        assertEquals(10.0, b.distanceM(-79)!!, 1e-9)
    }

    @Test fun eddystoneFrames() {
        val url = Beacons.parse(Ad.parseHex("0D16AAFE10E703676F6F676C6507"))!!
        assertEquals(BeaconKind.EddystoneUrl, url.kind)
        assertEquals("https://google.com", url.id)
        assertEquals(-25 - 41, url.txAt1m)
        val uid = Beacons.parse(Ad.parseHex("1516AAFE00E8" + "0102030405060708090A" + "0B0C0D0E0F10"))!!
        assertEquals(BeaconKind.EddystoneUid, uid.kind)
        assertEquals("0102030405060708090A · 0B0C0D0E0F10", uid.id)
        val tlm = Beacons.telemetry(Ad.parseHex("1116AAFE2000" + "0BB8" + "1880" + "00000064" + "00000E10"))!!
        assertEquals(3000, tlm.batteryMv)
        assertEquals(24.5, tlm.temperatureC!!, 1e-9)
        assertEquals(100L, tlm.advCount)
        assertEquals(360L, tlm.uptimeS)
        // Google Find Hub trackers use the same service but are not beacons.
        assertNull(Beacons.parse(Ad.parseHex("0516AAFE4100")))
    }

    @Test fun altBeacon() {
        val b = Beacons.parse(Ad.parseHex("1BFF1801BEAC" + "00112233445566778899AABBCCDDEEFF" + "0007" + "0009" + "C5" + "00"))!!
        assertEquals(BeaconKind.AltBeacon, b.kind)
        assertTrue(b.id.endsWith("· 7 / 9"))
    }

    @Test fun airPodsBattery() {
        val e = Earbuds.parse(Ad.parseHex("0CFF4C0007190" + "10E2000984500"))!!
        assertEquals("AirPods Pro", e.model)
        assertEquals(90, e.left!!.percent)
        assertEquals(80, e.right!!.percent)
        assertEquals(50, e.case!!.percent)
        assertTrue(e.case!!.charging)
        assertTrue(!e.left!!.charging)
        // Unknown level (15) stays unknown.
        assertNull(Earbuds.parse(Ad.parseHex("0CFF4C00071901" + "0E20209F4500"))!!.left!!.percent)
    }
}

class CoverageTest {
    private val m = com.allnetworktools.data.CoverageMap
    private fun at(x: Float, z: Float, rssi: Int) = com.allnetworktools.data.CoverageSample(x, z, rssi, "aa", 0)

    @Test fun interpolatesBetweenMeasurements() {
        val pts = listOf(at(0f, 0f, -50), at(1f, 0f, -90))
        assertEquals(-70f, m.estimate(pts, 0.5f, 0f)!!, 1e-3f)
        assertTrue(m.estimate(pts, 0f, 0f)!! > -53f)
        // Nobody walked there: no colour.
        assertNull(m.estimate(pts, 5f, 5f))
        assertNull(m.estimate(emptyList(), 0f, 0f))
        assertEquals(1f, m.level(-40f)); assertEquals(0f, m.level(-95f))
    }

    @Test fun mapsTheWalkedCorridor() {
        // A 4 m corridor walked every 40 cm, strong at one end and weak at the other.
        val walk = (0..10).map { at(it * 0.4f, 0f, -45 - it * 4) }
        val cells = m.cells(walk)
        val stats = m.stats(walk, cells)
        assertEquals(4f, stats.distanceM, 1e-3f)
        // About 4 + 2 × 1.25 m long and 2 × 1.25 m wide, in 50 cm squares.
        assertTrue("area ${stats.areaM2}", stats.areaM2 in 12f..20f)
        assertTrue(stats.goodShare in 0.3f..0.8f)
        assertEquals(-85, stats.minRssi); assertEquals(-45, stats.maxRssi)
        assertTrue(m.csv(walk).startsWith("horodatage_ms,x_m,z_m,rssi_dbm,bssid"))
    }

    @Test fun wifimanPalette() {
        val p = com.allnetworktools.data.CoveragePalette
        assertEquals(0xFFE53935.toInt(), p.argb(-95f))
        assertEquals(0xFFFDD835.toInt(), p.argb(-60f))
        assertEquals(0xFF26A69A.toInt(), p.argb(-20f))
        // Halfway between yellow (-60) and yellow-green (-50).
        assertEquals(((0xFD + 0xC0) / 2).toFloat(), ((p.argb(-55f) shr 16) and 0xFF).toFloat(), 1f)
        assertEquals("Bon", p.quality(-65))
    }

    @Test fun paintsTheFloorUnderThePhone() {
        val f = com.allnetworktools.data.PaintedFloor()
        f.paint(0.05f, 0.05f, -1.2f, -50)
        // A 30 cm brush in 10 cm tiles: about π × 3² tiles.
        assertTrue("${f.size}", f.size in 25..32)
        assertEquals(-50f, f.rssiAt(0, 0)!!, 0f)
        f.paint(0.05f, 0.05f, -1.2f, -70)
        assertEquals(-60f, f.rssiAt(0, 0)!!, 1e-4f)
        val v = f.vertices()
        assertEquals(f.size * 6 * 7, v.size)
        assertEquals(-1.2f, v[1], 0f)
        assertNull(f.rssiAt(10, 10))
    }

    @Test fun samplesEveryFortyCentimetres() {
        assertTrue(m.shouldSample(null, 0f, 0f))
        assertTrue(!m.shouldSample(at(0f, 0f, -50), 0.3f, 0f))
        assertTrue(m.shouldSample(at(0f, 0f, -50), 0.3f, 0.3f))
    }

    @Test fun countsStepsFromTheAccelerometer() {
        fun steps(amp: Double, hz: Double): Int {
            val d = com.allnetworktools.data.StepDetector()
            val rnd = java.util.Random(1)
            return (0 until 500).count { i -> // 10 s at 50 Hz
                val t = i / 50.0
                d.feed((t * 1e9).toLong(), (9.81 + amp * kotlin.math.sin(2 * Math.PI * hz * t) + 0.1 * rnd.nextGaussian()).toFloat())
            }
        }
        val walking = steps(2.5, 1.8)
        assertTrue("walking $walking", walking in 16..19)
        assertEquals(0, steps(0.0, 1.8))
        val w = com.allnetworktools.data.StepWalker(0.7f)
        w.step(0f)
        assertEquals(-0.7f, w.z, 1e-5f)
        w.step((Math.PI / 2).toFloat())
        assertEquals(0.7f, w.x, 1e-5f)
    }
}
