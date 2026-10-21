package com.allnetworktools.ui.pages.sdr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.data.Constellation
import com.allnetworktools.data.Satellite
import com.allnetworktools.data.sdr.Gps
import com.allnetworktools.data.sdr.GpsReceiver
import com.allnetworktools.data.sdr.GpsStage
import com.allnetworktools.data.sdr.GpsStatus
import com.allnetworktools.data.sdr.HackRf
import com.allnetworktools.data.sdr.SdrDevice
import com.allnetworktools.data.sdr.SdrRepository
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.InfoList
import com.allnetworktools.ui.components.InfoRow
import com.allnetworktools.ui.components.LeadingIcon
import com.allnetworktools.ui.components.LevelBar
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.pages.gnss.SkyPlot
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.StartButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.allnetworktools.R
import com.allnetworktools.util.S

/**
 * GPS with the HackRF alone and its stock telescopic antenna: 2.048 MS/s at 1575.42 MHz, full gain, and the software
 * receiver of [GpsReceiver]. Nothing is shared with the phone's own GNSS chip. The antenna port is never powered.
 */
class GpsController(private val repo: SdrRepository, private val scope: CoroutineScope, private val onAcquire: () -> Unit = {}) {
    var lnaGain by mutableIntStateOf(40)
    var vgaGain by mutableIntStateOf(40)
    var amp by mutableStateOf(true)
    var running by mutableStateOf(false)
        private set
    var starting by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var status by mutableStateOf<GpsStatus?>(null)
        private set
    /** When listening started, for the "first fix" timer. */
    var startedAt by mutableStateOf(0L)
        private set
    var firstFixSeconds by mutableStateOf<Int?>(null)
        private set

    private var radio: HackRf? = null
    @Volatile private var dspRunning = false
    private var workers: ExecutorService? = null
    private var acquisition: ExecutorService? = null

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
                    r.setSampleRate(GpsReceiver.FS.toInt(), 1_750_000) && r.setFrequency(Gps.L1_HZ.toLong()) &&
                        r.setLnaGain(lna) && r.setVgaGain(vga) && r.setAmp(withAmp) && r.setAntennaPower(false)
                }
                if (!ok) { r.close(); error = S(R.string.the_hackrf_did_not_accept_the); return@launch }
                radio = r
                status = null
                firstFixSeconds = null
                startedAt = System.currentTimeMillis()
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
        val cores = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
        val pool = Executors.newFixedThreadPool(cores).also { workers = it }
        val acq = Executors.newSingleThreadExecutor().also { acquisition = it }
        val rx = GpsReceiver(
            acquire = { task -> acq.execute(task) },
            parallel = { tasks -> pool.invokeAll(tasks.map { Callable { it.run() } }) },
        ) { s ->
            scope.launch {
                status = s
                if (s.fix != null && firstFixSeconds == null) firstFixSeconds = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
            }
        }
        dspRunning = true
        Thread({
            while (dspRunning) {
                val item = full.poll(200, TimeUnit.MILLISECONDS) ?: continue
                rx.feed(item.first, item.second)
                free.offer(item.first)
            }
        }, "sdr-gps").apply { priority = Thread.MAX_PRIORITY - 1; start() }
        r.startRx(
            onSamples = { data, len -> free.poll()?.let { b -> System.arraycopy(data, 0, b, 0, len); full.offer(b to len) } },
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
        workers?.shutdown(); workers = null
        acquisition?.shutdownNow(); acquisition = null
        val r = radio
        radio = null
        running = false
        if (r != null) scope.launch(Dispatchers.IO) { r.close() }
    }

    internal fun setForTest(s: GpsStatus) { status = s; running = true; startedAt = System.currentTimeMillis() - 95_000; firstFixSeconds = 41 }

    companion object {
        private const val POOL = 64
    }
}

private fun stageText(s: GpsStage) = when (s) {
    GpsStage.PullIn -> S(R.string.acquisition)
    GpsStage.Tracking -> S(R.string.tracking)
    GpsStage.BitSync -> S(R.string.reading_bits)
    GpsStage.Frame -> S(R.string.reading_the_message)
    GpsStage.Ephemeris -> S(R.string.orbit_known)
}

@Composable
fun GpsTool(vm: MainViewModel) {
    val c = vm.tools.gps
    val actions = LocalActions.current
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    val s = c.status
    val fix = s?.fix
    val tracked = s?.channels?.size ?: 0
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${fix?.used?.size ?: tracked}", style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(
                    if (fix != null) S(R.string.satellites_in_the_solution) else S(R.string.satellites_tracked),
                    Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500),
                )
            }
            Text(
                when {
                    fix != null -> "%.6f, %.6f · %.0f m".format(Locale.ROOT, fix.lat, fix.lon, fix.height)
                    c.running -> S(R.string.gps_l1_1575_42_mhz_first)
                    else -> S(R.string.gps_l1_1575_42_mhz_received)
                },
                style = rf(14, 20, tnum = true),
            )
            Row(Modifier.padding(top = 12.dp)) {
                when {
                    c.error != null -> HeroChip(c.error!!, AntTheme.net.poor)
                    c.starting -> HeroChip(S(R.string.starting_the_hackrf), AntTheme.net.fair, blink = true)
                    fix != null -> HeroChip(S(R.string.computed_position), AntTheme.net.good, blink = true)
                    c.running && (s?.channels?.any { it.stage == GpsStage.Ephemeris } == true) -> HeroChip(S(R.string.n_4_satellites_with_their_orbit_are), AntTheme.net.fair, blink = true)
                    c.running && tracked > 0 -> HeroChip(S(R.string.reading_the_orbits_30_s), AntTheme.net.fair, blink = true)
                    c.running -> HeroChip(S(R.string.searching_for_satellites_2), AntTheme.net.fair, blink = true)
                    device == null -> HeroChip(S(R.string.no_hackrf_plugged_in), AntTheme.net.poor)
                    else -> HeroChip(S(R.string.ready, device!!.name), AntTheme.net.good)
                }
            }
        }
        val d = device
        if (c.running || c.starting) StartButton(S(R.string.stop), Sym.Stop) { c.stop() }
        else StartButton(S(R.string.start_receiving), Sym.PlayArrow, enabled = d != null) { d?.let(c::start) }
        if (fix != null && s != null) {
            InfoList("Position") {
                val coords = "%.6f, %.6f".format(Locale.ROOT, fix.lat, fix.lon)
                InfoRow(S(R.string.coordinates), coords) { actions.copy(S(R.string.coordinates), coords) }
                InfoRow(S(R.string.altitude_ellipsoid), "%.0f m".format(Locale.getDefault(), fix.height))
                InfoRow(S(R.string.geometric_precision_pdop), "%.1f".format(Locale.getDefault(), fix.pdop))
                s.week?.let { w ->
                    val utc = SimpleDateFormat("dd/MM/yyyy HH:mm:ss 'UTC'", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("UTC") }
                    InfoRow(S(R.string.gps_time_2), utc.format(Date(((315_964_800.0 + w * Gps.SECONDS_PER_WEEK + fix.tow - 18) * 1000).toLong())))
                }
                c.firstFixSeconds?.let { InfoRow(S(R.string.first_fix), S(R.string.after_s, it)) }
                s.clockPpm?.let { InfoRow(S(R.string.hackrf_crystal_error), "%+.1f ppm".format(Locale.getDefault(), it)) }
            }
        }
        if (s != null && s.channels.isNotEmpty()) {
            if (s.channels.any { it.elevation != null }) {
                SectionCard {
                    SkyPlot(
                        s.channels.filter { it.elevation != null }.map {
                            Satellite(Constellation.GPS, it.prn, it.cn0.toFloat(), it.elevation!!.toFloat(), it.azimuth!!.toFloat(), it.used, 1575.42f)
                        },
                        height = 280.dp,
                    )
                }
            }
            SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
                s.channels.forEachIndexed { i, ch ->
                    if (i > 0) Hairline()
                    val acc = AntTheme.accent
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LeadingIcon(if (ch.used) Sym.CheckCircle else Sym.SatelliteAlt, acc.container, acc.onContainer, 40.dp, RoundedCornerShape(12.dp), 22.dp)
                        Column(Modifier.weight(1f)) {
                            Text("G%02d · %s".format(Locale.ROOT, ch.prn, stageText(ch.stage)), style = rf(15, 20, 600), maxLines = 1)
                            Text(
                                listOfNotNull(
                                    "Doppler %+.0f Hz".format(Locale.getDefault(), ch.dopplerHz),
                                    ch.elevation?.let { S(R.string.elevation_2).format(Locale.getDefault(), it) },
                                    ch.azimuth?.let { S(R.string.azimuth).format(Locale.getDefault(), it) },
                                ).joinToString(" · "),
                                style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant, maxLines = 1,
                            )
                            LevelBar(((ch.cn0 - 25) / 25).toFloat(), if (ch.cn0 >= 38) AntTheme.net.good else if (ch.cn0 >= 32) AntTheme.net.fair else AntTheme.net.poor, Modifier.padding(top = 6.dp), height = 6.dp)
                        }
                        Text("%.0f dB-Hz".format(Locale.getDefault(), ch.cn0), Modifier.width(72.dp), style = rf(13, 18, 600, tnum = true))
                    }
                }
            }
        } else if (c.running) {
            Text(
                S(R.string.searching_for_the_32_gps_satellites),
                Modifier.padding(horizontal = 4.dp), style = rf(12, 16), color = cs.onSurfaceVariant,
            )
        }
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GainSettings(
                    c.lnaGain, listOf(24, 32, 40), { c.lnaGain = it; c.applyGains() },
                    c.vgaGain, listOf(20, 30, 40, 50), { c.vgaGain = it; c.applyGains() },
                    c.amp, { c.amp = !c.amp; c.applyGains() },
                )
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(
                    S(R.string.the_hackrf_receives_the_gps_signals) +
                        S(R.string.searching_for_satellites_tracking_their_codes) +
                        S(R.string.with_the_stock_telescopic_antenna_collapse) +
                        S(R.string.hold_it_vertical_outside_under_an) +
                        S(R.string.including_30_s_of_uninterrupted_reading),
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}
