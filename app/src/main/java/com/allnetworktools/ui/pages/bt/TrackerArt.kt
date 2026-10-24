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
import androidx.compose.ui.graphics.nativeCanvas
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
                TrackerKind.GalaxyBuds -> galaxyBuds()
                TrackerKind.Tile -> tileMate()
                TrackerKind.GoogleTag -> findHubTag()
                TrackerKind.Chipolo -> chipolo()
                TrackerKind.Pebblebee -> pebblebee()
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
    // The engraved lettering, running round the disc as on the real tag.
    val r = 25.6f
    // Start at the top left, as on the tag, and size the text so it closes the circle exactly.
    val ring = android.graphics.Path().apply { addArc(50f - r, 50f - r, 50f + r, 50f + r, 140f, 359.9f) }
    val text = "Bluetooth LE • Ultra Wideband • AirTag • Designed by Apple in California • Assembled in China • "
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(220, 0xC4, 0xC7, 0xCC)
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL)
        textSize = 3f
    }
    val circumference = (2 * Math.PI * r).toFloat()
    paint.textSize = 3f * circumference * 0.985f / paint.measureText(text)
    drawContext.canvas.nativeCanvas.drawTextOnPath(text, ring, 0f, 0f, paint)
    drawPath(applePath(), Color.White)
}

/**
 * The apple, drawn on a 100 × 120 box with cubic curves (two shoulders around the stem's dip, the round bite on the
 * right, the two small bumps at the bottom, the almond leaf leaning right), then placed at the disc's centre.
 */
private fun applePath(): Path {
    val k = 0.2f
    fun x(v: Float) = 40f + v * k
    fun y(v: Float) = 39.5f + v * k
    val body = Path().apply {
        moveTo(x(50f), y(33f))
        cubicTo(x(56f), y(27f), x(66f), y(24f), x(74f), y(25f))
        cubicTo(x(82f), y(25f), x(88f), y(29f), x(92f), y(35f))
        // The bite.
        cubicTo(x(79f), y(43f), x(78f), y(63f), x(93f), y(71f))
        cubicTo(x(97f), y(82f), x(90f), y(99f), x(81f), y(108f))
        cubicTo(x(75f), y(116f), x(67f), y(118f), x(60f), y(114.5f))
        cubicTo(x(55f), y(112f), x(45f), y(112f), x(40f), y(114.5f))
        cubicTo(x(32f), y(118f), x(23f), y(115f), x(16f), y(104f))
        cubicTo(x(5f), y(88f), x(1f), y(68f), x(4f), y(55f))
        cubicTo(x(7f), y(36f), x(20f), y(25f), x(32f), y(25f))
        cubicTo(x(40f), y(25f), x(46f), y(29f), x(50f), y(33f))
        close()
    }
    val leaf = Path().apply {
        moveTo(x(49f), y(22f))
        cubicTo(x(48f), y(10f), x(57f), y(1f), x(70f), y(0f))
        cubicTo(x(71f), y(12f), x(62f), y(21f), x(49f), y(22f))
        close()
    }
    return Path().also { it.op(body, leaf, PathOperation.Union) }
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

/** A pair of Galaxy Buds: two graphite earbuds with a stem and a metallic ring, one tilted each way. */
private fun DrawScope.galaxyBuds() {
    fun bud(cx: Float, deg: Float) = withTransform({ rotate(deg, Offset(cx, 50f)) }) {
        val body = rounded(cx - 15f, 14f, cx + 15f, 56f, 15f)
        val stem = rounded(cx - 6f, 44f, cx + 6f, 88f, 6f)
        val shape = Path().apply { op(body, stem, PathOperation.Union) }
        shadowed(shape) { p ->
            drawPath(p, Brush.linearGradient(listOf(Color(0xFF5A5D66), Color(0xFF1E2024)), Offset(cx - 15f, 14f), Offset(cx + 15f, 88f)))
            drawPath(p, Color(0xFF6E727B), style = Stroke(1.1f))
        }
        drawCircle(Brush.linearGradient(listOf(Color(0xFFE2E5EA), Color(0xFF8E939B)), Offset(cx - 8f, 24f), Offset(cx + 8f, 40f)), 8.5f, Offset(cx, 32f), style = Stroke(2.4f))
        drawCircle(Color(0xFF34373D), 4f, Offset(cx, 32f))
        drawCircle(Color(0xFF9BA0A9), 1.5f, Offset(cx, 76f))
    }
    bud(30f, -12f)
    bud(70f, 12f)
}

/**
 * Tile Mate (2024): a navy face in a coral frame whose top-left corner opens into the key-ring loop, a round recessed
 * button with Tile's looping mark in the middle, and the "tile" wordmark in coral at the bottom right.
 */
private fun DrawScope.tileMate() {
    val coral = Color(0xFFF26B4E)
    // Frame, with the loop's opening near the top-left corner.
    val hole = rounded(16.5f, 16.5f, 34f, 34f, 5.5f)
    val frame = rounded(11f, 11f, 89f, 89f, 22f).minus(hole)
    shadowed(frame) { p ->
        drawPath(p, Brush.linearGradient(listOf(Color(0xFFFF8A6E), coral, Color(0xFFD9553A)), Offset(11f, 11f), Offset(89f, 89f)))
    }
    // Navy face, cut back around the loop so the coral shows there.
    val face = rounded(14.5f, 14.5f, 85.5f, 85.5f, 19f).minus(rounded(11f, 11f, 40f, 40f, 10f))
    drawPath(face, Brush.linearGradient(listOf(Color(0xFF34407A), Color(0xFF232D5C), Color(0xFF1A2249)), Offset(15f, 15f), Offset(85f, 85f)))
    drawArc(Color.White.copy(alpha = 0.16f), 250f, 70f, false, Offset(16f, 16f), Size(68f, 68f), style = Stroke(1.2f))
    // Recessed round button: darker, lit on its lower edge, the looping mark engraved in it.
    val c = Offset(52f, 50f)
    drawCircle(Brush.radialGradient(listOf(Color(0xFF1B2350), Color(0xFF151C40)), c, 15f), 14.5f, c)
    drawArc(Color.White.copy(alpha = 0.22f), 20f, 140f, false, Offset(c.x - 14.5f, c.y - 14.5f), Size(29f, 29f), style = Stroke(1.1f))
    drawArc(Color.Black.copy(alpha = 0.35f), 200f, 140f, false, Offset(c.x - 14.5f, c.y - 14.5f), Size(29f, 29f), style = Stroke(1.1f))
    val mark = Color(0xFF3B4888)
    // Tile's mark: an open loop whose inner teardrop comes down to the bottom of the outer one.
    drawArc(mark, 115f, 310f, false, Offset(c.x - 7.5f, c.y - 7.5f), Size(15f, 15f), style = Stroke(1.5f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    drawOval(mark, Offset(c.x - 3f, c.y - 4.5f), Size(6f, 12f), style = Stroke(1.5f))
    // Wordmark.
    drawContext.canvas.nativeCanvas.drawText(
        "tile", 76f, 80f,
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(0xF2, 0x6B, 0x4E)
            textSize = 13f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            textAlign = android.graphics.Paint.Align.RIGHT
        },
    )
}

/** Find Hub tag (Moto Tag, Chipolo, Pebblebee…): a dark disc on a key-ring tab, its button ringed in Google's colours. */
/** Chipolo ONE: a round tag with a keyring hole near the edge and the logo dot. */
private fun DrawScope.chipolo() {
    val shape = circle(50f, 50f, 40f).minus(circle(50f, 20f, 6f))
    shadowed(shape) { p ->
        drawPath(p, Brush.linearGradient(listOf(Color(0xFFFFD54F), Color(0xFFF9A825)), Offset(10f, 10f), Offset(90f, 90f)))
        drawPath(p, Color(0xFFE09A00), style = Stroke(1.4f))
    }
    drawCircle(Color(0xFF3E2723).copy(alpha = 0.85f), 7f, Offset(50f, 58f))
}

/** Pebblebee Clip: a rounded square with a ring for the keys and a light in the middle. */
private fun DrawScope.pebblebee() {
    val body = rounded(16f, 16f, 84f, 84f, 20f).minus(circle(30f, 30f, 6f))
    shadowed(body) { p ->
        drawPath(p, Brush.linearGradient(listOf(Color(0xFF4A4F57), Color(0xFF1F2226)), Offset(16f, 16f), Offset(84f, 84f)))
        drawPath(p, Color(0xFF666B73), style = Stroke(1.4f))
    }
    drawCircle(Brush.radialGradient(listOf(Color(0xFFB3E5FC), Color(0xFF29B6F6)), Offset(54f, 54f), 12f), 10f, Offset(54f, 54f))
}

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
