package com.allnetworktools

import com.allnetworktools.data.sdr.Wmbus
import com.allnetworktools.data.sdr.WmbusReceiver
import com.allnetworktools.data.sdr.WmbusTelegram
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Meter frames built from EN 13757-3/-4, sent as T1 and C1 through the whole receiver. */
class WmbusTest {
    private fun body(mfr: String, id: String, type: Int, config: Int, records: IntArray): IntArray {
        val a = IntArray(4) { i -> id.substring(6 - 2 * i, 8 - 2 * i).toInt(16) }
        val m = Wmbus.mfrOf(mfr)
        return intArrayOf(0x44, m and 0xFF, m shr 8) + a + intArrayOf(0x1B, type, 0x7A, 0x2A, 0x00, config and 0xFF, config shr 8) + records
    }

    private val water = body(
        "KAM", "12345678", 0x16, 0,
        intArrayOf(
            0x0C, 0x13, 0x56, 0x34, 0x12, 0x00, // volume, 8-digit BCD, litres: 123.456 m³
            0x02, 0x5A, 0xEB, 0x00, // flow temperature, 0.1 °C: 23.5 °C
            0x42, 0x6C, 0x3F, 0x3C, // storage 1, date: 2025-12-31
            0x02, 0xFD, 0x74, 0x10, 0x0E, // battery days: 3600
        ),
    )
    private val heat = body("TCH", "87654321", 0x04, 0x0510, IntArray(24) { (it * 37) and 0xFF })

    @Test
    fun crcAndCoding() {
        // Every nibble's 3-out-of-6 word has three ones and decodes back.
        for (n in 0..15) { assertEquals(3, Integer.bitCount(Wmbus.encode36(n))); assertEquals(n, Wmbus.decode36(Wmbus.encode36(n))) }
        assertEquals(-1, Wmbus.decode36(0x15))
        val a = Wmbus.buildFrame(water, false)
        assertEquals(Wmbus.airLength(water.size, false), a.size)
        val f = Wmbus.parse(a, false)
        assertNotNull(f)
        f!!
        assertEquals("KAM", f.mfr)
        assertEquals("12345678", f.id)
        assertEquals(0x16, f.type)
        assertEquals(false, f.encrypted)
        assertEquals(listOf("123.456", "23.5", "2025-12-31", "3600"), f.records.map { it.value })
        assertEquals(listOf(Wmbus.Q.VOLUME, Wmbus.Q.FLOW_T, Wmbus.Q.DATE, 32), f.records.map { it.quantity })
        assertEquals("m³", f.records[0].unit)
        assertEquals(1L, f.records[2].storage)
        // One flipped bit fails a block CRC.
        val bad = a.copyOf().also { it[14] = it[14] xor 4 }
        assertNull(Wmbus.parse(bad, false))
        val b = Wmbus.parse(Wmbus.buildFrame(heat, true), true)
        assertTrue(b!!.encrypted)
        assertTrue(b.records.isEmpty())
    }

    /** HackRF bytes at 2 MS/s, tuned 500 kHz below 868.95 MHz, the meter 8 kHz off. */
    private fun air(list: List<Pair<IntArray, Wmbus.Mode>>, seed: Int = 5): ByteArray {
        val rate = 2_000_000.0
        val spc = 20
        val gap = 120_000
        val rnd = Random(seed)
        val segs = list.map { (body, mode) -> Wmbus.chips(Wmbus.buildFrame(body, mode == Wmbus.Mode.C1B), mode, preamble = 19) }
        val n = gap + segs.sumOf { it.size * spc + 120_000 }
        val out = ByteArray(2 * n)
        var ph = 0.0
        var i = 0
        fun put(re: Double, im: Double) {
            out[2 * i] = (re * 50 + (rnd.nextDouble() - 0.5) * 16).roundToInt().coerceIn(-127, 127).toByte()
            out[2 * i + 1] = (im * 50 + (rnd.nextDouble() - 0.5) * 16).roundToInt().coerceIn(-127, 127).toByte()
            i++
        }
        repeat(gap) { put(0.0, 0.0) }
        for (chips in segs) {
            for (c in chips) repeat(spc) {
                ph += 2 * PI * (508_000.0 + if (c == 1) 50_000.0 else -50_000.0) / rate
                put(cos(ph), sin(ph))
            }
            repeat(120_000) { put(0.0, 0.0) }
        }
        return out
    }

    @Test
    fun receivesT1AndC1() {
        val got = ArrayList<WmbusTelegram>()
        val rx = WmbusReceiver { got += it }
        val bytes = air(listOf(water to Wmbus.Mode.T1, heat to Wmbus.Mode.C1A, water to Wmbus.Mode.C1B))
        var o = 0
        while (o < bytes.size) { val k = minOf(131072, bytes.size - o); rx.feed(bytes.copyOfRange(o, o + k), k); o += k }
        assertEquals(listOf(Wmbus.Mode.T1, Wmbus.Mode.C1A, Wmbus.Mode.C1B), got.map { it.mode })
        assertEquals(listOf("12345678", "87654321", "12345678"), got.map { it.frame.id })
        assertEquals("123.456", got[0].frame.records[0].value)
        assertTrue(got[1].frame.encrypted)
        assertTrue(got.all { it.snrDb > 10 })
    }

    @Test
    fun noiseAloneGivesNothing() {
        val got = ArrayList<WmbusTelegram>()
        val rx = WmbusReceiver { got += it }
        val rnd = Random(9)
        val b = ByteArray(2 * 2_000_000) { (rnd.nextInt(33) - 16).toByte() }
        var o = 0
        while (o < b.size) { val k = minOf(131072, b.size - o); rx.feed(b.copyOfRange(o, o + k), k); o += k }
        assertTrue(got.isEmpty())
    }
}
