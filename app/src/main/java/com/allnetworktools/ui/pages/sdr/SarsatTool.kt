package com.allnetworktools.ui.pages.sdr

import androidx.compose.foundation.clickable
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
import com.allnetworktools.data.sdr.Decimator
import com.allnetworktools.data.sdr.Sarsat
import com.allnetworktools.data.sdr.SarsatReceiver
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
import com.allnetworktools.ui.tools.ToolEmpty
import com.allnetworktools.util.Export
import com.allnetworktools.util.P
import com.allnetworktools.util.S
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope

/** A beacon heard, by its 15-hex identity, with its last message. */
data class SarsatBeacon(val beacon: Sarsat.Beacon, val firstMs: Long, val lastMs: Long, val bursts: Int, val hz: Double, val snrDb: Double)

/** First-generation 406 MHz distress beacons (EPIRB, ELT, PLB), 406.0 to 406.1 MHz from one tuning; kept for the session. */
class SarsatController(repo: SdrRepository, scope: CoroutineScope, onAcquire: () -> Unit = {}) :
    SdrRxController(repo, scope, onAcquire, SdrGain(listOf(16, 24, 32, 40), 32, listOf(20, 30, 40), 30, amp = true), "sdr-sarsat") {
    var beacons by mutableStateOf<List<SarsatBeacon>>(emptyList())
        private set
    var bursts by mutableIntStateOf(0)
        private set
    var failed by mutableIntStateOf(0)
        private set
    var selected by mutableStateOf<String?>(null)
    private val table = LinkedHashMap<String, SarsatBeacon>()

    override val sampleRate = 2_000_000
    override fun tuneHz() = SarsatReceiver.CENTRE_HZ - sampleRate / 4

    override fun newSink(): SdrSink {
        val d1 = Decimator(2, 450_000.0 / 2_000_000.0, 32)
        val re = FloatArray(131072 + 8)
        val im = FloatArray(re.size)
        var changed = false
        val rx = SarsatReceiver(1_000_000.0) { b ->
            val now = System.currentTimeMillis()
            synchronized(table) {
                val k = b.beacon.hexId
                val prev = table.remove(k)
                table[k] = SarsatBeacon(b.beacon, prev?.firstMs ?: now, now, (prev?.bursts ?: 0) + 1, SarsatReceiver.CENTRE_HZ + b.carrierHz, b.snrDb)
                changed = true
            }
        }
        return object : SdrSink {
            override fun feed(buf: ByteArray, len: Int) {
                var off = 0
                while (off < len) {
                    val m = minOf(2 * 2 * 65536, len - off)
                    val k = d1.process(buf.copyOfRange(off, off + m), m, re, im, 0)
                    rx.feed(re, im, k)
                    off += m
                }
            }
            override fun tick(nowMs: Long) {
                val list = synchronized(table) { if (changed) { changed = false; table.values.reversed() } else null }
                val b = rx.bursts
                val f = rx.failed
                post { if (list != null) beacons = list; bursts = b; failed = f }
            }
        }
    }

    fun csv(): String = buildString {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        append("derniere_salve_utc,hex_id,type,pays,protocole,identification,latitude,longitude,auto_test,salves,frequence_mhz,message_hex\r\n")
        beacons.forEach { s ->
            val b = s.beacon
            append(
                listOf(
                    fmt.format(Date(s.lastMs)), b.hexId, b.beaconType, "\"${b.country ?: ""}\"", "\"${b.protoName}\"", "\"${b.idText}\"",
                    b.lat?.let { "%.5f".format(Locale.ROOT, it) } ?: "", b.lon?.let { "%.5f".format(Locale.ROOT, it) } ?: "", if (b.selfTest) "1" else "0",
                    s.bursts.toString(), "%.4f".format(Locale.ROOT, s.hz / 1e6), b.rawHex,
                ).joinToString(","),
            )
            append("\r\n")
        }
    }

    internal fun setForTest(list: List<SarsatBeacon>) { beacons = list; markRunningForTest() }
}

@Composable
fun SarsatTool(vm: MainViewModel) {
    val c = vm.tools.sarsat
    val actions = LocalActions.current
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    if (c.beacons.isNotEmpty()) TopBarAction(Sym.Download) {
        val snapshot = c.csv()
        actions.saveFile("406mhz-${Export.stamp(System.currentTimeMillis())}.csv", "text/csv") { snapshot.toByteArray() }
    }
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${c.beacons.size}", style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(P(R.plurals.p_sarsat_beacons, c.beacons.size), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text(S(R.string.sarsat_line, c.bursts, c.beacons.count { it.beacon.selfTest }), style = rf(14, 20, tnum = true))
            Row(Modifier.padding(top = 12.dp)) {
                when {
                    c.error != null -> HeroChip(c.error!!, AntTheme.net.poor)
                    c.starting -> HeroChip(S(R.string.starting_the_hackrf), AntTheme.net.fair, blink = true)
                    c.running -> HeroChip(S(R.string.sarsat_listening), AntTheme.net.good, blink = true)
                    device == null -> HeroChip(S(R.string.no_hackrf_plugged_in), AntTheme.net.poor)
                    else -> HeroChip(S(R.string.ready, device!!.name), AntTheme.net.good)
                }
            }
        }
        val d = device
        if (c.running || c.starting) StartButton(S(R.string.stop), Sym.Stop) { c.stop() }
        else StartButton(S(R.string.start_receiving), Sym.PlayArrow, enabled = d != null) { d?.let(c::start) }
        if (c.beacons.isEmpty()) {
            if (c.running) ToolEmpty(Sym.Campaign, S(R.string.sarsat_none_yet), S(R.string.sarsat_none_yet_body), null)
        } else {
            val now = System.currentTimeMillis()
            SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
                c.beacons.forEachIndexed { i, s ->
                    if (i > 0) Hairline()
                    BeaconRow(s, now, s.beacon.hexId == c.selected, { c.selected = if (c.selected == s.beacon.hexId) null else s.beacon.hexId }) { lat, lon ->
                        actions.openUrl("geo:$lat,$lon?q=$lat,$lon(${s.beacon.hexId})")
                    }
                }
            }
        }
        SectionCard { GainSettings(c.gain) { c.applyGains() } }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(
                    S(R.string.sarsat_how_it_works) + (if (c.failed > 0) "\n\n" + S(R.string.sarsat_failed, c.failed) else ""),
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BeaconRow(s: SarsatBeacon, now: Long, selected: Boolean, onClick: () -> Unit, onMap: (Double, Double) -> Unit) {
    val b = s.beacon
    val acc = AntTheme.accent
    val alert = !b.selfTest
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val icon = when (b.beaconType) { "EPIRB", "Ship security" -> Sym.Boat; "ELT", "ELT-DT" -> Sym.Flight; "PLB" -> Sym.PersonSearch; else -> Sym.Campaign }
        LeadingIcon(icon, if (alert) cs.errorContainer else acc.container, if (alert) cs.onErrorContainer else acc.onContainer, 40.dp, RoundedCornerShape(12.dp), 22.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${b.beaconType} · ${b.hexId}", Modifier.weight(1f), style = rf(15, 20, 600, tnum = true), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val ago = ((now - s.lastMs) / 1000).coerceAtLeast(0)
                Text(if (ago < 60) S(R.string.s_ago, ago.toString()) else S(R.string.min_ago, (ago / 60).toString()), style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant)
            }
            Text(
                listOfNotNull(if (b.selfTest) S(R.string.sarsat_self_test) else S(R.string.sarsat_alert), b.country, if (b.homing) S(R.string.sarsat_homing) else null).joinToString(" · "),
                style = rf(13, 18, 600), color = if (alert) cs.error else cs.onSurface,
            )
            Text(b.idText, style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant, maxLines = if (selected) 6 else 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    b.lat?.let { "%.4f, %.4f".format(Locale.ROOT, b.lat, b.lon) + if (b.fine) "" else " (±%.0f km)".format(Locale.getDefault(), b.precisionDeg * 111) },
                    "%.3f MHz".format(Locale.getDefault(), s.hz / 1e6), "SNR %.0f dB".format(Locale.getDefault(), s.snrDb), "×${s.bursts}",
                ).joinToString(" · "),
                style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant,
            )
            if (selected) {
                Text(b.protoName + (if (b.corrected > 0) " · " + S(R.string.sarsat_corrected, b.corrected) else ""), Modifier.padding(top = 4.dp), style = rf(12, 16), color = cs.onSurfaceVariant)
                Text(b.rawHex, style = rf(11, 15, tnum = true), color = cs.onSurfaceVariant)
                if (b.lat != null && b.lon != null) Text(
                    S(R.string.sarsat_open_map), Modifier.padding(top = 6.dp).clickable { onMap(b.lat, b.lon) }, style = rf(13, 18, 600), color = acc.accent,
                )
            }
        }
    }
}
