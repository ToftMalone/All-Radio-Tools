package com.allnetworktools.ui.pages.bt

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.allnetworktools.R
import com.allnetworktools.data.BleDevice
import com.allnetworktools.data.GlassesConfidence
import com.allnetworktools.data.GlassesGuard
import com.allnetworktools.data.GlassesSeen
import com.allnetworktools.data.GlassesWatch
import com.allnetworktools.ui.components.AntFilterChip
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
import com.allnetworktools.ui.tools.ToolEmpty
import com.allnetworktools.util.S
import java.util.Locale
import kotlinx.coroutines.delay

/** Smart glasses with a camera nearby (Ray-Ban Meta, Oakley Meta, Spectacles…), from their Bluetooth advertisements. Listening only. */
@Composable
fun GlassesTool(watch: GlassesWatch, devices: List<BleDevice>, guardRunning: Boolean = false, onGuard: (Boolean) -> Unit = {}) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    LaunchedEffect(devices, tick, guardRunning) { if (!guardRunning) watch.update(devices, System.currentTimeMillis()) }
    DisposableEffect(Unit) {
        GlassesGuard.watching = true
        onDispose { GlassesGuard.watching = false }
    }
    if (watch.seen.isNotEmpty()) TopBarAction(Sym.Delete) { watch.clear() }
    val net = AntTheme.net
    val now = System.currentTimeMillis() + tick * 0
    val near = watch.nearby(now)
    PageColumn {
        HeroCard {
            Text(if (near.isNotEmpty()) S(R.string.glasses_nearby_now, near.size) else S(R.string.glasses_none_now), style = rf(26, 32, 600))
            Text(
                if (near.isNotEmpty()) near.joinToString(" · ") { it.hit.product } else S(R.string.glasses_none_body),
                style = rf(14, 20),
            )
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (near.isNotEmpty()) HeroChip(S(R.string.glasses_camera_nearby), net.poor, blink = true)
                else HeroChip(S(R.string.bluetooth_listening_in_progress), net.good, blink = true)
            }
            // The engine keeps watching with the app closed and alerts when camera glasses come close.
            Row(
                Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.surface.copy(alpha = 0.7f))
                    .clickable { onGuard(!guardRunning) }.padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Symbol(Sym.Shield, size = 22.dp, filled = guardRunning, tint = if (guardRunning) net.good else cs.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text(S(R.string.glasses_guard), style = rf(15, 20, 600))
                    Text(if (guardRunning) S(R.string.glasses_guard_on) else S(R.string.glasses_guard_off), style = rf(12, 16), color = cs.onSurfaceVariant)
                }
                androidx.compose.material3.Switch(
                    checked = guardRunning, onCheckedChange = onGuard,
                    thumbContent = if (guardRunning) ({ Symbol(Sym.Check, size = 16.dp, tint = cs.primary) }) else null,
                    colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = cs.primary, checkedThumbColor = cs.onPrimary),
                )
            }
        }

        Text(S(R.string.glasses_range), Modifier.padding(start = 8.dp, top = 4.dp), style = rf(14, 20, 600), color = cs.onSurfaceVariant)
        FlowRow(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(-60 to S(R.string.glasses_range_close), -75 to S(R.string.glasses_range_room), -90 to S(R.string.glasses_range_far)).forEach { (dbm, label) ->
                AntFilterChip("$label · $dbm dBm", watch.threshold == dbm, { watch.threshold = dbm })
            }
        }

        Text(S(R.string.glasses_heard), Modifier.padding(start = 8.dp, top = 4.dp), style = rf(14, 20, 600), color = cs.onSurfaceVariant)
        if (watch.seen.isEmpty()) ToolEmpty(Sym.Eyeglasses, S(R.string.glasses_none_yet), S(R.string.glasses_none_yet_body), null)
        else SectionCard(padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
            Column { watch.seen.forEachIndexed { i, s -> if (i > 0) Hairline(); SeenRow(s, now, watch.threshold) } }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(S(R.string.glasses_how_it_works), style = rf(13, 18), color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SeenRow(s: GlassesSeen, now: Long, threshold: Int) {
    val net = AntTheme.net
    val h = s.hit
    val fresh = now - s.lastMs <= 30_000
    val close = fresh && h.rssi >= threshold && h.camera != false
    val color = when {
        close && h.confidence != GlassesConfidence.Low -> net.poor
        fresh -> net.fair
        else -> cs.onSurfaceVariant
    }
    Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LeadingIcon(Sym.Eyeglasses, color.copy(alpha = 0.16f), color, 44.dp, RoundedCornerShape(14.dp), 24.dp)
            Column(Modifier.weight(1f)) {
                Text(h.product, style = rf(16, 22, 600), maxLines = 2)
                val cam = when (h.camera) { true -> S(R.string.glasses_has_camera); false -> S(R.string.glasses_no_camera); null -> S(R.string.glasses_camera_unknown) }
                val conf = S(when (h.confidence) { GlassesConfidence.High -> R.string.glasses_conf_high; GlassesConfidence.Medium -> R.string.glasses_conf_medium; GlassesConfidence.Low -> R.string.glasses_conf_low })
                Text("$cam · $conf", style = rf(12, 16), color = cs.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${h.rssi} dBm", style = gs(14, 18, 700, tnum = true), color = color)
                Text(if (fresh) "≈ %.0f m".format(Locale.getDefault(), h.distanceM) else ago(now - s.lastMs), style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant)
            }
        }
        Text(h.evidence.joinToString(" · ") + " · " + h.address.uppercase(Locale.ROOT), style = rf(11, 15), color = cs.onSurfaceVariant)
    }
}

private fun ago(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> S(R.string.s_ago, s.toString())
        else -> S(R.string.glasses_min_ago, (s / 60).toInt())
    }
}
