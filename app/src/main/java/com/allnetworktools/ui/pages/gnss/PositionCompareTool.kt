package com.allnetworktools.ui.pages.gnss

import android.graphics.Canvas
import android.graphics.Paint
import android.location.Location
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.data.PositionSet
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.BlinkDot
import com.allnetworktools.ui.components.IconCircleButton
import com.allnetworktools.ui.components.LiveChart
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.TileGrid
import com.allnetworktools.ui.components.MetricTile
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.ToolEmpty
import com.allnetworktools.util.fmt
import com.allnetworktools.util.plural
import kotlin.math.roundToInt
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import com.allnetworktools.R
import com.allnetworktools.util.S
import com.allnetworktools.util.P

/** Distance history between the satellite and network positions, kept while the app is open. */
class PositionCompareController {
    val gnssToNetwork = mutableStateListOf<Float>()

    fun feed(set: PositionSet) {
        val g = set.gnss
        val n = set.network
        if (g == null || n == null) return
        gnssToNetwork.add(g.distanceTo(n))
        while (gnssToNetwork.size > 90) gnssToNetwork.removeAt(0)
    }
}

private enum class Source(val label: String, val icon: String) {
    Gnss("Satellites (GNSS)", Sym.SatelliteAlt),
    Network(S(R.string.network_wi_fi_towers), Sym.Wifi),
    Fused(S(R.string.fused_google), Sym.MyLocation),
}

private fun PositionSet.of(s: Source): Location? = when (s) { Source.Gnss -> gnss; Source.Network -> network; Source.Fused -> fused }

private fun ageS(l: Location) = ((SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000_000L).coerceAtLeast(0)

private fun dist(a: Location?, b: Location?) = if (a == null || b == null) null else a.distanceTo(b)

private fun meters(m: Float) = if (m >= 1000) "${fmt(m / 1000, 1)} km" else "${fmt(m)} m"

private fun compass(bearing: Float): String {
    val dirs = listOf("nord", S(R.string.north_east), "est", S(R.string.south_east), "sud", S(R.string.south_west), "ouest", S(R.string.north_west))
    return dirs[(((bearing % 360 + 360) % 360) / 45f).roundToInt() % 8]
}

@Composable
fun PositionCompareTool(vm: MainViewModel) {
    val set by vm.positions.collectAsStateWithLifecycle()
    val scan by vm.wifiScan.collectAsStateWithLifecycle()
    val cell by vm.cell.collectAsStateWithLifecycle()
    val actions = LocalActions.current
    val c = vm.tools.positionCompare
    val net = AntTheme.net
    LaunchedEffect(set) { c.feed(set) }
    val colors = mapOf(Source.Gnss to net.gnss.accent, Source.Network to net.wifi.accent, Source.Fused to net.bt.accent)
    val gn = dist(set.gnss, set.network)
    PageColumn {
        val acc = AntTheme.accent
        HeroCard {
            if (gn == null) {
                Text(S(R.string.position_comparison), style = gs(24, 30, 500))
                Text(
                    when {
                        set.gnss == null && set.network == null -> S(R.string.waiting_for_the_first_positions)
                        set.gnss == null -> S(R.string.network_position_received_gnss_is_still)
                        else -> S(R.string.gnss_position_received_no_network_position)
                    },
                    Modifier.padding(top = 4.dp), style = rf(14, 20),
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BlinkDot(net.good, 8.dp, 1200)
                    Text(S(R.string.gnss_network_offset), style = rf(13, 18, 600))
                }
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (gn >= 1000) fmt(gn / 1000, 1) else fmt(gn), style = gs(56, 60, 500, -1.5f, tnum = true))
                    Text(if (gn >= 1000) "km" else "m", Modifier.padding(bottom = 8.dp), style = rf(20, 26, 500))
                }
                val g = set.gnss!!
                val n = set.network!!
                val tol = (g.accuracy.takeIf { g.hasAccuracy() } ?: 0f) + (n.accuracy.takeIf { n.hasAccuracy() } ?: 0f)
                Text(
                    buildString {
                        append(S(R.string.the_network_position_is_to_the, meters(gn), compass(g.bearingTo(n))))
                        append(if (gn <= tol) S(R.string.that_is_within_the_combined_error, fmt(tol)) else S(R.string.that_is_beyond_the_combined_error, fmt(tol)))
                    },
                    Modifier.padding(top = 4.dp), style = rf(14, 20),
                )
            }
        }
        val points = Source.entries.mapNotNull { s -> set.of(s)?.let { s to it } }
        if (points.isNotEmpty()) {
            SectionCard(shape = RoundedCornerShape(28.dp), padding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
                PositionMap(points.map { (s, l) -> Triple(GeoPoint(l.latitude, l.longitude), if (l.hasAccuracy()) l.accuracy else 0f, colors.getValue(s).toArgb()) })
                Row(Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    points.forEach { (s, _) ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(colors.getValue(s)))
                            Text(s.label.substringBefore(" ("), style = rf(12, 16), color = cs.onSurfaceVariant)
                        }
                    }
                }
                Text(S(R.string.the_circles_show_the_accuracy_each), Modifier.padding(horizontal = 8.dp), style = rf(12, 16), color = cs.onSurfaceVariant)
            }
        }
        Source.entries.forEach { s -> SourceCard(s, set.of(s), colors.getValue(s), s == Source.Network, scan?.size, cell.state?.serving?.let { "${it.tech.short} ${it.band ?: ""}".trim() }) }
        if (set.gnss != null || set.network != null || set.fused != null) {
            val g = set.gnss
            val n = set.network
            val f = set.fused
            TileGrid(
                listOfNotNull(
                    dist(g, f)?.let { Triple(S(R.string.gnss_fused), meters(it), "") },
                    dist(n, f)?.let { Triple(S(R.string.network_fused), meters(it), "") },
                    if (g != null && n != null && g.hasAltitude() && n.hasAltitude()) Triple(S(R.string.altitude_difference), "${fmt(kotlin.math.abs(g.altitude - n.altitude))} m", "") else null,
                    if (g != null && n != null && g.hasAccuracy() && n.hasAccuracy()) Triple(S(R.string.gnss_network_accuracy), "±${fmt(g.accuracy)} / ±${fmt(n.accuracy)}", "m") else null,
                ),
            ) { (k, v, u), m -> MetricTile(k, v, u, modifier = m) }
        }
        if (c.gnssToNetwork.size >= 3) {
            SectionCard {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(S(R.string.gnss_network_offset), Modifier.weight(1f), style = rf(16, 22, 600))
                    Text(S(R.string.avg_m_max_m, fmt(c.gnssToNetwork.average()), fmt(c.gnssToNetwork.max())), style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant)
                }
                val hi = (kotlin.math.ceil((c.gnssToNetwork.max() + 5f) / 10f) * 10f).coerceAtLeast(20f)
                LiveChart(c.gnssToNetwork.toList(), 0f, hi, hi / 4f, acc.accent, Modifier.padding(top = 10.dp), capacity = 90, morphMs = 400)
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = acc.accent)
                Text(
                    S(R.string.the_network_position_is_computed_by) +
                        S(R.string.it_is_often_accurate_to_2050),
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SourceCard(s: Source, l: Location?, color: Color, network: Boolean, wifiCount: Int?, cellLabel: String?) {
    val actions = LocalActions.current
    SectionCard(onClick = l?.let { { actions.copy("Position", "%.6f, %.6f".format(java.util.Locale.US, it.latitude, it.longitude)) } }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                Symbol(s.icon, size = 22.dp, filled = true, tint = if (l == null) cs.surface else Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(s.label, style = rf(15, 20, 600))
                Text(
                    l?.let { "%.5f° %s · %.5f° %s".format(java.util.Locale.getDefault(), kotlin.math.abs(it.latitude), if (it.latitude >= 0) "N" else "S", kotlin.math.abs(it.longitude), if (it.longitude >= 0) "E" else "O") } ?: S(R.string.no_position_received),
                    style = rf(13, 18, tnum = true), color = cs.onSurfaceVariant,
                )
            }
            if (l != null) Text(S(R.string.s_ago, ageS(l)), style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant)
        }
        if (l != null) {
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fun chip(t: String) = @Composable {
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(cs.surfaceContainerHigh).padding(horizontal = 10.dp, vertical = 6.dp)) { Text(t, style = rf(12, 16, 500, tnum = true)) }
                }
                if (l.hasAccuracy()) chip("±${fmt(l.accuracy)} m")()
                if (l.hasAltitude()) chip("${fmt(l.altitude)} m alt.")()
                if (l.hasSpeed() && l.speed > 0.3f) chip("${fmt(l.speed * 3.6f)} km/h")()
                val sats = l.extras?.getInt("satellites", -1) ?: -1
                if (s == Source.Gnss && sats >= 0) chip("$sats ${P(R.plurals.p_satellites, sats)}")()
            }
        }
        if (network) {
            Text(
                listOfNotNull(wifiCount?.let { "$it ${P(R.plurals.p_visible_wi_fi_networks, it)}" }, cellLabel?.let { S(R.string.cell_3, it) }).joinToString(" · ").ifEmpty { S(R.string.computed_from_wi_fi_and_cell) },
                Modifier.padding(top = 8.dp), style = rf(12, 16), color = cs.onSurfaceVariant,
            )
        }
    }
}

/** Dots (with accuracy circles below) for each source, on OpenStreetMap tiles. */
private class DotsOverlay : Overlay() {
    var dots: List<Pair<GeoPoint, Int>> = emptyList()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pt = android.graphics.Point()

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val d = map.context.resources.displayMetrics.density
        dots.forEach { (g, color) ->
            map.projection.toPixels(g, pt)
            fill.color = 0xFFFFFFFF.toInt()
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 8 * d, fill)
            fill.color = color
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 6 * d, fill)
        }
    }
}

@Composable
private fun PositionMap(sources: List<Triple<GeoPoint, Float, Int>>) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dark = com.allnetworktools.ui.theme.isDarkTheme(AntTheme.settings)
    val overlay = remember { DotsOverlay() }
    var fitted by remember { mutableStateOf(false) }
    val map = remember {
        configureOsm(context)
        TouchMapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            maxZoomLevel = 20.0
            controller.setZoom(17.0)
            sources.firstOrNull()?.first?.let { controller.setCenter(it) }
        }
    }
    fun fit() {
        val pts = sources.flatMap { (g, acc, _) ->
            val r = acc.coerceAtLeast(10f).toDouble()
            org.osmdroid.views.overlay.Polygon.pointsAsCircle(g, r)
        }
        // zoomToBoundingBox never returns on a view that has no size yet.
        if (pts.isEmpty() || map.width == 0 || map.height == 0) return
        val box = BoundingBox.fromGeoPoints(pts).increaseByScale(1.3f)
        map.zoomToBoundingBox(box, false, 24)
        if (map.zoomLevelDouble > 19.0) map.controller.setZoom(19.0)
    }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        map.onResume()
        onDispose { lifecycle.removeObserver(obs); map.onPause(); map.onDetach() }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(1.2f).clip(RoundedCornerShape(20.dp))) {
        AndroidView(
            factory = { map },
            modifier = Modifier.fillMaxSize(),
            update = { m ->
                m.overlays.clear()
                // Larger circles first so the smaller ones stay visible on top.
                sources.sortedByDescending { it.second }.forEach { (g, acc, color) ->
                    if (acc > 0f) {
                        m.overlays += Polygon().apply {
                            points = Polygon.pointsAsCircle(g, acc.toDouble())
                            fillPaint.color = (color and 0x00FFFFFF) or 0x22000000
                            setStrokeColor((color and 0x00FFFFFF) or 0xAA000000.toInt())
                            setStrokeWidth(2f)
                        }
                    }
                }
                overlay.dots = sources.map { it.first to it.third }
                m.overlays += overlay
                m.overlayManager.tilesOverlay.setColorFilter(if (dark) com.allnetworktools.ui.pages.gnss.darkTilesFilter else null)
                if (!fitted) { fitted = true; if (m.width > 0 && m.height > 0) fit() else m.addOnFirstLayoutListener { _, _, _, _, _ -> fit() } }
                m.invalidate()
            },
        )
        Column(Modifier.align(Alignment.TopEnd).padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val bg = cs.surface.copy(alpha = 0.92f)
            IconCircleButton(Sym.Add, { map.controller.zoomIn() }, size = 36.dp, bg = bg, tint = cs.onSurface)
            IconCircleButton(Sym.Remove, { map.controller.zoomOut() }, size = 36.dp, bg = bg, tint = cs.onSurface)
            IconCircleButton(Sym.MyLocation, { fit() }, size = 36.dp, bg = bg, tint = AntTheme.accent.accent)
        }
        Text(
            S(R.string.openstreetmap_contributors),
            Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(cs.surface.copy(alpha = 0.85f))
                .clickable { LocalActionsHolder.openCopyright(context) }.padding(horizontal = 6.dp, vertical = 2.dp),
            style = rf(10, 13, 500), color = cs.onSurface,
        )
    }
}

private object LocalActionsHolder {
    fun openCopyright(context: android.content.Context) {
        runCatching {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.openstreetmap.org/copyright")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
