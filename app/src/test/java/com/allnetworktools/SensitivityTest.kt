package com.allnetworktools

import com.allnetworktools.data.sdr.AfskDemodulator
import com.allnetworktools.data.sdr.AprsTx
import com.allnetworktools.data.sdr.Hdlc
import com.allnetworktools.data.sdr.Ant
import com.allnetworktools.data.sdr.AntHeard
import com.allnetworktools.data.sdr.AntReceiver
import com.allnetworktools.data.sdr.SoftMend
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
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

    // ---- Generic soft mending ----

    /** Counts frames decoded over [trials] with mending on and off: on must win, and never lose. */
    private fun gain(name: String, minGain: Int, decode: () -> Int): Pair<Int, Int> {
        SoftMend.enabled = false
        val without = try { decode() } finally { SoftMend.enabled = true }
        val with = decode()
        println("GAIN $name: $without without mending, $with with")
        assertTrue("$name: $without → $with", with >= without + minGain)
        return without to with
    }

    private fun crc16(b: ByteArray): Int {
        var c = 0xFFFF
        for (x in b) { c = c xor ((x.toInt() and 0xFF) shl 8); repeat(8) { c = if (c and 0x8000 != 0) (c shl 1) xor 0x1021 else c shl 1 }; c = c and 0xFFFF }
        return c
    }

    @Test fun softMendFlipsTheWeakBitsOnly() {
        val rnd = Random(5)
        val payload = ByteArray(20) { rnd.nextInt().toByte() }
        val c = crc16(payload)
        val good = payload + byteArrayOf((c shr 8).toByte(), c.toByte())
        val bits = ByteArray(good.size * 8) { i -> ((good[i / 8].toInt() shr (7 - i % 8)) and 1).toByte() }
        val buf = ByteArray(good.size)
        val check = { SoftMend.packMsb(bits, 0, buf, buf.size); val p = buf.copyOf(20); crc16(p) == (((buf[20].toInt() and 0xFF) shl 8) or (buf[21].toInt() and 0xFF)) }
        val margin = FloatArray(bits.size) { 1f + rnd.nextFloat() }
        bits[37] = (bits[37].toInt() xor 1).toByte(); margin[37] = 0.1f
        bits[150] = (bits[150].toInt() xor 1).toByte(); margin[150] = 0.2f
        assertEquals(2, SoftMend.mend(bits, margin, 0, bits.size, check = check))
        assertArrayEquals(good, buf)
        // A confident wrong bit is left alone: the frame is lost, not forged.
        bits[60] = (bits[60].toInt() xor 1).toByte()
        val before = bits.copyOf()
        assertEquals(0, SoftMend.mend(bits, margin, 0, bits.size, check = check))
        assertArrayEquals(before, bits)
    }

    @Test fun softMendRefusesNoise() {
        val rnd = Random(8)
        var passed = 0
        repeat(3000) {
            val bits = ByteArray(176) { rnd.nextInt(2).toByte() }
            val margin = FloatArray(176) { abs(rnd.nextGaussian()).toFloat() }
            val buf = ByteArray(22)
            if (SoftMend.mend(bits, margin, 0, 176) { SoftMend.packMsb(bits, 0, buf, 22); crc16(buf.copyOf(20)) == (((buf[20].toInt() and 0xFF) shl 8) or (buf[21].toInt() and 0xFF)) } > 0) passed++
        }
        assertEquals(0, passed)
    }

    /** ANT+ GFSK bursts at 4 MS/s with [noise] standard deviation (signal amplitude 40). */
    private fun antBursts(noise: Double, trials: Int, seed: Long): Int {
        val fs = 4_000_000.0
        val rnd = Random(seed)
        var ok = 0
        repeat(trials) { t ->
            val payload = byteArrayOf(0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x12, 0x34, t.toByte(), 140.toByte())
            val bits = Ant.bits(Ant.ANT_PLUS, 4321, Ant.HEART_RATE, 5, payload)
            val sps = 4
            val nrz = DoubleArray((bits.size + 8) * sps) { val b = it / sps - 4; if (b in bits.indices) (if (bits[b] == 1) 1.0 else -1.0) else 0.0 }
            val g = DoubleArray(4 * sps + 1) { val x = (it - 2 * sps) / sps.toDouble(); exp(-2 * (PI * 0.5 * x) * (PI * 0.5 * x) / kotlin.math.ln(2.0)) }
            val gs = g.sum()
            val pad = 600
            val buf = ByteArray(2 * (nrz.size + 2 * pad))
            var ph = rnd.nextDouble() * 2 * PI
            for (i in 0 until nrz.size + 2 * pad) {
                var f = 0.0
                val j = i - pad
                if (j >= 0 && j < nrz.size) for (k in g.indices) { val q = j + k - 2 * sps; if (q in nrz.indices) f += nrz[q] * g[k] / gs }
                ph += 2 * PI * (30_000.0 + 160_000.0 * f) / fs
                val on = j in 0 until nrz.size
                buf[2 * i] = ((if (on) 40 * cos(ph) else 0.0) + noise * rnd.nextGaussian()).toInt().coerceIn(-127, 127).toByte()
                buf[2 * i + 1] = ((if (on) 40 * sin(ph) else 0.0) + noise * rnd.nextGaussian()).toInt().coerceIn(-127, 127).toByte()
            }
            val got = ArrayList<AntHeard>()
            AntReceiver { got += it }.feedBytes(buf, buf.size, FloatArray(65536), FloatArray(65536))
            got.forEach { assertArrayEquals(payload, it.frame.payload) } // never a wrong frame
            if (got.isNotEmpty()) ok++
        }
        return ok
    }

    @Test fun antGainsFrames() {
        gain("ANT+ noise 13", 8) { antBursts(13.0, 30, 1) }
    }

    /** Seconds of pure noise into every receiver that mends with the looser gate: nothing may come out. */
    @Test fun noiseAloneGivesNothing() {
        val rnd = Random(21)
        fun noise(n: Int, sd: Double) = ByteArray(2 * n) { (rnd.nextGaussian() * sd).toInt().coerceIn(-127, 127).toByte() }
        for (sd in listOf(8.0, 30.0)) {
            val heard = ArrayList<String>()
            val ant = AntReceiver { heard += "ANT" }
            val zb = com.allnetworktools.data.sdr.ZigbeeReceiver(15) { heard += "Zigbee" }
            val dect = com.allnetworktools.data.sdr.DectReceiver { heard += "DECT" }
            val flarm = com.allnetworktools.data.sdr.FlarmReceiver { heard += "FLARM" }
            val re = FloatArray(65536); val im = FloatArray(65536)
            repeat(120) {
                val b = noise(65_536, sd) // one USB transfer, 128 KiB
                ant.feedBytes(b, b.size, re, im)
                zb.feedBytes(b, b.size, re, im)
                dect.feed(b, b.size)
                flarm.feed(b, b.size)
            }
            assertTrue("noise $sd gave $heard", heard.isEmpty())
        }
    }
}
