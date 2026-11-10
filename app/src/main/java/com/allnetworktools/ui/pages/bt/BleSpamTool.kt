package com.allnetworktools.ui.pages.bt

import android.os.SystemClock
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import com.allnetworktools.data.BleSpamGuard
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.allnetworktools.R
import com.allnetworktools.data.BleDevice
import com.allnetworktools.data.BleSpamWatch
import com.allnetworktools.data.FlipperClue
import com.allnetworktools.data.FlipperColor
import com.allnetworktools.data.SpamReason
import com.allnetworktools.data.SpamIncident
import com.allnetworktools.data.SpamKindState
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private val clock = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
private fun time(ms: Long) = synchronized(clock) { clock.format(Date(ms)) }

/** BLE spam (pop-up floods) going on around, its history, and the Flipper Zeros in range. Listening only. */
@Composable
fun BleSpamTool(watch: BleSpamWatch, devices: List<BleDevice>, guardRunning: Boolean = false, onGuard: (Boolean) -> Unit = {}) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    // While the engine runs it feeds the session itself; the screen only shows it.
    LaunchedEffect(devices, tick, guardRunning) { if (!guardRunning) watch.update(devices, SystemClock.elapsedRealtime(), System.currentTimeMillis()) }
    DisposableEffect(Unit) {
        BleSpamGuard.watching = true
        onDispose { BleSpamGuard.watching = false }
    }
    if (watch.incidents.isNotEmpty()) TopBarAction(Sym.Delete) { watch.clear() }
    val net = AntTheme.net
    PageColumn {
        HeroCard {
            val attacking = watch.kinds.filter { it.attacking }
            Text(
                if (attacking.isNotEmpty()) S(R.string.blespam_under_attack) else S(R.string.blespam_quiet),
                style = rf(26, 32, 600),
            )
            Text(
                if (attacking.isNotEmpty()) attacking.joinToString(" · ") { it.kind.label } else S(R.string.blespam_quiet_body),
                style = rf(14, 20),
            )
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (attacking.isNotEmpty()) HeroChip(S(R.string.blespam_spam_now), net.poor, blink = true)
                else HeroChip(S(R.string.bluetooth_listening_in_progress), net.good, blink = true)
                if (watch.flippers.isNotEmpty()) HeroChip(S(R.string.blespam_flipper_nearby, watch.flippers.size), net.fair)
            }
            // The engine keeps watching with the app closed and alerts on an attack or a Flipper Zero.
            Row(
                Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.surface.copy(alpha = 0.7f))
                    .clickable { onGuard(!guardRunning) }.padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Symbol(Sym.Shield, size = 22.dp, filled = guardRunning, tint = if (guardRunning) net.good else cs.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text(S(R.string.blespam_guard), style = rf(15, 20, 600))
                    Text(if (guardRunning) S(R.string.blespam_guard_on) else S(R.string.blespam_guard_off), style = rf(12, 16), color = cs.onSurfaceVariant)
                }
                androidx.compose.material3.Switch(
                    checked = guardRunning, onCheckedChange = onGuard,
                    thumbContent = if (guardRunning) ({ Symbol(Sym.Check, size = 16.dp, tint = cs.primary) }) else null,
                    colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = cs.primary, checkedThumbColor = cs.onPrimary),
                )
            }
        }

        if (watch.flippers.isNotEmpty()) {
            Text(S(R.string.blespam_flippers), Modifier.padding(start = 8.dp, top = 4.dp), style = rf(14, 20, 600), color = cs.onSurfaceVariant)
            SectionCard(padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                Column {
                    watch.flippers.forEachIndexed { i, f ->
                        if (i > 0) Hairline()
                        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LeadingIcon(Sym.Pets, net.fair.copy(alpha = 0.18f), net.fair, 44.dp, RoundedCornerShape(14.dp), 24.dp)
                            Column(Modifier.weight(1f)) {
                                Text(f.name ?: "Flipper Zero", style = rf(16, 22, 600), maxLines = 1)
                                val color = when (f.color) {
                                    FlipperColor.White -> S(R.string.blespam_flipper_white)
                                    FlipperColor.Black -> S(R.string.blespam_flipper_black)
                                    FlipperColor.Transparent -> S(R.string.blespam_flipper_transparent)
                                    FlipperColor.Unknown -> S(if (f.clue == FlipperClue.Address) R.string.blespam_flipper_by_address else R.string.blespam_flipper_by_name)
                                }
                                Text("${f.address.uppercase(Locale.ROOT)} · $color", style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 1)
                            }
                            Text("${f.rssi} dBm", style = gs(14, 18, 700, tnum = true), color = cs.onSurface)
                        }
                    }
                }
            }
        }

        Text(S(R.string.blespam_now), Modifier.padding(start = 8.dp, top = 4.dp), style = rf(14, 20, 600), color = cs.onSurfaceVariant)
        if (watch.kinds.isEmpty()) ToolEmpty(Sym.Shield, S(R.string.blespam_none), S(R.string.blespam_none_body), null)
        else SectionCard(padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
            Column { watch.kinds.forEachIndexed { i, k -> if (i > 0) Hairline(); KindRow(k) } }
        }

        if (watch.incidents.isNotEmpty()) {
            Text(S(R.string.blespam_history), Modifier.padding(start = 8.dp, top = 4.dp), style = rf(14, 20, 600), color = cs.onSurfaceVariant)
            SectionCard(padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                Column { watch.incidents.forEachIndexed { i, inc -> if (i > 0) Hairline(); IncidentRow(inc) } }
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(S(R.string.blespam_how_it_works), style = rf(13, 18), color = cs.onSurfaceVariant)
            }
        }
    }
}

private fun reasonsText(r: Set<SpamReason>) = SpamReason.entries.filter { it in r }.joinToString(", ") {
    S(
        when (it) {
            SpamReason.Crafted -> R.string.blespam_reason_crafted
            SpamReason.Flood -> R.string.blespam_reason_flood
            SpamReason.Cycling -> R.string.blespam_reason_cycling
            SpamReason.OneRadio -> R.string.blespam_reason_one_radio
        },
    )
}

@Composable
private fun KindRow(k: SpamKindState) {
    val net = AntTheme.net
    val color = if (k.attacking) net.poor else cs.onSurfaceVariant
    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LeadingIcon(if (k.attacking) Sym.Warning else Sym.Bluetooth, color.copy(alpha = 0.16f), color, 44.dp, RoundedCornerShape(14.dp), 24.dp)
        Column(Modifier.weight(1f)) {
            Text(k.kind.label, style = rf(16, 22, 600), maxLines = 1)
            Text(
                S(R.string.blespam_kind_line, k.kind.target, k.fresh, k.addresses) +
                    (if (k.attacking) " · " + reasonsText(k.reasons) else " · " + S(R.string.blespam_probably_real)) +
                    (k.detail?.let { " · $it" } ?: ""),
                style = rf(12, 16), color = cs.onSurfaceVariant,
            )
        }
        Text("${k.strongest} dBm", style = gs(14, 18, 700, tnum = true), color = color)
    }
}

@Composable
private fun IncidentRow(i: SpamIncident) {
    val net = AntTheme.net
    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Symbol(if (i.endMs == null) Sym.Warning else Sym.History, size = 22.dp, tint = if (i.endMs == null) net.poor else cs.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(i.kind.label, style = rf(15, 20, 600), maxLines = 1)
            Text(
                time(i.startMs) + " → " + (i.endMs?.let(::time) ?: S(R.string.blespam_ongoing)) + " · " + S(R.string.blespam_peak, i.peakFresh) + " · " + reasonsText(i.reasons),
                style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant,
            )
        }
        Text("${i.strongest} dBm", style = gs(13, 18, 600, tnum = true), color = cs.onSurfaceVariant)
    }
}
