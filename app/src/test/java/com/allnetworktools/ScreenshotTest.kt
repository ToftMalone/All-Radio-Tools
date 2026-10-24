package com.allnetworktools

import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.allnetworktools.data.ThemeMode
import com.allnetworktools.model.Network
import com.allnetworktools.model.Tool
import com.allnetworktools.ui.AntApp
import com.allnetworktools.ui.tools.Phase
import com.allnetworktools.data.WifiBand
import com.allnetworktools.data.net.DnsAnswer
import com.allnetworktools.data.net.DnsRecord
import com.allnetworktools.data.net.DnsType
import com.allnetworktools.data.BleDevice
import com.allnetworktools.data.BleKind
import com.allnetworktools.data.GattNames
import com.allnetworktools.ui.pages.bt.GattCharUi
import com.allnetworktools.ui.pages.bt.GattConn
import com.allnetworktools.ui.pages.bt.GattServiceUi
import com.allnetworktools.ui.pages.bt.NotifyLine
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the main screens with fake data into build/screenshots for visual review.
 * Run with: ./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "fr-w412dp-h915dp-xxhdpi", application = FakeApp::class)
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<FakeApp>()

    @After
    fun reset() {
        Scenario.airplane = false
        Scenario.sdrDevice.value = com.allnetworktools.data.sdr.SdrDevice("HackRF One", null)
        Scenario.cable.value = com.allnetworktools.data.radio.RadioCable("FTDI", null)
        Scenario.gnssDenied = false
        Scenario.throttled = false
        Scenario.bleEmpty = false
        Scenario.evilTwin = false
        Scenario.wifiStarted = false
        Scenario.bleStarted = false
        Scenario.cellStarted = false
        Scenario.gnssStarted = false
    }

    private fun shot(name: String, dark: Boolean = false, onboarding: Boolean = false, nav: NavState = NavState(), setup: (MainViewModel) -> Unit = {}) {
        runBlocking {
            app.settings.setTheme(if (dark) ThemeMode.Dark else ThemeMode.Light)
            app.settings.setOnboardingDone(!onboarding)
        }
        val vm = MainViewModel(app)
        vm.navigate { nav }
        setup(vm)
        compose.mainClock.autoAdvance = false
        compose.setContent { AntApp(vm) }
        repeat(30) {
            compose.mainClock.advanceTimeBy(100)
            org.robolectric.shadows.ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun onboarding() = shot("01_onboarding", onboarding = true)
    @Test fun home() = shot("02_home")
    /** Neither the home screen nor picking a card may start a measurement: only a network's own pages do. */
    @Test fun homeStartsNoMeasurement() {
        shot("02b_home_idle")
        assertFalse("Wi-Fi", Scenario.wifiStarted)
        assertFalse("BLE", Scenario.bleStarted)
        assertFalse("Cellulaire", Scenario.cellStarted)
        assertFalse("GNSS", Scenario.gnssStarted)
    }
    @Test fun bluetoothDashboardStartsOnlyBluetooth() {
        shot("20b_bt_dashboard_alone", nav = NavState(Network.Bluetooth, Page.Dashboard))
        assertTrue("BLE", Scenario.bleStarted)
        assertFalse("Wi-Fi", Scenario.wifiStarted)
        assertFalse("Cellulaire", Scenario.cellStarted)
        assertFalse("GNSS", Scenario.gnssStarted)
    }
    @Test fun toolsPageStartsOnlyItsNetwork() {
        shot("11b_wifi_tools_alone", nav = NavState(Network.Wifi, Page.Tools))
        assertFalse("BLE", Scenario.bleStarted)
        assertFalse("Cellulaire", Scenario.cellStarted)
        assertFalse("GNSS", Scenario.gnssStarted)
    }
    @Test fun homeSelected() = shot("03_home_wifi_selected", nav = NavState(Network.Wifi))
    @Test fun dockOnTools() = shot("06_dock_tools", nav = NavState(Network.Wifi, Page.Tools))
    @Test fun homeDark() = shot("04_home_gnss_dark", dark = true, nav = NavState(Network.Gnss))
    @Test fun homeAirplane() {
        Scenario.airplane = true
        shot("05_home_airplane")
    }
    @Test fun wifiDashboard() = shot("10_wifi_dashboard", nav = NavState(Network.Wifi, Page.Dashboard))
    @Test fun wifiTools() = shot("11_wifi_tools", nav = NavState(Network.Wifi, Page.Tools))
    @Test fun wifiScanner() = shot("12_wifi_scanner", nav = NavState(Network.Wifi, Page.ToolPage(Tool.WifiScan)))
    @Test fun wifiThrottled() {
        Scenario.throttled = true
        shot("13_wifi_scanner_throttled", nav = NavState(Network.Wifi, Page.ToolPage(Tool.WifiScan)))
    }
    @Test fun btDashboard() = shot("20_bt_dashboard", nav = NavState(Network.Bluetooth, Page.Dashboard))
    @Test fun bleScanner() = shot("21_ble_scanner", nav = NavState(Network.Bluetooth, Page.ToolPage(Tool.BleScan)))
    @Test fun bleEmpty() {
        Scenario.bleEmpty = true
        shot("22_ble_empty", nav = NavState(Network.Bluetooth, Page.ToolPage(Tool.BleScan)))
    }
    @Test fun cellDashboard() = shot("30_cell_dashboard", nav = NavState(Network.Cellular, Page.Dashboard))
    @Test fun neighbors() = shot("31_cell_neighbors", nav = NavState(Network.Cellular, Page.ToolPage(Tool.Neighbors)))
    @Test fun gnssDashboard() = shot("40_gnss_dashboard", nav = NavState(Network.Gnss, Page.Dashboard))
    @Test fun gnssTools() = shot("43_gnss_tools", nav = NavState(Network.Gnss, Page.Tools))
    @Test fun gnssDenied() {
        Scenario.gnssDenied = true
        shot("42_gnss_denied", nav = NavState(Network.Gnss, Page.Dashboard))
    }
    @Test fun settings() = shot("50_settings", nav = NavState(page = Page.Settings))
    @Test fun changelog() = shot("52_changelog", nav = NavState(page = Page.Changelog))
    @Test fun settingsDark() = shot("51_settings_dark", dark = true, nav = NavState(page = Page.Settings))
    @Test fun cellDashboardDark() = shot("32_cell_dashboard_dark", dark = true, nav = NavState(Network.Cellular, Page.Dashboard))

    private fun tool(t: Tool, arg: String? = null) = NavState(Network.Wifi, Page.ToolPage(t, arg))

    @Test fun lanIdle() = shot("62_lan_idle", nav = tool(Tool.Lan))
    @Test fun lanResults() = shot("63_lan_results", nav = tool(Tool.Lan)) { vm ->
        vm.tools.lan.devices.addAll(FakeData.lan)
        vm.tools.lan.subnet = "192.168.1.0/24"
        vm.tools.lan.durationMs = 4800
        vm.tools.lan.phase = Phase.Results
    }
    @Test fun lanDevice() = shot("64_lan_device", nav = tool(Tool.LanDevice, "192.168.1.23")) { vm ->
        vm.tools.lan.devices.addAll(FakeData.lan)
        vm.tools.lanDevice.ip = "192.168.1.23"
        vm.tools.lanDevice.latency = 5f
        vm.tools.lanDevice.services.addAll(listOf(8008 to "HTTP 200 OK", 8009 to null, 8443 to "HTTP 404 Not Found"))
        vm.tools.lanDevice.phase = Phase.Results
    }
    @Test fun pingIdle() = shot("65_ping_idle", nav = tool(Tool.Ping))
    @Test fun pingResults() = shot("66_ping_results", nav = tool(Tool.Ping)) { vm ->
        val c = vm.tools.ping
        c.resolved = "one.one.one.one"
        c.results.addAll(listOf(13.2f, 12.8f, 15.1f, 14.0f, null, 13.6f, 29.4f, 12.9f, 13.3f, 14.2f))
        c.phase = Phase.Results
    }
    @Test fun traceResults() = shot("67_trace_results", nav = tool(Tool.Trace)) { vm ->
        val c = vm.tools.trace
        c.target = "1.1.1.1"
        c.hops.addAll(FakeData.hops)
        c.phase = Phase.Results
    }
    @Test fun portsIdle() = shot("68_ports_idle", nav = tool(Tool.Ports))
    @Test fun portsResults() = shot("69_ports_results", nav = tool(Tool.Ports)) { vm ->
        val c = vm.tools.ports
        c.host = "192.168.1.254"; c.total = 100; c.scanned = 100; c.closed = 93; c.filtered = 2
        c.startedAt = 1000; c.endedAt = 1300
        c.open.addAll(FakeData.ports)
        c.phase = Phase.Results
    }
    @Test fun dnsResults() = shot("70_dns_results", nav = tool(Tool.Dns)) { vm ->
        val c = vm.tools.dns
        c.type = DnsType.NS; c.host = "google.com"
        c.answer = DnsAnswer(0, (1..4).map { DnsRecord("NS", "ns$it.google.com", 21600) }, 9f)
        listOf("Système" to 18f, "Cloudflare" to 9f, "Google" to 14f, "Quad9" to 21f).forEach { (k, v) -> c.compare[k] = v }
        c.phase = Phase.Results
    }
    @Test fun coverageWalk() = shot("74_coverage_walk", nav = tool(Tool.Coverage)) { vm ->
        val c = vm.tools.coverage
        c.support = com.allnetworktools.ui.pages.wifi.ArSupport(com.google.ar.core.ArCoreApk.Availability.SUPPORTED_INSTALLED, tofCamera = true)
        // An L-shaped walk through a flat: along a corridor, then into a room further from the box.
        val walk = (0..14).map { com.allnetworktools.data.CoverageSample(it * 0.4f, 0f, -42 - it * 2, "aa", it * 1000L) } +
            (1..10).map { com.allnetworktools.data.CoverageSample(5.6f, -it * 0.4f, -70 - it * 2, "aa", 20_000L + it * 1000L) }
        c.samples.addAll(walk)
        walk.forEach { s -> (0..3).forEach { k -> c.floor.paint(s.x + if (s.z == 0f) k * 0.1f else 0f, s.z - if (s.z != 0f) k * 0.1f else 0f, 0f, s.rssi) } }
        c.floorVersion++
        c.x = 5.6f; c.z = -4f
    }
    @Test fun gnssIntegrity() = shot("75_gnss_integrity", nav = NavState(Network.Gnss, Page.ToolPage(Tool.GnssIntegrity)))
    @Test fun gnssRaw() = shot("76_gnss_raw", nav = NavState(Network.Gnss, Page.ToolPage(Tool.GnssRaw)))
    @Test fun gnssCompass() = shot("77_gnss_compass", nav = NavState(Network.Gnss, Page.ToolPage(Tool.CompassCheck)))
    @Test fun gnssClock() = shot("78_gnss_clock", nav = NavState(Network.Gnss, Page.ToolPage(Tool.GnssClock)))
    @Test fun gnssSkyMask() = shot("79_gnss_sky_mask", nav = NavState(Network.Gnss, Page.ToolPage(Tool.SkyMask))) { vm ->
        val fake = (0 until 40).map { k ->
            (0 until 24).map { i ->
                val az = (i * 15f + k) % 360
                val el = 12f + (i * 7 % 70)
                // A building to the north-east: everything low between 20° and 110° is weak or lost.
                val cn0 = if (az in 20f..110f && el < 45f) (if (i % 3 == 0) 18f else 0f) else 30f + el / 5
                com.allnetworktools.data.Satellite(com.allnetworktools.data.Constellation.GPS, i + 1, cn0, el, az, cn0 > 25, null)
            }
        }
        fake.forEach { vm.tools.skyMask.mask.feed(it) }
        vm.tools.skyMask.version++
    }
    @Test fun speedEmpty() = shot("71_speed_empty", nav = tool(Tool.Speed)) { vm ->
        vm.tools.speed.meta = SpeedFake.meta
    }
    @Test fun speedResults() = shot("72_speed_results", nav = tool(Tool.Speed)) { vm ->
        val c = vm.tools.speed
        c.meta = SpeedFake.meta
        c.snap = SpeedFake.finished()
        c.measuredAt = 1_792_485_000_000
        c.phase = Phase.Results
    }
    @Test fun pingDark() = shot("73_ping_dark", dark = true, nav = tool(Tool.Ping)) { vm ->
        vm.tools.ping.resolved = "one.one.one.one"
        vm.tools.ping.results.addAll(listOf(13.2f, 12.8f, 15.1f, null, 13.6f))
        vm.tools.ping.phase = Phase.Running
    }

    private fun bt(t: Tool, arg: String? = null) = NavState(Network.Bluetooth, Page.ToolPage(t, arg))

    private fun fakeGatt(vm: MainViewModel) {
        val c = vm.tools.gatt
        c.address = "F4:0E:11:A2:3C:9B"
        fun ch(svc: Int, n: Int, props: Int) = GattCharUi("$svc/$n", GattNames.uuid(n), props)
        c.services.addAll(
            listOf(
                GattServiceUi(GattNames.uuid(0x1800), listOf(ch(0x1800, 0x2A00, 2), ch(0x1800, 0x2A01, 2))),
                GattServiceUi(GattNames.uuid(0x180F), listOf(ch(0x180F, 0x2A19, 2 or 16))),
                GattServiceUi(GattNames.uuid(0x180D), listOf(ch(0x180D, 0x2A37, 16), ch(0x180D, 0x2A38, 2))),
                GattServiceUi(GattNames.uuid(0x180A), listOf(ch(0x180A, 0x2A29, 2), ch(0x180A, 0x2A26, 2))),
            ),
        )
        c.values["${0x180F}/${0x2A19}"] = byteArrayOf(80)
        c.values["${0x180D}/${0x2A37}"] = byteArrayOf(0, 72)
        c.values["${0x180D}/${0x2A38}"] = byteArrayOf(2)
        c.notifying["${0x180D}/${0x2A37}"] = true
        c.expanded[GattNames.uuid(0x180F)] = true
        c.expanded[GattNames.uuid(0x180D)] = true
        c.log.add(NotifyLine(1_700_000_000_000, GattNames.uuid(0x2A37), byteArrayOf(0, 72)))
        c.log.add(NotifyLine(1_700_000_000_000, GattNames.uuid(0x2A37), byteArrayOf(0, 71)))
        c.mtu = 247; c.phy = 2; c.elapsedS = 1.8f
        c.conn = GattConn.Connected
        c.phase = Phase.Results
    }

    @Test fun gattIdle() = shot("80_gatt_idle", nav = bt(Tool.Gatt, "F4:0E:11:A2:3C:9B")) { it.tools.gatt.address = "F4:0E:11:A2:3C:9B" }
    @Test fun gattRunning() = shot("81_gatt_running", nav = bt(Tool.Gatt, "F4:0E:11:A2:3C:9B")) { vm ->
        vm.tools.gatt.address = "F4:0E:11:A2:3C:9B"
        vm.tools.gatt.mtu = 247; vm.tools.gatt.step = 2
        vm.tools.gatt.conn = GattConn.Connecting; vm.tools.gatt.phase = Phase.Running
    }
    @Test fun gattResults() = shot("82_gatt_results", nav = bt(Tool.Gatt, "F4:0E:11:A2:3C:9B"), setup = ::fakeGatt)
    @Test fun gattError() = shot("83_gatt_error", nav = bt(Tool.Gatt, "F4:0E:11:A2:3C:9B")) { vm ->
        vm.tools.gatt.address = "F4:0E:11:A2:3C:9B"
        vm.tools.gatt.error = GattNames.status(133)
        vm.tools.gatt.conn = GattConn.Lost; vm.tools.gatt.phase = Phase.Error
    }
    @Test fun paired() = shot("84_paired", nav = bt(Tool.Paired, "F4:0E:11:A2:3C:9B"))
    @Test fun pairedOff() = shot("85_paired_off", nav = bt(Tool.Paired, "aa"))
    @Test fun trackerIdle() = shot("86_tracker_idle", nav = bt(Tool.Tracker))
    @Test fun trackerRunning() = shot("87_tracker_running", nav = bt(Tool.Tracker)) { vm ->
        val c = vm.tools.tracker
        c.follow(BleDevice("70:99:1C:5B:E2:40", "JBL Flip 6", -61, null, BleKind.Audio, "Harman", true, 0))
        c.history.addAll(listOf(-82f, -80f, -79f, -77f, -76f, -74f, -73f, -72f, -70f, -68f, -66f, -65f, -63f, -62f, -61f))
        c.rssi = -61f; c.trend = 1
    }
    @Test fun trackerHotDark() = shot("88_tracker_found_dark", dark = true, nav = bt(Tool.Tracker)) { vm ->
        val c = vm.tools.tracker
        c.follow(BleDevice("E6:43:9A:0C:71:D8", "Tile Mate", -48, null, BleKind.Tag, "Tile", false, 0))
        c.history.addAll(listOf(-70f, -66f, -62f, -58f, -55f, -52f, -49f, -48f))
        c.rssi = -48f
    }

    private fun cellTool(t: Tool, arg: String? = null) = NavState(Network.Cellular, Page.ToolPage(t, arg))
    private fun gnssTool(t: Tool) = NavState(Network.Gnss, Page.ToolPage(t))

    @Test fun cellDetail() = shot("96_cell_detail", nav = cellTool(Tool.CellDetail, "serving"))
    @Test fun positionCompare() = shot("97_position_compare", nav = gnssTool(Tool.PositionCompare))
    @Test fun positionCompareDark() = shot("98_position_compare_dark", dark = true, nav = gnssTool(Tool.PositionCompare))
    @Test fun sky() = shot("A0_gnss_sky", nav = gnssTool(Tool.Sky))
    @Test fun skyMap() = shot("A1_gnss_sky_map", nav = gnssTool(Tool.Sky)) { it.tools.skyView.value = com.allnetworktools.ui.pages.gnss.SkyView.Map }
    @Test fun skyMapDark() = shot("A2_gnss_sky_map_dark", dark = true, nav = gnssTool(Tool.Sky)) { it.tools.skyView.value = com.allnetworktools.ui.pages.gnss.SkyView.Map }

    @Test fun bleFiltered() = shot("B0_ble_filtered", nav = bt(Tool.BleScan)) { vm ->
        vm.tools.bleFilter.exclude = setOf(com.allnetworktools.data.BleVendor.Apple)
        vm.tools.bleFilter.minRssi = -80
        vm.tools.bleFilter.nameMode = com.allnetworktools.ui.pages.bt.NameMode.Named
    }
    @Test fun bleFilterSheet() = shot("B1_ble_filter_sheet", nav = bt(Tool.BleScan)) { vm ->
        vm.tools.bleFilter.raw = "0xFF4C00"
        vm.tools.bleFilter.include = setOf(com.allnetworktools.data.BleVendor.Google)
        vm.tools.bleFilter.sheetOpen = true
    }
    @Test fun whois() = shot("B4_whois", nav = tool(Tool.Whois)) { vm ->
        val c = vm.tools.whois
        c.query = "google.com"
        c.result = com.allnetworktools.data.net.WhoisResult(
            "google.com", false,
            listOf(
                com.allnetworktools.data.net.WhoisHop("whois.iana.org", "refer: whois.verisign-grs.com"),
                com.allnetworktools.data.net.WhoisHop(
                    "whois.markmonitor.com",
                    "Domain Name: google.com\nRegistrar: MarkMonitor, Inc.\nCreation Date: 1997-09-15T07:00:00+0000\nRegistrar Registration Expiration Date: 2028-09-13T07:00:00+0000\nUpdated Date: 2024-08-02T02:17:33+0000\nRegistrant Organization: Google LLC\nRegistrant Country: US\nName Server: ns1.google.com\nName Server: ns2.google.com\nDomain Status: clientUpdateProhibited (https://www.icann.org/epp#clientUpdateProhibited)\nDNSSEC: unsigned",
                ),
            ),
        )
        c.phase = com.allnetworktools.ui.tools.Phase.Results
    }

    @Test fun bleIdentifiedUnnamed() = shot("B7_ble_identity_airpods", nav = bt(Tool.Gatt, "D3:11:6C:0A:52:9E"))
    @Test fun bleIdentifiedUnknown() = shot("B8_ble_identity_unknown", nav = bt(Tool.Gatt, "7E:03:5B:99:AC:40"))
    @Test fun bleIdentifyRunning() = shot("B9_ble_identify_running", nav = bt(Tool.BleScan)) { vm ->
        val c = vm.tools.bleIdentify
        c.running = true; c.total = 6; c.done = 2; c.current = "Appareil inconnu"
    }

    @Test fun wifiRoaming() = shot("C0_wifi_roaming", nav = NavState(Network.Wifi, Page.Dashboard)) { vm ->
        val now = System.currentTimeMillis()
        vm.setRoamsForTest(
            listOf(
                com.allnetworktools.RoamEvent(now - 40_000, "Freebox-7A2C", "a4:3e:51:7c:2a:9f", "a4:3e:51:7c:30:11", 5180, 5500, -71, -52, 1_820_000),
                com.allnetworktools.RoamEvent(now - 1_900_000, "Freebox-7A2C", "a4:3e:51:7c:30:11", "a4:3e:51:7c:2a:9e", 2437, 5180, -78, -55, 640_000),
            ),
        )
    }

    @Test fun btTools() = shot("D0_bt_tools", nav = NavState(Network.Bluetooth, Page.Tools))
    @Test fun cellTools() = shot("D1_cell_tools", nav = NavState(Network.Cellular, Page.Tools))
    @Test fun evilTwin() {
        Scenario.evilTwin = true
        shot("D2_evil_twin", nav = tool(Tool.EvilTwin))
    }
    @Test fun evilTwinClean() = shot("D3_evil_twin_clean", dark = true, nav = tool(Tool.EvilTwin))

    private fun tag(address: String, hex: String, minutesAgo: Int, path: List<Pair<Double, Double>>, rssi: Int): com.allnetworktools.ui.pages.bt.TrackerCandidate {
        val ads = com.allnetworktools.data.Ad.parseHex(hex)
        val sig = com.allnetworktools.data.TrackerDetect.classify(ads)!!
        val now = System.currentTimeMillis()
        return com.allnetworktools.ui.pages.bt.TrackerCandidate(address, sig).apply {
            device = BleDevice.from(address, null, rssi, null, false, now, ads)
            path.forEachIndexed { i, (la, lo) ->
                sightings += com.allnetworktools.data.Sighting(now - minutesAgo * 60_000L + i * (minutesAgo * 60_000L / path.size.coerceAtLeast(1)), la, lo, rssi)
            }
            assessment = com.allnetworktools.data.TrackerDetect.assess(sig, sightings)
        }
    }

    @Test fun unknownTrackers() = shot("D4_unknown_trackers", nav = bt(Tool.UnknownTrackers)) { vm ->
        val walk = (0 until 12).map { 48.8566 + it * 0.0017 to 2.3522 + it * 0.0009 }
        vm.tools.unknownTrackers.setForTest(
            listOf(
                tag("F2:6B:91:0C:3A:58", "1EFF4C00121910" + "5A".repeat(22) + "0201", 38, walk, -61),
                tag("C4:07:3B:E2:19:A0", "02010403025AFD17165AFD12C24A037F21348D05197CC6BE000000ED90DFA7", 7, walk.take(3), -79),
                tag("E8:12:77:40:BC:03", "07FF4C0012022400", 21, walk.take(2), -70),
                tag("D1:5E:02:9A:44:7C", "07FF4C0012020000", 3, walk.take(1), -88),
                tag("CF:33:10:8B:21:E6", "0303EDFE", 2, walk.take(1), -93),
            ),
            System.currentTimeMillis() - 41 * 60_000L, 2380.0,
        )
    }
    @Test fun trackerDetail() = shot("D4b_tracker_detail", nav = NavState(Network.Bluetooth, Page.ToolPage(Tool.TrackerDetail, "F2:6B:91:0C:3A:58"))) { vm ->
        val walk = (0 until 12).map { 48.8566 + it * 0.0017 to 2.3522 + it * 0.0009 }
        vm.tools.unknownTrackers.setForTest(
            listOf(tag("F2:6B:91:0C:3A:58", "1EFF4C00121910" + "5A".repeat(22) + "0201", 38, walk, -61)),
            System.currentTimeMillis() - 41 * 60_000L, 2380.0,
        )
    }
    /** Every tag drawing, large on light and dark tiles and at list size, to check them by eye. */
    @Test fun trackerArtGallery() {
        compose.setContent {
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.background(androidx.compose.ui.graphics.Color.White)) {
                listOf(androidx.compose.ui.graphics.Color(0xFFDAE2FF), androidx.compose.ui.graphics.Color(0xFFB3261E), androidx.compose.ui.graphics.Color(0xFF1B1B1F)).forEach { bg ->
                    androidx.compose.foundation.layout.Row {
                        com.allnetworktools.data.TrackerKind.entries.forEach { k ->
                            com.allnetworktools.ui.pages.bt.TrackerBadge(k, bg, 68.dp, androidx.compose.foundation.shape.RoundedCornerShape(20.dp), 56.dp)
                        }
                    }
                }
                com.allnetworktools.ui.pages.bt.TrackerArt(com.allnetworktools.data.TrackerKind.AirTag, androidx.compose.ui.Modifier.size(300.dp))
                androidx.compose.foundation.layout.Row {
                    com.allnetworktools.data.TrackerKind.entries.forEach { k ->
                        com.allnetworktools.ui.pages.bt.TrackerBadge(k, androidx.compose.ui.graphics.Color(0xFFDAE2FF), 44.dp, androidx.compose.foundation.shape.RoundedCornerShape(14.dp), 36.dp)
                    }
                }
            }
        }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
        File(dir, "D6_tracker_art.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun unknownTrackersEmpty() = shot("D5_unknown_trackers_empty", dark = true, nav = bt(Tool.UnknownTrackers)) { vm ->
        Scenario.bleEmpty = true
        vm.tools.unknownTrackers.reset()
    }
    @Test fun audit() = shot("E0_wifi_audit", nav = tool(Tool.Audit)) { vm ->
        val conn = com.allnetworktools.data.WifiConnection("Freebox-7A2C", "a4:3e:51:7c:2a:9f", -54, 5180, null, null, 6, "WPA3-Personnel (SAE)", "192.168.1.42", 24, "192.168.1.254", emptyList(), null, null)
        val link = com.allnetworktools.data.LinkSnapshot(true, "192.168.1.254", emptyList(), listOf("192.168.1.254"), "192.168.1.254", false, null, true, false)
        vm.tools.audit.setForTest(com.allnetworktools.data.NetworkAudit.checks(conn, "[RSN-SAE+PSK-CCMP][MFPC][WPS][ESS]", link, setOf(80, 443)), "Freebox-7A2C")
    }
    @Test fun mitm() = shot("E2_mitm", nav = tool(Tool.Mitm)) { vm ->
        val now = System.currentTimeMillis()
        vm.tools.mitm.setForTest(
            com.allnetworktools.data.GatewaySnapshot(now, "192.168.1.254", "192.168.1.254", listOf("192.168.1.254"), listOf("fe80::8e97:eaff:fe12:3456"), "192.168.1.37", "a4:3e:51:7c:2a:9f"),
            listOf(com.allnetworktools.data.MitmEvent(now - 95_000, com.allnetworktools.data.CheckLevel.Bad, "Le premier routeur a changé : 192.168.1.254 → 192.168.1.37")),
            23, now - 6 * 60_000L,
        )
    }


    @Test fun spectrum() = shot("H5_spectrum", dark = true, nav = NavState(Network.Sdr, Page.ToolPage(Tool.Spectrum))) { vm ->
        // FM band: a few stations over a −95 dBFS floor.
        val stations = mapOf(120 to -38f, 260 to -52f, 410 to -30f, 590 to -61f, 700 to -44f, 905 to -49f)
        val db = FloatArray(1024) { i ->
            var v = -95f + ((i * 37) % 7) * 0.8f
            stations.forEach { (k, p) -> val d = (i - k) / 6f; v = maxOf(v, p - 12 * d * d) }
            v
        }
        vm.tools.spectrum.setForTest(db, 98_000_000L)
    }
    @Test fun adsb() = shot("H6_adsb", nav = NavState(Network.Sdr, Page.ToolPage(Tool.Adsb))) { vm ->
        vm.tools.adsb.setForTest(
            listOf(
                com.allnetworktools.ui.pages.sdr.Plane(0x3944EF, "3944EF", "AFR1234", 36000, 452.0, 128.0, 0, 48.95, 2.70, 0, 412, -18.0),
                com.allnetworktools.ui.pages.sdr.Plane(0x4CA2B1, "4CA2B1", "RYR8KZ", 11250, 296.0, 265.0, -1408, 48.71, 2.12, 1, 233, -24.0),
                com.allnetworktools.ui.pages.sdr.Plane(0x3C6586, "3C6586", "DLH4AX", 38000, 471.0, 52.0, 64, 49.30, 2.41, 2, 168, -29.0),
                com.allnetworktools.ui.pages.sdr.Plane(0x39CE80, "39CE80", null, 4500, null, null, null, null, null, 6, 12, -35.0),
            ),
            rate = 87,
        )
    }
    @Test fun sonde() = shot("H7_sonde", nav = NavState(Network.Sdr, Page.ToolPage(Tool.Sonde))) { vm ->
        val track = (0 until 40).map { 48.77 + it * 0.004 to 2.01 + it * 0.009 + (it % 7) * 0.001 }
        vm.tools.sonde.setForTest(
            listOf(
                com.allnetworktools.ui.pages.sdr.SondeInfo("V3420117", "RS41", track.last().first, track.last().second, 18_432.0, 18_432.0, 5.2, 14.0, 62.0, 2.9, 9, 3051, 3012, 0, track),
                com.allnetworktools.ui.pages.sdr.SondeInfo("805-2-31234", "M10", 49.21, 1.62, 6_210.0, 33_870.0, -12.4, 9.0, 80.0, 2.6, 8, 7120, 640, 4, listOf(49.30 to 1.40, 49.25 to 1.50, 49.21 to 1.62), com.allnetworktools.data.sdr.Landing(49.17, 1.71, 640.0)),
            ),
            403_000_000L,
        )
    }
    @Test fun fm() = shot("H8_fm", nav = NavState(Network.Sdr, Page.ToolPage(Tool.Fm))) { vm ->
        vm.tools.fm.setForTest(
            com.allnetworktools.data.sdr.RdsInfo(0xF201, "FRANCE I", "En ce moment : le journal de 18 h, avec toute l'actualité", 1, tp = true, ta = false, groups = 212),
            104.3, -27.0,
        )
    }
    @Test fun ais() = shot("H9_ais", nav = NavState(Network.Sdr, Page.ToolPage(Tool.Ais))) { vm ->
        vm.tools.ais.setForTest(
            listOf(
                com.allnetworktools.ui.pages.sdr.Ship(227123456, "BRETAGNE III", "FABC", 60, "BREST", 0, 48.38, -4.49, 12.3, 87.5, 90, 1, 214, false, false),
                com.allnetworktools.ui.pages.sdr.Ship(244660001, "ROTTERDAM", "PD1234", 70, "BREST", 0, 48.31, -4.62, 9.8, 255.0, 254, 3, 96, false, false),
                com.allnetworktools.ui.pages.sdr.Ship(227998877, "LA MOUETTE", null, 37, null, 5, 48.36, -4.52, 0.0, null, null, 20, 31, false, false),
                com.allnetworktools.ui.pages.sdr.Ship(992271001, "BOUEE ROCHE", null, null, null, null, 48.34, -4.58, null, null, null, 45, 12, true, false),
                com.allnetworktools.ui.pages.sdr.Ship(227555123, null, null, null, null, null, null, null, null, null, null, 6, 3, false, false),
            ),
            total = 356,
        )
    }
    @Test fun aprs() = shot("H11_aprs", nav = NavState(Network.Sdr, Page.ToolPage(Tool.Aprs))) { vm ->
        vm.tools.aprs.setForTest(
            listOf(
                com.allnetworktools.ui.pages.sdr.AprsInfo("F4ABC-9", "F4ABC-9", false, 48.86, 2.35, 57.0, 251, 120.0, "/>", "En route vers le sud", null, null, "WIDE1-1", 14, 0, 12),
                com.allnetworktools.ui.pages.sdr.AprsInfo("F5XYZ-13", "F5XYZ-13", false, 48.79, 2.12, null, null, null, "/_", null, null,
                    com.allnetworktools.data.sdr.AprsWeather(220, 4, 5, 68, 52, 1013.2), "F1DIG", 9, 0, 140),
                com.allnetworktools.ui.pages.sdr.AprsInfo("F1DIG", "F1DIG", false, 48.95, 2.28, null, null, null, "/#", "Digipeater Île-de-France", null, null, null, 31, 2, 35),
                com.allnetworktools.ui.pages.sdr.AprsInfo("Object:BALISE", "BALISE", true, 48.70, 2.50, null, null, null, "/-", "Relais de rassemblement", null, null, "F1DIG", 3, 0, 300),
                com.allnetworktools.ui.pages.sdr.AprsInfo("F6QRP", "F6QRP", false, null, null, null, null, null, null, null, "QRV 144,800", null, null, 2, 0, 410),
            ),
            total = 59,
        )
    }
    @Test fun meteor() = shot("H13_meteor", nav = NavState(Network.Sdr, Page.ToolPage(Tool.Meteor))) { vm ->
        // A made-up picture of clouds over a coast: a few strips of smooth noise.
        fun px(x: Int, y: Int): Int {
            val cloud = 0.5 + 0.25 * Math.sin(x / 38.0 + Math.sin(y / 27.0) * 1.6) + 0.18 * Math.sin(y / 15.0 - x / 71.0) + 0.1 * Math.sin((x + y) / 9.0)
            val coast = if (x < 250 + 70 * Math.sin(y / 60.0)) 0.18 else 0.0
            return ((cloud * 0.8 + coast) * 255).toInt().coerceIn(0, 255)
        }
        val strips = (0 until 70).map { s -> ByteArray(784 * 4) { i -> px(i % 784, s * 4 + i / 784).toByte() } }
        val now = System.currentTimeMillis()
        val m3 = com.allnetworktools.data.orbit.MeteorSats.all[0]
        val m4 = com.allnetworktools.data.orbit.MeteorSats.all[1]
        vm.tools.meteor.setForTest(
            com.allnetworktools.data.sdr.LrptStatus(
                carrier = true, snrDb = 9.4, cfoHz = 2310.0, locked = true, hypothesis = "180°", frames = 214, rsOk = 211, rsFail = 3, corrected = 1840,
                basis = "base duale", zoneStart = 10, packets = 2990, segmentsOk = 2954, segmentsBad = 0, layout = 12,
                apids = mapOf(64 to 996, 65 to 996, 66 to 998), strips = mapOf(64 to 70, 65 to 70, 66 to 70),
            ),
            137_900_000L,
            listOf(
                com.allnetworktools.data.orbit.MeteorPass(m3, now - 4 * 60_000L, now + 8 * 60_000L, now + 2 * 60_000L, 63.0, 12.0, 188.0),
                com.allnetworktools.data.orbit.MeteorPass(m4, now + 71 * 60_000L, now + 83 * 60_000L, now + 77 * 60_000L, 31.0, 340.0, 205.0),
                com.allnetworktools.data.orbit.MeteorPass(m3, now + 4 * 3_600_000L, now + 4 * 3_600_000L + 11 * 60_000L, now + 4 * 3_600_000L + 5 * 60_000L, 19.0, 20.0, 160.0),
            ),
            mapOf(64 to strips, 65 to strips.map { s -> ByteArray(s.size) { (255 - (s[it].toInt() and 0xFF)).toByte() } }),
        )
    }
    @Test fun sdrTools() = shot("H14_sdr_tools", nav = NavState(Network.Sdr, Page.Tools))
    @Test fun sdrDashboard() = shot("H0_sdr_dashboard", nav = NavState(Network.Sdr, Page.Dashboard))
    @Test fun sdrMissing() = shot("H3_sdr_missing", nav = NavState(Network.Sdr, Page.Dashboard)) { Scenario.sdrDevice.value = null }
    @Test fun homeWithSdr() = shot("H4_home_sdr_missing") { Scenario.sdrDevice.value = null }
    @Test fun wifiDirect() = shot("G6_wifi_direct", nav = NavState(Network.Wifi, Page.ToolPage(Tool.WifiDirect)))

    private fun fpvDemo(vm: MainViewModel, watching: Boolean = false, tab: com.allnetworktools.ui.pages.sdr.FpvTab = com.allnetworktools.ui.pages.sdr.FpvTab.Analog) {
        val ch = com.allnetworktools.data.sdr.FpvChannels
        val now = System.currentTimeMillis()
        val img = if (watching) {
            val w = com.allnetworktools.ui.pages.sdr.FpvController.PIC_W; val h = com.allnetworktools.ui.pages.sdr.FpvController.PIC_H
            val px = IntArray(w * h) { i -> val x = i % w; val y = i / w; val g = (255 * (0.15 + 0.7 * x / w) * (if ((y / 72 + x / 52) % 2 == 0) 1.0 else 0.8)).toInt().coerceIn(0, 255); (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
            android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
        } else null
        val levels = List(12) { -52.0 + it * 1.2 }
        vm.tools.fpv.setForTest(
            listOf(
                com.allnetworktools.data.sdr.AnalogDrone(ch.byName("F4")!!, "PAL", 0.97, -38.0, levels, now - 95_000, now - 1_000, 0),
                com.allnetworktools.data.sdr.AnalogDrone(ch.byName("R2")!!, "NTSC", 0.82, -61.0, levels.reversed(), now - 300_000, now - 40_000, 2),
            ),
            running = true, watching = if (watching) ch.byName("F4") else null, image = img,
            dji = com.allnetworktools.data.sdr.DjiDetection(7, true, 2444.5, 22.0, listOf(14.0, 16.0, 19.0, 22.0), listOf(2414.5, 2429.5, 2444.5), now - 60_000, now - 2_000),
            rc = listOf(
                com.allnetworktools.data.sdr.RcLink(
                    "24-1113-0", "ExpressLRS 2,4 GHz", "250 Hz", 24, com.allnetworktools.data.sdr.RcModulation.Chirp,
                    250.0, 890.0, 1113.0, 18, 31.0, listOf(22.0, 24.0, 27.0, 29.0, 31.0), 412, now - 80_000, now - 1_000,
                    listOf("Modulation LoRa (chirps)", "Largeur 890 kHz", "Cadence 250 paquets/s, 97 % des écarts conformes", "Paquet de 1113 µs, dans son créneau", "Sauts sur 18 fréquences", "Grille de canaux de 1000 kHz"),
                ),
                com.allnetworktools.data.sdr.RcLink(
                    "868-1203-0", "TBS Crossfire", "150 Hz (FSK)", 868, com.allnetworktools.data.sdr.RcModulation.Fsk,
                    150.0, 312.0, 1203.0, 9, 18.0, listOf(20.0, 19.0, 18.0, 18.0), 96, now - 40_000, now - 6_000,
                    listOf("Modulation FSK / FLRC", "Largeur 312 kHz", "Cadence 150 paquets/s, 100 % des écarts conformes", "Paquet de 1203 µs, dans son créneau", "Sauts sur 9 fréquences"),
                ),
            ),
        )
        vm.tools.fpv.tab = tab
    }
    @Test fun fpvAnalog() = shot("H18_fpv_analog", nav = NavState(Network.Sdr, Page.ToolPage(Tool.Fpv))) { fpvDemo(it) }
    @Test fun fpvVideo() = shot("H19_fpv_video", nav = NavState(Network.Sdr, Page.ToolPage(Tool.Fpv))) { fpvDemo(it, watching = true) }
    @Test fun fpvRc() = shot("H21_fpv_rc", nav = NavState(Network.Sdr, Page.ToolPage(Tool.Fpv))) { fpvDemo(it, tab = com.allnetworktools.ui.pages.sdr.FpvTab.Rc) }
    @Test fun fpvDji() = shot("H20_fpv_dji", dark = true, nav = NavState(Network.Sdr, Page.ToolPage(Tool.Fpv))) { fpvDemo(it, tab = com.allnetworktools.ui.pages.sdr.FpvTab.Dji) }
    @Test fun updateDialog() = shot("Z9_update_dialog") { vm ->
        vm.updater.offerForTest(
            com.allnetworktools.update.UpdateInfo(
                "0.9", "v0.9",
                "- Nouvel outil « Drones FPV » dans l'onglet SDR\n- Meshtastic : correction de la réception des vrais nœuds\n- Le raccourci principal du dock devient le spectre",
                "https://github.com/ToftMalone/All-Radio-Tools/releases/download/v0.9/AllRadioTools-v0.9.apk", 4_590_146, null,
            ),
        )
    }

    private fun talkieChannels(slots: Int = 8): List<com.allnetworktools.data.radio.RadioChannel> {
        val ctcss = com.allnetworktools.data.radio.Tone.Ctcss(88.5)
        return listOf(
            com.allnetworktools.data.radio.RadioChannel(0, 145_500_000, 145_500_000, "APPEL2M", wide = false),
            com.allnetworktools.data.radio.RadioChannel(1, 145_600_000, 145_000_000, "RV48", txTone = ctcss, wide = false),
            com.allnetworktools.data.radio.RadioChannel(2, 446_006_250, null, "PMR 1", wide = false),
            com.allnetworktools.data.radio.RadioChannel(3, 156_800_000, null, "MAR 16"),
            com.allnetworktools.data.radio.RadioChannel(4, 144_800_000, null, "APRS", wide = false),
            com.allnetworktools.data.radio.RadioChannel(5, 433_500_000, 433_500_000, "APPEL70", rxTone = com.allnetworktools.data.radio.Tone.Dcs(23, true), power = com.allnetworktools.data.radio.RadioPower.Low),
        ).take(slots)
    }
    @Test fun talkieDashboard() = shot("T1_talkie_dashboard", nav = NavState(Network.Talkie, Page.Dashboard))
    @Test fun talkieChannelsList() = shot("T2_talkie_channels", nav = NavState(Network.Talkie, Page.ToolPage(Tool.RadioChannels))) { vm ->
        vm.tools.radioChannels.setForTest(
            talkieChannels(),
            com.allnetworktools.ui.pages.talkie.ProgMessage("Baofeng UV-5R reconnu : 6 canaux lus.", com.allnetworktools.ui.pages.talkie.ProgMessage.Kind.Success),
            com.allnetworktools.data.radio.Uv5rSpec(),
        )
    }
    @Test fun talkieChannelsNoCable() = shot("T3_talkie_channels_empty", dark = true, nav = NavState(Network.Talkie, Page.ToolPage(Tool.RadioChannels))) { Scenario.cable.value = null }
    @Test fun talkieTools() = shot("T4_talkie_tools", nav = NavState(Network.Talkie, Page.Tools))
}
