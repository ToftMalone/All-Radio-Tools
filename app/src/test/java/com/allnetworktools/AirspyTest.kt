package com.allnetworktools

import com.allnetworktools.data.sdr.AirspyIq
import com.allnetworktools.data.sdr.IqResampler
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirspyTest {
    /** A real tone sampled at 5 MS/s, 20 kHz above fs/4 inside the band: after conversion a single complex tone at +/-20 kHz. */
    @Test fun realSamplesBecomeASingleSidebandIq() {
        val fs = 5_000_000.0
        val f = fs / 4 + 20_000
        val n = 8192
        val real = ShortArray(n) { (2000.0 * 16 * cos(2 * PI * f * it / fs)).toInt().toShort() }
        AirspyIq().process(real, n)
        // After 200 pairs the filters have settled; the envelope of a one-sided tone is constant.
        val mags = (200 until n / 2).map { hypot(real[2 * it].toDouble(), real[2 * it + 1].toDouble()) }
        val mean = mags.average()
        assertTrue("level $mean", mean > 10_000)
        assertTrue("ripple ${mags.max() / mags.min()}", mags.max() / mags.min() < 1.1)
    }

    @Test fun resamplingKeepsTheToneAndItsLevel() {
        val inRate = 2_500_000
        val outRate = 2_000_000
        val f = 100_000.0
        val n = 20_000
        val src = ShortArray(2 * n)
        for (k in 0 until n) { src[2 * k] = (8000 * cos(2 * PI * f * k / inRate)).toInt().toShort(); src[2 * k + 1] = (8000 * sin(2 * PI * f * k / inRate)).toInt().toShort() }
        val rs = IqResampler(inRate, outRate)
        assertEquals(4, rs.up); assertEquals(5, rs.down)
        val out = ShortArray(2 * n)
        val m = rs.process(src, src.size, out)
        val pairs = m / 2
        assertEquals((n.toLong() * outRate / inRate).toDouble(), pairs.toDouble(), 2.0)
        val start = 100
        var dphi = 0.0
        var amp = 0.0
        for (k in start + 1 until pairs) {
            val a = atan2(out[2 * k + 1].toDouble(), out[2 * k].toDouble()) - atan2(out[2 * k - 1].toDouble(), out[2 * k - 2].toDouble())
            var d = a
            while (d > PI) d -= 2 * PI
            while (d < -PI) d += 2 * PI
            dphi += d
            amp += hypot(out[2 * k].toDouble(), out[2 * k + 1].toDouble())
        }
        val steps = pairs - start - 1
        assertEquals(2 * PI * f / outRate, dphi / steps, 1e-3)
        assertEquals(8000.0, amp / steps, 400.0)
    }

    @Test fun passThroughWhenTheRatesMatch() {
        val rs = IqResampler(2_000_000, 2_000_000)
        val src = shortArrayOf(1, 2, 3, 4)
        val out = ShortArray(4)
        assertEquals(4, rs.process(src, 4, out))
        assertTrue(out.contentEquals(src))
    }

    @Test fun threeToTwoIsPossibleButNotAnAwkwardRatio() {
        assertTrue(IqResampler.possible(3_000_000, 2_000_000))
        assertTrue(!IqResampler.possible(2_500_000, 1_999_999))
    }
}
