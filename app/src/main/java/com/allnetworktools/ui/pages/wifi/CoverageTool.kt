package com.allnetworktools.ui.pages.wifi

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.allnetworktools.data.CoverageCell
import com.allnetworktools.data.CoverageMap
import com.allnetworktools.data.CoveragePalette
import com.allnetworktools.data.PaintedFloor
import com.allnetworktools.data.CoverageSample
import com.allnetworktools.data.StepDetector
import com.allnetworktools.data.StepWalker
import com.allnetworktools.data.WifiConnection
import com.allnetworktools.data.magnitude
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.IconCircleButton
import com.allnetworktools.ui.components.InfoList
import com.allnetworktools.ui.components.InfoRow
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.TileGrid
import com.allnetworktools.ui.components.MetricTile
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.pages.TopBarAction
import com.allnetworktools.ui.pages.wifi.ArCoverage.activity
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
import com.allnetworktools.ui.tools.ToolEmpty
import com.allnetworktools.util.fmt
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import kotlin.math.max
import com.allnetworktools.R
import com.allnetworktools.util.S

enum class CoverageMode { Ar, Steps }

/**
 * Walk-to-map Wi-Fi coverage: the phone's position comes from AR tracking (camera, motion sensors and depth) or,
 * without ARCore, from counted steps and the compass; a measurement is taken every 40 cm walked. Kept in memory only.
 */
class CoverageController {
    val samples = mutableStateListOf<CoverageSample>()
    var mode by mutableStateOf(CoverageMode.Ar)
    var running by mutableStateOf(false)
    var x by mutableStateOf(0f)
    var z by mutableStateOf(0f)
    var heading by mutableStateOf(0f)
    var tracking by mutableStateOf<TrackingState?>(null)
    var failure by mutableStateOf(TrackingFailureReason.NONE)
    var depth by mutableStateOf(false)
    var tof by mutableStateOf(false)
    var arError by mutableStateOf<String?>(null)
    /** Checked once when the tool opens: ARCore's answer can take a moment. */
    var support by mutableStateOf<ArSupport?>(null)
    /** The floor painted in AR, 10 cm tiles; [floorVersion] changes when it does. */
    val floor = PaintedFloor()
    var floorVersion by mutableIntStateOf(0)
    private var rssi = -127
    private var bssid: String? = null

    fun signal(rssi: Int, bssid: String?) { this.rssi = rssi; this.bssid = bssid }

    fun start(mode: CoverageMode) {
        this.mode = mode
        samples.clear()
        floor.clear()
        floorVersion++
        x = 0f; z = 0f
        tracking = null
        arError = null
        running = true
    }

    fun stop() { running = false }

    fun clear() {
        samples.clear()
        floor.clear()
        floorVersion++
        running = false
    }

    fun moved(x: Float, z: Float, floorY: Float = 0f) {
        this.x = x; this.z = z
        if (running && rssi > -127 && CoverageMap.shouldSample(samples.lastOrNull(), x, z)) {
            samples += CoverageSample(x, z, rssi, bssid, System.currentTimeMillis(), floorY)
        }
    }

    fun onArPose(p: ArPose) {
        tracking = p.tracking
        failure = p.failure
        depth = p.depth
        heading = p.headingRad
        if (p.tracking == TrackingState.TRACKING) {
            moved(p.x, p.z, p.floorY)
            if (running && rssi > -127) {
                floor.paint(p.x, p.z, p.floorY, rssi)
                floorVersion++
            }
        }
    }
}

@Composable
fun CoverageTool(c: CoverageController, conn: WifiConnection?) {
    val context = LocalContext.current
    val actions = LocalActions.current
    LaunchedEffect(Unit) {
        while (c.support?.arcore?.isTransient != false) {
            c.support = ArCoverage.support(context)
            if (c.support?.arcore?.isTransient == true) kotlinx.coroutines.delay(300) else break
        }
    }
    val support = c.support ?: ArSupport(null, false)
    LaunchedEffect(conn?.rssi, conn?.bssid) { c.signal(conn?.rssi ?: -127, conn?.bssid) }
    if (c.samples.isNotEmpty() && !c.running) {
        TopBarAction(Sym.Download) {
            val snapshot = c.samples.toList()
            actions.saveFile(S(R.string.wifi_coverage_csv, System.currentTimeMillis() / 1000), "text/csv") { CoverageMap.csv(snapshot).toByteArray() }
        }
    }
    var cameraOk by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        cameraOk = ok
        if (ok) c.start(CoverageMode.Ar)
    }
    PageColumn {
        if (conn == null) {
            ToolEmpty(Sym.WifiOff, S(R.string.no_wi_fi), S(R.string.connect_to_the_wi_fi_network), null)
            return@PageColumn
        }
        val arPossible = support.possible
        if (c.running && c.mode == CoverageMode.Ar) ArScreen(c, conn)
        SignalHero(c, conn)
        if (c.running && c.mode == CoverageMode.Steps) StepTracking(c)
        if (c.samples.isNotEmpty() || c.running) CoverageMapCard(c)
        if (c.samples.isNotEmpty()) StatsTiles(c.samples)
        if (!c.running) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (arPossible) AntFilterChip(S(R.string.augmented_reality), c.mode == CoverageMode.Ar, onClick = { c.mode = CoverageMode.Ar })
                AntFilterChip(S(R.string.step_counter), c.mode == CoverageMode.Steps || !arPossible, onClick = { c.mode = CoverageMode.Steps })
            }
        }
        val mode = if (arPossible) c.mode else CoverageMode.Steps
        when {
            c.running -> ToolButtons(
                ToolButton(S(R.string.finish), Sym.Check, BtnKind.Fill) { c.stop() },
                ToolButton(S(R.string.clear), Sym.Delete, BtnKind.OutlineOnSurface) { c.clear() },
            )
            else -> ToolButtons(
                ToolButton(if (c.samples.isEmpty()) S(R.string.start_walking) else S(R.string.new_map), Sym.PlayArrow, BtnKind.Big) {
                    if (mode == CoverageMode.Ar) {
                        val activity = context.activity()
                        val install = runCatching { activity?.let { ArCoreApk.getInstance().requestInstall(it, true) } }.getOrNull()
                        if (install == ArCoreApk.InstallStatus.INSTALL_REQUESTED) return@ToolButton
                        if (cameraOk) c.start(CoverageMode.Ar) else askCamera.launch(Manifest.permission.CAMERA)
                    } else {
                        c.start(CoverageMode.Steps)
                    }
                },
            )
        }
        c.arError?.let { Text(it, Modifier.padding(horizontal = 4.dp), style = rf(12, 16), color = cs.error) }
        if (!c.running) DevicePanel(support, c)
        Legend()
    }
}

@Composable
private fun SignalHero(c: CoverageController, conn: WifiConnection) {
    val net = AntTheme.net
    HeroCard {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(conn.rssi.toString(), style = gs(56, 60, 500, -1.5f, tnum = true))
            Text(S(R.string.dbm_here), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
        }
        Text(
            if (c.running) S(R.string.walk_normally_holding_the_phone_in) else S(R.string.move_around_to_map_it, conn.ssid ?: S(R.string.hidden_network)),
            style = rf(14, 20),
        )
        Row(Modifier.padding(top = 12.dp)) {
            HeroChip(
                when {
                    conn.rssi >= -60 -> S(R.string.excellent_signal)
                    conn.rssi >= -70 -> S(R.string.good_signal)
                    conn.rssi >= -80 -> S(R.string.weak_signal)
                    else -> S(R.string.very_weak_signal)
                },
                when {
                    conn.rssi >= -60 -> net.good
                    conn.rssi >= -75 -> net.fair
                    else -> cs.error
                },
            )
        }
    }
}

/** The camera with the measurements painted on the floor, and what ARCore needs when it is not tracking yet. */
/**
 * Full-screen AR, as in WiFiman: the camera dimmed, the walked floor painted in 10 cm tiles of the signal colour, a
 * blue dot and arrow under the phone, the dBm scale and the network at the bottom.
 */
@Composable
private fun ArScreen(c: CoverageController, conn: WifiConnection) {
    androidx.compose.ui.window.Dialog(
        { c.stop() },
        androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            ArCamera(c)
            val insets = androidx.compose.foundation.layout.WindowInsets.safeDrawing
            Box(Modifier.fillMaxWidth().align(Alignment.TopCenter).windowInsetsPadding(insets).padding(horizontal = 8.dp, vertical = 4.dp).height(48.dp)) {
                IconCircleButton(Sym.Close, { c.stop() }, Modifier.align(Alignment.CenterStart), tint = Color.White, size = 44.dp)
                Text(S(R.string.signal_strength), Modifier.align(Alignment.Center), style = rf(17, 22, 600), color = Color.White)
            }
            val hint = when {
                c.tracking == TrackingState.TRACKING -> if (c.floor.size == 0) S(R.string.walk_the_floor_colours_under_your) else null
                c.failure == TrackingFailureReason.INSUFFICIENT_LIGHT -> S(R.string.too_dark_turn_the_light_on)
                c.failure == TrackingFailureReason.EXCESSIVE_MOTION -> S(R.string.move_more_slowly)
                c.failure == TrackingFailureReason.INSUFFICIENT_FEATURES -> S(R.string.point_at_the_floor_or_textured)
                c.failure == TrackingFailureReason.CAMERA_UNAVAILABLE -> S(R.string.camera_used_by_another_app)
                else -> S(R.string.sweep_the_floor_slowly_with_the)
            }
            hint?.let {
                Text(
                    it,
                    Modifier.align(Alignment.Center).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 14.dp, vertical = 8.dp),
                    style = rf(14, 20, 600), color = Color.White,
                )
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                ScaleBar(Modifier.padding(horizontal = 8.dp, vertical = 10.dp))
                NetworkBar(conn)
            }
        }
    }
}

/** The dBm scale: a rounded colour bar with the values written on it. */
@Composable
private fun ScaleBar(modifier: Modifier) {
    val stops = CoveragePalette.stops
    Box(
        modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(13.dp))
            .background(Brush.horizontalGradient(listOf(Color(stops.first().second)) + stops.map { Color(it.second) })),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("dBm", Modifier.weight(1f), style = rf(12, 16, 700), color = Color.White)
            stops.forEach { (v, _) -> Text("$v", Modifier.weight(1f), style = rf(12, 16, 700, tnum = true), color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
    }
}

@Composable
private fun NetworkBar(conn: WifiConnection) {
    val color = Color(CoveragePalette.argb(conn.rssi.toFloat()))
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF1B1B1F)).windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            com.allnetworktools.ui.components.Symbol(Sym.Wifi, size = 20.dp, filled = true, tint = Color.White)
        }
        Column(Modifier.weight(1f)) {
            Text(conn.ssid ?: S(R.string.hidden_network), style = rf(15, 20, 600), color = Color.White, maxLines = 1)
            Text(
                listOfNotNull(conn.bssid?.uppercase(), bandOf(conn.frequency)).joinToString(" · "),
                style = rf(12, 16), color = Color.White.copy(alpha = 0.6f), maxLines = 1,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${conn.rssi} dBm", style = rf(17, 22, 700, tnum = true), color = color)
            Text(CoveragePalette.quality(conn.rssi), style = rf(12, 16, 600), color = Color.White.copy(alpha = 0.8f))
        }
    }
}

private fun bandOf(mhz: Int) = when {
    mhz in 2400..2500 -> S(R.string.n_2_4_ghz)
    mhz in 4900..5900 -> "5 GHz"
    mhz in 5925..7125 -> "6 GHz"
    else -> null
}

/** The ARCore session and its GL view, filling the dialog. */
@Composable
private fun ArCamera(c: CoverageController) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val created = remember { runCatching { ArCoverage.session(context) } }
    val session = created.getOrNull()
    if (session == null) {
        LaunchedEffect(Unit) {
            c.arError = S(R.string.augmented_reality_unavailable, created.exceptionOrNull()?.message ?: S(R.string.arcore_did_not_start))
            c.stop()
        }
        return
    }
    val (arSession, tof) = session
    LaunchedEffect(tof) { c.tof = tof }
    val renderer = remember { ArCoverageRenderer(arSession, { runCatching { context.display.rotation }.getOrDefault(0) }) { c.onArPose(it) } }
    LaunchedEffect(c.floorVersion) { renderer.tiles = c.floor.vertices() }
    var view by remember { mutableStateOf<GLSurfaceView?>(null) }
    DisposableEffect(lifecycle) {
        fun resume() { runCatching { arSession.resume() }.onFailure { c.arError = S(R.string.camera_unavailable, it.message) }; view?.onResume() }
        fun pause() { view?.onPause(); arSession.pause() }
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> resume()
                Lifecycle.Event.ON_PAUSE -> pause()
                else -> Unit
            }
        }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resume()
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            pause()
            arSession.close()
        }
    }
    AndroidView(
        factory = { ctx ->
            GLSurfaceView(ctx).apply {
                preserveEGLContextOnPause = true
                setEGLContextClientVersion(2)
                setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                view = this
                onResume()
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun StepTracking(c: CoverageController) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val sm = context.getSystemService(SensorManager::class.java)
        val detector = StepDetector()
        val walker = StepWalker()
        val rot = FloatArray(9)
        val orient = FloatArray(3)
        c.moved(0f, 0f)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                when (e.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rot, e.values)
                        SensorManager.getOrientation(rot, orient)
                        c.heading = orient[0]
                    }
                    Sensor.TYPE_ACCELEROMETER -> if (detector.feed(e.timestamp, magnitude(e.values[0], e.values[1], e.values[2]))) {
                        walker.step(c.heading)
                        c.moved(walker.x, walker.z)
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        onDispose { sm?.unregisterListener(listener) }
    }
}

@Composable
private fun CoverageMapCard(c: CoverageController) {
    val samples = c.samples.toList()
    val cells = remember(samples.size) { CoverageMap.cells(samples) }
    val painted = remember(c.floorVersion) { c.floor.tiles() }
    val path = cs.onSurface
    val me = AntTheme.accent.accent
    val grid = cs.outlineVariant
    fun color(rssi: Float) = Color(CoveragePalette.argb(rssi))
    SectionCard(padding = PaddingValues(8.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainerHigh)) {
            Canvas(Modifier.fillMaxSize()) {
                val cell = CoverageMap.CELL_M
                // Fit everything walked (and the phone) with a margin, at least 6 m across.
                val xs = samples.map { it.x } + c.x
                val zs = samples.map { it.z } + c.z
                val cx = (xs.min() + xs.max()) / 2
                val cz = (zs.min() + zs.max()) / 2
                val span = max(6f, max(xs.max() - xs.min(), zs.max() - zs.min()) + 2 * CoverageMap.REACH_M + 1f)
                val scale = size.minDimension / span
                fun at(x: Float, z: Float) = Offset(size.width / 2 + (x - cx) * scale, size.height / 2 + (z - cz) * scale)
                // 1 m grid.
                val g0x = kotlin.math.floor(cx - span / 2).toInt()
                val g0z = kotlin.math.floor(cz - span / 2).toInt()
                for (k in 0..span.toInt() + 1) {
                    val gx = at((g0x + k).toFloat(), 0f).x
                    val gz = at(0f, (g0z + k).toFloat()).y
                    drawLine(grid, Offset(gx, 0f), Offset(gx, size.height), 1f)
                    drawLine(grid, Offset(0f, gz), Offset(size.width, gz), 1f)
                }
                if (painted.isNotEmpty()) {
                    // In AR: the floor exactly as painted, tile by tile.
                    val t = PaintedFloor.TILE_M
                    painted.forEach { (i, j, v) -> drawRect(color(v).copy(alpha = 0.85f), at(i * t, j * t), Size(t * scale * 0.84f, t * scale * 0.84f)) }
                } else {
                    cells.forEach { k: CoverageCell ->
                        drawRect(color(k.rssi).copy(alpha = 0.75f), at(k.ix * cell, k.iz * cell), Size(cell * scale + 1f, cell * scale + 1f))
                    }
                }
                if (samples.size > 1) {
                    val p = Path()
                    samples.forEachIndexed { i, s -> val o = at(s.x, s.z); if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y) }
                    drawPath(p, path.copy(alpha = 0.45f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                samples.firstOrNull()?.let { drawCircle(path, 4.dp.toPx(), at(it.x, it.z)) }
                if (c.running) {
                    val o = at(c.x, c.z)
                    drawCircle(Color.White, 10.dp.toPx(), o)
                    drawCircle(me, 8.dp.toPx(), o)
                    rotate(Math.toDegrees(c.heading.toDouble()).toFloat(), o) {
                        val tip = Path().apply {
                            moveTo(o.x, o.y - 18.dp.toPx()); lineTo(o.x - 6.dp.toPx(), o.y - 9.dp.toPx()); lineTo(o.x + 6.dp.toPx(), o.y - 9.dp.toPx()); close()
                        }
                        drawPath(tip, me)
                    }
                }
                // Scale bar: 1 m.
                val y = size.height - 14.dp.toPx()
                drawLine(path, Offset(14.dp.toPx(), y), Offset(14.dp.toPx() + scale, y), 3.dp.toPx(), cap = StrokeCap.Round)
            }
            Text("1 m", Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 20.dp), style = rf(11, 14, 600), color = cs.onSurfaceVariant)
            if (samples.isEmpty()) {
                Text(
                    if (c.mode == CoverageMode.Ar) S(R.string.point_at_the_floor_then_walk) else S(R.string.walk_to_draw_the_map),
                    Modifier.align(Alignment.Center), style = rf(14, 20, 600), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatsTiles(samples: List<CoverageSample>) {
    val s = remember(samples.size) { CoverageMap.stats(samples) }
    TileGrid(
        listOf(
            Triple(Sym.GridOn, S(R.string.area), fmt(s.areaM2, 1)) to "m²",
            Triple(Sym.CheckCircle, S(R.string.good_signal), fmt(s.goodShare * 100, 0)) to "%",
            Triple(Sym.Wifi, S(R.string.medium), s.meanRssi?.let { fmt(it, 0) } ?: "—") to "dBm",
            Triple(Sym.Route, S(R.string.walked), fmt(s.distanceM, 1)) to "m",
        ),
    ) { (t, u), mod -> MetricTile(t.second, t.third, u, t.first, mod) }
    InfoList(S(R.string.detail)) {
        InfoRow(S(R.string.measurements), s.samples.toString())
        InfoRow(S(R.string.weakest_strongest), "${s.minRssi} / ${s.maxRssi} dBm")
        if (s.accessPoints > 1) InfoRow(S(R.string.access_points_crossed), s.accessPoints.toString())
    }
}

@Composable
private fun DevicePanel(support: ArSupport, c: CoverageController) {
    InfoList(S(R.string.sensors_used)) {
        InfoRow(
            S(R.string.augmented_reality_arcore),
            when {
                support.arcore == ArCoreApk.Availability.SUPPORTED_INSTALLED -> S(R.string.available)
                support.arcore == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD || support.arcore == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> S(R.string.to_install_from_the_play_store)
                support.arcore?.isUnknown == true -> S(R.string.verifying)
                else -> S(R.string.not_supported_step_counter)
            },
        )
        InfoRow(
            S(R.string.depth_sensor),
            when {
                c.tof -> S(R.string.infrared_tof_in_use)
                support.tofCamera -> S(R.string.infrared_tof_present)
                else -> S(R.string.estimated_by_the_camera)
            },
        )
        InfoRow(S(R.string.motion), S(R.string.gyroscope_accelerometer_compass))
    }
    Text(
        S(R.string.in_augmented_reality_the_camera_follows) +
            S(R.string.and_coloured_tiles_settle_on_the) +
            S(R.string.nothing_is_saved_the_map_disappears),
        Modifier.padding(horizontal = 4.dp), style = rf(12, 16), color = cs.onSurfaceVariant,
    )
}

@Composable
private fun Legend() {
    SectionCard {
        ScaleBar(Modifier)
        Text(S(R.string.above_67_dbm_comfortable_video_calls), Modifier.padding(top = 8.dp), style = rf(12, 16), color = cs.onSurfaceVariant)
    }
}
