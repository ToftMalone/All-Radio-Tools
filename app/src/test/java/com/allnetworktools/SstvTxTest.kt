package com.allnetworktools

import com.allnetworktools.data.sdr.AudioModulator
import com.allnetworktools.data.sdr.Sstv
import com.allnetworktools.data.sdr.SstvDecoder
import com.allnetworktools.data.sdr.SstvEncoder
import com.allnetworktools.data.sdr.SstvImage
import com.allnetworktools.data.sdr.SstvReceiver
import com.allnetworktools.data.sdr.TxMode
import com.allnetworktools.data.sdr.TxStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SstvTxTest {
    private fun all(src: SstvEncoder): FloatArray {
        val out = ArrayList<Float>()
        val b = FloatArray(4096)
        while (true) { val n = src.next(b); if (n < 0) break; for (i in 0 until n) out += b[i] }
        return out.toFloatArray()
    }

    private fun meanError(a: IntArray, b: IntArray, w: Int, h: Int): Double {
        var e = 0.0; var n = 0
        for (yy in 2 until h - 2) for (x in 4 until w - 4) {
            val i = yy * w + x
            if (x % (w / 8) in 0..3 || x % (w / 8) >= w / 8 - 4) continue
            for (s in intArrayOf(16, 8, 0)) e += abs(((a[i] shr s) and 255) - ((b[i] shr s) and 255))
            n += 3
        }
        return e / n
    }

    /** Each mode offered for sending is read back by the app's SSTV decoder, picture and all. */
    @Test fun sentModesDecode() {
        for (name in SstvEncoder.SendModes) {
            val m = Sstv.modes.first { it.name == name }
            val pic = SstvTx.picture(m.width, m.height)
            val got = ArrayList<SstvImage>()
            val d = SstvDecoder { got += it }
            val a = all(SstvEncoder(m, pic, 8_000))
            d.feed(a, a.size)
            d.feed(FloatArray(8000 * 4), 8000 * 4)
            assertEquals(name, 1, got.size)
            assertEquals(name, m.name, got[0].mode)
            assertTrue(name, got[0].complete && got[0].vis)
            assertTrue("$name: ${meanError(pic, got[0].rgb, m.width, m.height)}", meanError(pic, got[0].rgb, m.width, m.height) < 12)
        }
    }

    /** Through FM at 2 MS/s, a quarter of the rate above the tuned frequency as the SSTV tool listens, and its receiver. */
    @Test fun robot36ThroughFm() {
        val m = Sstv.modes.first { it.name == "Robot 36" }
        val pic = SstvTx.picture(m.width, m.height)
        val stream = TxStream(AudioModulator(SstvEncoder(m, pic, 40_000), TxMode.Fm, 3_000.0, voice = false), 2_000_000, 500_000.0)
        val got = ArrayList<SstvImage>()
        val rx = SstvReceiver(SstvDecoder { got += it })
        val buf = ByteArray(131_072)
        var guard = 0
        while (!stream.finished && guard++ < 2_000) { stream.fill(buf); rx.feed(buf, buf.size) }
        assertTrue(got.isNotEmpty())
        val img = got.last()
        assertEquals("Robot 36", img.mode)
        assertTrue(meanError(pic, img.rgb, m.width, m.height) < 20)
    }

    @Test fun fitCoversAndCrops() {
        // A wide picture: the middle is kept.
        val src = IntArray(400 * 100) { i -> if (i % 400 < 200) 0xFF0000 else 0x0000FF }
        val out = SstvEncoder.fit(src, 400, 100, 320, 256)
        assertEquals(320 * 256, out.size)
        assertEquals(0xFF0000, out[0])
        assertEquals(0x0000FF, out[319])
    }
}
