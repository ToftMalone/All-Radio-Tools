package com.allnetworktools

import com.allnetworktools.data.sdr.IsmReading
import com.allnetworktools.data.sdr.IsmReceiver
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TapoTest {
    /** A GFSK-like burst at 50 kbit/s, [offHz] above the ISM channel's centre, through the HackRF chain. */
    private fun tapoBytes(offHz: Double, bitUs: Double = 20.0): ByteArray {
        val rnd = Random(7)
        val bits = ArrayList<Int>()
        repeat(32) { bits += it and 1 xor 1 } // preamble 1010…
        for (b in listOf(0xD3, 0x91, 0xD3, 0x91)) for (j in 7 downTo 0) bits += (b shr j) and 1 // a sync word
        repeat(160) { bits += rnd.nextInt(2) }
        val lead = 60_000
        val per = 2e6 * bitUs * 1e-6
        val total = lead * 2 + (bits.size * per).roundToInt()
        val out = ByteArray(total * 2)
        var ph = 0.0
        for (i in 0 until total) {
            val k = ((i - lead) / per).toInt()
            val on = i >= lead && k < bits.size
            val f = 500_000 + offHz + if (on) (if (bits[k] == 1) 25_000.0 else -25_000.0) else 0.0
            ph += 2 * PI * f / 2e6
            val a = if (on) 50.0 else 0.0
            out[2 * i] = (a * cos(ph) + rnd.nextInt(-3, 4)).roundToInt().toByte()
            out[2 * i + 1] = (a * sin(ph) + rnd.nextInt(-3, 4)).roundToInt().toByte()
        }
        return out
    }

    private fun ism(bytes: ByteArray, bandHz: Double): List<IsmReading> {
        val got = ArrayList<IsmReading>()
        val rx = IsmReceiver({ r, _ -> got += r }, tunedHz = bandHz - 500_000)
        var i = 0
        while (i < bytes.size) { val k = minOf(131_072, bytes.size - i); rx.feed(bytes.copyOfRange(i, i + k), k); i += k }
        return got
    }

    @Test fun tapoKnownByItsSignature() {
        // 868.35 MHz heard on the 868.3 MHz band.
        val r = ism(tapoBytes(50_000.0), 868.3e6).single()
        assertEquals("TP-Link Tapo", r.model)
        assertEquals(3, r.channel)
        // The same burst on another frequency, or at another rate, is not a Tapo.
        assertTrue(ism(tapoBytes(-40_000.0), 868.3e6).none { it.model == "TP-Link Tapo" })
        assertTrue(ism(tapoBytes(50_000.0, bitUs = 26.0), 868.3e6).none { it.model == "TP-Link Tapo" })
    }
}
