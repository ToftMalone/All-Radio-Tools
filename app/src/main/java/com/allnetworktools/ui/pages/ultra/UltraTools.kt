package com.allnetworktools.ui.pages.ultra

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.allnetworktools.R
import com.allnetworktools.data.ultra.ChirpPlayer
import com.allnetworktools.data.ultra.Sonar
import com.allnetworktools.data.ultra.UltraDetector
import com.allnetworktools.data.ultra.UltraFinding
import com.allnetworktools.data.ultra.UltraKind
import com.allnetworktools.data.ultra.UltraLevel
import com.allnetworktools.data.ultra.UltraMic
import com.allnetworktools.data.ultra.UltraSpectrum
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.pages.PageColumn
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
import com.allnetworktools.util.S
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Lowest frequency drawn: a little under the ultrasound band, so audible high tones are seen arriving. */
private const val VIEW_LO_HZ = 14_000.0

/**
 * The microphone listening for ultrasound: spectra for the spectrogram, the detector's findings, the level for the
 * leak finder. Everything runs on the microphone's thread; the screen reads a snapshot five times a second.
 */
class UltraController(private val context: Context) {
    var running by mutableStateOf(false); private set
    var failed by mutableStateOf(false); private set
    var rate by mutableIntStateOf(0); private set
    var source by mutableStateOf(""); private set
    var findings by mutableStateOf<List<UltraFinding>>(emptyList()); private set
    var spectrum by mutableStateOf<FloatArray?>(null); private set
    var levelDb by mutableFloatStateOf(-120f); private set
    var peakHz by mutableStateOf(0.0); private set
    var maxDb by mutableFloatStateOf(-120f); private set
    var frames by mutableIntStateOf(0); private set
    /** Highest frequency the view shows: just under half the sample rate. */
    val topHz: Double get() = if (rate > 0) minOf(rate / 2.0 * 0.98, MAX_VIEW_HZ) else 23_500.0

    var waterfall: Bitmap? = null; private set
    private var pixels = IntArray(0)
    private var columns = 0
    private val mic = UltraMic(context)
    private var job: Job? = null
    @Volatile private var lastSpectrum: FloatArray? = null
    @Volatile private var lastFindings: List<UltraFinding> = emptyList()
    @Volatile private var lastLevel = -120f
    @Volatile private var lastPeak = 0.0
    @Volatile private var drawn = 0
    private val lock = Any()

    fun start(scope: CoroutineScope) {
        if (running) return
        val t0 = System.currentTimeMillis()
        lateinit var spec: UltraSpectrum
        var det: UltraDetector? = null
        var lvl: UltraLevel? = null
        var loBin = 0
        val ok = mic.start(listOf(96_000, 48_000)) { x, n -> spec.feed(x, n) }.let { started ->
            if (!started) false else {
                rate = mic.rate; source = mic.sourceName
                val top = topHz
                spec = UltraSpectrum(rate) { db ->
                    val now = System.currentTimeMillis() - t0
                    det!!.feed(db, now)
                    lvl!!.feed(db)
                    lastSpectrum = db
                    lastLevel = lvl!!.levelDb
                    lastPeak = lvl!!.peakHz
                    lastFindings = det!!.findings(now)
                    synchronized(lock) { drawRow(db, loBin) }
                }
                det = UltraDetector(spec.binHz, top)
                lvl = UltraLevel(spec.binHz, 18_000.0, top)
                loBin = spec.binOf(VIEW_LO_HZ)
                columns = spec.binOf(top) - loBin
                waterfall = Bitmap.createBitmap(columns, ROWS, Bitmap.Config.ARGB_8888).apply { eraseColor(COLD) }
                pixels = IntArray(columns * ROWS) { COLD }
                true
            }
        }
        if (!ok) { failed = true; return }
        failed = false
        running = true
        job = scope.launch {
            while (isActive) {
                delay(200)
                spectrum = lastSpectrum
                findings = lastFindings
                levelDb = lastLevel
                peakHz = lastPeak
                if (lastLevel > maxDb) maxDb = lastLevel
                synchronized(lock) { waterfall?.setPixels(pixels, 0, columns, 0, 0, columns, ROWS) }
                frames = drawn
            }
        }
    }

    private fun drawRow(db: FloatArray, loBin: Int) {
        if (columns <= 0) return
        System.arraycopy(pixels, 0, pixels, columns, columns * (ROWS - 1))
        val row = db.copyOfRange(loBin, loBin + columns)
        val floor = row.sortedArray()[row.size / 2] - 3f
        for (x in 0 until columns) pixels[x] = heat(((row[x] - floor) / 45f).coerceIn(0f, 1f))
        drawn++
    }

    fun resetMax() { maxDb = levelDb }

    fun stop() {
        job?.cancel(); job = null
        mic.stop()
        running = false
    }

    companion object {
        const val ROWS = 180
        /** Phone microphones hear next to nothing above this, even at 96 kHz. */
        const val MAX_VIEW_HZ = 32_000.0
        val COLD = heat(0f)

        /** Dark blue to cyan, yellow and white. */
        fun heat(v: Float): Int {
            val stops = arrayOf(floatArrayOf(10f, 14f, 40f), floatArrayOf(20f, 90f, 170f), floatArrayOf(40f, 200f, 200f), floatArrayOf(250f, 220f, 60f), floatArrayOf(255f, 255f, 255f))
            val p = v * (stops.size - 1)
            val i = p.toInt().coerceAtMost(stops.size - 2)
            val f = p - i
            val a = stops[i]; val b = stops[i + 1]
            fun c(k: Int) = (a[k] + (b[k] - a[k]) * f).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (c(0) shl 16) or (c(1) shl 8) or c(2)
        }
    }
}

/** The sonar: a chirp every [PERIOD_MS], the echo looked for in what the microphone heard right after. */
class SonarController(private val context: Context) {
    var running by mutableStateOf(false); private set
    var failed by mutableStateOf(false); private set
    var distanceM by mutableStateOf<Double?>(null); private set
    var quality by mutableFloatStateOf(0f); private set
    var pings by mutableIntStateOf(0); private set
    var echoes by mutableIntStateOf(0); private set
    var directHeard by mutableStateOf(true); private set

    private val mic = UltraMic(context)
    private var player: ChirpPlayer? = null
    private var job: Job? = null
    private val ring = FloatArray(RATE)
    @Volatile private var written = 0L
    private val recent = ArrayDeque<Double>()

    fun start(scope: CoroutineScope) {
        if (running) return
        val chirp = Sonar.chirp(RATE)
        val p = ChirpPlayer(RATE, chirp)
        if (!p.ok || !mic.start(listOf(RATE)) { x, n ->
                synchronized(ring) { for (i in 0 until n) ring[((written + i) % ring.size).toInt()] = x[i] }
                written += n
            }) { p.release(); failed = true; return }
        player = p
        failed = false
        running = true
        recent.clear()
        job = scope.launch {
            val window = RATE * WINDOW_MS / 1000
            while (isActive) {
                p.ping()
                pings++
                delay(LISTEN_MS)
                val rec = synchronized(ring) {
                    val end = written
                    FloatArray(window) { i -> ring[((end - window + i).mod(ring.size.toLong())).toInt()] }
                }
                val e = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Sonar.measure(rec, RATE, chirp) }
                directHeard = e != null || Sonar.correlate(rec, chirp).maxOrNull()?.let { it > 1f } == true
                if (e != null) {
                    echoes++
                    recent.addLast(e.distanceM)
                    while (recent.size > 5) recent.removeFirst()
                    distanceM = recent.sorted()[recent.size / 2]
                    quality = (e.strength / 20).toFloat().coerceIn(0f, 1f)
                }
                delay(PERIOD_MS - LISTEN_MS)
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        mic.stop()
        player?.release(); player = null
        running = false
    }

    companion object {
        const val RATE = 48_000
        const val PERIOD_MS = 600L
        const val LISTEN_MS = 250L
        const val WINDOW_MS = 600
    }
}

@Composable
private fun Permission(onGrant: () -> Unit) =
    ToolError(Sym.Mic, S(R.string.permission_needed), S(R.string.ultra_permission), S(R.string.allow), onGrant)

@Composable
private fun Info(text: String) {
    SectionCard {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
            Text(text, style = rf(13, 18), color = cs.onSurfaceVariant)
        }
    }
}

private fun khz(hz: Double) = "%.1f kHz".format(Locale.getDefault(), hz / 1000)

@Composable
private fun kindName(k: UltraKind) = S(
    when (k) {
        UltraKind.TrackingBeacon -> R.string.ultra_k_beacon
        UltraKind.Repellent -> R.string.ultra_k_repellent
        UltraKind.Sweep -> R.string.ultra_k_sweep
        UltraKind.Jammer -> R.string.ultra_k_jammer
        UltraKind.ScreenWhine -> R.string.ultra_k_screen
        UltraKind.Whine -> R.string.ultra_k_whine
    },
)

@Composable
private fun kindText(k: UltraKind) = S(
    when (k) {
        UltraKind.TrackingBeacon -> R.string.ultra_d_beacon
        UltraKind.Repellent -> R.string.ultra_d_repellent
        UltraKind.Sweep -> R.string.ultra_d_sweep
        UltraKind.Jammer -> R.string.ultra_d_jammer
        UltraKind.ScreenWhine -> R.string.ultra_d_screen
        UltraKind.Whine -> R.string.ultra_d_whine
    },
)

private fun kindColor(k: UltraKind, net: com.allnetworktools.ui.theme.NetworkColors): Color = when (k) {
    UltraKind.TrackingBeacon, UltraKind.Jammer -> net.poor
    UltraKind.Repellent, UltraKind.Sweep -> net.fair
    UltraKind.ScreenWhine, UltraKind.Whine -> net.good
}

/** The ultrasound spectrogram and detector: tracking beacons, repellents, jammers, whines. */
@Composable
fun UltraScanTool(permitted: Boolean, onGrant: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = remember { UltraController(context.applicationContext) }
    DisposableEffect(permitted) {
        if (permitted) c.start(scope)
        onDispose { c.stop() }
    }
    PageColumn {
        if (!permitted) { Permission(onGrant); return@PageColumn }
        if (c.failed) { ToolError(Sym.MicOff, S(R.string.ultra_mic_failed), S(R.string.ultra_mic_failed_body), "OK") {}; return@PageColumn }
        val now = System.currentTimeMillis()
        val active = c.findings
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(active.size.toString(), style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(S(R.string.ultra_findings), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text(
                if (active.isEmpty()) S(R.string.ultra_quiet) else active.joinToString(" · ") { kindNamePlain(context, it.kind) },
                style = rf(14, 20),
            )
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (c.running) HeroChip(S(R.string.ultra_listening, khz(VIEW_LO_HZ), khz(c.topHz)), AntTheme.net.good, blink = true)
            }
        }
        Spectrogram(c)
        if (active.isNotEmpty()) {
            SectionCard {
                Column {
                    active.forEachIndexed { i, f ->
                        if (i > 0) Hairline()
                        FindingRow(f)
                    }
                }
            }
        }
        Info(S(R.string.ultra_scan_how, c.rate / 1000, c.source))
    }
}

private fun kindNamePlain(context: Context, k: UltraKind) = context.getString(
    when (k) {
        UltraKind.TrackingBeacon -> R.string.ultra_k_beacon
        UltraKind.Repellent -> R.string.ultra_k_repellent
        UltraKind.Sweep -> R.string.ultra_k_sweep
        UltraKind.Jammer -> R.string.ultra_k_jammer
        UltraKind.ScreenWhine -> R.string.ultra_k_screen
        UltraKind.Whine -> R.string.ultra_k_whine
    },
)

@Composable
private fun FindingRow(f: UltraFinding) {
    val color = kindColor(f.kind, AntTheme.net)
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 4.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(color).padding(horizontal = 6.dp))
        Column(Modifier.weight(1f)) {
            Text(kindName(f.kind), style = rf(15, 20, 600))
            Text(
                (if (f.hiHz - f.loHz > 200) "${khz(f.loHz)} – ${khz(f.hiHz)}" else khz(f.hz)) + " · +%.0f dB".format(Locale.getDefault(), f.snrDb),
                style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant,
            )
            Text(kindText(f.kind), Modifier.padding(top = 2.dp), style = rf(12, 16), color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun Spectrogram(c: UltraController) {
    val bmp = c.waterfall
    val line = AntTheme.accent.accent
    val grid = cs.outlineVariant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainerHigh)) {
            val db = c.spectrum
            val top = c.topHz
            val frames = c.frames
            Canvas(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 10.dp)) {
                if (frames < 0) return@Canvas
                // A line every kilohertz from 15 kHz.
                var k = 15_000.0
                while (k < top) {
                    val x = size.width * ((k - VIEW_LO_HZ) / (top - VIEW_LO_HZ)).toFloat()
                    drawLine(grid, Offset(x, 0f), Offset(x, size.height), 1f)
                    k += 1_000
                }
                if (db != null && c.rate > 0) {
                    val binHz = c.rate.toDouble() / ((db.size) * 2)
                    val lo = (VIEW_LO_HZ / binHz).toInt()
                    val hi = (top / binHz).toInt().coerceAtMost(db.size - 1)
                    val minDb = -130f; val maxDb = -30f
                    val p = Path()
                    for (i in lo..hi) {
                        val x = size.width * (i - lo).toFloat() / (hi - lo)
                        val y = size.height * (1 - ((db[i] - minDb) / (maxDb - minDb)).coerceIn(0f, 1f))
                        if (i == lo) p.moveTo(x, y) else p.lineTo(x, y)
                    }
                    drawPath(p, line, style = Stroke(2f))
                }
            }
        }
        if (bmp != null) {
            val tick = c.frames
            Image(
                bmp.asImageBitmap(), null,
                Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(20.dp)),
                contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.Low,
                alpha = if (tick >= 0) 1f else 1f,
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Text(khz(VIEW_LO_HZ), Modifier.weight(1f), style = rf(11, 14, tnum = true), color = cs.onSurfaceVariant)
            Text(khz((VIEW_LO_HZ + c.topHz) / 2), style = rf(11, 14, tnum = true), color = cs.onSurfaceVariant)
            Text(khz(c.topHz), Modifier.weight(1f), style = rf(11, 14, tnum = true), color = cs.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}

/** The sonar: the distance to what the top of the phone points at, by the echo of an ultrasonic chirp. */
@Composable
fun UltraSonarTool(permitted: Boolean, onGrant: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = remember { SonarController(context.applicationContext) }
    DisposableEffect(Unit) { onDispose { c.stop() } }
    PageColumn {
        if (!permitted) { Permission(onGrant); return@PageColumn }
        if (c.failed) ToolError(Sym.MicOff, S(R.string.ultra_mic_failed), S(R.string.ultra_mic_failed_body), "OK") {}
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(c.distanceM?.let { "%.2f".format(Locale.getDefault(), it) } ?: "—", style = gs(56, 60, 500, -1.5f, tnum = true))
                Text("m", Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text(
                when {
                    !c.running -> S(R.string.ultra_sonar_idle)
                    c.distanceM == null -> S(R.string.ultra_sonar_searching)
                    else -> S(R.string.ultra_sonar_echoes, c.echoes, c.pings)
                },
                style = rf(14, 20),
            )
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (c.running) HeroChip(S(R.string.ultra_sonar_pinging), AntTheme.net.good, blink = true)
                if (c.running && c.distanceM != null) HeroChip(
                    S(if (c.quality > 0.5f) R.string.ultra_echo_clear else R.string.ultra_echo_weak),
                    if (c.quality > 0.5f) AntTheme.net.good else AntTheme.net.fair,
                )
            }
        }
        if (c.running && !c.directHeard) SectionCard { Text(S(R.string.ultra_sonar_no_direct), style = rf(13, 18), color = cs.onSurfaceVariant) }
        if (c.running) ToolButtons(ToolButton(S(R.string.stop), Sym.Stop, BtnKind.Big) { c.stop() })
        else ToolButtons(ToolButton(S(R.string.start_2), Sym.PlayArrow, BtnKind.Big) { c.start(scope) })
        Info(S(R.string.ultra_sonar_how))
    }
}

/** The leak finder: how much ultrasound the microphone hears, to walk towards a hissing leak. */
@Composable
fun UltraLeakTool(permitted: Boolean, onGrant: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = remember { UltraController(context.applicationContext) }
    DisposableEffect(permitted) {
        if (permitted) c.start(scope)
        onDispose { c.stop() }
    }
    PageColumn {
        if (!permitted) { Permission(onGrant); return@PageColumn }
        // The limit comes first: the user must know what this can and cannot find before trusting it.
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Warning, size = 22.dp, tint = AntTheme.net.fair)
                Text(S(R.string.ultra_leak_limit), style = rf(13, 18), color = cs.onSurface)
            }
        }
        if (c.failed) { ToolError(Sym.MicOff, S(R.string.ultra_mic_failed), S(R.string.ultra_mic_failed_body), "OK") {}; return@PageColumn }
        val level = c.levelDb
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (level <= -119f) "—" else "%.0f".format(Locale.getDefault(), level), style = gs(56, 60, 500, -1.5f, tnum = true))
                Text("dB", Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text(
                if (level <= -119f) S(R.string.ultra_sonar_searching) else S(R.string.ultra_leak_hero, "%.0f".format(Locale.getDefault(), c.maxDb), khz(c.peakHz)),
                style = rf(14, 20),
            )
        }
        // The level as a bar: from the quietest a phone hears up there to very loud.
        val lo = -110f; val hi = -40f
        val frac = ((level - lo) / (hi - lo)).coerceIn(0f, 1f)
        val maxFrac = ((c.maxDb - lo) / (hi - lo)).coerceIn(0f, 1f)
        val barColor = when { frac > 0.66f -> AntTheme.net.poor; frac > 0.33f -> AntTheme.net.fair; else -> AntTheme.net.good }
        val marker = cs.onSurface
        Box(Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainerHigh)) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(barColor, size = size.copy(width = size.width * frac))
                val x = size.width * maxFrac
                drawLine(marker, Offset(x, 0f), Offset(x, size.height), 4f)
            }
        }
        ToolButtons(ToolButton(S(R.string.ultra_leak_reset), Sym.RestartAlt, BtnKind.Outline) { c.resetMax() })
        Spectrogram(c)
        Info(S(R.string.ultra_leak_how))
    }
}
