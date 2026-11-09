package com.allnetworktools.data.sdr

/**
 * RTL2832U USB demodulator and its Rafael Micro R820T / R820T2 / R828D tuner, ported from librtlsdr
 * (osmocom rtl-sdr, GPL-2.0-or-later: src/librtlsdr.c and src/tuner_r82xx.c, itself derived from the Linux r820t
 * driver by Mauro Carvalho Chehab and Steve Markgraf). The dongles with an Elonics E4000, Fitipower or FCI tuner are
 * not supported.
 */

/** The vendor control requests of the RTL2832U, on a USB connection (or a test double). */
interface RtlBus {
    /** Reads [length] bytes, or null when the transfer fails. */
    fun controlIn(value: Int, index: Int, length: Int): ByteArray?

    /** Writes [data]; returns the number of bytes written or a negative number. */
    fun controlOut(value: Int, index: Int, data: ByteArray): Int
}

/** The RTL2832U itself: registers, demodulator, I2C to the tuner and GPIO. */
class Rtl2832(private val bus: RtlBus) {
    var rtlXtal = DEF_XTAL

    // ---- registers ----------------------------------------------------------------------------------

    fun readArray(block: Int, addr: Int, len: Int): ByteArray? = bus.controlIn(addr, block shl 8, len)

    fun writeArray(block: Int, addr: Int, data: ByteArray): Int = bus.controlOut(addr, (block shl 8) or 0x10, data)

    private fun payload(value: Int, len: Int): ByteArray {
        val d = ByteArray(2)
        d[0] = (if (len == 1) value and 0xff else value shr 8).toByte()
        d[1] = (value and 0xff).toByte()
        return d.copyOf(len)
    }

    fun readReg(block: Int, addr: Int, len: Int): Int {
        val d = bus.controlIn(addr, block shl 8, len) ?: return 0
        return ((d.getOrElse(1) { 0 }.toInt() and 0xff) shl 8) or (d.getOrElse(0) { 0 }.toInt() and 0xff)
    }

    fun writeReg(block: Int, addr: Int, value: Int, len: Int): Int = bus.controlOut(addr, (block shl 8) or 0x10, payload(value, len))

    fun demodRead(page: Int, addr: Int, len: Int): Int {
        val d = bus.controlIn((addr shl 8) or 0x20, page, len) ?: return 0
        return ((d.getOrElse(1) { 0 }.toInt() and 0xff) shl 8) or (d.getOrElse(0) { 0 }.toInt() and 0xff)
    }

    /** Returns 0 when the whole value was written. */
    fun demodWrite(page: Int, addr: Int, value: Int, len: Int): Int {
        val r = bus.controlOut((addr shl 8) or 0x20, 0x10 or page, payload(value, len))
        demodRead(0x0a, 0x01, 1)
        return if (r == len) 0 else -1
    }

    // ---- I2C to the tuner (the repeater must be on) -----------------------------------------------------

    fun i2cWrite(addr: Int, data: ByteArray): Int = writeArray(IICB, addr, data)

    fun i2cRead(addr: Int, len: Int): ByteArray? = readArray(IICB, addr, len)

    fun i2cReadReg(addr: Int, reg: Int): Int {
        i2cWrite(addr, byteArrayOf(reg.toByte()))
        return (i2cRead(addr, 1)?.firstOrNull()?.toInt() ?: 0) and 0xff
    }

    fun setI2cRepeater(on: Boolean) { demodWrite(1, 0x01, if (on) 0x18 else 0x10, 1) }

    // ---- GPIO ---------------------------------------------------------------------------------------

    fun setGpioBit(gpio: Int, on: Boolean) {
        val bit = 1 shl gpio
        val r = readReg(SYSB, GPO, 1)
        writeReg(SYSB, GPO, if (on) r or bit else r and bit.inv(), 1)
    }

    fun setGpioOutput(gpio: Int) {
        val bit = 1 shl gpio
        writeReg(SYSB, GPD, readReg(SYSB, GPD, 1) and bit.inv(), 1)
        writeReg(SYSB, GPOE, readReg(SYSB, GPOE, 1) or bit, 1)
    }

    fun setBiasTee(gpio: Int, on: Boolean) {
        setGpioOutput(gpio)
        setGpioBit(gpio, on)
    }

    // ---- demodulator ---------------------------------------------------------------------------------

    private fun setFir() {
        val fir = ByteArray(20)
        for (i in 0 until 8) fir[i] = FIR_DEFAULT[i].toByte()
        var i = 0
        while (i < 8) {
            val v0 = FIR_DEFAULT[8 + i]
            val v1 = FIR_DEFAULT[8 + i + 1]
            fir[8 + i * 3 / 2] = (v0 shr 4).toByte()
            fir[8 + i * 3 / 2 + 1] = ((v0 shl 4) or ((v1 shr 8) and 0x0f)).toByte()
            fir[8 + i * 3 / 2 + 2] = v1.toByte()
            i += 2
        }
        for (k in fir.indices) demodWrite(1, 0x1c + k, fir[k].toInt() and 0xff, 1)
    }

    fun initBaseband() {
        writeReg(USBB, USB_SYSCTL, 0x09, 1)
        writeReg(USBB, USB_EPA_MAXPKT, 0x0002, 2)
        writeReg(USBB, USB_EPA_CTL, 0x1002, 2)
        // Power on the demodulator.
        writeReg(SYSB, DEMOD_CTL_1, 0x22, 1)
        writeReg(SYSB, DEMOD_CTL, 0xe8, 1)
        // Reset it.
        demodWrite(1, 0x01, 0x14, 1)
        demodWrite(1, 0x01, 0x10, 1)
        // No spectrum inversion, no adjacent channel rejection.
        demodWrite(1, 0x15, 0x00, 1)
        demodWrite(1, 0x16, 0x0000, 2)
        for (i in 0 until 6) demodWrite(1, 0x16 + i, 0x00, 1)
        setFir()
        // SDR mode, no DAGC.
        demodWrite(0, 0x19, 0x05, 1)
        demodWrite(1, 0x93, 0xf0, 1)
        demodWrite(1, 0x94, 0x0f, 1)
        demodWrite(1, 0x11, 0x00, 1)
        demodWrite(1, 0x04, 0x00, 1)
        demodWrite(0, 0x61, 0x60, 1)
        demodWrite(0, 0x06, 0x80, 1)
        // Zero-IF mode, DC cancellation, IQ compensation.
        demodWrite(1, 0xb1, 0x1b, 1)
        demodWrite(0, 0x0d, 0x83, 1)
    }

    fun deinitBaseband() {
        writeReg(SYSB, DEMOD_CTL, 0x20, 1)
    }

    fun setIfFreq(freq: Int): Int {
        val ifFreq = -((freq.toDouble() * TWO22) / rtlXtal).toLong().toInt()
        var r = demodWrite(1, 0x19, (ifFreq shr 16) and 0x3f, 1)
        r = r or demodWrite(1, 0x1a, (ifFreq shr 8) and 0xff, 1)
        r = r or demodWrite(1, 0x1b, ifFreq and 0xff, 1)
        return r
    }

    private fun setSampleFreqCorrection(ppm: Int): Int {
        val offs = (ppm * -1 * (1 shl 24) / 1_000_000).toShort().toInt()
        var r = demodWrite(1, 0x3f, offs and 0xff, 1)
        r = r or demodWrite(1, 0x3e, (offs shr 8) and 0x3f, 1)
        return r
    }

    /** The exact rate for [rate], or 0 when the resampler cannot make it. Programs the demodulator. */
    fun setSampleRate(rate: Int): Double {
        if (rate <= 225_000 || rate > 3_200_000 || (rate in 300_001..900_000)) return 0.0
        var ratio = ((rtlXtal.toDouble() * TWO22) / rate).toLong().toInt()
        ratio = ratio and 0x0ffffffc
        val real = ratio or ((ratio and 0x08000000) shl 1)
        val realRate = (rtlXtal.toDouble() * TWO22) / real.toLong().and(0xffffffffL)
        var r = demodWrite(1, 0x9f, ratio ushr 16, 2)
        r = r or demodWrite(1, 0xa1, ratio and 0xffff, 2)
        r = r or setSampleFreqCorrection(0)
        r = r or demodWrite(1, 0x01, 0x14, 1)
        r = r or demodWrite(1, 0x01, 0x10, 1)
        return if (r == 0) realRate else 0.0
    }

    fun resetBuffer() {
        writeReg(USBB, USB_EPA_CTL, 0x1002, 2)
        writeReg(USBB, USB_EPA_CTL, 0x0000, 2)
    }

    companion object {
        const val DEF_XTAL = 28_800_000
        private const val TWO22 = 4194304.0

        const val DEMODB = 0
        const val USBB = 1
        const val SYSB = 2
        const val IICB = 6

        const val USB_SYSCTL = 0x2000
        const val USB_EPA_CTL = 0x2148
        const val USB_EPA_MAXPKT = 0x2158
        const val DEMOD_CTL = 0x3000
        const val GPO = 0x3001
        const val GPOE = 0x3003
        const val GPD = 0x3004
        const val DEMOD_CTL_1 = 0x300b

        /** The default FIR of the Windows driver: 8 coefficients of 8 bits, then 8 of 12 bits. */
        private val FIR_DEFAULT = intArrayOf(-54, -36, -41, -40, -32, -14, 14, 53, 101, 156, 215, 273, 327, 372, 404, 421)
    }
}

/** The Rafael Micro R820T / R820T2 / R828D tuner behind an RTL2832U. */
class R82xx(private val rtl: Rtl2832, private val r828d: Boolean, private val xtal: Int, private val model: Model) {
    /** The RTL-SDR Blog dongles are told apart by their USB strings. */
    enum class Model { Generic, BlogV4, BlogV4L }

    private val addr = if (r828d) R828D_I2C_ADDR else R820T_I2C_ADDR
    private val regs = IntArray(NUM_REGS)
    private var xtalCapSel = XTAL_HIGH_CAP_0P
    var intFreq = 0
        private set
    private var filCalCode = 0
    private var input = 0
    private var hasLock = false
    private var initDone = false

    // ---- I2C and shadow registers ------------------------------------------------------------------------------

    private fun shadowEqual(reg: Int, v: IntArray): Boolean {
        val r = reg - REG_SHADOW_START
        if (r < 0 || v.size > NUM_REGS - r) return false
        for (i in v.indices) if (regs[r + i] != v[i]) return false
        return true
    }

    private fun write(reg: Int, v: IntArray): Int {
        // Not worth an I2C transfer when the register already holds it.
        if (shadowEqual(reg, v)) return 0
        var r = reg - REG_SHADOW_START
        var vv = v
        var len = v.size
        if (r < 0) { len += r; vv = v.copyOfRange(-r, v.size); r = 0 }
        if (len > 0) {
            val n = minOf(len, NUM_REGS - r)
            for (i in 0 until n) regs[r + i] = vv[i]
        }
        var pos = 0
        var cur = reg
        var left = v.size
        do {
            val size = minOf(left, MAX_I2C_MSG - 1)
            val buf = ByteArray(size + 1)
            buf[0] = cur.toByte()
            for (i in 0 until size) buf[1 + i] = v[pos + i].toByte()
            val rc = rtl.i2cWrite(addr, buf)
            if (rc != size + 1) return if (rc < 0) rc else -1
            cur += size
            left -= size
            pos += size
        } while (left > 0)
        return 0
    }

    private fun writeReg(reg: Int, v: Int): Int = write(reg, intArrayOf(v and 0xff))

    private fun cache(reg: Int): Int {
        val r = reg - REG_SHADOW_START
        return if (r in 0 until NUM_REGS) regs[r] else -1
    }

    private fun writeRegMask(reg: Int, v: Int, mask: Int): Int {
        val rc = cache(reg)
        if (rc < 0) return rc
        return writeReg(reg, (rc and mask.inv()) or (v and mask))
    }

    private fun bitrev(b: Int): Int {
        val lut = intArrayOf(0x0, 0x8, 0x4, 0xc, 0x2, 0xa, 0x6, 0xe, 0x1, 0x9, 0x5, 0xd, 0x3, 0xb, 0x7, 0xf)
        return (lut[b and 0xf] shl 4) or lut[(b and 0xff) shr 4]
    }

    private fun read(reg: Int, len: Int): IntArray? {
        if (rtl.i2cWrite(addr, byteArrayOf(reg.toByte())) != 1) return null
        val d = rtl.i2cRead(addr, len) ?: return null
        if (d.size != len) return null
        return IntArray(len) { bitrev(d[it].toInt() and 0xff) }
    }

    // ---- tuning ------------------------------------------------------------------------------------------

    private fun setMux(freqHz: Long): Int {
        val mhz = (freqHz / 1_000_000).toInt()
        var i = 0
        while (i < FREQ_RANGES.size - 1) {
            if (mhz < FREQ_RANGES[i + 1].freq) break
            i++
        }
        val range = FREQ_RANGES[i]
        var rc = writeRegMask(0x17, range.openD, 0x08)
        if (rc < 0) return rc
        rc = writeRegMask(0x1a, range.rfMuxPoly, 0xc3)
        if (rc < 0) return rc
        rc = writeReg(0x1b, range.tfC)
        if (rc < 0) return rc
        val v = when (xtalCapSel) {
            XTAL_LOW_CAP_30P, XTAL_LOW_CAP_20P -> range.xtalCap20p or 0x08
            XTAL_LOW_CAP_10P -> range.xtalCap10p or 0x08
            XTAL_HIGH_CAP_0P -> range.xtalCap0p or 0x00
            else -> range.xtalCap0p or 0x08
        }
        rc = writeRegMask(0x10, v, 0x0b)
        if (rc < 0) return rc
        rc = writeRegMask(0x08, 0x00, 0x3f)
        if (rc < 0) return rc
        return writeRegMask(0x09, 0x00, 0x3f)
    }

    private fun mask8(reg: Int, v: Int, mask: Int) = (reg and mask.inv()) or (v and mask)

    private fun setPll(freqHz: Long): Int {
        val vcoMin = 1_770_000L
        val vcoMax = vcoMin * 2
        val freqKhz = ((freqHz + 500) / 1000)
        val pllRef = xtal.toLong()
        var rc = writeRegMask(0x1a, 0x00, 0x0c)
        if (rc < 0) return rc
        val r = IntArray(7) { regs[0x10 - REG_SHADOW_START + it] }
        r[0] = mask8(r[0], 0, 0x10)
        r[2] = mask8(r[2], 0x80, 0xe0)
        var mixDiv = 2
        var divNum = 0
        while (mixDiv <= 64) {
            if (freqKhz * mixDiv >= vcoMin && freqKhz * mixDiv < vcoMax) {
                var divBuf = mixDiv
                while (divBuf > 2) { divBuf = divBuf shr 1; divNum++ }
                break
            }
            mixDiv = mixDiv shl 1
        }
        val data = read(0x00, 5) ?: return -1
        val vcoPowerRef = if (r828d || model == Model.BlogV4L) 1 else 2
        val vcoFineTune = (data[4] and 0x30) shr 4
        if (vcoFineTune > vcoPowerRef) divNum -= 1 else if (vcoFineTune < vcoPowerRef) divNum += 1
        r[0] = mask8(r[0], divNum shl 5, 0xe0)
        val vcoFreq = freqHz * mixDiv.toLong()
        val vcoDiv = (pllRef + 65536L * vcoFreq) / (2L * pllRef)
        val nint = (vcoDiv / 65536L).toInt()
        val sdm = (vcoDiv % 65536L).toInt()
        if (nint > (128 / vcoPowerRef) - 1) return -1
        val ni = (nint - 13) / 4
        val si = nint - 4 * ni - 13
        r[4] = (ni + (si shl 6)) and 0xff
        r[2] = mask8(r[2], if (sdm == 0) 0x08 else 0x00, 0x08)
        r[5] = sdm and 0xff
        r[6] = sdm shr 8
        rc = write(0x10, r)
        if (rc < 0) return rc
        var lock: IntArray? = null
        for (i in 0 until 2) {
            lock = read(0x00, 3) ?: return -1
            if (lock[2] and 0x40 != 0) break
            if (i == 0) {
                rc = writeRegMask(0x12, 0x60, 0xe0)
                if (rc < 0) return rc
            }
        }
        if (lock == null || lock[2] and 0x40 == 0) {
            hasLock = false
            return 0
        }
        hasLock = true
        return writeRegMask(0x1a, 0x08, 0x08)
    }

    private fun sysfreqSel(): Int {
        // DVB-T defaults, as in librtlsdr (the chip is only used as a plain tuner).
        val mixerTop = 0x24
        val lnaTop = 0xe5
        val lnaVthL = 0x53
        val mixerVthL = 0x75
        val airCable1In = 0x00
        val cable2In = 0x00
        val lnaDischarge = 14
        val cpCur = 0x38
        val divBufCur = 0x30
        val filterCur = 0x40
        var rc = writeRegMask(0x1d, lnaTop, 0xc7)
        if (rc < 0) return rc
        rc = writeRegMask(0x1c, mixerTop, 0xf8)
        if (rc < 0) return rc
        rc = writeReg(0x0d, lnaVthL)
        if (rc < 0) return rc
        rc = writeReg(0x0e, mixerVthL)
        if (rc < 0) return rc
        input = airCable1In
        rc = writeRegMask(0x05, airCable1In, 0x60)
        if (rc < 0) return rc
        rc = writeRegMask(0x06, cable2In, 0x08)
        if (rc < 0) return rc
        rc = writeRegMask(0x11, cpCur, 0x38)
        if (rc < 0) return rc
        rc = writeRegMask(0x17, divBufCur, 0x30)
        if (rc < 0) return rc
        rc = writeRegMask(0x0a, filterCur, 0x60)
        if (rc < 0) return rc
        // LNA: top lowest, normal mode, predetect off, AGC clock 250 Hz, LNA top 3, discharge, AGC clock 60 Hz.
        rc = writeRegMask(0x1d, 0, 0x38)
        if (rc < 0) return rc
        rc = writeRegMask(0x1c, 0, 0x04)
        if (rc < 0) return rc
        rc = writeRegMask(0x06, 0, 0x40)
        if (rc < 0) return rc
        rc = writeRegMask(0x1a, 0x30, 0x30)
        if (rc < 0) return rc
        rc = writeRegMask(0x1d, 0x18, 0x38)
        if (rc < 0) return rc
        rc = writeRegMask(0x1c, mixerTop, 0x04)
        if (rc < 0) return rc
        rc = writeRegMask(0x1e, lnaDischarge, 0x1f)
        if (rc < 0) return rc
        return writeRegMask(0x1a, 0x20, 0x30)
    }

    private fun setTvStandard(): Int {
        val ifKhz = 3570
        val filCalLo = 56000
        val filtGain = 0x10
        val imgR = 0x00
        val filtQ = 0x10
        val hpCor = 0x6b
        val extEnable = 0x60
        val loopThrough = 0x01
        val ltAtt = 0x00
        val fltExtWidest = 0x00
        val polyfilCur = 0x60
        for (i in 0 until NUM_REGS) regs[i] = if (i < INIT_ARRAY.size) INIT_ARRAY[i] else 0
        var rc = writeRegMask(0x0c, 0x00, 0x0f)
        if (rc < 0) return rc
        rc = writeRegMask(0x13, VER_NUM, 0x3f)
        if (rc < 0) return rc
        rc = writeRegMask(0x1d, 0x00, 0x38)
        if (rc < 0) return rc
        intFreq = ifKhz * 1000
        for (i in 0 until 2) {
            rc = writeRegMask(0x0b, hpCor, 0x60)
            if (rc < 0) return rc
            rc = writeRegMask(0x0f, 0x04, 0x04)
            if (rc < 0) return rc
            rc = writeRegMask(0x10, 0x00, 0x03)
            if (rc < 0) return rc
            rc = setPll(filCalLo * 1000L)
            if (rc < 0 || !hasLock) return rc
            rc = writeRegMask(0x0b, 0x10, 0x10)
            if (rc < 0) return rc
            rc = writeRegMask(0x0b, 0x00, 0x10)
            if (rc < 0) return rc
            rc = writeRegMask(0x0f, 0x00, 0x04)
            if (rc < 0) return rc
            val data = read(0x00, 5) ?: return -1
            filCalCode = data[4] and 0x0f
            if (filCalCode != 0 && filCalCode != 0x0f) break
        }
        if (filCalCode == 0x0f) filCalCode = 0
        rc = writeRegMask(0x0a, filtQ or filCalCode, 0x1f)
        if (rc < 0) return rc
        rc = writeRegMask(0x0b, hpCor, 0xef)
        if (rc < 0) return rc
        rc = writeRegMask(0x07, imgR, 0x80)
        if (rc < 0) return rc
        rc = writeRegMask(0x06, filtGain, 0x30)
        if (rc < 0) return rc
        rc = writeRegMask(0x1e, extEnable, 0x60)
        if (rc < 0) return rc
        rc = writeRegMask(0x05, loopThrough, 0x80)
        if (rc < 0) return rc
        rc = writeRegMask(0x1f, ltAtt, 0x80)
        if (rc < 0) return rc
        rc = writeRegMask(0x0f, fltExtWidest, 0x80)
        if (rc < 0) return rc
        return writeRegMask(0x19, polyfilCur, 0x60)
    }

    fun init(): Int {
        xtalCapSel = XTAL_HIGH_CAP_0P
        regs.fill(0)
        write(0x05, IntArray(NUM_REGS) { if (it < INIT_ARRAY.size) INIT_ARRAY[it] else 0 })
        var rc = setTvStandard()
        if (rc < 0) return rc
        rc = sysfreqSel()
        initDone = true
        return rc
    }

    fun standby(): Int {
        if (!initDone) return 0
        var rc = writeReg(0x06, 0xb1)
        if (rc < 0) return rc
        for ((r, v) in listOf(0x05 to 0xa0, 0x07 to 0x3a, 0x08 to 0x40, 0x09 to 0xc0, 0x0a to 0x36, 0x0c to 0x35, 0x0f to 0x68, 0x11 to 0x03, 0x17 to 0xf4)) {
            rc = writeReg(r, v)
            if (rc < 0) return rc
        }
        return writeReg(0x19, 0x0c)
    }

    /** Filter and IF for a bandwidth; returns the IF the demodulator must be told. */
    fun setBandwidth(bwIn: Int): Int {
        var bw = bwIn
        val reg0a: Int
        var reg0b: Int
        var realBw = 0
        if (bw > 7_000_000) {
            reg0a = 0x10; reg0b = 0x0b; intFreq = 4_570_000
        } else if (bw > 6_000_000) {
            reg0a = 0x10; reg0b = 0x2a; intFreq = 4_570_000
        } else if (bw > IF_LOW_PASS_BW[0] + FILT_HP_BW1 + FILT_HP_BW2) {
            reg0a = 0x10; reg0b = 0x6b; intFreq = 3_570_000
        } else {
            reg0a = 0x00; reg0b = 0x80; intFreq = 2_300_000
            if (bw > IF_LOW_PASS_BW[0] + FILT_HP_BW1) {
                bw -= FILT_HP_BW2; intFreq += FILT_HP_BW2; realBw += FILT_HP_BW2
            } else {
                reg0b = reg0b or 0x20
            }
            if (bw > IF_LOW_PASS_BW[0]) {
                bw -= FILT_HP_BW1; intFreq += FILT_HP_BW1; realBw += FILT_HP_BW1
            } else {
                reg0b = reg0b or 0x40
            }
            var i = 0
            while (i < IF_LOW_PASS_BW.size) {
                if (bw > IF_LOW_PASS_BW[i]) break
                i++
            }
            i = (i - 1).coerceAtLeast(0)
            reg0b = reg0b or (15 - i)
            realBw += IF_LOW_PASS_BW[i]
            intFreq -= realBw / 2
        }
        var rc = writeRegMask(0x0a, reg0a, 0x10)
        if (rc < 0) return rc
        rc = writeRegMask(0x0b, reg0b, 0xef)
        if (rc < 0) return rc
        return intFreq
    }

    fun setFreq(freq: Long): Int {
        val v4 = model == Model.BlogV4
        val v4l = model == Model.BlogV4L
        // The V4 up-converts the HF band by 28.8 MHz so that no offset is needed in software.
        val up = if ((v4 || v4l) && freq < 28_800_000) freq + 28_800_000 else freq
        val lo = up + intFreq
        var rc = setMux(lo)
        if (rc < 0) return rc
        rc = setPll(lo)
        if (rc < 0 || !hasLock) return if (rc < 0) rc else -1
        if (v4) {
            val openD = if (freq <= 2_200_000 || (freq in 85_000_000..112_000_000) || (freq in 172_000_000..242_000_000)) 0x00 else 0x08
            rc = writeRegMask(0x17, openD, 0x08)
            if (rc < 0) return rc
            val band = if (freq <= 28_800_000) HF else if (freq < 250_000_000) VHF else UHF
            if (band == HF) {
                rc = writeRegMask(0x1a, 0x40, 0xc3)
                if (rc < 0) return rc
                rc = writeReg(0x1b, 0x00)
                if (rc < 0) return rc
            }
            if (band != input) {
                input = band
                val cable2 = if (band == HF) 0x08 else 0x00
                rc = writeRegMask(0x06, cable2, 0x08)
                if (rc < 0) return rc
                rtl.setBiasTee(5, cable2 == 0)
                rc = writeRegMask(0x05, if (band == VHF) 0x40 else 0x00, 0x40)
                if (rc < 0) return rc
                rc = writeRegMask(0x05, if (band == UHF) 0x00 else 0x20, 0x20)
                if (rc < 0) return rc
            }
        } else if (v4l) {
            val band = if (freq <= 28_800_000) HF else UHF
            if (band == HF) {
                rc = writeRegMask(0x1a, 0x40, 0xc3)
                if (rc < 0) return rc
                rc = writeReg(0x1b, 0x00)
                if (rc < 0) return rc
            }
            if (band != input) {
                input = band
                val cable1 = if (band == HF) 0x40 else 0x00
                rtl.setBiasTee(5, cable1 == 0)
                rc = writeRegMask(0x05, cable1, 0x40)
                if (rc < 0) return rc
                rc = writeRegMask(0x05, if (band == UHF) 0x00 else 0x20, 0x20)
                if (rc < 0) return rc
            }
        } else {
            // R828D sticks switch between the cable and air inputs at 345 MHz.
            val airCable1In = if (freq > 345_000_000) 0x00 else 0x60
            if (r828d && airCable1In != input) {
                input = airCable1In
                rc = writeRegMask(0x05, airCable1In, 0x60)
            }
        }
        return rc
    }

    /** Manual gain in tenths of a dB: the LNA and the mixer are stepped up until the total is reached. */
    fun setGain(gainTenthDb: Int): Int {
        var rc = writeRegMask(0x05, 0x10, 0x10)
        if (rc < 0) return rc
        rc = writeRegMask(0x07, 0, 0x10)
        if (rc < 0) return rc
        read(0x00, 4) ?: return -1
        rc = writeRegMask(0x0c, 0x08, 0x9f)
        if (rc < 0) return rc
        var total = 0
        var mix = 0
        var lna = 0
        for (i in 0 until 15) {
            if (total >= gainTenthDb) break
            total += LNA_GAIN_STEPS[++lna]
            if (total >= gainTenthDb) break
            total += MIXER_GAIN_STEPS[++mix]
        }
        rc = writeRegMask(0x05, lna, 0x0f)
        if (rc < 0) return rc
        return writeRegMask(0x07, mix, 0x0f)
    }

    private class Range(val freq: Int, val openD: Int, val rfMuxPoly: Int, val tfC: Int, val xtalCap20p: Int, val xtalCap10p: Int, val xtalCap0p: Int)

    companion object {
        const val R820T_I2C_ADDR = 0x34
        const val R828D_I2C_ADDR = 0x74
        const val R828D_XTAL = 16_000_000
        const val CHECK_ADDR = 0x00
        const val CHECK_VAL = 0x69
        const val IF_FREQ = 3_570_000

        private const val REG_SHADOW_START = 5
        private const val NUM_REGS = 30
        private const val VER_NUM = 49
        private const val MAX_I2C_MSG = 8
        private const val XTAL_LOW_CAP_30P = 0
        private const val XTAL_LOW_CAP_20P = 1
        private const val XTAL_LOW_CAP_10P = 2
        private const val XTAL_HIGH_CAP_0P = 4
        private const val HF = 1
        private const val VHF = 2
        private const val UHF = 3
        private const val FILT_HP_BW1 = 350_000
        private const val FILT_HP_BW2 = 380_000

        private val INIT_ARRAY = intArrayOf(
            0x83, 0x32, 0x75, 0xc0, 0x40, 0xd6, 0x6c, 0xf5, 0x63, 0x75, 0x68, 0x6c, 0x83, 0x80, 0x00,
            0x0f, 0x00, 0xc0, 0x30, 0x48, 0xcc, 0x60, 0x00, 0x54, 0xae, 0x4a, 0xc0,
        )

        private val IF_LOW_PASS_BW = intArrayOf(1_700_000, 1_600_000, 1_550_000, 1_450_000, 1_200_000, 900_000, 700_000, 550_000, 450_000, 350_000)

        /** Measured gain steps, in tenths of a dB (librtlsdr). */
        private val LNA_GAIN_STEPS = intArrayOf(0, 9, 13, 40, 38, 13, 31, 22, 26, 31, 26, 14, 19, 5, 35, 13)
        private val MIXER_GAIN_STEPS = intArrayOf(0, 5, 10, 10, 19, 9, 10, 25, 17, 10, 8, 16, 13, 6, 3, -8)

        /** Total gains the tuner can reach, in tenths of a dB. */
        val GAINS = intArrayOf(0, 9, 14, 27, 37, 77, 87, 125, 144, 157, 166, 197, 207, 229, 254, 280, 297, 328, 338, 364, 372, 386, 402, 421, 434, 439, 445, 480, 496)

        private fun r(freq: Int, openD: Int, mux: Int, tf: Int, c20: Int, c10: Int, c0: Int) = Range(freq, openD, mux, tf, c20, c10, c0)

        private val FREQ_RANGES = arrayOf(
            r(0, 0x08, 0x02, 0xdf, 0x02, 0x01, 0x00), r(50, 0x08, 0x02, 0xbe, 0x02, 0x01, 0x00),
            r(55, 0x08, 0x02, 0x8b, 0x02, 0x01, 0x00), r(60, 0x08, 0x02, 0x7b, 0x02, 0x01, 0x00),
            r(65, 0x08, 0x02, 0x69, 0x02, 0x01, 0x00), r(70, 0x08, 0x02, 0x58, 0x02, 0x01, 0x00),
            r(75, 0x00, 0x02, 0x44, 0x02, 0x01, 0x00), r(80, 0x00, 0x02, 0x44, 0x02, 0x01, 0x00),
            r(90, 0x00, 0x02, 0x34, 0x01, 0x01, 0x00), r(100, 0x00, 0x02, 0x34, 0x01, 0x01, 0x00),
            r(110, 0x00, 0x02, 0x24, 0x01, 0x01, 0x00), r(120, 0x00, 0x02, 0x24, 0x01, 0x01, 0x00),
            r(140, 0x00, 0x02, 0x14, 0x01, 0x01, 0x00), r(180, 0x00, 0x02, 0x13, 0x00, 0x00, 0x00),
            r(220, 0x00, 0x02, 0x13, 0x00, 0x00, 0x00), r(250, 0x00, 0x02, 0x11, 0x00, 0x00, 0x00),
            r(280, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00), r(310, 0x00, 0x41, 0x00, 0x00, 0x00, 0x00),
            r(450, 0x00, 0x41, 0x00, 0x00, 0x00, 0x00), r(588, 0x00, 0x40, 0x00, 0x00, 0x00, 0x00),
            r(650, 0x00, 0x40, 0x00, 0x00, 0x00, 0x00),
        )
    }
}
