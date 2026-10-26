package com.allnetworktools

import com.allnetworktools.data.sdr.ModeAc
import com.allnetworktools.data.sdr.ModeAcDemodulator
import com.allnetworktools.data.sdr.ModeAcReply
import kotlin.math.roundToInt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeAcTest {
    @Test fun squawkAndAltitudeCodes() {
        for (sq in listOf(7000, 1200, 7700, 4521, 2)) assertEquals(sq, ModeAc.squawkOf(ModeAc.bitsOfSquawk(sq)))
        for (ft in listOf(-1200, 0, 3500, 10_000, 35_000, 41_000)) assertEquals(ft, ModeAc.altitudeOf(ModeAc.bitsOfAltitude(ft)))
        // The C digit of 7000, 7700 and co. is 0: never an altitude.
        assertNull(ModeAc.altitudeOf(ModeAc.bitsOfSquawk(7700)))
        assertNull(ModeAc.altitudeOf(ModeAc.bitsOfSquawk(7000)))
    }

    /** Replies at 2 MS/s: each 0.45 µs pulse spread over the samples it covers, a carrier offset, noise between replies. */
    private fun capture(replies: List<Pair<Int, Boolean>>, seed: Int = 1): ByteArray {
        val rate = 2e6
        val rnd = Random(seed)
        val n = 400_000
        val env = DoubleArray(n)
        replies.forEachIndexed { i, (bits, spi) ->
            val t0 = 60_000 + i * 70_000 + 0.37 * i
            val slots = (0..14).filter { it == 0 || it == 14 || (it in 1..13 && ModeAc.slotBit(bits, it)) } + (if (spi) listOf(17) else emptyList())
            for (s in slots) {
                val a = t0 + s * 1.45 * 2.0
                val b = a + 0.45 * 2.0
                var k = a.toInt()
                while (k < b) { env[k] += minOf(b, k + 1.0) - maxOf(a, k.toDouble()); k++ }
            }
        }
        val out = ByteArray(2 * n)
        var ph = 0.0
        for (k in 0 until n) {
            ph += 0.05
            val amp = 90 * env[k]
            out[2 * k] = (amp * kotlin.math.cos(ph) + rnd.nextInt(-2, 3)).roundToInt().coerceIn(-127, 127).toByte()
            out[2 * k + 1] = (amp * kotlin.math.sin(ph) + rnd.nextInt(-2, 3)).roundToInt().coerceIn(-127, 127).toByte()
        }
        return out
    }

    @Test fun findsRepliesAt2Msps() {
        val sent = listOf(ModeAc.bitsOfSquawk(7700) to false, ModeAc.bitsOfAltitude(35_000) to true, ModeAc.bitsOfSquawk(4521) to false)
        val got = ArrayList<ModeAcReply>()
        val d = ModeAcDemodulator { got += it }
        val buf = capture(sent)
        var i = 0
        while (i < buf.size) { val k = minOf(131_072, buf.size - i); d.feed(buf.copyOfRange(i, i + k), k); i += k }
        assertEquals(got.toString(), sent.map { it.first }, got.map { it.bits })
        assertEquals(listOf(false, true, false), got.map { it.spi })
    }

    @Test fun noiseAlone() {
        val got = ArrayList<ModeAcReply>()
        val d = ModeAcDemodulator { got += it }
        val buf = capture(emptyList(), seed = 9)
        d.feed(buf, buf.size)
        assertTrue(got.isEmpty())
    }
}
