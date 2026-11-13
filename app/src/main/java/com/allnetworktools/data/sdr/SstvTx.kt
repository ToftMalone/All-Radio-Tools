package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.sin

/**
 * A picture as SSTV audio, computed as it is sent at [rate]: leader, VIS header, then each line's sync and scans after
 * the published mode timings ([Sstv.modes], the ones the receiver reads). [rgb] is the picture at the mode's size,
 * packed 0xRRGGBB.
 */
class SstvEncoder(private val m: Sstv.Mode, private val rgb: IntArray, override val rate: Int, private val level: Float = 0.5f) : AudioSource {
    private val w = m.width
    private val y = DoubleArray(rgb.size)
    private val ry = DoubleArray(rgb.size)
    private val by = DoubleArray(rgb.size)
    private val lead = 300.0
    private val visMs = 910.0
    private val img = m.totalLines * m.lineMs
    private val totalMs = lead + visMs + img + 300.0
    private var i = 0L
    private var ph = 0.0

    init {
        require(rgb.size == m.width * m.height) { "picture is not ${m.width}×${m.height}" }
        for (k in rgb.indices) {
            val r = (rgb[k] shr 16 and 255).toDouble(); val g = (rgb[k] shr 8 and 255).toDouble(); val b = (rgb[k] and 255).toDouble()
            y[k] = 0.299 * r + 0.587 * g + 0.114 * b
            ry[k] = (128 + (r - y[k]) / 1.4).coerceIn(0.0, 255.0)
            by[k] = (128 + (b - y[k]) / 1.78).coerceIn(0.0, 255.0)
        }
    }

    /** Seconds on air. */
    val durationS: Double get() = totalMs / 1000

    private fun value(s: Sstv.Scan, line: Int, px: Int): Double = when (m.kind) {
        Sstv.Kind.Seq -> ((rgb[line * w + px] shr (if (s.comp == Sstv.R) 16 else if (s.comp == Sstv.G) 8 else 0)) and 255).toDouble()
        Sstv.Kind.Robot36 -> if (s.comp == Sstv.Y) y[line * w + px] else {
            val pl = if (line and 1 == 1) by else ry
            val y0 = line and 1.inv()
            0.25 * (pl[y0 * w + 2 * px] + pl[y0 * w + 2 * px + 1] + pl[(y0 + 1) * w + 2 * px] + pl[(y0 + 1) * w + 2 * px + 1])
        }
        Sstv.Kind.Robot72 -> if (s.comp == Sstv.Y) y[line * w + px] else { val pl = if (s.comp == Sstv.RY) ry else by; 0.5 * (pl[line * w + 2 * px] + pl[line * w + 2 * px + 1]) }
        Sstv.Kind.PD -> when (s.comp) {
            Sstv.Y -> y[2 * line * w + px]
            Sstv.Y2 -> y[(2 * line + 1) * w + px]
            else -> { val pl = if (s.comp == Sstv.RY) ry else by; 0.5 * (pl[2 * line * w + px] + pl[(2 * line + 1) * w + px]) }
        }
    }

    private fun lineFreq(line: Int, t: Double): Double {
        if (t >= m.syncOffMs && t < m.syncOffMs + m.syncMs) return 1200.0
        for (s in m.scans) if (t >= s.startMs && t < s.startMs + s.lenMs) return 1500.0 + 800.0 * value(s, line, minOf(s.px - 1, ((t - s.startMs) / s.lenMs * s.px).toInt())) / 255.0
        for (f in m.fixed) if (t >= f.startMs && t < f.startMs + f.lenMs) return if (f.hz < 0) (if (line and 1 == 1) 2300.0 else 1500.0) else f.hz
        return 1500.0
    }

    /** Leader 1900 Hz, break 1200 Hz, leader, start bit, the 7 bits of the mode code and even parity (1100 Hz for 1), stop bit. */
    private fun visFreq(t: Double): Double {
        if (t < 300) return 1900.0
        if (t < 310) return 1200.0
        if (t < 610) return 1900.0
        val j = ((t - 610) / 30).toInt()
        if (j == 0 || j == 9) return 1200.0
        val bit = if (j <= 7) (m.vis shr (j - 1)) and 1 else Integer.bitCount(m.vis) and 1
        return if (bit == 1) 1100.0 else 1300.0
    }

    override fun next(out: FloatArray): Int {
        var k = 0
        while (k < out.size) {
            var t = i * 1000.0 / rate - lead
            if (t + lead >= totalMs) break
            val f = when {
                t < 0 -> 0.0
                t < visMs -> visFreq(t)
                else -> { t -= visMs; if (t >= img) 0.0 else { val line = minOf(m.totalLines - 1, (t / m.lineMs).toInt()); lineFreq(line, t - line * m.lineMs) } }
            }
            if (f > 0) { ph += 2 * PI * f / rate; if (ph > 2 * PI) ph -= 2 * PI; out[k] = level * sin(ph).toFloat() } else out[k] = 0f
            i++
            k++
        }
        return if (k == 0) -1 else k
    }

    companion object {
        /** The modes offered for sending: the common ones every receiver knows. */
        val SendModes = listOf("Robot 36", "Martin M1", "Scottie S1", "PD120")

        /**
         * Fits [src] (any size, 0xRRGGBB) to the mode: scaled to cover and centre-cropped. Uses a plain nearest sample, so it
         * runs on the JVM in tests; the app scales with Android first.
         */
        fun fit(src: IntArray, sw: Int, sh: Int, w: Int, h: Int): IntArray {
            val scale = maxOf(w.toDouble() / sw, h.toDouble() / sh)
            val ox = (sw * scale - w) / 2
            val oy = (sh * scale - h) / 2
            return IntArray(w * h) { k ->
                val x = ((k % w + ox) / scale).toInt().coerceIn(0, sw - 1)
                val yy = ((k / w + oy) / scale).toInt().coerceIn(0, sh - 1)
                src[yy * sw + x] and 0xFFFFFF
            }
        }
    }
}
