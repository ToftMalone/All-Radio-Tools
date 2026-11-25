package com.allnetworktools

import com.allnetworktools.ui.components.FreshItems
import com.allnetworktools.ui.components.MarkerGlide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionHelpersTest {
    private val ms = 1_000_000L

    @Test fun markersGlideToTheirNewFix() {
        val g = MarkerGlide<String>()
        g.begin(); val a = g.at("AFR1", 48.0, 2.0, 0); g.end()
        assertEquals(48.0, a.latitude, 1e-9)
        assertFalse(g.moving)
        // A new fix: on its way at first, there after the glide.
        g.begin(); val mid = g.at("AFR1", 48.1, 2.1, 100 * ms); g.end()
        assertEquals(48.0, mid.latitude, 1e-9)
        assertTrue(g.moving)
        g.begin(); val half = g.at("AFR1", 48.1, 2.1, 550 * ms); g.end()
        assertTrue(half.latitude > 48.05 && half.latitude < 48.1)
        g.begin(); val end = g.at("AFR1", 48.1, 2.1, 1_100 * ms); g.end()
        assertEquals(48.1, end.latitude, 1e-9)
        assertFalse(g.moving)
        // A jump of a degree (a wrong fix, a new start) snaps.
        g.begin(); val far = g.at("AFR1", 49.5, 2.1, 1_200 * ms); g.end()
        assertEquals(49.5, far.latitude, 1e-9)
    }

    @Test fun aRowIsNewUntilSeen() {
        FreshItems.clearForTest()
        assertFalse(FreshItems.isSeen("adsb", "3C6586"))
        FreshItems.see("adsb", "3C6586")
        assertTrue(FreshItems.isSeen("adsb", "3C6586"))
        // Lists are apart: the same id in another list is still new there.
        assertFalse(FreshItems.isSeen("ais", "3C6586"))
    }
}
