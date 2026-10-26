package com.allnetworktools

import com.allnetworktools.data.sdr.AmbeDecoder
import com.allnetworktools.data.sdr.Fft
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** AMBE+2 frames built field by field (Golay, the pseudo-random modulation, the dibit order), then decoded. */
class AmbeTest {
    private fun frame(d: IntArray) = AmbeFrames.frame(d)
    private fun params(b0: Int, b1: Int, b2: Int, rest: Random) = AmbeFrames.params(b0, b1, b2, rest)

    @Test
    fun voicedFramesHaveTheirPitch() {
        val dec = AmbeDecoder()
        val rnd = Random(1)
        val b0 = 40
        val n = 40
        val pcm = FloatArray(160 * n)
        val fixed = params(b0, 0, 20, rnd) // voicing row 0: every band voiced
        repeat(n) { dec.decode(frame(fixed), 0, pcm, 160 * it) }
        assertEquals(0, dec.lastErrors)
        assertTrue(pcm.all { it.isFinite() })
        val tail = pcm.copyOfRange(160 * 8, 160 * 8 + 4096)
        val rms = sqrt(tail.map { it * it }.average())
        assertTrue("rms $rms", rms > 1e-3)
        // The strongest periodicity is the fundamental the table gives for this index.
        val f = Fft(4096)
        val re = tail.copyOf()
        val im = FloatArray(4096)
        f.forward(re, im)
        val mag = FloatArray(2049) { re[it] * re[it] + im[it] * im[it] }
        // Autocorrelation peak from the power spectrum.
        val ar = FloatArray(4096) { if (it < 2048) mag[it] else mag[4096 - it] }
        val ai = FloatArray(4096)
        f.forward(ar, ai)
        val lag = (20..160).maxBy { ar[it] }
        val pitchHz = 8000.0 / lag
        val want = com.allnetworktools.data.sdr.AmbeTables.ambeW0table[b0] * 8000.0
        assertTrue("pitch $pitchHz, want $want", abs(pitchHz - want) < want * 0.08)
    }

    @Test
    fun correctsBitErrorsAndKeepsSilenceSilent() {
        val dec = AmbeDecoder()
        val rnd = Random(2)
        val good = frame(params(60, 3, 18, rnd))
        val out = FloatArray(160)
        dec.decode(good, 0, out)
        assertEquals(0, dec.lastErrors)
        // Three errors in the first code word and two in the second: Golay puts them right.
        val hit = good.copyOf()
        for (j in intArrayOf(0, 4, 8, 3, 7)) hit[j] = hit[j] xor 1
        dec.decode(hit, 0, out)
        assertTrue(dec.lastErrors in 1..5)
        // Erasure frames (pitch index 120 to 123) give silence.
        val er = frame(params(121, 0, 0, rnd))
        dec.decode(er, 0, out)
        assertTrue(out.all { it == 0f })
    }
}

/** AMBE+2 frames as an encoder would put them on air, for the decoder tests. */
internal object AmbeFrames {
    private val golayGen = intArrayOf(0x63a, 0x31d, 0x7b4, 0x3da, 0x1ed, 0x6cc, 0x366, 0x1b3, 0x6e3, 0x54b, 0x49f, 0x475)
    private val w = intArrayOf(0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 2, 0, 2, 0, 2, 0, 2, 0, 2, 0, 2, 0, 2)
    private val x = intArrayOf(23, 10, 22, 9, 21, 8, 20, 7, 19, 6, 18, 5, 17, 4, 16, 3, 15, 2, 14, 1, 13, 0, 12, 10, 11, 9, 10, 8, 9, 7, 8, 6, 7, 5, 6, 4)
    private val y = intArrayOf(0, 2, 0, 2, 0, 2, 0, 2, 0, 3, 0, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3, 1, 3)
    private val z = intArrayOf(5, 3, 4, 2, 3, 1, 2, 0, 1, 13, 0, 12, 22, 11, 21, 10, 20, 9, 19, 8, 18, 7, 17, 6, 16, 5, 15, 4, 14, 3, 13, 2, 12, 1, 11, 0)

    fun golay(data12: Int): Int {
        var p = 0
        for (i in 0 until 12) if ((data12 shr (11 - i)) and 1 == 1) p = p xor golayGen[i]
        return (data12 shl 11) or p
    }

    /** The 72 bits on air for the 49 parameter bits [d]. */
    fun frame(d: IntArray): IntArray {
        val fr = Array(4) { IntArray(24) }
        var c0 = 0
        for (i in 0 until 12) c0 = (c0 shl 1) or d[i]
        val b0 = golay(c0)
        for (k in 0 until 23) fr[0][k + 1] = (b0 shr k) and 1
        var c1 = 0
        for (i in 12 until 24) c1 = (c1 shl 1) or d[i]
        val b1 = golay(c1)
        for (k in 0 until 23) fr[1][k] = (b1 shr k) and 1
        for (i in 0 until 11) fr[2][10 - i] = d[24 + i]
        for (i in 0 until 14) fr[3][13 - i] = d[35 + i]
        // The second code word is sent modulated by a sequence seeded from the first one's data.
        val pr = IntArray(24)
        pr[0] = 16 * c0
        for (i in 1 until 24) pr[i] = ((173 * pr[i - 1]) + 13849) % 65536
        for (i in 1 until 24) pr[i] = pr[i] / 32768
        var k = 1
        for (j in 22 downTo 0) { fr[1][j] = fr[1][j] xor pr[k]; k++ }
        val bits = IntArray(72)
        for (j in 0 until 36) { bits[2 * j] = fr[w[j]][x[j]]; bits[2 * j + 1] = fr[y[j]][z[j]] }
        return bits
    }

    /** Parameter bits with the pitch index [b0] (7 bits), voicing row [b1], gain [b2], the rest [rest]. */
    fun params(b0: Int, b1: Int, b2: Int, rest: Random): IntArray {
        val d = IntArray(49) { rest.nextInt(2) }
        intArrayOf(0, 1, 2, 3, 37, 38, 39).forEachIndexed { i, p -> d[p] = (b0 shr (6 - i)) and 1 }
        intArrayOf(4, 5, 6, 7, 35).forEachIndexed { i, p -> d[p] = (b1 shr (4 - i)) and 1 }
        intArrayOf(8, 9, 10, 11, 36).forEachIndexed { i, p -> d[p] = (b2 shr (4 - i)) and 1 }
        return d
    }
}
