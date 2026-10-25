package com.allnetworktools.ui.pages.wifi

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.allnetworktools.data.Country
import com.allnetworktools.data.WorldMap
import com.allnetworktools.data.net.CfBucketView
import com.allnetworktools.data.net.CfCsv
import com.allnetworktools.data.net.CfConfig
import com.allnetworktools.data.net.CfEngine
import com.allnetworktools.data.net.CfExperience
import com.allnetworktools.data.net.CfHttp
import com.allnetworktools.data.net.CfMath
import com.allnetworktools.data.net.CfMeta
import com.allnetworktools.data.net.CfSnapshot
import com.allnetworktools.data.net.CfStep
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.InfoList
import com.allnetworktools.ui.components.InfoRow
import com.allnetworktools.ui.components.LevelBar
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.SectionTitle
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.TextAction
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.pages.TopBarAction
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.BtnKind
import com.allnetworktools.ui.tools.Phase
import com.allnetworktools.ui.tools.ToolButton
import com.allnetworktools.ui.tools.ToolButtons
import com.allnetworktools.ui.tools.ToolError
import com.allnetworktools.util.fmt
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.allnetworktools.R
import com.allnetworktools.util.S

fun mbps(v: Float) = if (v >= 100) fmt(v) else fmt(v, 1)

private fun mbpsOf(bps: Double?) = bps?.let { mbps((it / 1e6).toFloat()) } ?: "—"
private fun msOf(v: Double?) = v?.let { if (it >= 100) fmt(it.toFloat()) else fmt(it.toFloat(), 1) } ?: "—"
private fun pctOf(v: Double?) = v?.let { if (it == 0.0) "0" else fmt((it * 100).toFloat(), 1) } ?: "—"

/** 100 ko, 1 Mo, 250 Mo: the transfer sizes the way the site labels them, in French units. */
fun sizeLabel(bytes: Long) = if (bytes >= 1_000_000) S(R.string.mb, bytes / 1_000_000) else S(R.string.kb, bytes / 1_000)

val levelNames = listOf(S(R.string.bad), S(R.string.poor), S(R.string.fair), S(R.string.good), S(R.string.excellent))

class SpeedController(private val scope: CoroutineScope) {
    var phase by mutableStateOf(Phase.Idle)
    var paused by mutableStateOf(false)
    var snap by mutableStateOf(CfSnapshot.Empty)
    var meta by mutableStateOf<CfMeta?>(null)
    var measuredAt by mutableLongStateOf(0L)
    private var engine: CfEngine? = null
    private var http: CfHttp? = null
    private var job: Job? = null

    fun loadMeta() {
        if (meta == null) scope.launch { meta = CfHttp.meta() }
    }

    fun start() {
        job?.cancel()
        http?.close()
        val h = CfHttp().also { http = it }
        // Big final files only off metered networks (mobile data, metered Wi-Fi).
        val metered = runCatching {
            com.allnetworktools.util.Res.context?.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered
        }.getOrNull() ?: true
        engine = CfEngine(h, CfHttp::packetLoss, bigFiles = !metered)
        snap = CfSnapshot.Empty
        measuredAt = 0L
        loadMeta()
        resume()
    }

    fun pause() {
        job?.cancel()
        paused = true
    }

    fun resume() {
        val e = engine ?: return
        paused = false
        phase = Phase.Running
        job = scope.launch {
            try {
                e.run { snap = it }
                measuredAt = System.currentTimeMillis()
                phase = Phase.Results
                http?.close()
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                phase = Phase.Error
            }
        }
    }

    fun cancel() {
        job?.cancel()
        http?.close()
        engine = null
        paused = false
        phase = Phase.Idle
        snap = CfSnapshot.Empty
    }

    /** The site's CSV export, byte for byte the same columns. */
    fun csv(): String = CfCsv.format(snap)
}

@Composable
fun SpeedTool(c: SpeedController, networkLabel: String, networkIcon: String) {
    val actions = LocalActions.current
    val haptics = AntTheme.haptics
    val s = c.snap
    LaunchedEffect(Unit) { c.loadMeta() }
    LaunchedEffect(c.phase) { if (c.phase == Phase.Results) haptics.confirm() }
    val export = {
        val name = "speed-results-${c.measuredAt / 1000}.csv"
        actions.saveFile(name, "text/csv") { c.csv().toByteArray() }
    }
    if (c.phase == Phase.Results) TopBarAction(Sym.Download, export)
    val start = { haptics.confirm(); c.start() }
    PageColumn {
        SectionTitle(S(R.string.your_internet_speed))
        SpeedCards(s)
        LatencyCards(s)
        if (c.phase == Phase.Error) {
            ToolError(Sym.SignalDisconnected, S(R.string.test_interrupted), S(R.string.the_connection_to_the_cloudflare_server), S(R.string.retry)) { c.resume() }
        }
        ProgressCard(c, start)
        AimCard(s)
        ServerCard(c.meta, networkLabel)
        if (s.stepIndex >= 0) {
            SectionTitle(S(R.string.measurements))
            BandwidthMeasurements(S(R.string.download_measurements), S(R.string.download_test), s.downBuckets, AntTheme.accent.accent, s.download)
            BandwidthMeasurements(S(R.string.upload_measurements), S(R.string.upload_test), s.upBuckets, AntTheme.net.galileo, s.upload)
            LatencyMeasurements(s)
            PacketLossMeasurements(s)
        }
        if (c.phase == Phase.Results) {
            val at = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, Locale.getDefault()).format(Date(c.measuredAt))
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(S(R.string.measured_on_s, at, s.durationMs / 1000), Modifier.weight(1f), style = rf(12, 16), color = cs.onSurfaceVariant)
                TextAction(S(R.string.export_to_csv), export, trailingIcon = Sym.Download)
            }
        }
    }
}

/** Download and upload: the 90th percentile so far, and every request's speed over time with that percentile drawn across. */
@Composable
private fun SpeedCards(s: CfSnapshot) {
    val down = AntTheme.accent.accent
    val up = AntTheme.net.galileo
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(Triple(S(R.string.download), Sym.Download, down) to (s.download to s.downPoints), Triple(S(R.string.upload), Sym.Upload, up) to (s.upload to s.upPoints))
            .forEach { (head, data) ->
                val (label, icon, color) = head
                val (bps, points) = data
                SectionCard(Modifier.weight(1f), padding = PaddingValues(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Symbol(icon, size = 18.dp, filled = true, tint = color)
                        Text(label, style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                    }
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
                        Text(mbpsOf(bps), style = gs(36, 40, 500, -1f, tnum = true))
                        Text(S(R.string.mbps), Modifier.padding(bottom = 4.dp), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                    }
                    OverTime(points.mapNotNull { p -> p.bps?.let { p.atMs to it } }, bps, color, Modifier.fillMaxWidth().height(48.dp).padding(top = 8.dp))
                }
            }
    }
}

@Composable
private fun OverTime(points: List<Pair<Long, Double>>, p90: Double?, color: Color, modifier: Modifier) {
    val track = cs.outlineVariant
    Canvas(modifier) {
        drawLine(track, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        if (points.isEmpty()) return@Canvas
        val t0 = points.minOf { it.first }
        val t1 = maxOf(points.maxOf { it.first }, t0 + 1)
        val top = maxOf(points.maxOf { it.second }, p90 ?: 0.0) * 1.1
        val r = 2.5f.dp.toPx()
        fun y(v: Double) = (size.height - r - (v / top) * (size.height - 2 * r)).toFloat()
        points.forEach { (t, v) ->
            val x = r + (t - t0).toFloat() / (t1 - t0) * (size.width - 2 * r)
            drawCircle(color.copy(alpha = 0.55f), r, Offset(x, y(v)))
        }
        if (p90 != null && p90 > 0) {
            drawLine(color, Offset(0f, y(p90)), Offset(size.width, y(p90)), 1.5f.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        }
    }
}

/** Latency and jitter idle, with their values under download and upload load; packet loss. */
@Composable
private fun LatencyCards(s: CfSnapshot) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LoadedMetric(S(R.string.latency), Sym.NetworkPing, s.latency, s.downLoadedLatency, s.upLoadedLatency, Modifier.weight(1f))
        LoadedMetric(S(R.string.jitter), Sym.SwapVert, s.jitter, s.downLoadedJitter, s.upLoadedJitter, Modifier.weight(1f))
    }
    SectionCard(padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { MetricHead(S(R.string.packet_loss), Sym.SignalDisconnected) }
            Text(if (s.packetLossError != null) "—" else pctOf(s.packetLoss?.ratio), style = gs(26, 32, 500, -0.5f, tnum = true))
            Text(" %", style = rf(12, 16, 600), color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun MetricHead(label: String, icon: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Symbol(icon, size = 16.dp, filled = true, tint = AntTheme.accent.accent)
        Text(label, style = rf(12, 16, 600), color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LoadedMetric(label: String, icon: String, idle: Double?, down: Double?, up: Double?, modifier: Modifier) {
    SectionCard(modifier, padding = PaddingValues(14.dp)) {
        MetricHead(label, icon)
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
            Text(msOf(idle), style = gs(26, 32, 500, -0.5f, tnum = true))
            Text(" ms", Modifier.padding(bottom = 3.dp), style = rf(12, 16, 600), color = cs.onSurfaceVariant)
        }
        listOf(Sym.ArrowDownward to down, Sym.ArrowUpward to up).forEach { (arrow, v) ->
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Symbol(arrow, size = 14.dp, tint = cs.onSurfaceVariant)
                Text(" ${msOf(v)} ms", style = rf(12, 16, 600, tnum = true), color = cs.onSurfaceVariant)
            }
        }
    }
}

private fun stepText(step: CfStep?, done: Int, s: CfSnapshot): String = when (step) {
    is CfStep.Latency -> S(R.string.latency_requests, done, step.packets)
    is CfStep.Transfer -> if (step.streams > 1) "${S(R.string.download)} ${sizeLabel(step.bytes)} · ${S(R.string.parallel_connections, step.streams)}"
        else S(R.string.requests, if (step.down) S(R.string.download) else S(R.string.upload), sizeLabel(step.bytes), done, step.count)
    is CfStep.PacketLoss -> s.packetLossLive?.let { S(R.string.packet_loss_packets, it.sent, step.packets) } ?: S(R.string.packet_loss_connecting)
    null -> ""
}

@Composable
private fun ProgressCard(c: SpeedController, start: () -> Unit) {
    val s = c.snap
    val total = CfConfig.steps.size
    if (c.phase == Phase.Running || c.paused || (c.phase == Phase.Error && s.stepIndex >= 0)) {
        SectionCard {
            val step = s.step
            val within = when (step) {
                is CfStep.Latency -> s.stepDone.toFloat() / step.packets
                is CfStep.Transfer -> s.stepDone.toFloat() / step.count
                is CfStep.PacketLoss -> (s.packetLossLive?.sent ?: 0).toFloat() / step.packets
                null -> 0f
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (c.paused) S(R.string.paused) else S(R.string.step_of, (s.stepIndex + 1).coerceIn(1, total), total),
                    Modifier.weight(1f), style = rf(14, 20, 600), color = AntTheme.accent.accent,
                )
                Text(fmt(((s.stepIndex.coerceAtLeast(0) + within) / total * 100), 0) + " %", style = rf(13, 18, 600, tnum = true), color = cs.onSurfaceVariant)
            }
            Text(stepText(step, s.stepDone, s), Modifier.padding(top = 2.dp, bottom = 10.dp), style = rf(13, 18), color = cs.onSurfaceVariant)
            LevelBar((s.stepIndex.coerceAtLeast(0) + within) / total, AntTheme.accent.accent)
        }
    }
    when {
        c.phase == Phase.Running -> ToolButtons(
            ToolButton("Pause", Sym.Pause, BtnKind.OutlineOnSurface) { c.pause() },
            ToolButton(S(R.string.cancel), Sym.Close, BtnKind.OutlineOnSurface) { c.cancel() },
        )
        c.paused -> ToolButtons(
            ToolButton(S(R.string.resume), Sym.PlayArrow, BtnKind.Fill) { c.resume() },
            ToolButton(S(R.string.cancel), Sym.Close, BtnKind.OutlineOnSurface) { c.cancel() },
        )
        c.phase == Phase.Results -> ToolButtons(ToolButton(S(R.string.run_the_test_again), Sym.Refresh, BtnKind.Big, start))
        c.phase == Phase.Idle -> {
            ToolButtons(ToolButton(S(R.string.start_2), Sym.PlayArrow, BtnKind.Big, start))
            Text(
                S(R.string.when_you_start_the_speed_test) +
                    S(R.string.note_a_speed_test_can_use),
                Modifier.padding(horizontal = 4.dp), style = rf(12, 16), color = cs.onSurfaceVariant,
            )
        }
        else -> Unit
    }
}

/** Network quality score: streaming, gaming and video calls, one to five stars as on the site. */
@Composable
private fun AimCard(s: CfSnapshot) {
    InfoList(S(R.string.network_quality_score)) {
        listOf(
            Triple(CfExperience.Streaming, S(R.string.video_streaming), Sym.Tv),
            Triple(CfExperience.Gaming, S(R.string.online_gaming), Sym.Gamepad),
            Triple(CfExperience.Rtc, S(R.string.video_calls), Sym.Videocam),
        ).forEach { (e, label, icon) ->
            val score = s.scores?.get(e)
            val bad = score?.level == 0
            val color = if (bad) cs.error else AntTheme.accent.accent
            androidx.compose.material3.HorizontalDivider(color = cs.outlineVariant, thickness = 1.dp)
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(icon, size = 22.dp, filled = true, tint = cs.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text(label, style = rf(14, 20, 600))
                    Text(
                        when {
                            score != null -> levelNames[score.level]
                            s.scores != null -> S(R.string.unavailable)
                            else -> S(R.string.pending)
                        },
                        style = rf(12, 16), color = if (score != null) color else cs.onSurfaceVariant,
                    )
                }
                Stars(score?.let { it.level + 1 } ?: 0, color)
            }
        }
    }
}

@Composable
private fun Stars(filled: Int, color: Color) {
    val off = cs.surfaceContainerHighest
    Canvas(Modifier.size(width = 92.dp, height = 16.dp)) {
        val r = size.height / 2
        repeat(5) { i ->
            val cx = r + i * (size.width - 2 * r) / 4
            val path = Path()
            for (k in 0 until 10) {
                val a = Math.toRadians(-90.0 + k * 36)
                val rr = if (k % 2 == 0) r else r * 0.45f
                val p = Offset(cx + (rr * cos(a)).toFloat(), r + (rr * sin(a)).toFloat())
                if (k == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            path.close()
            drawPath(path, if (i < filled) color else off)
        }
    }
}

/** Server location: the Cloudflare data centre the test ran against, and where Cloudflare places the phone. */
@Composable
private fun ServerCard(meta: CfMeta?, networkLabel: String) {
    val actions = LocalActions.current
    val context = LocalContext.current
    val countries by produceState<List<Country>>(emptyList()) { value = WorldMap.load(context) }
    InfoList(S(R.string.server_location)) {
        if (meta?.coloLat != null && meta.coloLon != null) {
            MiniMap(countries, meta.coloLat to meta.coloLon, meta.lat?.let { la -> meta.lon?.let { la to it } })
        }
        InfoRow(S(R.string.server), listOfNotNull(meta?.coloCity, meta?.colo?.let { "($it)" }).joinToString(" ").ifEmpty { "Cloudflare" })
        InfoRow(S(R.string.your_network_4), listOfNotNull(meta?.asOrganization, meta?.asn?.let { "AS$it" }).joinToString(" · ").ifEmpty { networkLabel })
        meta?.clientIp?.let { ip -> InfoRow(S(R.string.your_ip_address), ip) { actions.copy(S(R.string.ip_address), ip) } }
        listOfNotNull(meta?.city, meta?.region, meta?.country).takeIf { it.isNotEmpty() }?.let { InfoRow(S(R.string.your_estimated_location), it.joinToString(", ")) }
    }
}

@Composable
private fun MiniMap(countries: List<Country>, server: Pair<Double, Double>, client: Pair<Double, Double>?) {
    val land = cs.surfaceContainerHighest
    val acc = AntTheme.accent.accent
    val other = AntTheme.net.galileo
    val bg = cs.surfaceContainer
    val pts = listOfNotNull(server, client)
    // Equirectangular window around both points, at least 40° × 20°, with a 2:1 aspect.
    val cLat = (pts.minOf { it.first } + pts.maxOf { it.first }) / 2
    val cLon = (pts.minOf { it.second } + pts.maxOf { it.second }) / 2
    val spanLon = maxOf(40.0, (pts.maxOf { it.second } - pts.minOf { it.second }) * 1.6, (pts.maxOf { it.first } - pts.minOf { it.first }) * 3.2)
    val spanLat = spanLon / 2
    Canvas(Modifier.padding(bottom = 10.dp).fillMaxWidth().aspectRatio(2f).clip(RoundedCornerShape(16.dp)).background(bg)) {
        fun xy(lat: Double, lon: Double) = Offset(
            ((lon - cLon) / spanLon + 0.5).toFloat() * size.width,
            ((cLat - lat) / spanLat + 0.5).toFloat() * size.height,
        )
        countries.forEach { country ->
            country.rings.forEach { r ->
                val n = r.size / 2
                var visible = false
                for (i in 0 until n) if (abs(r[i * 2 + 1] - cLat) < spanLat && abs(r[i * 2] - cLon) < spanLon) { visible = true; break }
                if (!visible) return@forEach
                val path = Path()
                for (i in 0 until n) {
                    val p = xy(r[i * 2 + 1].toDouble(), r[i * 2].toDouble())
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                path.close()
                drawPath(path, land)
            }
        }
        val sp = xy(server.first, server.second)
        client?.let {
            val cp = xy(it.first, it.second)
            drawLine(acc.copy(alpha = 0.6f), cp, sp, 2.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            drawCircle(other, 5.dp.toPx(), cp)
        }
        drawCircle(acc.copy(alpha = 0.25f), 12.dp.toPx(), sp)
        drawCircle(acc, 6.dp.toPx(), sp)
    }
}

/** Per transfer size: requests done, and a box plot (min, quartiles, max) of their speeds on a shared log scale. */
@Composable
private fun BandwidthMeasurements(title: String, test: String, buckets: List<CfBucketView>, color: Color, p90: Double?) {
    if (buckets.isEmpty()) return
    val all = buckets.flatMap { b -> b.timings.mapNotNull { it.bps } }.filter { it > 0 }
    InfoList(title, trailing = { Text(S(R.string.n_90th_percentile_mbps, mbpsOf(p90)), style = rf(12, 16, 600, tnum = true), color = cs.onSurfaceVariant) }) {
        val lo = all.minOrNull()?.let { log10(it) - 0.1 } ?: 0.0
        val hi = all.maxOrNull()?.let { log10(it) + 0.1 } ?: 1.0
        buckets.forEach { b ->
            val v = b.timings.mapNotNull { it.bps }
            androidx.compose.material3.HorizontalDivider(color = cs.outlineVariant, thickness = 1.dp)
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$test ${sizeLabel(b.bytes)}", Modifier.weight(1f), style = rf(13, 18, 600))
                    Text("${b.timings.size}/${b.planned}", style = rf(12, 16, 600, tnum = true), color = cs.onSurfaceVariant)
                }
                if (v.isNotEmpty()) {
                    BoxPlot(v.map(::log10), lo, hi, color, Modifier.padding(top = 6.dp).fillMaxWidth().height(18.dp))
                    Text(
                        S(R.string.min_median_max_mbps, mbpsOf(v.min()), mbpsOf(CfMath.percentile(v, 0.5)), mbpsOf(v.max())),
                        Modifier.padding(top = 4.dp), style = rf(11, 14, tnum = true), color = cs.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BoxPlot(values: List<Double>, lo: Double, hi: Double, color: Color, modifier: Modifier) {
    val track = cs.surfaceContainerHighest
    Canvas(modifier) {
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        fun x(v: Double) = ((v - lo) / span).toFloat().coerceIn(0f, 1f) * size.width
        val mid = size.height / 2
        drawLine(track, Offset(0f, mid), Offset(size.width, mid), 2.dp.toPx(), cap = StrokeCap.Round)
        val q1 = x(CfMath.percentile(values, 0.25))
        val q3 = x(CfMath.percentile(values, 0.75))
        val med = x(CfMath.percentile(values, 0.5))
        drawLine(color, Offset(x(values.min()), mid), Offset(x(values.max()), mid), 2.dp.toPx(), cap = StrokeCap.Round)
        drawRoundRect(color.copy(alpha = 0.35f), Offset(q1, 0f), androidx.compose.ui.geometry.Size(maxOf(q3 - q1, 2f), size.height), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
        drawLine(color, Offset(med, 0f), Offset(med, size.height), 3.dp.toPx(), cap = StrokeCap.Round)
        values.forEach { drawCircle(color, 2.dp.toPx(), Offset(x(it), mid)) }
    }
}

@Composable
private fun LatencyMeasurements(s: CfSnapshot) {
    val rows = listOf(S(R.string.idle_2) to s.latencyPoints, S(R.string.during_download) to s.downLoadedPoints, S(R.string.during_upload) to s.upLoadedPoints).filter { it.second.isNotEmpty() }
    if (rows.isEmpty()) return
    val all = rows.flatMap { it.second }
    val hi = all.max() * 1.05 + 1
    InfoList(S(R.string.latency_measurements)) {
        rows.forEach { (label, v) ->
            androidx.compose.material3.HorizontalDivider(color = cs.outlineVariant, thickness = 1.dp)
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), style = rf(13, 18, 600))
                    Text(S(R.string.median_ms, msOf(CfMath.percentile(v, 0.5))), style = rf(12, 16, 600, tnum = true), color = cs.onSurfaceVariant)
                }
                BoxPlot(v, 0.0, hi, AntTheme.accent.accent, Modifier.padding(top = 6.dp).fillMaxWidth().height(18.dp))
                Text(
                    S(R.string.measurements_min_max_ms_jitter_ms, v.size, msOf(v.min()), msOf(v.max()), msOf(CfMath.jitter(v))),
                    Modifier.padding(top = 4.dp), style = rf(11, 14, tnum = true), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PacketLossMeasurements(s: CfSnapshot) {
    val live = s.packetLossLive
    if (live == null && s.packetLossError == null) return
    InfoList(S(R.string.packet_loss_measurements)) {
        androidx.compose.material3.HorizontalDivider(color = cs.outlineVariant, thickness = 1.dp)
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            if (s.packetLossError != null || live == null) {
                Text(S(R.string.packet_loss_could_not_be_measured), style = rf(13, 18), color = cs.onSurfaceVariant)
                s.packetLossError?.let { Text(it, Modifier.padding(top = 4.dp), style = rf(11, 14), color = cs.onSurfaceVariant) }
                return@Column
            }
            val good = AntTheme.net.good
            val bad = cs.error
            val pending = cs.surfaceContainerHighest
            val lost = if (s.packetLoss != null) live.lost.size else 0
            val waiting = live.sent - live.received - lost
            Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(pending)) {
                Row(Modifier.fillMaxWidth()) {
                    if (live.received > 0) Box(Modifier.weight(live.received.toFloat()).height(14.dp).background(good))
                    if (lost > 0) Box(Modifier.weight(lost.toFloat()).height(14.dp).background(bad))
                    val rest = live.total - live.received - lost
                    if (rest > 0) Box(Modifier.weight(rest.toFloat()).height(14.dp))
                }
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Legend(good, S(R.string.received_4, live.received))
                Legend(bad, S(R.string.lost_2, lost))
                if (s.packetLoss == null) Legend(pending, S(R.string.in_transit, waiting.coerceAtLeast(0)))
            }
            Text(S(R.string.udp_packets_sent_through_cloudflare_s, live.sent, live.total), Modifier.padding(top = 6.dp), style = rf(11, 14), color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(text, style = rf(12, 16, 600, tnum = true), color = cs.onSurfaceVariant)
    }
}
