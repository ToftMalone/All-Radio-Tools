package com.allnetworktools

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.allnetworktools.model.Network
import com.allnetworktools.model.Tool
import com.allnetworktools.ui.AntApp
import com.allnetworktools.ui.tools.Phase
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A few screens in English (the default language of the resources), to check the translation fits. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-w412dp-h915dp-xxhdpi", application = FakeApp::class)
class ScreenshotEnglishTest {
    @get:Rule
    val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<FakeApp>()

    private fun shot(name: String, nav: NavState = NavState(), setup: (MainViewModel) -> Unit = {}) {
        runBlocking { app.settings.setOnboardingDone(true) }
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
        val dir = File(System.getProperty("screenshots.dir") ?: "build/screenshots", "en").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun home() = shot("02_home")
    @Test fun wifiDashboard() = shot("10_wifi_dashboard", NavState(Network.Wifi, Page.Dashboard))
    @Test fun wifiTools() = shot("11_wifi_tools", NavState(Network.Wifi, Page.Tools))
    @Test fun btDashboard() = shot("20_bt_dashboard", NavState(Network.Bluetooth, Page.Dashboard))
    @Test fun btTools() = shot("21_bt_tools", NavState(Network.Bluetooth, Page.Tools))
    @Test fun bitchatOff() = shot("22_bitchat_off", NavState(Network.Bluetooth, Page.ToolPage(Tool.BitChat)))
    @Test fun bitchatChat() = shot("23_bitchat_chat", NavState(Network.Bluetooth, Page.ToolPage(Tool.BitChat))) { fakeMesh() }
    @Test fun remoteId() = shot("24_remote_id", NavState(Network.Bluetooth, Page.ToolPage(Tool.RemoteId))) { fakeDrones() }
    @Test fun fakeCells() = shot("25_fake_cells", NavState(Network.Cellular, Page.ToolPage(Tool.FakeCells))) {
        val hub = com.allnetworktools.data.CellWatchHub
        hub.setRunning(true)
        val now = System.currentTimeMillis()
        hub.add(com.allnetworktools.data.CellAnomaly(com.allnetworktools.data.CellAnomalyKind.NoNeighbours, now - 600_000, "2G · 208-01 · TAC 2201 · ID 4411", "2G · 208-01 · TAC 2201 · ID 4411"))
        hub.add(com.allnetworktools.data.CellAnomaly(com.allnetworktools.data.CellAnomalyKind.Downgrade2G, now - 60_000, "2G · 208-01 · TAC 2201 · ID 4411", "LTE → 2G"))
    }
    @Test fun trackers() = shot("26_trackers", NavState(Network.Bluetooth, Page.ToolPage(Tool.UnknownTrackers)))
    @Test fun gnssTools() = shot("40_gnss_tools", NavState(Network.Gnss, Page.Tools))
    @Test fun gnssIntegrity() = shot("75_gnss_integrity", NavState(Network.Gnss, Page.ToolPage(Tool.GnssIntegrity)))
    @Test fun gnssClock() = shot("78_gnss_clock", NavState(Network.Gnss, Page.ToolPage(Tool.GnssClock)))
    @Test fun sdrTools() = shot("60_sdr_tools", NavState(Network.Sdr, Page.Tools))
    @Test fun settings() = shot("50_settings", NavState(page = Page.Settings))
    @Test fun speedResults() = shot("72_speed_results", NavState(Network.Wifi, Page.ToolPage(Tool.Speed))) { vm ->
        val c = vm.tools.speed
        c.meta = SpeedFake.meta
        c.snap = SpeedFake.finished()
        c.measuredAt = 1_792_485_000_000
        c.phase = Phase.Results
    }

    private fun fakeMesh() {
        val now = 1_792_485_000_000
        fun m(id: String, from: String, nick: String, text: String, mine: Boolean, dt: Long) =
            com.allnetworktools.data.bitchat.ChatMessage(id, from, nick, text, now + dt, mine)
        com.allnetworktools.data.bitchat.BitchatHub.publish(
            com.allnetworktools.data.bitchat.MeshState(
                running = true, peerId = "a1b2c3d4e5f60708", nickname = "anon4242", links = 2,
                peers = listOf(
                    com.allnetworktools.data.bitchat.MeshPeer("1111222233334444", "marie", true, now, true),
                    com.allnetworktools.data.bitchat.MeshPeer("5555666677778888", "paul", false, now, false),
                ),
                publicChat = listOf(
                    m("1", "1111222233334444", "marie", "Anyone near the station?", false, 0),
                    m("2", "a1b2c3d4e5f60708", "anon4242", "Yes, 200 m away", true, 60_000),
                    m("3", "5555666677778888", "paul", "Relaying from the market", false, 120_000),
                ),
                relayed = 37, received = 120, sent = 15,
            ),
        )
    }

    private fun fakeDrones() {
        val now = System.currentTimeMillis()
        val d = com.allnetworktools.data.remoteid.Drone(
            key = "AA", id = "1581F5FKD229001234", idType = com.allnetworktools.data.remoteid.IdType.Serial,
            uaType = com.allnetworktools.data.remoteid.UaType.Multirotor, status = com.allnetworktools.data.remoteid.Status.Airborne,
            lat = 48.8584, lon = 2.2945, altGeoM = 152.0, heightM = 87.0, speedMs = 8.5, directionDeg = 45,
            operatorLat = 48.8570, operatorLon = 2.2925, operatorId = "FRAoPeRaToR0001", euClass = 1, euCategory = 1,
            rssi = -71, firstSeen = now - 60_000, lastSeen = now - 2_000,
        )
        val old = d.copy(key = "BB", id = "DTG-RX-77812", uaType = com.allnetworktools.data.remoteid.UaType.Aeroplane, lat = null, lon = null, operatorLat = null, lastSeen = now - 300_000, rssi = -88)
        com.allnetworktools.data.remoteid.RemoteIdHub.publish(
            com.allnetworktools.data.remoteid.RemoteIdState(running = true, drones = listOf(d, old), longRange = true),
        )
    }
}
