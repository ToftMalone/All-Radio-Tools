package com.allnetworktools.ui.pages.bt

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.allnetworktools.data.BondedDevice
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.InfoList
import com.allnetworktools.ui.components.InfoRow
import com.allnetworktools.ui.components.PillButton
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.ShapeBadge
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.cookieShape
import com.allnetworktools.ui.components.rise
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.ToolEmpty
import com.allnetworktools.util.fmt
import com.allnetworktools.R
import com.allnetworktools.util.S

private data class ProfileInfo(val icon: String, val title: String, val detail: String)

private val Profiles = mapOf(
    "A2DP" to ProfileInfo(Sym.MusicNote, S(R.string.media_audio), S(R.string.a2dp_high_quality_music_and_video)),
    "HFP" to ProfileInfo(Sym.Call, S(R.string.calls), S(R.string.hfp_microphone_and_call_audio)),
    "LE Audio" to ProfileInfo(Sym.GraphicEq, "LE Audio", S(R.string.lc3_low_power)),
    "ASHA" to ProfileInfo(Sym.Headphones, S(R.string.hearing_aid), S(R.string.asha_streaming_to_hearing_aids)),
    "HID" to ProfileInfo(Sym.Keyboard, S(R.string.input_device), S(R.string.hid_keyboard_mouse_gamepad)),
    "GATT" to ProfileInfo(Sym.AccountTree, S(R.string.ble_data), S(R.string.gatt_services_and_sensors)),
)

@Composable
fun PairedTool(device: BondedDevice?, rssi: Int?, onForget: () -> Boolean, onExplore: () -> Unit) {
    val actions = LocalActions.current
    val haptics = AntTheme.haptics
    val acc = AntTheme.accent
    var confirm by remember { mutableStateOf(false) }
    PageColumn {
        if (device == null) {
            ToolEmpty(
                Sym.BluetoothDisabled, S(R.string.device_not_found),
                S(R.string.this_device_is_no_longer_paired),
                S(R.string.bluetooth_settings),
            ) { actions.openBluetoothSettings() }
            return@PageColumn
        }
        HeroCard(padding = 24.dp) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                ShapeBadge(
                    device.icon, cookieShape(), 104.dp,
                    if (device.connected) acc.accent else cs.surfaceContainerHighest,
                    if (device.connected) acc.onAccent else cs.onSurfaceVariant, 48.dp,
                    spinMs = if (device.connected) 30_000 else 0,
                )
                Text(device.name, Modifier.padding(top = 16.dp), style = gs(26, 32, 500), textAlign = TextAlign.Center, maxLines = 2)
                Text(
                    listOfNotNull(device.kind, rssi?.let { "${fmt(it)} dBm" }).joinToString(" · "),
                    Modifier.padding(top = 2.dp), style = rf(13, 18),
                )
                Box(Modifier.padding(top = 12.dp)) {
                    if (device.connected) HeroChip(S(R.string.connected), acc.accent) else HeroChip(S(R.string.disconnected_2), cs.outline)
                }
                PillButton(
                    if (device.connected) S(R.string.manage_the_connection) else S(R.string.connect_2),
                    { haptics.confirm(); actions.openBluetoothSettings() },
                    Modifier.fillMaxWidth().padding(top = 18.dp),
                    icon = if (device.connected) Sym.Settings else Sym.Link, height = 48.dp,
                )
            }
        }
        if (device.connected) {
            SectionCard(Modifier.rise(0)) {
                Text(S(R.string.battery), style = rf(14, 20, 600), color = acc.accent)
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    BatteryRing(device.battery)
                    Text(
                        if (device.battery != null) S(R.string.level_sent_by_the_device_through)
                        else S(R.string.this_device_does_not_report_its),
                        Modifier.weight(1f), style = rf(13, 18), color = cs.onSurfaceVariant,
                    )
                }
            }
        }
        SectionCard(Modifier.rise(1), padding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
            Text(S(R.string.active_profiles), style = rf(14, 20, 600), color = acc.accent)
            if (device.profiles.isEmpty()) {
                Text(
                    if (device.connected) S(R.string.link_up_without_an_audio_or) else S(R.string.no_profile_the_device_is_disconnected),
                    Modifier.padding(vertical = 12.dp), style = rf(14, 20), color = cs.onSurfaceVariant,
                )
            }
            device.profiles.forEachIndexed { i, p ->
                val info = Profiles[p] ?: ProfileInfo(Sym.Bluetooth, p, p)
                if (i > 0) Hairline()
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Symbol(info.icon, size = 24.dp, tint = cs.onSurfaceVariant)
                    Column(Modifier.weight(1f)) {
                        Text(info.title, style = rf(15, 20, 500))
                        Text(info.detail, style = rf(12, 16), color = cs.onSurfaceVariant)
                    }
                    Symbol(Sym.CheckCircle, size = 22.dp, filled = true, tint = acc.accent)
                }
            }
            Hairline(Modifier.padding(top = 4.dp))
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(S(R.string.profiles_are_managed_in_android_s), Modifier.weight(1f), style = rf(12, 16), color = cs.onSurfaceVariant)
                com.allnetworktools.ui.components.TextAction(S(R.string.open_2), { actions.openBluetoothSettings() })
            }
        }
        InfoList(S(R.string.information_2)) {
            InfoRow(S(R.string.address), device.address) { actions.copy(S(R.string.address), device.address) }
            InfoRow("Type", device.transport)
            InfoRow(S(R.string.category), device.kind)
            InfoRow("Signal", rssi?.let { "${fmt(it)} dBm" } ?: S(R.string.not_advertised_2))
        }
        if (device.transport != "Classique (BR/EDR)") {
            PillButton(S(R.string.explore_gatt_services), onExplore, Modifier.fillMaxWidth(), icon = Sym.AccountTree, height = 48.dp, outlined = true, bg = acc.accent)
        }
        PillButton(
            S(R.string.forget_this_device), { haptics.longPress(); confirm = true }, Modifier.fillMaxWidth(),
            icon = Sym.Delete, height = 48.dp, outlined = true, bg = cs.error,
        )
    }
    if (confirm && device != null) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            icon = { Symbol(Sym.Delete, size = 24.dp, tint = cs.error) },
            title = { Text(S(R.string.forget, device.name)) },
            text = { Text(S(R.string.you_will_have_to_pair_it), style = rf(14, 20)) },
            confirmButton = {
                TextButton({
                    confirm = false
                    if (onForget()) actions.toast(S(R.string.forgotten, device.name))
                    else {
                        actions.toast(S(R.string.android_keeps_this_action_for_its))
                        actions.openBluetoothSettings()
                    }
                }) { Text(S(R.string.forget_2), color = cs.error) }
            },
            dismissButton = { TextButton({ confirm = false }) { Text(S(R.string.cancel)) } },
        )
    }
}

@Composable
private fun BatteryRing(level: Int?) {
    val acc = AntTheme.accent.accent
    val net = AntTheme.net
    val track = cs.surfaceContainerHighest
    val color = when {
        level == null -> cs.outline
        level <= 15 -> cs.error
        level <= 35 -> net.fair
        else -> acc
    }
    Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(72.dp)) {
            val sw = 8.dp.toPx()
            val tl = Offset(sw / 2, sw / 2)
            val sz = Size(size.width - sw, size.height - sw)
            drawArc(track, 0f, 360f, false, tl, sz, style = Stroke(sw))
            if (level != null) drawArc(color, -90f, 3.6f * level, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
        }
        if (level != null) Text("$level %", style = gs(16, 20, 600, tnum = true))
        else Symbol(Sym.Battery, size = 24.dp, tint = cs.onSurfaceVariant)
    }
}
