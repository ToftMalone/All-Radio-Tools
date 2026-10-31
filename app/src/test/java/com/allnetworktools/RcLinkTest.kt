package com.allnetworktools

import com.allnetworktools.data.sdr.RcBurst
import com.allnetworktools.data.sdr.RcBurstDetector
import com.allnetworktools.data.sdr.RcLink
import com.allnetworktools.data.sdr.RcLinkTracker
import com.allnetworktools.data.sdr.RcModulation
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Remote-control links synthesised from their published parameters (packet rate, modulation, bandwidth, hopping), fed
 * as 8-bit I/Q at 20 MS/s through the burst detector and the link tracker.
 */
class RcLinkTest {
    private val fs = 20e6

    /** Instantaneous frequency (Hz, relative to the packet centre) at time t into the packet, or null once it is over. */
    private fun interface Packet { fun freq(t: Double): Double? }

    private fun lora(bw: Double, sf: Int, symbols: Int, rnd: Random): Packet {
        val n = 1 shl sf
        val ts = n / bw
        val values = IntArray(symbols) { if (it < 8) 0 else rnd.nextInt(n) } // preamble of upchirps, then data
        return Packet { t ->
            val s = (t / ts).toInt()
            if (s >= symbols) null else {
                val x = (values[s].toDouble() / n + (t - s * ts) / ts) % 1.0
                -bw / 2 + x * bw
            }
        }
    }

    private fun fsk(bitRate: Double, dev: Double, bits: Int, rnd: Random): Packet {
        val b = BooleanArray(bits) { rnd.nextBoolean() }
        return Packet { t -> val i = (t * bitRate).toInt(); if (i >= bits) null else if (b[i]) dev else -dev }
    }

    /**
     * A hopping link around [windowHz]: one packet every [periodS] on a channel drawn from [channels]; packets on
     * channels outside the 20 MHz capture are not heard, as with a real band wider than the window. DSSS packets are
     * random ±1 chips at [chipRate] instead of a frequency.
     */
    private fun run(
        windowHz: Double, channels: List<Double>, periodS: Double, seconds: Double, make: (Random) -> Packet?,
        chipRate: Double = 0.0, chipsLen: Double = 0.0, seed: Long = 7, amp: Double = 0.4, random: Boolean = false,
    ): Pair<List<RcBurst>, List<RcLink>> {
        val rnd = Random(seed)
        val bursts = ArrayList<RcBurst>()
        val det = RcBurstDetector(fs, windowHz) { bursts += it }
        val buf = ByteArray(131072)
        var fill = 0
        var phase = 0.0
        var pktStart = 0.02 // the detector learns the noise floor first
        var gap = periodS
        var pkt: Packet? = make(rnd)
        var pktOffset = channels[rnd.nextInt(channels.size)] - windowHz
        var chip = 1.0
        var chipIdx = -1
        val total = (seconds * fs).toLong()
        for (i in 0 until total) {
            val t = i / fs
            if (t - pktStart >= gap) {
                pktStart += gap; gap = if (random) periodS * (0.2 + 1.6 * rnd.nextDouble()) else periodS; pkt = make(rnd); pktOffset = channels[rnd.nextInt(channels.size)] - windowHz; chipIdx = -1
            }
            val dt = t - pktStart
            if (dt < 0) {
                buf[fill++] = (rnd.nextGaussian() * 0.02 * 127).toInt().toByte(); buf[fill++] = (rnd.nextGaussian() * 0.02 * 127).toInt().toByte()
                if (fill == buf.size) { det.feed(buf, fill); fill = 0 }
                continue
            }
            var re = rnd.nextGaussian() * 0.02
            var im = rnd.nextGaussian() * 0.02
            val inside = kotlin.math.abs(pktOffset) < 9.5e6
            if (chipRate > 0 && dt < chipsLen && inside) {
                val ci = (dt * chipRate).toInt()
                if (ci != chipIdx) { chipIdx = ci; chip = if (rnd.nextBoolean()) 1.0 else -1.0 }
                phase += 2 * PI * pktOffset / fs
                re += amp * chip * cos(phase); im += amp * chip * sin(phase)
            } else {
                val f = pkt?.freq(dt)
                if (f != null && inside) {
                    phase += 2 * PI * (pktOffset + f) / fs
                    re += amp * cos(phase); im += amp * sin(phase)
                }
            }
            buf[fill++] = (re * 127).toInt().coerceIn(-127, 127).toByte()
            buf[fill++] = (im * 127).toInt().coerceIn(-127, 127).toByte()
            if (fill == buf.size) { det.feed(buf, fill); fill = 0 }
        }
        det.feed(buf, fill)
        val tracker = RcLinkTracker()
        val band = when { windowHz > 2e9 -> 24; windowHz > 900e6 -> 915; windowHz > 800e6 -> 868; else -> 433 }
        bursts.forEach { tracker.add(band, 0, it, (it.timeS * 1000).toLong()) }
        val links = tracker.links((seconds * 1000).toLong())
        return bursts to links
    }

    private val ism24 = (0 until 80).map { 2400.5e6 + it * 1e6 }

    /**
     * As the app listens: 10 MS/s, [windows] one after the other for [dwellS] each while the link goes on, and the
     * phone keeping only [keep] buffers of 32768 samples out of every [of]: the others are lost, the rest carry their
     * place in the stream.
     */
    private fun sweep(
        windows: List<Double>, channels: List<Double>, periodS: Double, dwellS: Double, make: (Random) -> Packet,
        keep: Int = 1, of: Int = 1, seed: Long = 11,
    ): List<RcLink> {
        val rate = 10e6
        val rnd = Random(seed)
        val tracker = RcLinkTracker()
        val band = if (windows.first() > 2e9) 24 else if (windows.first() > 900e6) 915 else 868
        var pktStart = 0.0
        var pkt = make(rnd)
        var pktCh = channels[rnd.nextInt(channels.size)]
        var phase = 0.0
        var at = 0L
        val buf = ByteArray(65536)
        var block = 0
        windows.forEachIndexed { w, centre ->
            val det = RcBurstDetector(rate, centre) { tracker.add(band, w, it, (it.timeS * 1000).toLong()) }
            // The detector learns the noise floor while the radio settles on the window.
            val end = at + (dwellS * rate).toLong()
            while (at < end) {
                var fill = 0
                val first = at
                while (fill < buf.size) {
                    val t = at / rate
                    while (t - pktStart >= periodS) { pktStart += periodS; pkt = make(rnd); pktCh = channels[rnd.nextInt(channels.size)] }
                    var re = rnd.nextGaussian() * 0.02
                    var im = rnd.nextGaussian() * 0.02
                    val f = pkt.freq(t - pktStart)
                    val off = pktCh - centre
                    if (f != null && abs(off) < 4.75e6 && t > (end - dwellS * rate) / rate + 0.02) {
                        phase += 2 * PI * (off + f) / rate
                        re += 0.4 * cos(phase); im += 0.4 * sin(phase)
                    }
                    buf[fill++] = (re * 127).toInt().coerceIn(-127, 127).toByte()
                    buf[fill++] = (im * 127).toInt().coerceIn(-127, 127).toByte()
                    at++
                }
                if (block++ % of < keep) det.feed(buf, fill, first)
            }
        }
        return tracker.links((at / rate * 1000).toLong())
    }

    /** What the phone does: 10 MS/s windows, and only a third of the samples processed. */
    @Test fun expressLrsHeardThroughLostBuffers() {
        val windows = (0 until 8).map { 2405e6 + it * 10e6 }
        val l = sweep(windows + windows, ism24, 1 / 250.0, 1.5, { lora(812.5e3, 6, 14, it) }, keep = 1, of = 3).single()
        assertEquals("ExpressLRS 2,4 GHz", l.protocol)
        assertEquals("250 Hz", l.variant)
        assertTrue(l.channels >= 8)
    }

    @Test fun flrcHeardThroughLostBuffers() {
        val windows = (0 until 8).map { 2405e6 + it * 10e6 }
        val l = sweep(windows, ism24, 1 / 500.0, 1.5, { fsk(1.3e6, 330e3, 120, it) }, keep = 1, of = 2).single()
        assertEquals("F500 (FLRC)", l.variant)
    }

    @Test fun crossfireAt10MSps() {
        val ch = (0 until 20).map { 860.2e6 + it * 0.5e6 }
        val l = sweep(listOf(865e6, 865e6), ch, 1 / 150.0, 1.5, { fsk(85e3, 50e3, 100, it) }, keep = 2, of = 3).single()
        assertEquals("TBS Crossfire", l.protocol)
    }

    @Test fun expressLrsLora24() {
        val (bursts, links) = run(2430e6, ism24, 1 / 250.0, 1.6, { lora(812.5e3, 6, 14, it) })
        assertTrue("bursts ${bursts.size}", bursts.size >= 40)
        assertTrue(bursts.count { it.modulation == RcModulation.Chirp } > bursts.size / 2)
        val l = links.single()
        assertEquals("ExpressLRS 2,4 GHz", l.protocol)
        assertEquals("250 Hz", l.variant)
        assertEquals(RcModulation.Chirp, l.modulation)
        assertTrue(l.channels >= 4)
    }

    @Test fun expressLrsFlrc() {
        val (_, links) = run(2450e6, ism24, 1 / 1000.0, 0.6, { fsk(1.3e6, 330e3, 120, it) })
        val l = links.single()
        assertEquals("ExpressLRS 2,4 GHz", l.protocol)
        assertEquals("F1000 (FLRC)", l.variant)
    }

    @Test fun frskyAccst() {
        val ch = (0 until 47).map { 2404e6 + it * 1.5e6 }
        val (_, links) = run(2430e6, ch, 0.009, 3.0, { fsk(250e3, 57e3, 240, it) })
        val l = links.single()
        assertEquals("FrSky ACCST / ACCESS 2,4 GHz", l.protocol)
        assertEquals(111.1, l.rateHz, 1.0)
    }

    @Test fun spektrumDsmx() {
        val ch = (0 until 23).map { 2403e6 + it * 3e6 }
        val (_, links) = run(2430e6, ch, 0.011, 3.0, { null }, chipRate = 1e6, chipsLen = 0.8e-3, amp = 0.1)
        val l = links.single()
        assertEquals(RcModulation.Dsss, l.modulation)
        assertEquals("Spektrum DSMX", l.protocol)
    }

    @Test fun expressLrs900Lora() {
        val ch = (0 until 40).map { 903.5e6 + it * 0.6e6 }
        val (_, links) = run(910e6, ch, 0.01, 2.5, { lora(500e3, 7, 14, it) })
        val l = links.single()
        assertEquals("ExpressLRS 915 MHz", l.protocol)
        assertEquals("100 Hz", l.variant)
    }

    @Test fun crossfire150() {
        val ch = (0 until 20).map { 860.2e6 + it * 0.5e6 }
        val (_, links) = run(868e6, ch, 1 / 150.0, 1.5, { fsk(85e3, 50e3, 100, it) })
        val l = links.single()
        assertEquals("TBS Crossfire", l.protocol)
        assertEquals(150.0, l.rateHz, 2.0)
    }

    @Test fun meshtasticIsNotARemoteControl() {
        // Meshtastic ShortFast style: LoRa 250 kHz on one frequency, packets at irregular times.
        val (bursts, links) = run(868e6, listOf(869.525e6), 0.08, 2.0, { lora(250e3, 7, 24, it) }, random = true)
        assertTrue(bursts.isNotEmpty())
        assertTrue(links.isEmpty())
    }

    @Test fun loraWanOnSeveralChannelsIsNotARemoteControl() {
        // LoRaWAN EU868: eight 125 kHz channels, random times, no fixed packet rate.
        val ch = listOf(867.1e6, 867.3e6, 867.5e6, 867.7e6, 867.9e6, 868.1e6, 868.3e6, 868.5e6)
        val (_, links) = run(868e6, ch, 0.03, 2.0, { lora(125e3, 7, 16, it) }, random = true)
        assertTrue(links.isEmpty())
    }

    @Test fun irregularHoppingLoraIsNotExpressLrs() {
        // Same width as ExpressLRS 2.4 GHz and hopping, but no fixed rate: not identified.
        val (_, links) = run(2430e6, ism24, 1 / 250.0, 1.6, { lora(812.5e3, 6, 14, it) }, random = true)
        assertTrue(links.isEmpty())
    }

    @Test fun fixedFrequencyBurstsAreNotRemoteControls() {
        // LoRaWAN-like: chirps, but always on one frequency.
        val (bursts, links) = run(868e6, listOf(868.1e6), 0.05, 1.5, { lora(125e3, 7, 20, it) })
        assertNotNull(bursts.firstOrNull())
        assertTrue(links.isEmpty())
    }

    @Test fun wideOrEndlessSignalsAreDropped() {
        // A 20 µs-chip DSSS 16 MHz wide (Wi-Fi-like) is too wide; nothing else is on the air.
        val (bursts, links) = run(2430e6, listOf(2430e6), 0.004, 0.5, { null }, chipRate = 16e6, chipsLen = 1.5e-3)
        assertTrue(bursts.isEmpty())
        assertTrue(links.isEmpty())
    }
}
