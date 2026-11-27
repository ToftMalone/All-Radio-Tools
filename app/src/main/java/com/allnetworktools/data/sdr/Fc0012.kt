package com.allnetworktools.data.sdr

/**
 * The Fitipower FC0012 tuner of early RTL-SDR sticks (about 22 to 948 MHz), zero-IF. A port of tuner_fc0012.c from
 * librtlsdr (Steve Markgraf, after the Linux driver by Hans-Frieder Vogt; GPL-2.0-or-later), the same register values
 * and PLL arithmetic. GPIO 6 of the RTL2832U switches its VHF / UHF filter.
 */
class Fc0012(private val rtl: Rtl2832, private val xtal: Int) : RtlTuner {
    private var hasLock = false
    override val locked get() = hasLock
    override val gains get() = GAINS

    private fun write(reg: Int, value: Int): Int = if (rtl.i2cWrite(ADDR, byteArrayOf(reg.toByte(), value.toByte())) < 0) -1 else 0

    private fun read(reg: Int): Int? {
        if (rtl.i2cWrite(ADDR, byteArrayOf(reg.toByte())) < 0) return null
        return rtl.i2cRead(ADDR, 1)?.firstOrNull()?.toInt()?.and(0xff)
    }

    override fun init(): Int {
        val reg = intArrayOf(
            0x00, // dummy register 0
            0x05, 0x10, 0x00, 0x00,
            0x0f, // 0x05
            0x00, // 0x06: divider 2, VCO slow
            0x00 or 0x20, // 0x07: 28.8 MHz crystal
            0xff, // 0x08: AGC clock / 256, AGC gain 1/256, loop bandwidth 1/8
            0x6e, // 0x09: loop-through off
            0xb8, // 0x0a: LO test buffer off
            0x82, // 0x0b
            0xfc or 0x02, // 0x0c: AGC up-down mode, dual master
            0x02, // 0x0d: AGC not forced, LNA forced
            0x00, 0x00, 0x00, 0x00,
            0x1f, // 0x12: maximum gain
            0x08, // 0x13: middle LNA gain
            0x00,
            0x04, // 0x15: LNA compensation on
        )
        for (i in 1 until reg.size) if (write(i, reg[i]) < 0) return -1
        return 0
    }

    /** Zero-IF: the filter stays at 6 MHz (librtlsdr does not change it), and the demodulator's IF is 0. */
    override fun setBandwidth(bwIn: Int): Int = 0

    override fun setFreq(freq: Long): Int {
        // The VHF / UHF input filter.
        rtl.setGpioBit(6, freq > 300_000_000L)
        val r = setParams(freq)
        hasLock = r >= 0
        return r
    }

    private fun setParams(freq: Long): Int {
        val reg = IntArray(7)
        val xtalDiv2 = xtal / 2L
        // The divider that keeps the VCO under 3.56 GHz.
        val (multi, r5, r6) = when {
            freq < 37_084_000L -> Triple(96, 0x82, 0x00)
            freq < 55_625_000L -> Triple(64, 0x02, 0x02)
            freq < 74_167_000L -> Triple(48, 0x42, 0x00)
            freq < 111_250_000L -> Triple(32, 0x82, 0x02)
            freq < 148_334_000L -> Triple(24, 0x22, 0x00)
            freq < 222_500_000L -> Triple(16, 0x42, 0x02)
            freq < 296_667_000L -> Triple(12, 0x12, 0x00)
            freq < 445_000_000L -> Triple(8, 0x22, 0x02)
            freq < 593_334_000L -> Triple(6, 0x0a, 0x00)
            else -> Triple(4, 0x12, 0x02)
        }
        reg[5] = r5; reg[6] = r6
        val fVco = freq * multi
        var vcoSelect = false
        if (fVco >= 3_060_000_000L) { reg[6] = reg[6] or 0x08; vcoSelect = true }
        var xdiv = (fVco / xtalDiv2).toInt()
        if (fVco - xdiv * xtalDiv2 >= xtalDiv2 / 2) xdiv++
        var pm = xdiv / 8
        var am = xdiv - 8 * pm
        if (am < 2) { am += 8; pm-- }
        if (pm > 31) { reg[1] = am + 8 * (pm - 31); reg[2] = 31 } else { reg[1] = am; reg[2] = pm }
        if (reg[1] > 15 || reg[2] < 0x0b) {
            com.allnetworktools.util.DebugLog.w("RTL-SDR", "FC0012: no PLL setting for $freq Hz")
            return -1
        }
        reg[6] = reg[6] or 0x20 // clock out
        var xin = ((fVco - (fVco / xtalDiv2) * xtalDiv2) / 1000).toInt() and 0xffff
        xin = ((xin shl 15) / (xtalDiv2 / 1000).toInt()) and 0xffff
        if (xin >= 16384) xin = (xin + 32768) and 0xffff
        reg[3] = (xin shr 8) and 0xff
        reg[4] = xin and 0xff
        reg[6] = (reg[6] and 0x3f) or 0x80 // 6 MHz bandwidth
        reg[5] = reg[5] or 0x07 // for the Realtek demodulator
        for (i in 1..6) if (write(i, reg[i]) < 0) return -1
        // VCO calibration, then the other VCO range when the control voltage is at an end of this one.
        if (write(0x0e, 0x80) < 0 || write(0x0e, 0x00) < 0 || write(0x0e, 0x00) < 0) return -1
        val v = (read(0x0e) ?: return -1) and 0x3f
        if ((vcoSelect && v > 0x3c) || (!vcoSelect && v < 0x02)) {
            reg[6] = if (vcoSelect) reg[6] and 0x08.inv() else reg[6] or 0x08
            if (write(0x06, reg[6]) < 0 || write(0x0e, 0x80) < 0 || write(0x0e, 0x00) < 0) return -1
        }
        return 0
    }

    /** The LNA's five steps (librtlsdr): -9.9, -4, 7.1, 17.9 and 19.2 dB. */
    override fun setGain(gainTenthDb: Int): Int {
        val g = GAINS.minByOrNull { kotlin.math.abs(it - gainTenthDb) } ?: 192
        val tmp = (read(0x13) ?: return -1) and 0xe0
        val bits = when (g) { -99 -> 0x02; -40 -> 0x00; 71 -> 0x08; 179 -> 0x17; else -> 0x10 }
        return write(0x13, tmp or bits)
    }

    companion object {
        const val ADDR = 0xc6
        const val CHECK_ADDR = 0x00
        const val CHECK_VAL = 0xa1
        const val MIN_HZ = 22_000_000L
        const val MAX_HZ = 948_000_000L
        val GAINS = intArrayOf(-99, -40, 71, 179, 192)
    }
}
