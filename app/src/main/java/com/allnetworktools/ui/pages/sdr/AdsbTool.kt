package com.allnetworktools.ui.pages.sdr

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.allnetworktools.MainViewModel
import com.allnetworktools.data.sdr.AdsbDemodulator
import com.allnetworktools.data.sdr.AdsbTracker
import com.allnetworktools.data.sdr.HackRf
import com.allnetworktools.data.sdr.SdrDevice
import com.allnetworktools.data.sdr.SdrRepository
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.IconCircleButton
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
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.StartButton
import com.allnetworktools.util.Export
import com.allnetworktools.util.plural
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import com.allnetworktools.R
import com.allnetworktools.util.S
import com.allnetworktools.util.P

/** Immutable copy of an aircraft for the UI. */
data class Plane(
    val icao: Int,
    val hex: String,
    val callsign: String?,
    val altitudeFt: Int?,
    val speedKt: Double?,
    val headingDeg: Double?,
    val verticalFtMin: Int?,
    val lat: Double?,
    val lon: Double?,
    val ageS: Int,
    val messages: Int,
    val levelDb: Double,
)

/** ADS-B receiver on 1090 MHz, 2 MS/s, the rate dump1090 was designed around. */
class AdsbController(private val repo: SdrRepository, private val scope: CoroutineScope, private val onAcquire: () -> Unit = {}) {
    val gain = SdrGain(listOf(16, 24, 32, 40), 32, listOf(20, 30, 40), 30, amp = false)
    var lnaGain by gain::lna
    var vgaGain by gain::vga
    var amp: Boolean
        get() = gain.amp == true
        set(v) { gain.amp = v }
    var running by mutableStateOf(false)
        private set
    var starting by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var planes by mutableStateOf<List<Plane>>(emptyList())
        private set
    var messagesPerSecond by mutableIntStateOf(0)
        private set
    var totalMessages by mutableIntStateOf(0)
        private set
    var selected by mutableStateOf<Int?>(null)

    private var radio: HackRf? = null
    @Volatile private var dspRunning = false

    fun start(device: SdrDevice) {
        if (running || starting) return
        onAcquire()
        error = null
        starting = true
        scope.launch {
            try {
                if (!repo.hasPermission(device) && !repo.requestPermission(device)) { error = S(R.string.usb_access_to_the_hackrf_refused); return@launch }
                val r = withContext(Dispatchers.IO) { repo.open(device) } ?: run { error = S(R.string.could_not_open_the_hackrf); return@launch }
                val lna = lnaGain
                val vga = vgaGain
                val withAmp = amp
                val ok = withContext(Dispatchers.IO) {
                    r.setSampleRate(2_000_000, HackRf.filterFor(2_000_000)) && r.setFrequency(1_090_000_000) &&
                        r.setLnaGain(lna) && r.setVgaGain(vga) && r.setAmp(withAmp)
                }
                if (!ok) { r.close(); error = S(R.string.the_hackrf_did_not_accept_the); return@launch }
                radio = r
                startPipeline(r)
                running = true
            } finally {
                starting = false
            }
        }
    }

    private fun startPipeline(r: HackRf) {
        val free = ArrayBlockingQueue<ByteArray>(POOL)
        val full = ArrayBlockingQueue<Pair<ByteArray, Int>>(POOL)
        repeat(POOL) { free.add(ByteArray(131072)) }
        val tracker = AdsbTracker()
        var count = 0
        val demod = AdsbDemodulator { msg, level -> tracker.update(msg, level, System.currentTimeMillis()); count++ }
        dspRunning = true
        Thread({
            var last = System.currentTimeMillis()
            var windowCount = 0
            while (dspRunning) {
                val item = full.poll(200, TimeUnit.MILLISECONDS)
                if (item != null) {
                    demod.feed(item.first, item.second)
                    free.offer(item.first)
                }
                val now = System.currentTimeMillis()
                if (now - last >= 1000) {
                    tracker.prune(now)
                    val snapshot = tracker.aircraft.values.map {
                        Plane(it.icao, it.hex, it.callsign, it.altitudeFt, it.speedKt, it.headingDeg, it.verticalFtMin, it.lat, it.lon,
                            ((now - it.lastSeenMs) / 1000).toInt(), it.messages, it.levelDb)
                    }.sortedBy { it.ageS }
                    val rate = count - windowCount
                    windowCount = count
                    val total = count
                    last = now
                    scope.launch { planes = snapshot; messagesPerSecond = rate; totalMessages = total }
                }
            }
        }, "sdr-adsb").start()
        gain.attach { applyGains() }
        r.startRx(
            onSamples = { data, len -> gain.feed(data, len); free.poll()?.let { b -> System.arraycopy(data, 0, b, 0, len); full.offer(b to len) } },
            onError = { msg -> scope.launch { error = msg; stop() } },
        )
    }

    fun applyGains() {
        val r = radio ?: return
        val lna = lnaGain
        val vga = vgaGain
        val withAmp = amp
        scope.launch(Dispatchers.IO) { r.setLnaGain(lna); r.setVgaGain(vga); r.setAmp(withAmp) }
    }

    fun stop() {
        dspRunning = false
        val r = radio
        radio = null
        running = false
        if (r != null) scope.launch(Dispatchers.IO) { r.close() }
    }

    internal fun setForTest(list: List<Plane>, rate: Int) {
        planes = list; messagesPerSecond = rate; totalMessages = rate * 60; running = true
    }

    companion object {
        private const val POOL = 32
    }
}

private fun ft(v: Int) = "%,d ft".format(Locale.getDefault(), v).replace(' ', ' ') + " (" + "%,d m".format(Locale.getDefault(), (v * 0.3048).roundToInt()).replace(' ', ' ') + ")"
private fun kt(v: Double) = S(R.string.kt_km_h, v.roundToInt(), (v * 1.852).roundToInt())

@Composable
fun AdsbTool(vm: MainViewModel) {
    val c = vm.tools.adsb
    val actions = LocalActions.current
    if (c.planes.isNotEmpty()) TopBarAction(Sym.Download) {
        val snapshot = c.planes
        val now = System.currentTimeMillis()
        actions.saveFile(S(R.string.aircraft_csv, Export.stamp(now)), "text/csv") { Export.planes(snapshot, now).toByteArray() }
    }
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    // The phone's position only centres the map and gives distances; it is read while this screen is open.
    val positions by vm.positions.collectAsStateWithLifecycle()
    val loc = positions.fused ?: positions.gnss ?: positions.network
    val me = loc?.let { it.latitude to it.longitude }
    val withPos = c.planes.filter { it.lat != null }
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${c.planes.size}", style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(P(R.plurals.p_aircraft, c.planes.size), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text("${withPos.size} ${P(R.plurals.p_located, withPos.size)} · 1090 MHz", style = rf(14, 20))
            Row(Modifier.padding(top = 12.dp)) {
                when {
                    c.error != null -> HeroChip(c.error!!, AntTheme.net.poor)
                    c.starting -> HeroChip(S(R.string.starting_the_hackrf), AntTheme.net.fair, blink = true)
                    c.running -> HeroChip(S(R.string.messages_s, c.messagesPerSecond), AntTheme.net.good, blink = true)
                    device == null -> HeroChip(S(R.string.no_hackrf_plugged_in), AntTheme.net.poor)
                    else -> HeroChip(S(R.string.ready, device!!.name), AntTheme.net.good)
                }
            }
        }
        val d = device
        if (c.running || c.starting) StartButton(S(R.string.stop), Sym.Stop) { c.stop() }
        else StartButton(S(R.string.start_receiving), Sym.PlayArrow, enabled = d != null) { d?.let(c::start) }
        AdsbMap(c, withPos, me, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)))
        if (c.planes.isNotEmpty()) {
            SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
                c.planes.forEachIndexed { i, p ->
                    if (i > 0) Hairline()
                    PlaneRow(p, me, p.icao == c.selected) { c.selected = if (c.selected == p.icao) null else p.icao }
                }
            }
        }
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GainSettings(c.gain) { c.applyGains() }
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(
                    S(R.string.aircraft_broadcast_their_identity_altitude_speed) +
                        S(R.string.a_position_needs_two_frames_even) +
                        S(R.string.receive_only),
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PlaneRow(p: Plane, me: Pair<Double, Double>?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(if (selected) AntTheme.accent.accent else AntTheme.accent.container),
            contentAlignment = Alignment.Center,
        ) {
            Symbol(Sym.Flight, size = 22.dp, filled = true, tint = if (selected) AntTheme.accent.onAccent else AntTheme.accent.onContainer)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.callsign ?: p.hex, Modifier.weight(1f), style = rf(15, 20, 600), maxLines = 1)
                Text(if (p.ageS <= 1) S(R.string.just_now) else S(R.string.s_ago, p.ageS), style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant)
            }
            Text(
                listOfNotNull(p.altitudeFt?.let(::ft), p.speedKt?.let(::kt)).joinToString(" · ").ifEmpty { "ICAO ${p.hex}" },
                style = rf(13, 18, tnum = true), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    if (p.callsign != null) "ICAO ${p.hex}" else null,
                    p.headingDeg?.let { S(R.string.heading, it.roundToInt()) },
                    p.verticalFtMin?.let { if (it > 64) "↑ $it ft/min" else if (it < -64) "↓ ${-it} ft/min" else "palier" },
                    if (me != null && p.lat != null && p.lon != null) "%.0f km".format(Locale.getDefault(), AdsbTracker.haversineKm(me.first, me.second, p.lat, p.lon)) else null,
                ).joinToString(" · "),
                style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant,
            )
        }
    }
}

/** Aircraft as arrows pointing along their track, labelled with their callsign. */
private class PlaneOverlay(private val density: Float) : Overlay() {
    var planes: List<Plane> = emptyList()
    var selected: Int? = null
    var me: GeoPoint? = null
    var accent = 0
    var onTap: (Int?) -> Unit = {}
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11 * density; isFakeBoldText = true }
    private val pt = android.graphics.Point()
    private val path = Path()

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = map.projection
        me?.let {
            proj.toPixels(it, pt)
            paint.style = Paint.Style.FILL
            paint.color = 0xFFFFFFFF.toInt(); c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 8 * density, paint)
            paint.color = 0xFF1E88E5.toInt(); c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 5 * density, paint)
        }
        planes.forEach { p ->
            proj.toPixels(GeoPoint(p.lat!!, p.lon!!), pt)
            val a = Math.toRadians(p.headingDeg ?: 0.0)
            val s = (if (p.icao == selected) 14 else 10) * density
            fun x(dx: Double, dy: Double) = (pt.x + dx * cos(a) - dy * sin(a)).toFloat()
            fun y(dx: Double, dy: Double) = (pt.y + dx * sin(a) + dy * cos(a)).toFloat()
            path.reset()
            path.moveTo(x(0.0, -s.toDouble()), y(0.0, -s.toDouble()))
            path.lineTo(x(s * 0.65, s * 0.8), y(s * 0.65, s * 0.8))
            path.lineTo(x(0.0, s * 0.35), y(0.0, s * 0.35))
            path.lineTo(x(-s * 0.65, s * 0.8), y(-s * 0.65, s * 0.8))
            path.close()
            paint.style = Paint.Style.FILL
            paint.color = if (p.icao == selected) 0xFFE53935.toInt() else accent
            c.drawPath(path, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f * density
            paint.color = 0xFFFFFFFF.toInt()
            c.drawPath(path, paint)
            val label = p.callsign ?: p.hex
            text.color = 0xFFFFFFFF.toInt()
            text.style = Paint.Style.STROKE; text.strokeWidth = 3 * density
            c.drawText(label, pt.x + s + 2 * density, pt.y - 2 * density, text)
            text.style = Paint.Style.FILL; text.color = 0xFF202124.toInt()
            c.drawText(label, pt.x + s + 2 * density, pt.y - 2 * density, text)
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, map: MapView): Boolean {
        val proj = map.projection
        val hit = planes.minByOrNull { p ->
            proj.toPixels(GeoPoint(p.lat!!, p.lon!!), pt)
            (pt.x - e.x) * (pt.x - e.x) + (pt.y - e.y) * (pt.y - e.y)
        }?.takeIf { p ->
            proj.toPixels(GeoPoint(p.lat!!, p.lon!!), pt)
            (pt.x - e.x) * (pt.x - e.x) + (pt.y - e.y) * (pt.y - e.y) < (28 * density) * (28 * density)
        }
        onTap(hit?.icao)
        return hit != null
    }
}

@Composable
private fun AdsbMap(c: AdsbController, planes: List<Plane>, me: Pair<Double, Double>?, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dark = com.allnetworktools.ui.theme.isDarkTheme(AntTheme.settings)
    val density = LocalDensity.current.density
    val overlay = remember { PlaneOverlay(density) }
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
            maxZoomLevel = 14.0
            controller.setZoom(8.0)
            controller.setCenter(GeoPoint(46.6, 2.4))
        }
    }
    LaunchedEffect(me, planes.isNotEmpty()) {
        if (centred) return@LaunchedEffect
        val target = me ?: planes.firstOrNull()?.let { it.lat!! to it.lon!! } ?: return@LaunchedEffect
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
    val safe = WindowInsets.safeDrawing
    Box(modifier) {
        AndroidView(
            factory = { map },
            modifier = Modifier.fillMaxSize(),
            update = { m ->
                overlay.planes = planes
                overlay.selected = c.selected
                overlay.me = me?.let { GeoPoint(it.first, it.second) }
                overlay.accent = accent
                overlay.onTap = { c.selected = it }
                if (overlay !in m.overlays) m.overlays += overlay
                m.overlayManager.tilesOverlay.setColorFilter(if (dark) darkTilesFilter else null)
                m.invalidate()
            },
        )
        Column(Modifier.align(Alignment.TopEnd).windowInsetsPadding(safe).padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val bg = cs.surface.copy(alpha = 0.92f)
            IconCircleButton(Sym.Add, { map.controller.zoomIn() }, size = 36.dp, bg = bg, tint = cs.onSurface)
            IconCircleButton(Sym.Remove, { map.controller.zoomOut() }, size = 36.dp, bg = bg, tint = cs.onSurface)
            if (me != null) IconCircleButton(Sym.MyLocation, { map.controller.animateTo(GeoPoint(me.first, me.second), 9.0, 600L) }, size = 36.dp, bg = bg, tint = AntTheme.accent.accent)
        }
        Text(
            S(R.string.openstreetmap_contributors),
            Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(cs.surface.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 2.dp),
            style = rf(10, 12), color = cs.onSurfaceVariant,
        )
    }
}
