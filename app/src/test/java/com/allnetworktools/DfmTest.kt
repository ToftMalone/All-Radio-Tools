package com.allnetworktools

import com.allnetworktools.data.sdr.Dfm
import com.allnetworktools.data.sdr.DfmDecoder
import com.allnetworktools.data.sdr.DfmDemodulator
import com.allnetworktools.data.sdr.SondeFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class DfmTest {
    private fun nibs(v: Long, n: Int) = IntArray(n) { ((v shr (4 * (n - 1 - it))) and 0xF).toInt() }

    /** A data block: 48 bits of payload, then its id, as 13 nibbles. */
    private fun dat(id: Int, hi32: Long, lo16: Long) = nibs((hi32 and 0xFFFFFFFFL shl 16) or (lo16 and 0xFFFF), 12) + intArrayOf(id)

    private val serial = 23_456_789L

    /** One cycle of the sonde's frames: the two serial halves, then data blocks 0 to 8 (old position layout). */
    private fun cycle(): List<IntArray> {
        val conf = listOf(
            intArrayOf(0xB, 0xC) + nibs(serial ushr 16, 4) + intArrayOf(0),
            intArrayOf(0xB, 0xC) + nibs(serial and 0xFFFF, 4) + intArrayOf(1),
        )
        val d = listOf(
            dat(0, 0x0000_0233L, 0), // mode 2, frame 0x33
            dat(1, 0x0000_0FFFL, 12_000),
            dat(2, (48.8566 * 1e7).toLong(), 1234), // 12.34 m/s
            dat(3, (2.3522 * 1e7).toLong(), 27_000), // 270.00 degrees
            dat(4, 1_234_567L, (-512L) and 0xFFFF), // 12345.67 m, -5.12 m/s
            dat(5, 0, 0), dat(6, 0, 0), dat(7, 0, 0),
            dat(8, 0x7EA_B_0000L or 0x0C, 0), // 2026-11, 12 satellites in bits 32..39
        )
        // Data block 8 holds the satellites in bits 32..39: put them there.
        val d8 = dat(8, 0x7EAB0000L, 0x0C00)
        val blocks = d.dropLast(1) + listOf(d8)
        return (0 until 5).map { k -> Dfm.frame(conf[k % 2], blocks[(2 * k) % 9], blocks[(2 * k + 1) % 9]) }
    }

    @Test fun blocksMendOneBitPerCodeWord() {
        val f = cycle()[1]
        // One wrong bit in every code word of the data block 1 (code words are interleaved: bit j of word i is at 13j+i).
        for (i in 0 until 13) f[16 + 56 + 13 * (i % 8) + i] = f[16 + 56 + 13 * (i % 8) + i] xor 1
        val (_, d1, _) = Dfm.blocks(f)
        assertNotNull(d1)
        assertEquals(2, Dfm.bits(d1!!, 48, 4).toInt())
        // Two in one code word: refused.
        val g = cycle()[1]
        g[16 + 56] = g[16 + 56] xor 1
        g[16 + 56 + 13] = g[16 + 56 + 13] xor 1
        assertNull(Dfm.blocks(g).second)
    }

    @Test fun decoderPutsTheCycleTogether() {
        val got = ArrayList<SondeFrame>()
        val dec = DfmDecoder { got += it }
        repeat(2) { cycle().forEach(dec::frame) }
        val f = got.last()
        assertEquals("$serial", f.serial)
        assertEquals("DFM-17", f.model)
        assertEquals(48.8566, f.lat!!, 1e-6)
        assertEquals(2.3522, f.lon!!, 1e-6)
        assertEquals(12_345.67, f.altitudeM!!, 1e-6)
        assertEquals(12.34, f.speedMs!!, 1e-9)
        assertEquals(270.0, f.courseDeg!!, 1e-9)
        assertEquals(-5.12, f.climbMs!!, 1e-9)
        assertEquals(12, f.satellites)
        assertEquals(0x33, f.frameNumber)
    }

    /** The cycle as a sonde sends it: Manchester chips, FSK, noise, both polarities, through the demodulator. */
    @Test fun demodulatorReadsFramesAtEitherPolarity() {
        for (invert in listOf(false, true)) {
            val got = ArrayList<SondeFrame>()
            val dec = DfmDecoder { got += it }
            val demod = DfmDemodulator(100_000.0) { dec.frame(it) }
            val rate = 100_000.0
            val chip = rate / (2 * Dfm.BAUD * 1.002)
            val rnd = java.util.Random(4)
            val re = ArrayList<Float>()
            val im = ArrayList<Float>()
            var ph = 0.0
            fun tone(dev: Double, n: Int) = repeat(n) {
                ph += 2 * PI * dev / rate
                re += (cos(ph) + rnd.nextGaussian() * 0.3).toFloat(); im += (sin(ph) + rnd.nextGaussian() * 0.3).toFloat()
            }
            repeat(2) {
                cycle().forEach { frame ->
                    tone(0.0, 20_000)
                    val chips = frame.flatMap { b -> if (b == 1) listOf(0, 1) else listOf(1, 0) }
                    var t = 0.0
                    chips.forEach { c -> val n = ((t + chip).toInt() - t.toInt()); t += chip; tone(if ((c == 1) != invert) 2400.0 else -2400.0, n) }
                }
            }
            tone(0.0, 30_000)
            val r = re.toFloatArray(); val i = im.toFloatArray()
            var k = 0
            while (k < r.size) { val n = minOf(4096, r.size - k); demod.feed(r.copyOfRange(k, k + n), i.copyOfRange(k, k + n), n); k += n }
            assertTrue("invert=$invert: $got", got.isNotEmpty())
            assertEquals("$serial", got.last().serial)
            assertEquals(48.8566, got.last().lat!!, 1e-6)
        }
    }
}
