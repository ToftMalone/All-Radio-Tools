package com.allnetworktools.ui.pages.sdr

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.R
import com.allnetworktools.data.sdr.Ant
import com.allnetworktools.data.sdr.AntReceiver
import com.allnetworktools.data.sdr.SdrRepository
import com.allnetworktools.data.sdr.Zigbee
import com.allnetworktools.data.sdr.ZigbeeReceiver
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.LeadingIcon
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.SegmentedRow
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
import com.allnetworktools.util.P
import com.allnetworktools.util.S
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope

private fun ago(now: Long, t: Long): String {
    val s = ((now - t) / 1000).coerceAtLeast(0)
    return if (s <= 2) S(R.string.just_now) else if (s < 60) S(R.string.s_ago, s.toString()) else S(R.string.min_ago, (s / 60).toString())
}

private val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }

// ---- Zigbee -------------------------------------------------------------------------------------------

data class ZbNetwork(val pan: Int, val channel: Int, val extPan: Long?, val permitJoin: Boolean?, val frames: Int, val devices: Int, val encrypted: Int, val lastMs: Long)
data class ZbDevice(val key: String, val pan: Int?, val short: Int?, val ext: Long?, val frames: Int, val last: String, val snrDb: Double, val lastMs: Long)
data class ZbRow(val timeMs: Long, val channel: Int, val src: String, val dst: String, val type: String, val info: String, val encrypted: Boolean, val len: Int)

/** Zigbee and other IEEE 802.15.4 networks at 2.4 GHz: one channel, or all sixteen in turn. */
class ZigbeeController(repo: SdrRepository, scope: CoroutineScope, onAcquire: () -> Unit = {}) :
    SdrRxController(repo, scope, onAcquire, SdrGain(listOf(16, 24, 32, 40), 32, listOf(20, 30, 40), 30, amp = true), "sdr-zigbee") {
    /** 11 to 26, or 0 to go through all of them. */
    var channel by mutableIntStateOf(0)
    var listening by mutableIntStateOf(11)
        private set
    var networks by mutableStateOf<List<ZbNetwork>>(emptyList())
        private set
    var devices by mutableStateOf<List<ZbDevice>>(emptyList())
        private set
    var frames by mutableStateOf<List<ZbRow>>(emptyList())
        private set
    var showFrames by mutableStateOf(false)
    private val nets = LinkedHashMap<String, ZbNetwork>()
    private val devs = LinkedHashMap<String, ZbDevice>()
    private val log = ArrayDeque<ZbRow>()

    override val sampleRate = ZigbeeReceiver.SAMPLE_RATE
    override fun tuneHz() = Zigbee.channelHz(if (channel == 0) 11 else channel)

    override fun newSink(): SdrSink {
        var changed = false
        val first = if (channel == 0) 11 else channel
        val rx = ZigbeeReceiver(first) { h ->
            val f = h.frame
            val now = System.currentTimeMillis()
            synchronized(nets) {
                f.pan?.let { pan ->
                    val k = "$pan/${h.channel}"
                    val n = nets[k] ?: ZbNetwork(pan, h.channel, null, null, 0, 0, 0, now)
                    nets[k] = n.copy(extPan = f.extPanId ?: n.extPan, permitJoin = f.permitJoin ?: n.permitJoin, frames = n.frames + 1, encrypted = n.encrypted + if (f.encrypted) 1 else 0, lastMs = now)
                }
                val key = when { f.srcMode == 3 -> "e/${f.srcExt}"; f.srcMode == 2 -> "s/${f.pan}/${f.srcShort}"; f.nwkSrcExt != null -> "e/${f.nwkSrcExt}"; else -> null }
                if (key != null) {
                    val d = devs[key] ?: ZbDevice(key, f.pan, if (f.srcMode == 2) f.srcShort else null, if (f.srcMode == 3) f.srcExt else f.nwkSrcExt, 0, "", h.snrDb, now)
                    devs[key] = d.copy(frames = d.frames + 1, last = f.info, snrDb = h.snrDb, lastMs = now, ext = d.ext ?: f.nwkSrcExt)
                    f.pan?.let { pan -> nets["$pan/${h.channel}"]?.let { n -> nets["$pan/${h.channel}"] = n.copy(devices = devs.values.count { it.pan == pan }) } }
                }
                log.addFirst(ZbRow(now, h.channel, f.src, f.dst, Zigbee.frameTypeName(f.type), f.info, f.encrypted, f.length))
                while (log.size > 200) log.removeLast()
                changed = true
            }
        }
        val re = FloatArray(65536)
        val im = FloatArray(65536)
        var dwellStart = 0L
        return object : SdrSink {
            override fun feed(buf: ByteArray, len: Int) = rx.feedBytes(buf, len, re, im)
            override fun tick(nowMs: Long) {
                if (channel == 0) {
                    // All channels in turn, two seconds each: beacons and periodic reports show which ones are in use.
                    if (dwellStart == 0L) dwellStart = nowMs
                    if (nowMs - dwellStart >= 2000) {
                        dwellStart = nowMs
                        val next = if (rx.channel >= 26) 11 else rx.channel + 1
                        rx.channel = next
                        retune(Zigbee.channelHz(next))
                    }
                }
                val ch = rx.channel
                val snap = synchronized(nets) { if (changed) { changed = false; Triple(nets.values.sortedByDescending { it.lastMs }, devs.values.sortedByDescending { it.lastMs }, log.toList()) } else null }
                post { listening = ch; if (snap != null) { networks = snap.first; devices = snap.second; frames = snap.third } }
            }
        }
    }

    fun csv(): String = buildString {
        append("heure_utc,canal,source,destination,type,contenu,chiffre,octets\r\n")
        frames.asReversed().forEach { f -> append("${utc.format(Date(f.timeMs))},${f.channel},${f.src},${f.dst},${f.type},\"${f.info.replace("\"", "\"\"")}\",${if (f.encrypted) 1 else 0},${f.len}\r\n") }
    }
}

@Composable
fun ZigbeeTool(vm: MainViewModel) {
    val c = vm.tools.zigbee
    val actions = LocalActions.current
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    if (c.frames.isNotEmpty()) TopBarAction(Sym.Download) {
        val snapshot = c.csv()
        actions.saveFile("zigbee-${Export.stamp(System.currentTimeMillis())}.csv", "text/csv") { snapshot.toByteArray() }
    }
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${c.networks.size}", style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(P(R.plurals.p_zwave_networks, c.networks.size), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text(S(R.string.zigbee_line, c.devices.size, c.frames.size), style = rf(14, 20, tnum = true))
            Row(Modifier.padding(top = 12.dp)) {
                when {
                    c.error != null -> HeroChip(c.error!!, AntTheme.net.poor)
                    c.starting -> HeroChip(S(R.string.starting_the_hackrf), AntTheme.net.fair, blink = true)
                    c.running -> HeroChip(S(R.string.zigbee_listening, c.listening, "%.0f".format(Locale.ROOT, Zigbee.channelHz(c.listening) / 1e6)), AntTheme.net.good, blink = true)
                    device == null -> HeroChip(S(R.string.no_hackrf_plugged_in), AntTheme.net.poor)
                    else -> HeroChip(S(R.string.ready, device!!.name), AntTheme.net.good)
                }
            }
        }
        val d = device
        if (c.running || c.starting) StartButton(S(R.string.stop), Sym.Stop) { c.stop() }
        else StartButton(S(R.string.start_receiving), Sym.PlayArrow, enabled = d != null) { d?.let(c::start) }
        SegmentedRow(listOf(false to S(R.string.zigbee_networks_tab), true to S(R.string.mesh_tab_packets)), c.showFrames, { c.showFrames = it }, Modifier.fillMaxWidth())
        val now = System.currentTimeMillis()
        if (c.frames.isEmpty()) {
            if (c.running) ToolEmpty(Sym.Hub, S(R.string.zigbee_none_yet), S(R.string.zigbee_none_yet_body), null)
        } else if (!c.showFrames) {
            c.networks.forEach { n ->
                SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)) {
                    Text(S(R.string.zigbee_pan, Zigbee.short(n.pan), n.channel) + (n.extPan?.let { " · " + Zigbee.ext(it) } ?: ""), style = rf(13, 18, 600, tnum = true), color = cs.onSurfaceVariant)
                    Text(
                        listOfNotNull(S(R.string.zigbee_frames_n, n.frames), if (n.encrypted > 0) S(R.string.zwave_encrypted_n, n.encrypted) else null,
                            n.permitJoin?.let { S(if (it) R.string.zigbee_join_open else R.string.zigbee_join_closed) }, ago(now, n.lastMs)).joinToString(" · "),
                        style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant,
                    )
                    c.devices.filter { it.pan == n.pan }.forEachIndexed { i, dv ->
                        Hairline()
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LeadingIcon(if (dv.short == 0) Sym.Router else Sym.Sensors, AntTheme.accent.container, AntTheme.accent.onContainer, 36.dp, RoundedCornerShape(12.dp), 20.dp)
                            Column(Modifier.weight(1f)) {
                                Text(listOfNotNull(dv.short?.let { if (it == 0) S(R.string.zigbee_coordinator) else Zigbee.short(it) }, dv.ext?.let { Zigbee.ext(it) }).joinToString(" · "), style = rf(13, 18, 600, tnum = true), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                dv.ext?.let { Zigbee.vendor(it) }?.let { Text(it, style = rf(12, 16)) }
                                Text(dv.last, style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(listOf("×${dv.frames}", "SNR %.0f dB".format(Locale.getDefault(), dv.snrDb), ago(now, dv.lastMs)).joinToString(" · "), style = rf(11, 16, tnum = true), color = cs.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        } else {
            SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
                c.frames.take(100).forEachIndexed { i, f ->
                    if (i > 0) Hairline()
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Symbol(if (f.encrypted) Sym.Lock else Sym.LockOpen, size = 18.dp, tint = if (f.encrypted) cs.onSurfaceVariant else AntTheme.accent.accent)
                        Column(Modifier.weight(1f)) {
                            Text("${f.type} · ${f.src} → ${f.dst}", style = rf(13, 18, 600, tnum = true), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(f.info, style = rf(12, 16), maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text(listOf(S(R.string.zigbee_channel_n, f.channel), "${f.len} o", ago(now, f.timeMs)).joinToString(" · "), style = rf(11, 16, tnum = true), color = cs.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(S(R.string.zigbee_channel), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf(0) + (11..26)).forEach { ch ->
                        AntFilterChip(if (ch == 0) S(R.string.zigbee_all_channels) else "$ch", c.channel == ch, { if (c.channel != ch && !c.starting) { if (c.running) c.stop(); c.channel = ch } })
                    }
                }
                Text(S(R.string.zigbee_channel_note), style = rf(12, 16), color = cs.onSurfaceVariant)
                GainSettings(c.gain) { c.applyGains() }
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(S(R.string.zigbee_how_it_works), style = rf(13, 18), color = cs.onSurfaceVariant)
            }
        }
    }
}

// ---- ANT+ ---------------------------------------------------------------------------------------------

data class AntSensor(val key: String, val number: Int, val type: Int, val transType: Int, val pairing: Boolean, val page: String, val text: String, val heartRate: Int?, val powerW: Int?, val cadence: Int?, val temperature: Double?, val maker: String?, val frames: Int, val snrDb: Double, val lastMs: Long)

/** ANT+ sports and fitness sensors on 2457 MHz: heart rate straps, bike speed, cadence and power, footpods, temperature. */
class AntController(repo: SdrRepository, scope: CoroutineScope, onAcquire: () -> Unit = {}) :
    SdrRxController(repo, scope, onAcquire, SdrGain(listOf(16, 24, 32, 40), 32, listOf(20, 30, 40), 30, amp = true), "sdr-ant") {
    var sensors by mutableStateOf<List<AntSensor>>(emptyList())
        private set
    var total by mutableIntStateOf(0)
        private set
    var selected by mutableStateOf<String?>(null)
    private val table = LinkedHashMap<String, AntSensor>()
    private var count = 0

    override val sampleRate = AntReceiver.SAMPLE_RATE
    override fun tuneHz() = Ant.CHANNEL_HZ

    override fun newSink(): SdrSink {
        var changed = false
        val rx = AntReceiver { h ->
            val f = h.frame
            val r = Ant.decode(f.type, f.payload)
            val now = System.currentTimeMillis()
            synchronized(table) {
                val k = "${f.deviceNumber}/${f.type}"
                val p = table[k]
                table[k] = AntSensor(
                    k, f.deviceNumber, f.type, f.transType, f.pairing, r.pageName, r.text,
                    r.heartRate ?: p?.heartRate?.takeIf { r.heartRate == null && f.type != Ant.HEART_RATE }, r.powerW ?: p?.powerW, r.cadenceRpm ?: p?.cadence, r.temperatureC ?: p?.temperature,
                    r.maker ?: p?.maker, (p?.frames ?: 0) + 1, h.snrDb, now,
                )
                count++
                changed = true
            }
        }
        val re = FloatArray(65536)
        val im = FloatArray(65536)
        return object : SdrSink {
            override fun feed(buf: ByteArray, len: Int) = rx.feedBytes(buf, len, re, im)
            override fun tick(nowMs: Long) {
                val (list, n) = synchronized(table) {
                    table.values.removeAll { nowMs - it.lastMs > 600_000 }
                    (if (changed) { changed = false; table.values.sortedByDescending { it.lastMs } } else null) to count
                }
                post { if (list != null) sensors = list; total = n }
            }
        }
    }

    fun csv(): String = buildString {
        append("derniere_reception_utc,numero,type,fabricant,page,valeur,frequence_cardiaque,puissance_w,cadence_rpm,temperature_c,trames\r\n")
        sensors.forEach { s -> append("${utc.format(Date(s.lastMs))},${s.number},\"${Ant.typeName(s.type)}\",\"${s.maker ?: ""}\",\"${s.page}\",\"${s.text}\",${s.heartRate ?: ""},${s.powerW ?: ""},${s.cadence ?: ""},${s.temperature ?: ""},${s.frames}\r\n") }
    }
}

@Composable
fun AntTool(vm: MainViewModel) {
    val c = vm.tools.ant
    val actions = LocalActions.current
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    if (c.sensors.isNotEmpty()) TopBarAction(Sym.Download) {
        val snapshot = c.csv()
        actions.saveFile("antplus-${Export.stamp(System.currentTimeMillis())}.csv", "text/csv") { snapshot.toByteArray() }
    }
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${c.sensors.size}", style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(P(R.plurals.p_ant_sensors, c.sensors.size), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text(S(R.string.ant_line, c.total), style = rf(14, 20, tnum = true))
            Row(Modifier.padding(top = 12.dp)) {
                when {
                    c.error != null -> HeroChip(c.error!!, AntTheme.net.poor)
                    c.starting -> HeroChip(S(R.string.starting_the_hackrf), AntTheme.net.fair, blink = true)
                    c.running -> HeroChip(S(R.string.ant_listening), AntTheme.net.good, blink = true)
                    device == null -> HeroChip(S(R.string.no_hackrf_plugged_in), AntTheme.net.poor)
                    else -> HeroChip(S(R.string.ready, device!!.name), AntTheme.net.good)
                }
            }
        }
        val d = device
        if (c.running || c.starting) StartButton(S(R.string.stop), Sym.Stop) { c.stop() }
        else StartButton(S(R.string.start_receiving), Sym.PlayArrow, enabled = d != null) { d?.let(c::start) }
        val now = System.currentTimeMillis()
        if (c.sensors.isEmpty()) {
            if (c.running) ToolEmpty(Sym.Favorite, S(R.string.ant_none_yet), S(R.string.ant_none_yet_body), null)
        } else SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
            c.sensors.forEachIndexed { i, s ->
                if (i > 0) Hairline()
                val sel = s.key == c.selected
                Row(Modifier.fillMaxWidth().clickable { c.selected = if (sel) null else s.key }.padding(vertical = 10.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val icon = when (s.type) { Ant.HEART_RATE -> Sym.Favorite; Ant.BIKE_POWER, Ant.BIKE_SPEED, Ant.BIKE_CADENCE, Ant.BIKE_SPEED_CADENCE -> Sym.PedalBike; Ant.STRIDE -> Sym.Speed; Ant.ENVIRONMENT -> Sym.Thermostat; else -> Sym.Sensors }
                    LeadingIcon(icon, AntTheme.accent.container, AntTheme.accent.onContainer, 40.dp, RoundedCornerShape(12.dp), 22.dp)
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${Ant.typeName(s.type)} · ${s.number}", Modifier.weight(1f), style = rf(15, 20, 600, tnum = true), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(ago(now, s.lastMs), style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant)
                        }
                        val big = s.heartRate?.let { "$it bpm" } ?: s.powerW?.let { "$it W" + (s.cadence?.let { c2 -> " · $c2 rpm" } ?: "") } ?: s.temperature?.let { "%.1f °C".format(Locale.getDefault(), it) }
                        big?.let { Text(it, style = gs(22, 28, 500, tnum = true)) }
                        Text("${s.page}: ${s.text}", style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = if (sel) 4 else 2, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(s.maker, if (s.pairing) S(R.string.ant_pairing) else null, "×${s.frames}", "SNR %.0f dB".format(Locale.getDefault(), s.snrDb)).joinToString(" · "), style = rf(11, 16, tnum = true), color = cs.onSurfaceVariant)
                    }
                }
            }
        }
        SectionCard { GainSettings(c.gain) { c.applyGains() } }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(S(R.string.ant_how_it_works), style = rf(13, 18), color = cs.onSurfaceVariant)
            }
        }
    }
}
