package com.allnetworktools.ui.pages.sdr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.allnetworktools.MainViewModel
import com.allnetworktools.R
import com.allnetworktools.data.sdr.MeshBand
import com.allnetworktools.data.sdr.Meshtastic
import com.allnetworktools.data.sdr.MeshtasticTracker
import com.allnetworktools.data.sdr.SdrRepository
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.BtnKind
import com.allnetworktools.ui.tools.HostInputField
import com.allnetworktools.ui.tools.ToolButton
import com.allnetworktools.ui.tools.ToolButtons
import com.allnetworktools.util.S
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.ui.components.SectionCard

/**
 * Meshtastic nodes and messages on the default channels (EU 868 or EU 433). Channels the user adds with their key are
 * read too, for this session only: nothing is stored.
 */
class MeshtasticController(repo: SdrRepository, scope: CoroutineScope, onAcquire: () -> Unit = {}) : MeshController(repo, scope, onAcquire, "sdr-meshtastic") {
    var bandKey by mutableStateOf("868")
    /** The free frequency (MHz, as typed) and the preset read there; used with the band "free". */
    var frequencyMhz by mutableStateOf("906.875")
    var presetName by mutableStateOf("LongFast")

    fun parsedHz(): Long? = frequencyMhz.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in 400.0..1000.0 }?.let { (it * 1e6).roundToLong() }

    override val band: MeshBand get() = when (bandKey) {
        "433" -> Meshtastic.EU433
        "free" -> Meshtastic.custom(parsedHz() ?: 906_875_000L, Meshtastic.PRESETS.first { it.name == presetName })
        else -> Meshtastic.EU868
    }
    override val tracker = MeshtasticTracker()

    /** Channels added by the user: name and key. */
    var extra by mutableStateOf<List<Meshtastic.Channel>>(emptyList())
        private set

    /** Adds a channel from its name and its key as the apps show it (base64); false when the key is not valid. */
    fun addChannel(name: String, keyBase64: String): Boolean {
        val raw = runCatching { java.util.Base64.getDecoder().decode(keyBase64.trim().replace('-', '+').replace('_', '/')) }.getOrNull() ?: return false
        val key = Meshtastic.expandKey(raw) ?: return false
        val n = name.trim()
        if (n.isEmpty() || n.length > 11) return false
        extra = extra.filterNot { it.name == n } + Meshtastic.Channel(n, key)
        tracker.channels = extra + Meshtastic.publicChannels
        return true
    }

    fun removeChannel(name: String) {
        extra = extra.filterNot { it.name == name }
        tracker.channels = extra + Meshtastic.publicChannels
    }

    // ---- node: the radio takes part in the mesh, announcing itself and its position on the default channel ----

    /** This node's number, made once and kept, as a real node's comes from its radio's address. */
    val nodeNum: Int by lazy {
        com.allnetworktools.data.sdr.TxSettings.intOnce("mesh_node") { kotlin.random.Random.nextInt(0x10000000, 0x7FFFFFFF) }
    }
    var nodeOn by mutableStateOf(false)
        private set
    var nodeName by mutableStateOf(com.allnetworktools.data.sdr.TxSettings.string("mesh_name", ""))
        private set
    var positionMinutes by mutableStateOf(com.allnetworktools.data.sdr.TxSettings.string("mesh_every", "30").toIntOrNull() ?: 30)
        private set
    /** Position precision sent, in bits (32 exact, 13 about 6 km); 0 sends no position. */
    var precisionBits by mutableStateOf(com.allnetworktools.data.sdr.TxSettings.string("mesh_precision", "13").toIntOrNull() ?: 13)
        private set
    var sentPackets by mutableStateOf(0)
        private set
    var lastSentMs by mutableStateOf(0L)
        private set
    /** The phone's latest fix, kept up to date by the screen. */
    @Volatile var fix: android.location.Location? = null
    private var nodeJob: kotlinx.coroutines.Job? = null

    val shortName: String get() = "%04x".format(nodeNum and 0xFFFF)
    val longName: String get() = nodeName.ifBlank { "All Radio Tools $shortName" }

    fun updateNodeName(v: String) { nodeName = v.take(36); com.allnetworktools.data.sdr.TxSettings.putString("mesh_name", nodeName) }
    fun updatePositionMinutes(v: Int) { positionMinutes = v; com.allnetworktools.data.sdr.TxSettings.putString("mesh_every", "$v") }
    fun updatePrecision(v: Int) { precisionBits = v; com.allnetworktools.data.sdr.TxSettings.putString("mesh_precision", "$v") }

    /** Where the node transmits: LongFast on the band's channel, or the free frequency with its preset. */
    class TxChannel(val hz: Long, val sf: Int, val bwHz: Double, val name: String)

    fun txChannel(): TxChannel? = when (bandKey) {
        "433" -> TxChannel(433_875_000L, 11, 250_000.0, "LongFast")
        "free" -> {
            val p = Meshtastic.PRESETS.first { it.name == presetName }
            parsedHz()?.let { TxChannel(it, p.sf, p.bwHz, Meshtastic.presetFor(p.sf, p.bwHz) ?: p.name) }
        }
        else -> TxChannel(869_525_000L, 11, 250_000.0, "LongFast")
    }

    fun txEmission(): com.allnetworktools.data.sdr.Emission? = txChannel()?.let {
        com.allnetworktools.data.sdr.Emission.around(it.hz, (it.bwHz / 2).toInt(), (it.bwHz / 2).toInt(), com.allnetworktools.data.sdr.TxUse.Ism)
    }

    /** What the node was asked and answers once the channel is free: the packet's port, payload and who gets it. */
    private class Reply(val to: Int, val port: Int, val payload: ByteArray, val requestId: Int)
    private val replies = java.util.concurrent.ConcurrentLinkedQueue<Reply>()
    private val answered = LinkedHashMap<Long, Long>()
    private val lastInfoTo = HashMap<Int, Long>()
    var answeredCount by mutableStateOf(0)
        private set

    /**
     * A real node answers what is asked of it: a traceroute, a request for its NodeInfo or its position, and an
     * acknowledgement for a message that asks for one. Only what is addressed to this node (or a NodeInfo request to
     * everyone) on the channel it transmits on, and only what the channel key opens: direct messages encrypted for this
     * node alone (PKI) stay unreadable, so they get no answer.
     */
    override fun onFrame(f: com.allnetworktools.data.sdr.MeshFrame) {
        if (!nodeOn || !f.frame.crcOk || f.frame.syncWord != Meshtastic.SYNC_WORD) return
        val ch = txChannel() ?: return
        if (kotlin.math.abs(f.hz - ch.hz) > 50_000 || f.sf != ch.sf) return
        val p = Meshtastic.decode(f.frame.payload, tracker.channels) ?: return
        val h = p.header
        val d = p.data ?: return
        if (h.from == nodeNum || h.from == 0 || h.from == Meshtastic.BROADCAST) return
        // Only the channel this node transmits on, so the answer is read by the one who asked.
        if (p.channel?.name != ch.name || !p.channel.key.contentEquals(Meshtastic.DEFAULT_PSK)) return
        val toUs = h.to == nodeNum
        if (!toUs && !(h.broadcast && d.port == Meshtastic.PORT_NODEINFO && d.wantResponse)) return
        val now = System.currentTimeMillis()
        synchronized(answered) {
            val k = (h.from.toLong() shl 32) or (h.id.toLong() and 0xFFFFFFFFL)
            if (answered.containsKey(k)) return
            answered[k] = now
            while (answered.size > 200) answered.remove(answered.keys.first())
        }
        val reply: Reply? = when {
            d.port == Meshtastic.PORT_TRACEROUTE && d.wantResponse && toUs ->
                Reply(h.from, Meshtastic.PORT_TRACEROUTE, com.allnetworktools.data.sdr.MeshtasticTx.routeDiscovery(Meshtastic.route(d.payload)?.towards ?: emptyList()), h.id)
            d.port == Meshtastic.PORT_NODEINFO && d.wantResponse && (toUs || h.broadcast) -> {
                // Not more than one NodeInfo every few minutes to the same node, as the firmware does.
                val last = synchronized(lastInfoTo) { lastInfoTo[h.from] ?: 0L }
                if (now - last < 5 * 60_000L) null else {
                    synchronized(lastInfoTo) { lastInfoTo[h.from] = now }
                    Reply(h.from, Meshtastic.PORT_NODEINFO, com.allnetworktools.data.sdr.MeshtasticTx.user(nodeNum, longName, shortName), h.id)
                }
            }
            d.port == Meshtastic.PORT_POSITION && d.wantResponse && toUs -> positionPayload()?.let { Reply(h.from, Meshtastic.PORT_POSITION, it, h.id) }
            // A message that asks for an acknowledgement (and is not itself an answer) gets one.
            toUs && h.wantAck && d.port != Meshtastic.PORT_ROUTING -> Reply(h.from, Meshtastic.PORT_ROUTING, com.allnetworktools.data.sdr.MeshtasticTx.routingAck(), h.id)
            else -> null
        }
        if (reply != null && replies.size < 8) replies.add(reply)
    }

    private fun sendPacket(device: com.allnetworktools.data.sdr.SdrDevice?, port: Int, payload: ByteArray, to: Int = Meshtastic.BROADCAST, requestId: Int = 0): Boolean {
        val ch = txChannel() ?: return false
        val e = txEmission() ?: return false
        val id = kotlin.random.Random.nextInt(1, Int.MAX_VALUE)
        val pkt = com.allnetworktools.data.sdr.MeshtasticTx.packet(nodeNum, id, ch.name, Meshtastic.DEFAULT_PSK, com.allnetworktools.data.sdr.MeshtasticTx.data(port, payload, requestId = requestId), to = to)
        val symbols = com.allnetworktools.data.sdr.LoraEncoder.symbols(pkt, ch.sf, cr = 1, hasCrc = true, ldro = com.allnetworktools.data.sdr.LoraPhy.lowDataRate(ch.sf, ch.bwHz))
        val stream = com.allnetworktools.data.sdr.TxStream(com.allnetworktools.data.sdr.LoraModulator(symbols, ch.sf, ch.bwHz, TX_RATE), TX_RATE, TX_SHIFT.toDouble())
        return tx.send(device, this, e, TX_RATE, ch.hz - TX_SHIFT, stream) {
            if (tx.error == null) { sentPackets++; lastSentMs = System.currentTimeMillis() }
        }
    }

    private suspend fun sendWhenFree(device: com.allnetworktools.data.sdr.SdrDevice?, port: Int, payload: ByteArray, to: Int = Meshtastic.BROADCAST, requestId: Int = 0) {
        while (tx.transmitting) kotlinx.coroutines.delay(200)
        sendPacket(device, port, payload, to, requestId)
        kotlinx.coroutines.delay(300)
        while (tx.transmitting) kotlinx.coroutines.delay(200)
    }

    private fun positionPayload(): ByteArray? {
        val f = fix ?: return null
        if (precisionBits <= 0) return null
        return com.allnetworktools.data.sdr.MeshtasticTx.position(
            f.latitude, f.longitude, if (f.hasAltitude()) f.altitude.toInt() else null, System.currentTimeMillis() / 1000, precisionBits,
        )
    }

    /** Starts the node: NodeInfo now and every three hours, its position shortly after and then every [positionMinutes]. */
    fun startNode(device: com.allnetworktools.data.sdr.SdrDevice?) {
        if (nodeOn) return
        nodeOn = true
        tx.error = null
        replies.clear()
        // A node listens: it answers what the others ask of it, so reception runs as long as it does.
        if (device != null && !running && !starting) { start(device); startedByNode = true }
        nodeJob = scope.launch {
            val user = com.allnetworktools.data.sdr.MeshtasticTx.user(nodeNum, longName, shortName)
            var lastInfo = 0L
            var lastPos = 0L
            while (nodeOn) {
                val now = System.currentTimeMillis()
                if (now - lastInfo >= 3 * 3_600_000L) { sendWhenFree(device, Meshtastic.PORT_NODEINFO, user); lastInfo = now; kotlinx.coroutines.delay(5_000) }
                if (System.currentTimeMillis() - lastPos >= positionMinutes * 60_000L) {
                    positionPayload()?.let { sendWhenFree(device, Meshtastic.PORT_POSITION, it); lastPos = System.currentTimeMillis() }
                }
                if (tx.error != null) { nodeOn = false; break }
                // Wait for the next announcement, answering what is asked meanwhile (a short pause, as a node waits for a free channel).
                val until = System.currentTimeMillis() + 5_000
                while (nodeOn && System.currentTimeMillis() < until) {
                    val r = replies.poll()
                    if (r == null) { kotlinx.coroutines.delay(250); continue }
                    kotlinx.coroutines.delay(400 + kotlin.random.Random.nextLong(0, 600))
                    sendWhenFree(device, r.port, r.payload, r.to, r.requestId)
                    answeredCount++
                }
            }
        }
    }

    private var startedByNode = false

    fun stopNode() {
        nodeOn = false; nodeJob?.cancel(); nodeJob = null; replies.clear()
        if (startedByNode) { startedByNode = false; stop() }
    }

    private companion object {
        const val TX_RATE = 2_000_000
        const val TX_SHIFT = 300_000L
    }
}

@Composable
fun MeshtasticTool(vm: MainViewModel) {
    val c = vm.tools.meshtastic
    val actions = LocalActions.current
    MeshScreen(
        vm, c, "meshtastic", Sym.Hub, S(R.string.meshtastic_none_yet), S(R.string.meshtastic_none_yet_body), S(R.string.meshtastic_how_it_works),
        canStart = { c.bandKey != "free" || c.parsedHz() != null },
        txPage = { MeshtasticTxPage(vm, c) },
    ) {
        Text(S(R.string.mesh_band), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
        BandChips(listOf("868" to S(R.string.meshtastic_band_868), "433" to S(R.string.meshtastic_band_433), "free" to S(R.string.meshtastic_band_free)), c.bandKey, !c.starting) {
            if (c.running) c.stop()
            c.bandKey = it
        }
        if (c.bandKey == "free") {
            if (!c.running) HostInputField(c.frequencyMhz, { c.frequencyMhz = it }, S(R.string.frequency_mhz), Sym.Antenna, keyboardType = KeyboardType.Decimal)
            BandChips(Meshtastic.PRESETS.map { it.name to it.name }, c.presetName, !c.starting) {
                if (c.running) c.stop()
                c.presetName = it
            }
            Text(
                S(if (c.parsedHz() != null) R.string.meshtastic_free_note else R.string.meshtastic_free_invalid, c.frequencyMhz.replace('.', ',')),
                style = rf(12, 16), color = if (c.parsedHz() != null) cs.onSurfaceVariant else cs.error,
            )
        }
        Text(S(R.string.mesh_channels), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AntFilterChip(S(R.string.meshtastic_default_channels), true, {})
            c.extra.forEach { ch -> AntFilterChip("🔑 ${ch.name} ✕", true, { c.removeChannel(ch.name) }) }
        }
        var name by remember { mutableStateOf("") }
        var key by remember { mutableStateOf("") }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HostInputField(name, { name = it }, S(R.string.meshtastic_channel_name), Sym.Tag, keyboardType = KeyboardType.Text)
            HostInputField(key, { key = it }, S(R.string.meshtastic_channel_key), Sym.Key, keyboardType = KeyboardType.Ascii)
            ToolButtons(
                ToolButton(S(R.string.mesh_add_channel), Sym.Add, BtnKind.Outline) {
                    if (c.addChannel(name, key)) { name = ""; key = ""; actions.toast(S(R.string.mesh_channel_added)) } else actions.toast(S(R.string.meshtastic_invalid_channel))
                },
            )
        }
        Text(S(R.string.mesh_channels_note), style = rf(12, 16), color = cs.onSurfaceVariant)
    }
}

/** The radio as a Meshtastic node: on or off, its name, how often and how precisely it sends its position. */
@Composable
private fun NodeCard(c: MeshtasticController, device: com.allnetworktools.data.sdr.SdrDevice?) {
    // No channel (an invalid free frequency) is out of band; otherwise the policy decides, and null means it may go out.
    val emission = c.txEmission()
    val why = if (emission == null) S(R.string.tx_out_of_band) else com.allnetworktools.data.sdr.TxPolicy.refuse(emission)
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(S(R.string.mesh_node_title), style = rf(15, 20, 600))
            Text(S(R.string.mesh_node_hint, Meshtastic.nodeId(c.nodeNum), c.txChannel()?.let { "%.3f".format(java.util.Locale.getDefault(), it.hz / 1e6) } ?: "—"), style = rf(12, 16), color = cs.onSurfaceVariant)
            HostInputField(c.nodeName, { c.updateNodeName(it) }, S(R.string.mesh_node_name, c.longName), Sym.Edit, keyboardType = KeyboardType.Text)
            Text(S(R.string.mesh_node_every), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 60).forEach { m -> AntFilterChip(S(R.string.aprs_beacon_every, m), c.positionMinutes == m, { c.updatePositionMinutes(m) }) }
            }
            Text(S(R.string.mesh_node_precision), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(32 to S(R.string.mesh_node_exact), 19 to "≈ 90 m", 16 to "≈ 700 m", 13 to "≈ 6 km", 0 to S(R.string.mesh_node_no_position)).forEach { (b, l) ->
                    AntFilterChip(l, c.precisionBits == b, { c.updatePrecision(b) })
                }
            }
            if (c.nodeOn) com.allnetworktools.ui.tools.StartButton(S(R.string.mesh_node_stop), Sym.Stop) { c.stopNode() }
            else com.allnetworktools.ui.tools.StartButton(S(R.string.mesh_node_start), Sym.Hub, enabled = why == null) { c.startNode(device) }
            if (c.sentPackets > 0) Text(
                S(R.string.mesh_node_sent, c.sentPackets, java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(c.lastSentMs))),
                style = rf(12, 16), color = cs.onSurfaceVariant,
            )
            if (why != null) Text(why, style = rf(12, 16), color = cs.onSurfaceVariant)
            TxStatus(c.tx)
        }
    }
}

/** The transmit page: the node, the band it joins, and the transmit power. */
@Composable
private fun MeshtasticTxPage(vm: MainViewModel, c: MeshtasticController) {
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    val positions by vm.positions.collectAsStateWithLifecycle()
    c.fix = positions.fused ?: positions.gnss ?: positions.network
    val emission = c.txEmission()
    val why = if (emission == null) S(R.string.tx_out_of_band) else com.allnetworktools.data.sdr.TxPolicy.refuse(emission)
    TxHero(
        c.longName,
        Meshtastic.nodeId(c.nodeNum) + (c.txChannel()?.let { " · %.3f MHz".format(java.util.Locale.getDefault(), it.hz / 1e6) } ?: ""),
        c.tx, device, why,
    )
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(S(R.string.mesh_band), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
            BandChips(listOf("868" to S(R.string.meshtastic_band_868), "433" to S(R.string.meshtastic_band_433), "free" to S(R.string.meshtastic_band_free)), c.bandKey, !c.starting && !c.nodeOn) {
                if (c.running) c.stop()
                c.bandKey = it
            }
        }
    }
    if (canTransmitWith(device)) {
        NodeCard(c, device)
        TxSettingsCard(amateur = false)
    }
    SectionCard {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            com.allnetworktools.ui.components.Symbol(Sym.Info, size = 22.dp, tint = com.allnetworktools.ui.theme.AntTheme.accent.accent)
            Text(S(R.string.mesh_tx_note), style = rf(13, 18), color = cs.onSurfaceVariant)
        }
    }
}
