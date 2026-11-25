package com.allnetworktools

import com.allnetworktools.data.sdr.GainAdvice
import com.allnetworktools.data.sdr.GainAdvisor
import com.allnetworktools.data.sdr.GainProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.roundToInt

class GainAdviceTest {
    /** 8-bit I/Q of gaussian noise at [sigma] (in converter steps), clipped like the converter does. */
    private fun noise(sigma: Double, n: Int, rnd: java.util.Random) = ByteArray(2 * n) { (rnd.nextGaussian() * sigma).roundToInt().coerceIn(-128, 127).toByte() }

    private fun verdict(sigma: Double): GainAdvice? {
        val a = GainAdvisor(GainProfile.Narrow)
        val rnd = java.util.Random(1)
        var t = 0L
        // 2 MS/s for 1.2 s, by 65536-sample blocks.
        repeat(37) { a.feed(noise(sigma, 65_536, rnd), 131_072, t); t += 32_768_000L }
        return a.advice
    }

    @Test fun strongWeakAndRight() {
        assertEquals(GainAdvice.TooStrong, verdict(90.0))
        // Under one step of noise: the floor sinks into the converter's quantisation.
        assertEquals(GainAdvice.TooWeak, verdict(0.3))
        assertEquals(GainAdvice.Good, verdict(6.0))
    }

    @Test fun oneWindowIsNotEnough() {
        val a = GainAdvisor(GainProfile.Narrow)
        val rnd = java.util.Random(2)
        a.feed(noise(90.0, 65_536, rnd), 131_072, 0L)
        a.feed(noise(90.0, 65_536, rnd), 131_072, 250_000_000L)
        // The first window only starts the clock, the second is one verdict: not yet agreed.
        assertNull(a.advice)
    }
}
