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
import com.allnetworktools.data.sdr.AdsbTracker
import com.allnetworktools.data.sdr.Flarm
import com.allnetworktools.data.sdr.FlarmAircraft
import com.allnetworktools.data.sdr.FlarmReceiver
import com.allnetworktools.data.sdr.FlarmTracker
import com.allnetworktools.data.sdr.SdrRepository
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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope

/**
 * FLARM: gliders, tow planes, paragliders and other light aircraft around, from both European channels at once. The
 * phone's clock unlocks each packet; its position completes the aircraft's. Aircraft that ask not to be tracked are
 * only counted. Kept in memory for the session.
 */
class FlarmController(repo: SdrRepository, scope: CoroutineScope, onAcquire: () -> Unit = {}) :
    SdrRxController(repo, scope, onAcquire, SdrGain(listOf(16, 24, 32, 40), 32, listOf(20, 30, 40), 30, amp = true), "sdr-flarm") {
    var aircraft by mutableStateOf<List<FlarmAircraft>>(emptyList())
        private set
    var privateCount by mutableIntStateOf(0)
        private set
    var newProtocol by mutableIntStateOf(0)
        private set

    override val sampleRate = FlarmReceiver.SAMPLE_RATE
    override fun tuneHz() = FlarmReceiver.CENTRE_HZ - sampleRate / 4

    override fun newSink(): SdrSink {
        val tracker = FlarmTracker()
        val rx = FlarmReceiver { tracker.update(it, System.currentTimeMillis()) }
        return object : SdrSink {
            override fun feed(buf: ByteArray, len: Int) = rx.feed(buf, len)
            override fun tick(nowMs: Long) {
                tracker.prune(nowMs)
                val list = tracker.aircraft.values.sortedByDescending { it.lastSeenMs }
                val priv = tracker.privateCount
                val v7 = tracker.newProtocolDevices
                post { aircraft = list; privateCount = priv; newProtocol = v7 }
            }
        }
    }

    internal fun setForTest(list: List<FlarmAircraft>, priv: Int) { aircraft = list; privateCount = priv; markRunningForTest() }
}

@Composable
fun FlarmTool(vm: MainViewModel) {
    val c = vm.tools.flarm
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    // The phone's position completes the aircraft's 19 and 20 low bits; it is read while this screen is open.
    val positions by vm.positions.collectAsStateWithLifecycle()
    val loc = positions.fused ?: positions.gnss ?: positions.network
    val me = loc?.let { it.latitude to it.longitude }
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${c.aircraft.size}", style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(P(R.plurals.p_aircraft, c.aircraft.size), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text(S(R.string.flarm_heard, c.privateCount, c.newProtocol), style = rf(14, 20))
            Row(Modifier.padding(top = 12.dp)) {
                when {
                    c.error != null -> HeroChip(c.error!!, AntTheme.net.poor)
                    c.starting -> HeroChip(S(R.string.starting_the_hackrf), AntTheme.net.fair, blink = true)
                    c.running -> HeroChip(S(R.string.flarm_listening), AntTheme.net.good, blink = true)
                    device == null -> HeroChip(S(R.string.no_hackrf_plugged_in), AntTheme.net.poor)
                    else -> HeroChip(S(R.string.ready, device!!.name), AntTheme.net.good)
                }
            }
        }
        val d = device
        if (c.running || c.starting) StartButton(S(R.string.stop), Sym.Stop) { c.stop() }
        else StartButton(S(R.string.start_receiving), Sym.PlayArrow, enabled = d != null) { d?.let(c::start) }
        if (c.aircraft.isEmpty()) {
            if (c.running) ToolEmpty(Sym.Flight, S(R.string.flarm_none_yet), S(R.string.flarm_none_yet_body), null)
        } else {
            val now = System.currentTimeMillis()
            val types = S(R.string.flarm_types).split('|')
            if (me == null) Empty(S(R.string.flarm_no_position))
            SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
                c.aircraft.forEachIndexed { i, a ->
                    if (i > 0) Hairline()
                    AircraftRow(a, types.getOrElse(a.type) { "?" }, me, now)
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
                Text(S(R.string.flarm_how_it_works), style = rf(13, 18), color = cs.onSurfaceVariant)
            }
        }
    }
}

private fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dl = Math.toRadians(lon2 - lon1)
    return (Math.toDegrees(atan2(sin(dl) * cos(p2), cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl))) + 360) % 360
}

@Composable
private fun AircraftRow(a: FlarmAircraft, type: String, me: Pair<Double, Double>?, now: Long) {
    val acc = AntTheme.accent
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LeadingIcon(Sym.Flight, acc.container, acc.onContainer, 40.dp, RoundedCornerShape(12.dp), 22.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$type · ${a.hexId}", Modifier.weight(1f), style = rf(15, 20, 600), maxLines = 1)
                val ago = ((now - a.lastSeenMs) / 1000).coerceAtLeast(0)
                Text(if (ago <= 2) S(R.string.just_now) else S(R.string.s_ago, ago.toString()), style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant)
            }
            val loc = Locale.getDefault()
            Text(
                listOfNotNull(
                    "${a.altM} m",
                    "%.0f km/h".format(loc, a.speedKmh),
                    when { a.climbMs > 0.2 -> "↑ %.1f m/s".format(loc, a.climbMs); a.climbMs < -0.2 -> "↓ %.1f m/s".format(loc, -a.climbMs); else -> null },
                    if (!a.airborne) S(R.string.flarm_on_ground) else null,
                ).joinToString(" · "),
                style = rf(14, 20, 500, tnum = true),
            )
            val where = me?.let {
                val (lat, lon) = Flarm.unpackPosition(a.lat19, a.lon20, it.first, it.second)
                val km = AdsbTracker.haversineKm(it.first, it.second, lat, lon)
                // Farther than this is an alias of the truncated position, not the aircraft.
                if (km < 600) "%.1f km · %d°".format(loc, km, bearing(it.first, it.second, lat, lon).roundToInt()) else null
            }
            Text(
                listOfNotNull(where, S(R.string.heading, a.trackDeg.roundToInt()), if (a.channel == 0) "868,2 MHz" else "868,4 MHz", snr(a.snrDb), "×${a.packets}").joinToString(" · "),
                style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant,
            )
        }
    }
}
