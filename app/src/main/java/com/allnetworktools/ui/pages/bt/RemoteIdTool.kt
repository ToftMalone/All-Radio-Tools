package com.allnetworktools.ui.pages.bt

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.R
import com.allnetworktools.data.remoteid.Drone
import com.allnetworktools.data.remoteid.IdType
import com.allnetworktools.data.remoteid.RemoteId
import com.allnetworktools.data.remoteid.RemoteIdHub
import com.allnetworktools.data.remoteid.Status
import com.allnetworktools.data.remoteid.UaType
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.IconCircleButton
import com.allnetworktools.ui.components.InfoRow
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.pages.TopBarAction
import com.allnetworktools.ui.pages.gnss.TouchMapView
import com.allnetworktools.ui.pages.gnss.configureOsm
import com.allnetworktools.ui.pages.gnss.darkTilesFilter
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.HeroTile
import com.allnetworktools.ui.tools.ToolEmpty
import com.allnetworktools.util.S
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * Drones broadcasting Remote ID over Bluetooth: their identity, position, height and speed, and where their pilot
 * stands. The detector runs in a foreground service, so it keeps listening (and alerting) with the app closed.
 */
@Composable
fun RemoteIdTool(
    permitted: Boolean,
    me: Pair<Double, Double>?,
    onGrant: () -> Unit,
    onNotifications: () -> Unit,
) {
    val context = LocalContext.current
    val state by RemoteIdHub.state.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
    DisposableEffect(Unit) {
        RemoteIdHub.watching = true
        onDispose { RemoteIdHub.watching = false }
    }
    if (state.drones.isNotEmpty()) TopBarAction(Sym.Delete) { RemoteIdHub.clear(); selected = null }

    val drones = state.drones
    val active = drones.filter { it.active(now) }
    val closest = if (me == null) null else active.mapNotNull { d -> d.lat?.let { RemoteId.distanceM(me.first, me.second, it, d.lon!!) } }.minOrNull()

    fun toggle(on: Boolean) {
        when {
            !on -> RemoteIdHub.stop(context)
            !permitted -> onGrant()
            else -> { onNotifications(); RemoteIdHub.start(context) }
        }
    }

    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(S(R.string.detection_engine), style = rf(22, 28, 500))
                    Text(if (state.running) S(R.string.detection_on) else S(R.string.detection_off), style = rf(14, 20))
                }
                Switch(
                    checked = state.running, onCheckedChange = ::toggle,
                    thumbContent = if (state.running) ({ Symbol(Sym.Check, size = 16.dp, tint = cs.primary) }) else null,
                    colors = SwitchDefaults.colors(checkedTrackColor = cs.primary, checkedThumbColor = cs.onPrimary),
                )
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroTile(active.size.toString(), S(R.string.in_range), Modifier.weight(1f))
                HeroTile(drones.size.toString(), S(R.string.seen), Modifier.weight(1f))
                HeroTile(closest?.let(::distance) ?: "—", S(R.string.closest), Modifier.weight(1f))
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    !state.running -> HeroChip(S(R.string.detection_stopped), AntTheme.net.poor)
                    state.bluetoothOff -> HeroChip(S(R.string.bluetooth_is_off), AntTheme.net.poor)
                    else -> HeroChip(S(R.string.listening_remote_id), AntTheme.net.good, blink = true)
                }
                if (state.running && state.longRange) HeroChip(S(R.string.long_range_supported), AntTheme.net.good)
            }
        }

        if (!state.running && !permitted) {
            ToolEmpty(Sym.Drone, S(R.string.nearby_devices_permission_needed), S(R.string.remote_id_permission_body), S(R.string.allow), onGrant)
        }

        val located = drones.filter { it.located }
        if (located.isNotEmpty()) {
            DroneMap(located, selected, me, now, { selected = it }, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)))
        }

        if (drones.isEmpty()) {
            if (state.running) ToolEmpty(Sym.Drone, S(R.string.no_drone_yet), S(R.string.no_drone_body), null)
        } else {
            SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
                drones.forEachIndexed { i, d ->
                    if (i > 0) Hairline()
                    DroneRow(d, me, now, d.key == selected) { selected = if (selected == d.key) null else d.key }
                    if (d.key == selected) DroneDetails(d, me, now)
                }
            }
        }

        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(S(R.string.remote_id_how_it_works), style = rf(13, 18), color = cs.onSurfaceVariant)
            }
        }
    }
}

private fun distance(m: Double): String =
    if (m < 1000) "${m.roundToInt()} m" else String.format(Locale.getDefault(), "%.1f km", m / 1000)

private fun ago(now: Long, t: Long): String {
    val s = ((now - t) / 1000).coerceAtLeast(0)
    return if (s <= 1) S(R.string.just_now) else if (s < 120) S(R.string.s_ago, s.toString()) else S(R.string.min_ago, (s / 60).toString())
}

private fun uaName(t: UaType): String = when (t) {
    UaType.Aeroplane, UaType.Glider -> S(R.string.ua_aeroplane)
    UaType.Multirotor -> S(R.string.ua_multirotor)
    UaType.HybridVtol -> S(R.string.ua_vtol)
    UaType.FreeBalloon, UaType.CaptiveBalloon, UaType.Airship -> S(R.string.ua_balloon)
    else -> S(R.string.ua_other)
}

private fun statusName(s: Status): String? = when (s) {
    Status.Ground -> S(R.string.status_ground)
    Status.Airborne -> S(R.string.status_airborne)
    Status.Emergency -> S(R.string.status_emergency)
    Status.Failure -> S(R.string.status_failure)
    Status.Undeclared -> null
}

private fun idName(t: IdType): String = when (t) {
    IdType.Serial -> S(R.string.id_serial)
    IdType.Registration -> S(R.string.id_registration)
    IdType.UtmUuid -> S(R.string.id_utm)
    IdType.SessionId -> S(R.string.id_session)
    else -> S(R.string.identifier)
}

private fun kmh(ms: Double) = "${(ms * 3.6).roundToInt()} km/h"

@Composable
private fun DroneRow(d: Drone, me: Pair<Double, Double>?, now: Long, selected: Boolean, onClick: () -> Unit) {
    val acc = AntTheme.accent
    val live = d.active(now)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(if (selected) acc.accent else if (live) acc.container else cs.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Symbol(Sym.Drone, size = 22.dp, filled = true, tint = if (selected) acc.onAccent else if (live) acc.onContainer else cs.onSurfaceVariant)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.title, Modifier.weight(1f), style = rf(15, 20, 600), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (live) ago(now, d.lastSeen) else S(R.string.out_of_range), style = rf(12, 16, tnum = true), color = if (d.status == Status.Emergency) cs.error else cs.onSurfaceVariant)
            }
            Text(
                listOfNotNull(uaName(d.uaType), statusName(d.status), d.heightM?.let { "${it.roundToInt()} m" }, d.speedMs?.let(::kmh)).joinToString(" · "),
                style = rf(13, 18, tnum = true), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val dist = if (me != null && d.lat != null) distance(RemoteId.distanceM(me.first, me.second, d.lat, d.lon!!)) else null
            Text(
                listOfNotNull(dist, d.operatorId, d.rssi?.let { "$it dBm" }).joinToString(" · "),
                style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DroneDetails(d: Drone, me: Pair<Double, Double>?, now: Long) {
    Column(Modifier.padding(bottom = 8.dp)) {
        d.id?.let { InfoRow(idName(d.idType), it) }
        InfoRow(S(R.string.type), uaName(d.uaType))
        statusName(d.status)?.let { InfoRow(S(R.string.status), it) }
        d.altGeoM?.let { InfoRow(S(R.string.altitude), "${it.roundToInt()} m") }
        d.heightM?.let { InfoRow(if (d.heightAgl) S(R.string.height_above_ground) else S(R.string.height_above_takeoff), "${it.roundToInt()} m") }
        d.speedMs?.let { InfoRow(S(R.string.speed), kmh(it)) }
        d.verticalMs?.let { InfoRow(S(R.string.vertical_speed), String.format(Locale.getDefault(), "%+.1f m/s", it)) }
        d.directionDeg?.let { InfoRow(S(R.string.heading), "$it°") }
        if (me != null && d.lat != null) {
            val m = RemoteId.distanceM(me.first, me.second, d.lat, d.lon!!)
            val b = RemoteId.bearingDeg(me.first, me.second, d.lat, d.lon)
            InfoRow(S(R.string.distance), "${distance(m)} · ${b.roundToInt()}°")
        }
        if (d.operatorLat != null) {
            val fromMe = me?.let { distance(RemoteId.distanceM(it.first, it.second, d.operatorLat, d.operatorLon!!)) }
            val fromDrone = d.lat?.let { distance(RemoteId.distanceM(it, d.lon!!, d.operatorLat, d.operatorLon!!)) }
            InfoRow(S(R.string.pilot), listOfNotNull(fromMe?.let { S(R.string.from_you, it) }, fromDrone?.let { S(R.string.from_the_drone, it) }).joinToString(" · ").ifEmpty { "%.5f, %.5f".format(Locale.ROOT, d.operatorLat, d.operatorLon) })
        }
        d.operatorId?.let { InfoRow(S(R.string.operator_id), it) }
        d.selfId?.let { InfoRow(S(R.string.flight_description), it) }
        if (d.euClass != null || d.euCategory != null) {
            InfoRow(S(R.string.eu_class), listOfNotNull(d.euClass?.let { "C$it" }, d.euCategory?.let { listOf("Open", "Specific", "Certified")[it - 1] }).joinToString(" · "))
        }
        d.rssi?.let { InfoRow(S(R.string.signal), "$it dBm") }
        InfoRow(S(R.string.last_seen), ago(now, d.lastSeen))
    }
}

/** Drones as arrows along their course, their pilots as dots joined to them, the selected one with its track. */
private class DroneOverlay(private val density: Float) : Overlay() {
    var drones: List<Drone> = emptyList()
    var selected: String? = null
    var me: GeoPoint? = null
    var accent = 0
    var now = 0L
    var onTap: (String?) -> Unit = {}
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11 * density; isFakeBoldText = true }
    private val pt = android.graphics.Point()
    private val pt2 = android.graphics.Point()
    private val path = Path()
    private val dash = DashPathEffect(floatArrayOf(8f, 6f), 0f)

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = map.projection
        me?.let {
            proj.toPixels(it, pt)
            paint.style = Paint.Style.FILL
            paint.color = 0xFFFFFFFF.toInt(); c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 8 * density, paint)
            paint.color = 0xFF1E88E5.toInt(); c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 5 * density, paint)
        }
        drones.forEach { d ->
            val sel = d.key == selected
            val live = d.now(now)
            val color = if (d.status == Status.Emergency) 0xFFE53935.toInt() else if (live) accent else 0xFF9E9E9E.toInt()
            proj.toPixels(GeoPoint(d.lat!!, d.lon!!), pt)
            if (sel && d.track.size > 1) {
                path.reset()
                d.track.forEachIndexed { i, (la, lo) -> proj.toPixels(GeoPoint(la, lo), pt2); if (i == 0) path.moveTo(pt2.x.toFloat(), pt2.y.toFloat()) else path.lineTo(pt2.x.toFloat(), pt2.y.toFloat()) }
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 3 * density; paint.color = color; paint.alpha = 140
                c.drawPath(path, paint)
                paint.alpha = 255
            }
            if (d.operatorLat != null && d.operatorLon != null) {
                proj.toPixels(GeoPoint(d.operatorLat, d.operatorLon), pt2)
                if (sel) {
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = 2 * density; paint.color = color; paint.pathEffect = dash
                    c.drawLine(pt.x.toFloat(), pt.y.toFloat(), pt2.x.toFloat(), pt2.y.toFloat(), paint)
                    paint.pathEffect = null
                }
                paint.style = Paint.Style.FILL
                paint.color = 0xFFFFFFFF.toInt(); c.drawCircle(pt2.x.toFloat(), pt2.y.toFloat(), 7 * density, paint)
                paint.color = 0xFF6D4C41.toInt(); c.drawCircle(pt2.x.toFloat(), pt2.y.toFloat(), 4.5f * density, paint)
            }
            val size = (if (sel) 13 else 10) * density
            val a = Math.toRadians((d.directionDeg ?: 0).toDouble())
            fun x(dx: Double, dy: Double) = (pt.x + dx * cos(a) - dy * sin(a)).toFloat()
            fun y(dx: Double, dy: Double) = (pt.y + dx * sin(a) + dy * cos(a)).toFloat()
            path.reset()
            path.moveTo(x(0.0, -size.toDouble()), y(0.0, -size.toDouble()))
            path.lineTo(x(size * 0.6, size * 0.8), y(size * 0.6, size * 0.8))
            path.lineTo(x(0.0, size * 0.4), y(0.0, size * 0.4))
            path.lineTo(x(-size * 0.6, size * 0.8), y(-size * 0.6, size * 0.8))
            path.close()
            paint.style = Paint.Style.FILL; paint.color = color
            c.drawPath(path, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f * density; paint.color = 0xFFFFFFFF.toInt()
            c.drawPath(path, paint)
            val label = d.title
            text.color = 0xFFFFFFFF.toInt(); text.style = Paint.Style.STROKE; text.strokeWidth = 3 * density
            c.drawText(label, pt.x + size + 2 * density, pt.y - 2 * density, text)
            text.style = Paint.Style.FILL; text.color = 0xFF202124.toInt()
            c.drawText(label, pt.x + size + 2 * density, pt.y - 2 * density, text)
        }
    }

    private fun Drone.now(t: Long) = active(t)

    override fun onSingleTapConfirmed(e: MotionEvent, map: MapView): Boolean {
        val proj = map.projection
        fun dist(d: Drone): Float {
            proj.toPixels(GeoPoint(d.lat!!, d.lon!!), pt)
            return (pt.x - e.x) * (pt.x - e.x) + (pt.y - e.y) * (pt.y - e.y)
        }
        val hit = drones.minByOrNull(::dist)?.takeIf { dist(it) < (28 * density) * (28 * density) }
        onTap(hit?.key)
        return hit != null
    }
}

@Composable
private fun DroneMap(drones: List<Drone>, selected: String?, me: Pair<Double, Double>?, now: Long, onSelect: (String?) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dark = com.allnetworktools.ui.theme.isDarkTheme(AntTheme.settings)
    val density = LocalDensity.current.density
    val overlay = remember { DroneOverlay(density) }
    val accent = AntTheme.accent.accent.toArgb()
    var centred by remember { mutableStateOf(false) }
    val map = remember {
        configureOsm(context)
        TouchMapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 4.0
            maxZoomLevel = 19.0
            controller.setZoom(15.0)
            controller.setCenter(GeoPoint(46.6, 2.4))
        }
    }
    LaunchedEffect(me, drones.isNotEmpty()) {
        if (centred) return@LaunchedEffect
        val target = drones.firstOrNull()?.let { it.lat!! to it.lon!! } ?: me ?: return@LaunchedEffect
        map.controller.setCenter(GeoPoint(target.first, target.second))
        centred = true
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
                overlay.drones = drones
                overlay.selected = selected
                overlay.me = me?.let { GeoPoint(it.first, it.second) }
                overlay.accent = accent
                overlay.now = now
                overlay.onTap = onSelect
                if (overlay !in m.overlays) m.overlays += overlay
                m.overlayManager.tilesOverlay.setColorFilter(if (dark) darkTilesFilter else null)
                m.invalidate()
            },
        )
        Column(Modifier.align(Alignment.TopEnd).padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val bg = cs.surface.copy(alpha = 0.92f)
            IconCircleButton(Sym.Add, { map.controller.zoomIn() }, size = 36.dp, bg = bg, tint = cs.onSurface)
            IconCircleButton(Sym.Remove, { map.controller.zoomOut() }, size = 36.dp, bg = bg, tint = cs.onSurface)
            if (me != null) IconCircleButton(Sym.MyLocation, { map.controller.animateTo(GeoPoint(me.first, me.second), 16.0, 600L) }, size = 36.dp, bg = bg, tint = AntTheme.accent.accent)
        }
        Text(
            S(R.string.openstreetmap_contributors),
            Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(cs.surface.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 2.dp),
            style = rf(10, 12), color = cs.onSurfaceVariant,
        )
    }
}
