package com.allnetworktools

import com.allnetworktools.data.sdr.Sstv
import com.allnetworktools.data.sdr.SstvDecoder
import com.allnetworktools.data.sdr.SstvImage
import com.allnetworktools.data.sdr.SstvReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** SSTV audio from a picture, written after the published mode timings (a test signal only: nothing is transmitted). */
object SstvTx {
    fun picture(w: Int, h: Int) = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        val bars = intArrayOf(0xFFFFFF, 0xFFFF00, 0x00FFFF, 0x00FF00, 0xFF00FF, 0xFF0000, 0x0000FF, 0x000000)
        if (y < h * 3 / 5) bars[x * 8 / w] else { val g = x * 255 / (w - 1); (g shl 16) or (g shl 8) or g }
    }

    fun audio(m: Sstv.Mode, rgb: IntArray, withVis: Boolean, offHz: Double = 0.0, ppm: Double = 0.0): FloatArray {
        val w = m.width
        val y = DoubleArray(rgb.size); val ry = DoubleArray(rgb.size); val by = DoubleArray(rgb.size)
        for (i in rgb.indices) {
            val r = (rgb[i] shr 16 and 255).toDouble(); val g = (rgb[i] shr 8 and 255).toDouble(); val b = (rgb[i] and 255).toDouble()
            y[i] = 0.299 * r + 0.587 * g + 0.114 * b
            ry[i] = (128 + (r - y[i]) / 1.4).coerceIn(0.0, 255.0); by[i] = (128 + (b - y[i]) / 1.78).coerceIn(0.0, 255.0)
        }
        fun value(s: Sstv.Scan, line: Int, px: Int): Double = when (m.kind) {
            Sstv.Kind.Seq -> ((rgb[line * w + px] shr (if (s.comp == Sstv.R) 16 else if (s.comp == Sstv.G) 8 else 0)) and 255).toDouble()
            Sstv.Kind.Robot36 -> if (s.comp == Sstv.Y) y[line * w + px] else {
                val pl = if (line and 1 == 1) by else ry; val y0 = line and 1.inv()
                0.25 * (pl[y0 * w + 2 * px] + pl[y0 * w + 2 * px + 1] + pl[(y0 + 1) * w + 2 * px] + pl[(y0 + 1) * w + 2 * px + 1])
            }
            Sstv.Kind.Robot72 -> if (s.comp == Sstv.Y) y[line * w + px] else { val pl = if (s.comp == Sstv.RY) ry else by; 0.5 * (pl[line * w + 2 * px] + pl[line * w + 2 * px + 1]) }
            Sstv.Kind.PD -> when (s.comp) {
                Sstv.Y -> y[2 * line * w + px]
                Sstv.Y2 -> y[(2 * line + 1) * w + px]
                else -> { val pl = if (s.comp == Sstv.RY) ry else by; 0.5 * (pl[2 * line * w + px] + pl[(2 * line + 1) * w + px]) }
            }
        }
        fun lineFreq(line: Int, t: Double): Double {
            if (t >= m.syncOffMs && t < m.syncOffMs + m.syncMs) return 1200.0
            for (s in m.scans) if (t >= s.startMs && t < s.startMs + s.lenMs) return 1500.0 + 800.0 * value(s, line, minOf(s.px - 1, ((t - s.startMs) / s.lenMs * s.px).toInt())) / 255.0
            for (f in m.fixed) if (t >= f.startMs && t < f.startMs + f.lenMs) return if (f.hz < 0) (if (line and 1 == 1) 2300.0 else 1500.0) else f.hz
            return 1500.0
        }
        fun visFreq(t: Double): Double {
            if (t < 300) return 1900.0
            if (t < 310) return 1200.0
            if (t < 610) return 1900.0
            val j = ((t - 610) / 30).toInt()
            if (j == 0 || j == 9) return 1200.0
            val bit = if (j <= 7) (m.vis shr (j - 1)) and 1 else Integer.bitCount(m.vis) and 1
            return if (bit == 1) 1100.0 else 1300.0
        }
        val lead = 400.0; val visMs = if (withVis) 910.0 else 0.0; val img = m.totalLines * m.lineMs
        val total = lead + visMs + img + 600
        val n = (total * 8).toInt()
        val out = FloatArray(n)
        var ph = 0.0
        for (i in 0 until n) {
            var t = i * (1 - ppm * 1e-6) / 8.0 - lead
            val f = when {
                t < 0 -> 0.0
                t < visMs -> visFreq(t)
                else -> { t -= visMs; if (t >= img) 0.0 else { val line = minOf(m.totalLines - 1, (t / m.lineMs).toInt()); lineFreq(line, t - line * m.lineMs) } }
            }
            if (f <= 0) continue
            ph += 2 * PI * (f + offHz) / 8000.0
            out[i] = 0.5f * sin(ph).toFloat()
        }
        return out
    }
}

class SstvTest {
    private fun decode(audio: FloatArray): List<SstvImage> {
        val got = ArrayList<SstvImage>()
        val d = SstvDecoder { got += it }
        d.feed(audio, audio.size)
        d.feed(FloatArray(8000 * 4), 8000 * 4)
        return got
    }

    private fun meanError(a: IntArray, b: IntArray, w: Int, h: Int): Double {
        var e = 0.0; var n = 0
        // Away from the colour edges, where the filters blur.
        for (yy in 2 until h - 2) for (x in 4 until w - 4) {
            val i = yy * w + x
            if (x % (w / 8) in 0..3 || x % (w / 8) >= w / 8 - 4) continue
            for (s in intArrayOf(16, 8, 0)) e += abs(((a[i] shr s) and 255) - ((b[i] shr s) and 255))
            n += 3
        }
        return e / n
    }

    @Test fun decodesTheIssModesAndOthers() {
        for (name in listOf("Robot 36", "Martin M1", "Scottie S1", "PD120", "PD180")) {
            val m = Sstv.modes.first { it.name == name }
            val pic = SstvTx.picture(m.width, m.height)
            val got = decode(SstvTx.audio(m, pic, withVis = true, offHz = 35.0, ppm = 150.0))
            assertEquals(name, 1, got.size)
            val img = got[0]
            assertEquals(name, m.name, img.mode)
            assertTrue(name, img.complete && img.vis)
            val err = meanError(pic, img.rgb, m.width, m.height)
            assertTrue("$name: mean error $err", err < 12)
        }
    }

    @Test fun findsAModeWithoutItsHeader() {
        val m = Sstv.modes.first { it.name == "Martin M1" }
        val got = decode(SstvTx.audio(m, SstvTx.picture(m.width, m.height), withVis = false))
        assertEquals(1, got.size)
        assertEquals("Martin M1", got[0].mode)
        assertTrue(got[0].lines > 200)
    }

    /** PD120 as the ISS sends it: narrowband FM (±5 kHz) with a Doppler offset, through the receiver at 1 MS/s. */
    @Test fun throughFm() {
        val m = Sstv.modes.first { it.name == "PD120" }
        val pic = SstvTx.picture(m.width, m.height)
        val audio = SstvTx.audio(m, pic, withVis = true)
        val got = ArrayList<SstvImage>()
        val rx = SstvReceiver(SstvDecoder { got += it })
        val rnd = Random(1)
        val up = 125
        val chunk = 8000
        var ph = 0.0
        val doppler = -3_200.0
        var i = 0
        while (i < audio.size) {
            val k = minOf(chunk, audio.size - i)
            val re = FloatArray(k * up); val im = FloatArray(k * up)
            for (j in 0 until k * up) {
                val a = audio[i + j / up] / 0.5
                ph += 2 * PI * (doppler + 5_000.0 * a) / 1_000_000.0
                re[j] = (cos(ph) + 0.05 * rnd.nextGaussian()).toFloat(); im[j] = (sin(ph) + 0.05 * rnd.nextGaussian()).toFloat()
            }
            rx.feedComplex1M(re, im, re.size)
            i += k
        }
        val tail = FloatArray(1_000_000) { 0.05f * rnd.nextGaussian().toFloat() }
        repeat(3) { rx.feedComplex1M(tail, tail.copyOf(), tail.size) }
        assertEquals(1, got.size)
        assertEquals("PD120", got[0].mode)
        val err = meanError(pic, got[0].rgb, m.width, m.height)
        assertTrue("mean error $err", err < 16)
    }
}
