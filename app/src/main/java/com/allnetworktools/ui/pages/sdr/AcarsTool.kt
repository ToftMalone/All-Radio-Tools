package com.allnetworktools.ui.pages.sdr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.allnetworktools.data.sdr.Acars
import com.allnetworktools.data.sdr.AcarsMessage
import com.allnetworktools.data.sdr.AcarsReceiver
import com.allnetworktools.data.sdr.HackRf
import com.allnetworktools.data.sdr.SdrDevice
import com.allnetworktools.data.sdr.SdrRepository
import com.allnetworktools.ui.LocalActions
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
import com.allnetworktools.util.Export
import com.allnetworktools.util.plural
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

/**
 * ACARS messages from airliners on the three European channels at once (131.525, 131.725 and 131.825 MHz), from one
 * 2.4 MS/s capture centred between them. Kept in memory for the session only.
 */
class AcarsController(private val repo: SdrRepository, private val scope: CoroutineScope, private val onAcquire: () -> Unit = {}) {
    var lnaGain by mutableIntStateOf(32)
    var vgaGain by mutableIntStateOf(30)
    var amp by mutableStateOf(true)
    var running by mutableStateOf(false)
        private set
    var starting by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var messages by mutableStateOf<List<AcarsMessage>>(emptyList())
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
                val rate = AcarsReceiver.SAMPLE_RATE
                val ok = withContext(Dispatchers.IO) {
                    r.setSampleRate(rate, HackRf.filterFor(rate)) && r.setFrequency(Acars.CenterHz) &&
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
        val rx = AcarsReceiver { m -> scope.launch { messages = (listOf(m) + messages).take(MAX) } }
        dspRunning = true
        Thread({
            while (dspRunning) {
                val item = full.poll(200, TimeUnit.MILLISECONDS) ?: continue
                rx.feed(item.first, item.second)
                free.offer(item.first)
            }
        }, "sdr-acars").start()
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

    fun clear() { messages = emptyList() }

    internal fun setForTest(list: List<AcarsMessage>) { messages = list; running = true }

    fun csv(): String = buildString {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        fun cell(v: Any?): String { val s = v?.toString() ?: ""; return if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s }
        append("heure_utc,frequence_mhz,immatriculation,vol,label,bloc,numero,sens,texte\r\n")
        messages.reversed().forEach { m ->
            append(listOf(fmt.format(Date(m.timeMs)), m.freqMhz, m.registration, m.flight, m.label, m.blockId, m.msgNo, if (m.downlink) S(R.string.aircraftground) else S(R.string.groundaircraft), m.text).joinToString(",") { cell(it) })
            append("\r\n")
        }
    }

    companion object {
        private const val POOL = 32
        private const val MAX = 500
    }
}

private val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

@Composable
fun AcarsTool(vm: MainViewModel) {
    val c = vm.tools.acars
    val actions = LocalActions.current
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    if (c.messages.isNotEmpty()) TopBarAction(Sym.Download) {
        val snapshot = c.csv()
        actions.saveFile("acars-${Export.stamp(System.currentTimeMillis())}.csv", "text/csv") { snapshot.toByteArray() }
    }
    val aircraft = c.messages.map { it.registration }.filter { it.isNotBlank() }.distinct().size
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${c.messages.size}", style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(P(R.plurals.p_messages, c.messages.size), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text(S(R.string.n_131_525_131_725_131_825, aircraft, P(R.plurals.p_aircraft, aircraft)), style = rf(14, 20))
            Row(Modifier.padding(top = 12.dp)) {
                when {
                    c.error != null -> HeroChip(c.error!!, AntTheme.net.poor)
                    c.starting -> HeroChip(S(R.string.starting_the_hackrf), AntTheme.net.fair, blink = true)
                    c.running -> HeroChip(S(R.string.listening_on_the_3_channels), AntTheme.net.good, blink = true)
                    device == null -> HeroChip(S(R.string.no_hackrf_plugged_in), AntTheme.net.poor)
                    else -> HeroChip(S(R.string.ready, device!!.name), AntTheme.net.good)
                }
            }
        }
        val d = device
        if (c.running || c.starting) StartButton(S(R.string.stop), Sym.Stop) { c.stop() }
        else StartButton(S(R.string.start_receiving), Sym.PlayArrow, enabled = d != null) { d?.let(c::start) }
        if (c.messages.isNotEmpty()) {
            SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
                c.messages.take(200).forEachIndexed { i, m ->
                    if (i > 0) Hairline()
                    MessageRow(m)
                }
            }
        }
        SectionCard {
            GainSettings(
                c.lnaGain, listOf(16, 24, 32, 40), { c.lnaGain = it; c.applyGains() },
                c.vgaGain, listOf(20, 30, 40), { c.vgaGain = it; c.applyGains() },
                c.amp, { c.amp = !c.amp; c.applyGains() },
            )
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(
                    S(R.string.acars_is_the_text_messaging_between) +
                        S(R.string.messages_are_short_and_spread_out) +
                        S(R.string.the_3_european_channels_are_monitored),
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MessageRow(m: AcarsMessage) {
    val acc = AntTheme.accent
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LeadingIcon(if (m.downlink) Sym.Flight else Sym.CellTower, acc.container, acc.onContainer, 40.dp, RoundedCornerShape(12.dp), 22.dp)
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(m.flight, m.registration.ifBlank { null }).joinToString(" · ").ifEmpty { S(R.string.ground_station) },
                style = rf(15, 20, 600), maxLines = 1,
            )
            Text(
                "${clock.format(Date(m.timeMs))} · ${"%.3f".format(Locale.getDefault(), m.freqMhz)} · ${m.label}" + (Acars.Labels[m.label]?.let { " $it" } ?: "") +
                    if (m.downlink) S(R.string.aircraft_ground) else S(R.string.ground_aircraft),
                style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant,
            )
            if (m.text.isNotBlank()) Text(m.text, Modifier.padding(top = 4.dp), style = rf(13, 18), maxLines = 6)
        }
    }
}
