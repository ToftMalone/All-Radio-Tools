package com.allnetworktools

import com.allnetworktools.data.sdr.ToneSignalling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class ToneSignallingTest {
    private val rate = 50_000.0

    private class Rx {
        val selcalls = ArrayList<Pair<String, String>>()
        val dtmf = ArrayList<String>()
        val dec = ToneSignalling({ s, d -> selcalls += s to d }, { dtmf += it })
    }

    /** Discriminator output: [ms] of the tones given (each a list of frequencies sounding together) with noise. */
    private fun Rx.play(parts: List<Pair<List<Double>, Int>>, rnd: java.util.Random, noise: Double = 200.0, dev: Double = 2500.0) {
        val ph = DoubleArray(2)
        for ((fs, ms) in parts) {
            repeat((rate * ms / 1000).toInt()) {
                var v = rnd.nextGaussian() * noise
                fs.forEachIndexed { k, f -> ph[k] += 2 * PI * f / rate; v += dev / fs.size * sin(ph[k]) }
                dec.push(v.toFloat())
            }
        }
    }

    private fun Rx.quiet(ms: Int, rnd: java.util.Random) = play(listOf(emptyList<Double>() to ms), rnd)

    @Test fun tablesAndRepeatTone() {
        assertEquals(listOf(1060.0, 2600.0, 1160.0, 2200.0, 2600.0), ToneSignalling.tonesOf(ToneSignalling.ZVEI1, "11299"))
        assertEquals("11299", ToneSignalling.digitsOf(ToneSignalling.ZVEI1, ToneSignalling.tonesOf(ToneSignalling.ZVEI1, "11299")))
        // A frequency of no standard's table.
        assertEquals(null, ToneSignalling.digitsOf(ToneSignalling.CCIR, listOf(1124.0, 1600.0, 1275.0, 1358.0)))
    }

    @Test fun fiveToneCallsOfEachStandard() {
        for ((std, digits) in listOf(ToneSignalling.ZVEI1 to "12345", ToneSignalling.CCIR to "90077", ToneSignalling.EEA to "21118", ToneSignalling.ZVEI1 to "00000", ToneSignalling.CCIR to "1234567")) {
            val rx = Rx()
            val rnd = java.util.Random(digits.hashCode().toLong())
            rx.quiet(300, rnd)
            // The sender's tone length a little off (3 %).
            rx.play(ToneSignalling.tonesOf(std, digits).map { listOf(it) to (std.toneMs * 1.03).toInt() }, rnd)
            rx.quiet(500, rnd)
            assertEquals("${std.name} $digits", listOf(std.name to digits), rx.selcalls)
            assertTrue(rx.dtmf.isEmpty())
        }
    }

    @Test fun dtmfString() {
        val rx = Rx()
        val rnd = java.util.Random(4)
        rx.quiet(200, rnd)
        val keys = mapOf('1' to (697.0 to 1209.0), '5' to (770.0 to 1336.0), '#' to (941.0 to 1477.0), '0' to (941.0 to 1336.0), 'A' to (697.0 to 1633.0))
        for (c in "1550#A") {
            val (r, k) = keys[c]!!
            rx.play(listOf(listOf(r, k) to 70), rnd)
            rx.quiet(60, rnd)
        }
        rx.quiet(2_000, rnd)
        assertEquals(listOf("1550#A"), rx.dtmf)
        assertTrue(rx.selcalls.isEmpty())
    }

    @Test fun noiseAndSingleTonesGiveNothing() {
        val rx = Rx()
        val rnd = java.util.Random(9)
        // Carrier-less discriminator noise, then a 1 kHz test tone, then an irregular "voice" of drifting tones.
        rx.play(listOf(emptyList<Double>() to 20_000), rnd, noise = 6_000.0)
        rx.play(listOf(listOf(1000.0) to 3_000), rnd)
        var f = 300.0
        repeat(400) { f = (f + rnd.nextGaussian() * 60).coerceIn(150.0, 900.0); rx.play(listOf(listOf(f, f * 2.3) to 15), rnd, dev = 3000.0) }
        rx.quiet(2_000, rnd)
        assertTrue(rx.selcalls.toString(), rx.selcalls.isEmpty())
        assertTrue(rx.dtmf.toString(), rx.dtmf.isEmpty())
    }
}
