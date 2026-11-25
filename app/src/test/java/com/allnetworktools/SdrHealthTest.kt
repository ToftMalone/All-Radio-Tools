package com.allnetworktools

import com.allnetworktools.data.sdr.HealthJudge
import com.allnetworktools.data.sdr.HealthSample
import com.allnetworktools.data.sdr.SdrAdvice
import com.allnetworktools.ui.pages.wifi.dnsAdvice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SdrHealthTest {
    private val sec = 1_000_000_000L

    /** One minute-long run at [rate], a sample a second: [deliver] the share of samples arriving, [drop] the share dropped, frames per second. */
    private fun run(
        seconds: Int, rate: Int = 2_000_000, deliver: Double = 1.0, drop: Double = 0.0,
        good: (Int) -> Int = { 0 }, bad: (Int) -> Int = { 0 }, adsb: Boolean = false,
    ): List<SdrAdvice?> {
        val j = HealthJudge(adsb)
        var g = 0L; var b = 0L; var d = 0L; var x = 0L
        val out = ArrayList<SdrAdvice?>()
        for (t in 0..seconds) {
            out += j.judge(HealthSample(t * sec + 10 * sec, 1, 10 * sec, rate, g, b, d, x))
            d += (2.0 * rate * deliver).toLong()
            x += (2.0 * rate * deliver * drop).toLong()
            g += good(t); b += bad(t)
        }
        return out
    }

    @Test fun healthyReceptionSaysNothing() {
        val v = run(200, good = { if (it % 3 == 0) 1 else 0 }, bad = { if (it % 20 == 0) 1 else 0 })
        assertEquals(listOf<SdrAdvice?>(null), v.distinct())
    }

    @Test fun usbAndPhone() {
        assertEquals(SdrAdvice.SamplesLostUsb, run(20, deliver = 0.6).last())
        assertEquals(SdrAdvice.SamplesLostPhone, run(20, drop = 0.1).last())
        // A slight shortfall (an RTL-SDR's rate a little off) is not a loss.
        assertNull(run(20, deliver = 0.97).last())
    }

    @Test fun framesNothingAndDamaged() {
        val nothing = run(60, bad = { if (it % 6 == 0) 1 else 0 })
        assertEquals(SdrAdvice.NothingPasses, nothing.last())
        // Not before 30 s of reception.
        assertNull(nothing[20])
        assertEquals(SdrAdvice.Damaged, run(60, good = { if (it % 5 == 0) 1 else 0 }, bad = { if (it % 3 == 0) 1 else 0 }).last())
    }

    @Test fun adsbNoAircraft() {
        assertEquals(SdrAdvice.NoAircraft, run(130, adsb = true).last())
        assertNull(run(100, adsb = true).last())
        assertNull(run(130, adsb = true, good = { if (it == 100) 1 else 0 }).last())
        // Another tool with nothing decoded: no advice (a quiet band is normal).
        assertNull(run(130).last())
    }

    @Test fun oneBadSecondDoesNotFlash() {
        val j = HealthJudge()
        var d = 0L
        var seen = 0
        for (t in 0..30) {
            if (j.judge(HealthSample(t * sec + 10 * sec, 1, 10 * sec, 1_000_000, 0, 0, d, 0)) != null) seen++
            // One second short in the middle of a good run.
            d += if (t == 15) 0L else 2_000_000L
        }
        assertEquals(0, seen)
    }

    @Test fun dnsAdviceChoices() {
        assertEquals(Triple("Quad9", 12f, 180f), dnsAdvice(180f, mapOf("Cloudflare" to 15f, "Quad9" to 12f, "Google" to null)))
        assertEquals(Triple("Cloudflare", 15f, null), dnsAdvice(null, mapOf("Cloudflare" to 15f)))
        // Twice as slow but only 10 ms more: not worth it.
        assertNull(dnsAdvice(20f, mapOf("Cloudflare" to 10f)))
        assertNull(dnsAdvice(40f, mapOf("Cloudflare" to 25f)))
        assertNull(dnsAdvice(null, mapOf("Cloudflare" to null)))
    }
}
