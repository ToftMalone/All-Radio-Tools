package com.allnetworktools.ui.pages.wifi

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import com.allnetworktools.data.net.Net
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.CardHeader
import com.allnetworktools.ui.components.Legend
import com.allnetworktools.ui.components.MetricTile
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.SegmentedRow
import com.allnetworktools.ui.components.TileGrid
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
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.HeroMetric
import com.allnetworktools.ui.tools.HostInputField
import com.allnetworktools.ui.tools.LogCard
import com.allnetworktools.ui.tools.ParamCard
import com.allnetworktools.ui.tools.ParamRow
import com.allnetworktools.ui.tools.Phase
import com.allnetworktools.ui.tools.StartButton
import com.allnetworktools.ui.tools.Stepper
import com.allnetworktools.ui.tools.ToolButton
import com.allnetworktools.ui.tools.ToolButtons
import com.allnetworktools.ui.tools.ToolEmpty
import com.allnetworktools.ui.tools.ToolError
import com.allnetworktools.ui.tools.active
import com.allnetworktools.ui.tools.form
import com.allnetworktools.util.fmt
import java.net.UnknownHostException
import kotlin.math.abs
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.allnetworktools.R
import com.allnetworktools.util.S

/** Keeps recently used hosts for the session (nothing is written to disk). */
class Recents(vararg initial: String) {
    val items = mutableStateListOf(*initial)
    fun push(v: String) {
        val t = v.trim()
        if (t.isEmpty()) return
        items.remove(t); items.add(0, t)
        while (items.size > 5) items.removeAt(items.lastIndex)
    }
}

class PingController(private val scope: CoroutineScope) {
    var host by mutableStateOf("1.1.1.1")
    /** Echoes to send; 0 means no limit, until stopped. */
    var count by mutableIntStateOf(10)
    val size = 56
    private val intervalMs = 1000L

    /** Only the last hour is kept when running without limit. */
    private val keep = 3600
    var phase by mutableStateOf(Phase.Idle)
    var resolved by mutableStateOf<String?>(null)
    val results = mutableStateListOf<Float?>()
    val recent = Recents("1.1.1.1", "google.com")
    private var job: Job? = null

    fun start(target: String = host) {
        host = target.trim()
        job?.cancel()
        results.clear()
        resolved = null
        phase = Phase.Running
        job = scope.launch {
            val addr = try {
                Net.resolve(host)
            } catch (_: UnknownHostException) {
                phase = Phase.Error; return@launch
            }
            recent.push(host)
            resolved = Net.reverse(addr).takeIf { it != host } ?: addr.hostAddress
            var sent = 0
            while (count == 0 || sent < count) {
                val t0 = System.currentTimeMillis()
                results += Net.ping(addr, size, 2).rttMs
                if (results.size > keep) results.removeAt(0)
                sent++
                if (count != 0 && sent >= count) break
                delay((intervalMs - (System.currentTimeMillis() - t0)).coerceAtLeast(0))
            }
            phase = if (results.all { it == null }) Phase.Empty else Phase.Results
        }
    }

    fun stop() {
        job?.cancel()
        phase = if (results.isEmpty()) Phase.Idle else Phase.Results
    }

    val ok get() = results.filterNotNull()
    val avg get() = ok.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f
    val jitter get() = ok.zipWithNext { a, b -> abs(a - b) }.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f
    val lossPct get() = if (results.isEmpty()) 0 else Math.round((results.size - ok.size) * 100f / results.size)

    fun report(): String = buildString {
        appendLine(S(R.string.ping_bytes, host, resolved ?: "", size))
        results.forEachIndexed { i, v -> appendLine("seq=${i + 1} " + (v?.let { "${fmt(it, 1)} ms" } ?: S(R.string.timed_out_2))) }
        appendLine(S(R.string.min_avg_max_ms_jitter_ms, fmt(ok.minOrNull() ?: 0f, 1), fmt(avg, 1), fmt(ok.maxOrNull() ?: 0f, 1), fmt(jitter, 1), lossPct))
    }
}

@Composable
fun PingTool(c: PingController, prefill: String?) {
    val actions = LocalActions.current
    val haptics = AntTheme.haptics
    val net = AntTheme.net
    LaunchedEffect(prefill) { if (prefill != null) { c.host = prefill; c.phase = Phase.Idle } }
    LaunchedEffect(c.phase) { if (c.phase == Phase.Results) haptics.confirm() }
    if (c.phase == Phase.Results) TopBarAction(Sym.IosShare) { actions.share("Ping ${c.host}", c.report()) }
    val start = { haptics.confirm(); c.start() }
    PageColumn {
        if (c.phase == Phase.Error) {
            ToolError(Sym.Dns, S(R.string.host_not_found), S(R.string.could_not_resolve_check_the_spelling, c.host), S(R.string.edit)) { c.phase = Phase.Idle }
        }
        if (c.phase.form) {
            HostInputField(c.host, { c.host = it }, S(R.string.host_or_ip_address), recent = c.recent.items, onDone = start)
            ParamCard {
                ParamRow(S(R.string.packets_2), S(R.string.n_0_unlimited_one_per_second), first = true) {
                    Stepper(c.count, { c.count = (c.count - 5).coerceAtLeast(0) }, { c.count = (c.count + 5).coerceAtMost(100) })
                }
            }
            StartButton(S(R.string.start_the_ping), enabled = c.host.isNotBlank(), onClick = start)
        }
        if (c.phase.active || c.phase == Phase.Empty) {
            val n = c.results.size
            val quality = when {
                c.avg < 20 -> S(R.string.excellent) to net.good
                c.avg < 60 -> S(R.string.fine) to net.fair
                else -> S(R.string.slow) to net.poor
            }
            HeroCard {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(c.host, style = gs(22, 28, 500), maxLines = 1)
                        Text(c.resolved ?: S(R.string.resolving), style = rf(13, 18), maxLines = 1)
                    }
                    when (c.phase) {
                        Phase.Running -> HeroChip(if (c.count == 0) "$n" else "$n / ${c.count}", AntTheme.accent.accent, blink = true)
                        Phase.Empty -> HeroChip(S(R.string.n_100_lost), cs.error)
                        else -> HeroChip(quality.first, quality.second)
                    }
                }
                val last = c.results.lastOrNull { it != null }
                HeroMetric(
                    when (c.phase) {
                        Phase.Empty -> "—"
                        Phase.Running -> fmt(last ?: 0f, 1)
                        else -> fmt(c.avg, 1)
                    },
                    "ms", modifier = Modifier.padding(top = 14.dp),
                )
                Text(
                    when (c.phase) {
                        Phase.Running -> S(R.string.last_reply)
                        Phase.Empty -> S(R.string.n_0_replies_out_of, n)
                        else -> S(R.string.average_over_replies_bytes, c.ok.size, c.size)
                    },
                    style = rf(13, 18),
                )
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
        if (c.phase.active) {
            SectionCard {
                CardHeader(S(R.string.latency_per_packet)) { Legend(listOf("RTT" to AntTheme.accent.accent, S(R.string.lost) to cs.error)) }
                LatencyBarChart(if (c.count == 0) c.results.takeLast(30) else c.results, if (c.count == 0) 30 else maxOf(c.count, 10), c.avg, running = c.phase == Phase.Running, modifier = Modifier.padding(top = 14.dp))
            }
            TileGrid(
                listOf(
                    Triple("Min", fmt(c.ok.minOrNull() ?: 0f, 1), "ms"),
                    Triple("Max", fmt(c.ok.maxOrNull() ?: 0f, 1), "ms"),
                    Triple(S(R.string.jitter), fmt(c.jitter, 1), "ms"),
                    Triple(S(R.string.loss), c.lossPct.toString(), "% · ${c.results.size - c.ok.size}/${c.results.size}"),
                ),
            ) { (k, v, u), mod -> MetricTile(k, v, u, modifier = mod) }
            val addr = c.resolved ?: c.host
            LogCard(
                S(R.string.log),
                c.results.mapIndexed { i, v -> i to v }.takeLast(6).reversed().map { (i, v) ->
                    (if (v == null) S(R.string.icmp_seq_timed_out, i + 1) else S(R.string.bytes_from_seq_ms, c.size + 8, addr, i + 1, fmt(v, 1))) to (if (v == null) cs.error else null)
                },
            )
        }
        if (c.phase == Phase.Empty) {
            ToolEmpty(Sym.TimerOff, S(R.string.no_reply), S(R.string.packets_sent_none_received_the_host), S(R.string.run_again), start)
        }
    }
}

/** One bar per packet, dashed grid, average line; lost packets are hatched in the error color. */
@Composable
fun LatencyBarChart(samples: List<Float?>, capacity: Int, average: Float, running: Boolean, modifier: Modifier = Modifier) {
    val acc = AntTheme.accent.accent
    val err = cs.error
    val empty = cs.surfaceContainerHighest
    val grid = cs.outlineVariant
    val avgColor = cs.onSurfaceVariant
    val maxV = samples.filterNotNull().maxOrNull() ?: 0f
    val scale = maxOf(30f, ceil(maxV / 10f) * 10f)
    val avgY by animateFloatAsState(average / scale, Motion.standard(), label = "avg")
    Box(modifier.fillMaxWidth().height(132.dp)) {
        listOf(scale, scale / 2, 0f).forEach { v ->
            Text(fmt(v), Modifier.offset(y = 132.dp * (1 - v / scale) - 7.dp), style = rf(10, 14), color = cs.onSurfaceVariant)
        }
        Canvas(Modifier.padding(start = 28.dp).fillMaxWidth().height(132.dp)) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
            listOf(0f, 0.5f, 1f).forEach { f -> drawLine(grid, Offset(0f, size.height * f), Offset(size.width, size.height * f), 1.dp.toPx(), pathEffect = dash) }
            val gap = 4.dp.toPx()
            val w = (size.width - gap * (capacity - 1)) / capacity
            for (i in 0 until capacity) {
                val x = i * (w + gap)
                when {
                    i >= samples.size -> drawRoundRect(empty, Offset(x, size.height - 4.dp.toPx()), Size(w, 4.dp.toPx()), CornerRadius(2.dp.toPx()))
                    samples[i] == null -> {
                        val r = CornerRadius(6.dp.toPx())
                        val rectPath = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(x, 0f, x + w, size.height, r)) }
                        clipPath(rectPath) {
                            var d = -size.height
                            while (d < w + size.height) {
                                drawLine(err.copy(alpha = 0.3f), Offset(x + d, size.height), Offset(x + d + size.height, 0f), 2.dp.toPx())
                                d += 8.dp.toPx()
                            }
                        }
                        drawRoundRect(err, Offset(x, 0f), Size(w, size.height), r, style = Stroke(1.5f.dp.toPx()))
                    }
                    else -> {
                        val h = (samples[i]!! / scale).coerceIn(0.02f, 1f) * size.height
                        val alpha = if (running && i == samples.size - 1) 1f else 0.8f
                        drawRoundRect(acc.copy(alpha = alpha), Offset(x, size.height - h), Size(w, h), CornerRadius(6.dp.toPx()))
                    }
                }
            }
            if (samples.any { it != null }) {
                val y = size.height * (1 - avgY)
                drawLine(avgColor.copy(alpha = 0.5f), Offset(0f, y), Offset(size.width, y), 2.dp.toPx())
            }
        }
    }
}
