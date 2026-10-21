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
}
