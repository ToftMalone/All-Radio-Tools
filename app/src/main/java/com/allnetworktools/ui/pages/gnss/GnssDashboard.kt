package com.allnetworktools.ui.pages.gnss

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.data.Constellation
import com.allnetworktools.data.FixType
import com.allnetworktools.data.Satellite
import com.allnetworktools.data.Units
import com.allnetworktools.ui.components.BlinkDot
import com.allnetworktools.ui.components.CardHeader
import com.allnetworktools.ui.components.EmptyStateCard
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.TileGrid
import com.allnetworktools.ui.components.ValueTile
import com.allnetworktools.ui.components.fadeUp
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Motion
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.constellationColor
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.util.fmt
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import com.allnetworktools.R
import com.allnetworktools.util.S

private val Dirs = listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")

fun directionOf(heading: Float): String = Dirs[(((heading % 360 + 360) % 360) / 45f).let { Math.round(it) } % 8]

fun headingLabel(heading: Float): String = "${Math.round((heading % 360 + 360) % 360) % 360}° ${directionOf(heading)}"

fun coord(v: Double, pos: String, neg: String) = "${fmt(abs(v), 5)}° ${if (v >= 0) pos else neg}"

fun distanceText(meters: Float, units: Units, decimals: Int = 0): String =
    if (units == Units.Imperial) "${fmt(meters * 3.28084f, decimals)} ft" else "${fmt(meters, decimals)} m"

fun speedText(mps: Float, units: Units): String =
    if (units == Units.Imperial) "${fmt(mps * 2.23694f, 1)} mph" else "${fmt(mps * 3.6f, 1)} km/h"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GnssDashboard(vm: MainViewModel) {
    val g by vm.gnss.collectAsStateWithLifecycle()
    val roles = AntTheme.net.gnss
    val units = AntTheme.settings.units
    val loc = g.location
    PageColumn {
        Row(
            Modifier.fadeUp().fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(roles.container).padding(20.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Row(
                    Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).background(roles.accent).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BlinkDot(roles.onAccent, 8.dp)
                    Text(if (g.fix == FixType.None) S(R.string.searching) else g.fix.label, style = rf(13, 18, 700), color = roles.onAccent)
                }
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(g.used.size.toString(), style = gs(52, 56, 500, -1.5f, tnum = true), color = roles.onContainer)
                    Text("/ ${g.visible.size}", Modifier.padding(bottom = 6.dp), style = rf(18, 24, 500), color = roles.onContainer)
                }
                Text(S(R.string.satellites_used_visible), style = rf(13, 18), color = roles.onContainer)
            }
            Column(horizontalAlignment = Alignment.End) {
                HeroLine(S(R.string.accuracy), loc?.takeIf { it.hasAccuracy() }?.let { "±${distanceText(it.accuracy, units, 1)}" } ?: "—")
                HeroLine("HDOP", g.hdop?.let { fmt(it, 1) } ?: "—")
                HeroLine("TTFF", g.ttffMs?.let { "${fmt(it / 1000f, 1)} s" } ?: "—")
            }
        }
        SectionCard(shape = RoundedCornerShape(28.dp), onClick = { vm.navigate { it.copy(page = com.allnetworktools.Page.ToolPage(com.allnetworktools.model.Tool.Sky)) } }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(S(R.string.constellations), Modifier.weight(1f), style = rf(16, 22, 600))
                com.allnetworktools.ui.components.Symbol(Sym.ChevronRight, size = 22.dp, tint = cs.onSurfaceVariant)
            }
            if (g.visible.isEmpty()) {
                Text(S(R.string.searching_for_satellites_go_outside_under), Modifier.padding(top = 6.dp), style = rf(14, 20), color = cs.onSurfaceVariant)
            } else {
                FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Constellation.entries.forEach { c ->
                        val vis = g.visible.count { it.constellation == c }
                        if (vis > 0) {
                            Row(
                                Modifier.height(32.dp).clip(RoundedCornerShape(10.dp)).background(cs.surfaceContainerHigh).padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(constellationColor(c)))
                                Text(c.label, style = rf(12, 16, 500))
                                Text("${g.used.count { it.constellation == c }}/$vis", style = rf(12, 16, 700, tnum = true))
                            }
                        }
                    }
                }
                Text(S(R.string.sky_plot_and_world_map_in), Modifier.padding(top = 10.dp), style = rf(12, 16), color = cs.onSurfaceVariant)
            }
        }
        TileGrid(
            listOf(
                "Latitude" to (loc?.let { coord(it.latitude, "N", "S") } ?: "—"),
                "Longitude" to (loc?.let { coord(it.longitude, "E", "O") } ?: "—"),
                "Altitude" to (loc?.takeIf { it.hasAltitude() }?.let { "${distanceText(it.altitude.toFloat(), units)} (WGS84)" } ?: "—"),
                S(R.string.accuracy) to (loc?.takeIf { it.hasAccuracy() }?.let { "±${distanceText(it.accuracy, units, 1)}" } ?: "—"),
                S(R.string.speed) to (loc?.takeIf { it.hasSpeed() }?.let { speedText(it.speed, units) } ?: "—"),
                "Cap" to (loc?.takeIf { it.hasBearing() }?.let { "${Math.round(it.bearing)}°" } ?: "—"),
            ),
        ) { (k, v), mod -> ValueTile(k, v, mod) }
        if (g.satellites.isNotEmpty()) {
            SectionCard {
                CardHeader(S(R.string.c_n0_per_satellite)) { Text("dB-Hz", style = rf(12, 16), color = cs.onSurfaceVariant) }
                val bars = g.satellites.sortedWith(compareByDescending<Satellite> { it.used }.thenByDescending { it.cn0 }).take(16)
                Cn0Bars(bars)
            }
        }
    }
}

@Composable
private fun HeroLine(k: String, v: String) {
    val c = AntTheme.net.gnss.onContainer
    Row {
        Text("$k ", style = rf(13, 22), color = c)
        Text(v, style = rf(13, 22, 600, tnum = true), color = c)
    }
}

@Composable
fun SkyPlot(sats: List<Satellite>, height: androidx.compose.ui.unit.Dp = 320.dp) {
    val roles = AntTheme.net.gnss
    val track = cs.surfaceContainerHigh
    val line = cs.outlineVariant
    BoxWithConstraints(Modifier.fillMaxWidth().height(height)) {
        val w = maxWidth
        val r = (minOf(w, height) - 40.dp) / 2
        val cx = w / 2
        val cy = height / 2
        Canvas(Modifier.matchParentSize()) {
            val c = Offset(cx.toPx(), cy.toPx())
            val rp = r.toPx()
            drawCircle(track, rp, c)
            drawCircle(line, rp * 2 / 3, c, style = Stroke(1.dp.toPx()))
            drawCircle(line, rp / 3, c, style = Stroke(1.dp.toPx()))
            drawLine(line, Offset(c.x - rp, c.y), Offset(c.x + rp, c.y), 1.dp.toPx())
            drawLine(line, Offset(c.x, c.y - rp), Offset(c.x, c.y + rp), 1.dp.toPx())
        }
        @Composable
        fun label(t: String, x: androidx.compose.ui.unit.Dp, y: androidx.compose.ui.unit.Dp, strong: Boolean = false, small: Boolean = false) =
            Text(
                t, Modifier.offset(x - 12.dp, y - 8.dp).size(24.dp, 16.dp), textAlign = TextAlign.Center,
                style = rf(if (small) 9 else 12, 16, if (strong) 700 else 600), color = if (strong) roles.accent else cs.onSurfaceVariant,
            )
        label("N", cx, cy - r - 10.dp, strong = true)
        label("E", cx + r + 12.dp, cy)
        label("S", cx, cy + r + 10.dp)
        label("O", cx - r - 12.dp, cy)
        label("60°", cx + 14.dp, cy - r / 3 - 6.dp, small = true)
        label("30°", cx + 14.dp, cy - r * 2 / 3 - 6.dp, small = true)
        sats.forEach { s ->
            key(s.id) {
                val rr = (90f - s.elevation.coerceIn(0f, 90f)) / 90f
                val a = Math.toRadians(s.azimuth.toDouble())
                val target = Offset((cx.value + r.value * rr * sin(a)).toFloat(), (cy.value - r.value * rr * cos(a)).toFloat())
                val pos by animateOffsetAsState(target, tween(1000, easing = LinearEasing), label = "sat")
                val col = constellationColor(s.constellation)
                val size = 20.dp
                Box(
                    Modifier.offset { androidx.compose.ui.unit.IntOffset((pos.x.dp - size / 2).roundToPx(), (pos.y.dp - size / 2).roundToPx()) }.size(size).clip(CircleShape)
                        .background(if (s.used) col else track).border(2.dp, col, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(s.svid.toString(), style = rf(8, 10, 800), color = if (s.used) cs.surface else col, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun Cn0Bars(sats: List<Satellite>) {
    Row(
        Modifier.padding(top = 14.dp).fillMaxWidth().height(140.dp),
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        sats.forEach { s ->
            key(s.id) {
                val h by animateFloatAsState((s.cn0 / 50f).coerceIn(0.02f, 1f), Motion.standard(), label = "cn0")
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom,
                ) {
                    Text(Math.round(s.cn0).toString(), style = rf(9, 12, 600, tnum = true), color = cs.onSurfaceVariant, maxLines = 1)
                    Box(
                        Modifier.padding(top = 3.dp).fillMaxWidth().fillMaxHeight(h * 0.86f)
                            .graphicsLayer { alpha = if (s.used) 1f else 0.35f }
                            .clip(RoundedCornerShape(6.dp, 6.dp, 2.dp, 2.dp)).background(constellationColor(s.constellation)),
                    )
                }
            }
        }
    }
    androidx.compose.material3.HorizontalDivider(color = cs.outlineVariant)
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        sats.forEach { Text(it.id, Modifier.weight(1f), style = rf(8, 10), color = cs.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 1) }
    }
}
