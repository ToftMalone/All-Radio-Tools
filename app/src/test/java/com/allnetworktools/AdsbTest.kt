package com.allnetworktools

import com.allnetworktools.data.sdr.AdsbDecode
import com.allnetworktools.data.sdr.AdsbDemodulator
import com.allnetworktools.data.sdr.AdsbInfo
import com.allnetworktools.data.sdr.AdsbTracker
import com.allnetworktools.data.sdr.Cpr
import com.allnetworktools.data.sdr.ModeS
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reference messages from "The 1090 MHz Riddle" (J. Sun, TU Delft). */
class AdsbTest {
    private fun m(hex: String) = ModeS.fromHex(hex)

    @Test fun parityAndIdentification() {
        val msg = m("8D4840D6202CC371C32CE0576098")
        assertTrue(ModeS.parityOk(msg))
        assertEquals(17, ModeS.df(msg))
        assertEquals(0x4840D6, ModeS.icao(msg))
        assertEquals(AdsbInfo.Identification("KLM1023", 0), AdsbDecode.decode(msg))
        // One flipped bit breaks the parity.
        val bad = msg.copyOf().also { it[6] = (it[6].toInt() xor 0x04).toByte() }
        assertFalse(ModeS.parityOk(bad))
    }

    @Test fun airbornePositionGlobalDecoding() {
        val even = AdsbDecode.decode(m("8D40621D58C382D690C8AC2863A7")) as AdsbInfo.Position
        val odd = AdsbDecode.decode(m("8D40621D58C386435CC412692AD6")) as AdsbInfo.Position
        assertFalse(even.odd)
        assertTrue(odd.odd)
        assertEquals(38000, even.altitudeFt)
        // The even frame is the newer one in the book's example.
        val (lat, lon) = Cpr.global(even.latCpr, even.lonCpr, odd.latCpr, odd.lonCpr, oddNewer = false)!!
        assertEquals(52.25720, lat, 1e-4)
        assertEquals(3.91937, lon, 1e-4)
        assertEquals(59, Cpr.nl(0.0))
        assertEquals(36, Cpr.nl(52.2572))
        assertEquals(1, Cpr.nl(88.0))
    }

    @Test fun velocities() {
        val gs = AdsbDecode.decode(m("8D485020994409940838175B284F")) as AdsbInfo.Velocity
        assertEquals(159.20, gs.speedKt!!, 0.01)
        assertEquals(182.88, gs.headingDeg!!, 0.01)
        assertEquals(-832, gs.verticalFtMin)
        assertFalse(gs.airspeed)
        val tas = AdsbDecode.decode(m("8DA05F219B06B6AF189400CBC33F")) as AdsbInfo.Velocity
        assertEquals(243.98, tas.headingDeg!!, 0.01)
        assertEquals(375.0, tas.speedKt!!, 0.01)
        assertEquals(-2304, tas.verticalFtMin)
        assertTrue(tas.airspeed)
    }

    @Test fun altitudeWithoutQBitIsNotGuessed() {
        assertNull(AdsbDecode.baroAltitude(0))
        assertNull(AdsbDecode.baroAltitude(0b1100_0010_0000)) // Q = 0: Gillham code
    }

    @Test fun trackerNeedsBothFramesWithinTenSeconds() {
        val t = AdsbTracker()
        t.update(m("8D40621D58C386435CC412692AD6"), -20.0, 1_000)
        assertNull(t.aircraft[0x40621D]!!.lat)
        t.update(m("8D40621D58C382D690C8AC2863A7"), -20.0, 3_000)
        val a = t.aircraft[0x40621D]!!
        assertEquals(52.2572, a.lat!!, 1e-3)
        assertEquals(38000, a.altitudeFt)
        t.update(m("8D4840D6202CC371C32CE0576098"), -25.0, 4_000)
        assertEquals("KLM1023", t.aircraft[0x4840D6]!!.callsign)
        t.prune(70_000)
        assertTrue(t.aircraft.isEmpty())
    }

    /** PPM waveform of [msgs] at 2 MS/s with random carrier phase, a frequency offset and noise, as HackRF bytes. */
    private fun waveform(msgs: List<ByteArray>, amp: Double, noise: Double, offsetHz: Double, seed: Long, blurred: Set<Pair<Int, Int>> = emptySet()): ByteArray {
        val rnd = Random(seed)
        val fs = 2_000_000.0
        val total = 4000 + msgs.size * 900
        val env = DoubleArray(total)
        var at = 1000 + 13 // odd start: frames do not land on a nice boundary
        msgs.forEachIndexed { k, msg ->
            for (p in intArrayOf(0, 2, 7, 9)) env[at + p] = 1.0
            for (bit in 0 until 112) {
                val one = (msg[bit / 8].toInt() shr (7 - bit % 8)) and 1 == 1
                // A blurred bit reads the same on both halves, as when a pulse edge falls on a sample.
                if (k to bit in blurred) { env[at + 16 + 2 * bit] = 0.5; env[at + 17 + 2 * bit] = 0.5; continue }
                env[at + 16 + 2 * bit + (if (one) 0 else 1)] = 1.0
            }
            at += 700 + rnd.nextInt(80)
        }
        val ph0 = rnd.nextDouble() * 2 * PI
        return ByteArray(total * 2) { i ->
            val n = i / 2
            val ph = ph0 + 2 * PI * offsetHz * n / fs
            val s = env[n] * amp * (if (i % 2 == 0) cos(ph) else sin(ph)) + rnd.nextGaussian() * noise + 6 // + DC offset
            s.toInt().coerceIn(-128, 127).toByte()
        }
    }

    @Test fun demodulatesFramesFromHackRfSamples() {
        val msgs = listOf("8D4840D6202CC371C32CE0576098", "8D40621D58C382D690C8AC2863A7", "8D485020994409940838175B284F", "8D40621D58C386435CC412692AD6").map(::m)
        val iq = waveform(msgs, amp = 60.0, noise = 6.0, offsetHz = 35_000.0, seed = 4)
        val got = ArrayList<String>()
        val d = AdsbDemodulator { msg, _ -> got += ModeS.hex(msg) }
        // Odd-sized chunks so frames straddle buffers.
        var i = 0
        while (i < iq.size) {
            val len = minOf(1234 * 2, iq.size - i)
            d.feed(iq.copyOfRange(i, i + len), len)
            i += len
        }
        assertEquals(msgs.map(ModeS::hex), got)
    }

    @Test fun uncertainBitsMendedForAKnownAircraftOnly() {
        val klm = m("8D4840D6202CC371C32CE0576098")
        // Bits 45 and 58 of the second copy are ones that read as uncertain: two bits to mend.
        val iq = waveform(listOf(klm, klm), amp = 60.0, noise = 0.0, offsetHz = 0.0, seed = 2, blurred = setOf(1 to 45, 1 to 58))
        val got = ArrayList<String>()
        val d = AdsbDemodulator { msg, _ -> got += ModeS.hex(msg) }
        d.feed(iq, iq.size)
        assertEquals(listOf(ModeS.hex(klm), ModeS.hex(klm)), got)
        assertEquals(1, d.mended)
        // Never heard cleanly: the same uncertain copy is not trusted.
        val lone = waveform(listOf(klm), amp = 60.0, noise = 0.0, offsetHz = 0.0, seed = 2, blurred = setOf(0 to 45))
        val none = ArrayList<ByteArray>()
        AdsbDemodulator { msg, _ -> none += msg }.feed(lone, lone.size)
        assertTrue(none.isEmpty())
    }

    @Test fun noiseAloneDecodesNothing() {
        val rnd = Random(9)
        val iq = ByteArray(2_000_000) { (rnd.nextGaussian() * 25).toInt().coerceIn(-128, 127).toByte() }
        val got = ArrayList<ByteArray>()
        AdsbDemodulator { msg, _ -> got += msg }.feed(iq, iq.size)
        assertTrue(got.isEmpty())
    }
}
