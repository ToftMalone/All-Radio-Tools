package com.allnetworktools

import com.allnetworktools.data.sdr.Decimator
import com.allnetworktools.data.sdr.LoraFrame
import com.allnetworktools.data.sdr.LoraPhy
import com.allnetworktools.data.sdr.LoraReceiver
import java.util.Random
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoraTest {
    /**
     * gr-lora_sdr's interleaver output for "ABC" at SF11, CR 4/5, CRC on; the modulated symbol is its inverse Gray
     * code plus one (gray_demap_impl.cc).
     */
    private val grLoraInterleaved = intArrayOf(0x55a, 0x2ec, 0xa0, 0xf0, 0x22, 0x584, 0x1e, 0x162, 0x23, 0x40f, 0x201, 0x303, 0x102)
    private val grLoraAbc = IntArray(grLoraInterleaved.size) { i ->
        val v = grLoraInterleaved[i]
        var g = v
        for (j in 1 until 11) g = g xor (v shr j)
        (g + 1) % 2048
    }

    @Test fun testTransmitterMatchesGrLoraSdr() {
        assertArrayEquals(grLoraAbc, LoraTx.symbols("ABC".toByteArray(), sf = 11, cr = 1))
    }

    @Test fun bitLayerDecodesGrLoraSdrSymbols() {
        val d = LoraPhy.decode(grLoraAbc.toList(), sf = 11, ldro = false)
        assertNotNull(d)
        assertEquals("ABC", d!!.payload.toString(Charsets.US_ASCII))
        assertTrue(d.crcOk)
        assertEquals(1, d.header.cr)
    }

    @Test fun bitLayerRoundTripsEveryCodingRateAndLdro() {
        val rnd = Random(3)
        for (sf in 7..12) for (cr in 1..4) for (ldro in listOf(false, true)) {
            val p = ByteArray(1 + rnd.nextInt(80)).also(rnd::nextBytes)
            val d = LoraPhy.decode(LoraTx.symbols(p, sf, cr, ldro = ldro).toList(), sf, ldro)
            assertNotNull("sf$sf cr$cr ldro$ldro", d)
            assertArrayEquals("sf$sf cr$cr ldro$ldro", p, d!!.payload)
            assertTrue(d.crcOk)
        }
    }

    private fun receive(
        payload: ByteArray, sf: Int = 11, bw: Double = 250_000.0, cr: Int = 1, ppm: Double = 0.0, extraCfoHz: Double = 0.0,
        delayChips: Double = 1234.37, snrDb: Double? = null, seed: Long = 1, centerHz: Double = 869.525e6,
    ): List<LoraFrame> {
        val ldro = LoraPhy.lowDataRate(sf, bw)
        val sym = LoraTx.symbols(payload, sf, cr, ldro = ldro)
        val cfo = ppm * 1e-6 * centerHz + extraCfoHz
        val (re, im) = LoraTx.waveform(sym, sf, bw, LoraReceiver.OS * bw, delayChips = delayChips, cfoHz = cfo, ppm = ppm)
        val rnd = Random(seed)
        val sigma = snrDb?.let { sqrt(Math.pow(10.0, -it / 10) / 2) } ?: 0.0
        val got = ArrayList<LoraFrame>()
        val rx = LoraReceiver(sf, bw, centerHz) { got += it }
        val fr = FloatArray(re.size) { (re[it] + sigma * rnd.nextGaussian()).toFloat() }
        val fi = FloatArray(im.size) { (im[it] + sigma * rnd.nextGaussian()).toFloat() }
        // Fed in USB-sized chunks, as the HackRF delivers them.
        var i = 0
        while (i < fr.size) {
            val k = minOf(16384, fr.size - i)
            rx.feed(fr.copyOfRange(i, i + k), fi.copyOfRange(i, i + k), k)
            i += k
        }
        return got
    }

    private val meshLike = ByteArray(60) { (it * 37 + 11).toByte() }

    @Test fun decodesCleanLongFast() {
        val f = receive(meshLike).single()
        assertTrue(f.crcOk)
        assertArrayEquals(meshLike, f.payload)
        assertEquals(0x2B, f.syncWord)
    }

    @Test fun decodesWithCrystalErrorsBothWays() {
        // ±20 ppm at 869 MHz is ±17 kHz, about ±143 bins at SF11/250 kHz, with the chip clock drifting accordingly.
        for (ppm in listOf(-20.0, -7.3, 4.1, 20.0)) {
            for (delay in listOf(0.0, 517.25, 1800.6)) {
                val f = receive(meshLike, ppm = ppm, delayChips = delay)
                assertEquals("ppm $ppm delay $delay", 1, f.size)
                assertTrue("ppm $ppm delay $delay", f[0].crcOk)
                assertEquals(ppm * 869.525, f[0].cfoHz, 60.0)
            }
        }
    }

    @Test fun decodesLongFrameWithDrift() {
        val p = ByteArray(230) { (it * 13).toByte() }
        val f = receive(p, ppm = 18.0).single()
        assertTrue(f.crcOk)
        assertArrayEquals(p, f.payload)
    }

    @Test fun decodesOtherPresets() {
        // ShortFast, MediumSlow, LongModerate (LDRO, CR 4/8), LongSlow (LDRO), ShortTurbo.
        for ((sf, bw, cr) in listOf(Triple(7, 250_000.0, 1), Triple(10, 250_000.0, 1), Triple(11, 125_000.0, 4), Triple(12, 125_000.0, 4), Triple(7, 500_000.0, 1))) {
            val f = receive(meshLike, sf = sf, bw = bw, cr = cr, ppm = -9.0)
            assertEquals("sf$sf bw$bw", 1, f.size)
            assertTrue("sf$sf bw$bw", f[0].crcOk)
        }
    }

    @Test fun decodesBelowTheNoise() {
        // −10 dB in the 500 kHz sampled band, i.e. about −7 dB in the LoRa channel: a weak but usual signal.
        var ok = 0
        for (seed in 1L..6L) if (receive(meshLike, ppm = 6.0, snrDb = -10.0, seed = seed).any { it.crcOk }) ok++
        assertEquals(6, ok)
    }

    @Test fun staysQuietOnNoise() {
        val rnd = Random(9)
        val got = ArrayList<LoraFrame>()
        val rx = LoraReceiver(11, 250_000.0, 869.525e6) { got += it }
        val k = 500_000
        repeat(6) { rx.feed(FloatArray(k) { rnd.nextGaussian().toFloat() }, FloatArray(k) { rnd.nextGaussian().toFloat() }, k) }
        assertTrue(got.isEmpty())
        assertEquals(0, rx.preambles)
    }

    @Test fun decodesThroughTheHackRfChain() {
        // 2 MS/s 8-bit I/Q with the channel 500 kHz above the tuned frequency, through the app's channel filter.
        val bw = 250_000.0
        val rate = 2_000_000.0
        val sym = LoraTx.symbols(meshLike, 11, 1)
        val (re, im) = LoraTx.waveform(sym, 11, bw, rate, delayChips = 777.7, cfoHz = 9_000.0, ppm = 9_000.0 / 869.525e6 * 1e6, mixHz = 500_000.0, amplitude = 40.0)
        val rnd = Random(5)
        val bytes = ByteArray(2 * re.size)
        for (i in re.indices) {
            bytes[2 * i] = (re[i] + 6 * rnd.nextGaussian()).roundToInt().coerceIn(-127, 127).toByte()
            bytes[2 * i + 1] = (im[i] + 6 * rnd.nextGaussian()).roundToInt().coerceIn(-127, 127).toByte()
        }
        val decim = (rate / (LoraReceiver.OS * bw)).toInt()
        val dec = Decimator(decim, 0.62 * bw / rate, taps = 24 * decim)
        val got = ArrayList<LoraFrame>()
        val rx = LoraReceiver(11, bw, 869.525e6) { got += it }
        val outRe = FloatArray(131072)
        val outIm = FloatArray(131072)
        var i = 0
        while (i < bytes.size) {
            val len = minOf(131072, bytes.size - i)
            val k = dec.process(bytes.copyOfRange(i, i + len), len, outRe, outIm, 0)
            rx.feed(outRe, outIm, k)
            i += len
        }
        assertEquals(1, got.size)
        assertTrue(got[0].crcOk)
        assertArrayEquals(meshLike, got[0].payload)
    }
}
