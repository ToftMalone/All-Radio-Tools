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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.data.sdr.HackRf
import com.allnetworktools.data.sdr.SdrDevice
import com.allnetworktools.data.sdr.SdrRepository
import com.allnetworktools.data.sdr.TpmsReading
import com.allnetworktools.data.sdr.TpmsReceiver
import com.allnetworktools.data.sdr.TpmsTracker
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.LeadingIcon
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.pages.TopBarAction
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.StartButton
import com.allnetworktools.ui.tools.ToolEmpty
import com.allnetworktools.util.Export
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.allnetworktools.R
import com.allnetworktools.util.S
import com.allnetworktools.util.P

/** Immutable copy of a sensor for the UI. */
data class TpmsInfo(val protocol: String, val id: String, val last: TpmsReading, val count: Int, val lastSeenMs: Long)

/**
 * Tyre pressure sensors of the cars around, on 433.92 MHz (or 315 MHz). The band sits fs/4 above the tuning, out of the
 * HackRF's DC spike. Kept in memory for the session only.
 */
class TpmsController(private val repo: SdrRepository, private val scope: CoroutineScope, private val onAcquire: () -> Unit = {}) {
    var bandHz by mutableStateOf(TpmsReceiver.EUROPE_HZ)
    var lnaGain by mutableIntStateOf(32)
    var vgaGain by mutableIntStateOf(30)
    var amp by mutableStateOf(true)
    var running by mutableStateOf(false)
        private set
    var starting by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var sensors by mutableStateOf<List<TpmsInfo>>(emptyList())
        private set
    var readings by mutableIntStateOf(0)
        private set

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
                val rate = TpmsReceiver.SAMPLE_RATE
                val hz = bandHz
                val ok = withContext(Dispatchers.IO) {
                    r.setSampleRate(rate, HackRf.filterFor(rate)) && r.setFrequency(hz - rate / 4) &&
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
        val tracker = TpmsTracker()
        var total = 0
        val rx = TpmsReceiver { tracker.update(it); total++ }
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
                    val snapshot = tracker.sensors.values.mapNotNull { s -> s.last?.let { TpmsInfo(s.protocol, s.id, it, s.count, s.lastSeenMs) } }
                        .sortedByDescending { it.lastSeenMs }
                    val n = total
                    scope.launch { sensors = snapshot; readings = n }
                }
            }
        }, "sdr-tpms").start()
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
        val r = radio
        radio = null
        running = false
        if (r != null) scope.launch(Dispatchers.IO) { r.close() }
    }

    internal fun setForTest(list: List<TpmsInfo>, total: Int) { sensors = list; readings = total; running = true }

    fun csv(): String = buildString {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        append("derniere_reception_utc,modele,identifiant,pression_kpa,temperature_c,pile_ok,receptions\r\n")
        sensors.forEach { s ->
            append(listOf(fmt.format(Date(s.lastSeenMs)), s.protocol, s.id, s.last.pressureKpa?.let { "%.1f".format(Locale.ROOT, it) }, s.last.temperatureC?.let { "%.0f".format(Locale.ROOT, it) }, s.last.batteryOk, s.count)
                .joinToString(",") { it?.toString() ?: "" })
            append("\r\n")
        }
    }

    companion object {
        private const val POOL = 32
    }
}

@Composable
fun TpmsTool(vm: MainViewModel) {
    val c = vm.tools.tpms
    val actions = LocalActions.current
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    if (c.sensors.isNotEmpty()) TopBarAction(Sym.Download) {
        val snapshot = c.csv()
        actions.saveFile("tpms-${Export.stamp(System.currentTimeMillis())}.csv", "text/csv") { snapshot.toByteArray() }
    }
    val mhz = "%.2f".format(Locale.getDefault(), c.bandHz / 1e6)
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${c.sensors.size}", style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(P(R.plurals.p_sensors, c.sensors.size), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text(S(R.string.tpms_heard, mhz, c.readings), style = rf(14, 20))
            Row(Modifier.padding(top = 12.dp)) {
                when {
                    c.error != null -> HeroChip(c.error!!, AntTheme.net.poor)
                    c.starting -> HeroChip(S(R.string.starting_the_hackrf), AntTheme.net.fair, blink = true)
                    c.running -> HeroChip(S(R.string.tpms_listening), AntTheme.net.good, blink = true)
                    device == null -> HeroChip(S(R.string.no_hackrf_plugged_in), AntTheme.net.poor)
                    else -> HeroChip(S(R.string.ready, device!!.name), AntTheme.net.good)
                }
            }
        }
        val d = device
        if (c.running || c.starting) StartButton(S(R.string.stop), Sym.Stop) { c.stop() }
        else StartButton(S(R.string.start_receiving), Sym.PlayArrow, enabled = d != null) { d?.let(c::start) }
        if (c.sensors.isEmpty()) {
            if (c.running) ToolEmpty(Sym.DirectionsCar, S(R.string.tpms_none_yet), S(R.string.tpms_none_yet_body), null)
        } else {
            val now = System.currentTimeMillis()
            SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
                c.sensors.forEachIndexed { i, s ->
                    if (i > 0) Hairline()
                    SensorRow(s, now)
                }
            }
        }
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(S(R.string.frequency), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(TpmsReceiver.EUROPE_HZ to "433,92 MHz · Europe", TpmsReceiver.AMERICA_HZ to "315 MHz · " + S(R.string.americas_asia)).forEach { (hz, label) ->
                        AntFilterChip(label, c.bandHz == hz, { if (c.bandHz != hz && !c.starting) { if (c.running) c.stop(); c.bandHz = hz } })
                    }
                }
                GainSettings(
                    c.lnaGain, listOf(16, 24, 32, 40), { c.lnaGain = it; c.applyGains() },
                    c.vgaGain, listOf(20, 30, 40), { c.vgaGain = it; c.applyGains() },
                    c.amp, { c.amp = !c.amp; c.applyGains() },
                )
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(S(R.string.tpms_how_it_works), style = rf(13, 18), color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SensorRow(s: TpmsInfo, now: Long) {
    val acc = AntTheme.accent
    val r = s.last
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LeadingIcon(Sym.TireRepair, acc.container, acc.onContainer, 40.dp, RoundedCornerShape(12.dp), 22.dp)
        Column(Modifier.weight(1f)) {
            Text(s.protocol, style = rf(15, 20, 600), maxLines = 1)
            val ago = ((now - s.lastSeenMs) / 1000).coerceAtLeast(0)
            Text(
                listOfNotNull(
                    s.id,
                    r.wheel?.let { S(R.string.tpms_wheel, it) },
                    if (r.moving == true) S(R.string.tpms_moving) else null,
                    if (r.batteryOk == false) S(R.string.tpms_battery_low) else null,
                    "×${s.count}",
                    if (ago <= 2) S(R.string.just_now) else S(R.string.s_ago, ago.toString()),
                ).joinToString(" · "),
                style = rf(12, 16, tnum = true), color = if (r.batteryOk == false) cs.error else cs.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(r.pressureKpa?.let { "%.2f bar".format(Locale.getDefault(), it / 100) } ?: "—", style = gs(20, 24, 500, tnum = true))
            r.temperatureC?.let { Text("%.0f °C".format(Locale.getDefault(), it), style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant) }
        }
    }
}
