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
import com.allnetworktools.data.sdr.Decimator
import com.allnetworktools.data.sdr.FloatDecimator
import com.allnetworktools.data.sdr.HackRf
import com.allnetworktools.data.sdr.HorusReceiver
import com.allnetworktools.data.sdr.Landing
import com.allnetworktools.data.sdr.SondeLanding
import com.allnetworktools.data.sdr.Meteomodem
import com.allnetworktools.data.sdr.MeteomodemDemodulator
import com.allnetworktools.data.sdr.Rs41
import com.allnetworktools.data.sdr.Rs41Demodulator
import com.allnetworktools.data.sdr.SdrDevice
import com.allnetworktools.data.sdr.SdrRepository
import com.allnetworktools.data.sdr.SondeTracker
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
import kotlin.math.roundToInt
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

/** Immutable copy of a sonde for the UI. */
data class SondeInfo(
    val serial: String,
    /** "RS41", "M10", "M10+" or "M20". */
    val model: String,
    val lat: Double?,
    val lon: Double?,
    val altitudeM: Double?,
    val maxAltitudeM: Double?,
    val climbMs: Double?,
    val speedMs: Double?,
    val courseDeg: Double?,
    val batteryV: Double?,
    val satellites: Int?,
    val frameNumber: Int?,
    val frames: Int,
    val ageS: Int,
    /** Latitude, longitude pairs of the flight so far. */
    val track: List<Pair<Double, Double>>,
    /** Predicted landing point while the sonde falls under its parachute. */
    val landing: Landing? = null,
)

/**
 * Radiosondes on 400–406 MHz: Vaisala RS41 and the Meteomodem M10 / M20 that Météo-France launches. The HackRF runs at
 * 2 MS/s tuned 500 kHz below the sonde; the channel is brought to baseband at 500 kS/s, then decimated to 50 kS/s for
 * the RS41 (4800 bit/s) and to 100 kS/s for the Meteomodem sondes (9600 bit/s). Both decoders listen at once.
 */
class SondeController(private val repo: SdrRepository, private val scope: CoroutineScope, private val onAcquire: () -> Unit = {}) {
    var frequencyMhz by mutableStateOf("403.000")
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
    var listeningHz by mutableStateOf<Long?>(null)
        private set
    var sondes by mutableStateOf<List<SondeInfo>>(emptyList())
        private set
    var totalFrames by mutableIntStateOf(0)
        private set
    var selected by mutableStateOf<String?>(null)

    private var radio: HackRf? = null
    @Volatile private var dspRunning = false

    /** The frequency in Hz, or why it cannot be used. */
    fun parsedHz(): Pair<Long?, String?> {
        val mhz = frequencyMhz.trim().replace(',', '.').toDoubleOrNull() ?: return null to S(R.string.invalid_frequency)
        if (mhz !in 1.0..6000.0) return null to S(R.string.the_hackrf_receives_from_1_to)
        return (mhz * 1e6).roundToInt().toLong() to null
    }

    fun start(device: SdrDevice) {
        if (running || starting) return
        val (hz, err) = parsedHz()
        if (hz == null) { error = err; return }
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
                // The sonde sits fs/4 above the tuned frequency, away from the HackRF's DC spike.
                val ok = withContext(Dispatchers.IO) {
                    r.setSampleRate(SAMPLE_RATE, 1_750_000) && r.setFrequency(hz - SAMPLE_RATE / 4) &&
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
        val tracker = SondeTracker()
        var count = 0
        val demod = Rs41Demodulator(SAMPLE_RATE / 40.0) { f -> Rs41.parse(f)?.let { tracker.update(it, System.currentTimeMillis()); count++ } }
        val mDemod = MeteomodemDemodulator(SAMPLE_RATE / 20.0) { f -> Meteomodem.parse(f)?.let { tracker.update(it, System.currentTimeMillis()); count++ } }
        val horus = HorusReceiver { f -> tracker.update(f, System.currentTimeMillis()); count++ }
        val d3 = FloatDecimator(5, 0.05)
        val cRe = FloatArray(8192)
        val cIm = FloatArray(8192)
        val d1 = Decimator(4, 0.07, taps = 48)
        val d2 = FloatDecimator(10, 0.024)
        val aRe = FloatArray(32768)
        val aIm = FloatArray(32768)
        val bRe = FloatArray(4096)
        val bIm = FloatArray(4096)
        dspRunning = true
        Thread({
            var last = 0L
            while (dspRunning) {
                val item = full.poll(200, TimeUnit.MILLISECONDS)
                if (item != null) {
                    val k = d1.process(item.first, item.second, aRe, aIm, 0)
                    free.offer(item.first)
                    val m = d2.process(aRe, aIm, k, bRe, bIm)
                    demod.feed(bRe, bIm, m)
                    val q = d3.process(aRe, aIm, k, cRe, cIm)
                    mDemod.feed(cRe, cIm, q)
                    horus.feed(aRe, aIm, k)
                }
                val now = System.currentTimeMillis()
                if (now - last >= 1000) {
                    last = now
                    tracker.prune(now)
                    val snapshot = tracker.sondes.values.map { s ->
                        SondeInfo(s.serial, s.model, s.lat, s.lon, s.altitudeM, s.maxAltitudeM, s.climbMs, s.speedMs, s.courseDeg, s.batteryV,
                            s.satellites, s.frameNumber, s.frames, ((now - s.lastSeenMs) / 1000).toInt(), s.track.map { it[0] to it[1] },
                            landingOf(s))
                    }.sortedBy { it.ageS }
                    val total = count
                    scope.launch { sondes = snapshot; totalFrames = total }
                }
            }
        }, "sdr-sonde").start()
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

    internal fun setForTest(list: List<SondeInfo>, hz: Long) {
        sondes = list; totalFrames = list.sumOf { it.frames }; listeningHz = hz; running = true
    }

    companion object {
        private const val SAMPLE_RATE = 2_000_000
        private const val POOL = 32
    }
}

/** Landing prediction from the sonde's last position and its own wind profile. */
private fun landingOf(s: com.allnetworktools.data.sdr.Sonde): Landing? {
    val lat = s.lat ?: return null
    val lon = s.lon ?: return null
    val alt = s.altitudeM ?: return null
    val climb = s.climbMs ?: return null
    return SondeLanding.predict(lat, lon, alt, climb, s.winds.values.toList())
}

private fun meters(v: Double) = "%,d m".format(Locale.getDefault(), v.roundToInt()).replace(' ', ' ')
private fun climb(v: Double) = when {
    v > 0.5 -> "↑ %.1f m/s".format(Locale.getDefault(), v)
    v < -0.5 -> "↓ %.1f m/s".format(Locale.getDefault(), -v)
    else -> "stable"
}

@Composable
fun SondeTool(vm: MainViewModel) {
    val c = vm.tools.sonde
    val actions = LocalActions.current
    if (c.sondes.isNotEmpty()) TopBarAction(Sym.Download) {
        val snapshot = c.sondes
        val now = System.currentTimeMillis()
        actions.saveFile(S(R.string.radiosondes_csv, Export.stamp(now)), "text/csv") { Export.sondes(snapshot, now).toByteArray() }
    }
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    val positions by vm.positions.collectAsStateWithLifecycle()
    val loc = positions.fused ?: positions.gnss ?: positions.network
    val me = loc?.let { it.latitude to it.longitude }
    val (_, freqError) = c.parsedHz()
    val withPos = c.sondes.filter { it.lat != null }
    var showSettings by remember { mutableStateOf(false) }
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${c.sondes.size}", style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(P(R.plurals.p_sondes, c.sondes.size), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text(
                S(R.string.rs41_m10_and_m20_mhz, c.listeningHz?.let { "%.3f".format(Locale.getDefault(), it / 1e6) } ?: c.frequencyMhz.replace('.', ',')),
                style = rf(14, 20),
            )
            Row(Modifier.padding(top = 12.dp)) {
                when {
                    c.error != null -> HeroChip(c.error!!, AntTheme.net.poor)
                    c.starting -> HeroChip(S(R.string.starting_the_hackrf), AntTheme.net.fair, blink = true)
                    c.running -> HeroChip(S(R.string.received_3, c.totalFrames, P(R.plurals.p_frames, c.totalFrames)), AntTheme.net.good, blink = true)
                    device == null -> HeroChip(S(R.string.no_hackrf_plugged_in), AntTheme.net.poor)
                    else -> HeroChip(S(R.string.ready, device!!.name), AntTheme.net.good)
                }
            }
        }
        val d = device
        if (c.running || c.starting) StartButton(S(R.string.stop), Sym.Stop) { c.stop() }
        else StartButton(S(R.string.start_receiving), Sym.PlayArrow, enabled = d != null && freqError == null) { d?.let(c::start) }
        SondeMap(c, withPos, me, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)))
        if (c.sondes.isNotEmpty()) {
            SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
                c.sondes.forEachIndexed { i, s ->
                    if (i > 0) Hairline()
                    SondeRow(s, me, s.serial == c.selected) { c.selected = if (c.selected == s.serial) null else s.serial }
                }
            }
        }
        SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { showSettings = !showSettings }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Symbol(Sym.Tune, size = 20.dp, tint = AntTheme.accent.accent)
                Text(S(R.string.settings_2), Modifier.weight(1f), style = rf(14, 20, 600))
                Text((if (c.gain.auto) S(R.string.auto_gain) + " · " else "") + "LNA ${c.lnaGain} · VGA ${c.vgaGain}${if (c.amp) S(R.string.amp) else ""}", style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 1)
            }
            if (showSettings) {
                Column(Modifier.padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("403.000" to S(R.string.radiosondes), "434.660" to "Horus", "434.200" to "Horus").forEach { (f, l) ->
                            AntFilterChip("${f.replace('.', ',')} · $l", c.frequencyMhz == f, { c.frequencyMhz = f })
                        }
                    }
                    HostInputField(c.frequencyMhz, { c.frequencyMhz = it }, S(R.string.frequency_mhz), Sym.Stream, keyboardType = KeyboardType.Decimal)
                    if (freqError != null) Text(freqError, style = rf(13, 18), color = cs.error)
                    GainSettings(c.gain) { c.applyGains() }
                    Text(
                        S(R.string.the_frequency_applies_at_the_next) +
                            S(R.string.a_line_that_comes_back_every),
                        style = rf(12, 16), color = cs.onSurfaceVariant,
                    )
                }
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(
                    S(R.string.weather_stations_launch_balloons_twice_a) +
                        S(R.string.meteomodem_m10_and_m20_sondes_other) +
                        S(R.string.gps_position_in_clear_every_second) +
                        S(R.string.horus_info),
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SondeRow(s: SondeInfo, me: Pair<Double, Double>?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(if (selected) AntTheme.accent.accent else AntTheme.accent.container),
            contentAlignment = Alignment.Center,
        ) {
            Symbol(Sym.Cloud, size = 22.dp, filled = true, tint = if (selected) AntTheme.accent.onAccent else AntTheme.accent.onContainer)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${s.model} · ${s.serial}", Modifier.weight(1f), style = rf(15, 20, 600), maxLines = 1)
                Text(if (s.ageS <= 1) S(R.string.just_now) else S(R.string.s_ago, s.ageS), style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant)
            }
            Text(
                listOfNotNull(s.altitudeM?.let(::meters), s.climbMs?.let(::climb), s.speedMs?.let { "${(it * 3.6).roundToInt()} km/h" })
                    .joinToString(" · ").ifEmpty { S(R.string.waiting_for_gps) },
                style = rf(13, 18, tnum = true), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            s.landing?.let { l ->
                val dist = me?.let { S(R.string.km_from_you).format(Locale.getDefault(), AdsbTracker.haversineKm(it.first, it.second, l.lat, l.lon)) }
                val where = "%.4f, %.4f".format(Locale.ROOT, l.lat, l.lon)
                Text(
                    listOfNotNull(S(R.string.landing_expected_in_min, (l.inSeconds / 60).roundToInt().coerceAtLeast(1)), dist, where).joinToString(" · "),
                    style = rf(13, 18, 600, tnum = true), color = AntTheme.accent.accent, maxLines = 2,
                )
            }
            Text(
                listOfNotNull(
                    s.maxAltitudeM?.takeIf { s.climbMs != null && s.climbMs < -0.5 }?.let { S(R.string.burst_at, meters(it)) },
                    s.batteryV?.let { "%.1f V".format(Locale.getDefault(), it) },
                    s.satellites?.let { "$it sat." },
                    if (me != null && s.lat != null && s.lon != null) "%.0f km".format(Locale.getDefault(), AdsbTracker.haversineKm(me.first, me.second, s.lat, s.lon)) else null,
                    "${s.frames} ${P(R.plurals.p_frames, s.frames)}",
                ).joinToString(" · "),
                style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Each sonde's track as a line, its current position as a dot labelled with the serial and altitude. */
private class SondeOverlay(private val density: Float) : Overlay() {
    var sondes: List<SondeInfo> = emptyList()
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
        sondes.forEach { s ->
            val color = if (s.serial == selected) 0xFFE53935.toInt() else accent
            if (s.track.size > 1) {
                path.reset()
                s.track.forEachIndexed { i, (la, lo) ->
                    proj.toPixels(GeoPoint(la, lo), pt)
                    if (i == 0) path.moveTo(pt.x.toFloat(), pt.y.toFloat()) else path.lineTo(pt.x.toFloat(), pt.y.toFloat())
                }
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3 * density
                paint.color = color
                c.drawPath(path, paint)
            }
            proj.toPixels(GeoPoint(s.lat!!, s.lon!!), pt)
            val r = (if (s.serial == selected) 9 else 7) * density
            paint.style = Paint.Style.FILL
            paint.color = 0xFFFFFFFF.toInt(); c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), r + 2 * density, paint)
            paint.color = color; c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), r, paint)
            s.landing?.let { l ->
                // Predicted landing: a cross, joined to the sonde by a dashed line.
                val sx = pt.x.toFloat()
                val sy = pt.y.toFloat()
                proj.toPixels(GeoPoint(l.lat, l.lon), pt)
                paint.style = Paint.Style.STROKE
                paint.color = color
                paint.strokeWidth = 2 * density
                paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(6 * density, 5 * density), 0f)
                c.drawLine(sx, sy, pt.x.toFloat(), pt.y.toFloat(), paint)
                paint.pathEffect = null
                paint.strokeWidth = 3 * density
                val k = 7 * density
                c.drawLine(pt.x - k, pt.y - k, pt.x + k, pt.y + k, paint)
                c.drawLine(pt.x - k, pt.y + k, pt.x + k, pt.y - k, paint)
                proj.toPixels(GeoPoint(s.lat!!, s.lon!!), pt)
            }
            val label = s.serial + (s.altitudeM?.let { " · " + meters(it) } ?: "")
            text.color = 0xFFFFFFFF.toInt()
            text.style = Paint.Style.STROKE; text.strokeWidth = 3 * density
            c.drawText(label, pt.x + r + 4 * density, pt.y - 2 * density, text)
            text.style = Paint.Style.FILL; text.color = 0xFF202124.toInt()
            c.drawText(label, pt.x + r + 4 * density, pt.y - 2 * density, text)
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, map: MapView): Boolean {
        val proj = map.projection
        fun dist(s: SondeInfo): Float {
            proj.toPixels(GeoPoint(s.lat!!, s.lon!!), pt)
            return (pt.x - e.x) * (pt.x - e.x) + (pt.y - e.y) * (pt.y - e.y)
        }
        val hit = sondes.minByOrNull(::dist)?.takeIf { dist(it) < (28 * density) * (28 * density) }
        onTap(hit?.serial)
        return hit != null
    }
}

@Composable
private fun SondeMap(c: SondeController, sondes: List<SondeInfo>, me: Pair<Double, Double>?, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dark = com.allnetworktools.ui.theme.isDarkTheme(AntTheme.settings)
    val density = LocalDensity.current.density
    val overlay = remember { SondeOverlay(density) }
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
            maxZoomLevel = 17.0
            controller.setZoom(8.0)
            controller.setCenter(GeoPoint(46.6, 2.4))
        }
    }
    LaunchedEffect(me, sondes.isNotEmpty()) {
        if (centred) return@LaunchedEffect
        val target = sondes.firstOrNull()?.let { it.lat!! to it.lon!! } ?: me ?: return@LaunchedEffect
        map.controller.setCenter(GeoPoint(target.first, target.second))
        centred = sondes.isNotEmpty()
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
                overlay.sondes = sondes
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
            val sel = sondes.firstOrNull { it.serial == c.selected } ?: sondes.firstOrNull()
            if (sel != null) IconCircleButton(Sym.Cloud, { map.controller.animateTo(GeoPoint(sel.lat!!, sel.lon!!), 11.0, 600L) }, size = 36.dp, bg = bg, tint = AntTheme.accent.accent)
        }
        Text(
            S(R.string.openstreetmap_contributors),
            Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(cs.surface.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 2.dp),
            style = rf(10, 12), color = cs.onSurfaceVariant,
        )
    }
}
