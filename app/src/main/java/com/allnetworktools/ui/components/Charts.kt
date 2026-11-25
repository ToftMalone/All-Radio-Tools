package com.allnetworktools.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.allnetworktools.ui.theme.Motion
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.util.fmt

/** Catmull-Rom spline through [points], as cubic Béziers. */
fun smoothPath(points: List<Offset>, path: Path = Path()): Path {
    path.reset()
    if (points.isEmpty()) return path
    path.moveTo(points[0].x, points[0].y)
    for (i in 0 until points.size - 1) {
        val p0 = points.getOrElse(i - 1) { points[i] }
        val p1 = points[i]
        val p2 = points[i + 1]
        val p3 = points.getOrElse(i + 2) { p2 }
        path.cubicTo(
            p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
            p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
            p2.x, p2.y,
        )
    }
    return path
}

/** Interpolates each sample from its previous value over [durationMs] (the mockups' 1 s linear path morph). */
@Composable
fun animatedSamples(samples: List<Float>, durationMs: Int): List<Float> {
    var from by remember { mutableStateOf(samples) }
    var to by remember { mutableStateOf(samples) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(samples) {
        val p = progress.value
        from = if (from.size == to.size) from.mapIndexed { i, v -> v + (to[i] - v) * p } else samples
        to = samples
        if (from.size != to.size) from = to
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMs, easing = LinearEasing))
    }
    val p = progress.value
    return if (from.size != to.size) to else to.mapIndexed { i, v -> from[i] + (v - from[i]) * p }
}

data class ChartBand(val from: Float, val to: Float, val color: Color)

/**
 * Sliding-window chart: dashed grid with labels, smoothed line, 14 % area and current-point dot.
 * [samples] are plotted left to right across [capacity] slots.
 */
@Composable
fun LiveChart(
    samples: List<Float>,
    min: Float,
    max: Float,
    gridStep: Float,
    color: Color,
    modifier: Modifier = Modifier,
    capacity: Int = samples.size,
    dotBorder: Color = cs.surfaceContainerLow,
    bands: List<ChartBand> = emptyList(),
    area: Boolean = true,
    morphMs: Int = 1000,
    height: Dp = 148.dp,
) {
    val values = animatedSamples(samples, morphMs)
    val gridColor = cs.outlineVariant
    val labelStyle = rf(10, 14)
    val labelColor = cs.onSurfaceVariant
    val gridValues = generateSequence(max) { it - gridStep }.takeWhile { it >= min - 0.01f }.toList()
    Box(modifier.fillMaxWidth().height(height)) {
        Canvas(Modifier.matchParentSize()) {
            val left = 32.dp.toPx()
            val top = 8.dp.toPx()
            val h = size.height - 28.dp.toPx()
            val w = size.width - left
            fun y(v: Float) = top + h - (v.coerceIn(min, max) - min) / (max - min) * h
            bands.forEach { b ->
                val y1 = y(maxOf(b.from, b.to)); val y2 = y(minOf(b.from, b.to))
                drawRect(b.color.copy(alpha = 0.08f), Offset(left, y1), Size(w, y2 - y1))
            }
            val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()))
            gridValues.forEach { g -> drawLine(gridColor, Offset(left, y(g)), Offset(size.width, y(g)), 1.dp.toPx(), pathEffect = dash) }
            if (values.size < 2) return@Canvas
            val n = maxOf(capacity, values.size)
            val step = w / (n - 1)
            val startX = left + (n - values.size) * step
            val pts = values.mapIndexed { i, v -> Offset(startX + i * step, y(v)) }
            val line = smoothPath(pts)
            if (area) {
                val fill = Path().apply {
                    addPath(line)
                    lineTo(pts.last().x, top + h); lineTo(pts.first().x, top + h); close()
                }
                drawPath(fill, color.copy(alpha = 0.14f))
            }
            drawPath(line, color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            drawCircle(dotBorder, 6.5f.dp.toPx(), pts.last())
            drawCircle(color, 5.dp.toPx() - 1.5f.dp.toPx() / 2, pts.last())
        }
        gridValues.forEach { g ->
            val frac = (max - g) / (max - min)
            Text(
                fmt(g),
                Modifier.offset(y = 8.dp + (height - 28.dp) * frac - 7.dp),
                style = labelStyle, color = labelColor,
            )
        }
    }
}

@Composable
fun Sparkline(samples: List<Float>, min: Float, max: Float, color: Color, modifier: Modifier = Modifier, areaAlpha: Float = 0.16f, morphMs: Int = 1000) {
    val values = animatedSamples(samples, morphMs)
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val pad = 2.dp.toPx()
        val h = size.height - pad * 2
        val step = size.width / (values.size - 1)
        val pts = values.mapIndexed { i, v -> Offset(i * step, pad + h - (v.coerceIn(min, max) - min) / (max - min) * h) }
        val line = smoothPath(pts)
        val fill = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
        drawPath(fill, color.copy(alpha = areaAlpha))
        drawPath(line, color, style = Stroke(2.5f.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** 180° arc gauge (−100 → −30 dBm by default), 18 dp stroke, value animated with spring(0.8, 380). */
@Composable
fun SignalGauge(
    value: Float?,
    color: Color,
    track: Color,
    label: String,
    display: String,
    range: ClosedFloatingPointRange<Float> = -100f..-30f,
    modifier: Modifier = Modifier,
) {
    val target = if (value == null) 0f else ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    val frac by animateFloatAsState(target, Motion.standard(), label = "gauge")
    // Drawn on a 300 × 184 grid: the arc's centre sits on the baseline (y 150) with a 124 radius, so the value
    // has 80 units of clear space above it inside the arc, and the end labels sit under each end of the arc.
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.widthIn(max = 300.dp).fillMaxWidth().aspectRatio(300f / 184f)) {
        val u = maxWidth / 300f
        Canvas(Modifier.matchParentSize()) {
            val k = size.width / 300f
            val stroke = Stroke(16.dp.toPx(), cap = StrokeCap.Round)
            val r = 124 * k
            val topLeft = Offset(150 * k - r, 150 * k - r)
            drawArc(track, 180f, 180f, false, topLeft, Size(r * 2, r * 2), style = stroke)
            if (frac > 0.001f) drawArc(color, 180f, 180f * frac, false, topLeft, Size(r * 2, r * 2), style = stroke)
        }
        Column(Modifier.fillMaxWidth().padding(top = u * 70), horizontalAlignment = Alignment.CenterHorizontally) {
            com.allnetworktools.ui.components.RollingText(display, style = gs(52, 56, 500, -1.5f, tnum = true))
            Text(label, Modifier.padding(top = 2.dp), style = rf(13, 18, 500), maxLines = 1)
        }
        val endColor = androidx.compose.material3.LocalContentColor.current.copy(alpha = 0.75f)
        Text(fmt(range.start), Modifier.width(u * 52).offset(x = u * 0, y = u * 164), style = rf(11, 14), color = endColor, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(fmt(range.endInclusive), Modifier.width(u * 52).offset(x = u * 248, y = u * 164), style = rf(11, 14), color = endColor, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** Small ring (7 dp stroke) colored by quality. */
@Composable
fun QualityRing(fraction: Float, color: Color, modifier: Modifier = Modifier, size: Dp = 56.dp, track: Color = cs.surfaceContainerHighest, stroke: Dp = 7.dp) {
    val f by animateFloatAsState(fraction.coerceIn(0.05f, 1f), Motion.standard(), label = "ring")
    val c by animateColorAsState(color, tween(300), label = "ringColor")
    Canvas(modifier.size(size)) {
        val sw = stroke.toPx() * (size.toPx() / 56.dp.toPx())
        val r = this.size.minDimension * 22f / 56f
        val tl = Offset(center.x - r, center.y - r)
        drawCircle(track, r, style = Stroke(sw))
        drawArc(c, -90f, 360f * f, false, tl, Size(r * 2, r * 2), style = Stroke(sw, cap = StrokeCap.Round))
    }
}
