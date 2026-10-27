package com.allnetworktools.ui.pages.wardrive

import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.R
import com.allnetworktools.data.Guard
import com.allnetworktools.data.MapNet
import com.allnetworktools.data.NetType
import com.allnetworktools.data.Wardrive
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.IconCircleButton
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.pages.ToolBack
import com.allnetworktools.ui.pages.gnss.TouchMapView
import com.allnetworktools.ui.pages.gnss.configureOsm
import com.allnetworktools.ui.pages.gnss.darkTilesFilter
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.mono
import com.allnetworktools.util.P
import com.allnetworktools.util.S
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

fun MapNet.guard(): Guard = if (type == NetType.Ble) Guard.Ble else Wardrive.guardOf(caps)

/** The wardriving database on a map: every network at the place it was heard strongest, coloured by its lock. */
@Composable
fun WardriveMapTool(vm: MainViewModel) {
    val context = LocalContext.current
    val st by Wardrive.state.collectAsStateWithLifecycle()
    var nets by remember { mutableStateOf<List<MapNet>?>(null) }
    // While it records, the map follows the database.
    LaunchedEffect(st.running) {
        do {
            nets = withContext(Dispatchers.IO) { Wardrive.db(context).mapNetworks() }
            delay(15_000)
        } while (st.running)
    }
    val positions by vm.positions.collectAsStateWithLifecycle()
    val loc = positions.gnss ?: positions.fused ?: positions.network
    WardriveMapScreen(nets, loc?.let { it.latitude to it.longitude })
}

@Composable
fun WardriveMapScreen(nets: List<MapNet>?, me: Pair<Double, Double>?) {
    var shown by remember { mutableStateOf(Guard.entries.toSet()) }
    var picked by remember { mutableStateOf<MapNet?>(null) }
    ToolBack(picked != null) { picked = null }
    val all = nets.orEmpty()
    val visible = remember(all, shown) { all.filter { it.guard() in shown } }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Guard.entries.forEach { g ->
                AntFilterChip(
                    guardLabel(g), g in shown, { shown = if (g in shown) shown - g else shown + g },
                    leadingDot = guardColor(g), trailing = all.count { it.guard() == g }.toString(),
                )
            }
        }
        Text(
            P(R.plurals.p_wardriving_map_count, visible.size, visible.size),
            Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = rf(12, 16), color = cs.onSurfaceVariant,
        )
        Box(Modifier.fillMaxWidth().weight(1f).padding(start = 16.dp, end = 16.dp, bottom = 120.dp).clip(RoundedCornerShape(28.dp))) {
            NetMap(visible, picked, me, onPick = { picked = it }, modifier = Modifier.fillMaxSize())
            picked?.let { n -> NetCard(n, Modifier.align(Alignment.BottomCenter).padding(10.dp)) }
            if (nets != null && all.isEmpty()) {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp).clip(RoundedCornerShape(24.dp)).background(cs.surface.copy(alpha = 0.94f)).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Symbol(Sym.Map, size = 32.dp, tint = AntTheme.accent.accent)
                    Text(S(R.string.wardriving_map_empty), Modifier.padding(top = 8.dp), style = rf(15, 20, 600))
                    Text(S(R.string.wardriving_map_empty_text), Modifier.padding(top = 4.dp), style = rf(13, 18), color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

private fun guardLabel(g: Guard) = when (g) {
    Guard.Open -> S(R.string.wardriving_open)
    Guard.Wep -> "WEP"
    Guard.Wpa -> "WPA"
    Guard.Wpa3 -> "WPA3"
    Guard.Ble -> "Bluetooth"
}

@Composable
private fun guardColor(g: Guard): Color {
    val net = AntTheme.net
    return when (g) {
        Guard.Open -> net.poor
        Guard.Wep -> net.fair
        Guard.Wpa -> net.good
        Guard.Wpa3 -> net.sdr.accent
        Guard.Ble -> net.bt.accent
    }
}

@Composable
private fun NetCard(n: MapNet, modifier: Modifier) {
    val context = LocalContext.current
    val vendor = remember(n.mac) { Wardrive.oui(context).vendor(n.mac) }
    val color = guardColor(n.guard())
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.surface).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            Modifier.width(52.dp).clip(RoundedCornerShape(16.dp)).background(color.copy(alpha = 0.16f)).padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Symbol(if (n.type == NetType.Ble) Sym.Bluetooth else if (n.guard() == Guard.Open) Sym.LockOpen else Sym.Lock, size = 22.dp, filled = true, tint = color)
            Text(guardLabel(n.guard()).take(5), style = rf(10, 12, 700), color = color, maxLines = 1)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(n.name.ifEmpty { S(R.string.hidden_network) }, Modifier.weight(1f), style = rf(15, 20, 600), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${n.level} dBm", style = gs(13, 18, 700, tnum = true), color = color)
            }
            Text(
                n.mac.lowercase(Locale.ROOT) + " · " + (if (n.type == NetType.Wifi) "${n.frequency} MHz" else "BLE"),
                style = mono(12, 17), color = cs.onSurfaceVariant, maxLines = 1,
            )
            if (n.type == NetType.Wifi && n.caps.isNotEmpty()) Text(Wardrive.shortCaps(n.caps), style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 1)
            if (vendor != null) Text(vendor, style = rf(12, 16, 500), color = AntTheme.accent.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                S(R.string.wardriving_last_seen, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(n.lastTime))),
                style = rf(12, 16), color = cs.onSurfaceVariant,
            )
        }
    }
}

/** One dot per network; only those on screen are drawn, the picked one larger with a ring. */
private class NetOverlay(private val density: Float) : Overlay() {
    var nets: List<MapNet> = emptyList()
    var colors: Map<Guard, Int> = emptyMap()
    var picked: MapNet? = null
    var me: GeoPoint? = null
    var onTap: (MapNet?) -> Unit = {}
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pt = android.graphics.Point()

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = map.projection
        val box = map.boundingBox
        val r = (if (map.zoomLevelDouble >= 16) 5f else if (map.zoomLevelDouble >= 13) 3.5f else 2.2f) * density
        paint.style = Paint.Style.FILL
        for (n in nets) {
            if (n.lat !in box.latSouth..box.latNorth || n.lon !in box.lonWest..box.lonEast) continue
            proj.toPixels(GeoPoint(n.lat, n.lon), pt)
            paint.color = colors[n.guard()] ?: 0xFF888888.toInt()
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), r, paint)
        }
        picked?.let { n ->
            proj.toPixels(GeoPoint(n.lat, n.lon), pt)
            paint.style = Paint.Style.FILL; paint.color = 0xFFFFFFFF.toInt()
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 10 * density, paint)
            paint.color = colors[n.guard()] ?: 0xFF888888.toInt()
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 7 * density, paint)
        }
        me?.let {
            proj.toPixels(it, pt)
            paint.style = Paint.Style.FILL
            paint.color = 0xFFFFFFFF.toInt(); c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 8 * density, paint)
            paint.color = 0xFF1E88E5.toInt(); c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 5 * density, paint)
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, map: MapView): Boolean {
        val proj = map.projection
        val box = map.boundingBox
        var best: MapNet? = null
        var bestD = (24 * density) * (24 * density)
        for (n in nets) {
            if (n.lat !in box.latSouth..box.latNorth || n.lon !in box.lonWest..box.lonEast) continue
            proj.toPixels(GeoPoint(n.lat, n.lon), pt)
            val d = (pt.x - e.x) * (pt.x - e.x) + (pt.y - e.y) * (pt.y - e.y)
            if (d < bestD) { bestD = d; best = n }
        }
        onTap(best)
        return best != null
    }
}

@Composable
private fun NetMap(nets: List<MapNet>, picked: MapNet?, me: Pair<Double, Double>?, onPick: (MapNet?) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dark = com.allnetworktools.ui.theme.isDarkTheme(AntTheme.settings)
    val density = LocalDensity.current.density
    val overlay = remember { NetOverlay(density) }
    val colors = Guard.entries.associateWith { guardColor(it).toArgb() }
    var centred by remember { mutableStateOf(false) }
    val map = remember {
        configureOsm(context)
        TouchMapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 3.0
            maxZoomLevel = 19.0
            controller.setZoom(15.0)
            controller.setCenter(GeoPoint(46.6, 2.4))
        }
    }
    LaunchedEffect(me, nets.isNotEmpty()) {
        if (centred) return@LaunchedEffect
        val target = me ?: nets.maxByOrNull { it.lastTime }?.let { it.lat to it.lon } ?: return@LaunchedEffect
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
                overlay.nets = nets
                overlay.colors = colors
                overlay.picked = picked
                overlay.me = me?.let { GeoPoint(it.first, it.second) }
                overlay.onTap = onPick
                if (overlay !in m.overlays) m.overlays += overlay
                m.overlayManager.tilesOverlay.setColorFilter(if (dark) darkTilesFilter else null)
                m.invalidate()
            },
        )
        Column(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val bg = cs.surface.copy(alpha = 0.92f)
            IconCircleButton(Sym.Add, { map.controller.zoomIn() }, size = 40.dp, bg = bg, tint = cs.onSurface)
            IconCircleButton(Sym.Remove, { map.controller.zoomOut() }, size = 40.dp, bg = bg, tint = cs.onSurface)
            if (me != null) IconCircleButton(Sym.MyLocation, { map.controller.animateTo(GeoPoint(me.first, me.second), 16.0, 600L) }, size = 40.dp, bg = bg, tint = AntTheme.accent.accent)
        }
        Text(
            S(R.string.openstreetmap_contributors),
            Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(cs.surface.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 2.dp),
            style = rf(10, 12), color = cs.onSurfaceVariant,
        )
    }
}
