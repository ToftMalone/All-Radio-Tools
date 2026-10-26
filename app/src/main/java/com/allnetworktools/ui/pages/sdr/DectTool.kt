package com.allnetworktools.ui.pages.sdr

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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.R
import com.allnetworktools.data.sdr.Dect
import com.allnetworktools.data.sdr.DectBase
import com.allnetworktools.data.sdr.DectReceiver
import com.allnetworktools.data.sdr.DectTracker
import com.allnetworktools.data.sdr.SdrRepository
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.LeadingIcon
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.StartButton
import com.allnetworktools.ui.tools.ToolEmpty
import com.allnetworktools.util.P
import com.allnetworktools.util.S
import java.util.Locale
import kotlinx.coroutines.CoroutineScope

/**
 * DECT base stations around: the carriers are visited in turn, a second each, or up to four while bursts are heard;
 * the bases are told apart by the identity they broadcast. Kept in memory for the session only.
 */
class DectController(repo: SdrRepository, scope: CoroutineScope, onAcquire: () -> Unit = {}) :
    SdrRxController(repo, scope, onAcquire, SdrGain(listOf(16, 24, 32, 40), 32, listOf(20, 30, 40), 30, amp = true), "sdr-dect") {
    var band by mutableStateOf(Dect.Band.Europe)
    var bases by mutableStateOf<List<DectBase>>(emptyList())
        private set
    var carrier by mutableIntStateOf(0)
        private set
    var handsetBursts by mutableIntStateOf(0)
        private set

    override val sampleRate = DectReceiver.SAMPLE_RATE
    override fun tuneHz() = band.carrierHz(0) - sampleRate / 4

    override fun newSink(): SdrSink {
        val b = band
        val tracker = DectTracker()
        var current = 0 // read and written on the DSP thread only
        var heard = 0
        var dwell = 0
        val rx = DectReceiver { burst -> heard++; tracker.update(burst, current, System.currentTimeMillis()) }
        post { carrier = 0 }
        return object : SdrSink {
            override fun feed(buf: ByteArray, len: Int) = rx.feed(buf, len)
            override fun tick(nowMs: Long) {
                dwell++
                if (heard == 0 || dwell >= 4) {
                    current = (current + 1) % b.count
                    dwell = 0
                    // Drop what is still in flight from the last carrier, and the radio's settling.
                    rx.skip = DectReceiver.SAMPLE_RATE / 20
                    retune(b.carrierHz(current) - DectReceiver.SAMPLE_RATE / 4)
                }
                heard = 0
                tracker.prune(nowMs)
                val list = tracker.bases().sortedByDescending { it.lastSeenMs }
                val c = current
                val hs = tracker.handsetBursts
                post { bases = list; carrier = c; handsetBursts = hs }
            }
        }
    }

    internal fun setForTest(list: List<DectBase>) { bases = list; markRunningForTest() }
}

@Composable
fun DectTool(vm: MainViewModel) {
    val c = vm.tools.dect
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${c.bases.size}", style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(P(R.plurals.p_bases, c.bases.size), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text(S(R.string.dect_heard, c.carrier, "%.3f".format(Locale.getDefault(), c.band.carrierHz(c.carrier) / 1e6), c.handsetBursts), style = rf(14, 20, tnum = true))
            Row(Modifier.padding(top = 12.dp)) {
                when {
                    c.error != null -> HeroChip(c.error!!, AntTheme.net.poor)
                    c.starting -> HeroChip(S(R.string.starting_the_hackrf), AntTheme.net.fair, blink = true)
                    c.running -> HeroChip(S(R.string.dect_scanning), AntTheme.net.good, blink = true)
                    device == null -> HeroChip(S(R.string.no_hackrf_plugged_in), AntTheme.net.poor)
                    else -> HeroChip(S(R.string.ready, device!!.name), AntTheme.net.good)
                }
            }
        }
        val d = device
        if (c.running || c.starting) StartButton(S(R.string.stop), Sym.Stop) { c.stop() }
        else StartButton(S(R.string.start_receiving), Sym.PlayArrow, enabled = d != null) { d?.let(c::start) }
        if (c.bases.isEmpty()) {
            if (c.running) ToolEmpty(Sym.Deskphone, S(R.string.dect_none_yet), S(R.string.dect_none_yet_body), null)
        } else {
            val now = System.currentTimeMillis()
            SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
                c.bases.forEachIndexed { i, b ->
                    if (i > 0) Hairline()
                    BaseRow(b, c.band, now)
                }
            }
        }
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(S(R.string.frequency), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Dect.Band.Europe to S(R.string.dect_band_eu), Dect.Band.Us to S(R.string.dect_band_us)).forEach { (b, label) ->
                        AntFilterChip(label, c.band == b, { if (c.band != b && !c.starting) { if (c.running) c.stop(); c.band = b } })
                    }
                }
                GainSettings(c.gain) { c.applyGains() }
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(S(R.string.dect_how_it_works), style = rf(13, 18), color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BaseRow(b: DectBase, band: Dect.Band, now: Long) {
    val acc = AntTheme.accent
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LeadingIcon(Sym.Deskphone, acc.container, acc.onContainer, 40.dp, RoundedCornerShape(12.dp), 22.dp)
        Column(Modifier.weight(1f)) {
            Text("RFPI ${b.rfpi.hex}", style = rf(15, 20, 600, tnum = true), maxLines = 1)
            val id = b.rfpi
            Text(
                if (id.emc != null) S(R.string.dect_identity_a, "%04X".format(id.emc), id.fpn.toString()) else S(R.string.dect_identity, id.arcLetter.toString()),
                style = rf(13, 18, tnum = true),
            )
            val slots = (0 until Dect.SLOTS).filter { b.slots shr it and 1 == 1 }
            val ago = ((now - b.lastSeenMs) / 1000).coerceAtLeast(0)
            Text(
                listOfNotNull(
                    S(R.string.dect_carriers, b.carriers.sorted().joinToString(", ") { "$it (${"%.3f".format(Locale.getDefault(), band.carrierHz(it) / 1e6)})" }),
                    if (slots.isNotEmpty()) S(R.string.dect_slots, slots.joinToString(", ")) else null,
                    "%.0f dB".format(Locale.getDefault(), b.rssiDb),
                    "×${b.bursts}",
                    if (ago <= 2) S(R.string.just_now) else S(R.string.s_ago, ago.toString()),
                ).joinToString(" · "),
                style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant,
            )
        }
    }
}
