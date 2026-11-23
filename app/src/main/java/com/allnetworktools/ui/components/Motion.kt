package com.allnetworktools.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.allnetworktools.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Continuous linear rotation, in degrees. */
@Composable
fun rememberSpin(periodMs: Int, reverse: Boolean = false): State<Float> {
    val t = rememberInfiniteTransition(label = "spin")
    return t.animateFloat(
        0f, if (reverse) -360f else 360f,
        infiniteRepeatable(tween(periodMs, easing = LinearEasing)),
        label = "spin",
    )
}

fun Modifier.spinning(angle: State<Float>) = graphicsLayer { rotationZ = angle.value }

/** Expanding ring: scale 0.6 → 2.2, alpha 0.7 → 0. */
@Composable
fun PulseRing(color: Color, size: Dp, periodMs: Int = 1600, filled: Boolean = false, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "pulse")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(periodMs, easing = Motion.Emphasized)), label = "pulse")
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                val s = 0.6f + 1.6f * p
                scaleX = s; scaleY = s; alpha = 0.7f * (1f - p)
            }
            .clip(CircleShape)
            .then(if (filled) Modifier.background(color) else Modifier.border(2.dp, color, CircleShape)),
    )
}

@Composable
fun BlinkDot(color: Color, size: Dp, periodMs: Int = 1600) {
    val t = rememberInfiniteTransition(label = "blink")
    val a by t.animateFloat(1f, 0.25f, infiniteRepeatable(tween(periodMs / 2, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "blink")
    Box(Modifier.size(size).graphicsLayer { alpha = a }.clip(CircleShape).background(color))
}

/**
 * Entrance used for lists: translateY 18 dp + alpha with spring(0.8, 380),
 * delayed by [index] × [stepMs] on first composition.
 */
@Composable
fun Modifier.rise(index: Int = 0, stepMs: Int = 45, distance: Dp = 18.dp): Modifier {
    val y = remember { Animatable(1f) }
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // Only the first rows are staggered: row 40 of a scan used to stay blank for two seconds before rising.
        delay((index.coerceIn(0, MAX_STAGGER) * stepMs).toLong())
        launch { y.animateTo(0f, Motion.standard()) }
        a.animateTo(1f, tween(300, easing = Motion.Emphasized))
    }
    return graphicsLayer {
        translationY = y.value * distance.toPx()
        alpha = a.value
    }
}

/** Rows past this one rise together with it. */
private const val MAX_STAGGER = 8

/** Pop entrance: scale 0.4 → 1 and rotation −40° → 0 with a bouncy spring. */
@Composable
fun Modifier.pop(delayMs: Int = 0, key: Any? = Unit): Modifier {
    val p = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        delay(delayMs.toLong())
        p.animateTo(1f, spring(0.55f, 420f))
    }
    return graphicsLayer {
        val v = p.value
        val s = 0.4f + 0.6f * v
        scaleX = s; scaleY = s
        rotationZ = -40f * (1f - v)
        alpha = v.coerceIn(0f, 1f)
    }
}

/** Page content entrance: translateY 12 dp → 0 with alpha, 400 ms emphasized. */
@Composable
fun Modifier.fadeUp(delayMs: Int = 0): Modifier {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(400, delayMs, Motion.Emphasized)) }
    return graphicsLayer {
        translationY = (1f - p.value) * 12.dp.toPx()
        alpha = p.value
    }
}
