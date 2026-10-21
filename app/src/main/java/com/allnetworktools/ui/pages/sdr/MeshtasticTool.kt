package com.allnetworktools.ui.pages.sdr

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.data.sdr.Decimator
import com.allnetworktools.data.sdr.HackRf
import com.allnetworktools.data.sdr.LoraFrame
import com.allnetworktools.data.sdr.LoraReceiver
import com.allnetworktools.data.sdr.MeshPreset
import com.allnetworktools.data.sdr.MeshRadio
import com.allnetworktools.data.sdr.MeshRegion
import com.allnetworktools.data.sdr.Meshtastic
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
import com.allnetworktools.ui.tools.BtnKind
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.HostInputField
import com.allnetworktools.ui.tools.StartButton
import com.allnetworktools.ui.tools.Stepper
import com.allnetworktools.ui.tools.ToolButton
import com.allnetworktools.ui.tools.ToolButtons
import com.allnetworktools.util.Export
import com.allnetworktools.util.plural
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.allnetworktools.R
import com.allnetworktools.util.S
import com.allnetworktools.util.P

/** A packet as shown: the first copy heard, and how many relayed copies followed. */
class MeshEntry(
    val timeMs: Long,
    val packet: Meshtastic.Packet,
    val snrDb: Double,
    val copies: Int = 1,
)

/** What has been learnt about a node from the packets it sent. */
data class MeshNode(
    val num: Long,
    val longName: String? = null,
    val shortName: String? = null,
    val hwModel: Int? = null,
    val role: Int? = null,
    val lastHeardMs: Long = 0,
    val snrDb: Double? = null,
    val hops: Int? = null,
    val battery: Int? = null,
    val voltage: Float? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val altitude: Int? = null,
    val packets: Int = 0,
) {
    val label: String get() = longName?.takeIf { it.isNotBlank() } ?: Meshtastic.nodeId(num)
}

/** A channel as typed in: name (blank = the preset's, like the public channel) and Base64 key. */
data class MeshChannelSetting(val name: String, val key: String) {
    fun toChannel(): Meshtastic.Channel? = Meshtastic.parseKey(key)?.let { Meshtastic.Channel(name.trim(), Meshtastic.expandKey(it)) }
}

/**
 * Meshtastic reception with a HackRF: 2 MS/s with the channel 500 kHz above the tuned frequency (away from the DC
 * spike), filtered and decimated to two samples per chip, then LoRa demodulation, decryption with the configured
 * channels and decoding of the packets. Receive only; everything is kept in memory for the session.
 */
class MeshtasticController(private val repo: SdrRepository, private val scope: CoroutineScope, private val onAcquire: () -> Unit = {}) {
    var region by mutableStateOf(MeshRegion.Eu868)
        private set
    var preset by mutableStateOf(MeshPreset.LongFast)
        private set
    /** Frequency slot (0-based); null = the one nodes pick from the primary channel's name. */
    var slot by mutableStateOf<Int?>(null)
        private set
    /** Frequency typed by hand, in MHz; blank = the region's channel plan. */
    var manualMhz by mutableStateOf("")
        private set
    val channels = mutableStateListOf(MeshChannelSetting("", "AQ=="))

    var lnaGain by mutableIntStateOf(32)
    var vgaGain by mutableIntStateOf(30)
    var amp by mutableStateOf(true)
    var running by mutableStateOf(false)
        private set
    var starting by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var showNodes by mutableStateOf(false)

    var packets by mutableStateOf<List<MeshEntry>>(emptyList())
        private set
    var nodes by mutableStateOf<Map<Long, MeshNode>>(emptyMap())
        private set

    // Reception counters for the diagnosis line.
    var preambles by mutableIntStateOf(0)
        private set
    var headerErrors by mutableIntStateOf(0)
        private set
    var framesBad by mutableIntStateOf(0)
        private set
    var otherLora by mutableIntStateOf(0)
        private set
    var lastSyncWord by mutableIntStateOf(-1)
        private set
    /** USB throughput over the last second; negative until the first measure. */
    var usbMBps by mutableStateOf(-1.0)
        private set

    private var radio: HackRf? = null
    private var device: SdrDevice? = null
    @Volatile private var dspRunning = false
    private val usbBytes = AtomicInteger(0)

    val slots: Int get() = MeshRadio.slots(region, preset)

    private fun primaryName(): String = channels.firstOrNull()?.name?.trim().orEmpty().ifBlank { preset.channelName }

    val autoSlot: Int? get() = MeshRadio.defaultSlot(region, preset, primaryName())

    /** The frequency listened to, in MHz: typed by hand, else the chosen or default slot. */
    val frequencyMhz: Double?
        get() {
            manualMhz.replace(',', '.').toDoubleOrNull()?.takeIf { it in 1.0..6000.0 }?.let { return it }
            val s = slot ?: autoSlot ?: return null
            return MeshRadio.frequencyMhz(region, preset, s)
        }

    fun pickRegion(r: MeshRegion) { region = r; slot = null; reconfigure() }
    fun pickPreset(p: MeshPreset) { preset = p; slot = null; reconfigure() }
    fun stepSlot(delta: Int) {
        val n = slots
        if (n <= 1) return
        slot = Math.floorMod((slot ?: autoSlot ?: 0) + delta, n)
        reconfigure()
    }
    fun typeFrequency(s: String) { manualMhz = s.filter { it.isDigit() || it == ',' || it == '.' }.take(10) }
    fun applyFrequency() = reconfigure()

    fun addChannel(name: String, key: String): Boolean {
        val c = MeshChannelSetting(name.trim(), key.trim())
        if (c.toChannel() == null) return false
        channels += c
        return true
    }

    fun removeChannel(c: MeshChannelSetting) { channels.remove(c) }

    /** Settings changed while listening: start again with them. */
    private fun reconfigure() {
        val d = device
        if (running && d != null) { stop(); start(d) }
    }

    fun start(device: SdrDevice) {
        if (running || starting) return
        val hz = frequencyMhz?.let { (it * 1e6).toLong() } ?: run { error = S(R.string.this_preset_does_not_fit_in); return }
        onAcquire()
        this.device = device
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
                    r.setSampleRate(SAMPLE_RATE, HackRf.filterFor(SAMPLE_RATE)) && r.setFrequency(hz - SAMPLE_RATE / 4) &&
                        r.setLnaGain(lna) && r.setVgaGain(vga) && r.setAmp(withAmp)
                }
                if (!ok) { r.close(); error = S(R.string.the_hackrf_did_not_accept_the); return@launch }
                radio = r
                startPipeline(r, hz.toDouble(), preset)
                running = true
            } finally {
                starting = false
            }
        }
    }

    private fun startPipeline(r: HackRf, hz: Double, p: MeshPreset) {
        val free = ArrayBlockingQueue<ByteArray>(POOL)
        val full = ArrayBlockingQueue<Pair<ByteArray, Int>>(POOL)
        repeat(POOL) { free.add(ByteArray(131072)) }
        val decim = SAMPLE_RATE / (LoraReceiver.OS * p.bandwidthHz)
        // The band plus a margin; 24 taps per output sample keep the chirp's edges flat.
        val decimator = Decimator(decim, 0.62 * p.bandwidthHz / SAMPLE_RATE, taps = 24 * decim)
        val rx = LoraReceiver(p.sf, p.bandwidthHz.toDouble(), hz, MeshRadio.ldro(p)) { f -> scope.launch { onFrame(f, p) } }
        val outRe = FloatArray(131072 / 2 / decim + 8)
        val outIm = FloatArray(outRe.size)
        usbBytes.set(0)
        usbMBps = -1.0
        dspRunning = true
        Thread({
            var lastTick = System.currentTimeMillis()
            while (dspRunning) {
                val item = full.poll(200, TimeUnit.MILLISECONDS)
                if (item != null) {
                    val k = decimator.process(item.first, item.second, outRe, outIm, 0)
                    free.offer(item.first)
                    rx.feed(outRe, outIm, k)
                }
                val now = System.currentTimeMillis()
                if (now - lastTick >= 1000) {
                    val rate = usbBytes.getAndSet(0) / ((now - lastTick) / 1000.0) / 1e6
                    lastTick = now
                    val pre = rx.preambles; val hdr = rx.headerErrors; val bad = rx.framesBad; val sync = rx.lastSyncWord
                    scope.launch { preambles = pre; headerErrors = hdr; framesBad = bad; lastSyncWord = sync; usbMBps = rate }
                }
            }
        }, "sdr-meshtastic").start()
        r.startRx(
            onSamples = { data, len ->
                usbBytes.addAndGet(len)
                free.poll()?.let { b -> System.arraycopy(data, 0, b, 0, len); full.offer(b to len) }
            },
            onError = { msg -> scope.launch { error = msg; stop() } },
        )
    }

    /** A frame from the receiver, on the main thread. */
    internal fun onFrame(f: LoraFrame, p: MeshPreset, now: Long = System.currentTimeMillis()) {
        if (!f.crcOk) return
        val ch = channels.mapNotNull { it.toChannel() }
        val packet = Meshtastic.decode(f.payload, ch, p)
        if (packet == null) { otherLora++; return }
        record(packet, f.snrDb, now)
    }

    private fun record(packet: Meshtastic.Packet, snrDb: Double, now: Long) {
        val h = packet.header
        // Relays repeat a packet with the same sender and id: count them on the first copy.
        val i = packets.indexOfFirst { it.packet.header.from == h.from && it.packet.header.id == h.id }
        packets = if (i >= 0) {
            packets.toMutableList().also { val e = it[i]; it[i] = MeshEntry(e.timeMs, e.packet, e.snrDb, e.copies + 1) }
        } else (listOf(MeshEntry(now, packet, snrDb)) + packets).take(MAX)
        // The node we heard directly is the relay (its last byte) when the packet was relayed; only the original sender
        // is identified in full.
        val old = nodes[h.from] ?: MeshNode(h.from)
        var n = old.copy(lastHeardMs = now, packets = old.packets + if (i >= 0) 0 else 1)
        if (i < 0) n = n.copy(snrDb = snrDb, hops = h.hops)
        when (val c = packet.data?.content) {
            is Meshtastic.Content.NodeInfo -> n = n.copy(longName = c.longName ?: n.longName, shortName = c.shortName ?: n.shortName, hwModel = c.hwModel ?: n.hwModel, role = c.role ?: n.role)
            is Meshtastic.Content.Position -> if (c.lat != null && c.lon != null) n = n.copy(lat = c.lat, lon = c.lon, altitude = c.altitude ?: n.altitude)
            is Meshtastic.Content.Telemetry -> if (c.battery != null || c.voltage != null) n = n.copy(battery = c.battery ?: n.battery, voltage = c.voltage ?: n.voltage)
            is Meshtastic.Content.MapReport -> n = n.copy(longName = c.longName ?: n.longName, shortName = c.shortName ?: n.shortName, hwModel = c.hwModel ?: n.hwModel, lat = c.lat ?: n.lat, lon = c.lon ?: n.lon)
            else -> Unit
        }
        nodes = nodes + (h.from to n)
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

    fun clear() {
        packets = emptyList(); nodes = emptyMap()
        preambles = 0; headerErrors = 0; framesBad = 0; otherLora = 0
    }

    internal fun setForTest(list: List<MeshEntry>, nodes: Map<Long, MeshNode>) { packets = list; this.nodes = nodes; running = true }

    fun csv(): String = buildString {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        fun cell(v: Any?): String { val s = v?.toString() ?: ""; return if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s }
        append(S(R.string.time_utc_from_name_to_channel))
        packets.reversed().forEach { e ->
            val h = e.packet.header
            append(
                listOf(
                    fmt.format(Date(e.timeMs)), Meshtastic.nodeId(h.from), nodes[h.from]?.longName,
                    if (h.to == Meshtastic.BROADCAST) "tous" else Meshtastic.nodeId(h.to), e.packet.channel,
                    e.packet.data?.let { Meshtastic.portName(it.port) } ?: statusText(e.packet.status),
                    describe(e.packet, nodes), "%.1f".format(Locale.ROOT, e.snrDb), h.hops, e.copies,
                ).joinToString(",") { cell(it) },
            )
            append("\r\n")
        }
    }

    companion object {
        const val SAMPLE_RATE = 2_000_000
        private const val POOL = 32
        private const val MAX = 500
    }
}

internal fun statusText(s: Meshtastic.Status): String = when (s) {
    Meshtastic.Status.Decoded -> S(R.string.decrypted)
    Meshtastic.Status.Private -> S(R.string.encrypted_private_message)
    Meshtastic.Status.OtherChannel -> S(R.string.other_channel)
    Meshtastic.Status.WrongKey -> S(R.string.different_key)
}

private fun f1(v: Float) = "%.1f".format(Locale.getDefault(), v)

private fun coords(lat: Double, lon: Double) = "%.5f, %.5f".format(Locale.ROOT, lat, lon)

/** The content of a packet in one or two lines of French. */
internal fun describe(p: Meshtastic.Packet, nodes: Map<Long, MeshNode>): String {
    fun name(num: Long) = nodes[num]?.label ?: Meshtastic.nodeId(num)
    val d = p.data ?: return when (p.status) {
        Meshtastic.Status.Private -> S(R.string.direct_message_encrypted_with_the_recipient)
        Meshtastic.Status.WrongKey -> S(R.string.the_channel_matches_one_of_yours)
        else -> S(R.string.unknown_channel_hash_add_its_name, p.header.channel)
    }
    return when (val c = d.content) {
        is Meshtastic.Content.Text -> c.text
        is Meshtastic.Content.Position -> listOfNotNull(
            if (c.lat != null && c.lon != null) coords(c.lat, c.lon) else S(R.string.hidden_position),
            c.altitude?.let { "$it m" }, c.speedKmh?.let { "$it km/h" }, c.sats?.let { "$it satellites" },
        ).joinToString(" · ")
        is Meshtastic.Content.NodeInfo -> listOfNotNull(
            listOfNotNull(c.longName, c.shortName?.let { "($it)" }).joinToString(" ").ifBlank { null },
            c.hwModel?.let(Meshtastic::hwModel), c.role?.takeIf { it != 0 }?.let(Meshtastic::role),
            if (c.hasPublicKey) S(R.string.public_key) else null,
        ).joinToString(" · ")
        is Meshtastic.Content.Telemetry -> listOfNotNull(
            c.battery?.let { if (it > 100) "secteur" else S(R.string.battery_2, it) }, c.voltage?.let { "${f1(it)} V" },
            c.chUtil?.let { S(R.string.channel_busy, f1(it)) }, c.airUtil?.let { S(R.string.airtime, f1(it)) },
            c.temperature?.let { "${f1(it)} °C" }, c.humidity?.let { S(R.string.humidity_2, f1(it)) }, c.pressure?.let { "${f1(it)} hPa" },
        ).joinToString(" · ").ifEmpty { S(R.string.measurements) }
        is Meshtastic.Content.Routing -> c.error?.let(Meshtastic::routingError) ?: S(R.string.routing)
        is Meshtastic.Content.Traceroute -> {
            val to = (listOf(p.header.from) + c.route + listOf(p.header.to)).map(::name)
            S(R.string.route) + to.joinToString(" → ") + if (c.back.isNotEmpty()) S(R.string.back_via) + c.back.joinToString(" → ", transform = ::name) else ""
        }
        is Meshtastic.Content.Neighbors -> if (c.neighbors.isEmpty()) S(R.string.no_neighbour) else
            c.neighbors.joinToString(" · ") { (n, snr) -> name(n) + (snr?.let { " (${f1(it)} dB)" } ?: "") }
        is Meshtastic.Content.Waypoint -> listOfNotNull(c.name, c.description?.ifBlank { null }, if (c.lat != null && c.lon != null) coords(c.lat, c.lon) else null).joinToString(" · ")
        is Meshtastic.Content.MapReport -> listOfNotNull(c.longName, c.hwModel?.let(Meshtastic::hwModel), c.firmware?.let { "firmware $it" }).joinToString(" · ")
        is Meshtastic.Content.Other -> S(R.string.bytes, c.size)
    }
}

private fun icon(p: Meshtastic.Packet): String = when (p.data?.port) {
    null -> if (p.status == Meshtastic.Status.Private) Sym.Lock else Sym.Key
    1, 7 -> Sym.Forum
    3, 8 -> Sym.LocationOn
    4, 73 -> Sym.Badge
    5 -> Sym.Check
    67 -> Sym.Thermostat
    70 -> Sym.Route
    71 -> Sym.Hub
    else -> Sym.DataObject
}

private val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

@Composable
fun MeshtasticTool(vm: MainViewModel) {
    val c = vm.tools.meshtastic
    val actions = LocalActions.current
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    if (c.packets.isNotEmpty()) TopBarAction(Sym.Download) {
        val snapshot = c.csv()
        actions.saveFile("meshtastic-${Export.stamp(System.currentTimeMillis())}.csv", "text/csv") { snapshot.toByteArray() }
    }
    val freq = c.frequencyMhz
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${c.packets.size}", style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(P(R.plurals.p_packets, c.packets.size), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text(
                "${c.nodes.size} ${P(R.plurals.p_nodes, c.nodes.size)} · " +
                    (freq?.let { "%.3f MHz".format(Locale.getDefault(), it) } ?: S(R.string.frequency_out_of_band)) + " · ${c.preset.channelName}",
                style = rf(14, 20),
            )
            Row(Modifier.padding(top = 12.dp)) {
                when {
                    c.error != null -> HeroChip(c.error!!, AntTheme.net.poor)
                    c.starting -> HeroChip(S(R.string.starting_the_hackrf), AntTheme.net.fair, blink = true)
                    c.running -> HeroChip(S(R.string.listening), AntTheme.net.good, blink = true)
                    device == null -> HeroChip(S(R.string.no_hackrf_plugged_in), AntTheme.net.poor)
                    else -> HeroChip(S(R.string.ready, device!!.name), AntTheme.net.good)
                }
            }
        }
        val d = device
        if (c.running || c.starting) StartButton(S(R.string.stop), Sym.Stop) { c.stop() }
        else StartButton(S(R.string.start_receiving), Sym.PlayArrow, enabled = d != null && freq != null) { d?.let(c::start) }
        if (c.running) Diagnosis(c)
        if (c.packets.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AntFilterChip(S(R.string.packets, c.packets.size), !c.showNodes, { c.showNodes = false })
                AntFilterChip(S(R.string.nodes, c.nodes.size), c.showNodes, { c.showNodes = true })
            }
            SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
                if (c.showNodes) {
                    c.nodes.values.sortedByDescending { it.lastHeardMs }.forEachIndexed { i, n ->
                        if (i > 0) Hairline()
                        NodeRow(n)
                    }
                } else {
                    c.packets.take(200).forEachIndexed { i, e ->
                        if (i > 0) Hairline()
                        PacketRow(e, c.nodes)
                    }
                }
            }
            ToolButtons(ToolButton(S(R.string.clear), Sym.Delete, BtnKind.OutlineOnSurface) { c.clear() })
        }
        FrequencyCard(c)
        ChannelsCard(c)
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
                    S(R.string.meshtastic_is_a_mesh_network_of) +
                        S(R.string.the_public_channel_longfast_key_aq) +
                        S(R.string.nodes_only_transmit_now_and_then),
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Diagnosis(c: MeshtasticController) {
    val sync = c.lastSyncWord.takeIf { it >= 0 }?.let { S(R.string.last_sync_word_0x).format(Locale.ROOT, it) } ?: ""
    val text = when {
        c.usbMBps in 0.0..0.5 -> S(R.string.the_hackrf_sends_almost_nothing_mb).format(Locale.getDefault(), c.usbMBps)
        c.preambles == 0 -> S(R.string.no_lora_frame_yet_nodes_transmit)
        else -> "${c.preambles} ${P(R.plurals.p_frame_starts, c.preambles)} · " +
            "${c.headerErrors} ${P(R.plurals.p_unreadable_headers, c.headerErrors)} · " +
            "${c.framesBad} ${P(R.plurals.p_damaged_frames, c.framesBad)}" +
            (if (c.otherLora > 0) S(R.string.not_meshtastic, c.otherLora) else "") + sync
    }
    Text(text, Modifier.padding(horizontal = 4.dp), style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant)
}

@Composable
private fun PacketRow(e: MeshEntry, nodes: Map<Long, MeshNode>) {
    val acc = AntTheme.accent
    val h = e.packet.header
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LeadingIcon(icon(e.packet), acc.container, acc.onContainer, 40.dp, RoundedCornerShape(12.dp), 22.dp)
        Column(Modifier.weight(1f)) {
            val from = nodes[h.from]?.label ?: Meshtastic.nodeId(h.from)
            val to = if (h.to == Meshtastic.BROADCAST) null else nodes[h.to]?.label ?: Meshtastic.nodeId(h.to)
            Text(from + (to?.let { " → $it" } ?: ""), style = rf(15, 20, 600), maxLines = 1)
            Text(
                listOfNotNull(
                    clock.format(Date(e.timeMs)),
                    e.packet.data?.let { Meshtastic.portName(it.port) } ?: statusText(e.packet.status),
                    e.packet.channel,
                    "SNR %.1f dB".format(Locale.getDefault(), e.snrDb),
                    h.hops?.let { if (it == 0) "direct" else "$it ${P(R.plurals.p_hops, it)}" },
                    if (e.copies > 1) "×${e.copies}" else null,
                ).joinToString(" · "),
                style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant,
            )
            Text(describe(e.packet, nodes), Modifier.padding(top = 4.dp), style = rf(13, 18), maxLines = 6)
        }
    }
}

@Composable
private fun NodeRow(n: MeshNode) {
    val acc = AntTheme.accent
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LeadingIcon(Sym.Hub, acc.container, acc.onContainer, 40.dp, RoundedCornerShape(12.dp), 22.dp)
        Column(Modifier.weight(1f)) {
            Text(listOfNotNull(n.label, n.shortName?.let { "($it)" }).joinToString(" "), style = rf(15, 20, 600), maxLines = 1)
            Text(
                listOfNotNull(
                    Meshtastic.nodeId(n.num), n.hwModel?.let(Meshtastic::hwModel), n.role?.takeIf { it != 0 }?.let(Meshtastic::role),
                ).joinToString(" · "),
                style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 1,
            )
            Text(
                listOfNotNull(
                    S(R.string.seen_at_2, clock.format(Date(n.lastHeardMs))),
                    n.snrDb?.let { "SNR %.1f dB".format(Locale.getDefault(), it) },
                    n.hops?.let { if (it == 0) "direct" else "$it ${P(R.plurals.p_hops, it)}" },
                    n.battery?.let { if (it > 100) "secteur" else S(R.string.battery_2, it) },
                    "${n.packets} ${P(R.plurals.p_packets, n.packets)}",
                ).joinToString(" · "),
                style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant,
            )
            if (n.lat != null && n.lon != null) {
                Text(coords(n.lat, n.lon) + (n.altitude?.let { " · $it m" } ?: ""), Modifier.padding(top = 2.dp), style = rf(13, 18, tnum = true))
            }
        }
    }
}

@Composable
private fun FrequencyCard(c: MeshtasticController) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(S(R.string.region), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MeshRegion.entries.forEach { r -> AntFilterChip(r.label, c.region == r, { c.pickRegion(r) }) }
            }
            Text(S(R.string.network_preset), Modifier.padding(top = 4.dp), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MeshPreset.entries.forEach { p -> AntFilterChip(p.channelName, c.preset == p, { c.pickPreset(p) }) }
            }
            Text(c.preset.description, style = rf(12, 16), color = cs.onSurfaceVariant)
            if (c.slots > 1 && c.manualMhz.isBlank()) {
                val s = c.slot ?: c.autoSlot ?: 0
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(S(R.string.frequency_slot_of, c.slots), style = rf(15, 20, 500))
                        Text(if (c.slot == null) S(R.string.the_primary_channel_s_like_the) else S(R.string.chosen_by_hand), style = rf(12, 16), color = cs.onSurfaceVariant)
                    }
                    Stepper(s + 1, { c.stepSlot(-1) }, { c.stepSlot(1) })
                }
            }
            Box(Modifier.padding(top = 4.dp)) {
                HostInputField(
                    c.manualMhz, c::typeFrequency, S(R.string.manual_frequency_mhz), icon = Sym.Antenna,
                    keyboardType = KeyboardType.Decimal, onDone = { c.applyFrequency() },
                )
            }
            Text(
                if (c.manualMhz.isBlank()) S(R.string.empty_the_meshtastic_band_plan_frequency, c.frequencyMhz?.let { "%.3f MHz".format(Locale.getDefault(), it) } ?: S(R.string.none_here))
                else S(R.string.confirm_with_the_keyboard_s_enter),
                style = rf(12, 16), color = cs.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChannelsCard(c: MeshtasticController) {
    var name by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(S(R.string.decrypted_channels), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
            c.channels.forEachIndexed { i, ch ->
                if (i > 0) Hairline()
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Symbol(if (ch.key == "AQ==") Sym.LockOpen else Sym.Lock, size = 22.dp, tint = cs.onSurfaceVariant)
                    Column(Modifier.weight(1f)) {
                        Text(ch.name.ifBlank { "Public (${c.preset.channelName})" }, style = rf(15, 20, 500), maxLines = 1)
                        Text(S(R.string.key, ch.key.ifBlank { "aucune" }), style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 1)
                    }
                    if (c.channels.size > 1) {
                        Box(Modifier.size(40.dp).clip(CircleShape).clickable { c.removeChannel(ch) }, contentAlignment = Alignment.Center) {
                            Symbol(Sym.Delete, size = 22.dp, tint = cs.onSurfaceVariant)
                        }
                    }
                }
            }
            Text(S(R.string.add_a_channel), Modifier.padding(top = 8.dp), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
            HostInputField(name, { name = it.take(11) }, S(R.string.channel_name_empty_the_preset_s), icon = Sym.Tag, keyboardType = KeyboardType.Text)
            HostInputField(key, { key = it.trim(); bad = false }, S(R.string.base64_key_aq_for_the_default), icon = Sym.Key, keyboardType = KeyboardType.Ascii)
            if (bad) Text(S(R.string.invalid_key_1_16_or_32), style = rf(12, 16), color = cs.error)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AntFilterChip(S(R.string.add), false, {
                    if (c.addChannel(name, key.ifBlank { "AQ==" })) { name = ""; key = "" } else bad = true
                })
            }
        }
    }
}
