package com.allnetworktools.ui.pages.bt

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.Page
import com.allnetworktools.data.BondedDevice
import com.allnetworktools.model.Tool
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.LeadingIcon
import com.allnetworktools.ui.components.OutlineChip
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.SectionTitle
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.fadeUp
import com.allnetworktools.ui.components.rise
import com.allnetworktools.ui.pages.PageColumn
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BtDashboard(vm: MainViewModel) {
    val bt by vm.bluetooth.collectAsStateWithLifecycle()
    val ble by vm.ble.collectAsStateWithLifecycle()
    val actions = LocalActions.current
    val roles = AntTheme.net.bt
    val openPaired = { d: BondedDevice -> vm.navigate { it.copy(page = Page.ToolPage(Tool.Paired, d.address)) } }
    PageColumn {
        Column(Modifier.fadeUp().fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(roles.container).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LeadingIcon(Sym.Bluetooth, roles.accent, roles.onAccent, 56.dp, RoundedCornerShape(20.dp), 28.dp)
                Column(Modifier.weight(1f)) {
                    Text(bt.adapter.name ?: S(R.string.this_device), style = gs(24, 30, 500), color = roles.onContainer, maxLines = 1)
                    Text(
                        bt.adapter.features.joinToString(" · ").ifEmpty { "Bluetooth" },
                        Modifier.graphicsLayer { alpha = 0.85f }, style = rf(13, 18), color = roles.onContainer,
                    )
                }
                Switch(
                    checked = true, onCheckedChange = { actions.openBluetoothSettings() },
                    thumbContent = { Symbol(Sym.Check, size = 16.dp, tint = roles.accent) },
                    colors = SwitchDefaults.colors(checkedTrackColor = roles.accent, checkedThumbColor = roles.onAccent),
                )
            }
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    bt.connected.size to P(R.plurals.p_connected, bt.connected.size),
                    bt.bonded.size to P(R.plurals.p_paired, bt.bonded.size),
                    ble.size to S(R.string.nearby_2),
                ).forEach { (v, k) ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(cs.surface).padding(12.dp)) {
                        Text(v.toString(), style = gs(30, 34, 500, tnum = true))
                        Text(k, style = rf(12, 16), color = cs.onSurfaceVariant)
                    }
                }
            }
        }
        SectionTitle(S(R.string.connected_2), roles.accent)
        if (bt.connected.isEmpty()) {
            SectionCard { Text(S(R.string.no_device_connected_at_the_moment), style = rf(14, 20), color = cs.onSurfaceVariant) }
        }
        bt.connected.forEachIndexed { i, d ->
            val rssi = ble.firstOrNull { it.address == d.address }?.rssi
            SectionCard(Modifier.rise(i), onClick = { openPaired(d) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LeadingIcon(d.icon, roles.container, roles.onContainer, 48.dp, CircleShape)
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = rf(16, 22, 600), maxLines = 1)
                        Text(listOf(d.kind).plus(d.profiles.take(2)).joinToString(" · "), style = rf(12, 16), color = cs.onSurfaceVariant)
                    }
                    if (rssi != null) Text("${fmt(rssi)} dBm", style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant)
                }
                if (d.profiles.isNotEmpty()) {
                    FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        d.profiles.forEach { OutlineChip(it) }
                    }
                }
            }
        }
        SectionTitle(S(R.string.paired), roles.accent)
        val others = bt.bonded.filter { !it.connected }
        SectionCard(padding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)) {
            if (others.isEmpty()) {
                Text(S(R.string.no_other_paired_device), Modifier.padding(16.dp), style = rf(14, 20), color = cs.onSurfaceVariant)
            }
            others.forEach { d ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { openPaired(d) }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Symbol(d.icon, size = 24.dp, tint = cs.onSurfaceVariant)
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = rf(15, 20, 500), maxLines = 1)
                        Text(S(R.string.disconnected, d.kind), style = rf(12, 16), color = cs.onSurfaceVariant)
                    }
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { Symbol(Sym.Settings, size = 22.dp, tint = cs.onSurfaceVariant) }
                }
            }
        }
    }
}
