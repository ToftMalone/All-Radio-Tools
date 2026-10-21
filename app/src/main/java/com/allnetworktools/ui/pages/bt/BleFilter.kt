package com.allnetworktools.ui.pages.bt

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.allnetworktools.data.BleDevice
import com.allnetworktools.data.BleVendor
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.PillButton
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.mono
import com.allnetworktools.R
import com.allnetworktools.util.S

enum class NameMode(val label: String) { All(S(R.string.all)), Named(S(R.string.with_name)), Unnamed(S(R.string.unnamed)) }

/** RSSI slider floor: at this value the RSSI filter is off. */
const val RssiOff = -100

/** BLE scanner filters, kept in the ViewModel so they survive navigation. */
class BleFilterState {
    /** When each address was first listed: the scanner keeps that order so rows do not jump around. */
    private val discovered = HashMap<String, Long>()

    fun inDiscoveryOrder(list: List<BleDevice>, now: Long = System.currentTimeMillis()): List<BleDevice> {
        list.forEach { discovered.getOrPut(it.address) { now } }
        return list.sortedWith(compareBy<BleDevice>({ discovered[it.address] }, { it.address }))
    }

    var text by mutableStateOf("")
    var nameMode by mutableStateOf(NameMode.All)
    var raw by mutableStateOf("")
    var exclude by mutableStateOf(emptySet<BleVendor>())
    var include by mutableStateOf(emptySet<BleVendor>())
    var minRssi by mutableIntStateOf(RssiOff)
    var sheetOpen by mutableStateOf(false)

    /** Normalised hex pattern: "0x4c 00" → "4C00"; null when empty or not hex. */
    val rawPattern: String?
        get() = raw.replace("0x", "", ignoreCase = true).filter { !it.isWhitespace() && it != ':' && it != '-' }.uppercase()
            .takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() || c in 'A'..'F' } }

    val rawInvalid get() = raw.isNotBlank() && rawPattern == null

    /** Filters set in the sheet (text and name mode are visible in the search bar). */
    val sheetCount get() = listOf(rawPattern != null, exclude.isNotEmpty(), include.isNotEmpty(), minRssi > RssiOff).count { it }

    fun toggleExclude(v: BleVendor) {
        exclude = if (v in exclude) exclude - v else exclude + v
        include = include - v
    }

    fun toggleInclude(v: BleVendor) {
        include = if (v in include) include - v else include + v
        exclude = exclude - v
    }

    fun reset() {
        raw = ""; exclude = emptySet(); include = emptySet(); minRssi = RssiOff
    }

    fun matches(d: BleDevice): Boolean {
        val q = text.trim()
        if (q.isNotEmpty() && !(d.name?.contains(q, true) == true || d.model?.contains(q, true) == true || d.maker?.contains(q, true) == true || d.address.contains(q, true) || d.address.replace(":", "").contains(q.replace(":", ""), true))) return false
        when (nameMode) {
            NameMode.Named -> if (d.name.isNullOrBlank()) return false
            NameMode.Unnamed -> if (!d.name.isNullOrBlank()) return false
            NameMode.All -> Unit
        }
        rawPattern?.let { p -> if (d.raw?.contains(p) != true) return false }
        if (minRssi > RssiOff && d.rssi < minRssi) return false
        val v = d.vendors
        if (exclude.isNotEmpty() && v.any { it in exclude }) return false
        if (include.isNotEmpty() && v.none { it in include }) return false
        return true
    }
}

/** Search field with the named / unnamed menu and the filter sheet button. */
@Composable
fun BleSearchBar(f: BleFilterState, onOpenSheet: () -> Unit) {
    val acc = AntTheme.accent
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(26.dp)).background(cs.surfaceContainerHigh).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Symbol(Sym.Search, size = 22.dp, tint = cs.onSurfaceVariant)
            Box(Modifier.weight(1f)) {
                if (f.text.isEmpty()) Text(S(R.string.name_model_or_address), style = rf(15, 20), color = cs.onSurfaceVariant)
                BasicTextField(
                    f.text, { f.text = it }, Modifier.fillMaxWidth(), singleLine = true,
                    textStyle = rf(15, 20).copy(color = cs.onSurface), cursorBrush = SolidColor(acc.accent),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None),
                )
            }
            if (f.text.isNotEmpty()) {
                Box(Modifier.size(36.dp).clip(CircleShape).clickable { f.text = "" }, contentAlignment = Alignment.Center) {
                    Symbol(Sym.Cancel, size = 20.dp, tint = cs.onSurfaceVariant)
                }
            }
            Box {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(if (f.nameMode != NameMode.All) acc.container else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { menu = true },
                    contentAlignment = Alignment.Center,
                ) { Symbol(Sym.MoreVert, size = 22.dp, tint = if (f.nameMode != NameMode.All) acc.onContainer else cs.onSurfaceVariant) }
                DropdownMenu(menu, { menu = false }, containerColor = cs.surfaceContainer) {
                    NameMode.entries.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(m.label, style = rf(15, 20, if (m == f.nameMode) 600 else 400)) },
                            onClick = { f.nameMode = m; menu = false },
                            leadingIcon = { Symbol(if (m == f.nameMode) Sym.CheckCircle else Sym.RadioUnchecked, size = 20.dp, filled = m == f.nameMode, tint = if (m == f.nameMode) acc.accent else cs.onSurfaceVariant) },
                        )
                    }
                }
            }
        }
        Box {
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(18.dp)).background(if (f.sheetCount > 0) acc.accent else cs.surfaceContainerHigh).clickable(onClick = onOpenSheet),
                contentAlignment = Alignment.Center,
            ) { Symbol(Sym.Tune, size = 24.dp, tint = if (f.sheetCount > 0) acc.onAccent else cs.onSurfaceVariant) }
            if (f.sheetCount > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(2.dp).size(18.dp).clip(CircleShape).background(cs.error),
                    contentAlignment = Alignment.Center,
                ) { Text(f.sheetCount.toString(), style = rf(10, 12, 700), color = cs.onError) }
            }
        }
    }
}

/** Active sheet filters as removable chips. */
@Composable
fun BleActiveFilters(f: BleFilterState, onOpen: () -> Unit) {
    val items = buildList {
        if (f.nameMode != NameMode.All) add(f.nameMode.label to { f.nameMode = NameMode.All })
        f.rawPattern?.let { add(S(R.string.data_0x, it) to { f.raw = "" }) }
        if (f.minRssi > RssiOff) add("RSSI ≥ ${com.allnetworktools.util.fmt(f.minRssi)}" to { f.minRssi = RssiOff })
        f.exclude.forEach { v -> add(S(R.string.without, v.label) to { f.exclude = f.exclude - v }) }
        f.include.forEach { v -> add(S(R.string.only, v.label) to { f.include = f.include - v }) }
    }
    if (items.isEmpty()) return
    val acc = AntTheme.accent
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (label, remove) ->
            Row(
                Modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).background(acc.container).clickable(onClick = onOpen).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(label, style = rf(13, 18, 600), color = acc.onContainer, maxLines = 1)
                Box(Modifier.size(24.dp).clip(CircleShape).clickable(onClick = remove), contentAlignment = Alignment.Center) {
                    Symbol(Sym.Close, size = 16.dp, tint = acc.onContainer)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BleFilterSheet(f: BleFilterState, matching: Int, total: Int, onDismiss: () -> Unit) {
    val acc = AntTheme.accent
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = cs.surfaceContainerLow) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(S(R.string.filters), Modifier.weight(1f), style = gs(24, 30, 500))
                Text("$matching / $total", style = rf(14, 20, 600, tnum = true), color = cs.onSurfaceVariant)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(S(R.string.raw_advertising_data), style = rf(14, 20, 600), color = acc.accent)
                Row(
                    Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHigh).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (f.raw.isEmpty()) Text("0x4C0002…", style = mono(15, 20), color = cs.onSurfaceVariant)
                        BasicTextField(
                            f.raw, { f.raw = it }, Modifier.fillMaxWidth(), singleLine = true,
                            textStyle = mono(15, 20).copy(color = if (f.rawInvalid) cs.error else cs.onSurface), cursorBrush = SolidColor(acc.accent),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                        )
                    }
                    if (f.raw.isNotEmpty()) Box(Modifier.size(32.dp).clip(CircleShape).clickable { f.raw = "" }, contentAlignment = Alignment.Center) { Symbol(Sym.Cancel, size = 20.dp, tint = cs.onSurfaceVariant) }
                }
                Text(
                    if (f.rawInvalid) S(R.string.hexadecimal_only_0_9_a_f) else S(R.string.byte_sequence_searched_in_the_packet),
                    style = rf(12, 16), color = if (f.rawInvalid) cs.error else cs.onSurfaceVariant,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(S(R.string.exclude), style = rf(14, 20, 600), color = acc.accent)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BleVendor.entries.forEach { v -> AntFilterChip(v.label, v in f.exclude, { f.toggleExclude(v) }, cs.errorContainer, cs.onErrorContainer) }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(S(R.string.include_only), style = rf(14, 20, 600), color = acc.accent)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BleVendor.entries.forEach { v -> AntFilterChip(v.label, v in f.include, { f.toggleInclude(v) }) }
                }
            }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(S(R.string.minimum_rssi), Modifier.weight(1f), style = rf(14, 20, 600), color = acc.accent)
                    Text(if (f.minRssi <= RssiOff) S(R.string.all) else "≥ ${com.allnetworktools.util.fmt(f.minRssi)} dBm", style = rf(14, 20, 600, tnum = true))
                }
                Slider(
                    f.minRssi.toFloat(), { f.minRssi = it.toInt() }, valueRange = RssiOff.toFloat()..-30f, steps = 13,
                    colors = SliderDefaults.colors(thumbColor = acc.accent, activeTrackColor = acc.accent),
                )
                Row {
                    Text(S(R.string.n_100_all), Modifier.weight(1f), style = rf(11, 14), color = cs.onSurfaceVariant)
                    Text(S(R.string.n_30_dbm_very_close), style = rf(11, 14), color = cs.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(S(R.string.reset), { f.reset() }, Modifier.weight(1f), icon = Sym.RestartAlt, height = 48.dp, outlined = true, bg = acc.accent)
                PillButton(S(R.string.show, matching), onDismiss, Modifier.weight(1f), icon = Sym.Check, height = 48.dp, bg = acc.accent, fg = acc.onAccent)
            }
        }
    }
}
