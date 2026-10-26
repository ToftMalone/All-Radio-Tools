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
import com.allnetworktools.R
import com.allnetworktools.data.sdr.SdrRepository
import com.allnetworktools.data.sdr.Wmbus
import com.allnetworktools.data.sdr.WmbusReceiver
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

/** One meter: its last frame, how it came, how often and when. */
data class WmbusMeter(val key: String, val frame: Wmbus.Frame, val mode: Wmbus.Mode, val snrDb: Double, val count: Int, val lastSeenMs: Long)

/** Wireless M-Bus meters around, T1 and C1 on 868.95 MHz. Kept in memory for the session only. */
class WmbusController(repo: SdrRepository, scope: CoroutineScope, onAcquire: () -> Unit = {}) :
    SdrRxController(repo, scope, onAcquire, SdrGain(listOf(16, 24, 32, 40), 32, listOf(20, 30, 40), 30, amp = true), "sdr-wmbus") {
    var meters by mutableStateOf<List<WmbusMeter>>(emptyList())
        private set
    var telegrams by mutableIntStateOf(0)
        private set

    override val sampleRate = WmbusReceiver.SAMPLE_RATE
    override fun tuneHz() = WmbusReceiver.CENTRE_HZ - sampleRate / 4

    override fun newSink(): SdrSink {
        val table = LinkedHashMap<String, WmbusMeter>()
        var total = 0
        val rx = WmbusReceiver { t ->
            val key = "${t.frame.mfr}/${t.frame.id}/${t.frame.type}"
            table[key] = WmbusMeter(key, t.frame, t.mode, t.snrDb, (table[key]?.count ?: 0) + 1, System.currentTimeMillis())
            total++
        }
        return object : SdrSink {
            override fun feed(buf: ByteArray, len: Int) = rx.feed(buf, len)
            override fun tick(nowMs: Long) {
                // Meters not heard for an hour are forgotten.
                table.values.removeAll { nowMs - it.lastSeenMs > 3_600_000 }
                val list = table.values.sortedByDescending { it.lastSeenMs }
                val n = total
                post { meters = list; telegrams = n }
            }
        }
    }

    internal fun setForTest(list: List<WmbusMeter>, n: Int) { meters = list; telegrams = n; markRunningForTest() }

    fun csv(quantities: List<String>): String = buildString {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        append("derniere_reception_utc,fabricant,identifiant,type,mode,chiffre,receptions,releves\r\n")
        meters.forEach { m ->
            val f = m.frame
            val values = f.records.joinToString("; ") { r -> "${recordName(r, quantities)}=${r.value}${if (r.unit.isEmpty()) "" else " " + r.unit}" }
            append(listOf(fmt.format(Date(m.lastSeenMs)), f.mfr, f.id, "%02X".format(f.type), m.mode.label, f.encrypted, m.count, "\"$values\"").joinToString(","))
            append("\r\n")
        }
    }
}

private fun recordName(r: Wmbus.Record, quantities: List<String>) =
    if (r.quantity >= 0) quantities.getOrElse(r.quantity) { "?" } else r.code ?: "?"

@Composable
fun WmbusTool(vm: MainViewModel) {
    val c = vm.tools.wmbus
    val actions = LocalActions.current
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    val quantities = S(R.string.wmbus_quantities).split('|')
    val types = S(R.string.wmbus_types).split('|')
    if (c.meters.isNotEmpty()) TopBarAction(Sym.Download) {
        val snapshot = c.csv(quantities)
        actions.saveFile("wmbus-${Export.stamp(System.currentTimeMillis())}.csv", "text/csv") { snapshot.toByteArray() }
    }
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${c.meters.size}", style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(P(R.plurals.p_meters, c.meters.size), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text(S(R.string.wmbus_heard, c.telegrams, c.meters.count { it.frame.encrypted }), style = rf(14, 20))
            Row(Modifier.padding(top = 12.dp)) {
                when {
                    c.error != null -> HeroChip(c.error!!, AntTheme.net.poor)
                    c.starting -> HeroChip(S(R.string.starting_the_hackrf), AntTheme.net.fair, blink = true)
                    c.running -> HeroChip(S(R.string.wmbus_listening), AntTheme.net.good, blink = true)
                    device == null -> HeroChip(S(R.string.no_hackrf_plugged_in), AntTheme.net.poor)
                    else -> HeroChip(S(R.string.ready, device!!.name), AntTheme.net.good)
                }
            }
        }
        val d = device
        if (c.running || c.starting) StartButton(S(R.string.stop), Sym.Stop) { c.stop() }
        else StartButton(S(R.string.start_receiving), Sym.PlayArrow, enabled = d != null) { d?.let(c::start) }
        if (c.meters.isEmpty()) {
            if (c.running) ToolEmpty(Sym.WaterDrop, S(R.string.wmbus_none_yet), S(R.string.wmbus_none_yet_body), null)
        } else {
            val now = System.currentTimeMillis()
            SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
                c.meters.forEachIndexed { i, m ->
                    if (i > 0) Hairline()
                    MeterRow(m, types, quantities, now)
                }
            }
        }
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GainSettings(c.gain) { c.applyGains() }
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(S(R.string.wmbus_how_it_works), style = rf(13, 18), color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MeterRow(m: WmbusMeter, types: List<String>, quantities: List<String>, now: Long) {
    val acc = AntTheme.accent
    val f = m.frame
    val icon = when (f.type) {
        0x02 -> Sym.ElectricMeter
        0x03 -> Sym.GasMeter
        0x04, 0x05, 0x08, 0x0C, 0x0D -> Sym.Heat
        0x06, 0x07, 0x15, 0x16, 0x17, 0x28 -> Sym.WaterDrop
        else -> Sym.Sensors
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LeadingIcon(icon, acc.container, acc.onContainer, 40.dp, RoundedCornerShape(12.dp), 22.dp)
        Column(Modifier.weight(1f)) {
            val maker = Wmbus.makers[f.mfr]?.let { "$it (${f.mfr})" } ?: f.mfr
            Text("${types.getOrElse(Wmbus.typeIndex(f.type)) { "?" }} · $maker", style = rf(15, 20, 600), maxLines = 1)
            val ago = ((now - m.lastSeenMs) / 1000).coerceAtLeast(0)
            Text(
                listOf("ID ${f.id}", m.mode.label, snr(m.snrDb), "×${m.count}", if (ago <= 2) S(R.string.just_now) else S(R.string.s_ago, ago.toString())).joinToString(" · "),
                style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant,
            )
            if (f.encrypted) {
                Text(S(R.string.wmbus_encrypted), Modifier.padding(top = 4.dp), style = rf(13, 18, 500), color = cs.onSurfaceVariant)
            } else {
                // The current values: storage 0, no tariff, no subunit, instantaneous.
                val now0 = f.records.filter { it.storage == 0L && it.tariff == 0 && it.subunit == 0 && it.function == 0 && it.value != "-" }.take(6)
                now0.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text(recordName(r, quantities), Modifier.weight(1f), style = rf(13, 18), color = cs.onSurfaceVariant, maxLines = 1)
                        Text(r.value + if (r.unit.isEmpty()) "" else " " + r.unit, style = rf(14, 18, 500, tnum = true))
                    }
                }
            }
        }
    }
}
