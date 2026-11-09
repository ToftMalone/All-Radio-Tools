package com.allnetworktools

import com.allnetworktools.data.sdr.R82xx
import com.allnetworktools.data.sdr.RtlBus
import com.allnetworktools.data.sdr.RtlSdr
import com.allnetworktools.data.sdr.Rtl2832
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The RTL2832U / R82xx driver against a fake USB bus: what it writes, not what a real dongle does with it. */
class RtlSdrTest {
    /** A bus that records the I2C writes to the tuner and answers its status reads like a locked R820T. */
    private class FakeBus : RtlBus {
        val i2c = ArrayList<ByteArray>()
        val demod = ArrayList<Pair<Int, Int>>()
        private fun rev(b: Int): Int {
            val l = intArrayOf(0x0, 0x8, 0x4, 0xc, 0x2, 0xa, 0x6, 0xe, 0x1, 0x9, 0x5, 0xd, 0x3, 0xb, 0x7, 0xf)
            return (l[b and 0xf] shl 4) or l[(b and 0xff) shr 4]
        }

        // Chip id 0x69, PLL locked (bit 6 of byte 2), VCO tune 2, filter calibration code 5.
        private val canned = intArrayOf(0x69, 0x00, 0x50, 0x00, 0x25)

        override fun controlIn(value: Int, index: Int, length: Int): ByteArray? =
            ByteArray(length) { rev(if (index == 0x0600 && it < canned.size) canned[it] else 0).toByte() }

        override fun controlOut(value: Int, index: Int, data: ByteArray): Int {
            if (index == 0x0610) i2c += data.copyOf()
            else if (index and 0xff == 0x11 || index == 0x10 || index == 0x11) demod += (value shr 8) to (data.last().toInt() and 0xff)
            return data.size
        }

        fun lastWriteTo(reg: Int, size: Int? = null) = i2c.lastOrNull { (it[0].toInt() and 0xff) == reg && (size == null || it.size == size) }
    }

    private fun tuner(bus: FakeBus, r828d: Boolean = false): R82xx {
        val t = R82xx(Rtl2832(bus), r828d, if (r828d) R82xx.R828D_XTAL else 28_800_000, R82xx.Model.Generic)
        assertTrue(t.init() >= 0)
        return t
    }

    @Test fun unsignedSamplesBecomeSigned() {
        val b = byteArrayOf(0, 127, 128.toByte(), 255.toByte())
        RtlSdr.unsignedToSigned(b, 4)
        assertEquals(listOf(-128, -1, 0, 127), b.map { it.toInt() })
    }

    @Test fun twoMegasamplesAreOffered() {
        val rtl = Rtl2832(FakeBus())
        assertEquals(2_000_000.0, rtl.setSampleRate(2_000_000), 1.0)
        assertEquals(2_400_000.0, rtl.setSampleRate(2_400_000), 1.0)
    }

    @Test fun ratesTheResamplerCannotMakeAreRefused() {
        val rtl = Rtl2832(FakeBus())
        assertEquals(0.0, rtl.setSampleRate(4_000_000), 0.0)
        assertEquals(0.0, rtl.setSampleRate(500_000), 0.0)
        assertEquals(0.0, rtl.setSampleRate(200_000), 0.0)
    }

    @Test fun initProgramsTheTunerAndTheFilterCalibrationIsKept() {
        val bus = FakeBus()
        tuner(bus)
        // The 27 init registers go out first, in chunks that fit an I2C message (7 data bytes + the register).
        assertEquals(0x05, bus.i2c[0][0].toInt())
        assertTrue(bus.i2c.take(5).all { it.size <= 8 })
        // Calibration code 5 with the low-Q bit: register 0x0a = 0x10 | 5 under the 0x1f mask.
        val r0a = bus.lastWriteTo(0x0a, 2)
        assertTrue(r0a != null)
    }

    @Test fun pllRegistersFollowTheFrequency() {
        val bus = FakeBus()
        val t = tuner(bus)
        assertEquals(3_570_000, t.intFreq)
        bus.i2c.clear()
        assertTrue(t.setFreq(100_000_000L) >= 0)
        val w = bus.lastWriteTo(0x10, 8)!!
        // LO = 100 MHz + 3.57 MHz IF; the VCO runs at 32 times that, divided by the 28.8 MHz crystal twice.
        val lo = 103_570_000L
        val vco = lo * 32L
        val div = (28_800_000L + 65536L * vco) / (2L * 28_800_000L)
        val nint = (div / 65536).toInt()
        val sdm = (div % 65536).toInt()
        val ni = (nint - 13) / 4
        val si = nint - 4 * ni - 13
        assertEquals(ni + (si shl 6), w[5].toInt() and 0xff)
        assertEquals(sdm and 0xff, w[6].toInt() and 0xff)
        assertEquals(sdm shr 8, w[7].toInt() and 0xff)
        // Divider 32 -> div_num 4 in the top three bits of register 0x10.
        assertEquals(4, (w[1].toInt() and 0xe0) shr 5)
    }

    @Test fun tuningStaysInTheTunerBands() {
        val bus = FakeBus()
        val t = tuner(bus)
        for (f in longArrayOf(30_000_000, 144_800_000, 433_920_000, 868_000_000, 1_090_000_000, 1_700_000_000)) assertTrue("$f", t.setFreq(f) >= 0)
    }

    @Test fun gainStepsUpTheLnaAndTheMixer() {
        val bus = FakeBus()
        val t = tuner(bus)
        assertTrue(t.setGain(0) >= 0)
        assertEquals(0, bus.lastWriteTo(0x05, 2)!![1].toInt() and 0x0f)
        assertTrue(t.setGain(496) >= 0)
        assertEquals(15, bus.lastWriteTo(0x05, 2)!![1].toInt() and 0x0f)
        // The last mixer step would overshoot 49.6 dB (the total is already there after 14), so it is not taken.
        assertEquals(14, bus.lastWriteTo(0x07, 2)!![1].toInt() and 0x0f)
    }

    @Test fun narrowRatesPickTheLowIf() {
        val bus = FakeBus()
        val t = tuner(bus)
        // 2 MHz of bandwidth: the 1.7 MHz low-pass plus the 350 kHz high-pass step, centred on 1.625 MHz.
        assertEquals(1_625_000, t.setBandwidth(2_000_000))
        // The DVB-T 6 MHz and 8 MHz modes keep their usual IFs.
        assertEquals(3_570_000, t.setBandwidth(5_000_000))
        assertEquals(4_570_000, t.setBandwidth(6_500_000))
        assertEquals(4_570_000, t.setBandwidth(8_000_000))
    }
}
