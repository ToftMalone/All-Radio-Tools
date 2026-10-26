package com.allnetworktools

import com.allnetworktools.data.sdr.Dsc
import com.allnetworktools.data.sdr.DscCall
import com.allnetworktools.data.sdr.DscDecoder
import com.allnetworktools.data.sdr.DscReceiver
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Calls built as M.493 lays them out, decoded from bits and from a synthetic channel 70 signal. */
class DscTest {
    private val distress = Dsc.distressAlert("227123450", 105, 47.25, -2.5, 14, 32)
    private val individual = Dsc.individual("002275300", 100, "227123450", 100, 126, 16)

    private fun decodeBits(bits: IntArray, flip: Set<Int> = emptySet()): List<DscCall> {
        val out = ArrayList<DscCall>()
        val d = DscDecoder { out += it }
        repeat(37) { d.bit(if (it % 2 == 0) 1f else -1f) }
        bits.forEachIndexed { i, b -> val v = if (i in flip) 1 - b else b; d.bit(if (v == 1) 1f else -1f) }
        repeat(40) { d.bit(-1f) }
        return out
    }

    @Test
    fun symbols() {
        for (s in 0..127) assertEquals(s, Dsc.decode(Dsc.bits(s)))
        assertEquals(-1, Dsc.decode(Dsc.bits(42) xor 0x100))
        assertEquals(47.25 to -2.5, Dsc.position(Dsc.positionSymbols(47.25, -2.5)))
        assertEquals("VHF 16", Dsc.frequency(Dsc.vhfChannelSymbols(16)))
        assertEquals(227, Dsc.mid("227123450"))
        assertEquals(227, Dsc.mid("002275300"))
    }

    @Test
    fun distressAlertFromBits() {
        val calls = decodeBits(Dsc.frameBits(distress, Dsc.EOS_OTHER))
        assertEquals(1, calls.size)
        val c = calls[0]
        assertTrue(c.distress)
        assertEquals(0, Dsc.kind(c))
        assertEquals("227123450", c.from)
        assertEquals(105, c.nature)
        assertEquals(47.25, c.position!!.first, 1e-9)
        assertEquals(-2.5, c.position!!.second, 1e-9)
        assertEquals(14 to 32, c.utc)
    }

    @Test
    fun timeDiversityRepairsDamage() {
        val bits = Dsc.frameBits(individual, Dsc.EOS_ACK_RQ)
        // Break the DX copy of the called MMSI's first symbol and the RX copy of the caller's last one.
        val dx = 20 + (12 + 2 * 2) * 10 + 3
        val rx = 20 + (12 + 2 * 12 + 5) * 10 + 6
        val calls = decodeBits(bits, setOf(dx, rx))
        assertEquals(1, calls.size)
        val c = calls[0]
        assertEquals("002275300", c.to)
        assertEquals("227123450", c.from)
        assertEquals("VHF 16", c.channel)
        assertEquals(12, Dsc.kind(c))
        assertTrue(Dsc.isCoast(c.to))
    }

    /** HackRF bytes at 2 MS/s tuned 500 kHz below channel 70: FM ±3 kHz carrying the 1300 / 2100 Hz tones. */
    @Test
    fun receivesFromRadio() {
        val bits = Dsc.frameBits(Dsc.allShips("227123450", 108, 100, 16), Dsc.EOS_OTHER, dots = 200)
        val rate = 2_000_000.0
        val spb = rate / 1200
        val rnd = Random(4)
        val gap = 200_000
        val n = gap * 2 + (bits.size * spb).roundToInt()
        val out = ByteArray(2 * n)
        var carrier = 0.0
        var tone = 0.0
        for (i in 0 until n) {
            val k = i - gap
            var dev = 0.0
            if (k >= 0 && k < bits.size * spb) {
                val b = bits[(k / spb).toInt()]
                tone += 2 * PI * (if (b == 1) 1300.0 else 2100.0) / rate
                dev = 3000.0 * sin(tone)
            }
            carrier += 2 * PI * (500_000.0 + 1_500.0 + dev) / rate
            out[2 * i] = (cos(carrier) * 40 + (rnd.nextDouble() - 0.5) * 30).roundToInt().toByte()
            out[2 * i + 1] = (sin(carrier) * 40 + (rnd.nextDouble() - 0.5) * 30).roundToInt().toByte()
        }
        val calls = ArrayList<DscCall>()
        val rx = DscReceiver { calls += it }
        var o = 0
        while (o < out.size) { val k = minOf(131072, out.size - o); rx.feed(out.copyOfRange(o, o + k), k); o += k }
        assertEquals(1, calls.size)
        assertNotNull(calls[0].from)
        assertEquals("227123450", calls[0].from)
        assertEquals(11, Dsc.kind(calls[0]))
        assertEquals("VHF 16", calls[0].channel)
    }
}
