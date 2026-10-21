package com.allnetworktools.ui.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.data.PermGroup
import com.allnetworktools.model.Network
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.ShapeBadge
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.cookieShape
import com.allnetworktools.ui.components.groupShape
import com.allnetworktools.ui.components.pop
import com.allnetworktools.ui.components.rise
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Motion
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.util.plural
import kotlinx.coroutines.launch
import com.allnetworktools.R
import com.allnetworktools.util.S
import com.allnetworktools.util.P

private data class ObPerm(val group: PermGroup, val icon: String, val title: String, val subtitle: String, val network: Network, val doneIcon: String)

private val ObPerms = listOf(
    ObPerm(PermGroup.Location, Sym.LocationOn, S(R.string.precise_location), S(R.string.wi_fi_ssid_scans_and_gnss), Network.Wifi, Sym.Wifi),
    ObPerm(PermGroup.Nearby, Sym.BluetoothSearching, S(R.string.nearby_devices), S(R.string.bluetooth_scan_and_connection), Network.Bluetooth, Sym.Bluetooth),
    ObPerm(PermGroup.Phone, Sym.SimCard, S(R.string.phone), S(R.string.carrier_cells_dual_sim), Network.Cellular, Sym.CellBars3),
)

@Composable
fun Onboarding(vm: MainViewModel, onDone: () -> Unit) {
    val pager = rememberPagerState { 3 }
    val scope = rememberCoroutineScope()
    val step = pager.currentPage
    Column(
        Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 24.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.End) {
            if (step < 2) {
                Box(Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onDone).padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(S(R.string.skip), style = rf(15, 20, 600), color = cs.primary)
                }
            }
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), beyondViewportPageCount = 0) { page ->
            when (page) {
                0 -> Welcome()
                1 -> Permissions(vm)
                else -> Done(vm)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(3) { i ->
                    val w by animateDpAsState(if (i == step) 28.dp else 8.dp, spring(0.7f, 450f), label = "dot")
                    val c by animateColorAsState(if (i == step) cs.primary else cs.outlineVariant, tween(250, easing = Motion.Emphasized), label = "dotColor")
                    Box(Modifier.width(w).height(8.dp).clip(RoundedCornerShape(4.dp)).background(c))
                }
            }
            Row(
                Modifier.height(56.dp).clip(RoundedCornerShape(28.dp)).background(cs.primary)
                    .clickable { if (step < 2) scope.launch { pager.animateScrollToPage(step + 1) } else onDone() }
                    .padding(horizontal = 28.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(listOf(S(R.string.start), S(R.string.continue_text), S(R.string.open_the_app))[step], style = rf(16, 20, 600), color = cs.onPrimary)
                Symbol(Sym.ArrowForward, size = 22.dp, tint = cs.onPrimary)
            }
        }
    }
}

@Composable
private fun Welcome() {
    val net = AntTheme.net
    val shapes = listOf(MaterialShapes.Cookie9Sided, MaterialShapes.Clover4Leaf, MaterialShapes.Cookie12Sided, MaterialShapes.Cookie7Sided)
    val nets = Network.entries
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(top = 32.dp).width(288.dp).align(Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            listOf(0 to 1, 2 to 3).forEach { (a, b) ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    listOf(a, b).forEach { i ->
                        val roles = net[nets[i]]
                        ShapeBadge(
                            nets[i].icon, shapes[i].toShape(), 137.dp, roles.accent, roles.onAccent, 52.dp,
                            Modifier.pop(i * 90), spinMs = 24_000 + i * 6_000, reverse = i % 2 == 1,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text(S(R.string.all_your_networks_measured_live), style = gs(36, 44, 500, -0.5f))
        Text(
            S(R.string.wi_fi_bluetooth_mobile_network_and),
            Modifier.padding(top = 12.dp), style = rf(16, 24), color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun Permissions(vm: MainViewModel) {
    val perms by vm.permissions.collectAsStateWithLifecycle()
    val actions = LocalActions.current
    val haptics = AntTheme.haptics
    Column(Modifier.fillMaxSize()) {
        Text(S(R.string.permissions), Modifier.padding(top = 8.dp), style = gs(32, 40, 500))
        Text(
            S(R.string.each_network_needs_a_specific_access),
            Modifier.padding(top = 8.dp), style = rf(15, 22), color = cs.onSurfaceVariant,
        )
        Column(Modifier.padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ObPerms.forEachIndexed { i, p ->
                val roles = AntTheme.net[p.network]
                val granted = p.group in perms
                Row(
                    Modifier.rise(i, 60).fillMaxWidth().clip(groupShape(i, ObPerms.size)).background(cs.surfaceContainerLow)
                        .padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(roles.container), contentAlignment = Alignment.Center) {
                        Symbol(p.icon, size = 24.dp, filled = true, tint = roles.onContainer)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(p.title, style = rf(15, 20, 600))
                        Text(p.subtitle, style = rf(12, 17), color = cs.onSurfaceVariant)
                    }
                    val bg by animateColorAsState(if (granted) Color.Transparent else roles.accent, tween(250, easing = Motion.Emphasized), label = "permBg")
                    Row(
                        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(bg)
                            .then(if (granted) Modifier.border(1.dp, cs.outlineVariant, RoundedCornerShape(18.dp)) else Modifier)
                            .clickable(enabled = !granted) { haptics.confirm(); actions.request(p.group) }
                            .padding(start = if (granted) 8.dp else 14.dp, end = if (granted) 12.dp else 14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (granted) Box(Modifier.pop(key = true)) { Symbol(Sym.Check, size = 18.dp, tint = AntTheme.net.good) }
                        Text(if (granted) S(R.string.granted) else S(R.string.allow), style = rf(13, 18, 700), color = if (granted) AntTheme.net.good else roles.onAccent)
                    }
                }
            }
        }
    }
}

@Composable
private fun Done(vm: MainViewModel) {
    val perms by vm.permissions.collectAsStateWithLifecycle()
    val granted = ObPerms.count { it.group in perms }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        ShapeBadge(Sym.Check, cookieShape(), 160.dp, cs.primary, cs.onPrimary, 72.dp, Modifier.pop(), spinMs = 30_000)
        Text(S(R.string.all_set), Modifier.padding(top = 28.dp), style = gs(32, 40, 500))
        Text(
            if (granted == 4) S(R.string.all_4_networks_are_available_tap)
            else S(R.string.of_4_the_remaining_networks_can, granted, P(R.plurals.p_permissions, granted), P(R.plurals.p_granted, granted)),
            Modifier.padding(top = 8.dp).widthIn(max = 320.dp), style = rf(15, 22), color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ObPerms.forEach { p ->
                val ok = p.group in perms
                val roles = AntTheme.net[p.network]
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(if (ok) roles.container else cs.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) { Symbol(if (ok) p.doneIcon else Sym.Block, size = 22.dp, filled = true, tint = if (ok) roles.onContainer else cs.onSurfaceVariant) }
            }
        }
    }
}
