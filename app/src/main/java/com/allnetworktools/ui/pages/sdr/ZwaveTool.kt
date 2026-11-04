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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.R
import com.allnetworktools.data.sdr.SdrRepository
import com.allnetworktools.data.sdr.Zwave
import com.allnetworktools.data.sdr.ZwaveReceiver
import com.allnetworktools.ui.LocalActions
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

data class ZwNode(val home: String, val id: Int, val sent: Int, val received: Int, val encrypted: Int, val last: String?, val rate: Int?, val snrDb: Double?, val lastMs: Long)
data class ZwFrameRow(val timeMs: Long, val home: String, val src: Int, val dst: String, val rate: Int, val text: String, val encrypted: Boolean, val snrDb: Double, val len: Int)

/** Z-Wave home automation on 868.40 / 868.42 / 869.85 MHz: networks (home ids), their nodes and the frames, for the session. */
class ZwaveController(repo: SdrRepository, scope: CoroutineScope, onAcquire: () -> Unit = {}) :
    SdrRxController(repo, scope, onAcquire, SdrGain(listOf(16, 24, 32, 40), 32, listOf(20, 30, 40), 30, amp = true), "sdr-zwave") {
    var nodes by mutableStateOf<List<ZwNode>>(emptyList())
        private set
    var frames by mutableStateOf<List<ZwFrameRow>>(emptyList())
        private set
    var bad by mutableIntStateOf(0)
        private set
    var showFrames by mutableStateOf(false)
    private val table = LinkedHashMap<String, ZwNode>()
    private val log = ArrayDeque<ZwFrameRow>()

    override val sampleRate = 2_000_000
    override fun tuneHz() = Zwave.EU_CENTRE_HZ

    override fun newSink(): SdrSink {
        var changed = false
        val rx = ZwaveReceiver { h ->
            val f = h.frame
            val now = System.currentTimeMillis()
            val enc = Zwave.encryption(f.payload) != 0
            val text = Zwave.describe(f)
            synchronized(table) {
                fun node(id: Int): ZwNode = table["${f.home}/$id"] ?: ZwNode(f.home, id, 0, 0, 0, null, null, null, 0)
                val s = node(f.src)
                table["${f.home}/${f.src}"] = s.copy(sent = s.sent + 1, encrypted = s.encrypted + if (enc) 1 else 0, last = text.takeIf { it.isNotEmpty() } ?: s.last, rate = h.rate, snrDb = h.snrDb, lastMs = now)
                val dsts = if (f.dst == -1) f.group else if (f.dst in 1..232) listOf(f.dst) else emptyList()
                for (d in dsts) { val n = node(d); table["${f.home}/$d"] = n.copy(received = n.received + 1, lastMs = maxOf(n.lastMs, 1)) }
                log.addFirst(ZwFrameRow(now, f.home, f.src, if (f.dst == -1) f.group.joinToString(",") else if (f.dst == 255) "*" else f.dst.toString(), h.rate, text, enc, h.snrDb, f.length))
                while (log.size > 200) log.removeLast()
                changed = true
            }
        }
        val re = FloatArray(65536)
        val im = FloatArray(65536)
        return object : SdrSink {
            override fun feed(buf: ByteArray, len: Int) = rx.feedBytes(buf, len, re, im)
            override fun tick(nowMs: Long) {
                val (n, l) = synchronized(table) { if (changed) { changed = false; table.values.sortedWith(compareBy({ it.home }, { it.id })) to log.toList() } else null to null }
                val b = rx.bad
                post { if (n != null) nodes = n; if (l != null) frames = l; bad = b }
            }
        }
    }

    fun csv(): String = buildString {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        append("heure_utc,home_id,source,destination,debit,contenu,chiffre,octets\r\n")
        frames.asReversed().forEach { f -> append("${fmt.format(Date(f.timeMs))},${f.home},${f.src},\"${f.dst}\",${Zwave.rateName(f.rate)},\"${f.text}\",${if (f.encrypted) 1 else 0},${f.len}\r\n") }
    }

    internal fun setForTest(n: List<ZwNode>, f: List<ZwFrameRow>) { nodes = n; frames = f; markRunningForTest() }
}

@Composable
fun ZwaveTool(vm: MainViewModel) {
    val c = vm.tools.zwave
    val actions = LocalActions.current
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    if (c.frames.isNotEmpty()) TopBarAction(Sym.Download) {
        val snapshot = c.csv()
        actions.saveFile("zwave-${Export.stamp(System.currentTimeMillis())}.csv", "text/csv") { snapshot.toByteArray() }
    }
    val homes = c.nodes.groupBy { it.home }
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${homes.size}", style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(P(R.plurals.p_zwave_networks, homes.size), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text(S(R.string.zwave_line, c.nodes.size, c.frames.size), style = rf(14, 20, tnum = true))
            Row(Modifier.padding(top = 12.dp)) {
                when {
                    c.error != null -> HeroChip(c.error!!, AntTheme.net.poor)
                    c.starting -> HeroChip(S(R.string.starting_the_hackrf), AntTheme.net.fair, blink = true)
                    c.running -> HeroChip(S(R.string.zwave_listening), AntTheme.net.good, blink = true)
                    device == null -> HeroChip(S(R.string.no_hackrf_plugged_in), AntTheme.net.poor)
                    else -> HeroChip(S(R.string.ready, device!!.name), AntTheme.net.good)
                }
            }
        }
        val d = device
        if (c.running || c.starting) StartButton(S(R.string.stop), Sym.Stop) { c.stop() }
        else StartButton(S(R.string.start_receiving), Sym.PlayArrow, enabled = d != null) { d?.let(c::start) }
        SegmentedRow(listOf(false to S(R.string.mesh_tab_nodes), true to S(R.string.mesh_tab_packets)), c.showFrames, { c.showFrames = it }, Modifier.fillMaxWidth())
        val now = System.currentTimeMillis()
        if (c.frames.isEmpty()) {
            if (c.running) ToolEmpty(Sym.Lightbulb, S(R.string.zwave_none_yet), S(R.string.zwave_none_yet_body), null)
        } else if (!c.showFrames) {
            homes.forEach { (home, list) ->
                SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)) {
                    Text(S(R.string.zwave_network, home), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                    list.forEachIndexed { i, n ->
                        if (i > 0) Hairline()
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LeadingIcon(if (n.id == 1) Sym.Router else Sym.Lightbulb, AntTheme.accent.container, AntTheme.accent.onContainer, 36.dp, RoundedCornerShape(12.dp), 20.dp)
                            Column(Modifier.weight(1f)) {
                                Text(if (n.id == 1) S(R.string.zwave_controller) else S(R.string.zwave_node, n.id), style = rf(14, 20, 600))
                                n.last?.let { Text(it, style = rf(12, 16), maxLines = 2, overflow = TextOverflow.Ellipsis) }
                                Text(
                                    listOfNotNull(S(R.string.zwave_sent_received, n.sent, n.received), n.rate?.let { Zwave.rateName(it) }, n.snrDb?.let { "SNR %.0f dB".format(Locale.getDefault(), it) },
                                        if (n.encrypted > 0) S(R.string.zwave_encrypted_n, n.encrypted) else null).joinToString(" · "),
                                    style = rf(11, 16, tnum = true), color = cs.onSurfaceVariant,
                                )
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
                            Text("${f.home} · ${f.src} → ${f.dst}", style = rf(13, 18, 600, tnum = true))
                            Text(f.text.ifEmpty { "—" }, style = rf(13, 18), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val ago = ((now - f.timeMs) / 1000).coerceAtLeast(0)
                            Text(listOf(Zwave.rateName(f.rate), "${f.len} o", "SNR %.0f dB".format(Locale.getDefault(), f.snrDb), if (ago < 60) S(R.string.s_ago, ago.toString()) else S(R.string.min_ago, (ago / 60).toString())).joinToString(" · "), style = rf(11, 16, tnum = true), color = cs.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        SectionCard { GainSettings(c.gain) { c.applyGains() } }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(S(R.string.zwave_how_it_works) + (if (c.bad > 0) "\n\n" + S(R.string.mesh_bad_crc, c.bad) else ""), style = rf(13, 18), color = cs.onSurfaceVariant)
            }
        }
    }
}
