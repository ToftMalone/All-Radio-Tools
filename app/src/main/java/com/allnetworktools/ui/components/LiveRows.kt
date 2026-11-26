package com.allnetworktools.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Motion
import kotlinx.coroutines.delay

/**
 * What the user has already met in each live list, for the session: a row is new until then. A row the user can tap
 * stays new until tapped; any other row until it has been on screen for [SEEN_MS].
 */
object FreshItems {
    private val seen = HashMap<String, LinkedHashSet<Any>>()
    const val SEEN_MS = 2_500L
    private const val KEEP = 4_000

    @Synchronized fun isSeen(list: String, id: Any) = seen[list]?.contains(id) == true

    @Synchronized fun see(list: String, id: Any) {
        val s = seen.getOrPut(list) { LinkedHashSet() }
        s += id
        // The oldest go first: a long session does not keep every message ever heard.
        if (s.size > KEEP) s.iterator().let { it.next(); it.remove() }
    }

    /** For screenshots: everything new again. */
    @Synchronized internal fun clearForTest() = seen.clear()
}

/**
 * The rows of a live list (what a radio tool hears): each keeps its place by [id], rises in when it first shows, and
 * is marked new ([FreshItems]) until the user has seen it or, when [tapToSee], tapped it.
 */
@Composable
fun <T> LiveRows(list: String, items: List<T>, id: (T) -> Any, tapToSee: Boolean = false, row: @Composable (Int, T) -> Unit) {
    items.forEachIndexed { i, item ->
        val k = id(item)
        key(k) {
            Column(Modifier.rise(i).fresh(list, k, tapToSee)) { row(i, item) }
        }
    }
}

/** The "new" mark: a tint and an accent edge, fading once the row is seen or tapped. */
@Composable
fun Modifier.fresh(list: String, id: Any, tapToSee: Boolean): Modifier {
    val isNew = remember(id) { !FreshItems.isSeen(list, id) }
    if (!isNew) return this
    var on by remember(id) { mutableStateOf(true) }
    var visible by remember(id) { mutableStateOf(false) }
    val a by animateFloatAsState(if (on) 1f else 0f, tween(700, easing = Motion.Emphasized), label = "fresh")
    if (!tapToSee) LaunchedEffect(visible, on) {
        if (visible && on) {
            delay(FreshItems.SEEN_MS)
            on = false
            FreshItems.see(list, id)
        }
    }
    val tint = AntTheme.accent.container
    val edge = AntTheme.accent.accent
    return this
        .then(
            if (tapToSee) Modifier.pointerInput(id) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    // Watched before the row's own click handles it, and only a tap counts (not a scroll across it).
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var moved = false
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if ((c.position - down.position).getDistance() > slop) moved = true
                        if (!c.pressed) break
                    }
                    if (!moved && on) { on = false; FreshItems.see(list, id) }
                }
            } else Modifier.onGloballyPositioned { c ->
                // On screen: at least half of the row inside the window and its scrolling parents.
                visible = c.boundsInWindow().height >= c.size.height / 2f && c.size.height > 0
            },
        )
        .drawBehind {
            if (a <= 0.01f) return@drawBehind
            val r = 12.dp.toPx()
            drawRoundRect(tint.copy(alpha = 0.55f * a), Offset(-6.dp.toPx(), 0f), Size(size.width + 12.dp.toPx(), size.height), CornerRadius(r))
            drawRoundRect(edge.copy(alpha = a), Offset(-6.dp.toPx(), 6.dp.toPx()), Size(3.dp.toPx(), size.height - 12.dp.toPx()), CornerRadius(2.dp.toPx()))
        }
}
