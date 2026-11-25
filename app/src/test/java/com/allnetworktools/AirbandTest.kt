package com.allnetworktools

import com.allnetworktools.data.sdr.AirbandScanner
import com.allnetworktools.ui.pages.sdr.AirbandController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class AirbandTest {
    @Test fun rasterAndNames() {
        val ch = AirbandScanner.channelsAround(120_700_000L)
        assertTrue(ch.all { it in 119_800_000L..121_600_000L })
        // 121.5 MHz (distress) and the 8.33 kHz channels around 120.000 are on it.
        assertTrue(ch.contains(121_500_000L))
        assertTrue(ch.contains(120_008_333L))
        assertEquals("120.010", AirbandController.channelName(120_008_333L))
        assertEquals("120.015", AirbandController.channelName(120_016_666L))
        assertEquals("121.500", AirbandController.channelName(121_500_000L))
        // Nothing outside the aviation band.
        assertTrue(AirbandScanner.channelsAround(118_200_000L).all { it >= AirbandScanner.LOW_HZ })
    }

    /** I/Q bytes at 2 MS/s with an AM carrier on each keyed channel (a 1 kHz tone) and noise. */
    private fun chunk(centre: Long, keyed: Map<Long, Double>, t0: Int, n: Int, rnd: java.util.Random): ByteArray {
        val out = ByteArray(2 * n)
        for (i in 0 until n) {
            val t = (t0 + i) / 2_000_000.0
            var re = rnd.nextGaussian() * 2.0
            var im = rnd.nextGaussian() * 2.0
            for ((hz, a) in keyed) {
                val env = a * (1 + 0.6 * sin(2 * PI * 1000 * t))
                val ph = 2 * PI * (hz - centre) * t
                re += env * cos(ph); im += env * sin(ph)
            }
            out[2 * i] = re.roundToInt().coerceIn(-127, 127).toByte()
            out[2 * i + 1] = im.roundToInt().coerceIn(-127, 127).toByte()
        }
        return out
    }

    @Test fun channelsOpenPlayAndClose() {
        val centre = 120_700_000L
        val tower = 120_350_000L // 25 kHz channel
        val approach = 121_008_333L // 8.33 kHz channel ("121.010")
        var now = 1_000L
        var audio = 0
        val opened = ArrayList<Long>()
        val sc = AirbandScanner(centre, onAudio = { _, n -> audio += n }, clock = { now })
        sc.onOpen = { opened += it }
        val rnd = java.util.Random(2)
        var t = 0
        fun run(ms: Int, keyed: Map<Long, Double>) {
            repeat(ms / 33) {
                val n = 65_536
                sc.feed(chunk(centre, keyed, t, n, rnd), 2 * n)
                t += n
                now += 33
            }
        }
        // The tower talks, then the approach, louder.
        run(1_000, mapOf(tower to 12.0))
        var snap = sc.snapshot()
        assertEquals(listOf(tower), snap.map { it.hz })
        assertTrue(snap.single().keyed && snap.single().playing)
        assertTrue("sound played", audio > 0)
        run(1_000, mapOf(tower to 12.0, approach to 25.0))
        snap = sc.snapshot()
        assertEquals(setOf(tower, approach), snap.map { it.hz }.toSet())
        // The tower was playing and is still keyed: it is kept, a reply does not cut it.
        assertEquals(tower, snap.single { it.playing }.hz)
        // The tower stops: after the hang time, the approach is played.
        run(3_000, mapOf(approach to 25.0))
        assertEquals(approach, sc.snapshot().single { it.playing }.hz)
        // Pinned: only the tower, silent now, so nothing plays.
        sc.pinned = tower
        run(500, mapOf(approach to 25.0))
        assertNull(sc.snapshot().firstOrNull { it.playing })
        sc.pinned = null
        assertEquals(listOf(tower, approach), opened)
        // Quiet for more than 30 s: both close.
        run(31_000, emptyMap())
        assertTrue(sc.snapshot().toString(), sc.snapshot().isEmpty())
    }
}
