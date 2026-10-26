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

    /** A tool's own back steps (a followed device, an open video) run before the page goes back, latest first. */
    @Test fun toolBackStepsRunBeforeThePage() {
        val stack = com.allnetworktools.ui.pages.ToolBackStack()
        val ran = mutableListOf<String>()
        assertEquals(false, stack.pop())
        val device: () -> Unit = { ran += "device" }
        val video: () -> Unit = { ran += "video" }
        stack.entries += device
        stack.entries += video
        assertTrue(stack.pop())
        assertEquals(listOf("video"), ran)
        stack.entries.remove(video)
        assertTrue(stack.pop())
        assertEquals(listOf("video", "device"), ran)
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
