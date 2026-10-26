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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.R
import com.allnetworktools.data.sdr.Acars
import com.allnetworktools.data.sdr.SdrRepository
import com.allnetworktools.data.sdr.Vdl2
import com.allnetworktools.data.sdr.Vdl2Message
import com.allnetworktools.data.sdr.Vdl2Receiver
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

/**
 * VDL Mode 2 on the six European channels at once, from one tuning: ACARS carried digitally, the ground stations'
 * announcements and the aircraft's link set-ups. Kept in memory for the session only.
 */
class Vdl2Controller(repo: SdrRepository, scope: CoroutineScope, onAcquire: () -> Unit = {}) :
    SdrRxController(repo, scope, onAcquire, SdrGain(listOf(16, 24, 32, 40), 32, listOf(20, 30, 40), 30, amp = true), "sdr-vdl2") {
    var messages by mutableStateOf<List<Vdl2Message>>(emptyList())
        private set

    override val sampleRate = Vdl2Receiver.SAMPLE_RATE
    override fun tuneHz() = Vdl2.CENTRE_HZ - sampleRate / 4

    override fun newSink(): SdrSink {
        val kept = ArrayDeque<Vdl2Message>()
        var changed = false
        val rx = Vdl2Receiver { m ->
            // Link supervision (RR, UA…) and empty frames say nothing worth a line.
            if (m.kind == Vdl2Message.Kind.Other) return@Vdl2Receiver
            kept.addFirst(m); changed = true
            while (kept.size > MAX) kept.removeLast()
        }
        return object : SdrSink {
            override fun feed(buf: ByteArray, len: Int) = rx.feed(buf, len)
            override fun tick(nowMs: Long) {
                if (!changed) return
                changed = false
                val list = kept.toList()
                post { messages = list }
            }
        }
    }

    internal fun setForTest(list: List<Vdl2Message>) { messages = list; markRunningForTest() }

    fun csv(): String = buildString {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        append("heure_utc,frequence_mhz,de,vers,trame,type,immatriculation,vol,label,texte,latitude,longitude,aeroports\r\n")
        messages.forEach { m ->
            val a = m.acars
            append(
                listOf(
                    fmt.format(Date(m.timeMs)), "%.3f".format(Locale.ROOT, m.freqHz / 1e6), m.srcHex, m.dstHex, m.frame, m.kind.name,
                    a?.registration ?: "", a?.flight ?: "", a?.label ?: "", a?.text ?: "",
                    m.position?.first?.toString() ?: "", m.position?.second?.toString() ?: "", m.airports ?: m.destination ?: "",
                ).joinToString(",") { "\"" + it.replace("\"", "'").replace("\n", " ") + "\"" },
            )
            append("\r\n")
        }
    }

    companion object {
        private const val MAX = 500
    }
}

private val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

@Composable
fun Vdl2Tool(vm: MainViewModel) {
    val c = vm.tools.vdl2
    val actions = LocalActions.current
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    if (c.messages.isNotEmpty()) TopBarAction(Sym.Download) {
        val snapshot = c.csv()
        actions.saveFile("vdl2-${Export.stamp(System.currentTimeMillis())}.csv", "text/csv") { snapshot.toByteArray() }
    }
    val aircraft = c.messages.filter { it.fromAircraft }.map { it.src.addr }.distinct().size
    val stations = c.messages.filter { it.fromGround }.map { it.src.addr }.distinct().size
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${c.messages.size}", style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(P(R.plurals.p_messages, c.messages.size), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text(S(R.string.vdl2_heard, aircraft, stations), style = rf(14, 20))
            Row(Modifier.padding(top = 12.dp)) {
                when {
                    c.error != null -> HeroChip(c.error!!, AntTheme.net.poor)
                    c.starting -> HeroChip(S(R.string.starting_the_hackrf), AntTheme.net.fair, blink = true)
                    c.running -> HeroChip(S(R.string.vdl2_listening), AntTheme.net.good, blink = true)
                    device == null -> HeroChip(S(R.string.no_hackrf_plugged_in), AntTheme.net.poor)
                    else -> HeroChip(S(R.string.ready, device!!.name), AntTheme.net.good)
                }
            }
        }
        val d = device
        if (c.running || c.starting) StartButton(S(R.string.stop), Sym.Stop) { c.stop() }
        else StartButton(S(R.string.start_receiving), Sym.PlayArrow, enabled = d != null) { d?.let(c::start) }
        if (c.messages.isEmpty()) {
            if (c.running) ToolEmpty(Sym.ConnectingAirports, S(R.string.vdl2_none_yet), S(R.string.vdl2_none_yet_body), null)
        } else {
            SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
                c.messages.take(200).forEachIndexed { i, m ->
                    if (i > 0) Hairline()
                    Vdl2Row(m)
                }
            }
        }
        SectionCard {
            GainSettings(c.gain) { c.applyGains() }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(S(R.string.vdl2_how_it_works), style = rf(13, 18), color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Vdl2Row(m: Vdl2Message) {
    val acc = AntTheme.accent
    val a = m.acars
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LeadingIcon(if (m.fromAircraft) Sym.Flight else Sym.CellTower, acc.container, acc.onContainer, 40.dp, RoundedCornerShape(12.dp), 22.dp)
        Column(Modifier.weight(1f)) {
            val title = when {
                a != null -> listOfNotNull(a.flight, a.registration.ifBlank { null }).joinToString(" · ").ifEmpty { m.srcHex }
                m.fromGround -> "${S(R.string.ground_station)} ${m.srcHex}"
                else -> m.srcHex
            }
            Text(title, style = rf(15, 20, 600), maxLines = 1)
            val what = when (m.kind) {
                Vdl2Message.Kind.Acars -> a?.let { "ACARS ${it.label}" + (Acars.Labels[it.label]?.let { l -> " $l" } ?: "") } ?: "ACARS"
                Vdl2Message.Kind.Xid -> "XID"
                Vdl2Message.Kind.X25 -> S(R.string.vdl2_atn)
                Vdl2Message.Kind.Other -> m.frame
            }
            Text(
                "${clock.format(Date(m.timeMs))} · ${"%.3f".format(Locale.getDefault(), m.freqHz / 1e6)} · $what" +
                    if (m.fromAircraft) S(R.string.aircraft_ground) else S(R.string.ground_aircraft),
                style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant,
            )
            val detail = listOfNotNull(
                m.airports?.let { S(R.string.vdl2_airports, it.chunked(4).joinToString(" ")) },
                m.destination?.let { S(R.string.vdl2_destination, it) },
                m.position?.let { "%.1f, %.1f".format(Locale.getDefault(), it.first, it.second) + (m.altFt?.let { ft -> " · FL${ft / 100}" } ?: "") },
            )
            if (detail.isNotEmpty()) Text(detail.joinToString(" · "), Modifier.padding(top = 2.dp), style = rf(13, 18))
            if (a != null && a.text.isNotBlank()) Text(a.text, Modifier.padding(top = 4.dp), style = rf(13, 18), maxLines = 6)
        }
    }
}
