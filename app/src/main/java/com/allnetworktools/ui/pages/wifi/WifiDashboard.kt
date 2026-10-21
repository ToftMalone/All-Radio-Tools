package com.allnetworktools.ui.pages.wifi

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.data.PermGroup
import com.allnetworktools.data.shortSecurity
import com.allnetworktools.data.standardLabel
import com.allnetworktools.data.unii
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.BlinkDot
import com.allnetworktools.ui.components.CardHeader
import com.allnetworktools.ui.components.IconChip
import com.allnetworktools.ui.components.InfoList
import com.allnetworktools.ui.components.InfoRow
import com.allnetworktools.ui.components.LiveChart
import com.allnetworktools.ui.components.MetricTile
import com.allnetworktools.ui.components.PanelAction
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.SignalGauge
import com.allnetworktools.ui.components.StatePanel
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.TextAction
import com.allnetworktools.ui.components.TileGrid
import com.allnetworktools.ui.components.fadeUp
import com.allnetworktools.data.bandOf
import com.allnetworktools.data.channelOf
import com.allnetworktools.util.plural
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.util.durationFr
import com.allnetworktools.util.fmt
import com.allnetworktools.util.signalValue
import com.allnetworktools.util.wifiQualityLabel
import kotlinx.coroutines.delay
import com.allnetworktools.R
import com.allnetworktools.util.S
import com.allnetworktools.util.P

fun prefixToMask(prefix: Int): String {
    val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
    return listOf(24, 16, 8, 0).joinToString(".") { ((mask shr it) and 0xFF).toString() }
}

/** Seconds-resolution clock for relative times. */
@Composable
fun rememberNow(periodMs: Long = 1000): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(periodMs) {
        while (true) {
            now = SystemClock.elapsedRealtime(); delay(periodMs)
        }
    }
    return now
}

fun rateLabel(seconds: Float): String {
    val hz = 1f / seconds
    return "${fmt(60 * seconds)} s · ${if (hz >= 1) fmt(hz, if (hz % 1f == 0f) 0 else 1) else fmt(hz, 1)} Hz"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WifiDashboard(vm: MainViewModel) {
    val ui by vm.wifi.collectAsStateWithLifecycle()
    val scan by vm.wifiScan.collectAsStateWithLifecycle()
    val perms by vm.permissions.collectAsStateWithLifecycle()
    val actions = LocalActions.current
    val roles = AntTheme.net.wifi
    val settings = AntTheme.settings
    val c = ui.connection
    if (c == null) {
        Box(Modifier.fillMaxSize().padding(bottom = 96.dp), contentAlignment = Alignment.Center) {
            StatePanel(
                Sym.WifiFind, S(R.string.not_connected), S(R.string.wi_fi_is_on_but_no),
                PanelAction(S(R.string.choose_a_network), Sym.Wifi) { actions.openWifiPanel() },
            )
        }
        return
    }
    val ap = scan?.firstOrNull { it.bssid.equals(c.bssid, true) }
    val now = rememberNow(30_000)
    val std = standardLabel(c.standard)
    PageColumn {
        CompositionLocalProvider(LocalContentColor provides roles.onContainer) {
            Column(Modifier.fadeUp().fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(roles.container).padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(c.ssid ?: S(R.string.hidden_ssid), style = gs(28, 34, 500), maxLines = 1)
                        val since = c.connectedAtElapsed?.let { S(R.string.connected_for, durationFr(now - it)) } ?: S(R.string.connected)
                        Text(since, Modifier.graphicsLayer { alpha = 0.85f }, style = rf(14, 20))
                    }
                    Symbol(Sym.Wifi, size = 28.dp, filled = true, tint = roles.accent)
                }
                if (c.ssid == null && !perms.location) {
                    TextAction(S(R.string.allow_location_to_show_the_ssid), { actions.request(PermGroup.Location) }, color = roles.accent)
                }
                FlowRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconChip(Sym.Lock, shortSecurity(c.security), roles.accent)
                    IconChip(Sym.WifiChannel, "${c.band.label} GHz · ch ${c.channel}", roles.accent)
                    std?.let { IconChip(Sym.Router, it.first, roles.accent) }
                    ap?.let { IconChip(Sym.SwapCalls, "${it.widthMhz} MHz", roles.accent) }
                }
                val v = signalValue(c.rssi, settings, -100f, -30f)
                SignalGauge(
                    value = c.rssi.toFloat(), color = roles.accent, track = cs.surface,
                    label = "${v.unit} · ${wifiQualityLabel(c.rssi)}", display = v.text,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 18.dp),
                )
            }
        }
        SectionCard {
            CardHeader(S(R.string.live_signal)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BlinkDot(cs.error, 8.dp, 1200)
                    Text(rateLabel(settings.refreshSeconds), style = rf(12, 16), color = cs.onSurfaceVariant)
                }
            }
            LiveChart(ui.history, -90f, -30f, 20f, roles.accent, Modifier.padding(top = 10.dp), capacity = 60, morphMs = settings.refreshMillis.toInt())
        }
        TileGrid(
            listOf(
                Triple(Sym.Upload, S(R.string.tx_link), c.linkTx?.let { fmt(it) } ?: "—") to "Mb/s",
                Triple(Sym.Download, S(R.string.rx_link), c.linkRx?.let { fmt(it) } ?: "—") to "Mb/s",
                Triple(Sym.GraphicEq, S(R.string.frequency), fmt(c.frequency)) to "MHz",
                Triple(Sym.Tag, S(R.string.channel_3), c.channel.toString()) to (ap?.let { "· ${it.widthMhz} MHz" } ?: ""),
            ),
        ) { (t, unit), mod -> MetricTile(t.second, t.third, unit, t.first, mod) }
        InfoList(S(R.string.access_point), roles.accent) {
            InfoRow("BSSID", c.bssid ?: S(R.string.blocked))
            InfoRow(S(R.string.security_2), c.security ?: "—")
            std?.let { InfoRow("Standard", "${it.first} · ${it.second}") }
            InfoRow(S(R.string.band), listOfNotNull("${c.band.label} GHz", unii(c.frequency)).joinToString(" · "))
            ap?.let { InfoRow(S(R.string.width), "${it.widthMhz} MHz") }
        }
        val roams by vm.roams.collectAsStateWithLifecycle()
        if (roams.isNotEmpty()) RoamingCard(roams, scan)
        InfoList(S(R.string.ip_network), roles.accent) {
            InfoRow("IPv4", c.ipv4 ?: "—", c.ipv4?.let { { actions.copy("IPv4", it) } })
            c.prefix?.let { InfoRow(S(R.string.mask), "${prefixToMask(it)} (/$it)") }
            InfoRow(S(R.string.gateway), c.gateway ?: "—", c.gateway?.let { { actions.copy(S(R.string.gateway), it) } })
            InfoRow("DNS", c.dns.take(2).joinToString(" · ").ifEmpty { "—" })
            c.ipv6?.let { InfoRow("IPv6", it) { actions.copy("IPv6", it) } }
        }
    }
}

private val roamTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())

/** Short form of a BSSID: the last three bytes are what tells two APs of one network apart. */
private fun shortBssid(b: String) = b.uppercase().takeLast(8)

@Composable
private fun RoamingCard(roams: List<com.allnetworktools.RoamEvent>, scan: List<com.allnetworktools.data.WifiAp>?) {
    val roles = AntTheme.net.wifi
    fun freq(f: Int) = "${bandOf(f).label} GHz · ch ${channelOf(f)}"
    InfoList(
        S(R.string.roaming_ap, roams.size, P(R.plurals.p_changes, roams.size)), roles.accent,
        trailing = { Text(roams.first().ssid ?: "", style = rf(12, 16), color = cs.onSurfaceVariant) },
    ) {
        roams.forEach { r ->
            com.allnetworktools.ui.components.Hairline()
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Symbol(Sym.SwapHoriz, size = 20.dp, tint = roles.accent)
                    Text("${shortBssid(r.fromBssid)} → ${shortBssid(r.toBssid)}", Modifier.weight(1f), style = rf(14, 20, 600, tnum = true))
                    Text(roamTime.format(java.util.Date(r.atMs)), style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant)
                }
                val same = bandOf(r.fromFreq) == bandOf(r.toFreq)
                Text(
                    (if (same) freq(r.toFreq) else S(R.string.band_change, freq(r.fromFreq), freq(r.toFreq))) +
                        " · ${fmt(r.rssiBefore)} → ${fmt(r.rssiAfter)} dBm",
                    style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant,
                )
                r.stayedMs?.let { Text(S(R.string.stayed_on_the_previous_ap, durationFr(it)), style = rf(12, 16), color = cs.onSurfaceVariant) }
            }
        }
    }
}
