package com.allnetworktools.ui.pages.wifi

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.R
import com.allnetworktools.data.AwarePeer
import com.allnetworktools.data.AwareStatus
import com.allnetworktools.data.WifiAwareController
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.IconCircleButton
import com.allnetworktools.ui.components.InfoRow
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
import com.allnetworktools.ui.tools.ToolError
import com.allnetworktools.util.P
import com.allnetworktools.util.S
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Wi-Fi Aware (NAN): what the phone supports, and the other phones nearby with this screen open, with their distance
 * measured by Wi-Fi RTT when both phones can. Android only finds services it knows the name of, hence the shared one.
 */
@Composable
fun WifiAwareTool(permitted: Boolean, permissionText: String, onFix: () -> Unit, located: Boolean, onLocate: () -> Unit) {
    val context = LocalContext.current
    val actions = LocalActions.current
    val c = remember { WifiAwareController(context.applicationContext) }
    val caps = remember { c.caps() }
    val state by c.state.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
    DisposableEffect(permitted, located) {
        if (permitted && caps.supported) c.start(ranging = located)
        onDispose { c.stop() }
    }
    // A hello from another phone pops up while the screen is open.
    val lastWave = state.waves.firstOrNull()
    LaunchedEffect(lastWave) { lastWave?.let { actions.toast(S(R.string.waved_at_you, it.from)) } }

    PageColumn {
        if (!caps.supported) {
            ToolError(Sym.WifiOff, S(R.string.wi_fi_aware_unavailable), S(R.string.this_phone_does_not_support_wi_fi_aware), "OK") {}
            Capabilities(caps)
            return@PageColumn
        }
        if (!permitted) {
            ToolError(Sym.Lock, S(R.string.permission_needed), permissionText, S(R.string.allow), onFix)
            return@PageColumn
        }
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(state.peers.size.toString(), style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(P(R.plurals.p_devices, state.peers.size), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text(S(R.string.wi_fi_aware_hero_body, c.myName), style = rf(14, 20))
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state.status) {
                    AwareStatus.Running -> HeroChip(S(R.string.wi_fi_aware_active), AntTheme.net.good, blink = true)
                    AwareStatus.Starting, AwareStatus.Idle -> HeroChip(S(R.string.starting), AntTheme.net.fair, blink = true)
                    AwareStatus.Unavailable -> HeroChip(S(R.string.wi_fi_aware_turned_off), AntTheme.net.poor)
                    AwareStatus.Failed -> HeroChip(S(R.string.wi_fi_aware_failed), AntTheme.net.poor)
                }
                if (state.ranging) HeroChip(S(R.string.distance_measured), AntTheme.net.good)
            }
        }
        if (state.status == AwareStatus.Unavailable) {
            SectionCard { Text(S(R.string.wi_fi_aware_turned_off_body), style = rf(13, 18), color = cs.onSurfaceVariant) }
        }
        if (caps.rtt && !located) {
            ToolError(Sym.MyLocation, S(R.string.distance_needs_location), S(R.string.distance_needs_location_body), S(R.string.allow), onLocate)
        }

        if (state.peers.isEmpty()) {
            SectionCard {
                Text(S(R.string.no_phone_yet), style = rf(16, 22, 600))
                Text(S(R.string.wi_fi_aware_empty_body), Modifier.padding(top = 4.dp), style = rf(13, 18), color = cs.onSurfaceVariant)
            }
        } else {
            SectionCard(padding = PaddingValues(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                Text(S(R.string.nearby), Modifier.padding(bottom = 4.dp), style = rf(14, 20, 600), color = AntTheme.accent.accent)
                state.peers.forEach { p ->
                    Hairline()
                    PeerRow(p, now) { c.wave(p.id) }
                }
            }
        }

        if (state.waves.isNotEmpty()) {
            val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
            SectionCard {
                Text(S(R.string.hellos_received), style = rf(14, 20, 600), color = AntTheme.accent.accent)
                state.waves.take(5).forEach { w ->
                    Text("👋 " + S(R.string.waved_at_you, w.from) + " · " + clock.format(Date(w.timeMs)), Modifier.padding(top = 6.dp), style = rf(14, 20))
                }
            }
        }

        Capabilities(caps)

        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(S(R.string.wi_fi_aware_how_it_works), style = rf(13, 18), color = cs.onSurfaceVariant)
            }
        }
    }
}

private fun meters(m: Double) = if (m < 10) String.format(Locale.getDefault(), "%.1f m", m) else "${m.toInt()} m"

@Composable
private fun PeerRow(p: AwarePeer, now: Long, onWave: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(AntTheme.accent.container), contentAlignment = Alignment.Center) {
            Symbol(Sym.Smartphone, size = 22.dp, filled = true, tint = AntTheme.accent.onContainer)
        }
        Column(Modifier.weight(1f)) {
            Text(p.name, style = rf(15, 20, 600), maxLines = 1)
            val ago = ((now - p.lastSeenMs) / 1000).coerceAtLeast(0)
            Text(
                listOfNotNull(
                    p.rssi?.let { "$it dBm" },
                    if (ago <= 2) S(R.string.just_now) else S(R.string.s_ago, ago.toString()),
                ).joinToString(" · "),
                style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant,
            )
        }
        p.distanceM?.let { d ->
            Column(horizontalAlignment = Alignment.End) {
                Text(meters(d), style = gs(22, 26, 500, tnum = true))
                p.distanceStdM?.let { Text("± " + meters(it), style = rf(11, 14, tnum = true), color = cs.onSurfaceVariant) }
            }
        }
        IconCircleButton(Sym.WavingHand, onWave, size = 44.dp, tint = AntTheme.accent.accent)
    }
}

@Composable
private fun Capabilities(caps: com.allnetworktools.data.AwareCaps) {
    val yes = S(R.string.yes)
    val no = S(R.string.no)
    SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
        Text(S(R.string.this_phone), Modifier.padding(bottom = 4.dp), style = rf(14, 20, 600), color = AntTheme.accent.accent)
        InfoRow("Wi-Fi Aware", if (caps.supported) yes else no)
        if (caps.supported) {
            InfoRow(S(R.string.available_now), if (caps.available) yes else no)
            InfoRow(S(R.string.distance_rtt), if (caps.rtt) yes else no)
            caps.publishSessions?.let { InfoRow(S(R.string.publish_sessions), it.toString()) }
            caps.subscribeSessions?.let { InfoRow(S(R.string.subscribe_sessions), it.toString()) }
            caps.dataPaths?.let { InfoRow(S(R.string.data_paths), it.toString()) }
            caps.maxServiceName?.let { InfoRow(S(R.string.max_service_name), it.toString()) }
            caps.maxServiceInfo?.let { InfoRow(S(R.string.max_service_info), S(R.string.bytes, it)) }
            caps.instantMode?.let { InfoRow(S(R.string.instant_communication), if (it) yes else no) }
        }
    }
}
