package com.allnetworktools.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.BuildConfig
import com.allnetworktools.MainViewModel
import com.allnetworktools.data.PermGroup
import com.allnetworktools.data.SignalDisplay
import com.allnetworktools.data.ThemeMode
import com.allnetworktools.data.Units
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.SegmentedRow
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.groupShape
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.pages.TopBarAction
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.R
import com.allnetworktools.util.S

private data class PermRow(val group: PermGroup, val icon: String, val title: String, val subtitle: String)

private val PermRows get() = AllPermRows.filter { it.group.permissions.isNotEmpty() }

private val AllPermRows = listOf(
    PermRow(PermGroup.Location, Sym.LocationOn, S(R.string.precise_location), S(R.string.wi_fi_ssid_scans_cells_and)),
    PermRow(PermGroup.Nearby, Sym.BluetoothSearching, S(R.string.nearby_devices), S(R.string.bluetooth_scan_and_connection)),
    PermRow(PermGroup.Phone, Sym.SimCard, S(R.string.phone), S(R.string.carrier_cells_dual_sim)),
    PermRow(PermGroup.LocalNetwork, Sym.Lan, S(R.string.local_network), S(R.string.lan_devices_ping_port_scan)),
    PermRow(PermGroup.Notifications, Sym.Notifications, "Notifications", S(R.string.background_handover_log)),
)

private val Licenses = listOf(
    "AndroidX, Jetpack Compose, Material 3" to "Apache 2.0",
    "Material Components for Android" to "Apache 2.0",
    "Kotlin, kotlinx.coroutines" to "Apache 2.0",
    "Roboto Flex, Google Sans Flex" to "SIL Open Font License 1.1",
    "Material Symbols" to "Apache 2.0",
)

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s = AntTheme.settings
    val perms by vm.permissions.collectAsStateWithLifecycle()
    val actions = LocalActions.current
    var dialog by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    TopBarAction(Sym.Help) { dialog = "help" }
    PageColumn {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Section(S(R.string.appearance)) {
                Item(0, 1, Sym.Contrast, S(R.string.theme), S(R.string.follows_the_android_setting_by_default)) {
                    SegmentedRow(
                        listOf(ThemeMode.System to S(R.string.system), ThemeMode.Light to S(R.string.light), ThemeMode.Dark to S(R.string.dark)), s.theme,
                        { vm.updateSettings { setTheme(it) } }, Modifier.fillMaxWidth(),
                        selectedColor = cs.primary, onSelectedColor = cs.onPrimary,
                        icons = mapOf(ThemeMode.System to Sym.BrightnessAuto, ThemeMode.Light to Sym.LightMode, ThemeMode.Dark to Sym.DarkMode),
                    )
                }
            }
            Section(S(R.string.measurements)) {
                Item(0, 4, Sym.Straighten, S(R.string.units), S(R.string.distances_speeds_and_altitudes)) {
                    SegmentedRow(listOf(Units.Metric to S(R.string.metric), Units.Imperial to S(R.string.imperial)), s.units, { vm.updateSettings { setUnits(it) } }, Modifier.fillMaxWidth(), selectedColor = cs.primary, onSelectedColor = cs.onPrimary)
                }
                Item(1, 4, Sym.CellBars3, S(R.string.signal_display), S(R.string.raw_value_or_percentage)) {
                    SegmentedRow(listOf(SignalDisplay.Dbm to "dBm", SignalDisplay.Percent to "%"), s.signal, { vm.updateSettings { setSignal(it) } }, Modifier.fillMaxWidth(), selectedColor = cs.primary, onSelectedColor = cs.onPrimary)
                }
                Item(2, 4, Sym.Vibration, S(R.string.haptic_feedback), S(R.string.vibrations_on_important_actions), trailing = { AntSwitch(s.haptics) }, onClick = { vm.updateSettings { setHaptics(!s.haptics) } })
                Item(3, 4, Sym.ScreenLock, S(R.string.screen_always_on), S(R.string.during_a_measurement_or_a_recording), trailing = { AntSwitch(s.keepAwake) }, onClick = { vm.updateSettings { setKeepAwake(!s.keepAwake) } })
            }
            Section(S(R.string.permissions)) {
                PermRows.forEachIndexed { i, p ->
                    val granted = p.group in perms
                    Item(
                        i, PermRows.size, p.icon, p.title, p.subtitle,
                        trailing = { PermPill(granted) },
                        onClick = {
                            if (granted) {
                                actions.toast(S(R.string.managed_in_android_settings_apps))
                                actions.openAppSettings()
                            } else {
                                actions.request(p.group)
                            }
                        },
                    )
                }
            }
            Section(S(R.string.app)) {
                val us by vm.updater.state.collectAsStateWithLifecycle()
                val latest by vm.updater.latest.collectAsStateWithLifecycle()
                // Checked at every launch: this only says what was found.
                Item(
                    0, 3, Sym.SystemUpdate, S(R.string.updated_2),
                    when (val u = us) {
                        is com.allnetworktools.update.UpdateState.Downloading -> S(R.string.downloading_version, u.info.version)
                        is com.allnetworktools.update.UpdateState.Installing -> S(R.string.installing)
                        else -> latest?.let { S(R.string.version_available, it.version) } ?: S(R.string.installed_version, BuildConfig.VERSION_NAME)
                    },
                    trailing = latest?.let { { LinkIcon(Sym.ChevronRight) } },
                    onClick = latest?.let { { vm.updater.reoffer() } },
                )
                Item(
                    1, 3, Sym.NewReleases, S(R.string.changelog),
                    trailing = { LinkIcon(Sym.ChevronRight) },
                    onClick = { vm.navigate { it.copy(network = null, page = com.allnetworktools.Page.Changelog) } },
                )
                Item(2, 3, Sym.Info, S(R.string.about), trailing = { LinkIcon(Sym.ChevronRight) }, onClick = { dialog = "about" })
            }
            Text(
                S(R.string.all_radio_tools_no_measurement_is, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp), style = rf(12, 18), color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
    }
    when (dialog) {
        "about" -> InfoDialog(
            "All Radio Tools ${BuildConfig.VERSION_NAME}",
            "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}).\n\n" +
                S(R.string.privacy_measurements_are_processed_in_memory) +
                S(R.string.and_the_app_contains_no_tracker) +
                S(R.string.open_source_licences_n) + Licenses.joinToString("\n") { (lib, lic) -> "$lib · $lic" },
        ) { dialog = null }
        "help" -> InfoDialog(
            S(R.string.help),
            S(R.string.tap_a_home_card_to_select) +
                S(R.string.tools_or_main_tool_the_dock),
        ) { dialog = null }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, Modifier.padding(start = 4.dp, end = 4.dp, top = 16.dp, bottom = 4.dp), style = rf(14, 20, 600), color = cs.primary)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
}

@Composable
private fun Item(
    index: Int,
    count: Int,
    icon: String,
    title: String,
    subtitle: String? = null,
    danger: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    below: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().clip(groupShape(index, count, 24.dp, 6.dp)).background(cs.surfaceContainerLow)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Symbol(icon, size = 24.dp, tint = cs.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(title, style = rf(16, 22, 500), color = if (danger) cs.error else cs.onSurface)
                if (subtitle != null) Text(subtitle, style = rf(13, 18), color = cs.onSurfaceVariant)
            }
            trailing?.invoke()
        }
        if (below != null) Box(Modifier.padding(start = 40.dp, top = 12.dp)) { below() }
    }
}

@Composable
private fun AntSwitch(checked: Boolean) {
    Switch(
        checked = checked,
        onCheckedChange = null,
        thumbContent = if (checked) ({ Symbol(Sym.Check, size = 16.dp, tint = cs.primary) }) else null,
        colors = SwitchDefaults.colors(checkedTrackColor = cs.primary, checkedThumbColor = cs.onPrimary),
    )
}

@Composable
private fun LinkIcon(icon: String) = Symbol(icon, size = 22.dp, tint = cs.onSurfaceVariant)

@Composable
private fun PermPill(granted: Boolean) {
    val good = AntTheme.net.good
    Row(
        Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(if (granted) Color.Transparent else cs.primary).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (granted) Symbol(Sym.CheckCircle, size = 16.dp, filled = true, tint = good)
        Text(if (granted) S(R.string.granted) else S(R.string.allow), style = rf(12, 16, 700), color = if (granted) good else cs.onPrimary)
        if (!granted) Symbol(Sym.ArrowForward, size = 16.dp, tint = cs.onPrimary)
    }
}

@Composable
private fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (v, label) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(v) }.padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        androidx.compose.material3.RadioButton(selected = v == selected, onClick = null)
                        Text(label, style = rf(16, 22))
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(S(R.string.close)) } },
    )
}

@Composable
private fun InfoDialog(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text, style = rf(14, 20)) },
        confirmButton = { TextButton(onDismiss) { Text("OK") } },
    )
}
