package com.allnetworktools.ui.pages.bt

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.allnetworktools.data.BleDevice
import com.allnetworktools.data.Battery
import com.allnetworktools.data.Beacon
import com.allnetworktools.data.Beacons
import com.allnetworktools.data.Earbuds
import com.allnetworktools.data.EarbudsStatus
import com.allnetworktools.ui.LocalActions
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
import com.allnetworktools.ui.tools.ToolEmpty
import com.allnetworktools.util.fmt
import com.allnetworktools.util.plural
import com.allnetworktools.R
import com.allnetworktools.util.S
import com.allnetworktools.util.P

/** Keeps the order devices were first listed in, so rows do not move while their signal changes. */
private class FirstSeen {
    private val at = HashMap<String, Long>()
    fun <T> order(items: List<T>, key: (T) -> String): List<T> {
        val now = System.currentTimeMillis()
        items.forEach { at.getOrPut(key(it)) { now } }
        return items.sortedWith(compareBy<T>({ at[key(it)] }, { key(it) }))
    }
}

// ---- Beacons ---------------------------------------------------------------------------------------

@Composable
fun BeaconsTool(devices: List<BleDevice>) {
    val actions = LocalActions.current
    val seen = remember { FirstSeen() }
    val found = seen.order(devices.mapNotNull { d -> Beacons.parse(d.ads)?.let { d to it } }) { it.first.address }
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(found.size.toString(), style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(P(R.plurals.p_beacons_detected, found.size), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text(S(R.string.ibeacon_eddystone_and_altbeacon_shops_museums), style = rf(14, 20))
            Row(Modifier.padding(top = 12.dp)) { HeroChip(S(R.string.bluetooth_listening_in_progress), AntTheme.net.good, blink = true) }
        }
        if (found.isEmpty()) {
            ToolEmpty(Sym.Sensors, S(R.string.no_beacon), S(R.string.beacons_advertise_every_100_ms_to), null)
        }
        found.forEach { (d, b) -> BeaconCard(d, b) { url -> actions.openUrl(url) } }
        SectionCard {
            Text(
                S(R.string.a_beacon_broadcasts_an_identifier_continuously) +
                    S(R.string.the_distance_is_estimated_from_the),
                style = rf(13, 18), color = cs.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BeaconCard(d: BleDevice, b: Beacon, onUrl: (String) -> Unit) {
    val acc = AntTheme.accent
    val tlm = Beacons.telemetry(d.ads)
    SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LeadingIcon(if (b.kind == com.allnetworktools.data.BeaconKind.EddystoneUrl) Sym.Link else Sym.Sensors, acc.container, acc.onContainer, 44.dp, RoundedCornerShape(14.dp), 24.dp)
            Column(Modifier.weight(1f)) {
                Text(b.kind.label + (b.maker?.let { " · $it" } ?: ""), style = rf(16, 22, 600), maxLines = 1)
                Text(
                    "${d.rssi} dBm" + (b.distanceM(d.rssi)?.let { " · ≈ ${if (it < 10) fmt(it, 1) else fmt(it)} m" } ?: ""),
                    style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant,
                )
            }
        }
        b.details.forEach { (k, v) ->
            Hairline(Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(k, Modifier.weight(0.35f), style = rf(13, 18), color = cs.onSurfaceVariant)
                Text(v, Modifier.weight(0.65f), style = rf(13, 18, 600))
            }
        }
        if (b.kind == com.allnetworktools.data.BeaconKind.EddystoneUrl) {
            Text(
                S(R.string.open_the_link),
                Modifier.padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(acc.container).clickable { onUrl(b.id) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = rf(13, 18, 600), color = acc.onContainer,
            )
        }
        if (tlm != null) {
            Hairline(Modifier.padding(top = 8.dp))
            Text(
                listOfNotNull(
                    tlm.batteryMv?.let { S(R.string.battery_v, fmt(it / 1000.0, 2)) },
                    tlm.temperatureC?.let { "${fmt(it, 1)} °C" },
                    S(R.string.h_of_operation, fmt(tlm.uptimeS / 3600.0, 0)),
                ).joinToString(" · "),
                Modifier.padding(top = 8.dp), style = rf(12, 16), color = cs.onSurfaceVariant,
            )
        }
    }
}

// ---- Earbuds battery ---------------------------------------------------------------------------------

@Composable
fun EarbudsTool(devices: List<BleDevice>) {
    val seen = remember { FirstSeen() }
    val found = seen.order(devices.mapNotNull { d -> Earbuds.parse(d.ads)?.let { d to it } }) { it.first.address }
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(found.size.toString(), style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(P(R.plurals.p_pairs_of_earbuds, found.size), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text(S(R.string.airpods_and_beats_battery_of_each), style = rf(14, 20))
            Row(Modifier.padding(top = 12.dp)) { HeroChip(S(R.string.bluetooth_listening_in_progress), AntTheme.net.good, blink = true) }
        }
        if (found.isEmpty()) {
            ToolEmpty(
                Sym.Headphones, S(R.string.no_earbuds),
                S(R.string.open_your_airpods_or_beats_case), null,
            )
        }
        found.forEach { (d, e) -> EarbudsCard(d, e) }
        SectionCard {
            Text(
                S(R.string.apple_and_beats_earbuds_advertise_their) +
                    S(R.string.other_brands_don_t_broadcast_it),
                style = rf(13, 18), color = cs.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EarbudsCard(d: BleDevice, e: EarbudsStatus) {
    val acc = AntTheme.accent
    SectionCard(padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LeadingIcon(Sym.Headphones, acc.container, acc.onContainer, 44.dp, RoundedCornerShape(14.dp), 24.dp)
            Column(Modifier.weight(1f)) {
                Text(e.model, style = rf(16, 22, 600), maxLines = 1)
                Text("${d.rssi} dBm", style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (e.single != null) {
                BatteryTile(S(R.string.headphones_2), e.single, Modifier.weight(1f))
            } else {
                BatteryTile(S(R.string.left), e.left, Modifier.weight(1f))
                BatteryTile(S(R.string.right), e.right, Modifier.weight(1f))
                BatteryTile(S(R.string.case_text), e.case, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun BatteryTile(label: String, b: Battery?, modifier: Modifier) {
    val net = AntTheme.net
    val p = b?.percent
    val color = when {
        p == null -> cs.outline
        p <= 20 -> cs.error
        p <= 50 -> net.fair
        else -> net.good
    }
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(cs.surfaceContainerHigh).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(p?.let { "$it %" } ?: "—", style = rf(18, 24, 700, tnum = true))
            if (b?.charging == true) Symbol(Sym.Bolt, size = 16.dp, filled = true, tint = net.good)
        }
        Text(label, style = rf(12, 16), color = cs.onSurfaceVariant)
        Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(cs.surfaceContainerHighest)) {
            Box(Modifier.fillMaxWidth((p ?: 0) / 100f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(color))
        }
    }
}
