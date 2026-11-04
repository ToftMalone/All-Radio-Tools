package com.allnetworktools

import com.allnetworktools.data.sdr.Sarsat
import com.allnetworktools.data.sdr.SarsatBurst
import com.allnetworktools.data.sdr.SarsatReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class SarsatTest {
    @Test fun messageLayer() {
        val b = Sarsat.encode(true, 227, 7, (123L shl 14) or 4567, 43.6047, 1.4442)
        val o = Sarsat.decode(b)!!
        assertEquals("France", o.country)
        assertEquals("PLB", o.beaconType)
        assertEquals(0, o.corrected)
        assertTrue(o.bch2Ok)
        assertEquals("certificate 123, serial 4567", o.idText)
        assertEquals(43.6047, o.lat!!, 0.0012)
        assertEquals(1.4442, o.lon!!, 0.0012)
        assertTrue(o.fine)
        // Three errors in the first field and two in the second are repaired; the identity does not move.
        val d = b.copyOf().also { it[30] = it[30] xor 1; it[50] = it[50] xor 1; it[80] = it[80] xor 1; it[110] = it[110] xor 1; it[120] = it[120] xor 1 }
        val r = Sarsat.decode(d)!!
        assertEquals(5, r.corrected)
        assertEquals(o.hexId, r.hexId)
        assertEquals("OXTEST", Sarsat.baudotUnpack(Sarsat.baudotPack("OXTEST", 6), 6))
        assertEquals("Bermuda", Sarsat.country(310))
        assertNull(Sarsat.country(1))
    }

    private fun locate(name: String): File? = listOf("src/test/resources/$name", "app/src/test/resources/$name", "/tmp/sarsat/$name").map(::File).firstOrNull { it.exists() }

    /** A real PLB burst (OnAir issue #223): 16-bit I/Q at 37.5 kHz, carrier near +1.44 kHz, beacon 4016196C3F81FE0 from New Zealand. */
    @Test fun realBeacon() {
        val f = locate("sarsat/epirbsignal_223.wav")
        assumeTrue(f != null)
        val raw = f!!.readBytes()
        val frames = (raw.size - 44) / 4
        val inR = FloatArray(frames)
        val inI = FloatArray(frames)
        for (i in 0 until frames) {
            val o = 44 + 4 * i
            inR[i] = ((raw[o].toInt() and 0xFF) or (raw[o + 1].toInt() shl 8)).toShort() / 32768f
            inI[i] = ((raw[o + 2].toInt() and 0xFF) or (raw[o + 3].toInt() shl 8)).toShort() / 32768f
        }
        // Up to the 1 MS/s the app feeds the receiver, the carrier moved to 406.028 MHz (−22 kHz from the 406.05 MHz centre).
        val inRate = 37_500.0
        val outRate = 1_000_000.0
        val shift = -22_000.0 - 1_440.0
        val pad = outRate.toInt()
        val n = (frames * outRate / inRate).toInt() + 2 * pad
        val re = FloatArray(n)
        val im = FloatArray(n)
        val rnd = Random(1)
        for (i in 0 until n) {
            var vr = 0f; var vi = 0f
            if (i in pad until n - pad) {
                val p = (i - pad) * inRate / outRate
                val k = p.toInt(); val fr = (p - k).toFloat(); val k1 = minOf(k + 1, frames - 1)
                vr = inR[k] * (1 - fr) + inR[k1] * fr; vi = inI[k] * (1 - fr) + inI[k1] * fr
            }
            val ph = 2 * PI * shift * i / outRate
            val c = cos(ph).toFloat(); val s = sin(ph).toFloat()
            re[i] = vr * c - vi * s + 0.002f * rnd.nextGaussian().toFloat()
            im[i] = vr * s + vi * c + 0.002f * rnd.nextGaussian().toFloat()
        }
        val got = ArrayList<SarsatBurst>()
        val rx = SarsatReceiver(outRate) { got += it }
        var i = 0
        while (i < n) { val k = minOf(65536, n - i); rx.feed(re.copyOfRange(i, i + k), im.copyOfRange(i, i + k), k); i += k }
        assertEquals(1, got.size)
        val b = got[0].beacon
        assertEquals("4016196C3F81FE0", b.hexId)
        assertEquals("New Zealand", b.country)
        assertEquals("PLB", b.beaconType)
        assertEquals(-22_000.0, got[0].carrierHz, 300.0)
    }

    @Test fun quietOnNoise() {
        val rnd = Random(7)
        val got = ArrayList<SarsatBurst>()
        val rx = SarsatReceiver(1_000_000.0) { got += it }
        val k = 100_000
        repeat(40) { rx.feed(FloatArray(k) { rnd.nextGaussian().toFloat() }, FloatArray(k) { rnd.nextGaussian().toFloat() }, k) }
        assertTrue(got.isEmpty())
    }
}
