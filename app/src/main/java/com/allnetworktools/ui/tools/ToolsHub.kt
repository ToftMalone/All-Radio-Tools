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
    val nfc by lazy { com.allnetworktools.ui.pages.nfc.NfcController(app, app.nfc, scope) }
    val ping by lazy { PingController(scope) }
    val trace by lazy { TraceController(scope) }
    val ports by lazy { PortsController(scope) }
    val dns by lazy { DnsController(scope) }
    val speed by lazy { SpeedController(scope) }
    // The same tools for mobile data: their own state, as they run in turns, never together.
    val cellPing by lazy { PingController(scope) }
    val cellTrace by lazy { TraceController(scope) }
    val cellDns by lazy { DnsController(scope).also { it.systemDns = "" } }
    val cellSpeed by lazy { SpeedController(scope) }
    val cellWhois by lazy { com.allnetworktools.ui.pages.wifi.WhoisController(scope) }
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
        com.allnetworktools.ui.pages.sdr.SpectrumController(app.sdr, scope) { acars.stop(); adsb.stop(); ais.stop(); aprs.stop(); cubesat.stop(); dect.stop(); dsc.stop(); flarm.stop(); fpv.stop(); ism.stop(); loraAprs.stop(); lpwan.stop(); meshcore.stop(); meshtastic.stop(); meteor.stop(); pmr.stop(); pocsag.stop(); sarsat.stop(); sonde.stop(); tpms.stop(); vdl2.stop(); wmbus.stop(); zwave.stop() }
    }
    val adsb: com.allnetworktools.ui.pages.sdr.AdsbController by lazy {
        com.allnetworktools.ui.pages.sdr.AdsbController(app.sdr, scope) { acars.stop(); ais.stop(); aprs.stop(); cubesat.stop(); dect.stop(); dsc.stop(); flarm.stop(); fpv.stop(); ism.stop(); loraAprs.stop(); lpwan.stop(); meshcore.stop(); meshtastic.stop(); meteor.stop(); pmr.stop(); pocsag.stop(); sarsat.stop(); sonde.stop(); spectrum.stop(); tpms.stop(); vdl2.stop(); wmbus.stop(); zwave.stop() }
    }
    val sonde: com.allnetworktools.ui.pages.sdr.SondeController by lazy {
        com.allnetworktools.ui.pages.sdr.SondeController(app.sdr, scope) { acars.stop(); adsb.stop(); ais.stop(); aprs.stop(); cubesat.stop(); dect.stop(); dsc.stop(); flarm.stop(); fpv.stop(); ism.stop(); loraAprs.stop(); lpwan.stop(); meshcore.stop(); meshtastic.stop(); meteor.stop(); pmr.stop(); pocsag.stop(); sarsat.stop(); spectrum.stop(); tpms.stop(); vdl2.stop(); wmbus.stop(); zwave.stop() }
    }
    val ais: com.allnetworktools.ui.pages.sdr.AisController by lazy {
        com.allnetworktools.ui.pages.sdr.AisController(app.sdr, scope) { acars.stop(); adsb.stop(); aprs.stop(); cubesat.stop(); dect.stop(); dsc.stop(); flarm.stop(); fpv.stop(); ism.stop(); loraAprs.stop(); lpwan.stop(); meshcore.stop(); meshtastic.stop(); meteor.stop(); pmr.stop(); pocsag.stop(); sarsat.stop(); sonde.stop(); spectrum.stop(); tpms.stop(); vdl2.stop(); wmbus.stop(); zwave.stop() }
    }
    val aprs: com.allnetworktools.ui.pages.sdr.AprsController by lazy {
        com.allnetworktools.ui.pages.sdr.AprsController(app.sdr, scope) { acars.stop(); adsb.stop(); ais.stop(); cubesat.stop(); dect.stop(); dsc.stop(); flarm.stop(); fpv.stop(); ism.stop(); loraAprs.stop(); lpwan.stop(); meshcore.stop(); meshtastic.stop(); meteor.stop(); pmr.stop(); pocsag.stop(); sarsat.stop(); sonde.stop(); spectrum.stop(); tpms.stop(); vdl2.stop(); wmbus.stop(); zwave.stop() }
    }
    val fpv: com.allnetworktools.ui.pages.sdr.FpvController by lazy {
        com.allnetworktools.ui.pages.sdr.FpvController(app.sdr, scope) { acars.stop(); adsb.stop(); ais.stop(); aprs.stop(); cubesat.stop(); dect.stop(); dsc.stop(); flarm.stop(); ism.stop(); loraAprs.stop(); lpwan.stop(); meshcore.stop(); meshtastic.stop(); meteor.stop(); pmr.stop(); pocsag.stop(); sarsat.stop(); sonde.stop(); spectrum.stop(); tpms.stop(); vdl2.stop(); wmbus.stop(); zwave.stop() }
    }
    val acars: com.allnetworktools.ui.pages.sdr.AcarsController by lazy {
        com.allnetworktools.ui.pages.sdr.AcarsController(app.sdr, scope) { adsb.stop(); ais.stop(); aprs.stop(); cubesat.stop(); dect.stop(); dsc.stop(); flarm.stop(); fpv.stop(); ism.stop(); loraAprs.stop(); lpwan.stop(); meshcore.stop(); meshtastic.stop(); meteor.stop(); pmr.stop(); pocsag.stop(); sarsat.stop(); sonde.stop(); spectrum.stop(); tpms.stop(); vdl2.stop(); wmbus.stop(); zwave.stop() }
    }
    val meteor: com.allnetworktools.ui.pages.sdr.MeteorController by lazy {
        com.allnetworktools.ui.pages.sdr.MeteorController(app.sdr, app.weatherTles, scope) { acars.stop(); adsb.stop(); ais.stop(); aprs.stop(); cubesat.stop(); dect.stop(); dsc.stop(); flarm.stop(); fpv.stop(); ism.stop(); loraAprs.stop(); lpwan.stop(); meshcore.stop(); meshtastic.stop(); pmr.stop(); pocsag.stop(); sarsat.stop(); sonde.stop(); spectrum.stop(); tpms.stop(); vdl2.stop(); wmbus.stop(); zwave.stop() }
    }
    val tpms: com.allnetworktools.ui.pages.sdr.TpmsController by lazy {
        com.allnetworktools.ui.pages.sdr.TpmsController(app.sdr, scope) { acars.stop(); adsb.stop(); ais.stop(); aprs.stop(); cubesat.stop(); dect.stop(); dsc.stop(); flarm.stop(); fpv.stop(); ism.stop(); loraAprs.stop(); lpwan.stop(); meshcore.stop(); meshtastic.stop(); meteor.stop(); pmr.stop(); pocsag.stop(); sarsat.stop(); sonde.stop(); spectrum.stop(); vdl2.stop(); wmbus.stop(); zwave.stop() }
    }
    val pocsag: com.allnetworktools.ui.pages.sdr.PocsagController by lazy {
        com.allnetworktools.ui.pages.sdr.PocsagController(app.sdr, scope) { acars.stop(); adsb.stop(); ais.stop(); aprs.stop(); cubesat.stop(); dect.stop(); dsc.stop(); flarm.stop(); fpv.stop(); ism.stop(); loraAprs.stop(); lpwan.stop(); meshcore.stop(); meshtastic.stop(); meteor.stop(); pmr.stop(); sarsat.stop(); sonde.stop(); spectrum.stop(); tpms.stop(); vdl2.stop(); wmbus.stop(); zwave.stop() }
    }
    val ism: com.allnetworktools.ui.pages.sdr.IsmController by lazy {
        com.allnetworktools.ui.pages.sdr.IsmController(app.sdr, scope) { acars.stop(); adsb.stop(); ais.stop(); aprs.stop(); cubesat.stop(); dect.stop(); dsc.stop(); flarm.stop(); fpv.stop(); loraAprs.stop(); lpwan.stop(); meshcore.stop(); meshtastic.stop(); meteor.stop(); pmr.stop(); pocsag.stop(); sarsat.stop(); sonde.stop(); spectrum.stop(); tpms.stop(); vdl2.stop(); wmbus.stop(); zwave.stop() }
    }
    val flarm: com.allnetworktools.ui.pages.sdr.FlarmController by lazy {
        com.allnetworktools.ui.pages.sdr.FlarmController(app.sdr, scope) { acars.stop(); adsb.stop(); ais.stop(); aprs.stop(); cubesat.stop(); dect.stop(); dsc.stop(); fpv.stop(); ism.stop(); loraAprs.stop(); lpwan.stop(); meshcore.stop(); meshtastic.stop(); meteor.stop(); pmr.stop(); pocsag.stop(); sarsat.stop(); sonde.stop(); spectrum.stop(); tpms.stop(); vdl2.stop(); wmbus.stop(); zwave.stop() }
    }
    val wmbus: com.allnetworktools.ui.pages.sdr.WmbusController by lazy {
        com.allnetworktools.ui.pages.sdr.WmbusController(app.sdr, scope) { acars.stop(); adsb.stop(); ais.stop(); aprs.stop(); cubesat.stop(); dect.stop(); dsc.stop(); flarm.stop(); fpv.stop(); ism.stop(); loraAprs.stop(); lpwan.stop(); meshcore.stop(); meshtastic.stop(); meteor.stop(); pmr.stop(); pocsag.stop(); sarsat.stop(); sonde.stop(); spectrum.stop(); tpms.stop(); vdl2.stop(); zwave.stop() }
    }
    val dsc: com.allnetworktools.ui.pages.sdr.DscController by lazy {
        com.allnetworktools.ui.pages.sdr.DscController(app.sdr, scope) { acars.stop(); adsb.stop(); ais.stop(); aprs.stop(); cubesat.stop(); dect.stop(); flarm.stop(); fpv.stop(); ism.stop(); loraAprs.stop(); lpwan.stop(); meshcore.stop(); meshtastic.stop(); meteor.stop(); pmr.stop(); pocsag.stop(); sarsat.stop(); sonde.stop(); spectrum.stop(); tpms.stop(); vdl2.stop(); wmbus.stop(); zwave.stop() }
    }
    val vdl2: com.allnetworktools.ui.pages.sdr.Vdl2Controller by lazy {
        com.allnetworktools.ui.pages.sdr.Vdl2Controller(app.sdr, scope) { acars.stop(); adsb.stop(); ais.stop(); aprs.stop(); cubesat.stop(); dect.stop(); dsc.stop(); flarm.stop(); fpv.stop(); ism.stop(); loraAprs.stop(); lpwan.stop(); meshcore.stop(); meshtastic.stop(); meteor.stop(); pmr.stop(); pocsag.stop(); sarsat.stop(); sonde.stop(); spectrum.stop(); tpms.stop(); wmbus.stop(); zwave.stop() }
    }
    val dect: com.allnetworktools.ui.pages.sdr.DectController by lazy {
        com.allnetworktools.ui.pages.sdr.DectController(app.sdr, scope) { acars.stop(); adsb.stop(); ais.stop(); aprs.stop(); cubesat.stop(); dsc.stop(); flarm.stop(); fpv.stop(); ism.stop(); loraAprs.stop(); lpwan.stop(); meshcore.stop(); meshtastic.stop(); meteor.stop(); pmr.stop(); pocsag.stop(); sarsat.stop(); sonde.stop(); spectrum.stop(); tpms.stop(); vdl2.stop(); wmbus.stop(); zwave.stop() }
    }
    val pmr: com.allnetworktools.ui.pages.sdr.PmrController by lazy {
        com.allnetworktools.ui.pages.sdr.PmrController(app.sdr, scope) { acars.stop(); adsb.stop(); ais.stop(); aprs.stop(); cubesat.stop(); dect.stop(); dsc.stop(); flarm.stop(); fpv.stop(); ism.stop(); loraAprs.stop(); lpwan.stop(); meshcore.stop(); meshtastic.stop(); meteor.stop(); pocsag.stop(); sarsat.stop(); sonde.stop(); spectrum.stop(); tpms.stop(); vdl2.stop(); wmbus.stop(); zwave.stop() }
    }
    val lpwan: com.allnetworktools.ui.pages.sdr.LpwanController by lazy {
        com.allnetworktools.ui.pages.sdr.LpwanController(app.sdr, scope) { acars.stop(); adsb.stop(); ais.stop(); aprs.stop(); cubesat.stop(); dect.stop(); dsc.stop(); flarm.stop(); fpv.stop(); ism.stop(); loraAprs.stop(); meshcore.stop(); meshtastic.stop(); meteor.stop(); pmr.stop(); pocsag.stop(); sarsat.stop(); sonde.stop(); spectrum.stop(); tpms.stop(); vdl2.stop(); wmbus.stop(); zwave.stop() }
    }
    val cubesat: com.allnetworktools.ui.pages.sdr.CubesatController by lazy {
        com.allnetworktools.ui.pages.sdr.CubesatController(app.sdr, app.amateurTles, scope) { acars.stop(); adsb.stop(); ais.stop(); aprs.stop(); dect.stop(); dsc.stop(); flarm.stop(); fpv.stop(); ism.stop(); loraAprs.stop(); lpwan.stop(); meshcore.stop(); meshtastic.stop(); meteor.stop(); pmr.stop(); pocsag.stop(); sarsat.stop(); sonde.stop(); spectrum.stop(); tpms.stop(); vdl2.stop(); wmbus.stop(); zwave.stop() }
    }
    val meshtastic: com.allnetworktools.ui.pages.sdr.MeshtasticController by lazy {
        com.allnetworktools.ui.pages.sdr.MeshtasticController(app.sdr, scope) { acars.stop(); adsb.stop(); ais.stop(); aprs.stop(); cubesat.stop(); dect.stop(); dsc.stop(); flarm.stop(); fpv.stop(); ism.stop(); loraAprs.stop(); lpwan.stop(); meshcore.stop(); meteor.stop(); pmr.stop(); pocsag.stop(); sarsat.stop(); sonde.stop(); spectrum.stop(); tpms.stop(); vdl2.stop(); wmbus.stop(); zwave.stop() }
    }
    val meshcore: com.allnetworktools.ui.pages.sdr.MeshCoreController by lazy {
        com.allnetworktools.ui.pages.sdr.MeshCoreController(app.sdr, scope) { acars.stop(); adsb.stop(); ais.stop(); aprs.stop(); cubesat.stop(); dect.stop(); dsc.stop(); flarm.stop(); fpv.stop(); ism.stop(); loraAprs.stop(); lpwan.stop(); meshtastic.stop(); meteor.stop(); pmr.stop(); pocsag.stop(); sarsat.stop(); sonde.stop(); spectrum.stop(); tpms.stop(); vdl2.stop(); wmbus.stop(); zwave.stop() }
    }
    val loraAprs: com.allnetworktools.ui.pages.sdr.LoraAprsController by lazy {
        com.allnetworktools.ui.pages.sdr.LoraAprsController(app.sdr, scope) { acars.stop(); adsb.stop(); ais.stop(); aprs.stop(); cubesat.stop(); dect.stop(); dsc.stop(); flarm.stop(); fpv.stop(); ism.stop(); lpwan.stop(); meshcore.stop(); meshtastic.stop(); meteor.stop(); pmr.stop(); pocsag.stop(); sarsat.stop(); sonde.stop(); spectrum.stop(); tpms.stop(); vdl2.stop(); wmbus.stop(); zwave.stop() }
    }
    val sarsat: com.allnetworktools.ui.pages.sdr.SarsatController by lazy {
        com.allnetworktools.ui.pages.sdr.SarsatController(app.sdr, scope) { acars.stop(); adsb.stop(); ais.stop(); aprs.stop(); cubesat.stop(); dect.stop(); dsc.stop(); flarm.stop(); fpv.stop(); ism.stop(); loraAprs.stop(); lpwan.stop(); meshcore.stop(); meshtastic.stop(); meteor.stop(); pmr.stop(); pocsag.stop(); sonde.stop(); spectrum.stop(); tpms.stop(); vdl2.stop(); wmbus.stop(); zwave.stop() }
    }
    val zwave: com.allnetworktools.ui.pages.sdr.ZwaveController by lazy {
        com.allnetworktools.ui.pages.sdr.ZwaveController(app.sdr, scope) { acars.stop(); adsb.stop(); ais.stop(); aprs.stop(); cubesat.stop(); dect.stop(); dsc.stop(); flarm.stop(); fpv.stop(); ism.stop(); loraAprs.stop(); lpwan.stop(); meshcore.stop(); meshtastic.stop(); meteor.stop(); pmr.stop(); pocsag.stop(); sarsat.stop(); sonde.stop(); spectrum.stop(); tpms.stop(); vdl2.stop(); wmbus.stop() }
    }
    val skyView = androidx.compose.runtime.mutableStateOf(com.allnetworktools.ui.pages.gnss.SkyView.Sky)
}
