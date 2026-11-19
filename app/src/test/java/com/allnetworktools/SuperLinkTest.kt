package com.allnetworktools

import com.allnetworktools.data.sdr.IsmReading
import com.allnetworktools.data.sdr.IsmReceiver
import com.allnetworktools.data.sdr.LoraFrame
import com.allnetworktools.data.sdr.LoraPhy
import com.allnetworktools.data.sdr.LoraReceiver
import com.allnetworktools.data.sdr.SuperLink
import com.allnetworktools.data.sdr.SuperLinkScanner
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperLinkTest {
    /** An uplink data frame as captured: Mctrl E0, Dctrl 54, the MAC, the counter, then 9 encrypted bytes. */
    private val uplink = byteArrayOf(
        0xE0.toByte(), 0x54, 0x90.toByte(), 0x41, 0xB2.toByte(), 0x2E, 0x9A.toByte(), 0x53, 0x2A, 0x17,
        0x11, 0x5C, 0x7E, 0x03, 0xA9.toByte(), 0x44, 0x0D, 0x61, 0xF2.toByte(),
    )

    @Test fun parsesTheClearHeader() {
        val f = SuperLink.parse(uplink)!!
        assertEquals("90:41:B2:2E:9A:53", f.mac)
        assertEquals("data", f.type)
        assertEquals(0x2A, f.counter)
        // Not SuperLink: an unknown Dctrl, too short, no address.
        assertNull(SuperLink.parse(uplink.copyOf().also { it[1] = 0x12 }))
        assertNull(SuperLink.parse(uplink.copyOf(12)))
        assertNull(SuperLink.parse(uplink.copyOf().also { for (i in 2 until 8) it[i] = 0 }))
    }

    @Test fun bitLayerRoundTripsSf5AndSf6() {
        val rnd = Random(4)
        for (sf in 5..6) for (cr in 1..4) for (full in listOf(true)) {
            val p = ByteArray(1 + rnd.nextInt(70)).also(rnd::nextBytes)
            val d = LoraPhy.decode(LoraTx.symbols(p, sf, cr, fullHeader = full).toList(), sf, false, full)
            assertNotNull("sf$sf cr$cr full$full", d)
            assertArrayEquals(p, d!!.payload)
            assertTrue(d.crcOk)
        }
    }

    private fun rx(fullHeader: Boolean, gap: Int, cfoHz: Double, snrDb: Double?, seed: Long = 1): List<LoraFrame> {
        val sym = LoraTx.symbols(uplink, 5, 1, fullHeader = fullHeader)
        val bw = SuperLink.BW_HZ
        val (re, im) = LoraTx.waveform(sym, 5, bw, LoraReceiver.OS * bw, syncWord = 0x12, preamble = 12, delayChips = 333.4, cfoHz = cfoHz, gapSymbols = gap, tailChips = 400.0)
        val rnd = Random(seed)
        val sigma = snrDb?.let { sqrt(Math.pow(10.0, -it / 10) / 2) } ?: 0.0
        val got = ArrayList<LoraFrame>()
        val r = LoraReceiver(5, bw, 916.2e6) { got += it }
        r.feed(FloatArray(re.size) { (re[it] + sigma * rnd.nextGaussian()).toFloat() }, FloatArray(im.size) { (im[it] + sigma * rnd.nextGaussian()).toFloat() }, re.size)
        return got
    }

    @Test fun receiverTakesSx126xSf5Frames() {
        // SX126x: header block at the full rate, two more symbols before it.
        val a = rx(fullHeader = true, gap = 2, cfoHz = 9_000.0, snrDb = null).single()
        assertTrue(a.crcOk); assertArrayEquals(uplink, a.payload); assertEquals(0x12, a.syncWord)
        // Should the two symbols not be there, the header is found all the same.
        val b = rx(fullHeader = true, gap = 0, cfoHz = -12_000.0, snrDb = null).single()
        assertTrue(b.crcOk); assertArrayEquals(uplink, b.payload)
    }

    @Test fun receiverTakesSf5InNoise() {
        val ok = (1..20).count { s -> rx(true, 2, 5_000.0, snrDb = 2.0, seed = s.toLong()).any { it.crcOk && it.payload.contentEquals(uplink) } }
        assertTrue("$ok / 20", ok >= 16)
    }

    // ---- the wide scan ------------------------------------------------------------------------------------

    private val rate = 2_000_000.0
    private val tuned = 865_900_000.0

    /** 2 MS/s around [tuned] with SuperLink frames at the given offsets, noise at [noise] per component. */
    private fun capture(offsetsHz: List<Double>, noise: Double, seed: Long, amplitude: Double = 1.0): Pair<FloatArray, FloatArray> {
        val parts = offsetsHz.mapIndexed { i, off ->
            // Each frame its own counter, as on the air.
            val sym = LoraTx.symbols(uplink.copyOf().also { it[8] = (it[8] + i).toByte() }, 5, 1, fullHeader = true)
            LoraTx.waveform(sym, 5, SuperLink.BW_HZ, rate, syncWord = 0x12, preamble = 12, delayChips = 500.0 + 9_000.0 * i, mixHz = off, gapSymbols = 2, tailChips = 3_000.0, amplitude = amplitude)
        }
        val n = parts.maxOf { it.first.size }
        val rnd = Random(seed)
        val re = FloatArray(n) { (noise * rnd.nextGaussian()).toFloat() }
        val im = FloatArray(n) { (noise * rnd.nextGaussian()).toFloat() }
        for ((r, i) in parts) for (k in r.indices) { re[k] += r[k].toFloat(); im[k] += i[k].toFloat() }
        return re to im
    }

    private fun scan(c: Pair<FloatArray, FloatArray>): List<Pair<SuperLink.Frame, Double>> {
        val got = ArrayList<Pair<SuperLink.Frame, Double>>()
        val s = SuperLinkScanner(rate, tuned) { f, hz -> got += f to hz }
        var i = 0
        while (i < c.first.size) {
            val k = minOf(65_536, c.first.size - i)
            s.feed(c.first.copyOfRange(i, i + k), c.second.copyOfRange(i, i + k), k)
            i += k
        }
        return got
    }

    @Test fun wideScanFindsFramesAnywhereInTheCapture() {
        // Off any grid: the EU plan is unknown.
        val offs = listOf(-812_345.0, -433_210.0, 27_500.0, 611_111.0)
        val got = scan(capture(offs, noise = 0.3, seed = 3))
        assertEquals(offs.size, got.size)
        for ((f, _) in got) assertEquals("90:41:B2:2E:9A:53", f.mac)
        for (o in offs) assertTrue("$o", got.any { abs(it.second - (tuned + o)) < 8_000 })
    }

    @Test fun wideScanTakesWeakFrames() {
        // The frame 3 dB over the noise in its 125 kHz channel (the capture is 16 times wider).
        val sigma = 0.25 * sqrt(16 / 2.0) / Math.pow(10.0, 3.0 / 20)
        val ok = (1..10).count { s -> scan(capture(listOf(-250_000.0 + 13_000 * s), noise = sigma, seed = s.toLong(), amplitude = 0.25)).isNotEmpty() }
        assertTrue("$ok / 10", ok >= 8)
    }

    @Test fun wideScanStaysQuietOnNoiseAndOtherSignals() {
        val n = 2_000_000
        val rnd = Random(5)
        val re = FloatArray(n) { (0.3 * rnd.nextGaussian()).toFloat() }
        val im = FloatArray(n) { (0.3 * rnd.nextGaussian()).toFloat() }
        // A carrier, an FSK sensor and LoRa SF7 on top.
        var ph = 0.0
        for (k in 0 until n) {
            val c = 2 * PI * 300_000 * k / rate
            re[k] += cos(c).toFloat(); im[k] += sin(c).toFloat()
            val bit = (k / 40) % 3 == 0
            ph += 2 * PI * (-500_000 + if (bit) 25_000 else -25_000) / rate
            re[k] += cos(ph).toFloat(); im[k] += sin(ph).toFloat()
        }
        val sf7 = LoraTx.waveform(LoraTx.symbols(uplink, 7, 1), 7, 125_000.0, rate, syncWord = 0x12, mixHz = 600_000.0)
        for (k in sf7.first.indices) { re[k] += sf7.first[k].toFloat(); im[k] += sf7.second[k].toFloat() }
        assertTrue(scan(re to im).isEmpty())
    }

    @Test fun ismReceiverReportsSuperLinkFromHackRfBytes() {
        val (re, im) = capture(listOf(-700_000.0), noise = 0.2, seed = 8)
        val bytes = ByteArray(re.size * 2)
        val rnd = Random(1)
        for (k in re.indices) {
            bytes[2 * k] = (40 * re[k] + rnd.nextInt(-1, 2)).roundToInt().coerceIn(-127, 127).toByte()
            bytes[2 * k + 1] = (40 * im[k] + rnd.nextInt(-1, 2)).roundToInt().coerceIn(-127, 127).toByte()
        }
        val got = ArrayList<IsmReading>()
        val rx = IsmReceiver({ r, _ -> got += r }, tunedHz = tuned)
        var i = 0
        while (i < bytes.size) { val k = minOf(262_144, bytes.size - i); rx.feed(bytes.copyOfRange(i, i + k), k); i += k }
        val r = got.single { it.model == "Ubiquiti SuperLink" }
        assertEquals("90:41:B2:2E:9A:53", r.id)
    }

    // ---- Tapo ---------------------------------------------------------------------------------------------

    /** A GFSK-like burst at 50 kbit/s, [offHz] above the ISM channel's centre, through the HackRF chain. */
    private fun tapoBytes(offHz: Double, bitUs: Double = 20.0): ByteArray {
        val rnd = Random(7)
        val bits = ArrayList<Int>()
        repeat(32) { bits += it and 1 xor 1 } // preamble 1010…
        for (b in listOf(0xD3, 0x91, 0xD3, 0x91)) for (j in 7 downTo 0) bits += (b shr j) and 1 // a sync word
        repeat(160) { bits += rnd.nextInt(2) }
        val lead = 60_000
        val per = 2e6 * bitUs * 1e-6
        val total = lead * 2 + (bits.size * per).roundToInt()
        val out = ByteArray(total * 2)
        var ph = 0.0
        for (i in 0 until total) {
            val k = ((i - lead) / per).toInt()
            val on = i >= lead && k < bits.size
            val f = 500_000 + offHz + if (on) (if (bits[k] == 1) 25_000.0 else -25_000.0) else 0.0
            ph += 2 * PI * f / 2e6
            val a = if (on) 50.0 else 0.0
            out[2 * i] = (a * cos(ph) + rnd.nextInt(-3, 4)).roundToInt().toByte()
            out[2 * i + 1] = (a * sin(ph) + rnd.nextInt(-3, 4)).roundToInt().toByte()
        }
        return out
    }

    private fun ism(bytes: ByteArray, bandHz: Double): List<IsmReading> {
        val got = ArrayList<IsmReading>()
        val rx = IsmReceiver({ r, _ -> got += r }, tunedHz = bandHz - 500_000)
        var i = 0
        while (i < bytes.size) { val k = minOf(131_072, bytes.size - i); rx.feed(bytes.copyOfRange(i, i + k), k); i += k }
        return got
    }

    @Test fun tapoKnownByItsSignature() {
        // 868.35 MHz heard on the 868.3 MHz band.
        val r = ism(tapoBytes(50_000.0), 868.3e6).single()
        assertEquals("TP-Link Tapo", r.model)
        assertEquals(3, r.channel)
        // The same burst on another frequency, or at another rate, is not a Tapo.
        assertTrue(ism(tapoBytes(-40_000.0), 868.3e6).none { it.model == "TP-Link Tapo" })
        assertTrue(ism(tapoBytes(50_000.0, bitUs = 26.0), 868.3e6).none { it.model == "TP-Link Tapo" })
    }
}
