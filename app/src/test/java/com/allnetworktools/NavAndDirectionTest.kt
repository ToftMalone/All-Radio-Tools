package com.allnetworktools

import androidx.test.core.app.ApplicationProvider
import com.allnetworktools.data.DirectionSweep
import com.allnetworktools.model.Network
import com.allnetworktools.model.Tool
import kotlin.math.abs
import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "fr", application = FakeApp::class)
class NavAndDirectionTest {
    @Test fun backReturnsToThePreviousScreen() {
        val vm = MainViewModel(ApplicationProvider.getApplicationContext<FakeApp>())
        vm.navigate { NavState(Network.Bluetooth, Page.Home) }
        vm.navigate { it.copy(page = Page.Tools) }
        vm.navigate { it.copy(page = Page.ToolPage(Tool.UnknownTrackers)) }
        vm.navigate { it.copy(page = Page.ToolPage(Tool.Tracker)) }
        // Chaud/Froid opened from the tracker list: back goes to the list, not to the tools page.
        vm.back(Page.Tools)
        assertEquals(Page.ToolPage(Tool.UnknownTrackers), vm.nav.value.page)
        vm.back(Page.Tools)
        assertEquals(Page.Tools, vm.nav.value.page)
        // Nothing left in the trail: the parent page is used.
        assertEquals(Page.Home, vm.backTarget(Page.Home))
    }

    @Test fun revisitingAPageDoesNotLoop() {
        val vm = MainViewModel(ApplicationProvider.getApplicationContext<FakeApp>())
        vm.navigate { NavState(Network.Wifi, Page.Home) }
        vm.navigate { it.copy(page = Page.Dashboard) }
        vm.navigate { it.copy(page = Page.Tools) }
        vm.navigate { it.copy(page = Page.Dashboard) }
        vm.back(Page.Home)
        assertEquals(Page.Home, vm.nav.value.page)
    }

    @Test fun sweepFindsTheStrongestSide() {
        val s = DirectionSweep()
        assertNull(s.estimate())
        // Device at 100°: body shadow makes the far side 12 dB weaker.
        var h = 0f
        while (h < 360f) {
            val rel = Math.toRadians((h - 100f).toDouble())
            s.add(h, (-70 + 6 * cos(rel)).toFloat())
            h += 7f
        }
        val e = s.estimate()!!
        assertTrue("bearing ${e.bearing}", abs(e.bearing - 100f) < 15f)
        assertEquals("forte", e.confidence)
    }
}
