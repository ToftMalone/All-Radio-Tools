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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.Page
import com.allnetworktools.R
import com.allnetworktools.data.BleAddrKind
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
        onExport = {
            if (Wardrive.exportCount(context) == 0L) actions.toast(S(R.string.wardriving_empty))
            else actions.saveStream(
                "AllRadioTools_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv", "text/csv",
            ) { Wardrive.exportAll(context, it) }
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
                    if (row.type == NetType.Wifi) {
                        val g = guardStyle(row.guard)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Symbol(g.icon, size = 18.dp, filled = true, tint = g.color)
                            Text(g.label + " · " + row.detail, style = rf(13, 18), color = cs.onSurfaceVariant)
                        }
                    }
                    row.addr?.let { a ->
                        val st = addrStyle(a)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Symbol(st.icon, size = 18.dp, filled = true, tint = st.color)
                            Text(st.note, style = rf(13, 18), color = cs.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (!row.excluded) DialogChoice(Sym.Block, S(R.string.wardriving_exclude_mac)) {
                        exclude(context, settings?.exclusions.orEmpty(), row.mac); picked = null
                    }
                    if (canOui && !row.excluded) DialogChoice(Sym.Block, S(R.string.wardriving_exclude_oui, vendor ?: oui, oui)) {
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
fun WardrivingScreen(st: WardriveState, onUpload: () -> Unit, onExport: () -> Unit, onToggle: () -> Unit, onRow: (WardriveRow) -> Unit) {
    val nav = WindowInsets.navigationBars.asPaddingValues()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 128.dp + nav.calculateBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        item(key = "head") {
            Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Header(st, onUpload, onExport, onToggle)
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val ms = st.lastScanMs
                    Text(
                        if (st.running && ms != null) P(R.plurals.p_wardriving_scanned, st.lastScanCount, st.lastScanCount, ms.toInt())
                        else P(R.plurals.p_wardriving_pending, st.pending.toInt(), st.pending.toInt()),
                        Modifier.weight(1f), style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(S(R.string.wardriving_queue, st.queue), style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 1)
                }
            }
        }
        if (st.rows.isEmpty()) {
            item(key = "empty") {
                ToolEmpty(Sym.DirectionsCar, S(R.string.wardriving_empty), S(R.string.wardriving_empty_text), if (st.running) null else S(R.string.start), onToggle)
            }
        } else {
            val n = st.rows.size
            itemsIndexed(st.rows, key = { _, r -> r.type.code + r.mac }) { i, r -> NetworkLine(r, onRow) }
        }
    }
}

/**
 * The top of the page: a recording dial with the position beside it, four counters in tiles, then two actions side by
 * side, to keep the run as a file or to send it.
 */
@Composable
private fun Header(st: WardriveState, onUpload: () -> Unit, onExport: () -> Unit, onToggle: () -> Unit) {
    val acc = AntTheme.accent
    val net = AntTheme.net
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val fix = st.fix
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(cs.surfaceContainerHigh).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // The dial: a ring that is lit while it records.
            Box(
                Modifier.size(72.dp).clip(CircleShape).border(5.dp, if (st.running) acc.accent else cs.outlineVariant, CircleShape)
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(if (st.running) 30.dp else 40.dp).clip(RoundedCornerShape(if (st.running) 8.dp else 20.dp))
                        .background(if (st.running) acc.accent else cs.onSurfaceVariant),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Symbol(if (fix != null) Sym.GpsFixed else Sym.GpsOff, size = 18.dp, filled = true, tint = if (fix != null) net.good else cs.onSurfaceVariant)
                    when {
                        !st.running -> Text(S(R.string.wardriving_stopped), style = rf(14, 19, 500), color = cs.onSurface)
                        fix == null -> Text(S(R.string.wardriving_waiting_gps_sync), style = rf(14, 19, 500), color = cs.onSurface)
                        else -> Text(String.format(Locale.US, "%.5f, %.5f", fix.lat, fix.lon), style = mono(14, 19, 600), color = cs.onSurface, maxLines = 1)
                    }
                }
                if (st.running && fix != null) {
                    Text(
                        (if (fix.gps) "GPS" else S(R.string.wardriving_network_fix)) + " ±${fix.accuracy.toInt()} m · ${fix.altitude.toInt()} m",
                        style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 1,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Counter(Modifier.weight(1f), Sym.Wifi, st.newWifi.toString(), net.wifi.accent)
            Counter(Modifier.weight(1f), Sym.Bluetooth, st.newBle.toString(), net.bt.accent)
            Counter(Modifier.weight(1f), Sym.Star, st.newTotal.toString(), acc.accent)
            Counter(Modifier.weight(1f), Sym.Database, st.db.total.toString(), cs.onSurfaceVariant)
        }
        val sending = st.upload == UploadState.Sending
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(18.dp)).border(1.5.dp, acc.accent, RoundedCornerShape(18.dp)).clickable(onClick = onExport),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
            ) {
                Symbol(Sym.Download, size = 20.dp, tint = acc.accent)
                Text(S(R.string.wardriving_export), style = rf(15, 20, 600), color = acc.accent, maxLines = 1)
            }
            Row(
                Modifier.weight(1.4f).height(52.dp).clip(RoundedCornerShape(18.dp)).background(acc.accent).clickable(enabled = !sending, onClick = onUpload),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
            ) {
                Symbol(Sym.CloudUpload, size = 20.dp, filled = true, tint = acc.onAccent)
                Text(if (sending) S(R.string.wardriving_sending) else S(R.string.wardriving_upload), style = rf(15, 20, 600), color = acc.onAccent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!sending && st.pending > 0) {
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(acc.onAccent).padding(horizontal = 8.dp, vertical = 1.dp)) {
                        Text(st.pending.toString(), style = gs(12, 16, 700, tnum = true), color = acc.accent)
                    }
                }
            }
        }
    }
}

/** A counter tile: the symbol over the number. */
@Composable
private fun Counter(modifier: Modifier, icon: String, value: String, tint: Color) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainerLow).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Symbol(icon, size = 20.dp, filled = true, tint = tint)
        Text(value, style = gs(17, 22, 700, tnum = true), color = cs.onSurface, maxLines = 1)
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
    val ble = r.type == NetType.Ble
    // Compact on purpose: about as many networks on screen as a dedicated wardriving app.
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surfaceContainerLow).clickable { onRow(r) }
            // Excluded: still shown, faded, with a red mark, so it is clear it is not recorded.
            .graphicsLayer { alpha = if (r.excluded) 0.5f else 1f }
            .padding(start = 6.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (ble) BleBadge(r) else GuardBadge(r.guard)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (r.excluded) {
                    Row(
                        Modifier.clip(RoundedCornerShape(6.dp)).background(cs.error).padding(horizontal = 5.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Symbol(Sym.Block, size = 11.dp, tint = cs.onError)
                        Text(S(R.string.wardriving_excluded), style = rf(10, 14, 700), color = cs.onError, maxLines = 1)
                    }
                }
                Text(
                    r.name.ifEmpty { S(if (ble) R.string.unknown_device else R.string.hidden_network) }, style = rf(14, 18, 600),
                    color = if (r.name.isEmpty()) cs.onSurfaceVariant else cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textDecoration = if (r.excluded) TextDecoration.LineThrough else null,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val addr = r.addr
                if (addr != null) {
                    val st = addrStyle(addr)
                    Symbol(st.icon, size = 13.dp, filled = true, tint = st.color)
                }
                Text(
                    buildString {
                        append(r.mac.lowercase(Locale.ROOT))
                        if (!ble) append(" · ").append(r.frequency).append(" MHz")
                        if (r.vendor != null) append(" · ").append(r.vendor)
                    },
                    style = mono(11, 15), color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${r.level}", style = gs(14, 18, 700, tnum = true), color = levelColor)
            val time = synchronized(clock) { clock.format(Date(r.seenAt)) }
            Text(time, style = rf(10, 13), color = cs.onSurfaceVariant)
        }
    }
}

/** The look of a Wi-Fi protection: symbol, short label, and how safe it is. */
private class GuardStyle(val icon: String, val label: String, val color: Color)

@Composable
private fun guardStyle(g: Guard): GuardStyle {
    val net = AntTheme.net
    return when (g) {
        Guard.Open -> GuardStyle(Sym.LockOpen, S(R.string.wardriving_open), net.poor)
        Guard.Owe -> GuardStyle(Sym.Shield, "OWE", net.fair)
        Guard.Wep -> GuardStyle(Sym.NoEncryption, "WEP", net.poor)
        Guard.Wpa -> GuardStyle(Sym.Lock, "WPA", net.fair)
        Guard.Wpa2 -> GuardStyle(Sym.Lock, "WPA2", net.good)
        Guard.Wpa23 -> GuardStyle(Sym.EnhancedEncryption, "WPA2/3", net.good)
        Guard.Wpa3 -> GuardStyle(Sym.VerifiedUser, "WPA3", net.good)
        Guard.Enterprise -> GuardStyle(Sym.Badge, "EAP", net.good)
        Guard.Ble -> GuardStyle(Sym.Bluetooth, "BLE", net.bt.accent)
    }
}

/** The look of a Bluetooth address kind. */
private class AddrStyle(val icon: String, val label: String, val note: String, val color: Color)

@Composable
private fun addrStyle(a: BleAddrKind): AddrStyle {
    val net = AntTheme.net
    return when (a) {
        BleAddrKind.Public -> AddrStyle(Sym.Fingerprint, S(R.string.ble_addr_public), S(R.string.ble_addr_public_note), net.poor)
        BleAddrKind.Static -> AddrStyle(Sym.PushPin, S(R.string.ble_addr_static), S(R.string.static_random_fixed_until_the_device), net.fair)
        BleAddrKind.Resolvable -> AddrStyle(Sym.SyncLock, S(R.string.ble_addr_resolvable), S(R.string.resolvable_private_changes_every_15_min), net.good)
        BleAddrKind.NonResolvable -> AddrStyle(Sym.Shuffle, S(R.string.ble_addr_anonymous), S(R.string.ble_addr_anonymous_note), net.good)
        BleAddrKind.Unknown -> AddrStyle(Sym.QuestionMark, S(R.string.ble_addr_unknown), S(R.string.ble_addr_unknown_note), cs.onSurfaceVariant)
    }
}

/** The protection of a Wi-Fi network: its symbol over its short name, on a tinted tile. */
@Composable
private fun GuardBadge(g: Guard) {
    val st = guardStyle(g)
    Column(
        Modifier.width(44.dp).clip(RoundedCornerShape(10.dp)).background(st.color.copy(alpha = 0.16f)).padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Symbol(st.icon, size = 18.dp, filled = true, tint = st.color)
        Text(st.label, style = rf(9, 11, 700), color = st.color, maxLines = 1)
    }
}

/** A Bluetooth device: what it is when it is known without doubt, else the Bluetooth logo. */
@Composable
private fun BleBadge(r: WardriveRow) {
    val c = AntTheme.net.bt.accent
    Box(
        Modifier.width(44.dp).height(36.dp).clip(RoundedCornerShape(10.dp)).background(c.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Symbol(r.icon ?: Sym.Bluetooth, size = 20.dp, filled = true, tint = c)
    }
}
