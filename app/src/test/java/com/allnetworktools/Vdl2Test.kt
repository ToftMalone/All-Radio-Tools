package com.allnetworktools

import com.allnetworktools.data.sdr.Vdl2
import com.allnetworktools.data.sdr.Vdl2Message
import com.allnetworktools.data.sdr.Vdl2Receiver
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bursts built as ICAO Doc 9776 lays them out, through the code layers and then as D8PSK through the receiver. */
class Vdl2Test {
    private val aircraft = Vdl2.Addr(0x3C6586, 1)
    private val ground = Vdl2.Addr(0x10916D, 4)

    private fun frames() = listOf(
        Vdl2.frame(ground, aircraft, 0x03, Vdl2.acarsInfo(".D-AIBL", "H1", '2', "M01ALH4AB/POS N48.1 E011.5")),
        Vdl2.frame(Vdl2.Addr(0xFFFFFF, 7), ground, 0xAF, Vdl2.xidGroundStation(48.4, 11.8, "EDDMEDMA")),
    )

    @Test
    fun codeLayers() {
        for (len in listOf(1, 100, 4000, 0x1FFF)) assertEquals(len, Vdl2.headerDecode(Vdl2.headerWord(len)))
        // One wrong header bit is corrected.
        assertEquals(1234, Vdl2.headerDecode(Vdl2.headerWord(1234) xor (1 shl 9)))
        val r = Random(3)
        val data = IntArray(Vdl2.RS_K) { r.nextInt(256) }
        val cw = data + Vdl2.rsEncode(data)
        val bad = cw.copyOf().also { it[5] = it[5] xor 0x55; it[100] = it[100] xor 1; it[250] = it[250] xor 0xFF }
        assertEquals(3, Vdl2.rsDecode(bad, 6))
        assertTrue(bad.contentEquals(cw))
        // The whole burst body, with a few octets hit in the air.
        val bits = Vdl2.hdlcBits(frames())
        val air = Vdl2.burstBits(bits)
        for (i in listOf(33, 34, 300, 777)) air[i] = air[i] xor 1 // three octets: what six parity octets can fix
        Vdl2.scramble(air)
        var w = 0
        for (i in 0 until Vdl2.HEADER_BITS) w = (w shl 1) or air[i]
        val len = Vdl2.headerDecode(w)
        assertEquals(bits.size, len)
        val back = Vdl2.burstData(air, Vdl2.HEADER_BITS, Vdl2.layout(len)!!, len)
        assertNotNull(back)
        val fr = Vdl2.hdlcFrames(back!!)
        assertEquals(2, fr.size)
        assertTrue(fr.all { Vdl2.fcsOk(it) })
        val m = Vdl2.parse(fr[0].copyOf(fr[0].size - 2), 136_975_000, 0, 20.0)!!
        assertEquals(Vdl2Message.Kind.Acars, m.kind)
        assertEquals("3C6586", m.srcHex)
        assertTrue(m.fromAircraft)
        assertEquals("H1", m.acars!!.label)
        assertEquals(".D-AIBL".trim('.'), m.acars!!.registration)
        val x = Vdl2.parse(fr[1].copyOf(fr[1].size - 2), 136_975_000, 0, 20.0)!!
        assertEquals(Vdl2Message.Kind.Xid, x.kind)
        assertEquals("EDDMEDMA", x.airports)
        assertEquals(48.4, x.position!!.first, 0.05)
        assertEquals(11.8, x.position!!.second, 0.05)
    }

    /** HackRF bytes at 2 MS/s tuned 500 kHz below 136.85 MHz, one burst on 136.975 MHz, 300 Hz off, in noise. */
    @Test
    fun receivesBurst() {
        val air = Vdl2.burstBits(Vdl2.hdlcBits(frames()))
        val syms = ArrayList<Int>()
        Vdl2.preamblePhase.forEach { syms += ((it % 8) + 8) % 8 }
        var ph = syms.last()
        var i = 0
        while (i < air.size) {
            val b = (air[i] shl 2) or (air.getOrElse(i + 1) { 0 } shl 1) or air.getOrElse(i + 2) { 0 }
            ph = (ph + Vdl2.stepForBits(b)) % 8
            syms += ph
            i += 3
        }
        val rate = 2_000_000.0
        val sps = rate / Vdl2.SYMBOL_RATE
        val gap = 300_000
        val n = 2 * gap + (syms.size * sps).roundToInt()
        val out = ByteArray(2 * n)
        val rnd = Random(8)
        val off = 500_000.0 + (136_975_000 - 136_850_000) + 300.0
        for (k in 0 until n) {
            val tSym = (k - gap) / sps
            var re = 0.0
            var im = 0.0
            if (tSym >= 0 && tSym < syms.size - 1) {
                val j = tSym.toInt()
                val f = tSym - j
                val a = syms[j] * PI / 4
                val b = syms[j + 1] * PI / 4
                re = (1 - f) * cos(a) + f * cos(b); im = (1 - f) * sin(a) + f * sin(b)
            }
            val c = 2 * PI * off * k / rate
            val r = re * cos(c) - im * sin(c)
            val q = re * sin(c) + im * cos(c)
            out[2 * k] = (r * 40 + (rnd.nextDouble() - 0.5) * 20).roundToInt().toByte()
            out[2 * k + 1] = (q * 40 + (rnd.nextDouble() - 0.5) * 20).roundToInt().toByte()
        }
        val got = ArrayList<Vdl2Message>()
        val rx = Vdl2Receiver { got += it }
        var o = 0
        while (o < out.size) { val k = minOf(131072, out.size - o); rx.feed(out.copyOfRange(o, o + k), k); o += k }
        assertEquals(2, got.size)
        assertTrue(got.all { it.freqHz == 136_975_000L })
        assertEquals("H1", got[0].acars?.label)
        assertEquals("EDDMEDMA", got[1].airports)
    }
}
