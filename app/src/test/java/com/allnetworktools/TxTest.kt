package com.allnetworktools

import com.allnetworktools.data.sdr.AudioModulator
import com.allnetworktools.data.sdr.BufferAudio
import com.allnetworktools.data.sdr.Emission
import com.allnetworktools.data.sdr.ListenMode
import com.allnetworktools.data.sdr.ListenReceiver
import com.allnetworktools.data.sdr.TxMode
import com.allnetworktools.data.sdr.TxPolicy
import com.allnetworktools.data.sdr.TxSettings
import com.allnetworktools.data.sdr.TxStream
import com.allnetworktools.data.sdr.TxUse
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TxTest {
    /** Power of [f] in [x] at [fs] (Goertzel). */
    private fun power(x: FloatArray, fs: Double, f: Double): Double {
        val w = 2 * PI * f / fs
        var re = 0.0
        var im = 0.0
        for (i in x.indices) { re += x[i] * cos(w * i); im -= x[i] * sin(w * i) }
        return hypot(re, im) / x.size
    }

    /** A 1 kHz tone sent as [tx] and heard back with the Listen receiver as [rx]. */
    private fun loop(tx: TxMode, rx: ListenMode, shift: Double = 250_000.0, rxOffset: Double = 250_000.0): FloatArray {
        val audioRate = 40_000
        val tone = FloatArray(audioRate) { (0.6 * sin(2 * PI * 1_000.0 * it / audioRate)).toFloat() }
        val stream = TxStream(AudioModulator(BufferAudio(tone, audioRate), tx, deviationHz = 2_500.0, voice = false), 2_000_000, shift)
        val out = ArrayList<Float>()
        val listen = ListenReceiver(2_000_000) { b, n -> for (i in 0 until n) out += b[i] }
        listen.mode = rx
        listen.offsetHz = rxOffset
        val buf = ByteArray(131_072)
        var guard = 0
        while (!stream.finished && guard++ < 200) { stream.fill(buf); listen.feed(buf, buf.size) }
        assertTrue(stream.finished)
        // Skip the start (filters settling, AGC).
        val a = out.toFloatArray()
        return a.copyOfRange(a.size / 3, a.size * 2 / 3)
    }

    private fun assertTone(a: FloatArray, f: Double = 1_000.0) {
        val p = power(a, ListenReceiver.AUDIO_RATE.toDouble(), f)
        val off = listOf(500.0, 1_500.0, 2_000.0, 3_000.0).maxOf { power(a, ListenReceiver.AUDIO_RATE.toDouble(), it) }
        assertTrue("tone $p vs others $off", p > 5 * off && p > 0.01)
    }

    @Test fun fmGoesOutAndComesBack() = assertTone(loop(TxMode.Fm, ListenMode.Nfm))
    @Test fun amGoesOutAndComesBack() = assertTone(loop(TxMode.Am, ListenMode.Am))
    @Test fun usbGoesOutAndComesBack() = assertTone(loop(TxMode.Usb, ListenMode.Usb))
    @Test fun lsbGoesOutAndComesBack() = assertTone(loop(TxMode.Lsb, ListenMode.Lsb))

    /** In USB the tone sits above the carrier: listened to in LSB it is gone. */
    @Test fun usbHasNoLowerSideband() {
        val wrong = loop(TxMode.Usb, ListenMode.Lsb)
        val right = loop(TxMode.Usb, ListenMode.Usb)
        val fs = ListenReceiver.AUDIO_RATE.toDouble()
        assertTrue(power(right, fs, 1_000.0) > 10 * power(wrong, fs, 1_000.0))
    }

    @Test fun licenceAndBands() {
        TxSettings.setForTest("", false)
        val twoMetres = Emission.around(145_500_000, 6_000, 6_000, TxUse.Amateur)
        assertNotNull(TxPolicy.refuse(twoMetres))
        TxSettings.setForTest("F4ABC", false)
        assertNotNull(TxPolicy.refuse(twoMetres))
        TxSettings.setForTest("F4ABC", true)
        assertNull(TxPolicy.refuse(twoMetres))
        // Air band, marine band, FM broadcast: never.
        assertNotNull(TxPolicy.refuse(Emission.around(121_500_000, 6_000, 6_000, TxUse.Amateur)))
        assertNotNull(TxPolicy.refuse(Emission.around(156_800_000, 6_000, 6_000, TxUse.Amateur)))
        assertNotNull(TxPolicy.refuse(Emission.around(100_000_000, 6_000, 6_000, TxUse.Amateur)))
        // Over the band edge.
        assertNotNull(TxPolicy.refuse(Emission.around(14_349_000, 0, 3_000, TxUse.Amateur)))
        // The mesh radios need no licence on 868 MHz, but stay there.
        TxSettings.setForTest("", false)
        assertNull(TxPolicy.refuse(Emission.around(869_525_000, 125_000, 125_000, TxUse.Ism)))
        assertNotNull(TxPolicy.refuse(Emission.around(145_500_000, 125_000, 125_000, TxUse.Ism)))
    }

    @Test fun callsigns() {
        listOf("F4ABC", "K1A", "2E0ABC", "EA1XYZ", "VK2ABC", "DL1ABC/P", "F/ON4ABC", "9A1AA").forEach { assertTrue(it, TxPolicy.isCallsign(it)) }
        listOf("", "ABC", "12345", "HELLO", "F4").forEach { assertFalse(it, TxPolicy.isCallsign(it)) }
        assertEquals(true, TxPolicy.isCallsign("F4ABC-9"))
    }
}
