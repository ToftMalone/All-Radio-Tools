package com.allnetworktools.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.allnetworktools.Page
import com.allnetworktools.model.Network
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.pop
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Motion
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.rf
import kotlinx.coroutines.launch
import com.allnetworktools.R
import com.allnetworktools.util.S

enum class DockTab { Dashboard, Featured, Tools }

fun activeTab(page: Page): DockTab? = when (page) {
    Page.Dashboard -> DockTab.Dashboard
    Page.Tools -> DockTab.Tools
    is Page.ToolPage -> if (page.tool.isDockShortcut) DockTab.Featured else DockTab.Tools
    else -> null
}

/**
 * Floating toolbar: a leading home button (back to the network grid, or deselect the card when already on
 * it), then the network's three tabs. Colors come from LocalAccent.
 */
@Composable
fun FloatingDock(
    network: Network,
    page: Page,
    onTab: (DockTab) -> Unit,
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val acc = AntTheme.accent
    val squash = remember { Animatable(0f) }
    LaunchedEffect(network) {
        squash.snapTo(1f)
        launch { squash.animateTo(0f, spring(0.5f, 500f)) }
    }
    Row(
        modifier
            .graphicsLayer {
                scaleX = 1f + 0.06f * squash.value
                scaleY = 1f - 0.1f * squash.value
            }
            .shadow(14.dp, RoundedCornerShape(32.dp), ambientColor = AntTheme.net.shadow, spotColor = AntTheme.net.shadow)
            .clip(RoundedCornerShape(32.dp))
            .background(acc.container)
            .height(64.dp)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onHome),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.pop(key = page == Page.Home)) {
                Symbol(if (page == Page.Home) Sym.Close else Sym.GridView, size = 24.dp, tint = acc.onContainer)
            }
        }
        val active = activeTab(page)
        DockTab.entries.filter { (it != DockTab.Featured || network.dockShortcut != null) && (it != DockTab.Dashboard || network.hasDashboard) }.forEach { tab ->
            val (icon, label) = when (tab) {
                DockTab.Dashboard -> Sym.SpaceDashboard to "Dashboard"
                DockTab.Featured -> network.dockShortcut!!.icon to network.featuredShort
                DockTab.Tools -> Sym.Handyman to S(R.string.tools)
            }
            TabButton(icon, label, tab == active) { onTab(tab) }
        }
    }
}

@Composable
private fun TabButton(icon: String, label: String, active: Boolean, onClick: () -> Unit) {
    val acc = AntTheme.accent
    val width by animateDpAsState(if (active) 128.dp else 48.dp, Motion.standard(), label = "tabW")
    val bg by animateColorAsState(if (active) acc.accent else Color.Transparent, tween(250, easing = Motion.Emphasized), label = "tabBg")
    val fg by animateColorAsState(if (active) acc.onAccent else acc.onContainer, tween(250, easing = Motion.Emphasized), label = "tabFg")
    val labelAlpha by animateFloatAsState(if (active) 1f else 0f, tween(200, easing = Motion.Emphasized), label = "tabLabel")
    Row(
        Modifier
            .width(width)
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(bg)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(icon, size = 24.dp, filled = active, tint = fg)
        if (labelAlpha > 0.01f) {
            Text(
                label, Modifier.padding(start = 6.dp).graphicsLayer { alpha = labelAlpha },
                style = rf(14, 20, 600), color = fg, maxLines = 1, softWrap = false,
            )
        }
    }
}
