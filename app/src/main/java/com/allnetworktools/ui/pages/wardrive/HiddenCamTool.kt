package com.allnetworktools.ui.pages.wardrive

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.allnetworktools.R
import com.allnetworktools.data.CamReason
import com.allnetworktools.data.CamSource
import com.allnetworktools.data.CamSuspect
import com.allnetworktools.data.HiddenCam
import com.allnetworktools.data.Wardrive
import com.allnetworktools.data.WifiConnection
import com.allnetworktools.data.net.LanScanner
import com.allnetworktools.data.net.subnetHosts
import com.allnetworktools.ui.components.LevelBar
import com.allnetworktools.ui.components.PillButton
import com.allnetworktools.ui.components.SectionTitle
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.groupShape
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.pages.ToolBack
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.ProgressBar
import com.allnetworktools.ui.tools.mono
import com.allnetworktools.util.P
import com.allnetworktools.util.S
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** The scan of the network the phone is connected to, kept while the app runs. */
class HiddenCamController(private val scope: CoroutineScope, private val lan: LanScanner) {
    sealed interface LanState {
        data object Idle : LanState
        data class Scanning(val fraction: Float) : LanState
        data class Done(val ssid: String?, val suspects: List<CamSuspect>, val hosts: Int) : LanState
    }

    var lanState by mutableStateOf<LanState>(LanState.Idle)
        private set
    private var job: Job? = null

    /** Every device of the subnet, then the camera ports of those that answered. */
    fun scanLan(conn: WifiConnection) {
        val ip = conn.ipv4 ?: return
        job?.cancel()
        lanState = LanState.Scanning(0f)
        job = scope.launch {
            val hosts = subnetHosts(ip, conn.prefix ?: 24)
            var devices = emptyList<com.allnetworktools.data.net.LanDevice>()
            lan.scan(hosts, ip, conn.gateway).collect { p ->
                lanState = LanState.Scanning(0.8f * p.scanned / p.total.coerceAtLeast(1))
                if (p.done) devices = p.devices
            }
            val others = devices.filter { !it.isSelf }
            val sem = Semaphore(16)
            var done = 0
            val found = others.map { d ->
                async {
                    sem.withPermit {
                        val open = lan.services(d.ip, HiddenCam.cameraPorts).map { it.first }.toSet() + d.openPorts
                        done++
                        lanState = LanState.Scanning(0.8f + 0.2f * done / others.size.coerceAtLeast(1))
                        HiddenCam.assessLan(d, open)
                    }
                }
            }.awaitAll().filterNotNull()
            lanState = LanState.Done(conn.ssid, HiddenCam.sort(found), others.size)
        }
    }
}

/**
 * Hidden cameras around: Wi-Fi networks and Bluetooth devices from camera makers or with camera names, the camera
 * ports on the network the phone is connected to, and a warm / cold signal to walk to the one picked.
 */
@Composable
fun HiddenCamTool(vm: MainViewModel) {
    val context = LocalContext.current
    val c = vm.tools.hiddenCam
    val wifi by vm.wifiScan.collectAsStateWithLifecycle()
    val ble by vm.ble.collectAsStateWithLifecycle()
    val conn = vm.wifi.collectAsStateWithLifecycle().value.connection
    val oui = remember { Wardrive.oui(context) }
    // Scans as often as Android allows, and Bluetooth at full speed, to follow a signal while walking around.
    LaunchedEffect(Unit) { while (true) { vm.startWifiScan(); delay(5_000) } }
    DisposableEffect(Unit) {
        vm.setBleLowLatency(true)
        onDispose { vm.setBleLowLatency(false) }
    }
    val hidden = S(R.string.hidden_network)
    val air = remember(wifi, ble) {
        val w = wifi.orEmpty().mapNotNull { ap ->
            val name = if (ap.ssid == hidden) "" else ap.ssid
            HiddenCam.assess(CamSource.Wifi, ap.bssid, name, oui.vendor(ap.bssid), ap.rssi)
        }
        val b = ble.mapNotNull { d -> HiddenCam.assess(CamSource.Ble, d.address, d.name.orEmpty(), d.maker ?: oui.vendor(d.address), d.rssi) }
        HiddenCam.sort(w + b)
    }
    var picked by remember { mutableStateOf<String?>(null) }
    ToolBack(picked != null) { picked = null }
    HiddenCamScreen(air, c.lanState, conn, picked, onPick = { picked = if (picked == it) null else it }, onScanLan = { conn?.let(c::scanLan) })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HiddenCamScreen(
    air: List<CamSuspect>,
    lan: HiddenCamController.LanState,
    conn: WifiConnection?,
    picked: String?,
    onPick: (String) -> Unit,
    onScanLan: () -> Unit,
) {
    val net = AntTheme.net
    val likely = air.count { it.likely } + ((lan as? HiddenCamController.LanState.Done)?.suspects?.count { it.likely } ?: 0)
    val possible = air.count { !it.likely } + ((lan as? HiddenCamController.LanState.Done)?.suspects?.count { !it.likely } ?: 0)
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$likely", style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(P(R.plurals.p_cam_likely, likely), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text(P(R.plurals.p_cam_possible, possible, possible), style = rf(14, 20))
            Row(Modifier.padding(top = 12.dp)) {
                if (likely > 0) HeroChip(S(R.string.cam_check_room), net.poor, blink = true)
                else HeroChip(S(R.string.cam_listening), net.good, blink = true)
            }
        }
        val sel = air.firstOrNull { it.key == picked }
        if (sel != null) Locator(sel)
        SectionTitle(S(R.string.cam_around))
        if (air.isEmpty()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainerLow).padding(16.dp)) {
                Text(S(R.string.cam_none_around), style = rf(15, 20, 600))
                Text(S(R.string.cam_none_around_text), Modifier.padding(top = 4.dp), style = rf(13, 18), color = cs.onSurfaceVariant)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                air.forEachIndexed { i, s -> SuspectRow(s, groupShape(i, air.size), s.key == picked) { onPick(s.key) } }
            }
        }
        SectionTitle(S(R.string.cam_on_network))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainerLow).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                conn?.ipv4 == null -> Text(S(R.string.cam_not_connected), style = rf(13, 18), color = cs.onSurfaceVariant)
                lan is HiddenCamController.LanState.Scanning -> {
                    Text(S(R.string.cam_scanning_network, conn.ssid ?: "Wi-Fi"), style = rf(14, 20, 500))
                    ProgressBar(lan.fraction)
                }
                else -> {
                    Text(S(R.string.cam_network_help, conn.ssid ?: "Wi-Fi"), style = rf(13, 18), color = cs.onSurfaceVariant)
                    if (lan is HiddenCamController.LanState.Done) {
                        Text(
                            if (lan.suspects.isEmpty()) P(R.plurals.p_cam_network_clean, lan.hosts, lan.hosts)
                            else P(R.plurals.p_cam_network_found, lan.suspects.size, lan.suspects.size),
                            style = rf(15, 20, 600), color = if (lan.suspects.any { it.likely }) net.poor else cs.onSurface,
                        )
                    }
                    PillButton(
                        if (lan is HiddenCamController.LanState.Done) S(R.string.cam_scan_again) else S(R.string.cam_scan_network),
                        onScanLan, icon = Sym.Search, height = 44.dp,
                    )
                }
            }
        }
        (lan as? HiddenCamController.LanState.Done)?.suspects?.takeIf { it.isNotEmpty() }?.let { list ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                list.forEachIndexed { i, s -> SuspectRow(s, groupShape(i, list.size), false) {} }
            }
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainerLow).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Symbol(Sym.Info, size = 20.dp, tint = AntTheme.accent.accent)
                Text(S(R.string.cam_tips_title), style = rf(15, 20, 600))
            }
            Text(S(R.string.cam_tips_text), style = rf(13, 18), color = cs.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuspectRow(s: CamSuspect, shape: androidx.compose.ui.graphics.Shape, selected: Boolean, onClick: () -> Unit) {
    val net = AntTheme.net
    val color = if (s.likely) net.poor else net.fair
    val icon = when (s.source) {
        CamSource.Wifi -> Sym.Wifi
        CamSource.Ble -> Sym.Bluetooth
        CamSource.Lan -> Sym.Lan
    }
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (selected) AntTheme.accent.container else cs.surfaceContainerLow)
            .clickable(enabled = s.source != CamSource.Lan, onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            Modifier.width(52.dp).clip(RoundedCornerShape(16.dp)).background(color.copy(alpha = 0.16f)).padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Symbol(Sym.Videocam, size = 22.dp, filled = true, tint = color)
            Symbol(icon, size = 14.dp, tint = color)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.name.ifEmpty { S(R.string.hidden_network) }, Modifier.weight(1f), style = rf(15, 20, 600),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (s.rssi != null) Text("${s.rssi} dBm", Modifier.padding(start = 8.dp), style = gs(13, 18, 700, tnum = true), color = levelColor(s.rssi))
            }
            Text(s.address.lowercase(Locale.ROOT), style = mono(12, 17), color = cs.onSurfaceVariant, maxLines = 1)
            if (s.vendor != null) Text(s.vendor, style = rf(12, 16, 500), color = AntTheme.accent.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FlowRow(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                s.reasons.sortedBy { it.ordinal }.forEach { r -> ReasonChip(reasonLabel(r, s.ports), if (r == CamReason.Near) net.poor else cs.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun ReasonChip(text: String, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(cs.surfaceContainerHighest).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(text, style = rf(11, 15, 600), color = color, maxLines = 1)
    }
}

private fun reasonLabel(r: CamReason, ports: List<Int>) = when (r) {
    CamReason.Maker -> S(R.string.cam_reason_maker)
    CamReason.Name -> S(R.string.cam_reason_name)
    CamReason.Chip -> S(R.string.cam_reason_chip)
    CamReason.Ports -> S(R.string.cam_reason_ports, ports.joinToString(", "))
    CamReason.Near -> S(R.string.cam_reason_near)
}

@Composable
private fun levelColor(rssi: Int): Color {
    val net = AntTheme.net
    return when {
        rssi >= HiddenCam.NEAR_DBM -> net.poor
        rssi >= -70 -> net.fair
        else -> net.good
    }
}

/** Warm / cold: the picked device's signal, live, as a bar that fills when the phone gets closer. */
@Composable
private fun Locator(s: CamSuspect) {
    val acc = AntTheme.accent
    val rssi = s.rssi ?: -100
    val fill by animateFloatAsState(((rssi + 95) / 60f).coerceIn(0f, 1f), label = "warm")
    val (word, color) = when {
        rssi >= -45 -> S(R.string.cam_burning) to AntTheme.net.poor
        rssi >= HiddenCam.NEAR_DBM -> S(R.string.cam_hot) to AntTheme.net.poor
        rssi >= -70 -> S(R.string.cam_warm) to AntTheme.net.fair
        else -> S(R.string.cam_cold) to AntTheme.net.wifi.accent
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(acc.container).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(S(R.string.cam_locating, s.name.ifEmpty { s.address }), style = rf(14, 20, 600), color = acc.onContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("$rssi", style = gs(48, 52, 500, -1f, tnum = true), color = acc.onContainer)
            Text("dBm", Modifier.padding(bottom = 6.dp), style = rf(16, 22, 500), color = acc.onContainer)
            Box(Modifier.weight(1f))
            Text(word, Modifier.padding(bottom = 6.dp), style = gs(22, 28, 600), color = color)
        }
        LevelBar(fill, color, height = 12.dp)
        Text(S(R.string.cam_locating_help), style = rf(13, 18), color = acc.onContainer.copy(alpha = 0.8f))
    }
}
