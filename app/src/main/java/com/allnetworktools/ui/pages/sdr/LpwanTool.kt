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
import com.allnetworktools.data.sdr.LoraWan
import com.allnetworktools.data.sdr.LpwanEvent
import com.allnetworktools.data.sdr.LpwanReceiver
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

/** A device heard, LoRaWAN, other LoRa or Sigfox, with what its last frame said in the clear. */
data class LpwanDevice(
    val key: String,
    val sigfox: Boolean,
    val id: String,
    val network: String?,
    val type: LoraWan.Type?,
    val counter: Int?,
    val port: Int?,
    val payload: String?,
    val sf: Int?,
    val hz: Double,
    val snrDb: Double?,
    val syncWord: Int?,
    val count: Int,
    val lastMs: Long,
)

/** LoRaWAN and Sigfox devices on 868 MHz, from one tuning. Kept in memory for the session only. */
class LpwanController(repo: SdrRepository, scope: CoroutineScope, onAcquire: () -> Unit = {}) :
    SdrRxController(repo, scope, onAcquire, SdrGain(listOf(16, 24, 32, 40), 32, listOf(20, 30, 40), 30, amp = true), "sdr-lpwan") {
    var devices by mutableStateOf<List<LpwanDevice>>(emptyList())
        private set
    var badFrames by mutableIntStateOf(0)
        private set

    override val sampleRate = LpwanReceiver.SAMPLE_RATE
    override fun tuneHz() = LpwanReceiver.CENTRE_HZ - sampleRate / 4

    override fun newSink(): SdrSink {
        val table = LinkedHashMap<String, LpwanDevice>()
        var bad = 0
        var changed = false
        val rx = LpwanReceiver { e ->
            val now = System.currentTimeMillis()
            val d: LpwanDevice? = when (e) {
                is LpwanEvent.Lora -> {
                    if (!e.frame.crcOk) { bad++; null } else {
                        val w = e.lorawan
                        val id = w?.devAddr?.let { "%08X".format(it) } ?: w?.devEui ?: "SW %02X".format(e.frame.syncWord)
                        val net = w?.devAddr?.let { a -> LoraWan.network(a) ?: LoraWan.nwkId(a)?.let { "NwkID %02X".format(it) } }
                        LpwanDevice(
                            "L/$id", false, id, net, w?.type, w?.fCnt, w?.fPort,
                            if (w == null) e.frame.payload.joinToString("") { "%02X".format(it) } else null,
                            e.sf, e.hz.toDouble(), e.frame.snrDb, e.frame.syncWord, 0, now,
                        )
                    }
                }
                is LpwanEvent.SigfoxUp -> LpwanDevice(
                    "S/${e.up.idHex}", true, e.up.idHex, null, null, e.up.counter, null,
                    if (e.up.singleBit) if (e.up.bitValue) "1" else "0" else e.up.payload.joinToString("") { "%02X".format(it) },
                    null, e.hz, null, null, 0, now,
                )
            }
            if (d != null) {
                val prev = table[d.key]
                // Sigfox sends each message three times: the same counter again is a repetition, not a new message.
                val again = prev != null && d.sigfox && prev.counter == d.counter && now - prev.lastMs < 30_000
                table.remove(d.key)
                table[d.key] = d.copy(count = (prev?.count ?: 0) + if (again) 0 else 1)
                changed = true
            }
        }
        return object : SdrSink {
            override fun feed(buf: ByteArray, len: Int) = rx.feed(buf, len)
            override fun tick(nowMs: Long) {
                table.values.removeAll { nowMs - it.lastMs > 3_600_000 }
                val list = if (changed) { changed = false; table.values.reversed() } else null
                val b = bad
                post { if (list != null) devices = list; badFrames = b }
            }
        }
    }

    internal fun setForTest(list: List<LpwanDevice>) { devices = list; markRunningForTest() }

    fun csv(): String = buildString {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        append("derniere_reception_utc,reseau,identifiant,operateur,type,compteur,port,donnees,sf,frequence_mhz,receptions\r\n")
        devices.forEach { d ->
            append(
                listOf(
                    fmt.format(Date(d.lastMs)), if (d.sigfox) "Sigfox" else if (d.type != null) "LoRaWAN" else "LoRa", d.id, d.network ?: "", d.type?.name ?: "",
                    d.counter?.toString() ?: "", d.port?.toString() ?: "", d.payload ?: "", d.sf?.toString() ?: "", "%.4f".format(Locale.ROOT, d.hz / 1e6), d.count.toString(),
                ).joinToString(","),
            )
            append("\r\n")
        }
    }
}

@Composable
fun LpwanTool(vm: MainViewModel) {
    val c = vm.tools.lpwan
    val actions = LocalActions.current
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    if (c.devices.isNotEmpty()) TopBarAction(Sym.Download) {
        val snapshot = c.csv()
        actions.saveFile("lpwan-${Export.stamp(System.currentTimeMillis())}.csv", "text/csv") { snapshot.toByteArray() }
    }
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${c.devices.size}", style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(P(R.plurals.p_devices, c.devices.size), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text(S(R.string.lpwan_heard, c.devices.count { !it.sigfox }, c.devices.count { it.sigfox }, c.badFrames), style = rf(14, 20))
            Row(Modifier.padding(top = 12.dp)) {
                when {
                    c.error != null -> HeroChip(c.error!!, AntTheme.net.poor)
                    c.starting -> HeroChip(S(R.string.starting_the_hackrf), AntTheme.net.fair, blink = true)
                    c.running -> HeroChip(S(R.string.lpwan_listening), AntTheme.net.good, blink = true)
                    device == null -> HeroChip(S(R.string.no_hackrf_plugged_in), AntTheme.net.poor)
                    else -> HeroChip(S(R.string.ready, device!!.name), AntTheme.net.good)
                }
            }
        }
        val d = device
        if (c.running || c.starting) StartButton(S(R.string.stop), Sym.Stop) { c.stop() }
        else StartButton(S(R.string.start_receiving), Sym.PlayArrow, enabled = d != null) { d?.let(c::start) }
        if (c.devices.isEmpty()) {
            if (c.running) ToolEmpty(Sym.Hub, S(R.string.lpwan_none_yet), S(R.string.lpwan_none_yet_body), null)
        } else {
            val now = System.currentTimeMillis()
            SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
                c.devices.forEachIndexed { i, dev ->
                    if (i > 0) Hairline()
                    DeviceRow(dev, now)
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
                Text(S(R.string.lpwan_how_it_works), style = rf(13, 18), color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DeviceRow(d: LpwanDevice, now: Long) {
    val acc = AntTheme.accent
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LeadingIcon(if (d.sigfox) Sym.SatelliteAlt else Sym.Hub, acc.container, acc.onContainer, 40.dp, RoundedCornerShape(12.dp), 22.dp)
        Column(Modifier.weight(1f)) {
            val kind = when {
                d.sigfox -> "Sigfox"
                d.type == LoraWan.Type.JoinRequest -> S(R.string.lpwan_join)
                d.type != null -> "LoRaWAN"
                else -> "LoRa"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$kind · ${d.id}", Modifier.weight(1f), style = rf(15, 20, 600, tnum = true), maxLines = 1)
                val ago = ((now - d.lastMs) / 1000).coerceAtLeast(0)
                Text(if (ago <= 2) S(R.string.just_now) else S(R.string.s_ago, ago.toString()), style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant)
            }
            Text(
                listOfNotNull(
                    d.network,
                    d.counter?.let { "#$it" },
                    d.port?.let { S(R.string.lpwan_port, it) },
                    d.sf?.let { "SF$it" },
                    "%.3f MHz".format(Locale.getDefault(), d.hz / 1e6),
                    d.snrDb?.let { snr(it) },
                    "×${d.count}",
                ).joinToString(" · "),
                style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant,
            )
            if (d.type != null && d.type != LoraWan.Type.JoinRequest) Text(S(R.string.lpwan_encrypted), Modifier.padding(top = 2.dp), style = rf(12, 16), color = cs.onSurfaceVariant)
            d.payload?.takeIf { it.isNotEmpty() }?.let { Text(it, Modifier.padding(top = 4.dp), style = rf(13, 18, 500, tnum = true), maxLines = 2) }
        }
    }
}
