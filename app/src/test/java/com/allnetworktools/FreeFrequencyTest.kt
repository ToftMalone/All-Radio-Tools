package com.allnetworktools

import com.allnetworktools.model.Tool
import com.allnetworktools.ui.pages.sdr.FreeFrequency
import com.allnetworktools.ui.pages.sdr.SdrHandoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #21: a tool's channel moved by the user, or by the frequency the spectrum's analysis found. */
class FreeFrequencyTest {
    @Test fun theWholeTuningMovesByTheShift() {
        val f = FreeFrequency(161_975_000L)
        assertEquals(0L, f.shiftHz)
        f.set(156_800_000L)
        assertEquals(156_800_000L - 161_975_000L, f.shiftHz)
        assertEquals(156_800_000L, f.effectiveHz)
        f.text = "abc"
        assertFalse(f.valid)
        assertEquals(0L, f.shiftHz)
        f.reset()
        assertTrue(f.valid)
        assertEquals(0L, f.shiftHz)
    }

    @Test fun aSignalTheToolAlreadyHearsKeepsItsChannels() {
        val f = FreeFrequency(868_950_000L)
        f.offer(868_955_000L, covered = SdrHandoff.near(868_955_000L, 60_000, 868_950_000))
        assertFalse(f.on)
        f.offer(869_525_000L, covered = SdrHandoff.near(869_525_000L, 60_000, 868_950_000))
        assertTrue(f.on)
        assertEquals(869_525_000L, f.hz())
    }

    @Test fun theHandoffGoesToItsToolOnce() {
        SdrHandoff.offer(Tool.Ais, 162_000_000L)
        assertNull(SdrHandoff.take(Tool.Dsc))
        assertEquals(162_000_000L, SdrHandoff.take(Tool.Ais))
        assertNull(SdrHandoff.take(Tool.Ais))
    }

    @Test fun mhzLabels() {
        assertEquals("868.95", FreeFrequency.mhz(868_950_000L))
        assertEquals("162.0", FreeFrequency.mhz(162_000_000L))
        assertEquals("446.0063", FreeFrequency.mhz(446_006_250L))
    }
}
