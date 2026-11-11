package com.allnetworktools.ui.pages.sdr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.R
import com.allnetworktools.data.sdr.Aprs
import com.allnetworktools.data.sdr.AprsTracker
import com.allnetworktools.data.sdr.LoraAprs
import com.allnetworktools.data.sdr.MeshRadio
import com.allnetworktools.data.sdr.SdrRepository
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.Hairline
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
import com.allnetworktools.util.P
import com.allnetworktools.util.S
import kotlinx.coroutines.CoroutineScope

/** APRS beacons sent over LoRa on 433.775 MHz (SF12) and 434.855 MHz (SF9), from one tuning; kept for the session. */
class LoraAprsController(repo: SdrRepository, scope: CoroutineScope, onAcquire: () -> Unit = {}) :
    SdrRxController(repo, scope, onAcquire, SdrGain(listOf(16, 24, 32, 40), 32, listOf(20, 30, 40), 30, amp = true), "sdr-lora-aprs") {
    var stations by mutableStateOf<List<AprsInfo>>(emptyList())
        private set
    var totalPackets by mutableIntStateOf(0)
        private set
    var badFrames by mutableIntStateOf(0)
        private set
    var selected by mutableStateOf<String?>(null)
    private val tracker = AprsTracker()
    private var bad = 0

    override val sampleRate = MeshRadio.SAMPLE_RATE
    override fun tuneHz() = LoraAprs.EU.loHz

    override fun newSink(): SdrSink {
        val radio = MeshRadio(LoraAprs.EU) { f ->
            if (f.frame.syncWord != LoraAprs.SYNC_WORD && !f.frame.crcOk) return@MeshRadio
            if (!f.frame.crcOk) { bad++; return@MeshRadio }
            val frame = LoraAprs.parse(f.frame.payload) ?: return@MeshRadio
            val r = Aprs.decode(frame) ?: return@MeshRadio
            synchronized(tracker) { tracker.update(r, System.currentTimeMillis()) }
        }
        return object : SdrSink {
            override fun feed(buf: ByteArray, len: Int) = radio.feed(buf, len)
            override fun close() = radio.close()
            override fun tick(nowMs: Long) {
                val (list, total) = synchronized(tracker) {
                    tracker.prune(nowMs)
                    tracker.stations.values.map {
                        AprsInfo(it.key, it.name ?: it.call, it.isObject, it.lat, it.lon, it.speedKn, it.courseDeg, it.altitudeM, it.symbol, it.comment,
                            it.status, it.weather, it.via, it.packets, it.messages, ((nowMs - it.lastSeenMs) / 1000).toInt())
                    }.sortedBy { it.ageS } to tracker.total
                }
                val b = bad
                post { stations = list; totalPackets = total; badFrames = b }
            }
        }
    }

    internal fun setForTest(list: List<AprsInfo>, total: Int) { stations = list; totalPackets = total; markRunningForTest() }
}

@Composable
fun LoraAprsTool(vm: MainViewModel) {
    val c = vm.tools.loraAprs
    val actions = LocalActions.current
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    val positions by vm.positions.collectAsStateWithLifecycle()
    val loc = positions.fused ?: positions.gnss ?: positions.network
    val me = loc?.let { it.latitude to it.longitude }
    val withPos = c.stations.filter { it.lat != null }
    if (c.stations.isNotEmpty()) TopBarAction(Sym.Download) {
        val snapshot = c.stations
        val now = System.currentTimeMillis()
        actions.saveFile("lora-aprs-${Export.stamp(now)}.csv", "text/csv") { Export.aprs(snapshot, now).toByteArray() }
    }
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${c.stations.size}", style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(P(R.plurals.p_stations, c.stations.size), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text(S(R.string.lora_aprs_line, withPos.size, c.totalPackets), style = rf(14, 20, tnum = true))
            Row(Modifier.padding(top = 12.dp)) {
                when {
                    c.error != null -> HeroChip(c.error!!, AntTheme.net.poor)
                    c.starting -> HeroChip(S(R.string.starting_the_hackrf), AntTheme.net.fair, blink = true)
                    c.running -> HeroChip(S(R.string.lora_aprs_listening), AntTheme.net.good, blink = true)
                    device == null -> HeroChip(S(R.string.no_hackrf_plugged_in), AntTheme.net.poor)
                    else -> HeroChip(S(R.string.ready, device!!.name), AntTheme.net.good)
                }
            }
        }
        val d = device
        if (c.running || c.starting) StartButton(S(R.string.stop), Sym.Stop) { c.stop() }
        else StartButton(S(R.string.start_receiving), Sym.PlayArrow, enabled = d != null) { d?.let(c::start) }
        val tab = ToolTabRow("LoraAprsTool0", map = true)
        if (tab == ToolTab.Map) {
            AprsMap(c.selected, { c.selected = it }, withPos, me, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)))
        }
        if (tab == ToolTab.List) {
            if (c.stations.isEmpty()) {
                if (c.running) ToolEmpty(Sym.Radio, S(R.string.lora_aprs_none_yet), S(R.string.lora_aprs_none_yet_body), null)
            } else {
                SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
                    c.stations.forEachIndexed { i, s ->
                        if (i > 0) Hairline()
                        StationRow(s, me, s.key == c.selected) { c.selected = if (c.selected == s.key) null else s.key }
                    }
                }
            }
        }
        if (tab == ToolTab.Settings) {
            SectionCard { GainSettings(c.gain) { c.applyGains() } }
        }
        if (tab == ToolTab.Settings) {
            SectionCard {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                    Text(
                        S(R.string.lora_aprs_how_it_works) + (if (c.badFrames > 0) "\n\n" + S(R.string.mesh_bad_crc, c.badFrames) else ""),
                        style = rf(13, 18), color = cs.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
