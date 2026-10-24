package com.allnetworktools.ui.tools

import com.allnetworktools.AntApplication
import com.allnetworktools.data.net.LanScanner
import com.allnetworktools.ui.pages.bt.GattController
import com.allnetworktools.ui.pages.bt.TrackerController
import com.allnetworktools.ui.pages.wifi.DnsController
import com.allnetworktools.ui.pages.wifi.LanController
import com.allnetworktools.ui.pages.wifi.LanDeviceController
import com.allnetworktools.ui.pages.wifi.PingController
import com.allnetworktools.ui.pages.wifi.PortsController
import com.allnetworktools.ui.pages.wifi.SpeedController
import com.allnetworktools.ui.pages.wifi.TraceController
import kotlinx.coroutines.CoroutineScope

/** One controller per tool, owned by the ViewModel so a scan survives navigating away and back. */
class ToolsHub(private val app: AntApplication, private val scope: CoroutineScope) {
    private val lanScanner by lazy { LanScanner(app) }

    val lan by lazy { LanController(scope, lanScanner) }
    val lanDevice by lazy { LanDeviceController(scope, lanScanner) }
    val ping by lazy { PingController(scope) }
    val trace by lazy { TraceController(scope) }
    val ports by lazy { PortsController(scope) }
    val dns by lazy { DnsController(scope) }
    val speed by lazy { SpeedController(scope) }
    val coverage = com.allnetworktools.ui.pages.wifi.CoverageController()
    val whois by lazy { com.allnetworktools.ui.pages.wifi.WhoisController(scope) }
    val bleFilter = com.allnetworktools.ui.pages.bt.BleFilterState()
    val gatt by lazy { GattController(app, scope, app.bluetooth.identities) }
    val bleIdentify by lazy { com.allnetworktools.ui.pages.bt.BleIdentifyController(scope, app.bluetooth) }
    val tracker by lazy { TrackerController(app, scope) }
    val positionCompare = com.allnetworktools.ui.pages.gnss.PositionCompareController()
    val integrity = com.allnetworktools.ui.pages.gnss.IntegrityController()
    val raw = com.allnetworktools.ui.pages.gnss.RawController()
    val compassCheck = com.allnetworktools.ui.pages.gnss.CompassController()
    val skyMask = com.allnetworktools.ui.pages.gnss.SkyMaskController()
    private val probes by lazy { com.allnetworktools.data.RealProbes() }
    private val links by lazy { com.allnetworktools.data.LinkReader(app) }
    val audit by lazy { com.allnetworktools.ui.pages.wifi.AuditController(probes, links, scope) }
    val mitm by lazy { com.allnetworktools.ui.pages.wifi.MitmController(probes, links, scope) }
    val evilTwin = com.allnetworktools.ui.pages.wifi.EvilTwinController()
    // Shared with the background guard, which keeps feeding it with the app closed.
    val unknownTrackers get() = com.allnetworktools.data.TrackerGuard.controller(app)
    // One HackRF, one tool at a time: starting one stops the other.
    val spectrum: com.allnetworktools.ui.pages.sdr.SpectrumController by lazy {
        com.allnetworktools.ui.pages.sdr.SpectrumController(app.sdr, scope) { adsb.stop(); sonde.stop(); ais.stop(); aprs.stop(); meteor.stop(); fpv.stop(); acars.stop() }
    }
    val adsb: com.allnetworktools.ui.pages.sdr.AdsbController by lazy {
        com.allnetworktools.ui.pages.sdr.AdsbController(app.sdr, scope) { spectrum.stop(); sonde.stop(); ais.stop(); aprs.stop(); meteor.stop(); fpv.stop(); acars.stop() }
    }
    val sonde: com.allnetworktools.ui.pages.sdr.SondeController by lazy {
        com.allnetworktools.ui.pages.sdr.SondeController(app.sdr, scope) { spectrum.stop(); adsb.stop(); ais.stop(); aprs.stop(); meteor.stop(); fpv.stop(); acars.stop() }
    }
    val ais: com.allnetworktools.ui.pages.sdr.AisController by lazy {
        com.allnetworktools.ui.pages.sdr.AisController(app.sdr, scope) { spectrum.stop(); adsb.stop(); sonde.stop(); aprs.stop(); meteor.stop(); fpv.stop(); acars.stop() }
    }
    val aprs: com.allnetworktools.ui.pages.sdr.AprsController by lazy {
        com.allnetworktools.ui.pages.sdr.AprsController(app.sdr, scope) { spectrum.stop(); adsb.stop(); sonde.stop(); ais.stop(); meteor.stop(); fpv.stop(); acars.stop() }
    }
    val fpv: com.allnetworktools.ui.pages.sdr.FpvController by lazy {
        com.allnetworktools.ui.pages.sdr.FpvController(app.sdr, scope) { spectrum.stop(); adsb.stop(); sonde.stop(); ais.stop(); aprs.stop(); meteor.stop(); acars.stop() }
    }
    val acars: com.allnetworktools.ui.pages.sdr.AcarsController by lazy {
        com.allnetworktools.ui.pages.sdr.AcarsController(app.sdr, scope) { spectrum.stop(); adsb.stop(); sonde.stop(); ais.stop(); aprs.stop(); fpv.stop(); meteor.stop() }
    }
    val meteor: com.allnetworktools.ui.pages.sdr.MeteorController by lazy {
        com.allnetworktools.ui.pages.sdr.MeteorController(app.sdr, app.weatherTles, scope) { spectrum.stop(); adsb.stop(); sonde.stop(); ais.stop(); aprs.stop(); fpv.stop(); acars.stop() }
    }
    val radioChannels by lazy { com.allnetworktools.ui.pages.talkie.ChannelsController(app.cables, scope) }
    val skyView = androidx.compose.runtime.mutableStateOf(com.allnetworktools.ui.pages.gnss.SkyView.Sky)
}
