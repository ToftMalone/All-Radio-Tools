package com.allnetworktools

import com.allnetworktools.data.sdr.ChannelImage
import com.allnetworktools.data.sdr.LrptReceiver
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrptReceiverTest {
    private val fs = 2_304_000.0

    /** A whole pass fragment as the HackRF would deliver it: [strips] image strips on APID 65, signed 8-bit I/Q. */
    private fun capture(strips: Int, symbolSnrDb: Double, seed: Long, cfo: Double, ppm: Double, offset: Double, noiseOnly: Boolean = false, m2x: Boolean = false): ByteArray {
        val packets = (0 until strips).flatMap { LrptTx.stripPackets(65, 300, it, 85) }
        val frames = LrptTx.dataUnits(packets, 5, 1).map { LrptTx.transmissionFrame(it) }
        var bits = LrptTx.bitStream(frames)
        // M2-x: NRZ-M (a 1 is a change of level) before the convolutional code, then offset QPSK.
        if (m2x) { var level = 0; bits = IntArray(bits.size) { level = level xor bits[it]; level } }
        val coded = ConvTx.encode(bits)
        // The wideband SNR is lower than the symbol SNR by the oversampling (2.304 MHz / 72 kHz = 32 → 15 dB).
        val (re, im) = QpskTx.waveform(coded, 32.0, offset, ppm, fs / 4 + cfo, fs, symbolSnrDb - 15.05, seed, lead = 40_000, oqpsk = m2x)
        val scale = 30f
        val out = ByteArray(2 * re.size)
        val rnd = Random(seed)
        for (i in re.indices) {
            out[2 * i] = Math.round(if (noiseOnly) rnd.nextGaussian() * 30 else (re[i] * scale).toDouble()).toInt().coerceIn(-127, 127).toByte()
            out[2 * i + 1] = Math.round(if (noiseOnly) rnd.nextGaussian() * 30 else (im[i] * scale).toDouble()).toInt().coerceIn(-127, 127).toByte()
        }
        return out
    }

    private fun run(iq: ByteArray, m2x: Boolean = false): Pair<LrptReceiver, List<Triple<Int, Int, ByteArray>>> {
        val strips = ArrayList<Triple<Int, Int, ByteArray>>()
        val rx = LrptReceiver(m2x) { apid, row, s -> strips += Triple(apid, row, s) }
        var i = 0
        while (i < iq.size) {
            val len = minOf(131072, iq.size - i)
            rx.feed(iq.copyOfRange(i, i + len), len)
            i += len
        }
        return rx to strips
    }

    @Test fun decodesAnImageFromHackRfSamples() {
        val iq = capture(strips = 12, symbolSnrDb = 9.0, seed = 3, cfo = 2300.0, ppm = 8.0, offset = 3.3)
        val (rx, got) = run(iq)
        val st = rx.status()
        println("STATUS $st")
        assertTrue("carrier", st.carrier || st.frames > 0)
        assertTrue("locked on the frame marker", st.frames > 0)
        assertTrue("frames ${st.frames} rsOk ${st.rsOk} rsFail ${st.rsFail}", st.rsOk >= st.frames - 2 && st.rsOk >= 5)
        assertEquals("base duale", st.basis)
        assertEquals(10, st.zoneStart)
        assertEquals(12, st.layout)
        assertEquals(0, st.segmentsBad)
        assertTrue("packets ${st.packets}", st.packets >= 60)
        // The decoded strips are those of the picture.
        val rows = got.map { it.second }.distinct().sorted()
        assertTrue("rows $rows", rows.size >= 5)
        assertTrue(got.all { it.first == 65 })
        val (_, row, strip) = got.last { r -> got.count { it.second == r.second } >= 14 }
        var err = 0.0
        var n = 0
        for (x in 0 until ChannelImage.WIDTH step 5) for (y in 0 until 4) {
            val ref = LrptTx.picture(x / 4, row)[(2 * y) * 8 + 2 * (x % 4)]
            err += Math.abs((strip[y * ChannelImage.WIDTH + x].toInt() and 0xFF) - ref); n++
        }
        assertTrue("mean error ${err / n}", err / n < 14)
    }

    /** Meteor-M2-3 and M2-4, the satellites that transmit now: offset QPSK, NRZ-M. */
    @Test fun decodesAnM2xImage() {
        for ((seed, cfo) in listOf(3L to 2300.0, 7L to -4100.0)) {
            val iq = capture(strips = 12, symbolSnrDb = 9.0, seed = seed, cfo = cfo, ppm = 8.0, offset = 3.3, m2x = true)
            val (rx, got) = run(iq, m2x = true)
            val st = rx.status()
            println("M2-x STATUS $st")
            assertTrue("frames ${st.frames} rsOk ${st.rsOk}", st.rsOk >= 5 && st.rsOk >= st.frames - 2)
            assertEquals(0, st.segmentsBad)
            assertTrue("rows", got.map { it.second }.distinct().size >= 5)
        }
        // The old chain does not read them, nor the new one the old satellite: the receiver must match the satellite.
        val m2 = capture(strips = 4, symbolSnrDb = 12.0, seed = 9, cfo = 1000.0, ppm = 0.0, offset = 1.0)
        assertEquals(0, run(m2, m2x = true).first.status().rsOk)
    }

    @Test fun noiseAloneProducesNothing() {
        val iq = capture(strips = 2, symbolSnrDb = 0.0, seed = 5, cfo = 0.0, ppm = 0.0, offset = 0.0, noiseOnly = true)
        val (rx, got) = run(iq)
        val st = rx.status()
        assertEquals(0, st.frames)
        assertEquals(0, got.size)
        assertTrue(!st.locked)
    }
}
