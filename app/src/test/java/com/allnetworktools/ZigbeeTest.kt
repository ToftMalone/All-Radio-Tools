package com.allnetworktools

import com.allnetworktools.data.sdr.Zigbee
import com.allnetworktools.data.sdr.ZigbeeHeard
import com.allnetworktools.data.sdr.ZigbeeReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class ZigbeeTest {
    @Test fun frameLayer() {
        assertEquals(0x2189, Zigbee.crc16("123456789".toByteArray(), 9))
        // A Zigbee NWK data frame in the clear, Home Automation On/Off from 0x1234 to the coordinator.
        val nwk = byteArrayOf(0x08, 0x00, 0x00, 0x00, 0x34, 0x12, 0x1E, 0x05)
        val aps = byteArrayOf(0x00, 0x01, 0x06, 0x00, 0x04, 0x01, 0x01, 0x07)
        val f = Zigbee.parse(Zigbee.buildMac(Zigbee.DATA, 42, 0x1A62, 0x0000, 0x1234, nwk + aps + byteArrayOf(0x01, 0x02, 0x03)))!!
        assertEquals(0x1A62, f.pan)
        assertEquals("0x1234", f.src)
        assertEquals(42, f.seq)
        assertTrue(f.info, f.info.contains("On/Off") && f.info.contains("Home Automation"))
        val enc = Zigbee.parse(Zigbee.buildMac(Zigbee.DATA, 1, 0x1A62, 0x0000, 0x1234, byteArrayOf(0x08, 0x02, 0, 0, 0x34, 0x12, 0x1E, 0x05, 0x28, 1, 2, 3)))!!
        assertTrue(enc.encrypted)
        assertEquals("Texas Instruments", Zigbee.vendor(0x00124B0001020304L))
    }

    /** O-QPSK as IEEE 802.15.4 defines it: even chips on I, odd chips on Q one chip later, half-sine pulses two chips long. */
    private fun modulate(psdu: ByteArray, fs: Double, cfoHz: Double, delay: Int): Pair<DoubleArray, DoubleArray> {
        val bytes = byteArrayOf(0, 0, 0, 0, 0xA7.toByte(), psdu.size.toByte()) + psdu
        val syms = bytes.flatMap { listOf(it.toInt() and 15, (it.toInt() shr 4) and 15) }
        val chips = syms.flatMap { s -> (0 until 32).map { n -> if ((Zigbee.CHIPS[s] ushr (31 - n)) and 1 == 1) 1.0 else -1.0 } }
        val tc = fs / 2_000_000.0
        val n = ((chips.size + 4) * tc).toInt() + delay + 2000
        val re = DoubleArray(n); val im = DoubleArray(n)
        for (i in delay until n) {
            val t = (i - delay) / tc
            var a = 0.0; var b = 0.0
            val m = t.toInt()
            for (c in maxOf(0, m - 2)..minOf(chips.size - 1, m)) {
                val u = t - c
                if (u in 0.0..2.0) { val p = sin(PI * u / 2); if (c % 2 == 0) a += chips[c] * p else b += chips[c] * p }
            }
            val ph = 2 * PI * cfoHz * i / fs
            re[i] = a * cos(ph) - b * sin(ph); im[i] = a * sin(ph) + b * cos(ph)
        }
        return re to im
    }

    @Test fun hearsFramesThroughTheHackRf() {
        val beacon = Zigbee.buildMac(Zigbee.BEACON, 7, 0x1A62, 0xFFFF, 0x0000, byteArrayOf(0xFF.toByte(), 0xCF.toByte(), 0x00, 0x00, 0x00, 0x22, 0x84.toByte(), 1, 2, 3, 4, 5, 6, 7, 8, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00))
        val data = Zigbee.buildMac(Zigbee.DATA, 9, 0x1A62, 0x0000, 0x4321, byteArrayOf(0x08, 0x02, 0, 0, 0x21, 0x43, 0x1E, 0x05, 0x28, 1, 2, 3, 4, 5, 6, 7, 8, 9))
        for ((cfo, seed) in listOf(0.0 to 1L, 60_000.0 to 2L, -85_000.0 to 3L)) {
            val got = ArrayList<ZigbeeHeard>()
            val rx = ZigbeeReceiver(15) { got += it }
            val rnd = Random(seed)
            for ((psdu, delay) in listOf(beacon to 3001, data to 517)) {
                val (re, im) = modulate(psdu, 4_000_000.0, cfo, delay)
                val buf = ByteArray(2 * re.size)
                for (i in re.indices) {
                    buf[2 * i] = (40 * re[i] + 6 * rnd.nextGaussian() + 3).toInt().coerceIn(-127, 127).toByte()
                    buf[2 * i + 1] = (40 * im[i] + 6 * rnd.nextGaussian() - 2).toInt().coerceIn(-127, 127).toByte()
                }
                val fr = FloatArray(65536); val fi = FloatArray(65536)
                rx.feedBytes(buf, buf.size, fr, fi)
            }
            assertEquals("cfo $cfo", 2, got.size)
            assertTrue("cfo $cfo: ${got[0].frame.info}", got[0].frame.info.startsWith("Zigbee beacon"))
            assertEquals(0x0807060504030201L, got[0].frame.extPanId)
            assertTrue(got[1].frame.encrypted)
            assertEquals(0x4321, got[1].frame.nwkSrc)
        }
    }
}
