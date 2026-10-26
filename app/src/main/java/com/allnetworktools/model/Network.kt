package com.allnetworktools.model

import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.R
import com.allnetworktools.util.S

enum class Network(
    val label: String,
    val icon: String,
    val featuredShort: String,
) {
    Wifi("Wi-Fi", Sym.Wifi, "Scan"),
    Bluetooth("Bluetooth", Sym.Bluetooth, "BLE"),
    Cellular(S(R.string.mobile_network), Sym.CellBars3, S(R.string.cells)),
    Gnss("GNSS", Sym.SatelliteAlt, S(R.string.sky)),
    Sdr("SDR", Sym.Antenna, S(R.string.spectrum)),
    ;

    // Getters rather than constructor arguments: Tool's entries reference Network, so eager
    // initialisation in both directions would leave one side null.

    /** Tool highlighted at the top of the network's Tools page. */
    val featured: Tool
        get() = when (this) {
            Wifi -> Tool.WifiScan
            Bluetooth -> Tool.BleScan
            Cellular -> Tool.CellDetail
            Gnss -> Tool.Sky
            Sdr -> Tool.Spectrum
        }

    /** Tool reachable directly from the dock. */
    val dockShortcut: Tool?
        get() = featured
}

/** Where the back arrow of a tool leads. */
sealed interface ToolParent {
    data object Tools : ToolParent
    data object Dashboard : ToolParent
    data class Other(val tool: Tool) : ToolParent
}

enum class Tool(
    val network: Network,
    val title: String,
    val icon: String,
    val topAction: String? = null,
    val parent: ToolParent = ToolParent.Tools,
    val implemented: Boolean = false,
) {
    WifiScan(Network.Wifi, S(R.string.wi_fi_scanner), Sym.WifiFind, implemented = true),
    Lan(Network.Wifi, S(R.string.lan_devices), Sym.Devices, Sym.Refresh),
    LanDevice(Network.Wifi, S(R.string.lan_device), Sym.Devices, Sym.MoreVert, ToolParent.Other(Lan)),
    Ping(Network.Wifi, "Ping", Sym.NetworkPing, Sym.IosShare),
    Trace(Network.Wifi, "Traceroute", Sym.Route, Sym.IosShare),
    Ports(Network.Wifi, S(R.string.port_scan), Sym.Lan, Sym.IosShare),
    Dns(Network.Wifi, "DNS Lookup", Sym.Dns, Sym.History),
    Speed(Network.Wifi, S(R.string.speed_test), Sym.Speed, Sym.History),
    Whois(Network.Wifi, "Whois", Sym.TravelExplore, Sym.IosShare),
    EvilTwin(Network.Wifi, S(R.string.fake_access_points), Sym.WifiTetheringError, Sym.Refresh),
    Audit(Network.Wifi, S(R.string.network_audit), Sym.Shield, Sym.Refresh),
    Mitm(Network.Wifi, S(R.string.man_in_the_middle), Sym.SwapHoriz, Sym.RestartAlt),
    WifiDirect(Network.Wifi, "Wi-Fi Direct", Sym.WifiTethering),
    WifiAware(Network.Wifi, "Wi-Fi Aware", Sym.Smartphone),
    Coverage(Network.Wifi, S(R.string.coverage_map), Sym.GridOn),

    BleScan(Network.Bluetooth, S(R.string.ble_scanner), Sym.BluetoothSearching, implemented = true),
    Gatt(Network.Bluetooth, S(R.string.ble_device), Sym.AccountTree, Sym.MoreVert),
    Paired(Network.Bluetooth, S(R.string.paired_device), Sym.Headphones, Sym.MoreVert, ToolParent.Dashboard),
    Tracker(Network.Bluetooth, S(R.string.hot_cold), Sym.MyLocation),
    UnknownTrackers(Network.Bluetooth, S(R.string.unknown_trackers), Sym.GppMaybe, Sym.RestartAlt),
    TrackerDetail(Network.Bluetooth, S(R.string.tracker_2), Sym.Sell, parent = ToolParent.Other(UnknownTrackers)),
    Beacons(Network.Bluetooth, S(R.string.beacons), Sym.Sensors),
    Earbuds(Network.Bluetooth, S(R.string.earbuds_battery), Sym.Headphones),

    CellDetail(Network.Cellular, S(R.string.serving_cell), Sym.CellTower, Sym.ContentCopy, implemented = true),
    Neighbors(Network.Cellular, S(R.string.neighbour_cells), Sym.CellTower, implemented = true),
    Handover(Network.Cellular, S(R.string.handover_log), Sym.SwapCalls),
    FakeCells(Network.Cellular, S(R.string.fake_cell_detector), Sym.Shield),

    Sky(Network.Gnss, S(R.string.gnss_sky), Sym.SatelliteAlt, implemented = true),
    PositionCompare(Network.Gnss, S(R.string.compare_positions), Sym.ShareLocation, Sym.Refresh),
    GnssIntegrity(Network.Gnss, S(R.string.jamming_and_spoofing), Sym.Shield, Sym.RestartAlt),
    GnssRaw(Network.Gnss, S(R.string.raw_measurements), Sym.Monitoring),
    CompassCheck(Network.Gnss, S(R.string.compass_and_gnss_course), Sym.Explore, Sym.RestartAlt),
    GnssClock(Network.Gnss, S(R.string.atomic_clock), Sym.Schedule),
    SkyMask(Network.Gnss, S(R.string.sky_mask), Sym.Radar, Sym.RestartAlt),

    Spectrum(Network.Sdr, S(R.string.spectrum_analyser), Sym.BarChart),
    Adsb(Network.Sdr, "ADS-B", Sym.Flight),
    Sonde(Network.Sdr, S(R.string.radiosondes), Sym.Cloud),
    Ais(Network.Sdr, "AIS", Sym.Boat),
    Aprs(Network.Sdr, "APRS", Sym.Radio),
    Acars(Network.Sdr, "ACARS", Sym.Flight),
    Meteor(Network.Sdr, "Meteor-M", Sym.SatelliteAlt),
    Fpv(Network.Sdr, "Drones", Sym.Videocam),
    Tpms(Network.Sdr, "TPMS", Sym.TireRepair),
    Pocsag(Network.Sdr, "POCSAG", Sym.Sms),
    Dsc(Network.Sdr, "DSC", Sym.Sailing),
    Wmbus(Network.Sdr, "Wireless M-Bus", Sym.WaterDrop),
    Flarm(Network.Sdr, "FLARM", Sym.Flight),
    Ism(Network.Sdr, "ISM", Sym.Thermostat),
    ;

    val isDockShortcut: Boolean get() = network.dockShortcut == this
}
