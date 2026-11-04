package com.allnetworktools

import com.allnetworktools.data.sdr.Ant
import com.allnetworktools.data.sdr.AntHeard
import com.allnetworktools.data.sdr.AntReceiver
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

class AntTest {
    @Test fun profiles() {
        val hr = Ant.decode(Ant.HEART_RATE, byteArrayOf(0x84.toByte(), 0x30, 0x21, 0x10, 0x00, 0x30, 0x8F.toByte(), 72))
        assertEquals(72, hr.heartRate)
        assertEquals("Previous beat", hr.pageName)
        val pw = Ant.decode(Ant.BIKE_POWER, byteArrayOf(0x10, 5, 0xB2.toByte(), 90, 0x10, 0x27, 0xFA.toByte(), 0x00))
        assertEquals(250, pw.powerW); assertEquals(90, pw.cadenceRpm)
        assertEquals("Garmin", Ant.decode(Ant.HEART_RATE, byteArrayOf(0x82.toByte(), 1, 0x39, 0x30, 0, 0, 1, 70)).maker)
        assertEquals("Wahoo Fitness", Ant.maker(32))
    }

    @Test fun hearsSensorsThroughTheHackRf() {
        val fs = 4_000_000.0
        val frames = listOf(
            Triple(4321, Ant.HEART_RATE, byteArrayOf(0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x12, 0x34, 9, 140.toByte())),
            Triple(777, Ant.BIKE_POWER, byteArrayOf(0x10, 5, 0xFF.toByte(), 88, 0x10, 0x27, 0x2C, 0x01)),
        )
        for ((cfo, seed) in listOf(0.0 to 1L, 70_000.0 to 2L, -90_000.0 to 3L)) {
            val got = ArrayList<AntHeard>()
            val rx = AntReceiver { got += it }
            val rnd = Random(seed)
            for ((num, type, payload) in frames) {
                val bits = Ant.bits(Ant.ANT_PLUS, num, type, 5, payload)
                // GFSK, BT 0.5, ±160 kHz.
                val sps = 4
                val nrz = DoubleArray((bits.size + 8) * sps) { val b = it / sps - 4; if (b in bits.indices) (if (bits[b] == 1) 1.0 else -1.0) else 0.0 }
                val g = DoubleArray(4 * sps + 1) { val t = (it - 2 * sps) / sps.toDouble(); exp(-2 * (PI * 0.5 * t) * (PI * 0.5 * t) / kotlin.math.ln(2.0)) }
                val gs = g.sum()
                val pad = 3000
                val re = DoubleArray(nrz.size + 2 * pad); val im = DoubleArray(re.size)
                var ph = 0.0
                for (i in re.indices) {
                    var f = 0.0
                    val j = i - pad
                    if (j >= 0 && j < nrz.size) for (t in g.indices) { val q = j + t - 2 * sps; if (q in nrz.indices) f += nrz[q] * g[t] / gs }
                    ph += 2 * PI * (cfo + 160_000.0 * f) / fs
                    val on = j in 0 until nrz.size
                    re[i] = if (on) cos(ph) else 0.0; im[i] = if (on) sin(ph) else 0.0
                }
                val buf = ByteArray(2 * re.size)
                for (i in re.indices) {
                    buf[2 * i] = (40 * re[i] + 5 * rnd.nextGaussian() + 2).toInt().coerceIn(-127, 127).toByte()
                    buf[2 * i + 1] = (40 * im[i] + 5 * rnd.nextGaussian()).toInt().coerceIn(-127, 127).toByte()
                }
                rx.feedBytes(buf, buf.size, FloatArray(65536), FloatArray(65536))
            }
            assertEquals("cfo $cfo: ${got.map { it.frame.deviceNumber }}", 2, got.size)
            assertEquals(4321, got[0].frame.deviceNumber)
            assertEquals(140, Ant.decode(got[0].frame.type, got[0].frame.payload).heartRate)
            assertEquals(300, Ant.decode(got[1].frame.type, got[1].frame.payload).powerW)
        }
        assertEquals(0.0, sqrt(0.0), 0.0)
    }
}
