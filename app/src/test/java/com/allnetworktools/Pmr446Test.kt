package com.allnetworktools

import com.allnetworktools.data.sdr.AmbeDecoder
import com.allnetworktools.data.sdr.Dmr
import com.allnetworktools.data.sdr.Dpmr
import com.allnetworktools.data.sdr.Pmr446
import com.allnetworktools.data.sdr.Pmr446Receiver
import com.allnetworktools.data.sdr.PmrEvent
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DMR446, dPMR446 and an analogue walkie-talkie at once in one capture of the band. */
class Pmr446Test {
    private val lc = Dmr.Lc(0, 0, 0x00, 9, 2_081_234)

    @Test
    fun dmrCodes() {
        for (v in listOf(0, 0x5A, 0xFF)) assertEquals((v shr 4) to (v and 15), Dmr.slotType((v shl 12) or Dmr.golayParity(v)))
        assertEquals(1 to 1, Dmr.slotType(((0x11 shl 12) or Dmr.golayParity(0x11)) xor 0b1000_0000_0100_0000_0001))
        val info = Dmr.lcInfo(lc, false)
        val tx = Dmr.bptcEncode(info)
        // Three errors in different rows and columns of the product code (two in one column could defeat it).
        for (i in listOf(3, 6, 118)) tx[i] = tx[i] xor 1
        val back = Dmr.bptcDecode(tx)
        assertNotNull(back)
        assertEquals(lc, Dmr.lcDecode(back!!, false))
        // One octet hit: Reed-Solomon puts it back.
        val hit = info.copyOf().also { for (i in 40 until 48) it[i] = it[i] xor 1 }
        assertEquals(lc, Dmr.lcDecode(hit, false))
        val burst = Dmr.dataBurst(1, 1, tx, 3)
        val r = Dmr.readData(burst) as Dmr.Burst.Header
        assertEquals(1, r.cc)
        assertEquals(2_081_234, r.lc.src)
        assertEquals(9, r.lc.dst)
        assertTrue(r.lc.group)
    }

    @Test
    fun dpmrCodes() {
        val h = Dpmr.Header(0, 1, 12_345, 0, 1, false)
        val air = Dpmr.blockEncode(Dpmr.headerBits(h), 10)
        air[17] = air[17] xor 1
        assertEquals(h, Dpmr.headerParse(Dpmr.blockDecode(air, 10)!!))
        assertEquals(12_345, Dpmr.endParse(Dpmr.blockDecode(Dpmr.blockEncode(Dpmr.endBits(12_345), 6), 6)!!))
        val cc = Dpmr.ccSymbols(37)
        assertEquals(37, Dpmr.ccDecode(FloatArray(12) { cc[it].toFloat() }, 0))
    }

    /** Frequency (Hz) of each sample at 2 MS/s for a 4FSK symbol stream, smoothed a little. */
    private fun fsk(symbols: IntArray, symRate: Double, unit: Double, rate: Double): DoubleArray {
        val sps = rate / symRate
        val n = (symbols.size * sps).roundToInt()
        val f = DoubleArray(n) { symbols[minOf(symbols.size - 1, (it / sps).toInt())] * unit }
        val w = (sps / 3).roundToInt()
        var acc = 0.0
        return DoubleArray(n) { i -> acc += f[i] - (if (i >= w) f[i - w] else 0.0); acc / minOf(i + 1, w) }
    }

    private fun symbolsOf(bits: IntArray) = IntArray(bits.size / 2) { Dmr.dibitToSymbol((bits[2 * it] shl 1) or bits[2 * it + 1]) }

    @Test
    fun hearsTheBand() {
        val rate = 2_000_000.0
        val total = (rate * 3.0).toInt()
        val rnd = Random(6)
        // DMR446 channel 1 (446.10625 MHz): voice headers, then terminators.
        val header = symbolsOf(Dmr.dataBurst(1, 1, Dmr.bptcEncode(Dmr.lcInfo(lc, false)), 3))
        val term = symbolsOf(Dmr.dataBurst(1, 2, Dmr.bptcEncode(Dmr.lcInfo(lc, true)), 3))
        val dmrSyms = IntArray(0).let { var a = it; repeat(12) { a += header }; repeat(6) { a += term }; a }
        val dmrF = fsk(dmrSyms, 4800.0, 648.0, rate)
        // dPMR446 channel 5: preamble, header frame with colour code 21, end frame.
        val h = Dpmr.Header(0, 0xFFFFFF, 4_711, 0, 1, false)
        val pre = IntArray(72) { if (it % 2 == 0) 3 else -3 }
        val hdr = Dpmr.FS1 + symbolsOf(Dpmr.blockEncode(Dpmr.headerBits(h), 10)) + Dpmr.ccSymbols(21) + symbolsOf(Dpmr.blockEncode(Dpmr.headerBits(h), 10))
        val end = Dpmr.FS3 + symbolsOf(Dpmr.blockEncode(Dpmr.endBits(4_711), 6))
        val dpmrSyms = pre + hdr + IntArray(480) { rnd.nextInt(4).let { d -> Dmr.dibitToSymbol(d) } } + end
        val dpmrF = fsk(dpmrSyms, 2400.0, 350.0, rate)
        val out = ByteArray(2 * total)
        var p1 = 0.0; var p2 = 0.0; var p3 = 0.0
        val s1 = (rate * 0.4).toInt()
        val s2 = (rate * 0.9).toInt()
        val o1 = Pmr446.analogueHz(9) - Pmr446.CENTRE_HZ + 500_000.0
        val o2 = Pmr446.dpmrHz(5) - Pmr446.CENTRE_HZ + 500_000.0
        val o3 = Pmr446.analogueHz(3) - Pmr446.CENTRE_HZ + 500_000.0
        for (i in 0 until total) {
            var re = 0.0; var im = 0.0
            if (i in s1 until s1 + dmrF.size) { p1 += 2 * PI * (o1 + 300 + dmrF[i - s1]) / rate; re += cos(p1); im += sin(p1) }
            if (i in s2 until s2 + dpmrF.size) { p2 += 2 * PI * (o2 - 200 + dpmrF[i - s2]) / rate; re += cos(p2); im += sin(p2) }
            // Analogue channel 3 all along: a 1 kHz tone at 2 kHz deviation and CTCSS 88.5 Hz at 400 Hz.
            val tt = i / rate
            p3 += 2 * PI * (o3 + 2000 * sin(2 * PI * 1000 * tt) + 400 * sin(2 * PI * 88.5 * tt)) / rate
            re += cos(p3); im += sin(p3)
            out[2 * i] = (re * 30 + (rnd.nextDouble() - 0.5) * 10).roundToInt().toByte()
            out[2 * i + 1] = (im * 30 + (rnd.nextDouble() - 0.5) * 10).roundToInt().toByte()
        }
        val events = ArrayList<PmrEvent>()
        val rx = Pmr446Receiver { events += it }
        var o = 0
        while (o < out.size) { val k = minOf(131072, out.size - o); rx.feed(out.copyOfRange(o, o + k), k); o += k }
        val headers = events.filterIsInstance<PmrEvent.Dmr>().map { it.burst }.filterIsInstance<Dmr.Burst.Header>()
        assertTrue(headers.isNotEmpty())
        assertTrue(headers.all { it.lc == lc && it.cc == 1 })
        assertTrue(events.filterIsInstance<PmrEvent.Dmr>().any { it.burst is Dmr.Burst.Terminator && it.hz == Pmr446.analogueHz(9) })
        val dh = events.filterIsInstance<PmrEvent.DpmrHeader>()
        assertTrue(dh.isNotEmpty())
        assertEquals(21, dh[0].cc)
        assertEquals(4_711, dh[0].header.own)
        assertEquals(Pmr446.dpmrHz(5), dh[0].hz)
        assertTrue(events.filterIsInstance<PmrEvent.DpmrEnd>().any { it.own == 4_711 })
        val ch3 = rx.chains[Pmr446.analogueHz(3)]
        assertNotNull(ch3)
        assertEquals(88.5, ch3!!.ctcss()!!, 0.01)
    }

    /** Bits of [n] AMBE+2 frames with pitch, voicing and gain changing from frame to frame. */
    private fun voiceFrames(n: Int, rnd: Random) = List(n) { AmbeFrames.frame(AmbeFrames.params(20 + rnd.nextInt(80), rnd.nextInt(8), 10 + rnd.nextInt(30), rnd)) }

    private fun reference(frames: List<IntArray>): List<FloatArray> {
        val dec = AmbeDecoder()
        return frames.map { f -> FloatArray(160).also { dec.decode(f, 0, it) } }
    }

    private fun bitsOf(v: Long, n: Int) = IntArray(n) { ((v shr (n - 1 - it)) and 1).toInt() }

    @Test
    fun decodesDigitalVoice() {
        val rate = 2_000_000.0
        val total = (rate * 2.4).toInt()
        val rnd = Random(9)
        fun noise(n: Int) = IntArray(n) { Dmr.dibitToSymbol(rnd.nextInt(4)) }
        // DMR446 channel 9: two voice headers, three superframes of six voice bursts (A with the voice sync, B to F
        // with embedded signalling), a terminator; one burst every 60 ms, the other slot idle.
        val dmrVoice = voiceFrames(54, rnd)
        val header = symbolsOf(Dmr.dataBurst(1, 1, Dmr.bptcEncode(Dmr.lcInfo(lc, false)), 3))
        val term = symbolsOf(Dmr.dataBurst(1, 2, Dmr.bptcEncode(Dmr.lcInfo(lc, true)), 3))
        var dmrSyms = IntArray(0)
        repeat(2) { dmrSyms += noise(12) + header + noise(144) }
        for (b in 0 until 18) {
            val vs = dmrVoice[3 * b] + dmrVoice[3 * b + 1] + dmrVoice[3 * b + 2]
            val centre = if (b % 6 == 0) bitsOf(Dmr.SYNC[0], 48) else IntArray(48) { rnd.nextInt(2) }
            dmrSyms += noise(12) + symbolsOf(vs.copyOfRange(0, 108) + centre + vs.copyOfRange(108, 216)) + noise(144)
        }
        dmrSyms += noise(12) + term + noise(144)
        val dmrF = fsk(dmrSyms, 4800.0, 648.0, rate)
        // dPMR446 channel 5: header, eight payload frames (sync FS2 or the colour code, control channel, four voice
        // frames scrambled), end.
        val dpmrVoice = voiceFrames(32, rnd)
        val h = Dpmr.Header(0, 0xFFFFFF, 4_711, 0, 1, false)
        val pre = IntArray(72) { if (it % 2 == 0) 3 else -3 }
        var dpmrSyms = pre + Dpmr.FS1 + symbolsOf(Dpmr.blockEncode(Dpmr.headerBits(h), 10)) + Dpmr.ccSymbols(21) + symbolsOf(Dpmr.blockEncode(Dpmr.headerBits(h), 10))
        val pn = Dpmr.pn9(288)
        for (k in 0 until 8) {
            val tch = (dpmrVoice[4 * k] + dpmrVoice[4 * k + 1] + dpmrVoice[4 * k + 2] + dpmrVoice[4 * k + 3]).let { v -> IntArray(288) { v[it] xor pn[it] } }
            dpmrSyms += (if (k % 2 == 0) Dpmr.FS2 else Dpmr.ccSymbols(21)) + symbolsOf(Dpmr.blockEncode(Dpmr.cchBits(k % 4, 4_711 shr 12, 0, 1), 6)) + symbolsOf(tch)
        }
        dpmrSyms += Dpmr.FS3 + symbolsOf(Dpmr.blockEncode(Dpmr.endBits(4_711), 6)) + noise(60)
        val dpmrF = fsk(dpmrSyms, 2400.0, 350.0, rate)
        val out = ByteArray(2 * total)
        var p1 = 0.0; var p2 = 0.0
        val s1 = (rate * 0.3).toInt()
        val s2 = (rate * 1.0).toInt()
        val o1 = Pmr446.analogueHz(9) - Pmr446.CENTRE_HZ + 500_000.0
        val o2 = Pmr446.dpmrHz(5) - Pmr446.CENTRE_HZ + 500_000.0
        for (i in 0 until total) {
            var re = 0.0; var im = 0.0
            if (i in s1 until s1 + dmrF.size) { p1 += 2 * PI * (o1 + 300 + dmrF[i - s1]) / rate; re += cos(p1); im += sin(p1) }
            if (i in s2 until s2 + dpmrF.size) { p2 += 2 * PI * (o2 - 200 + dpmrF[i - s2]) / rate; re += cos(p2); im += sin(p2) }
            out[2 * i] = (re * 30 + (rnd.nextDouble() - 0.5) * 10).roundToInt().toByte()
            out[2 * i + 1] = (im * 30 + (rnd.nextDouble() - 0.5) * 10).roundToInt().toByte()
        }
        val events = ArrayList<PmrEvent>()
        val rx = Pmr446Receiver { events += it }
        var o = 0
        while (o < out.size) { val k = minOf(131072, out.size - o); rx.feed(out.copyOfRange(o, o + k), k); o += k }
        // Every frame read and decoded in order: the same sound as the frames decoded straight from their bits.
        val dmrGot = events.filterIsInstance<PmrEvent.Voice>().filter { it.hz == Pmr446.analogueHz(9) }.map { it.pcm }
        assertEquals(54, dmrGot.size)
        reference(dmrVoice).forEachIndexed { i, want -> assertArrayEquals("DMR frame $i", want, dmrGot[i], 0f) }
        val dpmrGot = events.filterIsInstance<PmrEvent.Voice>().filter { it.hz == Pmr446.dpmrHz(5) }.map { it.pcm }
        assertTrue("dPMR frames ${dpmrGot.size}", dpmrGot.size >= 32)
        reference(dpmrVoice).forEachIndexed { i, want -> assertArrayEquals("dPMR frame $i", want, dpmrGot[i], 0f) }
        assertTrue(dmrGot.any { p -> p.any { kotlin.math.abs(it) > 0.01f } })
    }
}
