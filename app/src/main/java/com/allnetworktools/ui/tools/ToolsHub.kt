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
    val hiddenCam by lazy { com.allnetworktools.ui.pages.wardrive.HiddenCamController(scope, lanScanner) }
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
        com.allnetworktools.ui.pages.sdr.SpectrumController(app.sdr, scope) { adsb.stop(); sonde.stop(); ais.stop(); aprs.stop(); meteor.stop(); fpv.stop(); acars.stop(); tpms.stop(); pocsag.stop(); ism.stop(); flarm.stop(); wmbus.stop(); dsc.stop(); vdl2.stop(); dect.stop(); pmr.stop(); lpwan.stop(); cubesat.stop() }
    }
    val adsb: com.allnetworktools.ui.pages.sdr.AdsbController by lazy {
        com.allnetworktools.ui.pages.sdr.AdsbController(app.sdr, scope) { spectrum.stop(); sonde.stop(); ais.stop(); aprs.stop(); meteor.stop(); fpv.stop(); acars.stop(); tpms.stop(); pocsag.stop(); ism.stop(); flarm.stop(); wmbus.stop(); dsc.stop(); vdl2.stop(); dect.stop(); pmr.stop(); lpwan.stop(); cubesat.stop() }
    }
    val sonde: com.allnetworktools.ui.pages.sdr.SondeController by lazy {
        com.allnetworktools.ui.pages.sdr.SondeController(app.sdr, scope) { spectrum.stop(); adsb.stop(); ais.stop(); aprs.stop(); meteor.stop(); fpv.stop(); acars.stop(); tpms.stop(); pocsag.stop(); ism.stop(); flarm.stop(); wmbus.stop(); dsc.stop(); vdl2.stop(); dect.stop(); pmr.stop(); lpwan.stop(); cubesat.stop() }
    }
    val ais: com.allnetworktools.ui.pages.sdr.AisController by lazy {
        com.allnetworktools.ui.pages.sdr.AisController(app.sdr, scope) { spectrum.stop(); adsb.stop(); sonde.stop(); aprs.stop(); meteor.stop(); fpv.stop(); acars.stop(); tpms.stop(); pocsag.stop(); ism.stop(); flarm.stop(); wmbus.stop(); dsc.stop(); vdl2.stop(); dect.stop(); pmr.stop(); lpwan.stop(); cubesat.stop() }
    }
    val aprs: com.allnetworktools.ui.pages.sdr.AprsController by lazy {
        com.allnetworktools.ui.pages.sdr.AprsController(app.sdr, scope) { spectrum.stop(); adsb.stop(); sonde.stop(); ais.stop(); meteor.stop(); fpv.stop(); acars.stop(); tpms.stop(); pocsag.stop(); ism.stop(); flarm.stop(); wmbus.stop(); dsc.stop(); vdl2.stop(); dect.stop(); pmr.stop(); lpwan.stop(); cubesat.stop() }
    }
    val fpv: com.allnetworktools.ui.pages.sdr.FpvController by lazy {
        com.allnetworktools.ui.pages.sdr.FpvController(app.sdr, scope) { spectrum.stop(); adsb.stop(); sonde.stop(); ais.stop(); aprs.stop(); meteor.stop(); acars.stop(); tpms.stop(); pocsag.stop(); ism.stop(); flarm.stop(); wmbus.stop(); dsc.stop(); vdl2.stop(); dect.stop(); pmr.stop(); lpwan.stop(); cubesat.stop() }
    }
    val acars: com.allnetworktools.ui.pages.sdr.AcarsController by lazy {
        com.allnetworktools.ui.pages.sdr.AcarsController(app.sdr, scope) { spectrum.stop(); adsb.stop(); sonde.stop(); ais.stop(); aprs.stop(); fpv.stop(); meteor.stop(); tpms.stop(); pocsag.stop(); ism.stop(); flarm.stop(); wmbus.stop(); dsc.stop(); vdl2.stop(); dect.stop(); pmr.stop(); lpwan.stop(); cubesat.stop() }
    }
    val meteor: com.allnetworktools.ui.pages.sdr.MeteorController by lazy {
        com.allnetworktools.ui.pages.sdr.MeteorController(app.sdr, app.weatherTles, scope) { spectrum.stop(); adsb.stop(); sonde.stop(); ais.stop(); aprs.stop(); fpv.stop(); acars.stop(); tpms.stop(); pocsag.stop(); ism.stop(); flarm.stop(); wmbus.stop(); dsc.stop(); vdl2.stop(); dect.stop(); pmr.stop(); lpwan.stop(); cubesat.stop() }
    }
    val tpms: com.allnetworktools.ui.pages.sdr.TpmsController by lazy {
        com.allnetworktools.ui.pages.sdr.TpmsController(app.sdr, scope) { spectrum.stop(); adsb.stop(); sonde.stop(); ais.stop(); aprs.stop(); meteor.stop(); fpv.stop(); acars.stop(); pocsag.stop(); ism.stop(); flarm.stop(); wmbus.stop(); dsc.stop(); vdl2.stop(); dect.stop(); pmr.stop(); lpwan.stop(); cubesat.stop() }
    }
    val pocsag: com.allnetworktools.ui.pages.sdr.PocsagController by lazy {
        com.allnetworktools.ui.pages.sdr.PocsagController(app.sdr, scope) { spectrum.stop(); adsb.stop(); sonde.stop(); ais.stop(); aprs.stop(); meteor.stop(); fpv.stop(); acars.stop(); tpms.stop(); ism.stop(); flarm.stop(); wmbus.stop(); dsc.stop(); vdl2.stop(); dect.stop(); pmr.stop(); lpwan.stop(); cubesat.stop() }
    }
    val ism: com.allnetworktools.ui.pages.sdr.IsmController by lazy {
        com.allnetworktools.ui.pages.sdr.IsmController(app.sdr, scope) { acars.stop(); adsb.stop(); ais.stop(); aprs.stop(); fpv.stop(); meteor.stop(); pocsag.stop(); sonde.stop(); spectrum.stop(); tpms.stop(); flarm.stop(); wmbus.stop(); dsc.stop(); vdl2.stop(); dect.stop(); pmr.stop(); lpwan.stop(); cubesat.stop() }
    }
    val flarm: com.allnetworktools.ui.pages.sdr.FlarmController by lazy {
        com.allnetworktools.ui.pages.sdr.FlarmController(app.sdr, scope) { acars.stop(); adsb.stop(); ais.stop(); aprs.stop(); fpv.stop(); ism.stop(); meteor.stop(); pocsag.stop(); sonde.stop(); spectrum.stop(); tpms.stop(); wmbus.stop(); dsc.stop(); vdl2.stop(); dect.stop(); pmr.stop(); lpwan.stop(); cubesat.stop() }
    }
    val wmbus: com.allnetworktools.ui.pages.sdr.WmbusController by lazy {
        com.allnetworktools.ui.pages.sdr.WmbusController(app.sdr, scope) { acars.stop(); adsb.stop(); ais.stop(); aprs.stop(); flarm.stop(); fpv.stop(); ism.stop(); meteor.stop(); pocsag.stop(); sonde.stop(); spectrum.stop(); tpms.stop(); dsc.stop(); vdl2.stop(); dect.stop(); pmr.stop(); lpwan.stop(); cubesat.stop() }
    }
    val dsc: com.allnetworktools.ui.pages.sdr.DscController by lazy {
        com.allnetworktools.ui.pages.sdr.DscController(app.sdr, scope) { acars.stop(); adsb.stop(); ais.stop(); aprs.stop(); flarm.stop(); fpv.stop(); ism.stop(); meteor.stop(); pocsag.stop(); sonde.stop(); spectrum.stop(); tpms.stop(); wmbus.stop(); vdl2.stop(); dect.stop(); pmr.stop(); lpwan.stop(); cubesat.stop() }
    }
    val vdl2: com.allnetworktools.ui.pages.sdr.Vdl2Controller by lazy {
        com.allnetworktools.ui.pages.sdr.Vdl2Controller(app.sdr, scope) { acars.stop(); adsb.stop(); ais.stop(); aprs.stop(); dsc.stop(); flarm.stop(); fpv.stop(); ism.stop(); meteor.stop(); pocsag.stop(); sonde.stop(); spectrum.stop(); tpms.stop(); wmbus.stop(); dect.stop(); pmr.stop(); lpwan.stop(); cubesat.stop() }
    }
    val dect: com.allnetworktools.ui.pages.sdr.DectController by lazy {
        com.allnetworktools.ui.pages.sdr.DectController(app.sdr, scope) { acars.stop(); adsb.stop(); ais.stop(); aprs.stop(); dsc.stop(); flarm.stop(); fpv.stop(); ism.stop(); meteor.stop(); pocsag.stop(); sonde.stop(); spectrum.stop(); tpms.stop(); vdl2.stop(); wmbus.stop(); pmr.stop(); lpwan.stop(); cubesat.stop() }
    }
    val pmr: com.allnetworktools.ui.pages.sdr.PmrController by lazy {
        com.allnetworktools.ui.pages.sdr.PmrController(app.sdr, scope) { acars.stop(); adsb.stop(); ais.stop(); aprs.stop(); dect.stop(); dsc.stop(); flarm.stop(); fpv.stop(); ism.stop(); meteor.stop(); pocsag.stop(); sonde.stop(); spectrum.stop(); tpms.stop(); vdl2.stop(); wmbus.stop(); lpwan.stop(); cubesat.stop() }
    }
    val lpwan: com.allnetworktools.ui.pages.sdr.LpwanController by lazy {
        com.allnetworktools.ui.pages.sdr.LpwanController(app.sdr, scope) { acars.stop(); adsb.stop(); ais.stop(); aprs.stop(); dect.stop(); dsc.stop(); flarm.stop(); fpv.stop(); ism.stop(); meteor.stop(); pmr.stop(); pocsag.stop(); sonde.stop(); spectrum.stop(); tpms.stop(); vdl2.stop(); wmbus.stop(); cubesat.stop() }
    }
    val cubesat: com.allnetworktools.ui.pages.sdr.CubesatController by lazy {
        com.allnetworktools.ui.pages.sdr.CubesatController(app.sdr, app.amateurTles, scope) { acars.stop(); adsb.stop(); ais.stop(); aprs.stop(); dect.stop(); dsc.stop(); flarm.stop(); fpv.stop(); ism.stop(); lpwan.stop(); meteor.stop(); pmr.stop(); pocsag.stop(); sonde.stop(); spectrum.stop(); tpms.stop(); vdl2.stop(); wmbus.stop() }
    }
    val skyView = androidx.compose.runtime.mutableStateOf(com.allnetworktools.ui.pages.gnss.SkyView.Sky)
}
