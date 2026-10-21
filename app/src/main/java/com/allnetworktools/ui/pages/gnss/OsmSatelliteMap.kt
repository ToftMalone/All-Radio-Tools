package com.allnetworktools.ui.pages.gnss

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.allnetworktools.data.Constellation
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.IconCircleButton
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.constellationColor
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.rf
import java.io.File
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import com.allnetworktools.R
import com.allnetworktools.util.S

/** OpenStreetMap tiles need an identifying User-Agent; the tile cache stays in app storage. */
internal fun configureOsm(context: Context) {
    val c = Configuration.getInstance()
    if (c.userAgentValue == context.packageName) return
    c.userAgentValue = context.packageName
    c.osmdroidBasePath = File(context.cacheDir, "osmdroid")
    c.osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
    c.tileFileSystemCacheMaxBytes = 60L * 1024 * 1024
    c.tileFileSystemCacheTrimBytes = 45L * 1024 * 1024
}

/** A MapView that keeps its gestures instead of letting the scrolling page steal vertical drags. */
@SuppressLint("ViewConstructor")
internal class TouchMapView(context: Context) : MapView(context) {
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        return super.dispatchTouchEvent(ev)
    }
}

/** Draws the observer and each satellite at its sub-satellite point, above the OSM tiles. */
private class SatelliteOverlay(private val density: Float) : Overlay() {
    var observer: GeoPoint? = null
    var sats: List<SatOverhead> = emptyList()
    var colors: Map<Constellation, Int> = emptyMap()
    var accent = 0
    var ring = 0

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11 * density; isFakeBoldText = true }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11 * density; isFakeBoldText = true; style = Paint.Style.STROKE; strokeWidth = 3 * density }
    private val pt = android.graphics.Point()

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = map.projection
        val me = observer?.let { proj.toPixels(it, null) }
        if (me != null) {
            stroke.strokeWidth = 1.5f * density
            sats.filter { it.sat.used }.forEach { o ->
                proj.toPixels(GeoPoint(o.lat, o.lon), pt)
                stroke.color = (colors[o.sat.constellation] ?: accent) and 0x00FFFFFF or 0x55000000
                c.drawLine(me.x.toFloat(), me.y.toFloat(), pt.x.toFloat(), pt.y.toFloat(), stroke)
            }
        }
        val r = 6.5f * density
        val showLabels = map.zoomLevelDouble >= 3.0
        sats.forEach { o ->
            proj.toPixels(GeoPoint(o.lat, o.lon), pt)
            val col = colors[o.sat.constellation] ?: accent
            fill.color = ring
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), r + 2 * density, fill)
            if (o.sat.used) {
                fill.color = col
                c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), r, fill)
            } else {
                stroke.color = col; stroke.strokeWidth = 2.5f * density
                c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), r - density, stroke)
            }
            if (showLabels) {
                halo.color = ring; text.color = col
                val x = pt.x + r + 4 * density
                val y = pt.y + 4 * density
                c.drawText(o.sat.id, x, y, halo)
                c.drawText(o.sat.id, x, y, text)
            }
        }
        if (me != null) {
            fill.color = accent and 0x00FFFFFF or 0x40000000
            c.drawCircle(me.x.toFloat(), me.y.toFloat(), 16 * density, fill)
            fill.color = ring
            c.drawCircle(me.x.toFloat(), me.y.toFloat(), 7.5f * density, fill)
            fill.color = accent
            c.drawCircle(me.x.toFloat(), me.y.toFloat(), 5.5f * density, fill)
        }
    }
}

/** Inverts the light OSM style so the map follows the dark theme. */
internal val darkTilesFilter = ColorMatrixColorFilter(
    ColorMatrix(
        floatArrayOf(
            -0.9f, 0f, 0f, 0f, 235f,
            0f, -0.9f, 0f, 0f, 235f,
            0f, 0f, -0.9f, 0f, 240f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ).apply { postConcat(ColorMatrix().apply { setSaturation(0.35f) }) },
)

@Composable
fun OsmSatelliteMap(
    observer: Pair<Double, Double>,
    sats: List<SatOverhead>,
    modifier: Modifier = Modifier.fillMaxWidth().aspectRatio(1.25f).clip(RoundedCornerShape(20.dp)),
    onFullscreen: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val actions = LocalActions.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val acc = AntTheme.accent.accent
    val ring = cs.surface
    val dark = com.allnetworktools.ui.theme.isDarkTheme(AntTheme.settings)
    val colors = Constellation.entries.associateWith { constellationColor(it).toArgb() }
    val here = GeoPoint(observer.first, observer.second)
    val overlay = remember { SatelliteOverlay(context.resources.displayMetrics.density) }
    val map = remember {
        configureOsm(context)
        TouchMapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            // Low enough to see the whole world at once, even on a tall full-screen view.
            minZoomLevel = 0.0
            maxZoomLevel = 12.0
            isVerticalMapRepetitionEnabled = false
            setScrollableAreaLimitLatitude(MapView.getTileSystem().maxLatitude, MapView.getTileSystem().minLatitude, 0)
            controller.setZoom(1.6)
            controller.setCenter(GeoPoint(observer.first.coerceIn(-50.0, 50.0), observer.second))
            overlays.add(overlay)
        }
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
        onDispose {
            lifecycle.removeObserver(obs)
            map.onPause()
            map.onDetach()
        }
    }
    Box(modifier) {
        AndroidView(
            factory = { map },
            modifier = Modifier.fillMaxSize(),
            update = { m ->
                overlay.observer = here
                overlay.sats = sats
                overlay.colors = colors
                overlay.accent = acc.toArgb()
                overlay.ring = ring.toArgb()
                m.overlayManager.tilesOverlay.setColorFilter(if (dark) darkTilesFilter else null)
                m.invalidate()
            },
        )
        Column(Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val bg = cs.surface.copy(alpha = 0.92f)
            if (onFullscreen != null) IconCircleButton(Sym.Fullscreen, onFullscreen, size = 36.dp, bg = bg, tint = cs.onSurface)
            IconCircleButton(Sym.Add, { map.controller.zoomIn() }, size = 36.dp, bg = bg, tint = cs.onSurface)
            IconCircleButton(Sym.Remove, { map.controller.zoomOut() }, size = 36.dp, bg = bg, tint = cs.onSurface)
            IconCircleButton(Sym.MyLocation, { map.controller.animateTo(here, 5.0, 600L) }, size = 36.dp, bg = bg, tint = acc)
            IconCircleButton(Sym.Public, { map.controller.animateTo(GeoPoint(here.latitude.coerceIn(-50.0, 50.0), here.longitude), 0.5, 600L) }, size = 36.dp, bg = bg, tint = cs.onSurface)
        }
        Text(
            S(R.string.openstreetmap_contributors),
            Modifier.align(Alignment.BottomStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(cs.surface.copy(alpha = 0.85f))
                .clickable { actions.openUrl("https://www.openstreetmap.org/copyright") }.padding(horizontal = 6.dp, vertical = 2.dp),
            style = rf(10, 13, 500), color = cs.onSurface,
        )
    }
}
