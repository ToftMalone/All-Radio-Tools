package com.allnetworktools

import com.allnetworktools.data.sdr.FfskDemodulator
import com.allnetworktools.data.sdr.Mdc1200
import com.allnetworktools.data.sdr.Mdc1200Framer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sin

class Mdc1200Test {
    private fun blockOf(burst: IntArray) = burst.copyOfRange(56 + 40, burst.size)

    @Test fun blockRoundTripsAndCrcCatchesErrors() {
        for ((op, arg, unit) in listOf(Triple(0x01, 0x80, 0x1234), Triple(0x01, 0x00, 0x1777), Triple(0x00, 0x80, 0xABCD), Triple(0x12, 0x05, 0x0001))) {
            assertEquals(Mdc1200.Message(op, arg, unit), Mdc1200.decode(blockOf(Mdc1200.burst(op, arg, unit))))
        }
        val b = blockOf(Mdc1200.burst(0x01, 0x80, 0x1234))
        b[3] = b[3] xor 1
        assertNull(Mdc1200.decode(b))
        assertEquals(Mdc1200.Operation.PttEnd, Mdc1200.label(0x01, 0x00))
        assertEquals(Mdc1200.Operation.Ptt, Mdc1200.label(0x01, 0x80))
        assertEquals(Mdc1200.Operation.Emergency, Mdc1200.label(0x00, 0x80))
        assertNull(Mdc1200.label(0x7F, 0))
    }

    @Test fun burstThroughTheDemodulator() {
        for (invert in listOf(false, true)) {
            val got = ArrayList<Mdc1200.Message>()
            val framer = Mdc1200Framer { got += it }
            val demod = FfskDemodulator(50_000.0) { framer.tone(it) }
            val tones = Mdc1200.precode(Mdc1200.burst(0x01, 0x80, 0x4321))
            val rate = 50_000.0
            var ph = 0.0
            val rnd = java.util.Random(5)
            repeat(20_000) { demod.push((rnd.nextGaussian() * 800).toFloat()) }
            val spb = rate / (1200 * 0.996)
            for (i in 0 until (tones.size * spb).toInt()) {
                val t = tones[(i / spb).toInt()]
                ph += 2 * PI * (if (t == 1) 1200.0 else 1800.0) / rate
                demod.push(((if (invert) -1 else 1) * 3000 * sin(ph) + rnd.nextGaussian() * 300).toFloat())
            }
            repeat(20_000) { demod.push((rnd.nextGaussian() * 800).toFloat()) }
            assertEquals("invert=$invert", listOf(Mdc1200.Message(0x01, 0x80, 0x4321)), got)
        }
    }

    @Test fun noiseGivesNothing() {
        val got = ArrayList<Mdc1200.Message>()
        val framer = Mdc1200Framer { got += it }
        val demod = FfskDemodulator(50_000.0) { framer.tone(it) }
        val rnd = java.util.Random(1)
        repeat(3_000_000) { demod.push((rnd.nextGaussian() * 2000).toFloat()) }
        assertTrue(got.toString(), got.isEmpty())
    }

    private fun run(demod: FfskDemodulator, file: String, iq: Boolean) {
        val bb = ByteBuffer.wrap(javaClass.classLoader!!.getResourceAsStream("mdc1200/$file")!!.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var pr = 1f
        var pi = 0f
        if (!iq) { while (bb.remaining() >= 2) demod.push(bb.short / 32768f * 5000f); return }
        while (bb.remaining() >= 4) {
            val re = bb.short / 32768f
            val im = bb.short / 32768f
            demod.push(atan2(im * pr - re * pi, re * pr + im * pi) * 48_000f / (2 * PI.toFloat()))
            pr = re; pi = im
        }
    }

    /** A real Motorola radio (unit 1777, PTT ID at release), FM-demodulated here. */
    @Test fun realMotorolaCapture() {
        val got = ArrayList<Mdc1200.Message>()
        val framer = Mdc1200Framer { got += it }
        run(FfskDemodulator(48_000.0) { framer.tone(it) }, "mdc1200_unit1777_pttid_end_48k.cs16", true)
        assertTrue("${got.size} bursts", got.isNotEmpty())
        assertTrue(got.toString(), got.all { it == Mdc1200.Message(0x01, 0x00, 0x1777) })
    }

    /** The reference modem's audio (op 01, arg 80, unit 1234), used as discriminator output. */
    @Test fun referenceModemAudio() {
        val got = ArrayList<Mdc1200.Message>()
        val framer = Mdc1200Framer { got += it }
        run(FfskDemodulator(48_000.0) { framer.tone(it) }, "mdc1200_ref_01_80_1234_48k.s16", false)
        assertTrue("${got.size} bursts", got.isNotEmpty())
        assertTrue(got.toString(), got.all { it == Mdc1200.Message(0x01, 0x80, 0x1234) })
    }
}
