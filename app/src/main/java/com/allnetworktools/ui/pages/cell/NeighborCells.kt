package com.allnetworktools.ui.pages.cell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.CellUi
import com.allnetworktools.MainViewModel
import com.allnetworktools.Page
import com.allnetworktools.data.CellMeasure
import com.allnetworktools.data.PermGroup
import com.allnetworktools.data.RadioTech
import com.allnetworktools.model.Tool
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.EmptyStateCard
import com.allnetworktools.ui.components.LeadingIcon
import com.allnetworktools.ui.components.Legend
import com.allnetworktools.ui.components.LevelBar
import com.allnetworktools.ui.components.PanelAction
import com.allnetworktools.ui.components.fadeUp
import com.allnetworktools.ui.components.groupShape
import com.allnetworktools.ui.components.rise
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.pages.TopBarAction
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.util.fmt
import com.allnetworktools.util.plural
import com.allnetworktools.R
import com.allnetworktools.util.S
import com.allnetworktools.util.P

fun CellMeasure.title(): String = listOfNotNull(band ?: tech.short, pci?.let { "PCI $it" }).joinToString(" · ")
fun CellMeasure.arfcnText(): String? = arfcn?.let { "${arfcnLabel ?: "ARFCN"} ${fmt(it)}" }

@Composable
fun NeighborCells(vm: MainViewModel) {
    var paused by rememberSaveable { mutableStateOf(false) }
    var frozen by remember { mutableStateOf(CellUi(null, emptyList())) }
    val ui = if (paused) frozen else vm.cell.collectAsStateWithLifecycle().value.also { frozen = it }
    val perms by vm.permissions.collectAsStateWithLifecycle()
    val actions = LocalActions.current
    val haptics = AntTheme.haptics
    TopBarAction(if (paused) Sym.PlayArrow else Sym.Pause) { haptics.segment(); paused = !paused }
    val roles = AntTheme.net.cell
    val serving = ui.state?.serving
    val neighbors = ui.state?.neighbors.orEmpty()
    val open = { key: String -> vm.navigate { it.copy(page = Page.ToolPage(Tool.CellDetail, key)) } }
    PageColumn {
        Row(
            Modifier.fadeUp().fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(roles.container)
                .clickable { open("serving") }.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            LeadingIcon(Sym.CellTower, roles.accent, roles.onAccent, 52.dp, RoundedCornerShape(18.dp), 28.dp)
            Column(Modifier.weight(1f)) {
                Text(S(R.string.serving_cell_2), Modifier.graphicsLayer { alpha = 0.85f }, style = rf(12, 16, 600, 0.4f), color = roles.onContainer)
                Text(serving?.let { "${it.tech.short} ${it.title()}" } ?: S(R.string.none_2), Modifier.padding(top = 2.dp), style = rf(16, 22, 600), color = roles.onContainer)
                val sub = listOfNotNull(serving?.arfcnText(), serving?.nodeId?.let { "${if (serving.tech == RadioTech.NR) "gNB" else "eNB"} ${fmt(it)}" }).joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, Modifier.graphicsLayer { alpha = 0.85f }, style = rf(12, 16, tnum = true), color = roles.onContainer)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(serving?.level?.let { fmt(it) } ?: "—", style = gs(32, 34, 500, tnum = true), color = roles.onContainer)
                Text(if (serving?.rsrp != null) "dBm RSRP" else "dBm", style = rf(11, 14), color = roles.onContainer)
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${neighbors.size} ${P(R.plurals.p_neighbour_cells, neighbors.size)}", Modifier.weight(1f), style = rf(13, 18), color = cs.onSurfaceVariant)
            Legend(listOf(S(R.string.good) to AntTheme.net.good, S(R.string.fair) to AntTheme.net.fair, S(R.string.weak) to AntTheme.net.poor))
        }
        if (!perms.location) {
            EmptyStateCard(
                Sym.LocationOff, S(R.string.location_needed),
                S(R.string.android_only_gives_the_list_of),
                PanelAction(S(R.string.allow), Sym.MyLocation) { actions.request(PermGroup.Location) },
            )
        } else if (neighbors.isEmpty()) {
            EmptyStateCard(Sym.CellTower, S(R.string.no_neighbour_cell), S(R.string.the_modem_reports_no_neighbour_cell))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            neighbors.forEachIndexed { i, n -> NeighborItem(n, i, neighbors.size) { open(n.key) } }
        }
    }
}

@Composable
private fun NeighborItem(n: CellMeasure, index: Int, count: Int, onClick: () -> Unit) {
    val roles = AntTheme.net.cell
    val v = n.level
    val color = qualityColor(v, RsrpSpec)
    Column(
        Modifier.rise(index, 50).fillMaxWidth().clip(groupShape(index, count)).background(cs.surfaceContainerLow)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val nr = n.tech == RadioTech.NR
            Box(
                Modifier.height(28.dp).defaultMinSize(minWidth = 40.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (nr) roles.accent else cs.surfaceContainerHighest).padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(n.tech.short, style = rf(12, 16, 700), color = if (nr) roles.onAccent else cs.onSurface) }
            Column(Modifier.weight(1f)) {
                Text(n.title(), style = rf(15, 20, 600))
                n.arfcnText()?.let { Text(it, style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant) }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(v?.let { fmt(it) } ?: "—", style = gs(20, 24, 500, tnum = true))
                Text(" dBm", Modifier.padding(bottom = 2.dp), style = rf(11, 14), color = cs.onSurfaceVariant)
            }
        }
        LevelBar(((v ?: -125) + 125) / 50f, color, Modifier.padding(top = 12.dp))
    }
}
