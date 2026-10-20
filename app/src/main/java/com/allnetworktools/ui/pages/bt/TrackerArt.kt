package com.allnetworktools.ui.pages.bt

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import com.allnetworktools.data.TrackerKind

/**
 * Drawings of the tags themselves, made for this app rather than generic symbols: the white AirTag disc, the iPhone,
 * a pair of AirPods, the Galaxy SmartTag2 with its key-ring hole, the Tile Mate and a Find Hub tag. Drawn on a
 * 100 × 100 grid with a soft shadow, so they read on any background colour.
 */
@Composable
fun TrackerArt(kind: TrackerKind, modifier: Modifier) {
    Canvas(modifier) {
        val k = size.minDimension / 100f
        withTransform({
            translate((size.width - 100 * k) / 2, (size.height - 100 * k) / 2)
            scale(k, k, Offset.Zero)
        }) {
            when (kind) {
                TrackerKind.AirTag -> airTag()
                TrackerKind.AppleDevice -> iPhone()
                TrackerKind.AirPods -> airPods()
                TrackerKind.SmartTag -> smartTag()
                TrackerKind.Tile -> tileMate()
                TrackerKind.GoogleTag -> findHubTag()
            }
        }
    }
}

/** The art on a coloured tile, in place of a symbol icon. */
@Composable
fun TrackerBadge(kind: TrackerKind, bg: Color, size: Dp, shape: Shape, artSize: Dp) {
    Box(Modifier.size(size).clip(shape).background(bg), contentAlignment = Alignment.Center) {
        TrackerArt(kind, Modifier.size(artSize))
    }
}

private val Shadow = Color.Black.copy(alpha = 0.22f)

/** Draws [shape] once offset and dark (the shadow), then lets [body] paint it. */
private fun DrawScope.shadowed(shape: Path, body: DrawScope.(Path) -> Unit) {
    translate(1.5f, 3f) { drawPath(shape, Shadow) }
    body(shape)
}

private fun circle(cx: Float, cy: Float, r: Float) = Path().apply { addOval(androidx.compose.ui.geometry.Rect(cx - r, cy - r, cx + r, cy + r)) }

private fun rounded(l: Float, t: Float, r: Float, b: Float, radius: Float) =
    Path().apply { addRoundRect(RoundRect(l, t, r, b, CornerRadius(radius, radius))) }

private fun Path.minus(other: Path) = Path().also { it.op(this, other, PathOperation.Difference) }

/** AirTag: a glossy white disc, slightly domed, with its polished rim. */
private fun DrawScope.airTag() {
    shadowed(circle(50f, 50f, 40f)) { p ->
        drawPath(p, Brush.radialGradient(listOf(Color.White, Color(0xFFF1F2F4), Color(0xFFD9DCE1)), Offset(42f, 40f), 48f))
        drawPath(p, Color(0xFFB8BDC5), style = Stroke(2.2f))
    }
    // The dome's edge and a highlight.
    drawCircle(Color(0xFFE3E6EA), 31f, Offset(50f, 50f), style = Stroke(1.6f))
    drawOval(Color.White.copy(alpha = 0.9f), Offset(28f, 24f), Size(22f, 12f))
}

/** iPhone: dark frame, colourful screen, Dynamic Island. */
private fun DrawScope.iPhone() {
    shadowed(rounded(27f, 6f, 73f, 94f, 12f)) { p ->
        drawPath(p, Brush.linearGradient(listOf(Color(0xFF4A4D55), Color(0xFF1E2024)), Offset(27f, 6f), Offset(73f, 94f)))
    }
    drawPath(
        rounded(30.5f, 9.5f, 69.5f, 90.5f, 9f),
        Brush.linearGradient(listOf(Color(0xFF5B8DEF), Color(0xFF7B5CD6), Color(0xFFE0679B)), Offset(30f, 10f), Offset(70f, 90f)),
    )
    drawRoundRect(Color.Black, Offset(43f, 13.5f), Size(14f, 4.5f), CornerRadius(2.25f, 2.25f))
    // Side buttons.
    drawRoundRect(Color(0xFF2A2C31), Offset(73f, 30f), Size(1.8f, 14f), CornerRadius(1f, 1f))
    drawRoundRect(Color(0xFF2A2C31), Offset(25.2f, 26f), Size(1.8f, 8f), CornerRadius(1f, 1f))
    drawRoundRect(Color(0xFF2A2C31), Offset(25.2f, 38f), Size(1.8f, 10f), CornerRadius(1f, 1f))
}

/** AirPods: two white buds with their stems, the dark speaker grille facing out. */
private fun DrawScope.airPods() {
    fun bud(mirror: Boolean) {
        val sx = if (mirror) -1f else 1f
        withTransform({ scale(sx, 1f, Offset(50f, 50f)) }) {
            rotate(-12f, Offset(36f, 50f)) {
                val head = Path().apply { addOval(androidx.compose.ui.geometry.Rect(22f, 16f, 46f, 40f)) }
                val stem = rounded(31f, 30f, 41f, 86f, 5f)
                val shape = Path().also { it.op(head, stem, PathOperation.Union) }
                shadowed(shape) { p ->
                    drawPath(p, Brush.linearGradient(listOf(Color.White, Color(0xFFE2E5EA)), Offset(22f, 16f), Offset(46f, 86f)))
                    drawPath(p, Color(0xFFB8BDC5), style = Stroke(1.6f))
                }
                drawOval(Color(0xFF3A3D44), Offset(27f, 21f), Size(9f, 6f))
                drawRoundRect(Color(0xFFC9CDD3), Offset(33.5f, 80f), Size(5f, 4f), CornerRadius(2f, 2f))
            }
        }
    }
    bud(false)
    bud(true)
}

/** Galaxy SmartTag2: a long dark pebble with a big key-ring hole at the top and a round button. */
private fun DrawScope.smartTag() {
    val body = rounded(29f, 5f, 71f, 95f, 21f).minus(circle(50f, 25f, 10.5f))
    shadowed(body) { p ->
        drawPath(p, Brush.linearGradient(listOf(Color(0xFF4B4F57), Color(0xFF1C1E22)), Offset(29f, 5f), Offset(71f, 95f)))
        drawPath(p, Color(0xFF5E636C), style = Stroke(1.4f))
    }
    drawCircle(Color(0xFF6B7079), 10.5f, Offset(50f, 25f), style = Stroke(1.2f))
    drawCircle(Brush.radialGradient(listOf(Color(0xFF3A3E45), Color(0xFF26282D)), Offset(48f, 64f), 12f), 10f, Offset(50f, 66f))
    drawCircle(Color(0xFF6B7079), 10f, Offset(50f, 66f), style = Stroke(1.2f))
}

/** Tile Mate: a white rounded square, the key-ring hole in a corner, the logo plate in the middle. */
private fun DrawScope.tileMate() {
    val body = rounded(14f, 14f, 86f, 86f, 17f).minus(circle(27.5f, 27.5f, 5.5f))
    shadowed(body) { p ->
        drawPath(p, Brush.linearGradient(listOf(Color.White, Color(0xFFE4E7EB)), Offset(14f, 14f), Offset(86f, 86f)))
        drawPath(p, Color(0xFFB8BDC5), style = Stroke(1.8f))
    }
    drawCircle(Color(0xFFB8BDC5), 5.5f, Offset(27.5f, 27.5f), style = Stroke(1.4f))
    // The "tile" mark: a small dark rounded square with a white dot.
    drawRoundRect(Color(0xFF1F2328), Offset(41f, 47f), Size(18f, 18f), CornerRadius(5f, 5f))
    drawCircle(Color.White, 3f, Offset(50f, 56f))
}

/** Find Hub tag (Moto Tag, Chipolo, Pebblebee…): a dark disc on a key-ring tab, its button ringed in Google's colours. */
private fun DrawScope.findHubTag() {
    val tab = rounded(40f, 4f, 60f, 26f, 8f).minus(circle(50f, 13f, 4.5f))
    val disc = circle(50f, 57f, 37f)
    val shape = Path().also { it.op(tab, disc, PathOperation.Union) }
    shadowed(shape) { p ->
        drawPath(p, Brush.linearGradient(listOf(Color(0xFF555A63), Color(0xFF24272C)), Offset(13f, 4f), Offset(87f, 94f)))
        drawPath(p, Color(0xFF6B7079), style = Stroke(1.4f))
    }
    val colors = listOf(Color(0xFF4285F4), Color(0xFFEA4335), Color(0xFFFBBC05), Color(0xFF34A853))
    colors.forEachIndexed { i, c ->
        drawArc(c, -90f + i * 90f + 6f, 78f, false, Offset(31f, 38f), Size(38f, 38f), style = Stroke(4.5f))
    }
    drawCircle(Brush.radialGradient(listOf(Color(0xFF41454C), Color(0xFF2B2E33)), Offset(48f, 55f), 14f), 13f, Offset(50f, 57f))
}
