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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.data.sdr.AdsbTracker
import com.allnetworktools.data.sdr.Aprs
import com.allnetworktools.data.sdr.AprsReceiver
import com.allnetworktools.data.sdr.AprsTracker
import com.allnetworktools.data.sdr.AprsWeather
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
import com.allnetworktools.ui.tools.HostInputField
import com.allnetworktools.ui.tools.StartButton
import com.allnetworktools.util.Export
import com.allnetworktools.util.plural
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
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

/** Immutable copy of a station, object or item for the UI. */
data class AprsInfo(
    val key: String,
    val title: String,
    val isObject: Boolean,
    val lat: Double?,
    val lon: Double?,
    val speedKn: Double?,
    val courseDeg: Int?,
    val altitudeM: Double?,
    val symbol: String?,
    val comment: String?,
    val status: String?,
    val weather: AprsWeather?,
    val via: String?,
    val packets: Int,
    val messages: Int,
    val ageS: Int,
) {
    val moving get() = (speedKn ?: 0.0) > 2.0
    val isWeather get() = weather != null
    val isRelay get() = symbol?.getOrNull(1).let { it == '#' || it == 'r' || it == '&' }
}

/**
 * APRS beacons from amateur radio operators: 1200 baud packet radio on 144,800 MHz in Europe. The channel sits fs/4 above
 * the tuning, the HackRF's DC spike falls outside it. Text messages between operators are counted, never shown.
 */
class AprsController(private val repo: SdrRepository, private val scope: CoroutineScope, private val onAcquire: () -> Unit = {}) {
    var frequencyMhz by mutableStateOf("144.800")
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
    var stations by mutableStateOf<List<AprsInfo>>(emptyList())
        private set
    var totalPackets by mutableIntStateOf(0)
        private set
    var selected by mutableStateOf<String?>(null)
    var listeningHz by mutableStateOf<Long?>(null)
        private set

    private var radio: HackRf? = null
    @Volatile private var dspRunning = false

    fun parsedHz(): Long? = frequencyMhz.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in 100.0..1000.0 }?.let { (it * 1e6).roundToLong() }

    fun start(device: SdrDevice) {
        if (running || starting) return
        val hz = parsedHz() ?: run { error = S(R.string.invalid_frequency_100_to_1000_mhz); return }
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
                val rate = AprsReceiver.SAMPLE_RATE
                val ok = withContext(Dispatchers.IO) {
                    r.setSampleRate(rate, HackRf.filterFor(rate)) && r.setFrequency(hz - rate / 4) &&
                        r.setLnaGain(lna) && r.setVgaGain(vga) && r.setAmp(withAmp)
                }
                if (!ok) { r.close(); error = S(R.string.the_hackrf_did_not_accept_the); return@launch }
                radio = r
                listeningHz = hz
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
        val tracker = AprsTracker()
        val rx = AprsReceiver { tracker.update(it, System.currentTimeMillis()) }
        dspRunning = true
        Thread({
            var last = 0L
            while (dspRunning) {
                val item = full.poll(200, TimeUnit.MILLISECONDS)
                if (item != null) {
                    rx.feed(item.first, item.second)
                    free.offer(item.first)
                }
                val now = System.currentTimeMillis()
                if (now - last >= 1000) {
                    last = now
                    tracker.prune(now)
                    val snapshot = tracker.stations.values.map {
                        AprsInfo(it.key, it.name ?: it.call, it.isObject, it.lat, it.lon, it.speedKn, it.courseDeg, it.altitudeM, it.symbol, it.comment,
                            it.status, it.weather, it.via, it.packets, it.messages, ((now - it.lastSeenMs) / 1000).toInt())
                    }.sortedBy { it.ageS }
                    val total = tracker.total
                    scope.launch { stations = snapshot; totalPackets = total }
                }
            }
        }, "sdr-aprs").start()
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
        listeningHz = null
        if (r != null) scope.launch(Dispatchers.IO) { r.close() }
    }

    internal fun setForTest(list: List<AprsInfo>, total: Int) {
        stations = list; totalPackets = total; listeningHz = 144_800_000L; running = true
    }

    companion object {
        private const val POOL = 32
    }
}

private fun kmh(kn: Double) = "${(kn * 1.852).roundToInt()} km/h"

private fun weatherLine(w: AprsWeather): String = listOfNotNull(
    w.tempF?.let { "%.0f °C".format(Locale.getDefault(), (it - 32) / 1.8) },
    w.humidity?.let { "$it %" },
    w.pressureHpa?.let { "%.0f hPa".format(Locale.getDefault(), it) },
    w.windMph?.let { S(R.string.wind_km_h, (it * 1.609).roundToInt()) + (w.windDir?.let { d -> " · $d°" } ?: "") },
).joinToString(" · ")

@Composable
fun AprsTool(vm: MainViewModel) {
    val c = vm.tools.aprs
    val actions = LocalActions.current
    if (c.stations.isNotEmpty()) TopBarAction(Sym.Download) {
        val snapshot = c.stations
        val now = System.currentTimeMillis()
        actions.saveFile("aprs-${Export.stamp(now)}.csv", "text/csv") { Export.aprs(snapshot, now).toByteArray() }
    }
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    val positions by vm.positions.collectAsStateWithLifecycle()
    val loc = positions.fused ?: positions.gnss ?: positions.network
    val me = loc?.let { it.latitude to it.longitude }
    val withPos = c.stations.filter { it.lat != null }
    val heardMessages = c.stations.sumOf { it.messages }
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${c.stations.size}", style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(P(R.plurals.p_stations, c.stations.size), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text(
                S(R.string.mhz, withPos.size, P(R.plurals.p_located_positionnee, withPos.size), c.listeningHz?.let { "%.3f".format(Locale.getDefault(), it / 1e6) } ?: c.frequencyMhz.replace('.', ',')),
                style = rf(14, 20),
            )
            Row(Modifier.padding(top = 12.dp)) {
                when {
                    c.error != null -> HeroChip(c.error!!, AntTheme.net.poor)
                    c.starting -> HeroChip(S(R.string.starting_the_hackrf), AntTheme.net.fair, blink = true)
                    c.running -> HeroChip(S(R.string.received_2, c.totalPackets, P(R.plurals.p_packets, c.totalPackets)), AntTheme.net.good, blink = true)
                    device == null -> HeroChip(S(R.string.no_hackrf_plugged_in), AntTheme.net.poor)
                    else -> HeroChip(S(R.string.ready, device!!.name), AntTheme.net.good)
                }
            }
        }
        val d = device
        if (c.running || c.starting) StartButton(S(R.string.stop), Sym.Stop) { c.stop() }
        else StartButton(S(R.string.start_receiving), Sym.PlayArrow, enabled = d != null && c.parsedHz() != null) { d?.let(c::start) }
        AprsMap(c, withPos, me, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)))
        if (c.stations.isNotEmpty()) {
            SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
                c.stations.forEachIndexed { i, s ->
                    if (i > 0) Hairline()
                    StationRow(s, me, s.key == c.selected) { c.selected = if (c.selected == s.key) null else s.key }
                }
            }
        }
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(S(R.string.frequency), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("144.800" to "Europe", "144.390" to S(R.string.north_america), "145.825" to S(R.string.satellites_iss)).forEach { (f, l) ->
                        AntFilterChip("${f.replace('.', ',')} · $l", c.frequencyMhz == f, { if (c.frequencyMhz != f && !c.starting) { if (c.running) c.stop(); c.frequencyMhz = f } })
                    }
                }
                if (!c.running) HostInputField(c.frequencyMhz, { c.frequencyMhz = it }, S(R.string.frequency_mhz), Sym.Stream, keyboardType = KeyboardType.Decimal)
                GainSettings(c.gain) { c.applyGains() }
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(
                    S(R.string.aprs_is_the_radio_amateurs_beacon) +
                        S(R.string.stations_transmit_every_1_to_30) +
                        S(R.string.text_messages_between_operators_are_never) +
                        (if (heardMessages > 0) P(R.plurals.p_messages_heard_ignored, heardMessages, heardMessages) else "") +
                        S(R.string.receive_only_2),
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StationRow(s: AprsInfo, me: Pair<Double, Double>?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(if (selected) AntTheme.accent.accent else AntTheme.accent.container),
            contentAlignment = Alignment.Center,
        ) {
            val icon = if (s.isWeather) Sym.Cloud else if (s.moving) Sym.DirectionsCar else if (s.isRelay) Sym.Hub else Sym.Radio
            Symbol(icon, size = 22.dp, filled = true, tint = if (selected) AntTheme.accent.onAccent else AntTheme.accent.onContainer)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.title, Modifier.weight(1f), style = rf(15, 20, 600), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (s.ageS < 60) S(R.string.s_ago, s.ageS) else S(R.string.min_ago, s.ageS / 60), style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant)
            }
            Text(
                s.weather?.let(::weatherLine)?.takeIf { it.isNotEmpty() }
                    ?: listOfNotNull(
                        Aprs.symbolName(s.symbol), s.speedKn?.takeIf { it > 0.5 }?.let(::kmh), s.courseDeg?.takeIf { s.moving }?.let { S(R.string.heading, it) },
                        s.altitudeM?.let { "${it.roundToInt()} m" },
                    ).joinToString(" · ").ifEmpty { s.status ?: S(R.string.no_position) },
                style = rf(13, 18, tnum = true), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    (s.comment ?: s.status)?.takeIf { s.weather == null || s.comment != null },
                    s.via?.let { "via $it" },
                    if (me != null && s.lat != null && s.lon != null) "%.0f km".format(Locale.getDefault(), AdsbTracker.haversineKm(me.first, me.second, s.lat, s.lon)) else null,
                    "${s.packets} ${P(R.plurals.p_packets, s.packets)}",
                ).joinToString(" · "),
                style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Moving stations as arrows along their course, the others as dots, labelled with their callsign. */
private class AprsOverlay(private val density: Float) : Overlay() {
    var stations: List<AprsInfo> = emptyList()
    var selected: String? = null
    var me: GeoPoint? = null
    var accent = 0
    var onTap: (String?) -> Unit = {}
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
        stations.forEach { s ->
            proj.toPixels(GeoPoint(s.lat!!, s.lon!!), pt)
            val sel = s.key == selected
            val color = if (sel) 0xFFE53935.toInt() else if (s.isWeather) 0xFF039BE5.toInt() else if (s.isRelay) 0xFF7E57C2.toInt() else accent
            val size = (if (sel) 13 else 9) * density
            paint.style = Paint.Style.FILL
            if (s.moving && s.courseDeg != null) {
                val a = Math.toRadians(s.courseDeg.toDouble())
                fun x(dx: Double, dy: Double) = (pt.x + dx * cos(a) - dy * sin(a)).toFloat()
                fun y(dx: Double, dy: Double) = (pt.y + dx * sin(a) + dy * cos(a)).toFloat()
                path.reset()
                path.moveTo(x(0.0, -size.toDouble()), y(0.0, -size.toDouble()))
                path.lineTo(x(size * 0.6, size * 0.8), y(size * 0.6, size * 0.8))
                path.lineTo(x(0.0, size * 0.4), y(0.0, size * 0.4))
                path.lineTo(x(-size * 0.6, size * 0.8), y(-size * 0.6, size * 0.8))
                path.close()
                paint.color = color
                c.drawPath(path, paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f * density; paint.color = 0xFFFFFFFF.toInt()
                c.drawPath(path, paint)
            } else {
                paint.color = 0xFFFFFFFF.toInt(); c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), size * 0.6f + 2 * density, paint)
                paint.color = color; c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), size * 0.6f, paint)
            }
            text.color = 0xFFFFFFFF.toInt(); text.style = Paint.Style.STROKE; text.strokeWidth = 3 * density
            c.drawText(s.title, pt.x + size + 2 * density, pt.y - 2 * density, text)
            text.style = Paint.Style.FILL; text.color = 0xFF202124.toInt()
            c.drawText(s.title, pt.x + size + 2 * density, pt.y - 2 * density, text)
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, map: MapView): Boolean {
        val proj = map.projection
        fun dist(s: AprsInfo): Float {
            proj.toPixels(GeoPoint(s.lat!!, s.lon!!), pt)
            return (pt.x - e.x) * (pt.x - e.x) + (pt.y - e.y) * (pt.y - e.y)
        }
        val hit = stations.minByOrNull(::dist)?.takeIf { dist(it) < (28 * density) * (28 * density) }
        onTap(hit?.key)
        return hit != null
    }
}

@Composable
private fun AprsMap(c: AprsController, stations: List<AprsInfo>, me: Pair<Double, Double>?, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dark = com.allnetworktools.ui.theme.isDarkTheme(AntTheme.settings)
    val density = LocalDensity.current.density
    val overlay = remember { AprsOverlay(density) }
    val accent = AntTheme.accent.accent.toArgb()
    var centred by remember { mutableStateOf(false) }
    val map = remember {
        configureOsm(context)
        TouchMapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 3.0
            maxZoomLevel = 17.0
            controller.setZoom(9.0)
            controller.setCenter(GeoPoint(46.6, 2.4))
        }
    }
    LaunchedEffect(me, stations.isNotEmpty()) {
        if (centred) return@LaunchedEffect
        val target = me ?: stations.firstOrNull()?.let { it.lat!! to it.lon!! } ?: return@LaunchedEffect
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
                overlay.stations = stations
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
            if (me != null) IconCircleButton(Sym.MyLocation, { map.controller.animateTo(GeoPoint(me.first, me.second), 10.0, 600L) }, size = 36.dp, bg = bg, tint = AntTheme.accent.accent)
        }
        Text(
            S(R.string.openstreetmap_contributors),
            Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(cs.surface.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 2.dp),
            style = rf(10, 12), color = cs.onSurfaceVariant,
        )
    }
}
