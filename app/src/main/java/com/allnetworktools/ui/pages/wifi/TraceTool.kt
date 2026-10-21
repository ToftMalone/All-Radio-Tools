package com.allnetworktools.ui.pages.wifi

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.allnetworktools.data.net.Net
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.BlinkDot
import com.allnetworktools.ui.components.SegmentedRow
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.rise
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.pages.TopBarAction
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Motion
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.BtnKind
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HostInputField
import com.allnetworktools.ui.tools.ParamCard
import com.allnetworktools.ui.tools.ParamRow
import com.allnetworktools.ui.tools.Phase
import com.allnetworktools.ui.tools.StartButton
import com.allnetworktools.ui.tools.ToolButton
import com.allnetworktools.ui.tools.ToolButtons
import com.allnetworktools.ui.tools.ToolEmpty
import com.allnetworktools.ui.tools.ToolError
import com.allnetworktools.ui.tools.active
import com.allnetworktools.ui.tools.form
import com.allnetworktools.ui.tools.mono
import com.allnetworktools.util.fmt
import java.net.InetAddress
import java.net.UnknownHostException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.allnetworktools.R
import com.allnetworktools.util.S

data class Hop(
    val n: Int,
    val ip: String?,
    val host: String?,
    val rtts: List<Float?>,
    val tag: String,
    val destination: Boolean,
) {
    val timedOut get() = ip == null
    val avg: Float? get() = rtts.filterNotNull().takeIf { it.isNotEmpty() }?.average()?.toFloat()
}

private fun isPrivate(ip: String) = runCatching {
    val a = InetAddress.getByName(ip)
    a.isSiteLocalAddress || a.isLinkLocalAddress || a.isLoopbackAddress || ip.startsWith("100.") && ip.split('.')[1].toInt() in 64..127
}.getOrDefault(false)

private val IxHints = listOf("franceix", "ix.", "-ix", "decix", "de-cix", "ams-ix", "linx", "equinix", "peering")

class TraceController(private val scope: CoroutineScope) {
    var host by mutableStateOf("one.one.one.one")
    val maxHops = 30
    var phase by mutableStateOf(Phase.Idle)
    var target by mutableStateOf<String?>(null)
    var probingTtl by mutableIntStateOf(0)
    val hops = mutableStateListOf<Hop>()
    val recent = Recents("one.one.one.one", "google.com", "8.8.8.8")
    private var job: Job? = null

    val reached get() = hops.any { it.destination }

    fun start(dest: String = host) {
        host = dest.trim()
        job?.cancel()
        hops.clear()
        target = null
        phase = Phase.Running
        job = scope.launch {
            val addr = try {
                Net.resolve(host)
            } catch (_: UnknownHostException) {
                phase = Phase.Error; return@launch
            }
            recent.push(host)
            target = addr.hostAddress
            var silent = 0
            for (ttl in 1..maxHops) {
                probingTtl = ttl
                val reply = Net.ping(addr, 56, 1, ttl)
                val from = reply.from
                if (from == null) {
                    hops += Hop(ttl, null, null, listOf(null, null, null), S(R.string.icmp_filtered_by_this_router), false)
                    if (++silent >= 6) break
                    continue
                }
                silent = 0
                val dest = !reply.ttlExceeded
                val hopAddr = InetAddress.getByName(from)
                val rtts = buildList {
                    if (dest) add(reply.rttMs)
                    while (size < 3) add(Net.ping(hopAddr, 56, 1).rttMs)
                }
                val name = Net.reverse(hopAddr, 1200)
                val tag = when {
                    dest -> "Destination"
                    ttl == 1 && isPrivate(from) -> S(R.string.gateway_local_network)
                    isPrivate(from) -> S(R.string.private_carrier_network)
                    IxHints.any { name?.lowercase()?.contains(it) == true } -> S(R.string.internet_exchange_point)
                    else -> S(R.string.carrier_router)
                }
                hops += Hop(ttl, from, name, rtts, tag, dest)
                if (dest) break
            }
            // Drop the trailing run of silent hops.
            while (hops.lastOrNull()?.timedOut == true && !reached) hops.removeAt(hops.lastIndex)
            phase = if (hops.none { !it.timedOut }) Phase.Empty else Phase.Results
        }
    }

    fun stop() {
        job?.cancel()
        phase = if (hops.isEmpty()) Phase.Idle else Phase.Results
    }

    fun report() = buildString {
        appendLine(S(R.string.traceroute_to, host, target ?: ""))
        hops.forEach { h -> appendLine("${h.n}  ${h.host ?: h.ip ?: "* * *"}  ${h.ip ?: ""}  ${h.rtts.joinToString(" ") { it?.let { v -> fmt(v, 1) + " ms" } ?: "*" }}") }
    }
}

@Composable
fun TraceTool(c: TraceController) {
    val actions = LocalActions.current
    val haptics = AntTheme.haptics
    LaunchedEffect(c.phase) { if (c.phase == Phase.Results) haptics.confirm() }
    if (c.phase == Phase.Results) TopBarAction(Sym.IosShare) { actions.share("Traceroute ${c.host}", c.report()) }
    val start = { haptics.confirm(); c.start() }
    PageColumn {
        if (c.phase == Phase.Error) {
            ToolError(Sym.CloudOff, S(R.string.host_not_found), S(R.string.could_not_resolve_check_the_connection, c.host), S(R.string.edit)) { c.phase = Phase.Idle }
        }
        if (c.phase.form) {
            HostInputField(c.host, { c.host = it }, "Destination", recent = c.recent.items, onDone = start)
            ParamCard {
                ParamRow(S(R.string.protocol), S(R.string.icmp_without_root), first = true) {}
            }
            StartButton(S(R.string.start_the_traceroute), Sym.Route, enabled = c.host.isNotBlank(), onClick = start)
        }
        if (c.phase.active || c.phase == Phase.Empty) {
            HeroCard {
                Text(c.host, style = gs(22, 28, 500), maxLines = 1)
                Text(S(R.string.icmp_hops_max, c.target ?: "…", c.maxHops), style = rf(13, 18))
                PathStrip(c)
                val last = c.hops.lastOrNull { it.destination }
                Text(
                    when {
                        c.phase == Phase.Running -> S(R.string.ttl_probe_of, c.probingTtl, c.maxHops)
                        c.phase == Phase.Empty -> S(R.string.no_hop_reached)
                        last != null -> S(R.string.destination_reached_hops_ms, last.n, fmt(last.avg ?: 0f, 1))
                        else -> S(R.string.destination_not_reached_hops, c.hops.size)
                    },
                    Modifier.padding(top = 14.dp), style = rf(14, 20, 600),
                )
                if (c.phase == Phase.Running) {
                    ToolButtons(ToolButton(S(R.string.stop), Sym.Stop, BtnKind.Outline) { c.stop() }, modifier = Modifier.padding(top = 14.dp))
                } else {
                    ToolButtons(
                        ToolButton(S(R.string.run_again), Sym.Refresh, BtnKind.Fill, start),
                        ToolButton(S(R.string.edit), Sym.Tune, BtnKind.Outline) { c.phase = Phase.Idle },
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            }
        }
        if (c.phase.active && c.hops.isNotEmpty() || c.phase == Phase.Running) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainerLow).padding(vertical = 8.dp)) {
                val rows = c.hops.toList()
                val probing = c.phase == Phase.Running
                rows.forEachIndexed { i, h -> HopRow(h, i == 0, i == rows.lastIndex && !probing, i) }
                if (probing) HopRow(Hop(c.probingTtl, null, S(R.string.ttl_probe, c.probingTtl), emptyList(), "", false), rows.isEmpty(), true, 0, probing = true)
            }
        }
        if (c.phase == Phase.Empty) {
            ToolEmpty(Sym.Route, S(R.string.no_hop_answered), S(R.string.the_intermediate_routers_filter_icmp_packets), S(R.string.run_again), start)
        }
    }
}

@Composable
private fun PathStrip(c: TraceController) {
    val acc = AntTheme.accent
    val blink by rememberInfiniteTransition(label = "probe").animateFloat(
        1f, 0.25f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "probe",
    )
    val answered = c.hops.filter { !it.timedOut }
    val steps = listOf(
        Triple(Sym.Smartphone, S(R.string.you), true),
        Triple(Sym.Router, "Box", answered.any { it.n == 1 }),
        Triple(Sym.Cloud, "FAI", answered.any { it.n >= 2 && it.ip?.let(::isPrivate) == false }),
        Triple(Sym.Hub, "IX", answered.any { it.tag.startsWith("Point") } || answered.count { it.ip?.let(::isPrivate) == false } >= 3 || c.reached),
        Triple(Sym.Flag, S(R.string.target), c.reached),
    )
    Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.Top) {
        steps.forEachIndexed { i, (icon, label, on) ->
            val bg by animateColorAsState(if (on) acc.accent else cs.surface, tween(400, easing = Motion.Emphasized), label = "node")
            val probing = !on && c.phase == Phase.Running && (i == 0 || steps[i - 1].third)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
                    if (i > 0) {
                        Row(Modifier.fillMaxWidth()) {
                            Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (on) acc.accent else cs.surface))
                            Box(Modifier.weight(1f))
                        }
                    }
                    Box(
                        Modifier.size(40.dp).graphicsLayer { alpha = if (probing) blink else 1f }
                            .clip(if (i == steps.lastIndex) RoundedCornerShape(14.dp) else CircleShape).background(bg),
                        contentAlignment = Alignment.Center,
                    ) { Symbol(icon, size = 20.dp, filled = true, tint = if (on) acc.onAccent else cs.onSurfaceVariant) }
                }
                Text(label, style = rf(11, 14, 600))
            }
        }
    }
}

@Composable
private fun HopRow(h: Hop, first: Boolean, last: Boolean, index: Int, probing: Boolean = false) {
    val acc = AntTheme.accent
    val line = acc.accent.copy(alpha = 0.4f)
    val outline = cs.outline
    val muted = h.timedOut || probing
    Row(Modifier.rise(index).fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.width(32.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(3.dp).height(12.dp).background(if (first) Color.Transparent else line))
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                if (muted) {
                    Canvas(Modifier.size(28.dp)) {
                        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
                        if (h.destination) drawRoundRect(outline, cornerRadius = CornerRadius(10.dp.toPx()), style = Stroke(2.dp.toPx(), pathEffect = dash))
                        else drawCircle(outline, size.minDimension / 2 - 1.dp.toPx(), style = Stroke(2.dp.toPx(), pathEffect = dash))
                    }
                    if (probing) BlinkDot(acc.accent, 8.dp, 1000)
                    else Text(h.n.toString(), style = rf(12, 16, 700), color = cs.onSurfaceVariant)
                } else {
                    Box(
                        Modifier.size(28.dp).clip(if (h.destination) RoundedCornerShape(10.dp) else CircleShape).background(acc.accent),
                        contentAlignment = Alignment.Center,
                    ) { Text(h.n.toString(), style = rf(12, 16, 700), color = acc.onAccent) }
                }
            }
            Box(Modifier.width(3.dp).weight(1f).background(if (last) Color.Transparent else line))
        }
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(
                if (h.timedOut && !probing) "* * *" else h.host ?: h.ip ?: "",
                style = rf(15, 20, 600).copy(fontStyle = if (probing) FontStyle.Italic else FontStyle.Normal),
                color = if (muted) cs.onSurfaceVariant else cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (!probing) {
                Text(if (h.timedOut) S(R.string.no_response) else h.ip.orEmpty(), style = mono(12, 16), color = cs.onSurfaceVariant)
                Text(h.tag, style = rf(11, 16), color = cs.onSurfaceVariant)
            }
        }
        Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.End) {
            h.avg?.let { Text("${fmt(it, 1)} ms", style = gs(18, 22, 500, tnum = true)) }
            if (!probing) {
                Text(h.rtts.joinToString(" · ") { it?.let { v -> fmt(v, 1) } ?: "*" }, style = mono(11, 16), color = cs.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}
