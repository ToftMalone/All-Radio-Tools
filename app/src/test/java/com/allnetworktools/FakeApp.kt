package com.allnetworktools

import android.content.Context
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.os.SystemClock
import com.allnetworktools.data.AdapterInfo
import com.allnetworktools.data.BleDevice
import com.allnetworktools.data.Ad
import com.allnetworktools.data.BluetoothRepository
import com.allnetworktools.data.BluetoothSnapshot
import com.allnetworktools.data.BondedDevice
import com.allnetworktools.data.CellMeasure
import com.allnetworktools.data.CellRepository
import com.allnetworktools.data.CellState
import com.allnetworktools.data.Constellation
import com.allnetworktools.data.FixType
import com.allnetworktools.data.GnssRepository
import com.allnetworktools.data.GnssState
import com.allnetworktools.data.PermGroup
import com.allnetworktools.data.PermissionSnapshot
import com.allnetworktools.data.PermissionsRepository
import com.allnetworktools.data.RadioTech
import com.allnetworktools.data.RadiosRepository
import com.allnetworktools.data.Satellite
import com.allnetworktools.data.SimInfo
import com.allnetworktools.data.WifiAp
import com.allnetworktools.data.WifiConnection
import com.allnetworktools.data.WifiRepository
import com.allnetworktools.ui.theme.Sym
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** Scenario switches read by the fakes; set before the ViewModel is created. */
object Scenario {
    val sdrDevice = kotlinx.coroutines.flow.MutableStateFlow<com.allnetworktools.data.sdr.SdrDevice?>(com.allnetworktools.data.sdr.SdrDevice("HackRF One", null))
    val cable = kotlinx.coroutines.flow.MutableStateFlow<com.allnetworktools.data.radio.RadioCable?>(com.allnetworktools.data.radio.RadioCable("FTDI", null))
    var airplane = false
    var gnssDenied = false
    var throttled = false
    var bleEmpty = false
    var evilTwin = false

    /** Set when a measurement flow starts being collected: the home screen must not start any. */
    var wifiStarted = false
    var bleStarted = false
    var cellStarted = false
    var gnssStarted = false
}

private fun Context.fakePermissions() = object : PermissionsRepository(this@fakePermissions) {
    override val state: StateFlow<PermissionSnapshot> = MutableStateFlow(
        PermissionSnapshot(
            PermGroup.entries.toSet() -
                (if (Scenario.gnssDenied) setOf(PermGroup.Location) else emptySet()),
        ),
    )

    override fun refresh() = Unit
}

private fun Context.fakeRadios() = object : RadiosRepository(this@fakeRadios) {
    override val hasWifi = true
    override val hasBluetooth = true
    override val wifiEnabled: Flow<Boolean> = flowOf(!Scenario.airplane)
    override val bluetoothEnabled: Flow<Boolean> = flowOf(!Scenario.airplane)
    override val airplaneMode: Flow<Boolean> = flowOf(Scenario.airplane)
    override val locationEnabled: Flow<Boolean> = flowOf(true)
}

internal val scan = listOf(
    WifiAp("Freebox-7A2C", "a4:3e:51:7c:2a:9f", -54, 5180, 80, "WPA3", ScanResult.WIFI_STANDARD_11AX, 5210),
    WifiAp("FreeWifi_secure", "a4:3e:51:7c:2a:a0", -58, 5180, 80, "WPA2-EAP", ScanResult.WIFI_STANDARD_11AX, 5210),
    WifiAp("Livebox-91F0", "70:fc:8f:11:22:33", -67, 5220, 80, "WPA2", ScanResult.WIFI_STANDARD_11AC, 5210),
    WifiAp("SFR_5G_B2E1", "00:1f:9f:44:55:66", -73, 5500, 80, "WPA2", ScanResult.WIFI_STANDARD_11AC, 5530),
    WifiAp("Bbox-5G-4410", "e8:ad:a6:77:88:99", -79, 5260, 80, "WPA2/3", ScanResult.WIFI_STANDARD_11AX, 5290),
    WifiAp("DIRECT-HP-Print", "fa:da:0c:01:02:03", -82, 5745, 20, "WPA2", ScanResult.WIFI_STANDARD_11N, 5745),
    WifiAp("Café Lumière", "b0:be:76:04:05:06", -86, 5580, 40, "Ouvert", ScanResult.WIFI_STANDARD_11AC, 5590),
    WifiAp("Freebox-7A2C", "a4:3e:51:7c:2a:9e", -51, 2437, 20, "WPA3", ScanResult.WIFI_STANDARD_11AX, 2437),
    WifiAp("Livebox-91F0", "70:fc:8f:11:22:34", -63, 2412, 20, "WPA2", ScanResult.WIFI_STANDARD_11N, 2412),
    WifiAp("SFR_B2E1", "00:1f:9f:44:55:67", -70, 2462, 20, "WPA2", ScanResult.WIFI_STANDARD_11N, 2462),
)

/** A copy of the Freebox network, open and stronger, from a software-generated address; a Livebox copy from another maker. */
internal val twins = listOf(
    WifiAp("Freebox-7A2C", "02:4a:91:c3:5e:10", -41, 5180, 80, "Ouvert", ScanResult.WIFI_STANDARD_11N, 5180),
    WifiAp("Livebox-91F0", "b8:27:eb:6a:0d:44", -58, 2412, 20, "WPA2", ScanResult.WIFI_STANDARD_11N, 2412),
    WifiAp("Bbox-5G-4410", "e8:ad:a6:77:88:9a", -81, 5260, 80, "WPA2/3", ScanResult.WIFI_STANDARD_11AX, 5290),
)

private fun Context.fakeWifi() = object : WifiRepository(this@fakeWifi) {
    private var rssi = -54
    private val wifiConn = WifiConnection(
        ssid = "Freebox-7A2C", bssid = "a4:3e:51:7c:2a:9f", rssi = -54, frequency = 5180, linkTx = 1201, linkRx = 960,
        standard = ScanResult.WIFI_STANDARD_11AX, security = "WPA3-Personnel (SAE)", ipv4 = "192.168.1.42", prefix = 24,
        gateway = "192.168.1.254", dns = listOf("192.168.1.254", "1.1.1.1"), ipv6 = "2a01:e0a:3c1:5e70::7f2e",
        connectedAtElapsed = SystemClock.elapsedRealtime() - 8_040_000,
    )
    override val connection: Flow<WifiConnection?> = flowOf(wifiConn).onStart { Scenario.wifiStarted = true }

    override fun pollRssi(): com.allnetworktools.data.WifiPoll {
        rssi = (rssi + listOf(-3, -1, 0, 2, 3).random()).coerceIn(-64, -46)
        return com.allnetworktools.data.WifiPoll(rssi, 1201, 960, "a4:3e:51:7c:2a:9f", 5180)
    }

    override val throttledUntil: StateFlow<Long> =
        MutableStateFlow(if (Scenario.throttled) SystemClock.elapsedRealtime() + 72_000 else 0L)

    override fun startScan() = !Scenario.throttled
    override val scanResults: Flow<List<WifiAp>> = flowOf(if (Scenario.evilTwin) scan + twins else scan)
}

private fun fakeBle(addr: String, name: String?, rssi: Int, hex: String, connectable: Boolean = true) =
    BleDevice.from(addr, name, rssi, null, connectable, 0, Ad.parseHex(hex))

private val bleDevices = listOf(
    fakeBle("F4:0E:11:A2:3C:9B", "Pixel Buds Pro 2", -48, "02010606162CFECD8256"),
    fakeBle("D3:11:6C:0A:52:9E", null, -52, "1EFF4C000719010E202B8F" + "00".repeat(20)),
    fakeBle("C8:2A:DD:14:07:E1", "Pixel Watch 3", -55, "020106"),
    fakeBle("54:8C:A0:5E:7B:11", null, -66, "0FFF0600010F2002A1B2C3D4E5F60718"),
    fakeBle("E4:2B:34:7A:11:C0", "Polar H10 7A3B2C", -69, "0303" + "0D18"),
    fakeBle("70:99:1C:5B:E2:40", "JBL Flip 6", -71, "020106"),
    fakeBle("E6:43:9A:0C:71:D8", "Tile Mate", -74, "0303EDFE", connectable = false),
    fakeBle("F0:12:88:43:9D:02", null, -77, "1AFF4C000215B9407F30F5F8466EAFF925556B57FE6D00010002C5", connectable = false),
    fakeBle("D2:5F:88:31:AA:06", "Mi Smart Band 8", -79, "020106"),
    fakeBle("C1:07:E8:55:20:19", null, -82, "0303AAFE0E16AAFE10E7036578616D706C6507", connectable = false),
    fakeBle("5A:1B:C7:9E:22:F3", null, -84, "0AFF4C0010050B1C8E3D21", connectable = false),
    fakeBle("48:AA:10:6F:31:C7", null, -85, "06FF7500420401"),
    fakeBle("2C:41:A1:6D:90:3B", "LE-Bose QC45", -88, "020106"),
    fakeBle("7E:03:5B:99:AC:40", null, -89, "020106020AF4"),
    fakeBle("6B:20:91:C4:0F:E7", null, -90, "020106", connectable = false),
)

private fun Context.fakeBluetooth() = object : BluetoothRepository(this@fakeBluetooth) {
    override val snapshot: Flow<BluetoothSnapshot> = flowOf(
        BluetoothSnapshot(
            AdapterInfo("Pixel 9 Pro", listOf("Bluetooth 5", "LE Audio", "Auracast")),
            listOf(
                BondedDevice("F4:0E:11:A2:3C:9B", "Pixel Buds Pro 2", Sym.Headphones, "Audio", true, listOf("LE Audio", "A2DP", "HFP"), "Double mode", 80),
                BondedDevice("C8:2A:DD:14:07:E1", "Pixel Watch 3", Sym.Watch, "Montre", true, listOf("GATT")),
                BondedDevice("aa", "MX Keys", Sym.Keyboard, "Clavier", false, emptyList()),
                BondedDevice("bb", "JBL Flip 6", Sym.Speaker, "Enceinte", false, emptyList()),
                BondedDevice("cc", "Peugeot 208", Sym.DirectionsCar, "Voiture", false, emptyList()),
                BondedDevice("dd", "MX Master 3S", Sym.Mouse, "Souris", false, emptyList()),
            ),
        ),
    )

    override fun bleScan(lowPower: Boolean, staleMs: Long, filters: List<android.bluetooth.le.ScanFilter>?): Flow<List<BleDevice>> =
        flowOf(if (Scenario.bleEmpty) emptyList() else bleDevices).onStart { Scenario.bleStarted = true }
}

private fun cell(tech: RadioTech, band: String, pci: Int, label: String, arfcn: Int, level: Int, registered: Boolean = false) = CellMeasure(
    tech = tech, registered = registered, band = band, arfcnLabel = label, arfcn = arfcn, pci = pci, tac = 36104,
    cellId = if (registered) 2351872017L else null, nodeId = if (registered) 574187L else null, sector = 17,
    mcc = "208", mnc = "01", rsrp = level, rsrq = -11, sinr = 14, rssi = when (tech) { RadioTech.LTE -> -67; RadioTech.NR -> level + 24; else -> null }, rssiEstimated = tech == RadioTech.NR,
    cqi = 12, timingAdvance = 3, bandwidthKhz = if (registered) 90_000 else null, downlinkMhz = if (tech == RadioTech.NR) 3549.99 else null,
)

private fun Context.fakeCell() = object : CellRepository(this@fakeCell) {
    override val hasTelephony = true
    override fun simReady() = true
    override fun cells(refreshMs: Long): Flow<CellState> = flowOf(Unit).onStart { Scenario.cellStarted = true }.map {
        CellState(
            operator = "Orange F", simSlot = 1, techLabel = "5G SA", techLong = "STANDALONE", techBig = "5G",
            roaming = false, voiceAndData = true,
            serving = cell(RadioTech.NR, "n78", 412, "NR-ARFCN", 636666, -92, registered = true),
            neighbors = listOf(
                cell(RadioTech.LTE, "B20", 97, "EARFCN", 6300, -89),
                cell(RadioTech.LTE, "B3", 322, "EARFCN", 1850, -95),
                cell(RadioTech.NR, "n78", 187, "NR-ARFCN", 636666, -99),
                cell(RadioTech.LTE, "B7", 322, "EARFCN", 3100, -101),
                cell(RadioTech.NR, "n78", 58, "NR-ARFCN", 636666, -106),
                cell(RadioTech.LTE, "B1", 445, "EARFCN", 300, -112),
            ),
            servingBandwidthsKhz = listOf(90_000), signalLevel = 3, hasService = true,
        )
    }

    override fun plmns(): Pair<String?, String?> = "20801" to "20801"

    override fun sims() = listOf(
        SimInfo(1, 1, "Orange F", listOf("Données mobiles", "Appels", "SMS"), false, "5G", 3, true),
        SimInfo(2, 2, "Free Mobile", emptyList(), true, "4G+", 2, false),
    )
}

private val satRandom = java.util.Random(7)

private val sats = listOf(
    Constellation.GPS to 5, Constellation.GPS to 12, Constellation.GPS to 13, Constellation.GPS to 15, Constellation.GPS to 18,
    Constellation.GPS to 20, Constellation.GPS to 25, Constellation.GPS to 29, Constellation.Galileo to 3, Constellation.Galileo to 7,
    Constellation.Galileo to 11, Constellation.Galileo to 24, Constellation.Galileo to 26, Constellation.Galileo to 33,
    Constellation.Glonass to 2, Constellation.Glonass to 8, Constellation.Glonass to 17, Constellation.Glonass to 23,
    Constellation.BeiDou to 6, Constellation.BeiDou to 14, Constellation.BeiDou to 21, Constellation.BeiDou to 26,
    Constellation.BeiDou to 33, Constellation.BeiDou to 42, Constellation.QZSS to 194,
).mapIndexed { i, (c, svid) ->
    val r = satRandom
    val el = 8 + r.nextFloat() * 78
    val cn = 16 + r.nextFloat() * 30
    Satellite(c, svid, cn, el, (i * 137.5f + r.nextFloat() * 40) % 360, cn >= 26 && el >= 12, null)
}

private fun Context.fakeGnss() = object : GnssRepository(this@fakeGnss) {
    val loc = Location(LocationManager.GPS_PROVIDER).apply {
        latitude = 48.85661; longitude = 2.35222; altitude = 42.0; accuracy = 3.2f; speed = 0f; bearing = 184f
    }

    override fun lastKnownLocation() = loc
    override fun status(refreshMs: Long): Flow<GnssState> =
        flowOf(GnssState(sats, loc, FixType.Fix3D, 0.8f, 4100)).onStart { Scenario.gnssStarted = true }

    private fun at(provider: String, dLat: Double, dLon: Double, acc: Float) = Location(provider).apply {
        latitude = loc.latitude + dLat; longitude = loc.longitude + dLon; altitude = 47.0; accuracy = acc
        elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos() - 3_000_000_000L
        extras = android.os.Bundle().apply { putInt("satellites", 13) }
    }

    override fun raw(): Flow<com.allnetworktools.data.RawEpoch> = flowOf(GnssFake.epoch(sats))
    override fun capabilities() = com.allnetworktools.data.GnssCaps(true, true, false, true, true, true, true, false, true)

    override fun positions(refreshMs: Long): Flow<com.allnetworktools.data.PositionSet> = flowOf(
        com.allnetworktools.data.PositionSet(
            gnss = at(LocationManager.GPS_PROVIDER, 0.0, 0.0, 3.2f),
            network = at(LocationManager.NETWORK_PROVIDER, 0.00028, 0.00035, 28f),
            fused = at(LocationManager.FUSED_PROVIDER, 0.00004, 0.00005, 6f),
        ),
    )
}

class FakeApp : AntApplication() {
    override val permissions by lazy { fakePermissions() }
    override val radios by lazy { fakeRadios() }
    override val wifi by lazy { fakeWifi() }
    override val bluetooth by lazy { fakeBluetooth() }
    override val cell by lazy { fakeCell() }
    override val gnss by lazy { fakeGnss() }
    override val tles by lazy {
        object : com.allnetworktools.data.orbit.TleRepository(null) {
            override suspend fun load(forceRefresh: Boolean) =
                parse(FakeApp::class.java.classLoader!!.getResource("gnss.tle")!!.readText(), System.currentTimeMillis() - 2 * 3_600_000L, false)
        }
    }
    override val towers by lazy {
        object : com.allnetworktools.data.TowerRepository() {
            override suspend fun fetch(q: com.allnetworktools.data.TowerQuery) =
                parse(q, FakeApp::class.java.classLoader!!.getResource("anfr_orange_paris.json")!!.readText(), System.currentTimeMillis())
        }
    }
    override val updater by lazy {
        object : com.allnetworktools.update.AppUpdater(this@FakeApp, "0.1") {
            override suspend fun check(autoInstall: Boolean, force: Boolean) = Unit
            override suspend fun releases() = listOf(
                com.allnetworktools.update.ReleaseNote("v0.9.3", "2026-10-04T11:43:26Z", "- GNSS : la Manche, la mer du Nord et la Baltique sont nommées sous les satellites\n- Bluetooth : une page complète par traqueur, avec Chaud/Froid et Faire sonner"),
                com.allnetworktools.update.ReleaseNote("v0.9.2", "2026-10-04T10:54:03Z", "- Wi-Fi : jauge de signal redessinée\n- Bluetooth : radar selon la distance estimée\n- Réseau mobile : carte des antennes retirée"),
                com.allnetworktools.update.ReleaseNote("v0.1", "2026-09-01T09:00:00Z", "- Première version"),
            )
        }
    }
    override val cables by lazy {
        object : com.allnetworktools.data.radio.RadioCableRepository(this@FakeApp) {
            override val cable: Flow<com.allnetworktools.data.radio.RadioCable?> = Scenario.cable
            override fun hasPermission(c: com.allnetworktools.data.radio.RadioCable) = true
        }
    }
    override val sdr by lazy {
        object : com.allnetworktools.data.sdr.SdrRepository(this@FakeApp) {
            override val device: Flow<com.allnetworktools.data.sdr.SdrDevice?> = Scenario.sdrDevice
        }
    }
    override val wifiDirect by lazy {
        object : com.allnetworktools.data.WifiDirectRepository(this@FakeApp) {
            override val supported = true
            override fun peers(): Flow<List<com.allnetworktools.data.P2pPeer>> = flowOf(
                listOf(
                    com.allnetworktools.data.P2pPeer("[TV] Samsung Q80 Series", "7a:bd:3c:11:4e:02", "7-0050F204-1", com.allnetworktools.data.P2pStatus.Available, false, true),
                    com.allnetworktools.data.P2pPeer("DIRECT-6B-HP OfficeJet 8020", "fa:da:0c:51:9a:6b", "3-0050F204-1", com.allnetworktools.data.P2pStatus.Available, true, true),
                    com.allnetworktools.data.P2pPeer("Pixel 9", "3e:11:aa:90:c2:7f", "10-0050F204-5", com.allnetworktools.data.P2pStatus.Unavailable, false, false),
                ),
            )
        }
    }
}

object FakeData {
    val scan get() = com.allnetworktools.scan

    val lan = listOf(
        com.allnetworktools.data.net.LanDevice("192.168.1.254", hostname = "freebox.lan", ms = 1f, isGateway = true, openPorts = setOf(80, 443)),
        com.allnetworktools.data.net.LanDevice("192.168.1.42", isSelf = true),
        com.allnetworktools.data.net.LanDevice("192.168.1.12", hostname = "macbook-air-de-lea.lan", ms = 4f, services = setOf("_companion-link._tcp")),
        com.allnetworktools.data.net.LanDevice("192.168.1.20", hostname = "lgwebostv.lan", ms = 6f, services = setOf("_airplay._tcp")),
        com.allnetworktools.data.net.LanDevice("192.168.1.23", mdnsName = "Chromecast Salon", ms = 5f, services = setOf("_googlecast._tcp")),
        com.allnetworktools.data.net.LanDevice("192.168.1.31", hostname = "hp-laserjet.lan", ms = 9f, openPorts = setOf(9100, 631)),
        com.allnetworktools.data.net.LanDevice("192.168.1.35", mdnsName = "Philips Hue", ms = 3f, services = setOf("_hue._tcp")),
        com.allnetworktools.data.net.LanDevice("192.168.1.102", hostname = "esp32-18f36d.lan", ms = 14f),
    )

    val hops = listOf(
        com.allnetworktools.ui.pages.wifi.Hop(1, "192.168.1.254", "freebox.lan", listOf(1.1f, 0.9f, 1.3f), "Passerelle · réseau local", false),
        com.allnetworktools.ui.pages.wifi.Hop(2, "78.254.1.62", null, listOf(4.6f, 4.2f, 5.1f), "Routeur opérateur", false),
        com.allnetworktools.ui.pages.wifi.Hop(3, null, null, listOf(null, null, null), "ICMP filtré par ce routeur", false),
        com.allnetworktools.ui.pages.wifi.Hop(4, "37.49.237.10", "th2.franceix.net", listOf(6.1f, 6.0f, 6.6f), "Point d'échange Internet", false),
        com.allnetworktools.ui.pages.wifi.Hop(5, "1.1.1.1", "one.one.one.one", listOf(6.4f, 6.2f, 6.5f), "Destination", true),
    )

    val ports = listOf(
        com.allnetworktools.ui.pages.wifi.OpenPort(53, "domain", null, null),
        com.allnetworktools.ui.pages.wifi.OpenPort(80, "http", "nginx · HTTP 200 OK", null),
        com.allnetworktools.ui.pages.wifi.OpenPort(443, "https", "nginx · HTTP 403 Forbidden", null),
        com.allnetworktools.ui.pages.wifi.OpenPort(445, "microsoft-ds", null, "Partage de fichiers exposé sur le réseau"),
    )
}
