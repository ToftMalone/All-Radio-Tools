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
import com.allnetworktools.data.CoverageSample
import com.allnetworktools.data.StepDetector
import com.allnetworktools.data.StepWalker
import com.allnetworktools.data.WifiConnection
import com.allnetworktools.data.magnitude
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.AntFilterChip
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
    private var rssi = -127
    private var bssid: String? = null

    fun signal(rssi: Int, bssid: String?) { this.rssi = rssi; this.bssid = bssid }

    fun start(mode: CoverageMode) {
        this.mode = mode
        samples.clear()
        x = 0f; z = 0f
        tracking = null
        arError = null
        running = true
    }

    fun stop() { running = false }

    fun clear() {
        samples.clear()
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
        if (p.tracking == TrackingState.TRACKING) moved(p.x, p.z, p.floorY)
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
            actions.saveFile("couverture-wifi-${System.currentTimeMillis() / 1000}.csv", "text/csv") { CoverageMap.csv(snapshot).toByteArray() }
        }
    }
    var cameraOk by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        cameraOk = ok
        if (ok) c.start(CoverageMode.Ar)
    }
    PageColumn {
        if (conn == null) {
            ToolEmpty(Sym.WifiOff, "Pas de Wi-Fi", "Connectez-vous au réseau Wi-Fi dont vous voulez cartographier la couverture.", null)
            return@PageColumn
        }
        val arPossible = support.possible
        if (c.running && c.mode == CoverageMode.Ar) {
            ArView(c, conn.rssi)
        } else if (!c.running || c.mode == CoverageMode.Steps) {
            SignalHero(c, conn)
        }
        if (c.running && c.mode == CoverageMode.Steps) StepTracking(c)
        if (c.samples.isNotEmpty() || c.running) CoverageMapCard(c)
        if (c.samples.isNotEmpty()) StatsTiles(c.samples)
        if (!c.running) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (arPossible) AntFilterChip("Réalité augmentée", c.mode == CoverageMode.Ar, onClick = { c.mode = CoverageMode.Ar })
                AntFilterChip("Podomètre", c.mode == CoverageMode.Steps || !arPossible, onClick = { c.mode = CoverageMode.Steps })
            }
        }
        val mode = if (arPossible) c.mode else CoverageMode.Steps
        when {
            c.running -> ToolButtons(
                ToolButton("Terminer", Sym.Check, BtnKind.Fill) { c.stop() },
                ToolButton("Effacer", Sym.Delete, BtnKind.OutlineOnSurface) { c.clear() },
            )
            else -> ToolButtons(
                ToolButton(if (c.samples.isEmpty()) "Commencer à marcher" else "Nouvelle carte", Sym.PlayArrow, BtnKind.Big) {
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
            Text("dBm ici", Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
        }
        Text(
            if (c.running) "Marchez normalement, téléphone tenu devant vous" else "${conn.ssid ?: "Réseau masqué"} · déplacez-vous pour cartographier",
            style = rf(14, 20),
        )
        Row(Modifier.padding(top = 12.dp)) {
            HeroChip(
                when {
                    conn.rssi >= -60 -> "Excellent signal"
                    conn.rssi >= -70 -> "Bon signal"
                    conn.rssi >= -80 -> "Signal faible"
                    else -> "Signal très faible"
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
@Composable
private fun ArView(c: CoverageController, rssi: Int) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val colors = intArrayOf(cs.error.toArgb(), AntTheme.net.fair.toArgb(), AntTheme.net.good.toArgb())
    val created = remember { runCatching { ArCoverage.session(context) } }
    val session = created.getOrNull()
    if (session == null) {
        LaunchedEffect(Unit) {
            c.arError = "Réalité augmentée indisponible : ${created.exceptionOrNull()?.message ?: "ARCore n'a pas démarré"}"
            c.stop()
        }
        return
    }
    val (arSession, tof) = session
    LaunchedEffect(tof) { c.tof = tof }
    val renderer = remember { ArCoverageRenderer(arSession, { runCatching { context.display.rotation }.getOrDefault(0) }, colors) { c.onArPose(it) } }
    renderer.samples = c.samples.toList()
    renderer.currentRssi = rssi
    var view by remember { mutableStateOf<GLSurfaceView?>(null) }
    DisposableEffect(lifecycle) {
        fun resume() { runCatching { arSession.resume() }.onFailure { c.arError = "Caméra indisponible : ${it.message}" }; view?.onResume() }
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
    Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(28.dp)).background(Color.Black)) {
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
        val level = CoverageMap.level(rssi.toFloat())
        Row(
            Modifier.align(Alignment.TopStart).padding(12.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text("$rssi", style = gs(28, 32, 600, tnum = true), color = signalColor(level))
            Text(" dBm", Modifier.padding(bottom = 3.dp), style = rf(13, 18, 600), color = Color.White)
        }
        val hint = when {
            c.tracking == TrackingState.TRACKING -> null
            c.failure == TrackingFailureReason.INSUFFICIENT_LIGHT -> "Trop sombre : allumez la lumière"
            c.failure == TrackingFailureReason.EXCESSIVE_MOTION -> "Bougez plus lentement"
            c.failure == TrackingFailureReason.INSUFFICIENT_FEATURES -> "Visez le sol ou des objets texturés"
            c.failure == TrackingFailureReason.CAMERA_UNAVAILABLE -> "Caméra utilisée par une autre application"
            else -> "Balayez lentement le sol avec la caméra…"
        }
        Text(
            hint ?: "Marchez : un point est posé tous les 40 cm",
            Modifier.align(Alignment.BottomCenter).padding(12.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 12.dp, vertical = 6.dp),
            style = rf(13, 18, 600), color = Color.White,
        )
    }
}

/** Steps from the accelerometer, heading from the rotation-vector sensor (compass + gyroscope). */
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
private fun signalColor(v: Float): Color {
    val good = AntTheme.net.good
    val fair = AntTheme.net.fair
    val bad = cs.error
    return if (v < 0.5f) lerp(bad, fair, v * 2) else lerp(fair, good, (v - 0.5f) * 2)
}

/** Top-down map of the walked floor: 50 cm squares coloured by signal, the path, and where the phone is. */
@Composable
private fun CoverageMapCard(c: CoverageController) {
    val samples = c.samples.toList()
    val cells = remember(samples.size) { CoverageMap.cells(samples) }
    val good = AntTheme.net.good
    val fair = AntTheme.net.fair
    val bad = cs.error
    val path = cs.onSurface
    val me = AntTheme.accent.accent
    val grid = cs.outlineVariant
    fun color(v: Float): Color = if (v < 0.5f) lerp(bad, fair, v * 2) else lerp(fair, good, (v - 0.5f) * 2)
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
                cells.forEach { k: CoverageCell ->
                    drawRect(color(CoverageMap.level(k.rssi)).copy(alpha = 0.75f), at(k.ix * cell, k.iz * cell), Size(cell * scale + 1f, cell * scale + 1f))
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
                    if (c.mode == CoverageMode.Ar) "Visez le sol puis marchez" else "Marchez pour tracer la carte",
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
            Triple(Sym.GridOn, "Surface", fmt(s.areaM2, 1)) to "m²",
            Triple(Sym.CheckCircle, "Bon signal", fmt(s.goodShare * 100, 0)) to "%",
            Triple(Sym.Wifi, "Moyenne", s.meanRssi?.let { fmt(it, 0) } ?: "—") to "dBm",
            Triple(Sym.Route, "Parcouru", fmt(s.distanceM, 1)) to "m",
        ),
    ) { (t, u), mod -> MetricTile(t.second, t.third, u, t.first, mod) }
    InfoList("Détail") {
        InfoRow("Mesures", s.samples.toString())
        InfoRow("Plus faible / plus fort", "${s.minRssi} / ${s.maxRssi} dBm")
        if (s.accessPoints > 1) InfoRow("Points d'accès traversés", s.accessPoints.toString())
    }
}

@Composable
private fun DevicePanel(support: ArSupport, c: CoverageController) {
    InfoList("Capteurs utilisés") {
        InfoRow(
            "Réalité augmentée (ARCore)",
            when {
                support.arcore == ArCoreApk.Availability.SUPPORTED_INSTALLED -> "Disponible"
                support.arcore == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD || support.arcore == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> "À installer depuis le Play Store"
                support.arcore?.isUnknown == true -> "Vérification…"
                else -> "Non compatible : podomètre"
            },
        )
        InfoRow(
            "Capteur de profondeur",
            when {
                c.tof -> "Infrarouge (ToF) utilisé"
                support.tofCamera -> "Infrarouge (ToF) présent"
                else -> "Estimée par la caméra"
            },
        )
        InfoRow("Mouvements", "Gyroscope, accéléromètre, boussole")
    }
    Text(
        "En réalité augmentée, la caméra suit vos déplacements au centimètre près : marchez simplement, téléphone tenu devant vous, " +
            "et des pastilles de couleur se posent au sol. Sans ARCore, les pas et la boussole tracent le parcours (moins précis). " +
            "Rien n'est enregistré : la carte disparaît quand vous quittez l'outil, sauf si vous l'exportez.",
        Modifier.padding(horizontal = 4.dp), style = rf(12, 16), color = cs.onSurfaceVariant,
    )
}

@Composable
private fun Legend() {
    val good = AntTheme.net.good
    val fair = AntTheme.net.fair
    val bad = cs.error
    SectionCard {
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Brush.horizontalGradient(listOf(bad, fair, good))))
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(-90, -80, -70, -60, -50).forEach { Text(fmt(it), style = rf(11, 14, tnum = true), color = cs.onSurfaceVariant) }
        }
        Text("dBm · au-dessus de -67 dBm : appels vidéo et streaming confortables", Modifier.padding(top = 4.dp), style = rf(12, 16), color = cs.onSurfaceVariant)
    }
}
