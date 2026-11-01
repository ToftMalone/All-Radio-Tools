package com.allnetworktools

import com.allnetworktools.data.sdr.Ais
import com.allnetworktools.data.sdr.AisMessage
import com.allnetworktools.data.sdr.AisReceiver
import com.allnetworktools.data.sdr.AisTracker
import com.allnetworktools.data.sdr.Hdlc
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AisTest {
    /** NMEA 6-bit armoring to one bit per byte. */
    private fun armored(payload: String): ByteArray {
        val out = ByteArray(payload.length * 6)
        payload.forEachIndexed { i, ch ->
            var v = ch.code - 48
            if (v > 40) v -= 8
            for (k in 0 until 6) out[i * 6 + k] = ((v shr (5 - k)) and 1).toByte()
        }
        return out
    }

    private class Bits {
        val b = ArrayList<Int>()
        fun put(v: Long, w: Int) { for (i in w - 1 downTo 0) b += ((v shr i) and 1).toInt() }
        fun put(v: Int, w: Int) = put(v.toLong() and ((1L shl w) - 1), w)
        fun text(t: String, chars: Int) {
            for (i in 0 until chars) {
                val c = t.getOrElse(i) { '@' }
                put(if (c.code >= 64) c.code - 64 else c.code, 6)
            }
        }
    }

    private fun type1(mmsi: Int, lat: Double, lon: Double, sog: Double, cog: Double, hdg: Int): Bits {
        val w = Bits()
        w.put(1, 6); w.put(0, 2); w.put(mmsi, 30); w.put(0, 4); w.put(-128, 8); w.put((sog * 10).toInt(), 10); w.put(1, 1)
        w.put(Math.round(lon * 600_000).toInt(), 28); w.put(Math.round(lat * 600_000).toInt(), 27)
        w.put((cog * 10).toInt(), 12); w.put(hdg, 9); w.put(30, 6); w.put(0, 2 + 3 + 1 + 19)
        return w
    }

    private fun type5(mmsi: Int, call: String, name: String, type: Int, dest: String): Bits {
        val w = Bits()
        w.put(5, 6); w.put(0, 2); w.put(mmsi, 30); w.put(0, 2); w.put(9_123_456, 30); w.text(call, 7); w.text(name, 20)
        w.put(type, 8); w.put(0, 30); w.put(0, 4); w.put(0, 20); w.put(100, 8); w.text(dest, 20); w.put(0, 2)
        while (w.b.size < 424) w.b += 0
        return w
    }

    @Test fun decodesReferencePositionReport() {
        // A widely used sample sentence (gpsd documentation): a Swedish ship off Gothenburg.
        val b = armored("13u?etPv2;0n:dDPwUM1U1Cb069D")
        val m = Ais.decode(b, 168)!!
        assertEquals(1, m.type)
        assertEquals(265547250, m.mmsi)
        assertEquals(13.9, m.sogKn!!, 0.01)
        assertEquals(40.4, m.cogDeg!!, 0.01)
        assertEquals(41, m.heading)
        assertEquals(57.66, m.lat!!, 0.01)
        assertEquals(11.83, m.lon!!, 0.01)
        assertEquals("Suède", Ais.country(m.mmsi))
    }

    @Test fun staticVoyageData() {
        val w = type5(227123456, "FABC", "BRETAGNE III", 60, "BREST")
        val m = Ais.decode(w.b.map { it.toByte() }.toByteArray(), w.b.size)!!
        assertEquals("BRETAGNE III", m.name)
        assertEquals("FABC", m.callsign)
        assertEquals(60, m.shipType)
        assertEquals("BREST", m.destination)
        assertEquals("Passagers", Ais.shipTypeName(60))
    }

    @Test fun notAvailableValuesAreDropped() {
        val w = type1(227000001, 91.0, 181.0, 102.3, 360.0, 511)
        val m = Ais.decode(w.b.map { it.toByte() }.toByteArray(), w.b.size)!!
        assertNull(m.lat); assertNull(m.lon); assertNull(m.sogKn); assertNull(m.cogDeg); assertNull(m.heading)
    }

    @Test fun trackerMergesStaticAndPosition() {
        val t = AisTracker()
        t.update(AisMessage(1, 227123456, lat = 48.38, lon = -4.49, sogKn = 12.3, cogDeg = 87.5, heading = 90, navStatus = 0), 0)
        t.update(AisMessage(5, 227123456, name = "BRETAGNE III", shipType = 60, destination = "BREST"), 1000)
        t.update(AisMessage(24, 227123456, callsign = "FABC"), 2000)
        val v = t.vessels[227123456]!!
        assertEquals("BRETAGNE III", v.name)
        assertEquals(48.38, v.lat!!, 1e-9)
        assertEquals("FABC", v.callsign)
        assertEquals(3, v.messages)
        t.prune(31 * 60_000L)
        assertTrue(t.vessels.isEmpty())
    }

    /** Message bits to the line: each byte least significant bit first, CRC, bit stuffing, flags, training sequence, NRZI. */
    private fun frameBits(msg: Bits): IntArray {
        val data = ArrayList(List(msg.b.size) { i -> msg.b[(i and 7.inv()) + 7 - (i and 7)] })
        var crc = 0xFFFF
        for (x in data) crc = if ((crc xor x) and 1 != 0) (crc ushr 1) xor 0x8408 else crc ushr 1
        crc = crc xor 0xFFFF
        for (i in 0 until 16) data += (crc shr i) and 1 // CRC goes out LSB first
        val out = ArrayList<Int>()
        repeat(12) { out += 0; out += 1 } // training sequence
        val flag = intArrayOf(0, 1, 1, 1, 1, 1, 1, 0)
        out += flag.toList()
        var ones = 0
        for (x in data) {
            out += x
            if (x == 1) { if (++ones == 5) { out += 0; ones = 0 } } else ones = 0
        }
        out += flag.toList()
        repeat(8) { out += 0 }
        // NRZI: a 0 is a change of level.
        var level = 0
        return IntArray(out.size) { if (out[it] == 0) level = 1 - level; level }
    }

    @Test fun hdlcRoundTrip() {
        val w = type1(227123456, 48.38, -4.49, 12.3, 87.5, 90)
        val line = frameBits(w)
        val got = ArrayList<List<Int>>()
        val h = Hdlc { bits, n -> got += Ais.fromAir(bits, n).take(n).map { it.toInt() } }
        var prev = 0
        for (l in line) { h.bit(if (l == prev) 1 else 0); prev = l }
        // Back in message order once each byte is turned round.
        assertEquals(listOf(w.b), got)
    }

    /** Two GMSK packets on the two channels, as 8-bit HackRF samples at 2.304 MS/s. */
    private fun waveform(a: IntArray, b: IntArray, noise: Double, seed: Long): ByteArray {
        val rnd = Random(seed)
        val fs = AisReceiver.SAMPLE_RATE
        val hold = 10
        val sps = 24 // at 230.4 kS/s, then held ×10
        // Gaussian filter, BT 0.4.
        val sigma = Math.sqrt(Math.log(2.0)) / (2 * PI * 0.4) * sps
        val taps = DoubleArray(3 * sps) { exp(-Math.pow(it - 1.5 * sps, 2.0) / (2 * sigma * sigma)) }
        val sum = taps.sum()
        fun freq(line: IntArray): DoubleArray {
            val nrz = DoubleArray((line.size + 8) * sps + taps.size) { 0.0 }
            for (i in nrz.indices) {
                val bit = ((i - taps.size / 2) / sps).coerceIn(0, line.size - 1)
                nrz[i] = if (line[bit] == 1) 1.0 else -1.0
            }
            return DoubleArray(line.size * sps) { n -> var acc = 0.0; for (k in taps.indices) acc += taps[k] * nrz[n + k]; 2400.0 * acc / sum }
        }
        val fa = freq(a)
        val fb = freq(b)
        val n = 3000 + maxOf(fa.size, fb.size) * hold + 3000
        var pa = 0.0
        var pb = 0.0
        val iq = ByteArray(2 * n)
        for (i in 0 until n) {
            val j = (i - 1500) / hold
            val da = if (j in fa.indices && i >= 1500) fa[j] else 0.0
            val db = if (j in fb.indices && i >= 1500) fb[j] else 0.0
            // Channel A at −25 kHz, channel B at +25 kHz of the centre, which is itself fs/4 above the tuned frequency;
            // 700 Hz of tuning error on both.
            pa += 2 * PI * (fs / 4 - 25_000 + 700 + da) / fs
            pb += 2 * PI * (fs / 4 + 25_000 + 700 + db) / fs
            val re = 22 * cos(pa) + 22 * cos(pb)
            val im = 22 * sin(pa) + 22 * sin(pb)
            iq[2 * i] = (re + rnd.nextGaussian() * noise).toInt().coerceIn(-127, 127).toByte()
            iq[2 * i + 1] = (im + rnd.nextGaussian() * noise).toInt().coerceIn(-127, 127).toByte()
        }
        return iq
    }

    @Test fun demodulatesBothChannelsFromHackRfSamples() {
        val a = frameBits(type1(227123456, 48.3830, -4.4950, 12.3, 87.5, 90))
        val b = frameBits(type5(244660001, "PD1234", "ROTTERDAM", 70, "BREST"))
        val got = ArrayList<Pair<Int, Int>>()
        val rx = AisReceiver { m, ch -> got += m.mmsi to ch }
        val iq = waveform(a, b, noise = 8.0, seed = 5)
        var i = 0
        while (i < iq.size) {
            val len = minOf(131072, iq.size - i)
            rx.feed(iq.copyOfRange(i, i + len), len)
            i += len
        }
        assertEquals(listOf(227123456 to 0, 244660001 to 1).sortedBy { it.second }, got.sortedBy { it.second })
    }

    @Test fun noiseAloneDecodesNothing() {
        val rnd = Random(2)
        val iq = ByteArray(2 * 2_304_000) { (rnd.nextGaussian() * 25).toInt().coerceIn(-127, 127).toByte() }
        val got = ArrayList<AisMessage>()
        val rx = AisReceiver { m, _ -> got += m }
        var i = 0
        while (i < iq.size) { val len = minOf(131072, iq.size - i); rx.feed(iq.copyOfRange(i, i + len), len); i += len }
        assertTrue(got.isEmpty())
        assertNotNull(rx)
    }
}
