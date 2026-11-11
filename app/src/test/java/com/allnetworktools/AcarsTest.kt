package com.allnetworktools

import com.allnetworktools.data.sdr.Acars
import com.allnetworktools.data.sdr.AcarsDecoder
import com.allnetworktools.data.sdr.AcarsMessage
import com.allnetworktools.data.sdr.AcarsReceiver
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AcarsTest {
    /** Bits of the frame, least significant bit first, after a pre-key of ones. */
    private fun bits(frame: ByteArray): IntArray {
        val out = ArrayList<Int>()
        repeat(40) { out += 1 }
        for (b in frame) for (i in 0 until 8) out += (b.toInt() shr i) and 1
        repeat(16) { out += 1 }
        return out.toIntArray()
    }

    /** MSK audio at [fs]: 2400 Hz or 1200 Hz per bit, continuous phase. [differential] codes a 0 as a tone change. */
    private fun audio(bits: IntArray, fs: Double, differential: Boolean, noise: Double, seed: Long = 1, high: Double = 1.0, start: Int = 1000): FloatArray {
        val spb = fs / 2400
        val rnd = Random(seed)
        val out = FloatArray(((bits.size + 10) * spb).toInt() + 4000)
        var phase = 0.0
        var tone = 1
        var t = start
        bits.forEachIndexed { k, b ->
            tone = if (differential) (if (b == 1) tone else 1 - tone) else b
            val f = if (tone == 1) 2400.0 else 1200.0
            val a = if (tone == 1) high else 1.0
            val end = start + (k + 1) * spb
            while (t < end) {
                out[t] = (a * sin(phase)).toFloat()
                phase += 2 * PI * f / fs
                t++
            }
        }
        // Noise everywhere, before and after the message too, as on air.
        for (i in out.indices) out[i] += (rnd.nextGaussian() * noise).toFloat()
        return out
    }

    private fun decode(a: FloatArray): List<AcarsMessage> {
        val got = ArrayList<AcarsMessage>()
        val d = AcarsDecoder(48_000.0, 131.725) { got += it }
        var i = 0
        while (i < a.size) { val n = minOf(1000, a.size - i); d.feed(a.copyOfRange(i, i + n), n); i += n }
        return got
    }

    private val sample = Acars.frame('2', ".F-GKXA", 'N', "H1", '3', "M01AAF1234#DFBPOS N48123E002456")

    @Test fun crcAndParsing() {
        val body = sample.copyOfRange(5, sample.size - 3)
        val c = Acars.crc(body, 0, body.size)
        assertEquals(c and 0xFF, sample[sample.size - 3].toInt() and 0xFF)
        val m = Acars.parse(body, body.size, 131.525, 0)!!
        assertEquals("F-GKXA", m.registration)
        assertEquals("H1", m.label)
        assertEquals("M01A", m.msgNo)
        assertEquals("AF1234", m.flight)
        assertEquals("#DFBPOS N48123E002456", m.text)
        assertTrue(m.downlink)
    }

    @Test fun decodesBothToneConventions() {
        for (diff in listOf(false, true)) {
            val got = decode(audio(bits(sample), 48_000.0, diff, 0.3))
            assertEquals("differential=$diff", 1, got.size)
            assertEquals("AF1234", got[0].flight)
        }
    }

    /** What failed on air: noise before the message, a long text, tones at different levels, a clock a little off. */
    @Test fun longMessageInNoiseWithUnevenTones() {
        val text = "M02AAF1234" + "/POS N4812.3 E00245.6 FL350 SPD 452 ETA 1423 FOB 0123 WIND 270/045 TEMP M54 RMK ALL NORMAL ONBOARD THANKS"
        val frame = Acars.frame('2', ".F-HPJA", 'N', "H1", '5', text)
        var ok = 0
        for (s in 0 until 10) {
            val a = audio(bits(frame), 48_000.0 * (1 + 100e-6), s % 2 == 0, 0.5, s.toLong(), high = 0.6, start = 1000 + s)
            val got = decode(a)
            assertTrue("seed $s: ${got.size} copies", got.size <= 1)
            if (got.singleOrNull()?.text == text.substring(10)) ok++
        }
        assertTrue("$ok/10", ok >= 9)
    }

    @Test fun mendsABadCharacterWithTheBcs() {
        val frame = sample.copyOf()
        // A bit flipped in the text: its parity is wrong, the BCS tells which value it was.
        frame[30] = (frame[30].toInt() xor 0x04).toByte()
        val got = decode(audio(bits(frame), 48_000.0, false, 0.2))
        assertEquals(1, got.size)
        assertEquals("#DFBPOS N48123E002456", got[0].text)
    }

    @Test fun twoMessagesBackToBack() {
        val a = audio(bits(sample), 48_000.0, false, 0.3, 1)
        val b = audio(bits(Acars.frame('2', ".F-HBXA", 'N', "Q0", '4', null)), 48_000.0, false, 0.3, 2)
        val got = decode(a + b)
        assertEquals(listOf("F-GKXA", "F-HBXA"), got.map { it.registration })
    }

    @Test fun receivesThroughTheHackRfChain() {
        val fs = AcarsReceiver.SAMPLE_RATE.toDouble()
        val a = audio(bits(Acars.frame('2', ".N123AB", 'N', "Q0", 'S', null)), 48_000.0, false, 0.0)
        // AM on the 131.825 MHz channel, upsampled by holding each audio sample 50 times.
        val n = a.size * 50
        val re = FloatArray(n)
        val im = FloatArray(n)
        val rnd = Random(4)
        val w = 2 * PI * (131_825_000L - Acars.CenterHz).toDouble() / fs
        for (i in 0 until n) {
            val env = 0.4 * (1 + 0.6 * a[i / 50])
            re[i] = (env * cos(w * i) + rnd.nextGaussian() * 0.02).toFloat()
            im[i] = (env * sin(w * i) + rnd.nextGaussian() * 0.02).toFloat()
        }
        val got = ArrayList<AcarsMessage>()
        val rx = AcarsReceiver { got += it }
        var i = 0
        while (i < n) { val k = minOf(65536, n - i); rx.feedFloat(re.copyOfRange(i, i + k), im.copyOfRange(i, i + k), k); i += k }
        assertEquals(1, got.size)
        assertEquals("N123AB", got[0].registration)
        assertEquals(131.825, got[0].freqMhz, 1e-9)
        assertEquals("Q0", got[0].label)
    }

    /**
     * The "+ *" bit-sync characters go out while the transmitter keys up and often arrive damaged on real signals
     * (issue #6: a HackRF heard nothing that BrowSDR decoded on 131.725 MHz). The frame must be read from SYN SYN SOH.
     */
    @Test fun damagedBitSyncCharactersDoNotLoseTheMessage() {
        val f = Acars.frame('2', "RA89047", 'N', "Q0", '5', null)
        // "+" and "*" replaced by garbage, as when the carrier is still rising.
        f[0] = 0x55; f[1] = 0x00
        assertEquals(listOf("RA89047"), decode(audio(bits(f), 48_000.0, false, 0.2)).map { it.registration })
        // And with no bit-sync characters at all.
        val g = Acars.frame('2', ".JY-RAC", 'N', "Q0", '6', null)
        val bare = g.copyOfRange(2, g.size)
        assertEquals(listOf("JY-RAC"), decode(audio(bits(bare), 48_000.0, false, 0.2)).map { it.registration })
    }
}
