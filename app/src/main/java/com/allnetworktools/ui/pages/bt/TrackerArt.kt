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

/**
 * AirTag from the back, the side everyone recognises: a thick glossy white rim, a thin chrome ring, then the
 * mirror-polished steel disc (dark, it reflects the room) with the engraved lettering around and the white apple.
 */
private fun DrawScope.airTag() {
    val c = Offset(50f, 50f)
    shadowed(circle(50f, 50f, 43f)) { p ->
        // White plastic rim, lit from the top left.
        drawPath(p, Brush.linearGradient(listOf(Color.White, Color(0xFFF4F5F7), Color(0xFFD3D6DB)), Offset(15f, 10f), Offset(85f, 92f)))
        drawPath(p, Color(0xFFB9BDC3), style = Stroke(1f))
    }
    drawArc(Color.White, 150f, 110f, false, Offset(9f, 9f), Size(82f, 82f), style = Stroke(2f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    // Chrome ring.
    drawCircle(Brush.linearGradient(listOf(Color(0xFFF2F3F5), Color(0xFF8E939A), Color(0xFFE6E8EB), Color(0xFF7A7F86)), Offset(20f, 20f), Offset(80f, 80f)), 34f, c)
    // Mirror steel: near black, a touch lighter towards the edge, with a soft reflection.
    drawCircle(Brush.radialGradient(listOf(Color(0xFF0B0B0C), Color(0xFF141415), Color(0xFF2C2D30)), c, 32f), 31.5f, c)
    drawArc(Color.White.copy(alpha = 0.10f), 200f, 80f, false, Offset(22f, 22f), Size(56f, 56f), style = Stroke(5f))
    // Engraved lettering running round the disc: fine grey strokes, too small to read at icon size, as on the real thing.
    val rnd = java.util.Random(7)
    var ang = -80.0
    while (ang < 260) {
        val len = 2.0 + rnd.nextInt(4)
        if (rnd.nextInt(9) != 0) {
            val a0 = Math.toRadians(ang); val a1 = Math.toRadians(ang + len)
            drawLine(
                Color(0xFFB4B7BC).copy(alpha = 0.75f),
                Offset(50f + 26.5f * kotlin.math.cos(a0).toFloat(), 50f + 26.5f * kotlin.math.sin(a0).toFloat()),
                Offset(50f + 26.5f * kotlin.math.cos(a1).toFloat(), 50f + 26.5f * kotlin.math.sin(a1).toFloat()),
                1.4f,
            )
        }
        ang += len + 1.6
    }
    // The apple: two lobes on top, a narrower bottom, the bite on the right and the leaf.
    val body = Path().apply {
        addOval(androidx.compose.ui.geometry.Rect(39.5f, 42.5f, 51.5f, 62f))
        addOval(androidx.compose.ui.geometry.Rect(48.5f, 42.5f, 60.5f, 62f))
    }.let { p -> Path().also { it.op(p, Path().apply { addOval(androidx.compose.ui.geometry.Rect(42f, 50f, 58f, 64.5f)) }, PathOperation.Union) } }
    val notch = circle(50f, 42.5f, 2.6f)
    val bite = circle(62.6f, 51f, 4.6f)
    val apple = Path().also { it.op(body, notch, PathOperation.Difference) }.let { p -> Path().also { it.op(p, bite, PathOperation.Difference) } }
    drawPath(apple, Color.White)
    rotate(-40f, Offset(52.5f, 38.5f)) {
        drawOval(Color.White, Offset(50.5f, 34.2f), Size(4.2f, 8.6f))
    }
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

/** AirPods (3rd/4th generation): two round white heads on short stems, angled towards each other. */
private fun DrawScope.airPods() {
    fun bud(mirror: Boolean) {
        val sx = if (mirror) -1f else 1f
        withTransform({ scale(sx, 1f, Offset(50f, 50f)) }) {
            rotate(14f, Offset(30f, 48f)) {
                val head = Path().apply { addOval(androidx.compose.ui.geometry.Rect(14f, 22f, 46f, 52f)) }
                val stem = rounded(30f, 40f, 42f, 80f, 6f)
                val shape = Path().also { it.op(head, stem, PathOperation.Union) }
                shadowed(shape) { p ->
                    drawPath(p, Brush.linearGradient(listOf(Color.White, Color(0xFFE2E5EA)), Offset(14f, 22f), Offset(46f, 80f)))
                    drawPath(p, Color(0xFFB8BDC5), style = Stroke(1.6f))
                }
                // Speaker grille on the inner face, a vent on top, the microphone at the stem's tip.
                drawOval(Color(0xFF3A3D44), Offset(32f, 30f), Size(10f, 8f))
                drawOval(Color(0xFF8E939B), Offset(20f, 26f), Size(7f, 3.5f))
                drawRoundRect(Color(0xFFC9CDD3), Offset(33.5f, 74f), Size(5f, 3.5f), CornerRadius(1.75f, 1.75f))
            }
        }
    }
    bud(false)
    bud(true)
}

/** Galaxy SmartTag2: a flat, rounded capsule whose metal-lined key-ring hole fills nearly half of it. */
private fun DrawScope.smartTag() {
    val body = rounded(22f, 9f, 78f, 91f, 28f).minus(circle(50f, 35f, 16.5f))
    shadowed(body) { p ->
        drawPath(p, Brush.linearGradient(listOf(Color(0xFF4B4F57), Color(0xFF1C1E22)), Offset(22f, 9f), Offset(78f, 91f)))
        drawPath(p, Color(0xFF5E636C), style = Stroke(1.4f))
    }
    // The metal ring lining the hole.
    drawCircle(Brush.linearGradient(listOf(Color(0xFFE2E5EA), Color(0xFF8E939B)), Offset(34f, 19f), Offset(66f, 51f)), 16.5f, Offset(50f, 35f), style = Stroke(3.2f))
    // The button, a soft dot in the lower half.
    drawCircle(Color(0xFF34373D), 6.5f, Offset(50f, 72f))
    drawCircle(Color(0xFF666B74), 6.5f, Offset(50f, 72f), style = Stroke(1f))
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
