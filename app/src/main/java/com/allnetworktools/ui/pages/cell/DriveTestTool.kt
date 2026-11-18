package com.allnetworktools.ui.pages.cell

import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.R
import com.allnetworktools.data.DrivePoint
import com.allnetworktools.data.DriveQualities
import com.allnetworktools.data.DriveQuality
import com.allnetworktools.data.DriveTest
import com.allnetworktools.data.PermGroup
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.IconCircleButton
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.pages.gnss.TouchMapView
import com.allnetworktools.ui.pages.gnss.configureOsm
import com.allnetworktools.ui.pages.gnss.darkTilesFilter
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.BtnKind
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.ToolButton
import com.allnetworktools.ui.tools.ToolButtons
import com.allnetworktools.ui.tools.ToolError
import com.allnetworktools.util.Export
import com.allnetworktools.util.S
import java.util.Locale
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/** The colour of a quality, on the map and in the shares. */
internal fun qualityColor(q: DriveQuality): Color = when (q) {
    DriveQuality.Excellent -> Color(0xFF2E9E4F)
    DriveQuality.Good -> Color(0xFF8BC34A)
    DriveQuality.Fair -> Color(0xFFF5A623)
    DriveQuality.Poor -> Color(0xFFE53935)
    DriveQuality.NoService -> Color(0xFF424242)
}

@Composable
internal fun qualityName(q: DriveQuality): String = S(
    when (q) {
        DriveQuality.Excellent -> R.string.drive_q_excellent
        DriveQuality.Good -> R.string.drive_q_good
        DriveQuality.Fair -> R.string.drive_q_fair
        DriveQuality.Poor -> R.string.drive_q_poor
        DriveQuality.NoService -> R.string.drive_q_none
    },
)

/**
 * Drive test: the mobile signal recorded along a trip, drawn on the map in the colour of its quality, with the share
 * of the route in each quality and technology, and the export to KML (Google Earth) and CSV.
 */
@Composable
fun DriveTestTool(permitted: Boolean, onGrant: () -> Unit, notificationsGranted: Boolean) {
    val context = LocalContext.current
    val actions = LocalActions.current
    val running by DriveTest.running.collectAsStateWithLifecycle()
    val points by DriveTest.points.collectAsStateWithLifecycle()
    val sum = remember(points) { DriveQualities.summary(points) }
    PageColumn {
        if (!permitted) {
            ToolError(Sym.Lock, S(R.string.permission_needed), S(R.string.drive_permission), S(R.string.allow), onGrant)
            return@PageColumn
        }
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(String.format(Locale.getDefault(), "%.1f", sum.distanceKm), style = gs(56, 60, 500, -1.5f, tnum = true))
                Text("km", Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text(
                if (points.isEmpty()) S(R.string.drive_hero_empty)
                else S(R.string.drive_hero, points.size, duration(sum.durationS), sum.handovers),
                style = rf(14, 20),
            )
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running) HeroChip(S(R.string.recording), AntTheme.net.good, blink = true) else HeroChip(S(R.string.stopped), cs.outline)
                points.lastOrNull()?.let { p -> HeroChip(listOfNotNull(p.techLabel, p.levelDbm?.let { "$it dBm" }).joinToString(" · "), qualityColor(p.quality)) }
            }
        }
        if (running) ToolButtons(ToolButton(S(R.string.stop), Sym.Stop, BtnKind.Big) { DriveTest.stop(context) })
        else ToolButtons(
            ToolButton(if (points.isEmpty()) S(R.string.start_2) else S(R.string.new_session), Sym.PlayArrow, BtnKind.Big) {
                if (!notificationsGranted) actions.request(PermGroup.Notifications)
                DriveTest.start(context)
            },
        )
        if (points.isNotEmpty()) {
            DriveMap(points, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)))
            Legend()
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(S(R.string.drive_route_quality), style = rf(14, 20, 600))
                    ShareBar(DriveQuality.entries.map { it to (sum.quality[it] ?: 0.0) })
                    DriveQuality.entries.filter { (sum.quality[it] ?: 0.0) > 0 }.forEach { q ->
                        ShareLine(qualityName(q), sum.quality[q] ?: 0.0, qualityColor(q))
                    }
                    if (sum.techs.isNotEmpty()) {
                        Text(S(R.string.drive_route_network), Modifier.padding(top = 6.dp), style = rf(14, 20, 600))
                        sum.techs.entries.sortedByDescending { it.value }.forEach { (t, v) -> ShareLine(t, v, AntTheme.accent.accent) }
                    }
                    sum.worst?.let { w ->
                        Text(
                            S(R.string.drive_worst, listOfNotNull(w.levelDbm?.let { "$it dBm" } ?: qualityName(w.quality), w.techLabel, w.cell).joinToString(" · ")),
                            Modifier.padding(top = 6.dp), style = rf(12, 16), color = cs.onSurfaceVariant,
                        )
                    }
                }
            }
            if (!running) {
                ToolButtons(
                    ToolButton(S(R.string.drive_export_kml), Sym.Map, BtnKind.Outline) {
                        val snapshot = points
                        actions.saveFile("drive-test-${Export.stamp(System.currentTimeMillis())}.kml", "application/vnd.google-earth.kml+xml") {
                            DriveQualities.kml(snapshot, "Drive test").toByteArray()
                        }
                    },
                    ToolButton(S(R.string.export_to_csv), Sym.Download, BtnKind.Outline) {
                        val snapshot = points
                        actions.saveFile("drive-test-${Export.stamp(System.currentTimeMillis())}.csv", "text/csv") { DriveQualities.csv(snapshot).toByteArray() }
                    },
                )
                ToolButtons(ToolButton(S(R.string.mesh_clear), Sym.Delete, BtnKind.OutlineOnSurface) { DriveTest.clear() })
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(S(R.string.drive_how_it_works), style = rf(13, 18), color = cs.onSurfaceVariant)
            }
        }
    }
}

private fun duration(s: Long): String = if (s >= 3600) "%dh%02d".format(s / 3600, (s % 3600) / 60) else "%d min".format(s / 60)

@Composable
private fun Legend() {
    FlowRow(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DriveQuality.entries.forEach { q ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(qualityColor(q)))
                Text(qualityName(q), style = rf(11, 14), color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ShareBar(parts: List<Pair<DriveQuality, Double>>) {
    Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(cs.surfaceContainerHigh)) {
        parts.filter { it.second > 0 }.forEach { (q, v) -> Box(Modifier.weight(v.toFloat()).height(12.dp).background(qualityColor(q))) }
    }
}

@Composable
private fun ShareLine(label: String, share: Double, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(label, Modifier.weight(1f), style = rf(13, 18))
        Text("%.0f %%".format(Locale.getDefault(), share * 100), style = rf(13, 18, 600, tnum = true))
    }
}

private class RouteOverlay(private val density: Float) : Overlay() {
    var points: List<DrivePoint> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val a = android.graphics.Point()
    private val b = android.graphics.Point()

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow || points.isEmpty()) return
        val proj = map.projection
        paint.strokeWidth = 5 * density
        for (i in 1 until points.size) {
            val p = points[i - 1]
            val q = points[i]
            if (DriveQualities.distanceM(p.lat, p.lon, q.lat, q.lon) > 2_000) continue
            proj.toPixels(GeoPoint(p.lat, p.lon), a)
            proj.toPixels(GeoPoint(q.lat, q.lon), b)
            paint.color = qualityColor(p.quality).toArgb()
            c.drawLine(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat(), paint)
        }
        // Each change of cell is a white ring.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2 * density
        paint.color = 0xFFFFFFFF.toInt()
        points.filter { it.handover }.forEach { p ->
            proj.toPixels(GeoPoint(p.lat, p.lon), a)
            c.drawCircle(a.x.toFloat(), a.y.toFloat(), 5 * density, paint)
        }
        paint.style = Paint.Style.FILL
        points.last().let { p ->
            proj.toPixels(GeoPoint(p.lat, p.lon), a)
            paint.color = 0xFFFFFFFF.toInt(); c.drawCircle(a.x.toFloat(), a.y.toFloat(), 8 * density, paint)
            paint.color = 0xFF1E88E5.toInt(); c.drawCircle(a.x.toFloat(), a.y.toFloat(), 5 * density, paint)
        }
    }
}

@Composable
private fun DriveMap(points: List<DrivePoint>, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dark = com.allnetworktools.ui.theme.isDarkTheme(AntTheme.settings)
    val density = LocalDensity.current.density
    val overlay = remember { RouteOverlay(density) }
    var follow by remember { mutableStateOf(true) }
    val map = remember {
        configureOsm(context)
        TouchMapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 3.0
            maxZoomLevel = 18.0
            controller.setZoom(15.0)
        }
    }
    val last = points.lastOrNull()
    LaunchedEffect(last?.timeMs) {
        if (follow && last != null) map.controller.setCenter(GeoPoint(last.lat, last.lon))
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
    Box(modifier) {
        AndroidView(
            factory = { map },
            modifier = Modifier.fillMaxSize(),
            update = { m ->
                overlay.points = points
                if (overlay !in m.overlays) m.overlays += overlay
                m.overlayManager.tilesOverlay.setColorFilter(if (dark) darkTilesFilter else null)
                m.invalidate()
            },
        )
        Column(Modifier.align(Alignment.TopEnd).padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val bg = cs.surface.copy(alpha = 0.92f)
            IconCircleButton(Sym.Add, { map.controller.zoomIn() }, size = 36.dp, bg = bg, tint = cs.onSurface)
            IconCircleButton(Sym.Remove, { map.controller.zoomOut() }, size = 36.dp, bg = bg, tint = cs.onSurface)
            IconCircleButton(Sym.MyLocation, {
                follow = !follow
                last?.let { map.controller.animateTo(GeoPoint(it.lat, it.lon)) }
            }, size = 36.dp, bg = bg, tint = if (follow) AntTheme.accent.accent else cs.onSurface)
        }
        Text(
            S(R.string.openstreetmap_contributors),
            Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(cs.surface.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 2.dp),
            style = rf(10, 12), color = cs.onSurfaceVariant,
        )
    }
}
