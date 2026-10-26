package com.allnetworktools

import com.allnetworktools.data.sdr.Horus
import com.allnetworktools.data.sdr.HorusDemodulator
import com.allnetworktools.data.sdr.HorusPacket
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HorusTest {
    @Test fun golayFixesThreeBits() {
        val w = Horus.golayEncode(0xABC)
        assertEquals(0xABC to 0, Horus.golayDecode(w))
        assertEquals(0xABC to 3, Horus.golayDecode(w xor 0b100000010000000000001))
    }

    @Test fun frameRoundTripWithErrors() {
        for (v2 in listOf(false, true)) {
            val p = Horus.build(7, 1234, 48.8566, 2.3522, 23_456, 42, 9, -31, 3.1, v2)
            val f = Horus.encodeFrame(p)
            // A few bit errors spread by the interleaver.
            for (i in listOf(40, 100, 170, 260)) f[i / 8] = (f[i / 8].toInt() xor (1 shl (i % 8))).toByte()
            val (back, fixed) = Horus.decodeFrame(f.copyOfRange(2, f.size), p.size)
            val k = Horus.parse(back, fixed)
            assertNotNull(k)
            assertEquals(if (v2) 2 else 1, k!!.version)
            assertEquals(7, k.payloadId)
            assertEquals(48.8566, k.lat, 1e-4)
            assertEquals(23_456.0, k.altitudeM, 0.0)
            assertEquals(-31, k.tempC)
            assertEquals(4, fixed)
        }
        assertNull(Horus.parse(ByteArray(22)))
    }

    /** 100 baud 4FSK, 270 Hz apart, with noise and a frequency offset, at 10 kS/s. */
    @Test fun demodulatesFourFsk() {
        val fs = 10_000.0
        val packets = listOf(Horus.build(3, 1, 52.0, 4.5, 30_000, 20, 8, -40, 2.9, false), Horus.build(256, 2, -33.9, 151.2, 1500, 5, 7, 12, 3.3, true))
        val bits = ArrayList<Int>()
        fun bytes(b: ByteArray) { for (x in b) for (k in 7 downTo 0) bits += (x.toInt() shr k) and 1 }
        for (p in packets) {
            repeat(16) { bytes(byteArrayOf(0x1B)) }
            bytes(Horus.encodeFrame(p))
        }
        repeat(8) { bytes(byteArrayOf(0x1B)) }
        val syms = bits.chunked(2).map { (it[0] shl 1) or it.getOrElse(1) { 0 } }
        val rnd = Random(4)
        val spb = (fs / 100).toInt()
        val lead = 30_000
        val n = lead + syms.size * spb
        val re = FloatArray(n); val im = FloatArray(n)
        var ph = 0.0
        for (i in 0 until n) {
            val s = if (i < lead) -1 else syms[(i - lead) / spb]
            if (s >= 0) {
                ph += 2 * PI * (650.0 + s * 270.0) / fs
                re[i] = cos(ph).toFloat() * 0.3f; im[i] = sin(ph).toFloat() * 0.3f
            }
            re[i] += (rnd.nextFloat() - 0.5f) * 0.25f; im[i] += (rnd.nextFloat() - 0.5f) * 0.25f
        }
        // The tones are learnt from a few seconds of signal first, as on air where payloads send every few seconds.
        val got = ArrayList<HorusPacket>()
        val d = HorusDemodulator(fs) { got += it }
        repeat(2) {
            var i = 0
            while (i < n) { val k = minOf(2000, n - i); d.feed(re.copyOfRange(i, i + k), im.copyOfRange(i, i + k), k); i += k }
        }
        assertEquals(got.toString(), listOf(3, 256), got.map { it.payloadId }.distinct())
        assertEquals(30_000.0, got.first { it.payloadId == 3 }.altitudeM, 0.0)
    }
}
