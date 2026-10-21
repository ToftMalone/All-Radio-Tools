package com.allnetworktools.ui.pages.wifi

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.data.EvilTwin
import com.allnetworktools.data.SsidReport
import com.allnetworktools.data.TwinRisk
import com.allnetworktools.data.WifiAp
import com.allnetworktools.data.WifiConnection
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.TechChip
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.pages.TopBarAction
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.ToolError
import com.allnetworktools.ui.tools.mono
import com.allnetworktools.ui.tools.ToolEmpty
import com.allnetworktools.util.plural
import kotlinx.coroutines.flow.StateFlow
import com.allnetworktools.R
import com.allnetworktools.util.S
import com.allnetworktools.util.P

/** Remembers when each BSSID and SSID was first seen while the tool is used. */
class EvilTwinController {
    val firstSeen = mutableStateMapOf<String, Long>()
    val ssidFirstSeen = mutableStateMapOf<String, Long>()
    var lastScanMs by mutableStateOf(0L)
        private set

    fun feed(scan: List<WifiAp>, now: Long = System.currentTimeMillis()) {
        scan.forEach { ap ->
            if (ap.bssid !in firstSeen) firstSeen[ap.bssid] = now
            if (ap.ssid.isNotBlank() && ap.ssid !in ssidFirstSeen) ssidFirstSeen[ap.ssid] = now
        }
        lastScanMs = now
    }

    fun reset() {
        firstSeen.clear()
        ssidFirstSeen.clear()
    }
}

@Composable
private fun riskColor(r: TwinRisk): Color = when (r) {
    TwinRisk.High -> cs.error
    TwinRisk.Medium -> AntTheme.net.fair
    TwinRisk.Low -> AntTheme.accent.accent
    TwinRisk.None -> AntTheme.net.good
}

@Composable
fun EvilTwinTool(
    c: EvilTwinController,
    conn: WifiConnection?,
    results: StateFlow<List<WifiAp>?>,
    startScan: () -> Boolean,
    locationOk: Boolean,
    onFixLocation: () -> Unit,
) {
    val scan by results.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { startScan() }
    LaunchedEffect(scan) { scan?.let { c.feed(it) } }
    TopBarAction(Sym.Refresh) { startScan() }
    // Only the network the phone is connected to: the access points that broadcast its name.
    val ssid = conn?.ssid?.takeIf { it.isNotBlank() }
    val reports = if (ssid == null) emptyList() else EvilTwin.analyze(scan.orEmpty().filter { it.ssid == ssid }, c.firstSeen, c.ssidFirstSeen, System.currentTimeMillis())
    PageColumn {
        if (!locationOk) {
            ToolError(Sym.LocationOff, S(R.string.location_needed_2), S(R.string.android_requires_precise_location_with_location), S(R.string.allow), onFixLocation)
            return@PageColumn
        }
        if (ssid == null) {
            ToolEmpty(Sym.WifiOff, S(R.string.no_wi_fi), S(R.string.connect_to_a_wi_fi_network), null)
            return@PageColumn
        }
        val mine = reports.firstOrNull()
        val suspicious = mine?.flagged?.size ?: 0
        val connectedFlagged = conn.bssid != null && mine != null && conn.bssid.lowercase() in mine.flagged.map { it.lowercase() }
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(suspicious.toString(), style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(P(R.plurals.p_suspicious_access_points, suspicious), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            val aps = mine?.aps?.size ?: 0
            Text(
                "$aps ${P(R.plurals.p_access_points_broadcast, aps)} « $ssid »",
                style = rf(14, 20),
            )
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    scan == null -> HeroChip(S(R.string.scanning_2), AntTheme.net.fair, blink = true)
                    connectedFlagged -> HeroChip(S(R.string.your_access_point_is_suspicious), cs.error, blink = true)
                    mine != null -> HeroChip(S(R.string.your_network, mine.risk.label.lowercase()), riskColor(mine.risk))
                    else -> HeroChip(S(R.string.analysed_at_each_scan), AntTheme.net.good)
                }
            }
        }
        if (connectedFlagged) {
            SectionCard(color = cs.errorContainer) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Symbol(Sym.Warning, size = 24.dp, filled = true, tint = cs.onErrorContainer)
                    Text(
                        S(R.string.you_are_connected_to_flagged_below, conn!!.bssid?.uppercase()),
                        style = rf(13, 18), color = cs.onErrorContainer,
                    )
                }
            }
        }
        reports.forEach { r -> ReportCard(r, conn) }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(
                    S(R.string.a_fake_access_point_evil_twin) +
                        S(R.string.the_tool_compares_the_advertised_security) +
                        S(R.string.mesh_networks_and_the_routers_of),
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReportCard(r: SsidReport, conn: WifiConnection?) {
    SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(r.ssid, style = rf(16, 22, 600), maxLines = 1)
                Text("${r.aps.size} ${P(R.plurals.p_access_points, r.aps.size)}" + if (r.ssid == conn?.ssid) S(R.string.your_network_2) else "", style = rf(12, 16), color = cs.onSurfaceVariant)
            }
            TechChip(r.risk.label, riskColor(r.risk), if (r.risk == TwinRisk.High) cs.onError else Color.White)
        }
        r.findings.forEach { f ->
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.padding(top = 5.dp).size(10.dp).clip(CircleShape).background(riskColor(f.risk)))
                Column {
                    Text(f.title, style = rf(14, 20, 600))
                    Text(f.detail, style = rf(13, 18), color = cs.onSurfaceVariant)
                }
            }
        }
        Column(Modifier.padding(top = 8.dp)) {
            r.aps.forEach { ap ->
                val flagged = r.findings.filter { ap.bssid in it.bssids }.minByOrNull { it.risk.ordinal }
                val connected = conn?.bssid?.equals(ap.bssid, ignoreCase = true) == true
                Hairline()
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(flagged?.let { riskColor(it.risk) } ?: cs.outlineVariant))
                    Column(Modifier.weight(1f)) {
                        Text(ap.bssid.uppercase() + if (connected) S(R.string.connected_3) else "", style = mono(12.5f, 18, if (connected) 700 else 500))
                        Text(
                            listOfNotNull(
                                ap.security, "${ap.band.label} GHz · ch ${ap.channel}",
                                if (EvilTwin.locallyAdministered(ap.bssid)) S(R.string.local_address) else null,
                            ).joinToString(" · "),
                            style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 1,
                        )
                    }
                    Text("${ap.rssi} dBm", style = rf(13, 18, 600, tnum = true))
                }
            }
        }
    }
}
