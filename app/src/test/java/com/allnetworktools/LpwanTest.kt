package com.allnetworktools

import com.allnetworktools.data.sdr.LoraWan
import com.allnetworktools.data.sdr.LpwanEvent
import com.allnetworktools.data.sdr.LpwanReceiver
import com.allnetworktools.data.sdr.Sigfox
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A LoRaWAN uplink and a Sigfox uplink in one capture of the 868 MHz band. */
class LpwanTest {
    // Unconfirmed data up from 26011234 (The Things Network), ADR, FCnt 42, port 1, four encrypted bytes, MIC.
    private val up = byteArrayOf(0x40, 0x34, 0x12, 0x01, 0x26, 0x80.toByte(), 0x2A, 0x00, 0x01, 0x11, 0x22, 0x33, 0x44, 0x5A, 0x6B, 0x7C, 0x0D)

    @Test
    fun lorawanFields() {
        val f = LoraWan.parse(up)!!
        assertEquals(LoraWan.Type.UnconfirmedUp, f.type)
        assertEquals(0x26011234, f.devAddr)
        assertEquals(42, f.fCnt)
        assertEquals(1, f.fPort)
        assertEquals(4, f.payloadLen)
        assertTrue(f.adr)
        assertEquals("The Things Network", LoraWan.network(f.devAddr!!))
        // From the LoRa Alliance's NetID table: Orange 1E000000/7, Bouygues Telecom 0E000000/7 (NetID 7, not 0x0B,
        // which is Everynet), a type 6 NetID (C00058, FC016000/22), and an unassigned range.
        assertEquals("Orange", LoraWan.network(0x1F123456))
        assertEquals("Bouygues Telecom", LoraWan.network(0x0E654321))
        assertEquals("Everynet", LoraWan.network(0x16000001))
        assertEquals("The Things Network", LoraWan.network(0xFC0161AB.toInt()))
        assertNull(LoraWan.network(0x0A000000))
        val join = ByteArray(23) { it.toByte() }
        val j = LoraWan.parse(join)!!
        assertEquals(LoraWan.Type.JoinRequest, j.type)
        assertEquals("100F0E0D0C0B0A09", j.devEui)
        assertNull(LoraWan.parse(byteArrayOf(0x41) + ByteArray(15)))
    }

    @Test
    fun sigfoxFrames() {
        for (rank in 1..3) for (len in listOf(0, 1, 4, 8, 12)) {
            val p = ByteArray(len) { (it * 7 + 1).toByte() }
            val bits = Sigfox.buildFrame(0x1A2B3C, 77, p, rank)
            val u = Sigfox.parse(bits, Sigfox.PREAMBLE)
            assertNotNull("rank $rank len $len", u)
            assertEquals(0x1A2B3C, u!!.id)
            assertEquals(77, u.counter)
            assertArrayEquals(p, u.payload)
            assertEquals(rank, u.rank)
        }
    }

    @Test
    fun hearsBoth() {
        val rate = 1_000_000.0
        val total = (rate * 3.2).toInt()
        val sym = LoraTx.symbols(up, 9, 1, hasCrc = true)
        val (lr, li) = LoraTx.waveform(sym, 9, 125_000.0, rate, syncWord = 0x34, preamble = 8, delayChips = 40_000.0, cfoHz = 2_000.0, mixHz = 200_000.0, tailChips = 0.0, amplitude = 1.0)
        val sig = Sigfox.buildFrame(0x00C0FFEE, 1234, byteArrayOf(0x12, 0x34, 0x56, 0x78), 1)
        // D-BPSK at 100 bit/s: a phase inversion for each 0 bit, on 868.150 MHz.
        val s = IntArray(sig.size + 1) { 1 }
        for (i in sig.indices) s[i + 1] = if (sig[i] == 1) s[i] else -s[i]
        val sStart = (rate * 0.8).toInt()
        val spb = rate / 100
        val rnd = Random(3)
        val re = FloatArray(total)
        val im = FloatArray(total)
        for (i in 0 until total) {
            var a = 0.0; var b = 0.0
            if (i < lr.size) { a += lr[i]; b += li[i] }
            val k = i - sStart
            if (k >= 0 && k < s.size * spb) {
                val ph = 2 * PI * (-150_000.0) * i / rate
                val v = 0.5 * s[(k / spb).toInt()]
                a += v * cos(ph); b += v * sin(ph)
            }
            re[i] = (a + 0.1 * rnd.nextGaussian()).toFloat(); im[i] = (b + 0.1 * rnd.nextGaussian()).toFloat()
        }
        val ev = ArrayList<LpwanEvent>()
        val rx = LpwanReceiver { ev += it }
        rx.feedComplex(re, im, total)
        val lora = ev.filterIsInstance<LpwanEvent.Lora>()
        assertEquals(1, lora.size)
        assertEquals(868_500_000L, lora[0].hz)
        assertEquals(9, lora[0].sf)
        assertEquals(0x34, lora[0].frame.syncWord)
        assertEquals(0x26011234, lora[0].lorawan?.devAddr)
        val sf = ev.filterIsInstance<LpwanEvent.SigfoxUp>()
        assertEquals(1, sf.size)
        assertEquals("00C0FFEE", sf[0].up.idHex)
        assertEquals(1234, sf[0].up.counter)
        assertEquals(868_150_000.0, sf[0].hz, 50.0)
    }

    /** The wide mode: 2 MS/s HackRF bytes centred on 867.8 MHz, LoRa on 867.3 and 868.5 MHz, Sigfox on 868.150 MHz. */
    @Test
    fun wideModeHearsTheEightChannels() {
        val rate = 2_000_000.0
        val total = (rate * 2.4).toInt()
        val sym = LoraTx.symbols(up, 9, 1, hasCrc = true)
        val (ar, ai) = LoraTx.waveform(sym, 9, 125_000.0, rate, syncWord = 0x34, preamble = 8, delayChips = 10_000.0, cfoHz = 1_500.0, mixHz = -500_000.0, tailChips = 0.0)
        val (br, bi) = LoraTx.waveform(sym, 9, 125_000.0, rate, syncWord = 0x34, preamble = 8, delayChips = 60_000.0, cfoHz = -2_000.0, mixHz = 700_000.0, tailChips = 0.0)
        val sig = Sigfox.buildFrame(0x00C0FFEE, 1234, byteArrayOf(0x12, 0x34, 0x56, 0x78), 1)
        val s = IntArray(sig.size + 1) { 1 }
        for (i in sig.indices) s[i + 1] = if (sig[i] == 1) s[i] else -s[i]
        val sStart = (rate * 0.6).toInt()
        val spb = rate / 100
        val rnd = Random(5)
        val bytes = ByteArray(2 * total)
        for (i in 0 until total) {
            var a = 0.0; var b = 0.0
            if (i < ar.size) { a += ar[i]; b += ai[i] }
            if (i < br.size) { a += br[i]; b += bi[i] }
            val k = i - sStart
            if (k >= 0 && k < s.size * spb) {
                val ph = 2 * PI * 350_000.0 * i / rate
                val v = 0.5 * s[(k / spb).toInt()]
                a += v * cos(ph); b += v * sin(ph)
            }
            bytes[2 * i] = (30 * a + 3 * rnd.nextGaussian()).toInt().coerceIn(-127, 127).toByte()
            bytes[2 * i + 1] = (30 * b + 3 * rnd.nextGaussian()).toInt().coerceIn(-127, 127).toByte()
        }
        val ev = ArrayList<LpwanEvent>()
        val rx = LpwanReceiver(wide = true) { ev += it }
        assertEquals(8, rx.channelsHz.size)
        var o = 0
        while (o < bytes.size) { val k = minOf(262_144, bytes.size - o); rx.feed(bytes.copyOfRange(o, o + k), k); o += k }
        rx.close()
        val lora = ev.filterIsInstance<LpwanEvent.Lora>().filter { it.frame.crcOk }
        assertEquals(listOf(867_300_000L, 868_500_000L), lora.map { it.hz }.sorted())
        assertTrue(lora.all { it.lorawan?.devAddr == 0x26011234 })
        val sf = ev.filterIsInstance<LpwanEvent.SigfoxUp>()
        assertEquals(1, sf.size)
        assertEquals("00C0FFEE", sf[0].up.idHex)
        assertEquals(868_150_000.0, sf[0].hz, 50.0)
    }
}
