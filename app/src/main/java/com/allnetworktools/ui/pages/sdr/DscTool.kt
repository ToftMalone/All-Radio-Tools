package com.allnetworktools.ui.pages.sdr

import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.R
import com.allnetworktools.data.sdr.AdsbTracker
import com.allnetworktools.data.sdr.Ais
import com.allnetworktools.data.sdr.Dsc
import com.allnetworktools.data.sdr.DscCall
import com.allnetworktools.data.sdr.DscReceiver
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
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope

/** One call as heard, with when. */
data class DscHeard(val call: DscCall, val atMs: Long)

/** Digital selective calls on channel 70: distress alerts, safety and routine calls between ships and coast stations. */
class DscController(repo: SdrRepository, scope: CoroutineScope, onAcquire: () -> Unit = {}) :
    SdrRxController(repo, scope, onAcquire, SdrGain(listOf(16, 24, 32, 40), 32, listOf(20, 30, 40), 30, amp = true), "sdr-dsc") {
    var calls by mutableStateOf<List<DscHeard>>(emptyList())
        private set

    override val sampleRate = DscReceiver.SAMPLE_RATE
    override fun tuneHz() = DscReceiver.CHANNEL_70_HZ - sampleRate / 4

    override fun newSink(): SdrSink {
        val heard = ArrayList<DscHeard>()
        var changed = false
        val rx = DscReceiver { c ->
            // A call repeated within the minute (DSC calls are often sent twice) is kept once.
            val now = System.currentTimeMillis()
            if (heard.none { it.call.symbols == c.symbols && now - it.atMs < 60_000 }) { heard.add(0, DscHeard(c, now)); changed = true }
            if (heard.size > 200) heard.removeAt(heard.size - 1)
        }
        return object : SdrSink {
            override fun feed(buf: ByteArray, len: Int) = rx.feed(buf, len)
            override fun tick(nowMs: Long) {
                if (!changed) return
                changed = false
                val list = heard.toList()
                post { calls = list }
            }
        }
    }

    internal fun setForTest(list: List<DscHeard>) { calls = list; markRunningForTest() }

    fun csv(kinds: List<String>, natures: List<String>): String = buildString {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        append("reception_utc,type,de,vers,nature,latitude,longitude,heure_utc,canal,en_detresse,symboles\r\n")
        calls.forEach { h ->
            val c = h.call
            append(
                listOf(
                    fmt.format(Date(h.atMs)), kinds.getOrElse(Dsc.kind(c)) { "" }, c.from ?: "", c.to ?: "",
                    if (c.nature >= 100) natures.getOrElse(c.nature - 100) { "" } else "",
                    c.position?.first?.let { "%.4f".format(Locale.ROOT, it) } ?: "", c.position?.second?.let { "%.4f".format(Locale.ROOT, it) } ?: "",
                    c.utc?.let { "%02d:%02d".format(it.first, it.second) } ?: "", c.channel ?: "", c.distressMmsi ?: "", c.symbols.joinToString(" "),
                ).joinToString(",") { "\"" + it.replace("\"", "'") + "\"" },
            )
            append("\r\n")
        }
    }
}

@Composable
fun DscTool(vm: MainViewModel) {
    val c = vm.tools.dsc
    val actions = LocalActions.current
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    val positions by vm.positions.collectAsStateWithLifecycle()
    val loc = positions.fused ?: positions.gnss ?: positions.network
    val me = loc?.let { it.latitude to it.longitude }
    val kinds = S(R.string.dsc_kinds).split('|')
    val natures = S(R.string.dsc_natures).split('|')
    if (c.calls.isNotEmpty()) TopBarAction(Sym.Download) {
        val snapshot = c.csv(kinds, natures)
        actions.saveFile("dsc-${Export.stamp(System.currentTimeMillis())}.csv", "text/csv") { snapshot.toByteArray() }
    }
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${c.calls.size}", style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(P(R.plurals.p_calls, c.calls.size), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text(S(R.string.dsc_heard, c.calls.count { it.call.distress }), style = rf(14, 20))
            Row(Modifier.padding(top = 12.dp)) {
                when {
                    c.error != null -> HeroChip(c.error!!, AntTheme.net.poor)
                    c.starting -> HeroChip(S(R.string.starting_the_hackrf), AntTheme.net.fair, blink = true)
                    c.running -> HeroChip(S(R.string.dsc_listening), AntTheme.net.good, blink = true)
                    device == null -> HeroChip(S(R.string.no_hackrf_plugged_in), AntTheme.net.poor)
                    else -> HeroChip(S(R.string.ready, device!!.name), AntTheme.net.good)
                }
            }
        }
        val d = device
        if (c.running || c.starting) StartButton(S(R.string.stop), Sym.Stop) { c.stop() }
        else StartButton(S(R.string.start_receiving), Sym.PlayArrow, enabled = d != null) { d?.let(c::start) }
        if (c.calls.isEmpty()) {
            if (c.running) ToolEmpty(Sym.Sailing, S(R.string.dsc_none_yet), S(R.string.dsc_none_yet_body), null)
        } else {
            SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
                c.calls.forEachIndexed { i, h ->
                    if (i > 0) Hairline()
                    CallRow(h, kinds, natures, me)
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
                Text(S(R.string.dsc_how_it_works), style = rf(13, 18), color = cs.onSurfaceVariant)
            }
        }
    }
}

/** An MMSI with its flag and, for a coast station, the word for it. */
private fun station(m: String?): String {
    if (m == null) return "?"
    val country = Dsc.mid(m)?.let { Ais.country(it * 1_000_000) }
    return listOfNotNull(m, if (Dsc.isCoast(m)) S(R.string.dsc_coast_station) else null, country).joinToString(" · ")
}

@Composable
private fun CallRow(h: DscHeard, kinds: List<String>, natures: List<String>, me: Pair<Double, Double>?) {
    val c = h.call
    val acc = AntTheme.accent
    val red = c.distress
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp))
            .then(if (red) Modifier.background(cs.errorContainer) else Modifier).padding(vertical = 6.dp, horizontal = if (red) 8.dp else 0.dp),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LeadingIcon(if (red) Sym.Warning else Sym.Sailing, if (red) cs.error else acc.container, if (red) cs.onError else acc.onContainer, 40.dp, RoundedCornerShape(12.dp), 22.dp)
        Column(Modifier.weight(1f)) {
            val onBg = if (red) cs.onErrorContainer else cs.onSurface
            val sub = if (red) cs.onErrorContainer else cs.onSurfaceVariant
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(kinds.getOrElse(Dsc.kind(c)) { "?" }, Modifier.weight(1f), style = rf(15, 20, 600), color = onBg, maxLines = 1)
                Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(h.atMs)), style = rf(12, 16, tnum = true), color = sub)
            }
            Text(station(c.from), style = rf(13, 18, 500, tnum = true), color = onBg)
            val to = when {
                c.format == Dsc.DISTRESS || c.format == Dsc.ALL_SHIPS -> S(R.string.dsc_all_ships)
                else -> c.to?.let(::station)
            }
            val lines = listOfNotNull(
                to?.let { S(R.string.dsc_to, it) },
                c.distressMmsi?.let { S(R.string.dsc_in_distress, station(it)) },
                if (c.nature in 100..112) natures.getOrNull(c.nature - 100) else null,
                c.position?.let { p ->
                    val text = "%.4f, %.4f".format(Locale.getDefault(), p.first, p.second)
                    me?.let { "$text · %.0f km".format(Locale.getDefault(), AdsbTracker.haversineKm(it.first, it.second, p.first, p.second)) } ?: text
                },
                c.utc?.let { "%02d:%02d UTC".format(it.first, it.second) },
                c.channel?.let { S(R.string.dsc_channel, it) },
            )
            lines.forEach { Text(it, style = rf(12, 16, tnum = true), color = sub) }
        }
    }
}
