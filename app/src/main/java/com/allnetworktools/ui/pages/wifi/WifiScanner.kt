package com.allnetworktools.ui.pages.wifi

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.Page
import com.allnetworktools.data.PermGroup
import com.allnetworktools.data.WifiAp
import com.allnetworktools.data.WifiBand
import com.allnetworktools.data.channelOf
import com.allnetworktools.model.Tool
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.EmptyStateCard
import com.allnetworktools.ui.components.ErrorBanner
import com.allnetworktools.ui.components.LeadingIcon
import com.allnetworktools.ui.components.PanelAction
import com.allnetworktools.ui.components.PulseRing
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.SegmentedRow
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.TextAction
import com.allnetworktools.ui.components.groupShape
import com.allnetworktools.ui.components.rise
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.pages.TopBarAction
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Motion
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.util.fmt
import com.allnetworktools.util.plural
import kotlinx.coroutines.delay
import kotlin.math.abs
import com.allnetworktools.R
import com.allnetworktools.util.S
import com.allnetworktools.util.P

private const val SCAN_PERIOD_MS = 30_000L

/** Horizontal slot layout of the channel chart for a band. */
private class BandAxis(val slots: Float, val axis: List<Int>, val slotOf: (Float) -> Float)

private fun ch5Index(ch: Float): Float = when {
    ch <= 64f -> (ch - 36f) / 4f
    ch <= 144f -> 8f + (ch - 100f) / 4f
    else -> 20f + (ch - 149f) / 4f
}

private fun axisFor(band: WifiBand) = when (band) {
    WifiBand.B24 -> BandAxis(15f, listOf(1, 3, 5, 7, 9, 11, 13)) { it + 1f }
    WifiBand.B5 -> BandAxis(26f, listOf(36, 52, 100, 116, 132, 149, 165)) { ch5Index(it) + 1f }
    WifiBand.B6 -> BandAxis(60f, listOf(1, 49, 97, 145, 193)) { (it - 1f) / 4f + 1f }
}

@Composable
fun WifiScanner(vm: MainViewModel) {
    val scan by vm.wifiScan.collectAsStateWithLifecycle()
    val conn = vm.wifi.collectAsStateWithLifecycle().value.connection
    val throttledUntil by vm.wifiThrottledUntil.collectAsStateWithLifecycle()
    val perms by vm.permissions.collectAsStateWithLifecycle()
    val actions = LocalActions.current
    val haptics = AntTheme.haptics
    val roles = AntTheme.net.wifi
    var paused by rememberSaveable { mutableStateOf(false) }
    var band by rememberSaveable { mutableStateOf(conn?.band ?: WifiBand.B24) }
    var resultsAt by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(scan) { resultsAt = SystemClock.elapsedRealtime() }
    val now = rememberNow()
    val throttled = throttledUntil > now

    LaunchedEffect(paused) {
        while (!paused) {
            val ok = vm.startWifiScan()
            val wait = if (ok) SCAN_PERIOD_MS else (vm.wifiThrottledUntil.value - SystemClock.elapsedRealtime()).coerceAtLeast(5_000)
            delay(wait)
        }
    }
    TopBarAction(
        when {
            throttled -> Sym.Schedule
            paused -> Sym.PlayArrow
            else -> Sym.Pause
        },
    ) {
        if (throttled) actions.toast(S(R.string.new_scan_possible_in_s, (throttledUntil - now) / 1000))
        else {
            haptics.segment(); paused = !paused
        }
    }

    val all = scan.orEmpty()
    val list = all.filter { it.band == band }
    PageColumn {
        if (throttled) {
            ErrorBanner(
                Sym.Error, S(R.string.scan_limited_by_android),
                S(R.string.n_4_scans_max_every_2_minutes, (now - resultsAt) / 1000),
                S(R.string.new_scan_possible_in_s, ((throttledUntil - now) / 1000).coerceAtLeast(0)),
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(roles.accent))
                if (!paused && !throttled) PulseRing(roles.accent, 12.dp, filled = true)
            }
            Text(
                if (paused || throttled) S(R.string.scan_paused_2) else S(R.string.scanning_3),
                Modifier.padding(start = 10.dp).weight(1f), style = rf(14, 20, 500),
            )
            Text("${list.size} ${P(R.plurals.p_networks, list.size)}", style = rf(12, 16), color = cs.onSurfaceVariant)
        }
        SegmentedRow(
            WifiBand.entries.map { it to "${it.label} GHz" }, band, { band = it },
            Modifier.fillMaxWidth(), selectedColor = roles.accent, onSelectedColor = roles.onAccent,
        )
        if (!perms.location) {
            EmptyStateCard(
                Sym.LocationOff, S(R.string.location_needed),
                S(R.string.android_only_gives_wi_fi_scan),
                PanelAction(S(R.string.allow), Sym.MyLocation) { actions.request(PermGroup.Location) },
            )
            return@PageColumn
        }
        SectionCard(padding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(S(R.string.channel_use), Modifier.weight(1f), style = rf(14, 20, 600))
            }
            ChannelChart(list, band, conn?.bssid)
        }
        if (scan != null && list.isEmpty()) {
            EmptyStateCard(Sym.WifiFind, S(R.string.no_network_on_ghz, band.label), S(R.string.the_scan_goes_on_try_another))
        }
        key(band) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                list.forEachIndexed { i, ap -> ScanItem(ap, ap.bssid.equals(conn?.bssid, true), i, list.size) }
            }
        }
    }
}

@Composable
private fun ChannelChart(list: List<WifiAp>, band: WifiBand, ownBssid: String?) {
    val net = AntTheme.net
    val hue = listOf(net.wifi.accent, net.bt.accent, net.cell.accent, net.gnss.accent, cs.primary, cs.error, net.galileo, net.beidou)
    val outline = cs.outline
    val axis = axisFor(band)
    val grow = remember { Animatable(0f) }
    LaunchedEffect(band) {
        grow.snapTo(0f)
        grow.animateTo(1f, tween(800, easing = Motion.Emphasized))
    }
    BoxWithConstraints(Modifier.fillMaxWidth().height(170.dp)) {
        val widthPx = constraints.maxWidth.toFloat()
        val density = androidx.compose.ui.platform.LocalDensity.current
        fun xOf(slot: Float) = 8f + (widthPx - 12f) * slot / axis.slots
        fun yOfDbm(d: Int) = 140f - ((d.coerceIn(-95, -30) + 95) / 65f) * 130f
        data class Hump(val ap: WifiAp, val cx: Float, val hw: Float, val py: Float, val color: Color, val own: Boolean)
        val humps = list.mapIndexed { i, ap ->
            val centerCh = channelOf(ap.centerFrequency).toFloat().takeIf { it > 0 } ?: ap.channel.toFloat()
            val cx = xOf(axis.slotOf(centerCh))
            val unit = (widthPx - 12f) / axis.slots
            val hw = if (band == WifiBand.B24) unit * 2.2f else unit * (ap.widthMhz / 40f + 0.1f)
            val own = ap.bssid.equals(ownBssid, true)
            Hump(ap, cx, hw, yOfDbm(ap.rssi), if (own) net.wifi.accent else hue[(i + 1) % hue.size], own)
        }
        val scaleY = with(density) { 1.dp.toPx() }
        Canvas(Modifier.matchParentSize()) {
            drawLine(outline, Offset(8f, 140 * scaleY), Offset(size.width - 4f, 140 * scaleY), 1.dp.toPx())
            val g = grow.value
            humps.forEach { h ->
                val base = 140f * scaleY
                val py = base + (h.py * scaleY - base) * g
                val cy = 2 * py - base
                val path = Path().apply {
                    moveTo(h.cx - h.hw, base)
                    quadraticTo(h.cx, cy, h.cx + h.hw, base)
                }
                drawPath(path, h.color.copy(alpha = 0.16f))
                drawPath(path, h.color, style = Stroke((if (h.own) 3f else 1.5f).dp.toPx()))
            }
        }
        val minGap = with(density) { 70.dp.toPx() }
        val labelled = humps.fold(emptyList<Hump>()) { acc, h ->
            if (acc.size < 4 && acc.none { abs(it.cx - h.cx) < minGap && abs(it.py - h.py) < 18f }) acc + h else acc
        }
        labelled.forEach { h ->
            Text(
                h.ap.ssid,
                Modifier.width(120.dp).offset { androidx.compose.ui.unit.IntOffset((h.cx - 60.dp.toPx()).toInt(), ((h.py - 20f) * grow.value + 140f * (1 - grow.value)).dp.roundToPx()) },
                style = rf(10, 14, 700), color = if (h.own) net.wifi.accent else cs.onSurfaceVariant,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        axis.axis.forEach { ch ->
            Text(
                ch.toString(),
                Modifier.width(30.dp).offset(x = with(density) { xOf(axis.slotOf(ch.toFloat())).toDp() } - 15.dp, y = 146.dp),
                style = rf(10, 14), color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ScanItem(ap: WifiAp, own: Boolean, index: Int, count: Int) {
    val roles = AntTheme.net.wifi
    val fg = if (own) roles.onContainer else cs.onSurface
    Row(
        Modifier.rise(index).fillMaxWidth().clip(groupShape(index, count))
            .background(if (own) roles.container else cs.surfaceContainerLow)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val icon = when {
            ap.rssi > -60 -> Sym.Wifi
            ap.rssi > -72 -> Sym.Wifi2Bar
            else -> Sym.Wifi1Bar
        }
        LeadingIcon(icon, roles.container, roles.onContainer)
        Column(Modifier.weight(1f)) {
            Text(ap.ssid + if (own) S(R.string.connected_4) else "", style = rf(15, 20, 600), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(S(R.string.channel_mhz, ap.channel, ap.widthMhz), style = rf(12, 16), color = if (own) fg else cs.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(fmt(ap.rssi), style = gs(20, 24, 500, tnum = true), color = fg)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Symbol(if (ap.security == "Ouvert") Sym.LockOpen else Sym.Lock, size = 13.dp, filled = true, tint = if (own) fg else cs.onSurfaceVariant)
                Text(ap.security, style = rf(11, 14), color = if (own) fg else cs.onSurfaceVariant)
            }
        }
    }
}
