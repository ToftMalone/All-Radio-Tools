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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.allnetworktools.data.CheckLevel
import com.allnetworktools.data.GatewaySnapshot
import com.allnetworktools.data.LinkReader
import com.allnetworktools.data.LinkSnapshot
import com.allnetworktools.data.MitmEvent
import com.allnetworktools.data.MitmWatch
import com.allnetworktools.data.NetProbes
import com.allnetworktools.data.NetworkAudit
import com.allnetworktools.data.SecCheck
import com.allnetworktools.data.WifiAp
import com.allnetworktools.data.WifiConnection
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.pages.TopBarAction
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.IndeterminateBar
import com.allnetworktools.ui.tools.ToolEmpty
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.allnetworktools.R
import com.allnetworktools.util.S

@Composable
internal fun levelColor(l: CheckLevel): Color = when (l) {
    CheckLevel.Bad -> cs.error
    CheckLevel.Warn -> AntTheme.net.fair
    CheckLevel.Info -> AntTheme.accent.accent
    CheckLevel.Good -> AntTheme.net.good
}

@Composable
internal fun CheckList(title: String, checks: List<SecCheck>) {
    SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
        Text(title, Modifier.padding(bottom = 4.dp), style = rf(14, 20, 600), color = AntTheme.accent.accent)
        checks.sortedBy { it.level.ordinal }.forEach { c ->
            Hairline()
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val col = levelColor(c.level)
                Box(Modifier.padding(top = 2.dp).size(22.dp).clip(CircleShape).background(col.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Symbol(
                        when (c.level) {
                            CheckLevel.Bad -> Sym.Error
                            CheckLevel.Warn -> Sym.Warning
                            CheckLevel.Info -> Sym.Info
                            CheckLevel.Good -> Sym.Check
                        },
                        size = 16.dp, filled = true, tint = col,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(c.title, style = rf(15, 20, 600))
                    Text(c.detail, Modifier.padding(top = 2.dp), style = rf(13, 18), color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    SectionCard {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
            Text(text, style = rf(13, 18), color = cs.onSurfaceVariant)
        }
    }
}

private fun summary(checks: List<SecCheck>): Pair<Int, Int> = checks.count { it.level == CheckLevel.Bad } to checks.count { it.level == CheckLevel.Warn }

// ---- 1. Network audit -----------------------------------------------------------------------------

class AuditController(private val probes: NetProbes, private val links: LinkReader?, private val scope: CoroutineScope) {
    var checks by mutableStateOf<List<SecCheck>?>(null)
        private set
    var running by mutableStateOf(false)
        private set
    var auditedSsid by mutableStateOf<String?>(null)
        private set

    fun run(conn: WifiConnection, scan: List<WifiAp>?) {
        if (running) return
        running = true
        scope.launch {
            val link = links?.read()
            val ports = conn.gateway?.let { gw -> listOf(23, 80, 443).filter { probes.tcpOpen(gw, it) }.toSet() }
            val caps = scan?.firstOrNull { it.bssid.equals(conn.bssid, true) }?.caps
            checks = NetworkAudit.checks(conn, caps, link, ports)
            auditedSsid = conn.ssid
            running = false
        }
    }

    internal fun setForTest(c: List<SecCheck>, ssid: String) {
        checks = c
        auditedSsid = ssid
    }
}

@Composable
fun AuditTool(c: AuditController, conn: WifiConnection?, scan: List<WifiAp>?) {
    LaunchedEffect(conn?.bssid) { if (conn != null && c.checks == null) c.run(conn, scan) }
    if (conn != null) TopBarAction(Sym.Refresh) { c.run(conn, scan) }
    PageColumn {
        if (conn == null) {
            ToolEmpty(Sym.WifiOff, S(R.string.no_wi_fi), S(R.string.connect_to_a_wi_fi_network_3), null)
            return@PageColumn
        }
        val checks = c.checks
        HeroCard {
            if (checks == null) {
                Text(S(R.string.audit_of, conn.ssid ?: S(R.string.your_network_3)), style = gs(24, 30, 500))
                Text(S(R.string.analysing_the_encryption_the_access_point), Modifier.padding(top = 4.dp), style = rf(14, 20))
                IndeterminateBar(Modifier.padding(top = 16.dp))
            } else {
                val score = NetworkAudit.score(checks)
                val (bad, warn) = summary(checks)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("$score", style = gs(64, 68, 500, tnum = true))
                    Column(Modifier.padding(bottom = 10.dp)) {
                        Text("/ 100", style = rf(18, 24, 600, tnum = true))
                        Text(c.auditedSsid ?: "", style = rf(14, 20))
                    }
                }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeroChip(if (bad == 0) S(R.string.no_risk) else "$bad ${if (bad > 1) "risques" else "risque"}", if (bad == 0) AntTheme.net.good else cs.error)
                    if (warn > 0) HeroChip(S(R.string.to_improve_2, warn), AntTheme.net.fair)
                    if (c.running) HeroChip(S(R.string.analysing_again), AntTheme.net.fair, blink = true)
                }
            }
        }
        if (checks != null) CheckList(S(R.string.points_checked), checks)
        Note(
            S(R.string.encryption_comes_from_android_wps_pmf) +
                S(R.string.to_fix_them_open_your_router, conn.gateway ?: "192.168.1.1"),
        )
    }
}

// ---- 2. Man in the middle -----------------------------------------------------------------------

class MitmController(private val probes: NetProbes, private val links: LinkReader?, private val scope: CoroutineScope) {
    var current by mutableStateOf<GatewaySnapshot?>(null)
        private set
    val events = mutableStateListOf<MitmEvent>()
    var rounds by mutableStateOf(0)
        private set
    var startedAt by mutableStateOf(0L)
        private set
    private var job: Job? = null

    private var frozen = false

    fun start(bssid: () -> String?) {
        if (frozen || job?.isActive == true) return
        if (startedAt == 0L) startedAt = System.currentTimeMillis()
        job = scope.launch {
            while (isActive) {
                val l: LinkSnapshot? = links?.read()
                val s = GatewaySnapshot(System.currentTimeMillis(), l?.gateway, l?.dhcpServer, l?.dns.orEmpty(), l?.ipv6Routers.orEmpty(), probes.firstHop(), bssid())
                current?.let { prev -> events.addAll(0, MitmWatch.changes(prev, s)) }
                current = s
                rounds++
                delay(15_000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun reset() {
        events.clear()
        rounds = 0
        startedAt = System.currentTimeMillis()
    }

    internal fun setForTest(s: GatewaySnapshot, e: List<MitmEvent>, r: Int, start: Long) {
        frozen = true
        current = s
        events.clear(); events.addAll(e)
        rounds = r
        startedAt = start
    }
}

private val hms = DateTimeFormatter.ofPattern("HH:mm:ss")

@Composable
fun MitmTool(c: MitmController, conn: WifiConnection?) {
    val bssid by androidx.compose.runtime.rememberUpdatedState(conn?.bssid)
    DisposableEffect(Unit) {
        c.start { bssid }
        onDispose { c.stop() }
    }
    TopBarAction(Sym.RestartAlt) { c.reset() }
    PageColumn {
        val s = c.current
        val checks = s?.let(MitmWatch::checks).orEmpty()
        val bad = checks.count { it.level == CheckLevel.Bad } + c.events.count { it.level == CheckLevel.Bad }
        HeroCard {
            Text(
                when {
                    s == null -> S(R.string.analysing_the_network_path)
                    bad > 0 -> S(R.string.interception_possible)
                    else -> S(R.string.no_intermediary_detected)
                },
                style = gs(28, 34, 500),
            )
            Text(
                (conn?.ssid?.let { S(R.string.network_2, it) } ?: "") + S(R.string.check_every_15_s, c.rounds, if (c.rounds > 1) S(R.string.checks) else S(R.string.check)),
                Modifier.padding(top = 4.dp), style = rf(14, 20),
            )
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroChip(S(R.string.monitoring), AntTheme.net.good, blink = true)
                if (c.events.isNotEmpty()) HeroChip("${c.events.size} ${if (c.events.size > 1) "changements" else "changement"}", AntTheme.net.fair)
            }
            if (s == null) IndeterminateBar(Modifier.padding(top = 16.dp))
        }
        if (s != null) {
            CheckList(S(R.string.current_state), checks)
            SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
                Text(S(R.string.observed_path), Modifier.padding(bottom = 4.dp), style = rf(14, 20, 600), color = AntTheme.accent.accent)
                listOf(
                    S(R.string.gateway) to (s.gateway ?: "—"),
                    S(R.string.first_router) to (s.firstHop ?: S(R.string.no_answer_2)),
                    S(R.string.dhcp_server) to (s.dhcpServer ?: "—"),
                    "DNS" to s.dns.joinToString().ifEmpty { "—" },
                    S(R.string.ipv6_routers) to s.ipv6Routers.joinToString().ifEmpty { S(R.string.none) },
                ).forEach { (k, v) -> com.allnetworktools.ui.components.InfoRow(k, v) }
            }
        }
        SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
            Text(S(R.string.changes_during_monitoring), Modifier.padding(bottom = 4.dp), style = rf(14, 20, 600), color = AntTheme.accent.accent)
            if (c.events.isEmpty()) Text(S(R.string.no_change_since_monitoring_began), Modifier.padding(vertical = 10.dp), style = rf(13, 18), color = cs.onSurfaceVariant)
            c.events.take(30).forEach { e ->
                Hairline()
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(levelColor(e.level)))
                    Text(e.text, Modifier.weight(1f), style = rf(13, 18))
                    Text(Instant.ofEpochMilli(e.timeMs).atZone(ZoneId.systemDefault()).format(hms), style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant)
                }
            }
        }
        Note(
            S(R.string.an_attacker_spoofing_the_router_arp) +
                S(R.string.the_tool_also_watches_the_dhcp) +
                S(R.string.a_spoof_that_does_not_relay),
        )
    }
}
