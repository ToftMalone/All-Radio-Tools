package com.allnetworktools

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = FakeApp::class)
class ListenDragTest {
    /** Issue #28: a drag held at an edge of the spectrum moves a quarter of the view every 0.7 s, not at every move. */
    @Test fun dragAtTheEdgeStepsInsteadOfRunningAway() {
        val c = com.allnetworktools.ui.pages.sdr.ListenController(com.allnetworktools.data.sdr.SdrRepository(org.robolectric.RuntimeEnvironment.getApplication()), kotlinx.coroutines.MainScope())
        c.tune(100_000_000L)
        c.markRunningForTest()
        c.tune(105_000_000L)
        val span = c.viewSpanHz
        val centre = c.centerHz
        val mid = (c.centerHz + c.viewOffsetHz()).toDouble()
        val step = c.stepHz.toDouble()
        c.startDrag()
        // Inside the view: the channel follows the finger, the radio and the view stay.
        c.dragFraction(0.75f, 0)
        assertEquals(mid + span / 4, c.frequencyHz.toDouble(), step)
        assertEquals(centre, c.centerHz)
        // Near the edge but inside: held where the radio need not move.
        c.dragFraction(0.95f, 10)
        assertEquals(centre, c.centerHz)
        // Held at the right edge, moving a little 15 times over 1.4 s: three quarter-view steps, not fifteen.
        val f0 = c.frequencyHz.toDouble()
        for (k in 0..14) c.dragFraction(0.99f, 1_000L + k * 100L)
        c.endDrag()
        assertEquals(f0 + 3 * span / 4, c.frequencyHz.toDouble(), step)
    }
}
