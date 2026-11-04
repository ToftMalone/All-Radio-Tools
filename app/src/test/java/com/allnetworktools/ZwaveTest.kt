package com.allnetworktools

import com.allnetworktools.data.sdr.Zwave
import com.allnetworktools.data.sdr.ZwaveHeard
import com.allnetworktools.data.sdr.ZwaveReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class ZwaveTest {
    @Test fun frameLayer() {
        // CRC-16/AUG-CCITT check value, as G.9959 R3 uses it.
        assertEquals(0xE5CC, Zwave.crc16("123456789".toByteArray(), 9))
        for (rate in 1..3) {
            val m = Zwave.build(0xC0FFEE01L, 1, 5, byteArrayOf(0x25, 0x01, 0xFF.toByte()), rate, seq = 9, ackReq = true)
            val f = Zwave.parse(m, rate)!!
            assertTrue(f.checkOk)
            assertEquals("C0FFEE01", f.home)
            assertEquals(1, f.src); assertEquals(5, f.dst); assertEquals(9, f.seq); assertTrue(f.ackReq)
            assertEquals("SWITCH_BINARY Set on", Zwave.describe(f))
            m[10] = (m[10].toInt() xor 0x10).toByte()
            assertFalse(Zwave.parse(m, rate)!!.checkOk)
        }
        val s2 = Zwave.parse(Zwave.build(1, 2, 1, byteArrayOf(0x9F.toByte(), 0x03, 9, 9), 2), 2)!!
        assertEquals("Security S2 encrypted (4 octets)", Zwave.describe(s2))
        val t = Zwave.parse(Zwave.build(1, 2, 1, byteArrayOf(0x31, 0x05, 0x01, 0x22, 0x00, 0xDC.toByte()), 2), 2)!!
        assertEquals("SENSOR_MULTILEVEL Report temperature 22.0 °C", Zwave.describe(t))
    }

    /** One FSK burst per rate (R1 Manchester on 868.42, R2 on 868.40, R3 on 869.85 MHz), as HackRF bytes at 2 MS/s around 869.13 MHz. */
    @Test fun hearsTheThreeRates() {
        val fs = 2_000_000.0
        val rnd = Random(3)
        val bursts = listOf(
            Triple(1, 868_420_000L, Zwave.build(0x0A0B0C0DL, 3, 1, byteArrayOf(0x80.toByte(), 0x03, 0x55), 1)),
            Triple(2, 868_400_000L, Zwave.build(0x0A0B0C0DL, 7, 1, byteArrayOf(0x30, 0x03, 0xFF.toByte()), 2)),
            Triple(3, 869_850_000L, Zwave.build(0x0A0B0C0DL, 9, 1, byteArrayOf(0x25, 0x03, 0x00), 3)),
        )
        val sig = ArrayList<Pair<Double, Double>>()
        repeat((0.05 * fs).toInt()) { sig += 0.0 to 0.0 }
        var phase = 0.0
        for ((rate, hz, mpdu) in bursts) {
            val bytes = ByteArray(if (rate == 3) 24 else 10) { 0x55 } + byteArrayOf(Zwave.sof(rate).toByte()) + mpdu
            val bits = bytes.flatMap { b -> (7 downTo 0).map { (b.toInt() shr it) and 1 } }
            val chips = if (rate == 1) bits.flatMap { if (it == 1) listOf(1, 0) else listOf(0, 1) } else bits
            val spc = fs / (Zwave.chipRate(rate) * (1 + 30e-6))
            val off = (hz - Zwave.EU_CENTRE_HZ).toDouble() + 3_000.0
            val dev = if (rate == 3) 29_000.0 else 20_000.0
            val n = ((chips.size + 2) * spc).toInt()
            for (i in 0 until n) {
                val c = chips[minOf((i / spc).toInt(), chips.size - 1)]
                phase += 2 * PI * (off + dev * (if (c == 1) 1 else -1)) / fs
                sig += 40 * cos(phase) to 40 * sin(phase)
            }
            repeat((0.03 * fs).toInt()) { sig += 0.0 to 0.0 }
        }
        val buf = ByteArray(2 * sig.size)
        for (i in sig.indices) {
            buf[2 * i] = (sig[i].first + 4 * rnd.nextGaussian()).roundToInt().coerceIn(-127, 127).toByte()
            buf[2 * i + 1] = (sig[i].second + 4 * rnd.nextGaussian()).roundToInt().coerceIn(-127, 127).toByte()
        }
        val got = ArrayList<ZwaveHeard>()
        val rx = ZwaveReceiver { got += it }
        val re = FloatArray(65536); val im = FloatArray(65536)
        var i = 0
        while (i < buf.size) { val k = minOf(262144, buf.size - i); rx.feedBytes(buf.copyOfRange(i, i + k), k, re, im); i += k }
        assertEquals(got.map { "${it.rate} ${it.frame.src}" }.toString(), 3, got.size)
        assertEquals(listOf(1, 2, 3), got.map { it.rate })
        assertEquals(listOf(3, 7, 9), got.map { it.frame.src })
        assertEquals("BATTERY Report 85 %", Zwave.describe(got[0].frame))
        assertEquals("SENSOR_BINARY Report triggered", Zwave.describe(got[1].frame))
    }
}
