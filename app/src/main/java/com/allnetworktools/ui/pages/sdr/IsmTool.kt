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
import com.allnetworktools.data.sdr.Ism
import com.allnetworktools.data.sdr.IsmReading
import com.allnetworktools.data.sdr.IsmReceiver
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

/** One device heard: its last reading, how often, and when. */
data class IsmDevice(val key: String, val last: IsmReading, val count: Int, val lastSeenMs: Long)

/**
 * ISM sensors: weather stations, thermometers, energy monitors, meters, door and motion sensors, fixed-code remotes. The
 * band sits fs/4 above the tuning, out of the HackRF's DC spike. Kept in memory for the session only.
 */
class IsmController(private val repo: SdrRepository, private val scope: CoroutineScope, private val onAcquire: () -> Unit = {}) {
    var bandHz by mutableStateOf(BANDS.first().first)
    val gain = SdrGain(listOf(16, 24, 32, 40), 32, listOf(20, 30, 40), 30, amp = true)
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
    var devices by mutableStateOf<List<IsmDevice>>(emptyList())
        private set
    var readings by mutableIntStateOf(0)
        private set
    var unknown by mutableIntStateOf(0)
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
                val rate = IsmReceiver.SAMPLE_RATE
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
        val table = LinkedHashMap<String, IsmDevice>()
        var total = 0
        var unknownCount = 0
        val rx = IsmReceiver({ reading, _ ->
            val now = System.currentTimeMillis()
            val key = "${reading.model}/${reading.id}/${reading.channel}"
            val prev = table[key]
            table[key] = IsmDevice(key, reading, (prev?.count ?: 0) + 1, now)
            total++
        }, { unknownCount++ })
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
                    // Devices not heard for an hour are forgotten.
                    table.values.removeAll { now - it.lastSeenMs > 3_600_000 }
                    val snapshot = table.values.sortedByDescending { it.lastSeenMs }
                    val n = total
                    val u = unknownCount
                    scope.launch { devices = snapshot; readings = n; unknown = u }
                }
            }
        }, "sdr-ism").start()
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

    internal fun setForTest(list: List<IsmDevice>, total: Int) { devices = list; readings = total; running = true }

    fun csv(): String = buildString {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        append("derniere_reception_utc,modele,type,identifiant,canal,pile_ok,receptions,mesures\r\n")
        devices.forEach { d ->
            val r = d.last
            val values = r.fields.joinToString("; ") { f -> "${f.name}=${Ism.fmt(f)}${if (f.text == null && f.unit.isNotEmpty()) " " + f.unit else ""}" }
            append(listOf(fmt.format(Date(d.lastSeenMs)), r.model, r.kind, r.id, r.channelName ?: r.channel, r.battery, d.count, "\"$values\"").joinToString(",") { it?.toString() ?: "" })
            append("\r\n")
        }
    }

    companion object {
        private const val POOL = 32
        /** The ISM bands the sensors use, with where. */
        val BANDS = listOf(433_920_000L to "433,92 MHz", 868_300_000L to "868,3 MHz", 315_000_000L to "315 MHz", 345_000_000L to "345 MHz", 915_000_000L to "915 MHz")
    }
}

private fun unit(u: String) = when (u) { "C" -> "°C"; "deg" -> "°"; "" -> ""; else -> u }

private fun value(f: com.allnetworktools.data.sdr.IsmField) = Ism.fmt(f) + unit(f.unit).let { if (it.isEmpty() || f.text != null) "" else if (it == "°") it else " $it" }

@Composable
fun IsmTool(vm: MainViewModel) {
    val c = vm.tools.ism
    val actions = LocalActions.current
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    if (c.devices.isNotEmpty()) TopBarAction(Sym.Download) {
        val snapshot = c.csv()
        actions.saveFile("ism-${Export.stamp(System.currentTimeMillis())}.csv", "text/csv") { snapshot.toByteArray() }
    }
    val band = IsmController.BANDS.first { it.first == c.bandHz }.second
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${c.devices.size}", style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(P(R.plurals.p_devices, c.devices.size), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text(S(R.string.ism_heard, band, c.readings, c.unknown), style = rf(14, 20))
            Row(Modifier.padding(top = 12.dp)) {
                when {
                    c.error != null -> HeroChip(c.error!!, AntTheme.net.poor)
                    c.starting -> HeroChip(S(R.string.starting_the_hackrf), AntTheme.net.fair, blink = true)
                    c.running -> HeroChip(S(R.string.ism_listening), AntTheme.net.good, blink = true)
                    device == null -> HeroChip(S(R.string.no_hackrf_plugged_in), AntTheme.net.poor)
                    else -> HeroChip(S(R.string.ready, device!!.name), AntTheme.net.good)
                }
            }
        }
        val d = device
        if (c.running || c.starting) StartButton(S(R.string.stop), Sym.Stop) { c.stop() }
        else StartButton(S(R.string.start_receiving), Sym.PlayArrow, enabled = d != null) { d?.let(c::start) }
        if (c.devices.isEmpty()) {
            if (c.running) ToolEmpty(Sym.Thermostat, S(R.string.tpms_none_yet), S(R.string.ism_none_yet_body), null)
        } else {
            val now = System.currentTimeMillis()
            SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
                c.devices.forEachIndexed { i, s ->
                    if (i > 0) Hairline()
                    DeviceRow(s, now)
                }
            }
        }
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(S(R.string.frequency), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    IsmController.BANDS.forEach { (hz, label) ->
                        AntFilterChip(label, c.bandHz == hz, { if (c.bandHz != hz && !c.starting) { if (c.running) c.stop(); c.bandHz = hz } })
                    }
                }
                GainSettings(c.gain) { c.applyGains() }
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(S(R.string.ism_how_it_works, Ism.deviceNames.size), style = rf(13, 18), color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DeviceRow(s: IsmDevice, now: Long) {
    val acc = AntTheme.accent
    val r = s.last
    val icon = when (r.kind) { "Weather" -> Sym.Thermostat; "Energy" -> Sym.Bolt; "Remote" -> Sym.Sensors; "TPMS" -> Sym.TireRepair; else -> Sym.Sensors }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LeadingIcon(icon, acc.container, acc.onContainer, 40.dp, RoundedCornerShape(12.dp), 22.dp)
        Column(Modifier.weight(1f)) {
            Text(r.model, style = rf(15, 20, 600), maxLines = 1)
            val ago = ((now - s.lastSeenMs) / 1000).coerceAtLeast(0)
            Text(
                listOfNotNull(
                    "ID ${r.id}",
                    (r.channelName ?: r.channel?.toString())?.let { S(R.string.ism_channel, it) },
                    if (r.battery == false) S(R.string.tpms_battery_low) else null,
                    "×${s.count}",
                    if (ago <= 2) S(R.string.just_now) else S(R.string.s_ago, ago.toString()),
                ).joinToString(" · "),
                style = rf(12, 16, tnum = true), color = if (r.battery == false) cs.error else cs.onSurfaceVariant,
            )
            if (r.fields.isNotEmpty()) {
                Text(r.fields.joinToString(" · ") { value(it) }, Modifier.padding(top = 4.dp), style = rf(14, 20, 500, tnum = true))
            }
        }
    }
}
