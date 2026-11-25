package com.allnetworktools.ui.pages.sdr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A picture of the spectrum and waterfall to share: the trace, the waterfall rows, a frequency scale and a caption
 * (centre, width, marker, UTC time, app version). Drawn on its own canvas, the same in light and dark theme.
 */
object SpectrumSnapshot {
    const val WIDTH = 1600
    private const val PAD = 48f
    private const val SPECTRUM_H = 520f
    private const val WATERFALL_H = 420f
    private const val SCALE_H = 70f
    private const val CAPTION_H = 150f
    private const val BG = 0xFF101418.toInt()
    private const val PANEL = 0xFF1B2128.toInt()
    private const val GRID = 0x33FFFFFF
    private const val LINE = 0xFF7FD4E6.toInt()
    private const val HOLD = 0xAAE6B800.toInt()
    private const val MARKER = 0xFFF2A6D3.toInt()
    private const val TEXT = 0xFFE8EAED.toInt()
    private const val DIM = 0xFF9AA4AE.toInt()

    /** The caption under the picture, also given as the shared text. */
    fun caption(centerHz: Double, spanHz: Double, markerHz: Long?, markerDb: Float?, timeMs: Long, version: String): String = buildString {
        append("%.4f MHz ± %s MHz".format(Locale.US, centerHz / 1e6, widthLabel(spanHz / 2)))
        if (markerHz != null) {
            append(" · marker %.4f MHz".format(Locale.US, markerHz / 1e6))
            markerDb?.let { append(" %.0f dBFS".format(Locale.US, it)) }
        }
        append(" · ").append(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(timeMs)))
        append(" · All Radio Tools ").append(version)
    }

    fun render(
        db: FloatArray,
        hold: FloatArray?,
        waterfall: Bitmap?,
        centerHz: Double,
        spanHz: Double,
        markerHz: Long?,
        caption: String,
    ): Bitmap {
        val h = (PAD + SPECTRUM_H + 16 + WATERFALL_H + SCALE_H + CAPTION_H).toInt()
        val bmp = Bitmap.createBitmap(WIDTH, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(BG)
        val left = PAD
        val right = WIDTH - PAD
        val w = right - left
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        // Spectrum panel, -110 to -10 dBFS, a grid line every 20 dB.
        val sTop = PAD
        val sBottom = sTop + SPECTRUM_H
        fill.color = PANEL
        c.drawRoundRect(RectF(left, sTop, right, sBottom), 28f, 28f, fill)
        val grid = Paint().apply { color = GRID; strokeWidth = 2f }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DIM; textSize = 26f; typeface = Typeface.MONOSPACE }
        fun y(v: Float) = sBottom - 20 - (SPECTRUM_H - 40) * ((v + 110f) / 100f).coerceIn(0f, 1f)
        for (g in -100..-20 step 20) {
            c.drawLine(left + 12, y(g.toFloat()), right - 12, y(g.toFloat()), grid)
            c.drawText("$g", left + 18, y(g.toFloat()) - 8, label)
        }
        c.drawLine(left + w / 2, sTop + 12, left + w / 2, sBottom - 12, grid)
        fun trace(a: FloatArray, color: Int, width: Float) {
            val p = Path()
            a.forEachIndexed { i, v ->
                val x = left + binX(i, a.size, w)
                if (i == 0) p.moveTo(x, y(v)) else p.lineTo(x, y(v))
            }
            c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; this.color = color; strokeWidth = width })
        }
        hold?.let { trace(it, HOLD, 2.5f) }
        trace(db, LINE, 3.5f)
        val markerX = markerHz?.let { left + w * (((it - (centerHz - spanHz / 2)) / spanHz).toFloat()) }?.takeIf { it in left..right }
        markerX?.let { c.drawLine(it, sTop + 12, it, sBottom - 12, Paint().apply { color = MARKER; strokeWidth = 4f }) }
        // Waterfall, its rows stretched to the panel.
        val wTop = sBottom + 16
        val wBottom = wTop + WATERFALL_H
        if (waterfall != null) {
            c.save()
            c.clipRect(left, wTop, right, wBottom)
            c.drawBitmap(waterfall, Rect(0, 0, waterfall.width, waterfall.height), RectF(left, wTop, right, wBottom), Paint(Paint.FILTER_BITMAP_FLAG))
            c.restore()
        }
        markerX?.let { c.drawLine(it, wTop, it, wBottom, Paint().apply { color = MARKER; strokeWidth = 3f }) }
        // Frequency scale: the two edges and the centre.
        val scale = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DIM; textSize = 30f; typeface = Typeface.MONOSPACE }
        val sy = wBottom + 44
        fun mhz(hz: Double) = "%.3f".format(Locale.US, hz / 1e6)
        scale.textAlign = Paint.Align.LEFT; c.drawText(mhz(centerHz - spanHz / 2), left, sy, scale)
        scale.textAlign = Paint.Align.CENTER; c.drawText(mhz(centerHz), left + w / 2, sy, scale)
        scale.textAlign = Paint.Align.RIGHT; c.drawText(mhz(centerHz + spanHz / 2), right, sy, scale)
        // Caption.
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT; textSize = 38f; typeface = Typeface.DEFAULT_BOLD }
        val parts = caption.split(" · ")
        c.drawText(parts.first(), left, wBottom + SCALE_H + 56, title)
        val rest = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DIM; textSize = 28f }
        c.drawText(parts.drop(1).joinToString(" · "), left, wBottom + SCALE_H + 104, rest)
        return bmp
    }
}
