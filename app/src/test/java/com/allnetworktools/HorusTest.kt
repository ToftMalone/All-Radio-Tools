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
import org.junit.Assert.assertTrue
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

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Test fun realV1FrameFromHorusdemodlib() {
        // The coded frame and its payload from horusdemodlib's own tests (encoder.py, CMakeLists.txt): its last Golay
        // word holds only 8 data bits, which horus_l2.c places one bit up.
        val coded = hex("2424C06B300D0415C5DBD332EFD7C190D7AF7F3C2891DE9F4BA1EB2B437BE1E2D8419D3DC9E44FDF78DAA07A98")
        val payload = hex("000900071E2A000000000000000000000000259A6B14")
        val (back, fixed) = Horus.decodeFrame(coded.copyOfRange(2, coded.size), Horus.V1_BYTES)
        assertEquals(0, fixed)
        assertTrue(payload.contentEquals(back))
        assertTrue(coded.contentEquals(Horus.encodeFrame(payload)))
        val k = Horus.parse(back)!!
        assertEquals(9, k.seq)
        assertEquals(37, k.tempC)
    }

    /** Frames sent back to back with no 0x1B preamble between them, as horusdemodlib's v2 sample recording has them. */
    @Test fun framesWithoutPreamble() {
        val fs = 10_000.0
        val bits = ArrayList<Int>()
        fun bytes(b: ByteArray) { for (x in b) for (k in 7 downTo 0) bits += (x.toInt() shr k) and 1 }
        for (s in 1..4) bytes(Horus.encodeFrame(Horus.build(256, s, 48.1, -1.7, 12_000 + s, 30, 9, -20, 3.0, true)))
        val syms = bits.chunked(2).map { (it[0] shl 1) or it[1] }
        val rnd = Random(9)
        val spb = (fs / 100).toInt()
        val lead = 30_000
        val n = lead + syms.size * spb + 5000
        val re = FloatArray(n); val im = FloatArray(n)
        var ph = 0.0
        for (i in 0 until n) {
            val j = i - lead
            // A steady carrier before the frames lets the tones be learnt (one of them, as payloads idle on one tone).
            val s = if (j < 0) j / spb and 3 else if (j / spb < syms.size) syms[j / spb] else -1
            if (s >= 0) {
                ph += 2 * PI * (-420.0 + s * 270.0) / fs
                re[i] = cos(ph).toFloat() * 0.3f; im[i] = sin(ph).toFloat() * 0.3f
            }
            re[i] += (rnd.nextFloat() - 0.5f) * 0.25f; im[i] += (rnd.nextFloat() - 0.5f) * 0.25f
        }
        val got = ArrayList<HorusPacket>()
        val d = HorusDemodulator(fs) { got += it }
        var i = 0
        while (i < n) { val k = minOf(2000, n - i); d.feed(re.copyOfRange(i, i + k), im.copyOfRange(i, i + k), k); i += k }
        assertEquals(got.toString(), listOf(1, 2, 3, 4), got.map { it.seq })
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
