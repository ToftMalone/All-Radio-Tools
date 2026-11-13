package com.allnetworktools

import com.allnetworktools.data.sdr.AmpCheck
import com.allnetworktools.data.sdr.AmpHealth
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmpCheckTest {
    @After fun reset() = AmpHealth.reset()

    /** Runs the check as a tool would: the user wants the amplifier on; a probe turns it off for a moment. */
    private fun run(fe: FakeFrontEnd, seconds: Double): List<AmpCheck.Action> {
        val c = AmpCheck()
        val actions = ArrayList<AmpCheck.Action>()
        var probeOff = false
        var t = 0.0
        while (t < seconds) {
            fe.amp = !probeOff && !AmpHealth.dead
            val a = c.feed(fe.buffer(), 8192, t, fe.amp, fe.lna, fe.vga)
            when (a) {
                AmpCheck.Action.ProbeOff -> probeOff = true
                AmpCheck.Action.Restore -> probeOff = false
                AmpCheck.Action.Dead -> probeOff = false
                AmpCheck.Action.None -> Unit
            }
            if (a != AmpCheck.Action.None) actions += a
            t += 0.05
        }
        return actions
    }

    /** A dead amplifier: almost nothing with it on, the band's noise with it off. Three tries and it is declared dead. */
    @Test fun deadAmplifierIsFound() {
        val fe = FakeFrontEnd(2).apply { ampDead = true; lna = 40; vga = 40; tones += -100.0 to 0.1 }
        val actions = run(fe, 60.0)
        assertTrue(AmpHealth.dead)
        assertEquals(AmpCheck.Action.Dead, actions.last())
        assertEquals(3, actions.count { it == AmpCheck.Action.ProbeOff })
        assertFalse("off once dead", fe.amp)
    }

    /** A working amplifier on a quiet band: the probe finds more level with it, never declares it dead. */
    @Test fun workingAmplifierIsKept() {
        val fe = FakeFrontEnd(3).apply { lna = 8; vga = 8 }
        run(fe, 120.0)
        assertFalse(AmpHealth.dead)
    }

    /** A working amplifier with a good level is left alone: no probe at all. */
    @Test fun noProbeWhenTheLevelIsGood() {
        val fe = FakeFrontEnd(4).apply { lna = 32; vga = 30; tones += -60.0 to 0.1 }
        val actions = run(fe, 60.0)
        assertTrue(actions.isEmpty())
        assertFalse(AmpHealth.dead)
    }
}
