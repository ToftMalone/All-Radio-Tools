package com.allnetworktools.ui.pages.sdr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.R
import com.allnetworktools.data.orbit.MeteorPredictor
import com.allnetworktools.data.orbit.MeteorSat
import com.allnetworktools.data.orbit.Observer
import com.allnetworktools.data.orbit.Sgp4
import com.allnetworktools.data.orbit.Tle
import com.allnetworktools.data.orbit.WeatherTleRepository
import com.allnetworktools.data.sdr.Ax25Frame
import com.allnetworktools.data.sdr.Cubesat
import com.allnetworktools.data.sdr.CubesatReceiver
import com.allnetworktools.data.sdr.SdrRepository
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.LeadingIcon
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.HostInputField
import com.allnetworktools.ui.tools.StartButton
import com.allnetworktools.util.P
import com.allnetworktools.util.S
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A frame as heard, with when and the Doppler shift at that moment. */
data class CubesatFrame(val frame: Ax25Frame, val atMs: Long, val dopplerHz: Double)

/** A pass of the chosen satellite over the phone. */
data class CubesatPass(val riseMs: Long, val setMs: Long, val maxEl: Double)

/**
 * AX.25 telemetry from amateur satellites: a preset (with passes and the Doppler shift followed from its orbit) or a
 * frequency of one's own. Kept in memory for the session only.
 */
class CubesatController(repo: SdrRepository, private val tles: WeatherTleRepository, scope: CoroutineScope, onAcquire: () -> Unit = {}) :
    SdrRxController(repo, scope, onAcquire, SdrGain(listOf(16, 24, 32, 40), 32, listOf(20, 30, 40), 30, amp = true), "sdr-cubesat") {
    /** The preset chosen, or null for the custom frequency. */
    var sat by mutableStateOf<Cubesat.Sat?>(Cubesat.sats.first())
    var customMhz by mutableStateOf("437.800")
    var customMode by mutableStateOf(Cubesat.Mode.G3ruh9600)
    var frames by mutableStateOf<List<CubesatFrame>>(emptyList())
        private set
    var dopplerHz by mutableStateOf(0.0)
        private set
    var passes by mutableStateOf<List<CubesatPass>>(emptyList())
        private set
    var passesError by mutableStateOf<String?>(null)
        private set
    @Volatile var observer: Observer? = null
    @Volatile private var tle: Tle? = null

    private val hz get() = sat?.hz ?: ((customMhz.replace(',', '.').toDoubleOrNull() ?: 437.8) * 1e6).toLong()
    private val mode get() = sat?.mode ?: customMode

    override val sampleRate = CubesatReceiver.SAMPLE_RATE
    override fun tuneHz() = hz - sampleRate / 4

    /** Element sets from CelesTrak's amateur group, then the passes of the next day for [obs]. */
    fun loadPasses(obs: Observer) {
        observer = obs
        val s = sat ?: run { passes = emptyList(); tle = null; return }
        scope.launch {
            try {
                val t = tles.load().firstOrNull { it.catalog == s.norad }
                tle = t
                if (t == null) { passesError = S(R.string.cubesat_no_elements); passes = emptyList(); return@launch }
                passesError = null
                val now = System.currentTimeMillis()
                passes = MeteorPredictor.passes(t, MeteorSat(s.name, s.norad, s.hz / 1e6), obs, now - 15 * 60_000L, now + 24 * 3_600_000L, minPeakDeg = 5.0)
                    .map { CubesatPass(it.riseMs, it.setMs, it.maxElevation) }
            } catch (e: Exception) {
                passesError = S(R.string.orbital_elements_unavailable, e.message ?: S(R.string.offline))
            }
        }
    }

    /** The Doppler shift now: −(range rate) / c × frequency, from the orbit; 0 without one. */
    private fun doppler(nowMs: Long): Double {
        val t = tle ?: return 0.0
        val o = observer ?: return 0.0
        val sgp = Sgp4(t)
        fun range(ms: Long): Double? { val jd = Sgp4.jdFromUnixMs(ms); return sgp.position((jd - sgp.jdEpoch) * 1440.0)?.let { o.look(it, jd).rangeKm } }
        val a = range(nowMs) ?: return 0.0
        val b = range(nowMs + 1000) ?: return 0.0
        return -(b - a) * 1000.0 / 299_792_458.0 * hz
    }

    override fun newSink(): SdrSink {
        val list = ArrayDeque<CubesatFrame>()
        var changed = false
        val rx = CubesatReceiver(mode) { f -> list.addFirst(CubesatFrame(f, System.currentTimeMillis(), dopplerHz)); changed = true; while (list.size > 300) list.removeLast() }
        rx.dopplerHz = doppler(System.currentTimeMillis())
        return object : SdrSink {
            override fun feed(buf: ByteArray, len: Int) = rx.feed(buf, len)
            override fun tick(nowMs: Long) {
                val d = doppler(nowMs)
                rx.dopplerHz = d
                val l = if (changed) { changed = false; list.toList() } else null
                post { dopplerHz = d; if (l != null) frames = l }
            }
        }
    }

    internal fun setForTest(list: List<CubesatFrame>, p: List<CubesatPass>) { frames = list; passes = p; markRunningForTest() }
}

private val dayFmt = SimpleDateFormat("EEE HH:mm", Locale.getDefault())
private val hmFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
private val hmsFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

@Composable
fun CubesatTool(vm: MainViewModel) {
    val c = vm.tools.cubesat
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    val positions by vm.positions.collectAsStateWithLifecycle()
    val loc = positions.fused ?: positions.gnss ?: positions.network
    LaunchedEffect(c.sat, loc != null) { loc?.let { c.loadPasses(Observer(it.latitude, it.longitude, it.altitude)) } }
    val now = System.currentTimeMillis()
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${c.frames.size}", style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(P(R.plurals.p_messages, c.frames.size), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            val s = c.sat
            Text(
                listOfNotNull(
                    s?.name ?: S(R.string.cubesat_custom),
                    "%.3f MHz".format(Locale.getDefault(), (s?.hz ?: ((c.customMhz.replace(',', '.').toDoubleOrNull() ?: 0.0) * 1e6).toLong()) / 1e6),
                    if (c.running && s != null) "Doppler %+.1f kHz".format(Locale.getDefault(), c.dopplerHz / 1000) else null,
                ).joinToString(" · "),
                style = rf(14, 20, tnum = true),
            )
            Row(Modifier.padding(top = 12.dp)) {
                when {
                    c.error != null -> HeroChip(c.error!!, AntTheme.net.poor)
                    c.starting -> HeroChip(S(R.string.starting_the_hackrf), AntTheme.net.fair, blink = true)
                    c.running -> HeroChip(S(R.string.cubesat_listening), AntTheme.net.good, blink = true)
                    device == null -> HeroChip(S(R.string.no_hackrf_plugged_in), AntTheme.net.poor)
                    else -> HeroChip(S(R.string.ready, device!!.name), AntTheme.net.good)
                }
            }
        }
        val d = device
        if (c.running || c.starting) StartButton(S(R.string.stop), Sym.Stop) { c.stop() }
        else StartButton(S(R.string.start_receiving), Sym.PlayArrow, enabled = d != null) { d?.let(c::start) }
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(S(R.string.cubesat_satellite), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    fun pick(s: Cubesat.Sat?) { if (c.sat != s && !c.starting) { if (c.running) c.stop(); c.sat = s } }
                    Cubesat.sats.forEach { s -> AntFilterChip(s.name, c.sat == s, { pick(s) }) }
                    AntFilterChip(S(R.string.cubesat_custom), c.sat == null, { pick(null) })
                }
                if (c.sat == null) {
                    var text by remember { mutableStateOf(c.customMhz) }
                    HostInputField(text, { text = it; c.customMhz = it }, S(R.string.cubesat_frequency_mhz), icon = Sym.SettingsInputAntenna, keyboardType = KeyboardType.Decimal)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Cubesat.Mode.Afsk1200 to "AFSK 1200", Cubesat.Mode.G3ruh9600 to "G3RUH 9600").forEach { (m, label) ->
                            AntFilterChip(label, c.customMode == m, { if (c.customMode != m && !c.starting) { if (c.running) c.stop(); c.customMode = m } })
                        }
                    }
                }
                GainSettings(c.gain) { c.applyGains() }
            }
        }
        if (c.sat != null) {
            SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
                Text(S(R.string.next_passes), Modifier.padding(bottom = 4.dp), style = rf(14, 20, 600))
                when {
                    loc == null -> Text(S(R.string.cubesat_needs_location), Modifier.padding(bottom = 8.dp), style = rf(13, 18), color = cs.onSurfaceVariant)
                    c.passesError != null -> Text(c.passesError!!, Modifier.padding(bottom = 8.dp), style = rf(13, 18), color = cs.error)
                    c.passes.isEmpty() -> Text(S(R.string.computing), Modifier.padding(bottom = 8.dp), style = rf(13, 18), color = cs.onSurfaceVariant)
                    else -> c.passes.take(6).forEachIndexed { i, p ->
                        if (i > 0) Hairline()
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Symbol(Sym.SatelliteAlt, size = 22.dp, tint = if (p.maxEl >= 40) AntTheme.net.good else if (p.maxEl >= 20) AntTheme.net.fair else cs.onSurfaceVariant)
                            Text(
                                "${dayFmt.format(Date(p.riseMs))} → ${hmFmt.format(Date(p.setMs))} · ${((p.setMs - p.riseMs) / 60_000.0).roundToInt()} min · max ${p.maxEl.roundToInt()}°",
                                Modifier.weight(1f), style = rf(13, 18, tnum = true),
                            )
                            if (now in p.riseMs..p.setMs) Text(S(R.string.in_progress), style = rf(12, 16, 600), color = AntTheme.net.good)
                        }
                    }
                }
            }
        }
        if (c.frames.isNotEmpty()) {
            SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
                c.frames.take(100).forEachIndexed { i, f ->
                    if (i > 0) Hairline()
                    FrameRow(f)
                }
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(S(R.string.cubesat_how_it_works), style = rf(13, 18), color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FrameRow(f: CubesatFrame) {
    val acc = AntTheme.accent
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LeadingIcon(Sym.SatelliteAlt, acc.container, acc.onContainer, 40.dp, RoundedCornerShape(12.dp), 22.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${f.frame.src} → ${f.frame.dst}", Modifier.weight(1f), style = rf(15, 20, 600), maxLines = 1)
                Text(hmsFmt.format(Date(f.atMs)), style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant)
            }
            if (f.frame.path.isNotEmpty() || f.dopplerHz != 0.0) {
                Text(
                    listOfNotNull(f.frame.path.joinToString(",").ifEmpty { null }, if (f.dopplerHz != 0.0) "Doppler %+.1f kHz".format(Locale.getDefault(), f.dopplerHz / 1000) else null).joinToString(" · "),
                    style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant,
                )
            }
            val text = String(CharArray(f.frame.info.size) { val b = f.frame.info[it].toInt() and 0xFF; if (b in 32..126) b.toChar() else '·' })
            Text(text, Modifier.padding(top = 4.dp), style = rf(13, 18), maxLines = 6)
        }
    }
}
