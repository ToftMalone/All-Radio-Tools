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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.data.sdr.HackRf
import com.allnetworktools.data.sdr.PocsagMessage
import com.allnetworktools.data.sdr.PocsagNetwork
import com.allnetworktools.data.sdr.PocsagNetworks
import com.allnetworktools.data.sdr.PocsagReceiver
import com.allnetworktools.data.sdr.SdrDevice
import com.allnetworktools.data.sdr.SdrRepository
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
import com.allnetworktools.ui.tools.HostInputField
import com.allnetworktools.ui.tools.StartButton
import com.allnetworktools.ui.tools.ToolEmpty
import com.allnetworktools.util.Export
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.allnetworktools.R
import com.allnetworktools.util.S
import com.allnetworktools.util.P

/**
 * POCSAG pagers: every channel of a paging network heard at once from one 2 MS/s capture, at the three bit rates.
 * Messages stay in memory for the session only.
 */
class PocsagController(private val repo: SdrRepository, private val scope: CoroutineScope, private val onAcquire: () -> Unit = {}) {
    /** A network from [PocsagNetworks], or null for the single frequency in [customMhz]. */
    var network by mutableStateOf<PocsagNetwork?>(PocsagNetworks.France)
    var customMhz by mutableStateOf("466.075")
    var lnaGain by mutableIntStateOf(32)
    var vgaGain by mutableIntStateOf(30)
    var amp by mutableStateOf(true)
    var running by mutableStateOf(false)
        private set
    var starting by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var messages by mutableStateOf<List<PocsagMessage>>(emptyList())
        private set
    var listening by mutableStateOf<List<Long>>(emptyList())
        private set

    private var radio: HackRf? = null
    @Volatile private var dspRunning = false

    fun parsedCustomHz(): Long? = customMhz.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in 100.0..1000.0 }?.let { (it * 1e6).roundToLong() }

    fun channels(): List<Long>? = network?.channelsHz ?: parsedCustomHz()?.let { listOf(it) }

    fun start(device: SdrDevice) {
        if (running || starting) return
        val chans = channels() ?: run { error = S(R.string.invalid_frequency_100_to_1000_mhz); return }
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
                val rate = PocsagReceiver.SAMPLE_RATE
                val centre = PocsagReceiver.centre(chans)
                val ok = withContext(Dispatchers.IO) {
                    r.setSampleRate(rate, HackRf.filterFor(rate)) && r.setFrequency(centre) &&
                        r.setLnaGain(lna) && r.setVgaGain(vga) && r.setAmp(withAmp)
                }
                if (!ok) { r.close(); error = S(R.string.the_hackrf_did_not_accept_the); return@launch }
                radio = r
                listening = chans
                startPipeline(r, chans)
                running = true
            } finally {
                starting = false
            }
        }
    }

    private fun startPipeline(r: HackRf, chans: List<Long>) {
        val free = ArrayBlockingQueue<ByteArray>(POOL)
        val full = ArrayBlockingQueue<Pair<ByteArray, Int>>(POOL)
        repeat(POOL) { free.add(ByteArray(131072)) }
        val rx = PocsagReceiver(chans) { m -> scope.launch { messages = (listOf(m) + messages).take(MAX) } }
        dspRunning = true
        Thread({
            while (dspRunning) {
                val item = full.poll(200, TimeUnit.MILLISECONDS) ?: continue
                rx.feed(item.first, item.second)
                free.offer(item.first)
            }
        }, "sdr-pocsag").start()
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
        listening = emptyList()
        if (r != null) scope.launch(Dispatchers.IO) { r.close() }
    }

    internal fun setForTest(list: List<PocsagMessage>) { messages = list; listening = PocsagNetworks.France.channelsHz; running = true }

    fun csv(): String = buildString {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        fun cell(v: Any?): String { val s = v?.toString() ?: ""; return if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s }
        append("heure_utc,frequence_mhz,bauds,ric,fonction,type,texte\r\n")
        messages.reversed().forEach { m ->
            append(listOf(fmt.format(Date(m.timeMs)), m.freqMhz, m.baud, m.ric, m.functionLetter, m.kind.name.lowercase(), m.text).joinToString(",") { cell(it) })
            append("\r\n")
        }
    }

    companion object {
        private const val POOL = 32
        private const val MAX = 500
    }
}

private fun mhzList(hz: List<Long>) = hz.joinToString(" · ") { "%.4f".format(Locale.getDefault(), it / 1e6).trimEnd('0').trimEnd(',', '.') }

@Composable
fun PocsagTool(vm: MainViewModel) {
    val c = vm.tools.pocsag
    val actions = LocalActions.current
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    if (c.messages.isNotEmpty()) TopBarAction(Sym.Download) {
        val snapshot = c.csv()
        actions.saveFile("pocsag-${Export.stamp(System.currentTimeMillis())}.csv", "text/csv") { snapshot.toByteArray() }
    }
    val chans = c.listening.ifEmpty { c.channels().orEmpty() }
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${c.messages.size}", style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(P(R.plurals.p_messages, c.messages.size), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text(listOfNotNull(c.network?.name, mhzList(chans).takeIf { chans.isNotEmpty() }?.let { "$it MHz" }).joinToString("\n"), style = rf(14, 20))
            Row(Modifier.padding(top = 12.dp)) {
                when {
                    c.error != null -> HeroChip(c.error!!, AntTheme.net.poor)
                    c.starting -> HeroChip(S(R.string.starting_the_hackrf), AntTheme.net.fair, blink = true)
                    c.running -> HeroChip(P(R.plurals.p_listening_channels, c.listening.size, c.listening.size), AntTheme.net.good, blink = true)
                    device == null -> HeroChip(S(R.string.no_hackrf_plugged_in), AntTheme.net.poor)
                    else -> HeroChip(S(R.string.ready, device!!.name), AntTheme.net.good)
                }
            }
        }
        val d = device
        if (c.running || c.starting) StartButton(S(R.string.stop), Sym.Stop) { c.stop() }
        else StartButton(S(R.string.start_receiving), Sym.PlayArrow, enabled = d != null && c.channels() != null) { d?.let(c::start) }
        if (c.messages.isEmpty()) {
            if (c.running) ToolEmpty(Sym.Sms, S(R.string.pocsag_none_yet), S(R.string.pocsag_none_yet_body), null)
        } else {
            SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
                c.messages.take(200).forEachIndexed { i, m ->
                    if (i > 0) Hairline()
                    PageRow(m)
                }
            }
        }
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(S(R.string.pocsag_network), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (PocsagNetworks.all + listOf(null)).forEach { net ->
                        AntFilterChip(net?.name ?: S(R.string.pocsag_custom), c.network == net, {
                            if (c.network != net && !c.starting) { if (c.running) c.stop(); c.network = net }
                        })
                    }
                }
                if (c.network == null && !c.running) HostInputField(c.customMhz, { c.customMhz = it }, S(R.string.frequency_mhz), Sym.Stream, keyboardType = KeyboardType.Decimal)
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
                Text(S(R.string.pocsag_how_it_works), style = rf(13, 18), color = cs.onSurfaceVariant)
            }
        }
    }
}

private val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

@Composable
private fun PageRow(m: PocsagMessage) {
    val acc = AntTheme.accent
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LeadingIcon(if (m.kind == PocsagMessage.Kind.Tone) Sym.Notifications else Sym.Sms, acc.container, acc.onContainer, 40.dp, RoundedCornerShape(12.dp), 22.dp)
        Column(Modifier.weight(1f)) {
            Text("RIC ${m.ric} · ${m.functionLetter}", style = rf(15, 20, 600, tnum = true), maxLines = 1)
            Text(
                "${clock.format(Date(m.timeMs))} · ${mhzList(listOf((m.freqMhz * 1e6).roundToLong()))} MHz · ${m.baud} bauds",
                style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant,
            )
            Text(
                if (m.kind == PocsagMessage.Kind.Tone) S(R.string.pocsag_tone_only) else m.text,
                Modifier.padding(top = 4.dp), style = rf(13, 18), maxLines = 8,
                color = if (m.kind == PocsagMessage.Kind.Tone) cs.onSurfaceVariant else cs.onSurface,
            )
        }
    }
}
