package com.allnetworktools

import com.allnetworktools.data.sdr.FfskDemodulator
import com.allnetworktools.data.sdr.FleetSync
import com.allnetworktools.data.sdr.FleetSyncFramer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sin

class FleetSyncTest {
    private fun frameOf(burst: IntArray) = burst.copyOfRange(32 + 16, burst.size)

    @Test fun identityRoundTripsInBothFormats() {
        for ((fleet, unit) in listOf(99 to 999, 107 to 1772, 200 to 3000, 354 to 5094)) {
            for (fs2 in listOf(false, true)) {
                val m = FleetSync.decode(frameOf(FleetSync.burst(fleet, unit, fs2)).let { if (fs2) it else it + IntArray(FleetSync.FRAME_BITS - it.size) })
                assertEquals("$fleet/$unit fs2=$fs2", Triple(fleet, unit, fs2), m?.let { Triple(it.fleet, it.unit, it.fs2) })
            }
        }
    }

    @Test fun fleetSyncTwoMendsOneBitPerCodeWord() {
        val frame = frameOf(FleetSync.burst(107, 1772, true))
        // One wrong bit in each 15-bit code word: all mended.
        for (k in 0 until 16) frame[4 + k * 16 + 3] = frame[4 + k * 16 + 3] xor 1
        assertEquals(1772, FleetSync.decode(frame)?.unit)
        // FleetSync I has no correction: one wrong bit fails the block check.
        val one = frameOf(FleetSync.burst(107, 1772, false))
        one[20] = one[20] xor 1
        assertNull(FleetSync.decode(one))
    }

    /** The tones as an FM discriminator gives them (Hz of deviation), through the demodulator and the framer. */
    @Test fun ffskBurstThroughTheDemodulator() {
        for (invert in listOf(false, true)) {
            val got = ArrayList<FleetSync.Message>()
            val framer = FleetSyncFramer { got += it }
            val demod = FfskDemodulator(50_000.0) { framer.bit(it) }
            val bits = FleetSync.burst(130, 2468, true)
            val rate = 50_000.0
            var ph = 0.0
            val rnd = java.util.Random(3)
            // Voice-like noise before and after, a clock 0.5 % off.
            repeat(20_000) { demod.push((rnd.nextGaussian() * 800).toFloat()) }
            val spb = rate / (1200 * 1.005)
            for (i in 0 until (bits.size * spb).toInt()) {
                val b = bits[(i / spb).toInt()] xor (if (invert) 1 else 0)
                ph += 2 * PI * (if (b == 1) 1200.0 else 1800.0) / rate
                demod.push((3000 * sin(ph) + rnd.nextGaussian() * 300).toFloat())
            }
            repeat(20_000) { demod.push((rnd.nextGaussian() * 800).toFloat()) }
            assertEquals("invert=$invert", listOf(Triple(130, 2468, true)), got.map { Triple(it.fleet, it.unit, it.fs2) })
        }
    }

    @Test fun noiseGivesNothing() {
        val got = ArrayList<FleetSync.Message>()
        val framer = FleetSyncFramer { got += it }
        val demod = FfskDemodulator(50_000.0) { framer.bit(it) }
        val rnd = java.util.Random(1)
        repeat(3_000_000) { demod.push((rnd.nextGaussian() * 2000).toFloat()) }
        assertTrue(got.toString(), got.isEmpty())
    }

    /** Two real Kenwood captures (GopherTrunk's fixtures): FM-demodulated here, then the same chain. */
    @Test fun realKenwoodBursts() {
        for ((file, fs2) in listOf("fleetsync1_fleet107_unit1772_48k.cs16" to false, "fleetsync2_fleet107_unit1772_48k.cs16" to true)) {
            val bytes = javaClass.classLoader!!.getResourceAsStream("fleetsync/$file")!!.readBytes()
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val got = ArrayList<FleetSync.Message>()
            val framer = FleetSyncFramer { got += it }
            val demod = FfskDemodulator(48_000.0) { framer.bit(it) }
            var pr = 1f
            var pi = 0f
            while (bb.remaining() >= 4) {
                val re = bb.short / 32768f
                val im = bb.short / 32768f
                val f = atan2(im * pr - re * pi, re * pr + im * pi) * 48_000f / (2 * PI.toFloat())
                pr = re; pi = im
                demod.push(f)
            }
            assertTrue("$file: ${got.size} bursts", got.size >= 2)
            assertTrue(got.toString(), got.all { it.fleet == 107 && it.unit == 1772 && it.fs2 == fs2 })
        }
    }
}
