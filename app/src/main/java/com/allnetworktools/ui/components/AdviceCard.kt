package com.allnetworktools.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.allnetworktools.R
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.util.S

/** What a dynamic advice shows: shown only when something is wrong, and it can be put away for the session. */
data class Advice(val key: String, val icon: String, val title: String, val body: String, val tint: Color)

/** Advice put away this session (by key); they come back at the next launch. */
object AdviceDismissed {
    private val keys = mutableStateMapOf<String, Boolean>()
    fun has(key: String) = keys[key] == true
    fun add(key: String) { keys[key] = true }
    internal fun clearForTest() = keys.clear()
}

/**
 * A dynamic advice: slides in when [advice] appears, out when it goes or is put away. Nothing at all is laid out while
 * there is none, so a page's spacing does not change.
 */
@Composable
fun AdviceCard(advice: Advice?, modifier: Modifier = Modifier) {
    val shown = advice?.takeIf { !AdviceDismissed.has(it.key) }
    val last = remember { arrayOfNulls<Advice>(1) }
    if (shown != null) last[0] = shown
    val state = remember { MutableTransitionState(false) }
    state.targetState = shown != null
    if (!state.currentState && !state.targetState) return
    val a = last[0] ?: return
    AnimatedVisibility(state, modifier, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        SectionCard(color = a.tint.copy(alpha = 0.12f), padding = PaddingValues(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(a.icon, Modifier.padding(top = 2.dp), size = 22.dp, tint = a.tint)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(a.title, style = rf(14, 20, 600), color = cs.onSurface)
                    Text(a.body, style = rf(13, 18), color = cs.onSurfaceVariant)
                }
                IconCircleButton(Sym.Close, { AdviceDismissed.add(a.key) }, size = 36.dp, label = S(R.string.advice_dismiss))
            }
        }
    }
}

/**
 * A section that comes and goes with [visible]: it unfolds in and folds away instead of popping, and lays nothing out
 * while hidden. [content] is still called during the fold-away, so it must cope with the data that made it disappear.
 */
@Composable
fun AppearSection(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(visible) }
    state.targetState = visible
    if (!state.currentState && !state.targetState) return
    AnimatedVisibility(state, modifier, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        // Several cards in one section stay one under the other, spaced as on a page.
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}
