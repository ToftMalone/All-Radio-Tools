package com.allnetworktools.ui.pages.wifi

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.allnetworktools.data.net.Net
import com.allnetworktools.data.net.Services
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.SectionTitle
import com.allnetworktools.ui.components.Symbol
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
import com.allnetworktools.ui.tools.BtnKind
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroTile
import com.allnetworktools.ui.tools.HostInputField
import com.allnetworktools.ui.tools.Phase
import com.allnetworktools.ui.tools.ProgressRing
import com.allnetworktools.ui.tools.StartButton
import com.allnetworktools.ui.tools.ToolButton
import com.allnetworktools.ui.tools.ToolButtons
import com.allnetworktools.ui.tools.ToolEmpty
import com.allnetworktools.ui.tools.ToolError
import com.allnetworktools.ui.tools.active
import com.allnetworktools.ui.tools.form
import com.allnetworktools.ui.tools.mono
import com.allnetworktools.util.fmt
import com.allnetworktools.util.plural
import java.net.UnknownHostException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.allnetworktools.R
import com.allnetworktools.util.S
import com.allnetworktools.util.P

enum class PortPreset(val label: String, val sub: String) { Common(S(R.string.common), "Top 100"), All(S(R.string.all), "0 – 65535"), Custom(S(R.string.custom), S(R.string.range)) }

data class OpenPort(val port: Int, val service: String, val banner: String?, val warning: String?)

class PortsController(private val scope: CoroutineScope) {
    var host by mutableStateOf("")
    var preset by mutableStateOf(PortPreset.Common)
    var from by mutableStateOf("1")
    var to by mutableStateOf("1024")
    val timeoutMs = 1000
    var phase by mutableStateOf(Phase.Idle)
    var scanned by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var closed by mutableIntStateOf(0)
    var filtered by mutableIntStateOf(0)
    var startedAt by mutableLongStateOf(0L)
    var endedAt by mutableLongStateOf(0L)
    val open = mutableStateListOf<OpenPort>()
    val recent = Recents()
    private var job: Job? = null

    fun ports(): List<Int> = when (preset) {
        PortPreset.Common -> Services.Top100
        PortPreset.All -> (0..65535).toList()
        PortPreset.Custom -> {
            val a = from.toIntOrNull()?.coerceIn(0, 65535) ?: 1
            val b = to.toIntOrNull()?.coerceIn(0, 65535) ?: a
            (minOf(a, b)..maxOf(a, b)).toList()
        }
    }

    fun start() {
        job?.cancel()
        open.clear(); scanned = 0; closed = 0; filtered = 0
        val list = ports()
        total = list.size
        phase = Phase.Running
        startedAt = System.currentTimeMillis(); endedAt = 0
        job = scope.launch {
            val addr = try {
                Net.resolve(host)
            } catch (_: UnknownHostException) {
                phase = Phase.Error; return@launch
            }
            recent.push(host)
            var unreachable = 0
            val sem = Semaphore(if (list.size > 5000) 256 else 96)
            list.map { p ->
                launch {
                    sem.withPermit {
                        var banner: String? = null
                        val r = Net.tcp(addr, p, timeoutMs) { s -> banner = Net.grabBanner(s, p, host) }
                        when (r.state) {
                            Net.Tcp.Open -> {
                                open += OpenPort(p, Services.name(p), banner, Services.warning(p))
                                open.sortBy { it.port }
                            }
                            Net.Tcp.Closed -> closed++
                            Net.Tcp.Filtered -> filtered++
                            Net.Tcp.Unreachable -> { unreachable++; filtered++ }
                        }
                        scanned++
                    }
                }
            }.forEach { it.join() }
            endedAt = System.currentTimeMillis()
            phase = when {
                open.isEmpty() && closed == 0 && unreachable > 0 -> Phase.Error
                open.isEmpty() -> Phase.Empty
                else -> Phase.Results
            }
        }
    }

    fun stop() {
        job?.cancel()
        endedAt = System.currentTimeMillis()
        phase = if (scanned == 0) Phase.Idle else Phase.Results
    }

    val portsPerSecond: Float get() {
        val end = if (endedAt > 0) endedAt else System.currentTimeMillis()
        return scanned / ((end - startedAt).coerceAtLeast(1) / 1000f)
    }

    fun report() = buildString {
        appendLine(S(R.string.port_scan_ports, host, preset.label, total))
        open.forEach { appendLine("${it.port}/tcp  ${it.service}  ${it.banner ?: ""}") }
        appendLine(S(R.string.open_closed_filtered, open.size, closed, filtered))
    }
}

@Composable
fun PortsTool(c: PortsController, prefill: String?, gateway: String?) {
    val actions = LocalActions.current
    val haptics = AntTheme.haptics
    LaunchedEffect(prefill, gateway) {
        when {
            prefill != null -> { c.host = prefill; c.phase = Phase.Idle }
            c.host.isEmpty() -> c.host = gateway ?: "192.168.1.1"
        }
        if (gateway != null && gateway !in c.recent.items) c.recent.items.add(gateway)
    }
    LaunchedEffect(c.phase) { if (c.phase == Phase.Results) haptics.confirm() }
    if (c.phase == Phase.Results) TopBarAction(Sym.IosShare) { actions.share("Ports ${c.host}", c.report()) }
    val start = { haptics.confirm(); c.start() }
    PageColumn {
        if (c.phase == Phase.Error) {
            ToolError(Sym.Block, S(R.string.host_unreachable), S(R.string.does_not_answer_over_tcp_check, c.host), S(R.string.edit)) { c.phase = Phase.Idle }
        }
        if (c.phase.form) {
            HostInputField(c.host, { c.host = it }, S(R.string.host), recent = c.recent.items, onDone = start)
            PresetSelector(c.preset) { c.preset = it }
            if (c.preset == PortPreset.Custom) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField(S(R.string.start_port), c.from, { c.from = it }, Modifier.weight(1f))
                    NumberField(S(R.string.end_port), c.to, { c.to = it }, Modifier.weight(1f))
                }
            }
            StartButton(S(R.string.scan_ports, fmt(c.ports().size)), Sym.Radar, enabled = c.host.isNotBlank(), onClick = start)
        }
        if (c.phase.active || c.phase == Phase.Empty) {
            val frac = if (c.total == 0) 0f else c.scanned / c.total.toFloat()
            HeroCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    ProgressRing(frac, "${Math.round(frac * 100)} %")
                    Column(Modifier.weight(1f)) {
                        Text(c.host, style = gs(22, 28, 500), maxLines = 1)
                        Text("${fmt(c.scanned)} / ${fmt(c.total)} ports · ${c.preset.label.lowercase()}", style = rf(13, 18, tnum = true))
                        Text(
                            if (c.phase == Phase.Running) S(R.string.ports_s, c.open.size, P(R.plurals.p_open, c.open.size), fmt(c.portsPerSecond))
                            else S(R.string.finished_in_s, c.open.size, P(R.plurals.p_open, c.open.size), fmt((c.endedAt - c.startedAt) / 1000f, 1)),
                            Modifier.padding(top = 4.dp), style = rf(13, 18, 600),
                        )
                    }
                }
                if (c.phase != Phase.Running) {
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HeroTile(c.open.size.toString(), S(R.string.open_4), Modifier.weight(1f))
                        HeroTile(fmt(c.closed), S(R.string.closed), Modifier.weight(1f))
                        HeroTile(fmt(c.filtered), S(R.string.filtered), Modifier.weight(1f))
                    }
                }
                if (c.phase == Phase.Running) {
                    ToolButtons(ToolButton(S(R.string.stop), Sym.Stop, BtnKind.Outline) { c.stop() }, modifier = Modifier.padding(top = 16.dp))
                } else {
                    ToolButtons(
                        ToolButton(S(R.string.run_again), Sym.Refresh, BtnKind.Fill, start),
                        ToolButton(S(R.string.edit), Sym.Tune, BtnKind.Outline) { c.phase = Phase.Idle },
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        }
        if (c.phase.active && c.open.isNotEmpty()) {
            SectionTitle(S(R.string.open_ports))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                c.open.forEachIndexed { i, p -> PortItem(p, i, c.open.size) }
            }
        }
        if (c.phase == Phase.Empty) {
            ToolEmpty(Sym.Lock, S(R.string.no_open_port), S(R.string.all_the_tested_ports_are_closed, c.host), S(R.string.scan_another_range)) { c.phase = Phase.Idle }
        }
    }
}

@Composable
private fun PresetSelector(selected: PortPreset, onSelect: (PortPreset) -> Unit) {
    val acc = AntTheme.accent
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainerHigh).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PortPreset.entries.forEach { p ->
            val on = p == selected
            val bg by animateColorAsState(if (on) acc.accent else Color.Transparent, tween(250, easing = Motion.Emphasized), label = "preset")
            val fg = if (on) acc.onAccent else cs.onSurfaceVariant
            Column(
                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(16.dp)).background(bg).clickable { onSelect(p) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Text(p.label, style = rf(14, 18, 600), color = fg)
                Text(p.sub, style = rf(11, 14, 500), color = fg.copy(alpha = 0.8f))
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    val outline = cs.outline
    Column(
        modifier.clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 4.dp)).background(cs.surfaceContainerHigh)
            .drawBehind { drawRect(outline, Offset(0f, size.height - 2.dp.toPx()), Size(size.width, 2.dp.toPx())) }
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp),
    ) {
        Text(label, style = rf(12, 16), color = cs.onSurfaceVariant)
        BasicTextField(
            value, { onChange(it.filter(Char::isDigit).take(5)) },
            textStyle = rf(16, 24, tnum = true).copy(color = cs.onSurface), singleLine = true,
            cursorBrush = SolidColor(AntTheme.accent.accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
}

@Composable
private fun PortItem(p: OpenPort, index: Int, count: Int) {
    val acc = AntTheme.accent
    Row(
        Modifier.rise(index).fillMaxWidth().clip(groupShape(index, count)).background(cs.surfaceContainerLow).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.defaultMinSize(minWidth = 60.dp).height(44.dp).clip(RoundedCornerShape(14.dp)).background(acc.container).padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) { Text(p.port.toString(), style = mono(16, 20, 700), color = acc.onContainer) }
        Column(Modifier.weight(1f)) {
            Text(p.service, style = rf(15, 20, 600))
            if (p.banner != null) Text(p.banner, style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 2)
            if (p.warning != null) {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Symbol(Sym.Warning, size = 16.dp, filled = true, tint = AntTheme.net.fair)
                    Text(p.warning, style = rf(12, 16, 600), color = AntTheme.net.fair)
                }
            }
        }
        Box(Modifier.height(24.dp).clip(RoundedCornerShape(6.dp)).background(cs.surfaceContainerHighest).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            Text("TCP", style = rf(11, 14, 700))
        }
    }
}
