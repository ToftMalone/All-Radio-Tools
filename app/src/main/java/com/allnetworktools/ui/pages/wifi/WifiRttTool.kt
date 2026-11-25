package com.allnetworktools.ui.pages.wifi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.R
import com.allnetworktools.data.RttTrack
import com.allnetworktools.data.WifiRttRanger
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.BtnKind
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.ToolButton
import com.allnetworktools.ui.tools.ToolButtons
import com.allnetworktools.ui.tools.ToolError
import com.allnetworktools.util.S
import java.util.Locale

/**
 * Wi-Fi RTT: the distance to the access points that answer round-trip timing, to a metre or two, without connecting.
 * Walking towards one shows it getting closer; the phone held against it calibrates its offset.
 */
@Composable
fun WifiRttTool(permitted: Boolean, permissionText: String, onFix: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ranger = remember { WifiRttRanger(context.applicationContext) }
    val state by ranger.state.collectAsStateWithLifecycle()
    DisposableEffect(permitted) {
        if (permitted) ranger.start(scope)
        onDispose { ranger.stop() }
    }
    PageColumn {
        if (!ranger.supported) {
            ToolError(Sym.WifiOff, S(R.string.rtt_unsupported), S(R.string.rtt_unsupported_body), "OK") {}
            HowItWorks()
            return@PageColumn
        }
        if (!permitted) {
            ToolError(Sym.Lock, S(R.string.permission_needed), permissionText, S(R.string.allow), onFix)
            return@PageColumn
        }
        val ranged = state.tracks.filter { it.distanceM != null }
        val nearest = ranged.firstOrNull()
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                com.allnetworktools.ui.components.RollingText(nearest?.distanceM?.let { metres(it) } ?: "—", style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(if (nearest != null) "m" else "", Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text(nearest?.let { S(R.string.rtt_nearest, it.ssid) } ?: S(R.string.rtt_responders, state.responders, state.heard), style = rf(14, 20))
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    !state.available -> HeroChip(S(R.string.rtt_off), AntTheme.net.poor)
                    state.responders == 0 -> HeroChip(S(R.string.rtt_searching), AntTheme.net.fair, blink = true)
                    else -> HeroChip(S(R.string.rtt_measuring, state.responders), AntTheme.net.good, blink = true)
                }
            }
        }
        if (!state.available) SectionCard { Text(S(R.string.rtt_off_body), style = rf(13, 18), color = cs.onSurfaceVariant) }
        if (state.available && state.responders == 0) {
            SectionCard {
                Text(S(R.string.rtt_none_title), style = rf(16, 22, 600))
                Text(S(R.string.rtt_none_body, state.heard), Modifier.padding(top = 4.dp), style = rf(13, 18), color = cs.onSurfaceVariant)
            }
        }
        com.allnetworktools.ui.components.AppearSection(state.tracks.isNotEmpty()) {
            SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) {
                Text(S(R.string.rtt_access_points), Modifier.padding(bottom = 4.dp), style = rf(14, 20, 600), color = AntTheme.accent.accent)
                state.tracks.forEach { t ->
                    Hairline()
                    TrackRow(t, onCalibrate = { ranger.calibrate(t.bssid) }, onReset = { ranger.clearCalibration(t.bssid) })
                }
            }
        }
        HowItWorks()
    }
}

private fun metres(m: Double) = if (m < 10) "%.1f".format(Locale.getDefault(), m) else "%.0f".format(Locale.getDefault(), m)

@Composable
private fun TrackRow(t: RttTrack, onCalibrate: () -> Unit, onReset: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.ssid, style = rf(15, 20, 600), maxLines = 1)
                Text(
                    listOfNotNull(t.bssid.uppercase(), "${t.frequency} MHz", t.rssi?.let { "$it dBm" }).joinToString(" · "),
                    style = rf(12, 16), color = cs.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                val d = t.distanceM
                Text(if (d != null) metres(d) + " m" else "—", style = gs(22, 26, 500, tnum = true))
                t.spreadM?.takeIf { d != null }?.let { Text("± " + metres(it) + " m", style = rf(12, 16), color = cs.onSurfaceVariant) }
            }
        }
        val speed = t.speedMs
        val trend = when {
            speed == null || kotlin.math.abs(speed) < 0.2 -> null
            speed < 0 -> S(R.string.rtt_closer)
            else -> S(R.string.rtt_farther)
        }
        Text(
            listOfNotNull(
                trend,
                S(R.string.rtt_success, t.successes, t.attempts),
                if (t.offsetM != 0.0) S(R.string.rtt_calibrated, metres(t.offsetM)) else null,
            ).joinToString(" · "),
            style = rf(12, 16), color = cs.onSurfaceVariant,
        )
        if (t.distanceM != null) ToolButtons(
            if (t.offsetM == 0.0) ToolButton(S(R.string.rtt_calibrate), Sym.MyLocation, BtnKind.OutlineOnSurface, onCalibrate)
            else ToolButton(S(R.string.rtt_reset), Sym.RestartAlt, BtnKind.OutlineOnSurface, onReset),
        )
    }
}

@Composable
private fun HowItWorks() {
    SectionCard {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
            Text(S(R.string.rtt_how_it_works), style = rf(13, 18), color = cs.onSurfaceVariant)
        }
    }
}
