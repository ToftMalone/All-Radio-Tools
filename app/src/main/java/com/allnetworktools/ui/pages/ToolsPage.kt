package com.allnetworktools.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.Page
import com.allnetworktools.model.Network
import com.allnetworktools.model.Tool
import com.allnetworktools.ui.components.LeadingIcon
import com.allnetworktools.ui.components.PillButton
import com.allnetworktools.ui.components.SectionTitle
import com.allnetworktools.ui.components.ShapeBadge
import com.allnetworktools.ui.components.TileGrid
import com.allnetworktools.ui.components.cookieShape
import com.allnetworktools.ui.components.fadeUp
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.util.plural
import com.allnetworktools.R
import com.allnetworktools.util.S
import com.allnetworktools.util.P

private data class ToolEntry(val tool: Tool, val title: String, val subtitle: String, val icon: String = tool.icon)
private data class ToolGroup(val title: String, val items: List<ToolEntry>)

private fun groups(net: Network, connectedDevice: String?, servingLabel: String?): List<ToolGroup> = when (net) {
    Network.Wifi -> listOf(
        ToolGroup(S(R.string.analysis), listOf(ToolEntry(Tool.Speed, S(R.string.speed_test), S(R.string.ping_download_upload)), ToolEntry(Tool.Coverage, S(R.string.coverage_map), S(R.string.the_signal_room_by_room)))),
        ToolGroup(S(R.string.local_network), listOf(ToolEntry(Tool.Lan, S(R.string.lan_devices), S(R.string.host_discovery)), ToolEntry(Tool.Ports, S(R.string.port_scan), S(R.string.tcp_common_ports)))),
        ToolGroup(S(R.string.discovery), listOf(ToolEntry(Tool.WifiDirect, "Wi-Fi Direct", S(R.string.devices_connected_directly)))),
        ToolGroup(
            S(R.string.diagnostics),
            listOf(ToolEntry(Tool.Ping, "Ping", S(R.string.latency_jitter_loss)), ToolEntry(Tool.Trace, "Traceroute", S(R.string.hops_to_the_host)), ToolEntry(Tool.Dns, "DNS Lookup", "A, AAAA, MX, TXT, NS"), ToolEntry(Tool.Whois, "Whois", S(R.string.domain_or_ip_address))),
        ),
        ToolGroup(
            S(R.string.security_2),
            listOf(
                ToolEntry(Tool.Audit, S(R.string.network_audit), S(R.string.encryption_wps_pmf_router)),
                ToolEntry(Tool.EvilTwin, S(R.string.fake_access_points), S(R.string.duplicate_ssids_evil_twin)),
                ToolEntry(Tool.Mitm, S(R.string.man_in_the_middle), S(R.string.arp_spoofing_rogue_dhcp)),
            ),
        ),
    )
    Network.Bluetooth -> listOf(
        ToolGroup(
            S(R.string.devices),
            listOf(
                ToolEntry(Tool.Tracker, S(R.string.hot_cold), S(R.string.find_a_device)),
                ToolEntry(Tool.Earbuds, S(R.string.earbuds_battery), S(R.string.airpods_and_beats_left_right_case)),
                ToolEntry(Tool.Beacons, S(R.string.beacons), "iBeacon, Eddystone, AltBeacon"),
            ),
        ),
        ToolGroup(S(R.string.security_2), listOf(ToolEntry(Tool.UnknownTrackers, S(R.string.unknown_trackers), S(R.string.airtag_smarttag_tile_following_you)))),
    )
    Network.Cellular -> listOf(
        ToolGroup(
            S(R.string.cells),
            listOf(
                ToolEntry(Tool.Neighbors, S(R.string.neighbour_cells), S(R.string.nr_and_lte_cells_seen_by)),
                ToolEntry(Tool.Handover, S(R.string.handover_log), S(R.string.cell_changes_on_the_move_csv)),
            ),
        ),
        ToolGroup(S(R.string.security_2), listOf(ToolEntry(Tool.FakeCells, S(R.string.fake_cell_detector), S(R.string.fake_cell_subtitle)))),
    )
    Network.Gnss -> listOf(
        ToolGroup(
            S(R.string.tools),
            listOf(
                ToolEntry(Tool.PositionCompare, S(R.string.compare_positions), S(R.string.gnss_network_and_fused)),
                ToolEntry(Tool.GnssClock, S(R.string.atomic_clock), S(R.string.satellite_utc_time_phone_offset)),
                ToolEntry(Tool.CompassCheck, S(R.string.compass_and_gnss_course), S(R.string.check_the_compass_while_walking)),
                ToolEntry(Tool.SkyMask, S(R.string.sky_mask), S(R.string.where_obstacles_hide_the_sky)),
            ),
        ),
        ToolGroup(
            S(R.string.expert),
            listOf(
                ToolEntry(Tool.GnssIntegrity, S(R.string.jamming_and_spoofing), S(R.string.detect_a_jammer_or_fake_signals)),
                ToolEntry(Tool.GnssRaw, S(R.string.raw_measurements), S(R.string.dual_frequency_l1_l5_rinex_export)),
            ),
        ),
    )
    Network.Talkie -> emptyList()
    Network.Sdr -> listOf(
        ToolGroup(S(R.string.tools), listOf(ToolEntry(Tool.Adsb, S(R.string.aircraft_ads_b), S(R.string.map_of_aircraft_on_1090_mhz)), ToolEntry(Tool.Sonde, S(R.string.radiosondes), S(R.string.rs41_m10_and_m20_sondes_around)), ToolEntry(Tool.Fm, S(R.string.fm_radio), S(R.string.listening_and_rds_87_5_to)), ToolEntry(Tool.Ais, S(R.string.ships_ais), S(R.string.map_of_ships_on_162_mhz)), ToolEntry(Tool.Meteor, S(R.string.weather_satellites_meteor_m), S(R.string.lrpt_images_on_137_mhz)), ToolEntry(Tool.Aprs, S(R.string.ham_radio_aprs), S(R.string.beacons_on_144_800_mhz)), ToolEntry(Tool.Acars, S(R.string.aircraft_messages_acars), S(R.string.airliner_messaging_131_mhz)), ToolEntry(Tool.Meshtastic, "Meshtastic", S(R.string.messages_positions_and_lora_nodes_868)), ToolEntry(Tool.GpsSdr, S(R.string.gps_receiver), S(R.string.position_computed_by_the_hackrf_1575)), ToolEntry(Tool.Fpv, S(R.string.drones), S(R.string.analog_fpv_dji_and_rc_links)))),
    )
}

private fun featuredSubtitle(net: Network, servingLabel: String?) = when (net) {
    Network.Wifi -> S(R.string.nearby_networks_and_channel_use)
    Network.Bluetooth -> S(R.string.nearby_devices_live_with_filters)
    Network.Cellular -> servingLabel ?: S(R.string.identifiers_and_measurements_of_the_connected)
    Network.Gnss -> S(R.string.sky_plot_and_world_map_of)
    Network.Sdr -> S(R.string.spectrum_and_waterfall_1_mhz_to)
    Network.Talkie -> S(R.string.read_edit_and_write_memories_over)
}

@Composable
private fun featuredBadge(net: Network, vm: MainViewModel): String? = when (net) {
    Network.Wifi -> vm.wifiScan.collectAsStateWithLifecycle().value?.let { "${it.size} ${P(R.plurals.p_networks, it.size)}" }
    Network.Bluetooth -> vm.ble.collectAsStateWithLifecycle().value.size.let { "$it ${P(R.plurals.p_devices, it)}" }
    Network.Cellular -> vm.cell.collectAsStateWithLifecycle().value.state?.serving?.tech?.short
    Network.Gnss -> vm.gnss.collectAsStateWithLifecycle().value.let { "${it.used.size}/${it.visible.size} satellites" }
    Network.Talkie -> vm.tools.radioChannels.let { c -> c.count.takeIf { it > 0 }?.let { "$it ${P(R.plurals.p_channels, it)}" } }
    Network.Sdr -> vm.tools.spectrum.let { if (it.running && it.tunedHz > 0) "%.1f MHz".format(java.util.Locale.getDefault(), it.tunedHz / 1e6) else null }
}

@Composable
fun ToolsPage(net: Network, vm: MainViewModel) {
    val acc = AntTheme.accent
    val open = { tool: Tool -> vm.navigate { it.copy(page = Page.ToolPage(tool)) } }
    // Only the network being shown is read: opening Wi-Fi tools must not start Bluetooth or the modem.
    val connected = if (net == Network.Bluetooth) vm.bluetooth.collectAsStateWithLifecycle().value.connected.firstOrNull()?.name else null
    val serving = if (net == Network.Cellular) vm.cell.collectAsStateWithLifecycle().value.state?.serving else null
    val servingLabel = serving?.let { s -> listOfNotNull(s.pci?.let { "PCI $it" }, s.band).joinToString(" · ").ifEmpty { null } }
    val badge = featuredBadge(net, vm)
    PageColumn {
        Column(
            Modifier.fadeUp().fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(acc.container)
                .clickable { open(net.featured) }.padding(20.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                ShapeBadge(net.featured.icon, cookieShape(), 72.dp, acc.accent, acc.onAccent, 34.dp, spinMs = 30_000)
                if (badge != null) {
                    Box(Modifier.clip(RoundedCornerShape(14.dp)).background(cs.surface).padding(horizontal = 10.dp, vertical = 5.dp)) {
                        Text(badge, style = rf(12, 18, 600), color = cs.onSurface)
                    }
                }
            }
            Text(net.featured.title, Modifier.padding(top = 16.dp), style = gs(26, 32, 500), color = acc.onContainer)
            Text(featuredSubtitle(net, servingLabel), Modifier.padding(top = 2.dp).graphicsLayer { alpha = 0.85f }, style = rf(14, 20), color = acc.onContainer)
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
                PillButton(S(R.string.launch), { open(net.featured) }, icon = Sym.PlayArrow, height = 48.dp)
            }
        }
        groups(net, connected, servingLabel).forEach { g ->
            SectionTitle(g.title)
            TileGrid(g.items) { e, mod ->
                Column(
                    mod.fadeUp(60).clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainerLow)
                        .clickable { open(e.tool) }.padding(16.dp),
                ) {
                    LeadingIcon(e.icon, cs.surfaceContainerHighest, acc.accent, shape = RoundedCornerShape(14.dp))
                    Spacer(Modifier.weight(1f, fill = false).heightIn(min = 12.dp))
                    Text(e.title, Modifier.padding(top = 12.dp), style = rf(15, 20, 600), maxLines = 2)
                    Text(e.subtitle, Modifier.padding(top = 2.dp), style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 2)
                }
            }
        }
    }
}
