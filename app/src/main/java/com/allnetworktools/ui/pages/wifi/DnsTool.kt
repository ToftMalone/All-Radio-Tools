package com.allnetworktools.ui.pages.wifi

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.allnetworktools.data.net.DnsAnswer
import com.allnetworktools.data.net.DnsClient
import com.allnetworktools.data.net.DnsServer
import com.allnetworktools.data.net.DnsType
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.groupShape
import com.allnetworktools.ui.components.rise
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.pages.TopBarAction
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Motion
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroMetric
import com.allnetworktools.ui.tools.HostInputField
import com.allnetworktools.ui.tools.Phase
import com.allnetworktools.ui.tools.Spinner
import com.allnetworktools.ui.tools.StartButton
import com.allnetworktools.ui.tools.ToolEmpty
import com.allnetworktools.ui.tools.ToolError
import com.allnetworktools.ui.tools.mono
import com.allnetworktools.util.fmt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import com.allnetworktools.R
import com.allnetworktools.util.S

class DnsController(private val scope: CoroutineScope) {
    var host by mutableStateOf("android.com")
    var type by mutableStateOf(DnsType.A)
    var serverIndex by mutableIntStateOf(1)
    var doh by mutableStateOf(true)
    var phase by mutableStateOf(Phase.Idle)
    var answer by mutableStateOf<DnsAnswer?>(null)
    var failure by mutableStateOf<String?>(null)
    val compare = mutableStateMapOf<String, Float?>()
    val recent = Recents("android.com", "wikipedia.org", "free.fr")
    var systemDns by mutableStateOf("192.168.1.254")
    private var job: Job? = null

    val servers: List<DnsServer> get() = listOf(DnsServer(S(R.string.system), systemDns, null)) + DnsClient.Public
    val server get() = servers[serverIndex]

    fun start() {
        job?.cancel()
        answer = null; failure = null; compare.clear()
        phase = Phase.Running
        val name = host.trim().removePrefix("https://").removePrefix("http://").substringBefore('/')
        host = name
        job = scope.launch {
            val a = runCatching { DnsClient.resolve(name, type, server, doh) }
            a.onFailure {
                failure = S(R.string.no_answer_from_check_the_connection, server.name, server.ip)
                phase = Phase.Error
                return@launch
            }
            val ans = a.getOrThrow()
            answer = ans
            recent.push(name)
            phase = when {
                ans.rcode == 3 -> Phase.Error
                ans.rcode != 0 -> { failure = S(R.string.the_server_answered, ans.rcodeName); Phase.Error }
                ans.records.isEmpty() -> Phase.Empty
                else -> Phase.Results
            }
            if (phase == Phase.Results) {
                servers.map { s -> async { s.name to runCatching { DnsClient.resolve(name, type, s, doh).ms }.getOrNull() } }
                    .awaitAll().forEach { (n, ms) -> compare[n] = ms }
            }
        }
    }

    fun report() = buildString {
        val ans = answer ?: return@buildString
        appendLine("${type.name} $host via ${server.name} (${server.ip}) · ${ans.rcodeName} · ${fmt(ans.ms)} ms")
        ans.records.forEach { appendLine("${it.type}\t${it.value}\tTTL ${it.ttl}") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DnsTool(c: DnsController, systemDns: String?) {
    val actions = LocalActions.current
    val haptics = AntTheme.haptics
    val acc = AntTheme.accent
    LaunchedEffect(systemDns) { if (systemDns != null) c.systemDns = systemDns }
    LaunchedEffect(c.phase) { if (c.phase == Phase.Results) haptics.confirm() }
    if (c.phase == Phase.Results) TopBarAction(Sym.IosShare) { actions.share("DNS ${c.host}", c.report()) }
    val start = { haptics.confirm(); c.start() }
    PageColumn {
        if (c.phase == Phase.Error) {
            if (c.answer?.rcode == 3) {
                ToolError(Sym.TravelExplore, S(R.string.non_existent_domain_nxdomain), S(R.string.does_not_exist_according_to_check, c.host, c.server.name, c.server.ip), S(R.string.edit)) { c.phase = Phase.Idle }
            } else {
                ToolError(Sym.CloudOff, S(R.string.resolution_failed), c.failure ?: "", S(R.string.retry), start)
            }
        }
        HostInputField(c.host, { c.host = it }, S(R.string.domain_name), Sym.Language, c.recent.items, onDone = start)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DnsType.entries.forEach { t -> AntFilterChip(t.name, c.type == t, { c.type = t }) }
        }
        c.servers.chunked(2).forEachIndexed { row, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEachIndexed { col, s ->
                    val i = row * 2 + col
                    ServerRadioCard(s, c.serverIndex == i, Modifier.weight(1f)) { c.serverIndex = i }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainerLow).clickable { c.doh = !c.doh }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("DNS over HTTPS", style = rf(15, 20, 500))
                Text(if (c.serverIndex == 0 && c.doh) S(R.string.not_offered_by_the_system_server) else S(R.string.encrypted_query_rfc_8484), style = rf(12, 16), color = cs.onSurfaceVariant)
            }
            Switch(
                c.doh, null,
                thumbContent = if (c.doh) ({ Symbol(Sym.Check, size = 16.dp, tint = acc.accent) }) else null,
                colors = SwitchDefaults.colors(checkedTrackColor = acc.accent, checkedThumbColor = acc.onAccent),
            )
        }
        StartButton(S(R.string.resolve), Sym.Search, enabled = c.host.isNotBlank() && c.phase != Phase.Running, onClick = start)
        if (c.phase == Phase.Running) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainerLow).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Spinner()
                Text("${c.type.name} ${c.host} → ${c.server.ip}${if (c.doh && c.server.dohUrl != null) " (DoH)" else ""}…", style = mono(14, 20, 500))
            }
        }
        val ans = c.answer
        if (c.phase == Phase.Results && ans != null) {
            HeroCard(padding = 20.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.height(26.dp).clip(RoundedCornerShape(13.dp)).background(AntTheme.net.good).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                        Text(ans.rcodeName, style = rf(12, 16, 700), color = cs.surface)
                    }
                    Text(S(R.string.record_2, ans.records.size, if (ans.records.size > 1) "s" else "", c.type.name), style = rf(13, 18, 500))
                }
                HeroMetric(fmt(ans.ms), "ms", 48, Modifier.padding(top = 10.dp))
                Text(S(R.string.response_time_via, c.server.name, c.server.ip), Modifier.graphicsLayer { alpha = 0.85f }, style = rf(13, 18))
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ans.records.forEachIndexed { i, r ->
                    Row(
                        Modifier.rise(i).fillMaxWidth().clip(groupShape(i, ans.records.size)).background(cs.surfaceContainerLow).padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier.defaultMinSize(minWidth = 52.dp).height(28.dp).clip(RoundedCornerShape(8.dp)).background(acc.accent).padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(r.type, style = rf(12, 16, 700), color = acc.onAccent) }
                        Text(r.value, Modifier.weight(1f), style = mono(14, 20))
                        Text("TTL ${fmt(r.ttl)} s", Modifier.padding(top = 4.dp), style = rf(11, 14), color = cs.onSurfaceVariant)
                    }
                }
            }
            SectionCard {
                Text(S(R.string.server_comparison), style = rf(14, 20, 600))
                val maxMs = maxOf(30f, c.compare.values.filterNotNull().maxOrNull() ?: 0f)
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    c.servers.forEachIndexed { i, s ->
                        val v = c.compare[s.name]
                        val f by animateFloatAsState(if (v == null) 0f else (v / maxMs).coerceIn(0.03f, 1f), Motion.standard(), label = "cmp")
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(s.name, Modifier.width(84.dp), style = rf(13, 18, 500))
                            Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(cs.surfaceContainerHighest)) {
                                Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(if (i == c.serverIndex) acc.accent else cs.outline))
                            }
                            Text(
                                when {
                                    s.name !in c.compare -> "…"
                                    v == null -> S(R.string.failed_3)
                                    else -> "${fmt(v)} ms"
                                },
                                Modifier.width(56.dp), style = rf(13, 18, 600, tnum = true), textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            )
                        }
                    }
                }
            }
        }
        if (c.phase == Phase.Empty) {
            ToolEmpty(Sym.SearchOff, S(R.string.no_record_2, c.type.name), S(R.string.the_server_answered_noerror_with_no), S(R.string.look_up_type_a)) {
                c.type = DnsType.A; start()
            }
        }
    }
}

@Composable
private fun ServerRadioCard(s: DnsServer, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val acc = AntTheme.accent
    val inner by animateFloatAsState(if (selected) 1f else 0f, Motion.standard(), label = "radio")
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier.heightIn(min = 64.dp).clip(shape).background(if (selected) acc.container else androidx.compose.ui.graphics.Color.Transparent)
            .border(if (selected) 2.dp else 1.dp, if (selected) acc.accent else cs.outlineVariant, shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(20.dp).border(2.dp, if (selected) acc.accent else cs.onSurfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(10.dp).graphicsLayer { scaleX = inner; scaleY = inner }.clip(CircleShape).background(acc.accent))
        }
        Column {
            Text(s.name, style = rf(14, 18, 600), color = if (selected) acc.onContainer else cs.onSurface)
            Text(s.ip, style = mono(12, 16), color = (if (selected) acc.onContainer else cs.onSurface).copy(alpha = 0.8f))
        }
    }
}
