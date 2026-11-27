package com.allnetworktools

import com.allnetworktools.data.sdr.Fc0012
import com.allnetworktools.data.sdr.Rtl2832
import com.allnetworktools.data.sdr.RtlBus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Fc0012Test {
    /** A stick that keeps the tuner's registers as written over I2C and answers reads from them. */
    private class FakeStick : RtlBus {
        val regs = IntArray(0x20)
        var pointer = 0
        override fun controlIn(value: Int, index: Int, length: Int): ByteArray? =
            if (value == Fc0012.ADDR && index == Rtl2832.IICB shl 8) byteArrayOf(regs[pointer].toByte()) else ByteArray(length)
        override fun controlOut(value: Int, index: Int, data: ByteArray): Int {
            if (value == Fc0012.ADDR && index == (Rtl2832.IICB shl 8) or 0x10) {
                pointer = data[0].toInt() and 0xff
                if (data.size > 1) regs[pointer] = data[1].toInt() and 0xff
            }
            return data.size
        }
    }

    @Test fun pllAt100MHzAsLibrtlsdrComputesIt() {
        val stick = FakeStick()
        val t = Fc0012(Rtl2832(stick), 28_800_000)
        assertEquals(0, t.init())
        // A VCO control voltage in the middle of the range: no switch to the other VCO.
        stick.regs[0x0e] = 0x20
        assertEquals(0, t.setFreq(100_000_000L))
        // Worked by hand from tuner_fc0012.c: ×32 divider, VCO 3.2 GHz (high range), XDIV 222 (PM 27, AM 6), XIN 7281.
        assertEquals(6, stick.regs[1])
        assertEquals(27, stick.regs[2])
        assertEquals(7281 shr 8, stick.regs[3])
        assertEquals(7281 and 0xff, stick.regs[4])
        assertEquals(0x82 or 0x07, stick.regs[5])
        assertEquals(0xaa, stick.regs[6])
        assertTrue(t.locked)
    }

    @Test fun gainStepsAndRange() {
        val stick = FakeStick()
        val t = Fc0012(Rtl2832(stick), 28_800_000)
        t.init()
        t.setGain(200)
        assertEquals(0x10, stick.regs[0x13] and 0x1f)
        t.setGain(-120)
        assertEquals(0x02, stick.regs[0x13] and 0x1f)
        // Every frequency it covers finds a PLL setting.
        stick.regs[0x0e] = 0x20
        var f = Fc0012.MIN_HZ
        while (f <= Fc0012.MAX_HZ) { assertEquals("$f Hz", 0, t.setFreq(f)); f += 7_000_000L }
    }
}
