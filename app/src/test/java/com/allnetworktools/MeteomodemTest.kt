package com.allnetworktools

import com.allnetworktools.data.sdr.Meteomodem
import com.allnetworktools.data.sdr.MeteomodemDemodulator
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M10 / M20 frames built after the rs1729/RS layouts, checked against its C check routine, then sent over the air. */
class MeteomodemTest {
    @Test fun checkMatchesTheReferenceRoutine() {
        // Values printed by rs1729's update_checkM10, compiled from its source, over the same bytes.
        val a = ByteArray(99) { (it * 37 + 11).toByte() }
        assertEquals(0x4302, Meteomodem.check(a, 99))
        val b = ByteArray(0x44) { (255 - it * 3).toByte() }
        assertEquals(0x29CF, Meteomodem.check(b, 0x44))
    }

    @Test fun serialNumbersMatchTheReferenceFormat() {
        val m10 = ByteArray(Meteomodem.M10_LEN)
        byteArrayOf(0x52, 0x00, 0x85.toByte(), 0xC2.toByte(), 0x5E).copyInto(m10, 0x5D)
        assertEquals("805-2-27874", Meteomodem.m10Serial(m10)) // C: "805 2 27874"
        val m20 = ByteArray(Meteomodem.M20_LEN)
        byteArrayOf(0x6A, 0x1C, 0x84.toByte()).copyInto(m20, 0x12)
        assertEquals("811-1-10263", Meteomodem.m20Serial(m20))
    }

    private fun ByteArray.be(o: Int, n: Int, v: Long) { for (i in 0 until n) this[o + i] = (v shr (8 * (n - 1 - i))).toByte() }

    private fun seal(f: ByteArray): ByteArray { val cs = Meteomodem.check(f, f.size - 2); f.be(f.size - 2, 2, cs.toLong()); return f }

    private fun m10(lat: Double, lon: Double, altM: Double): ByteArray {
        val f = ByteArray(Meteomodem.M10_LEN)
        f[0] = 0x64; f[1] = 0x9F.toByte(); f[2] = 0x20
        val scale = (1 shl 30) / 90.0
        f.be(0x04, 2, (3.0 * 200).toLong()) // 3 m/s east
        f.be(0x06, 2, (4.0 * 200).toLong()) // 4 m/s north
        f.be(0x08, 2, (5.0 * 200).toLong()) // climbing 5 m/s
        f.be(0x0E, 4, (lat * scale).roundToInt().toLong())
        f.be(0x12, 4, (lon * scale).roundToInt().toLong())
        f.be(0x16, 4, (altM * 1000).toLong())
        f[0x1E] = 9
        byteArrayOf(0x52, 0x00, 0x85.toByte(), 0xC2.toByte(), 0x5E).copyInto(f, 0x5D)
        f[0x62] = 42
        return seal(f)
    }

    private fun m20(lat: Double, lon: Double, altM: Double): ByteArray {
        val f = ByteArray(Meteomodem.M20_LEN)
        f[0] = 0x45; f[1] = 0x20
        f.be(0x08, 3, (altM * 100).toLong())
        f.be(0x0B, 2, -250) // 2.5 m/s west
        f.be(0x0D, 2, 0)
        f.be(0x18, 2, -600) // falling 6 m/s
        f.be(0x1C, 4, (lat * 1e6).roundToInt().toLong())
        f.be(0x20, 4, (lon * 1e6).roundToInt().toLong())
        byteArrayOf(0x6A, 0x1C, 0x84.toByte()).copyInto(f, 0x12)
        return seal(f)
    }

    @Test fun parsesBothModels() {
        val a = Meteomodem.parse(m10(48.7745, 2.0098, 12_345.6))!!
        assertEquals("M10", a.model); assertEquals("805-2-27874", a.serial)
        assertEquals(48.7745, a.lat!!, 1e-6); assertEquals(2.0098, a.lon!!, 1e-6); assertEquals(12_345.6, a.altitudeM!!, 1e-3)
        assertEquals(5.0, a.speedMs!!, 1e-6); assertEquals(5.0, a.climbMs!!, 1e-6); assertEquals(36.87, a.courseDeg!!, 0.01); assertEquals(9, a.satellites)
        val b = Meteomodem.parse(m20(43.6047, 1.4442, 851.25))!!
        assertEquals("M20", b.model); assertEquals("811-1-10263", b.serial)
        assertEquals(43.6047, b.lat!!, 1e-6); assertEquals(851.25, b.altitudeM!!, 1e-6); assertEquals(-6.0, b.climbMs!!, 1e-6); assertEquals(270.0, b.courseDeg!!, 1e-6)
        val bad = m10(48.0, 2.0, 100.0).also { it[0x20] = (it[0x20] + 1).toByte() }
        assertFalse(Meteomodem.valid(bad))
    }

    @Test fun m10PlusWithItsGtopReceiver() {
        // Météo-France's current M10s: Gtop GPS, position in 1e-6 degrees, altitude in cm, velocity in cm/s.
        val f = ByteArray(Meteomodem.M10_LEN)
        f[0] = 0x64; f[1] = 0xAF.toByte()
        f.be(0x04, 4, (44.8378 * 1e6).roundToInt().toLong())
        f.be(0x08, 4, (-0.5792 * 1e6).roundToInt().toLong())
        f.be(0x0C, 3, 2_345_678)
        f.be(0x0F, 2, 0) // east
        f.be(0x11, 2, -800) // 8 m/s south
        f.be(0x13, 2, 512) // climbing 5.12 m/s
        f[0x45] = 0x2F; f[0x46] = 0x02 // ADC 559: 3.70 V
        byteArrayOf(0x52, 0x00, 0x85.toByte(), 0xC2.toByte(), 0x5E).copyInto(f, 0x5D)
        val r = Meteomodem.parse(seal(f))!!
        assertEquals("M10+", r.model)
        assertEquals(44.8378, r.lat!!, 1e-6)
        assertEquals(-0.5792, r.lon!!, 1e-6)
        assertEquals(23_456.78, r.altitudeM!!, 1e-6)
        assertEquals(8.0, r.speedMs!!, 1e-9)
        assertEquals(180.0, r.courseDeg!!, 1e-9)
        assertEquals(5.12, r.climbMs!!, 1e-9)
        assertEquals(3.70, r.batteryV!!, 0.005)
        // Before its first fix the receiver sends zeros: no position then.
        val cold = ByteArray(Meteomodem.M10_LEN).also { it[0] = 0x64; it[1] = 0xAF.toByte() }
        assertEquals(null, Meteomodem.parse(seal(cold))!!.lat)
    }

    /** Over the air: each bit as two half-bit symbols (different for 1), FSK, noise, at 100 kS/s complex baseband. */
    private fun air(frames: List<Pair<ByteArray, Double>>, fs: Double, polarity: Int, seed: Long = 3): Pair<FloatArray, FloatArray> {
        val rnd = Random(seed)
        val re = ArrayList<Float>()
        val im = ArrayList<Float>()
        var phase = 0.0
        fun emit(sym: Int, symLen: Double, n: Int) {
            repeat(n) {
                phase += 2 * PI * polarity * sym * 15e3 / fs + 2 * PI * 1.2e3 / fs // ±15 kHz, plus a small tuning error
                re += (cos(phase) + rnd.nextGaussian() * 0.25).toFloat(); im += (sin(phase) + rnd.nextGaussian() * 0.25).toFloat()
            }
        }
        var t = 0.0
        var level = 1
        for ((f, baud) in frames) {
            val symLen = fs / (2 * baud)
            // Preamble of zeros (pairs repeat), then the frame, then some idle.
            val bits = ArrayList<Int>()
            repeat(48) { bits += 0 }
            for (b in f) for (i in 7 downTo 0) bits += (b.toInt() shr i) and 1
            repeat(32) { bits += 0 }
            for (bit in bits) {
                val s1 = -level
                val s2 = if (bit == 1) -s1 else s1
                for (s in intArrayOf(s1, s2)) {
                    val end = t + symLen
                    emit(s, symLen, (end.roundToInt() - t.roundToInt()))
                    t = end
                }
                level = s2
            }
        }
        return re.toFloatArray() to im.toFloatArray()
    }

    @Test fun demodulatesM10AndM20FromTheAir() {
        val fs = 100e3
        for (pol in intArrayOf(1, -1)) {
            val (re, im) = air(listOf(m10(48.7745, 2.0098, 12_345.6) to 9615.0, m20(43.6047, 1.4442, 851.25) to 9600.0, m10(48.78, 2.01, 12_400.0) to 9615.0), fs, pol)
            val got = ArrayList<ByteArray>()
            val d = MeteomodemDemodulator(fs) { got += it }
            var i = 0
            while (i < re.size) { val n = minOf(4096, re.size - i); d.feed(re.copyOfRange(i, i + n), im.copyOfRange(i, i + n), n); i += n }
            val parsed = got.mapNotNull { Meteomodem.parse(it) }
            assertEquals("polarity $pol", listOf("M10", "M20", "M10"), parsed.map { it.model })
            assertNotNull(parsed[1].lat)
            assertTrue(parsed.all { it.serial != null })
        }
    }
}
