package com.allnetworktools.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import com.allnetworktools.ui.theme.Motion

/**
 * A big number that rolls: each character that changes slides out and the new one in, upwards when the value grows,
 * downwards when it falls (like an odometer). Characters are matched from the right, so units stay put and a new
 * leading digit simply appears.
 */
@Composable
fun RollingText(text: String, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current, color: Color = Color.Unspecified) {
    val last = remember { arrayOf(text) }
    val up = remember(text) { (number(text) ?: 0.0) >= (number(last[0]) ?: 0.0) }
    last[0] = text
    Row(modifier.clearAndSetSemantics { contentDescription = text }) {
        val n = text.length
        for (i in 0 until n) {
            key(n - i) {
                AnimatedContent(
                    text[i],
                    transitionSpec = {
                        val d = if (up) 1 else -1
                        (slideInVertically(tween(320, easing = Motion.Emphasized)) { h -> d * h } + fadeIn(tween(220))) togetherWith
                            (slideOutVertically(tween(320, easing = Motion.Emphasized)) { h -> -d * h } + fadeOut(tween(160))) using SizeTransform(clip = true)
                    },
                    label = "roll",
                ) { ch -> Text(ch.toString(), style = style, color = color, maxLines = 1) }
            }
        }
    }
}

/** The number a text shows ("-61", "15,4", "2 351"), to know which way it moved. */
private fun number(s: String): Double? = s.filter { it.isDigit() || it == ',' || it == '.' || it == '-' || it == '−' }
    .replace(',', '.').replace('−', '-').toDoubleOrNull()
