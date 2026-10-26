package com.allnetworktools

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
}
