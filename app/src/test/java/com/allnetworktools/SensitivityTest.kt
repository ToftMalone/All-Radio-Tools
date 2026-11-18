package com.allnetworktools

import com.allnetworktools.data.sdr.AfskDemodulator
import com.allnetworktools.data.sdr.AprsTx
import com.allnetworktools.data.sdr.Hdlc
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class SensitivityTest {
    private val frame = AprsTx.frame("F4ABC-9", AprsTx.positionInfo(48.8566, 2.3522, "/[", "Sensitivity test", 35.0))

    /** The line levels of [bits] after NRZI (a 0 changes the level). */
    private fun levels(bits: IntArray): IntArray {
        var lv = 0
        return IntArray(bits.size) { i -> if (bits[i] == 0) lv = lv xor 1; lv }
    }

    private fun bytes(bits: ByteArray, count: Int) = ByteArray((count + 7) / 8).also { out ->
        for (i in 0 until count) if (bits[i].toInt() == 1) out[i / 8] = (out[i / 8].toInt() or (1 shl (i % 8))).toByte()
    }

    private fun run(lv: IntArray, conf: FloatArray): Pair<List<ByteArray>, Int> {
        val got = ArrayList<ByteArray>()
        val h = Hdlc(Hdlc.AX25_MAX_BITS) { b, n -> got += bytes(b, n) }
        for (i in lv.indices) h.level(lv[i], conf[i])
        return got to h.mended
    }

    @Test fun cleanFrameNeedsNoMending() {
        val lv = levels(AprsTx.bits(frame, 4))
        val (got, mended) = run(lv, FloatArray(lv.size) { 1f })
        assertEquals(1, got.size)
        assertArrayEquals(frame, got[0])
        assertEquals(0, mended)
    }

    @Test fun oneAndTwoUncertainLevelsAreMended() {
        val bits = AprsTx.bits(frame, 4)
        for (wrong in listOf(listOf(140), listOf(97, 300), listOf(33 + 8 * 4, 250))) {
            val lv = levels(bits)
            val conf = FloatArray(lv.size) { 0.8f + (it % 7) * 0.05f }
            for (i in wrong) { lv[i] = lv[i] xor 1; conf[i] = 0.05f }
            val (got, mended) = run(lv, conf)
            assertEquals("flipped $wrong", 1, got.size)
            assertArrayEquals(frame, got[0])
            assertEquals(1, mended)
        }
    }

    @Test fun confidentWrongLevelIsNotGuessed() {
        // Three wrong levels are beyond mending: the frame is lost, not replaced by a wrong one.
        val lv = levels(AprsTx.bits(frame, 4))
        val conf = FloatArray(lv.size) { 1f }
        for (i in intArrayOf(100, 200, 300)) { lv[i] = lv[i] xor 1; conf[i] = 0.05f }
        assertTrue(run(lv, conf).first.isEmpty())
    }

    @Test fun noiseIsNeverMendedIntoFrames() {
        val rnd = Random(3)
        val n = 3_000_000
        val lv = IntArray(n) { if (rnd.nextBoolean()) 1 else 0 }
        val conf = FloatArray(n) { abs(rnd.nextGaussian()).toFloat() }
        // Random levels pass a CRC-16 now and then by chance (the callers' checks catch those), but none is mended.
        assertEquals(0, run(lv, conf).second)
    }

    private fun afskDecoded(noise: Double, mend: Boolean, trials: Int = 60, seed: Long = 11): Int {
        val rate = 48_000
        val clean = AprsTx.audio(AprsTx.bits(frame, 24), rate, 0.5f)
        val rnd = Random(seed)
        var ok = 0
        Hdlc.mending = mend
        try {
            repeat(trials) {
                val a = FloatArray(clean.size + 4800)
                for (i in clean.indices) a[2400 + i] = clean[i]
                for (i in a.indices) a[i] += (rnd.nextGaussian() * noise).toFloat()
                val got = ArrayList<ByteArray>()
                AfskDemodulator(rate.toDouble()) { got += it }.feedAudio(a, a.size)
                got.forEach { assertArrayEquals(frame, it) } // never a wrong frame
                if (got.isNotEmpty()) ok++
            }
        } finally {
            Hdlc.mending = true
        }
        return ok
    }

    /** End to end: beacons in noise through the AFSK demodulator; mending wins frames and never forges one. */
    @Test fun afskInNoiseGainsFrames() {
        for (noise in listOf(0.40, 0.45, 0.48, 0.52)) {
            val without = afskDecoded(noise, false)
            val with = afskDecoded(noise, true)
            println("AFSK noise $noise: $without/60 without mending, $with/60 with")
            assertTrue(with >= without)
            if (noise == 0.45) assertTrue("mending should win frames: $without → $with", with >= without + 6)
        }
    }

    @Test fun stressNoWrongFrame() {
        var total = 0
        for (seed in 1L..10L) for (noise in listOf(0.46, 0.5, 0.55)) total += afskDecoded(noise, true, 20, seed)
        println("STRESS decoded $total/600, none wrong")
    }
}
