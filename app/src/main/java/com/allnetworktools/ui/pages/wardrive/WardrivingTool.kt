package com.allnetworktools.ui.pages.wardrive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.Page
import com.allnetworktools.R
import com.allnetworktools.data.Exclusions
import com.allnetworktools.data.Guard
import com.allnetworktools.data.NetType
import com.allnetworktools.data.PermGroup
import com.allnetworktools.data.UploadState
import com.allnetworktools.data.Wardrive
import com.allnetworktools.data.WardriveRow
import com.allnetworktools.data.WardriveState
import com.allnetworktools.model.Tool
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.pages.TopBarAction
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.ToolEmpty
import com.allnetworktools.ui.tools.mono
import com.allnetworktools.util.P
import com.allnetworktools.util.S
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The WiGLE home screen: counts of the run and of the database, the upload button, the position, the recording switch,
 * the last scan, then every network heard, strongest first.
 */
@Composable
fun WardrivingTool(vm: MainViewModel) {
    val context = LocalContext.current
    val actions = LocalActions.current
    val st by Wardrive.state.collectAsStateWithLifecycle()
    val settings by Wardrive.settings(context).settings.collectAsStateWithLifecycle(null)
    val perms by vm.permissions.collectAsStateWithLifecycle()
    // While it records, the engine keeps the counts up to date itself.
    LaunchedEffect(Unit) { if (!Wardrive.state.value.running) Wardrive.load(context) }
    TopBarAction(Sym.Settings) { vm.navigate { it.copy(page = Page.ToolPage(Tool.WardrivingSettings)) } }
    LaunchedEffect(st.upload) {
        val msg = when (val u = st.upload) {
            is UploadState.Done -> P(R.plurals.p_wardriving_uploaded, u.count.toInt(), u.count.toInt())
            UploadState.Nothing -> S(R.string.wardriving_upload_nothing)
            is UploadState.Failed -> S(R.string.wardriving_upload_failed, u.message)
            else -> null
        }
        if (msg != null) {
            actions.toast(msg)
            Wardrive.dismissUpload()
        }
    }
    var picked by remember { mutableStateOf<WardriveRow?>(null) }
    WardrivingScreen(
        st,
        onUpload = {
            if (settings?.connected == true) Wardrive.upload(context)
            else {
                actions.toast(S(R.string.wardriving_connect_first))
                vm.navigate { it.copy(page = Page.ToolPage(Tool.WardrivingSettings)) }
            }
        },
        onToggle = {
            if (st.running) Wardrive.stop(context) else {
                if (PermGroup.Notifications !in perms) actions.request(PermGroup.Notifications)
                Wardrive.start(context)
            }
        },
        onRow = { picked = it },
    )
    picked?.let { row ->
        val vendor = row.vendor
        val oui = row.mac.take(8).uppercase(Locale.ROOT)
        val canOui = row.type == NetType.Wifi && Wardrive.oui(context).vendor(row.mac) != null
        AlertDialog(
            onDismissRequest = { picked = null },
            title = { Text(row.name.ifEmpty { row.mac.uppercase(Locale.ROOT) }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(row.mac.uppercase(Locale.ROOT), style = mono(14))
                    if (vendor != null) Text(vendor, style = rf(14, 20), color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    DialogChoice(Sym.Block, S(R.string.wardriving_exclude_mac)) {
                        exclude(context, settings?.exclusions.orEmpty(), row.mac); picked = null
                    }
                    if (canOui) DialogChoice(Sym.Block, S(R.string.wardriving_exclude_oui, vendor ?: oui, oui)) {
                        exclude(context, settings?.exclusions.orEmpty(), oui); picked = null
                    }
                    DialogChoice(Sym.ContentCopy, S(R.string.copy)) {
                        actions.copy("MAC", row.mac.uppercase(Locale.ROOT)); picked = null
                    }
                }
            },
            confirmButton = { TextButton({ picked = null }) { Text(S(R.string.close)) } },
        )
    }
}

private fun exclude(context: android.content.Context, current: Set<String>, entry: String) {
    val e = Exclusions.pretty(entry) ?: return
    Wardrive.applyExclusions(context, current + e)
}

@Composable
private fun DialogChoice(icon: String, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Symbol(icon, size = 20.dp, tint = AntTheme.accent.accent)
        Text(text, style = rf(15, 20))
    }
}

@Composable
fun WardrivingScreen(st: WardriveState, onUpload: () -> Unit, onToggle: () -> Unit, onRow: (WardriveRow) -> Unit) {
    val acc = AntTheme.accent
    val net = AntTheme.net
    val nav = WindowInsets.navigationBars.asPaddingValues()
    Column(Modifier.fillMaxSize()) {
        // Run counters, then the database.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Counter(Sym.Wifi, st.newWifi.toString(), net.wifi.accent)
            Counter(Sym.Bluetooth, st.newBle.toString(), net.bt.accent)
            Spacer(Modifier.weight(1f))
            Counter(Sym.Star, st.newTotal.toString(), acc.accent)
            Text("/", style = rf(16, 22), color = cs.onSurfaceVariant)
            Counter(Sym.Database, st.db.total.toString(), cs.onSurface)
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val sending = st.upload == UploadState.Sending
            Box(
                Modifier.widthIn(max = 120.dp).heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp))
                    .border(2.dp, acc.accent, RoundedCornerShape(14.dp)).background(acc.container)
                    .clickable(enabled = !sending, onClick = onUpload).padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (sending) S(R.string.wardriving_sending) else S(R.string.wardriving_upload),
                    style = rf(13, 17, 700), color = acc.onContainer, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            val fix = st.fix
            Box(
                Modifier.size(44.dp).clip(CircleShape).border(2.dp, if (fix != null) net.good else cs.outline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Symbol(if (fix != null) Sym.GpsFixed else Sym.GpsOff, size = 24.dp, tint = if (fix != null) net.good else cs.onSurfaceVariant)
            }
            Column(Modifier.weight(1f)) {
                when {
                    !st.running -> Text(S(R.string.wardriving_stopped), style = rf(13, 18), color = cs.onSurfaceVariant)
                    fix == null -> Text(S(R.string.wardriving_waiting_gps_sync), style = rf(13, 18), color = cs.onSurfaceVariant)
                    else -> {
                        Text(String.format(Locale.US, "%.5f, %.5f", fix.lat, fix.lon), style = mono(13, 18), maxLines = 1)
                        Text(
                            (if (fix.gps) "GPS" else S(R.string.wardriving_network_fix)) + " ±${fix.accuracy.toInt()} m · ${fix.altitude.toInt()} m",
                            style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 1,
                        )
                    }
                }
            }
            // The recording switch: the Wi-Fi symbol lit while it records.
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(if (st.running) acc.accent else cs.surfaceContainerHigh).clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                Symbol(if (st.running) Sym.Wifi else Sym.WifiOff, size = 32.dp, filled = true, tint = if (st.running) acc.onAccent else cs.onSurfaceVariant)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val ms = st.lastScanMs
            Text(
                if (st.running && ms != null) P(R.plurals.p_wardriving_scanned, st.lastScanCount, st.lastScanCount, ms.toInt())
                else P(R.plurals.p_wardriving_pending, st.pending.toInt(), st.pending.toInt()),
                Modifier.weight(1f), style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(S(R.string.wardriving_queue, st.queue), style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(acc.accent))
        if (st.rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(16.dp)) {
                ToolEmpty(Sym.DirectionsCar, S(R.string.wardriving_empty), S(R.string.wardriving_empty_text), if (st.running) null else S(R.string.start), onToggle)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 128.dp + nav.calculateBottomPadding())) {
                items(st.rows, key = { it.type.code + it.mac }) { r ->
                    NetworkLine(r, onRow)
                    Hairline()
                }
            }
        }
    }
}

@Composable
private fun Counter(icon: String, value: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Symbol(icon, size = 20.dp, filled = true, tint = tint)
        Text(value, style = gs(16, 22, 600, tnum = true), color = tint)
    }
}

private val clock = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

@Composable
private fun NetworkLine(r: WardriveRow, onRow: (WardriveRow) -> Unit) {
    val net = AntTheme.net
    val levelColor = when {
        r.level >= -60 -> net.good
        r.level >= -75 -> net.fair
        else -> net.poor
    }
    Row(
        Modifier.fillMaxWidth().clickable { onRow(r) }.padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            GuardBadge(r.guard)
            Text(r.level.toString(), style = gs(13, 16, 600, tnum = true), color = levelColor)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.name, Modifier.weight(1f), style = rf(15, 20, 600), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val time = synchronized(clock) { clock.format(Date(r.seenAt)) }
                Text(
                    listOfNotNull(r.vendor, time).joinToString(" - "), Modifier.padding(start = 8.dp).widthIn(max = 220.dp),
                    style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.mac.lowercase(Locale.ROOT), style = mono(13, 18))
                Text(if (r.type == NetType.Wifi) "${r.frequency}MHz" else "BLE", Modifier.padding(start = 8.dp), style = rf(12, 16), color = cs.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(r.detail, Modifier.padding(start = 8.dp), style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The lock of a Wi-Fi network with its family, or the Bluetooth LE symbol. */
@Composable
private fun GuardBadge(g: Guard) {
    val net = AntTheme.net
    val (icon, label, color) = when (g) {
        Guard.Wpa3 -> Triple(Sym.Lock, "WPA3", net.good)
        Guard.Wpa -> Triple(Sym.Lock, "WPA", net.good)
        Guard.Wep -> Triple(Sym.Lock, "WEP", net.fair)
        Guard.Open -> Triple(Sym.LockOpen, S(R.string.wardriving_open), net.poor)
        Guard.Ble -> Triple(Sym.Bluetooth, "LE", net.bt.accent)
    }
    Box(contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Symbol(icon, size = 22.dp, filled = true, tint = color)
            Text(label, style = rf(9, 10, 700), color = color, maxLines = 1)
        }
    }
}
