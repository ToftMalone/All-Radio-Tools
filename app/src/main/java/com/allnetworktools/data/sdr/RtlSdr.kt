package com.allnetworktools.data.sdr

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import com.allnetworktools.R
import com.allnetworktools.util.DebugLog
import com.allnetworktools.util.S
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException

/**
 * An RTL-SDR dongle (RTL2832U with an R820T, R820T2 or R828D tuner), receive only. The dongle sends unsigned 8-bit I/Q;
 * they are turned into signed 8-bit ones, the format of every other radio here. Ported from librtlsdr.
 */
class RtlSdr private constructor(
    private val conn: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val epIn: UsbEndpoint?,
    private val rtl: Rtl2832,
    private val tuner: R82xx,
    private val model: R82xx.Model,
    tunerName: String,
) : SdrRadio {
    override val caps = SdrCaps(
        name = "RTL-SDR ($tunerName)",
        // The Blog V4 and V4L up-convert the HF band.
        minHz = if (model == R82xx.Model.Generic) 24_000_000L else 500_000L,
        maxHz = 1_766_000_000L,
        maxRate = 3_200_000,
    )

    @Volatile private var running = false
    private var thread: Thread? = null
    private var rate = 0
    private var freq = 0L
    private var lna = 0
    private var vga = 0
    private var amp = false

    private inline fun <T> tuned(block: () -> T): T {
        rtl.setI2cRepeater(true)
        try { return block() } finally { rtl.setI2cRepeater(false) }
    }

    override fun setSampleRate(hz: Int, filterHz: Int): Boolean {
        val real = rtl.setSampleRate(hz)
        if (real <= 0.0) { DebugLog.w("RTL-SDR", "Sample rate $hz refused by the RTL2832U resampler"); return false }
        rate = real.toInt()
        // The tuner filter follows the rate; the IF it picks goes to the demodulator.
        val ifFreq = tuned { tuner.setBandwidth(rate) }
        DebugLog.i("RTL-SDR", "Rate ${real.toInt()} Hz, tuner IF $ifFreq Hz")
        if (ifFreq < 0) return false
        rtl.setIfFreq(ifFreq)
        if (freq != 0L) tuned { tuner.setFreq(freq) }
        return true
    }

    override fun setFrequency(hz: Long): Boolean {
        if (hz < caps.minHz || hz > caps.maxHz) return false
        val r = tuned { tuner.setFreq(hz) }
        if (r < 0) DebugLog.w("RTL-SDR", "Tuning to $hz Hz failed ($r): ${if (!tuner.locked) "PLL not locked" else "I2C error"}")
        freq = if (r >= 0) hz else 0L
        return r >= 0
    }

    /** The HackRF gain scale (LNA + VGA + amplifier, up to 116 dB) spread over the tuner's 0 to 49.6 dB. */
    private fun applyGain(): Boolean {
        val total = lna + vga + (if (amp) 14 else 0)
        val wanted = (total * R82xx.GAINS.last() / 116).coerceIn(0, R82xx.GAINS.last())
        val g = R82xx.GAINS.minByOrNull { Math.abs(it - wanted) } ?: 0
        return tuned { tuner.setGain(g) } >= 0
    }

    /** [value] in dB: the nearest step the tuner can make. */
    override fun setNativeGain(value: Int): Boolean {
        val g = R82xx.GAINS.minByOrNull { Math.abs(it - value * 10) } ?: 0
        return tuned { tuner.setGain(g) } >= 0
    }

    override fun setLnaGain(db: Int): Boolean { lna = db.coerceIn(0, 40); return applyGain() }
    override fun setVgaGain(db: Int): Boolean { vga = db.coerceIn(0, 62); return applyGain() }
    override fun setAmp(on: Boolean): Boolean { amp = on; return applyGain() }

    /** The bias tee of the RTL-SDR Blog dongles (GPIO 0). */
    override fun setAntennaPower(on: Boolean): Boolean {
        rtl.setBiasTee(0, on)
        return true
    }

    override fun startRx(onSamples: (ByteArray, Int) -> Unit, onError: (String) -> Unit) {
        if (running) return
        val ep = epIn ?: run { DebugLog.e("RTL-SDR", "No bulk IN endpoint"); onError(S(R.string.usb_error)); return }
        rtl.resetBuffer()
        running = true
        thread = Thread({
            val reqs = List(TRANSFERS) { UsbRequest().apply { initialize(conn, ep) } }
            val bufs = List(TRANSFERS) { ByteBuffer.allocateDirect(TRANSFER_SIZE) }
            val copy = ByteArray(TRANSFER_SIZE)
            reqs.forEachIndexed { i, r -> r.clientData = i; r.queue(bufs[i]) }
            var timeouts = 0
            try {
                while (running) {
                    val r = try {
                        conn.requestWait(1000)
                    } catch (e: TimeoutException) {
                        if (++timeouts >= 3) { onError(S(R.string.no_more_samples_from_the_hackrf)); break }
                        continue
                    }
                    if (r == null) { onError(S(R.string.usb_link_lost)); break }
                    timeouts = 0
                    val b = bufs[r.clientData as Int]
                    val len = b.position()
                    b.flip()
                    b.get(copy, 0, len)
                    b.clear()
                    if (running) r.queue(b)
                    if (len > 0) {
                        unsignedToSigned(copy, len)
                        onSamples(copy, len)
                    }
                }
            } catch (e: Exception) {
                if (running) onError(e.message ?: S(R.string.usb_error))
            } finally {
                reqs.forEach { runCatching { it.cancel(); it.close() } }
            }
        }, "rtlsdr-rx").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    override fun close() {
        running = false
        runCatching { thread?.join(1500) }
        runCatching { setAntennaPower(false) }
        runCatching { tuned { tuner.standby() } }
        runCatching { rtl.deinitBaseband() }
        runCatching { conn.releaseInterface(iface) }
        runCatching { conn.close() }
    }

    companion object {
        private const val TRANSFERS = 8
        private const val TRANSFER_SIZE = 16 * 32 * 512
        private const val TIMEOUT_MS = 300

        /** The ids librtlsdr knows (generic RTL2832U sticks and the ones sold under other brands). */
        private val KNOWN = setOf(
            0x0bda to 0x2832, 0x0bda to 0x2838, 0x0413 to 0x6680, 0x0413 to 0x6f0f, 0x0458 to 0x707f, 0x0ccd to 0x00a9,
            0x0ccd to 0x00b3, 0x0ccd to 0x00b4, 0x0ccd to 0x00b5, 0x0ccd to 0x00b7, 0x0ccd to 0x00b8, 0x0ccd to 0x00b9,
            0x0ccd to 0x00c0, 0x0ccd to 0x00c6, 0x0ccd to 0x00d3, 0x0ccd to 0x00d7, 0x0ccd to 0x00e0, 0x1554 to 0x5020,
            0x15f4 to 0x0131, 0x15f4 to 0x0133, 0x185b to 0x0620, 0x185b to 0x0650, 0x185b to 0x0680, 0x1b80 to 0xd393,
            0x1b80 to 0xd394, 0x1b80 to 0xd395, 0x1b80 to 0xd397, 0x1b80 to 0xd398, 0x1b80 to 0xd39d, 0x1b80 to 0xd3a4,
            0x1b80 to 0xd3a8, 0x1b80 to 0xd3af, 0x1b80 to 0xd3b0, 0x1d19 to 0x1101, 0x1d19 to 0x1102, 0x1d19 to 0x1103,
            0x1d19 to 0x1104, 0x1f4d to 0xa803, 0x1f4d to 0xb803, 0x1f4d to 0xc803, 0x1f4d to 0xd286, 0x1f4d to 0xd803,
        )

        fun isRtlSdr(d: UsbDevice) = (d.vendorId to d.productId) in KNOWN

        /** In place: the dongle's unsigned samples (0 to 255, centred on 127.5) become signed ones. */
        fun unsignedToSigned(buf: ByteArray, len: Int) {
            for (i in 0 until len) buf[i] = (buf[i].toInt() xor 0x80).toByte()
        }

        fun open(manager: UsbManager, device: UsbDevice): RtlSdr? {
            val iface = (0 until device.interfaceCount).map(device::getInterface).firstOrNull() ?: return null
            val ep = (0 until iface.endpointCount).map(iface::getEndpoint)
                .firstOrNull { it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
            val conn = manager.openDevice(device) ?: return null
            if (!conn.claimInterface(iface, true)) { conn.close(); return null }
            val bus = object : RtlBus {
                override fun controlIn(value: Int, index: Int, length: Int): ByteArray? {
                    val b = ByteArray(length)
                    val r = conn.controlTransfer(UsbConstants.USB_TYPE_VENDOR or UsbConstants.USB_DIR_IN, 0, value, index, b, length, TIMEOUT_MS)
                    return if (r < 0) null else b.copyOf(r)
                }

                override fun controlOut(value: Int, index: Int, data: ByteArray): Int =
                    conn.controlTransfer(UsbConstants.USB_TYPE_VENDOR or UsbConstants.USB_DIR_OUT, 0, value, index, data, data.size, TIMEOUT_MS)
            }
            val rtl = Rtl2832(bus)
            // A first write that fails means the dongle needs a reset.
            DebugLog.i("RTL-SDR", "Interfaces ${device.interfaceCount}, endpoint ${ep?.address?.let { "0x%02x".format(it) }}, max packet ${ep?.maxPacketSize}")
            if (rtl.writeReg(Rtl2832.USBB, Rtl2832.USB_SYSCTL, 0x09, 1) < 0) { DebugLog.e("RTL-SDR", "First control write failed"); conn.close(); return null }
            rtl.initBaseband()
            val maker = device.manufacturerName.orEmpty()
            val product = device.productName.orEmpty()
            val model = when {
                maker == "RTLSDRBlog" && product == "Blog V4" -> R82xx.Model.BlogV4
                maker == "RTLSDRBlog" && product == "Blog V4L" -> R82xx.Model.BlogV4L
                else -> R82xx.Model.Generic
            }
            rtl.setI2cRepeater(true)
            val chip = when {
                rtl.i2cReadReg(R82xx.R820T_I2C_ADDR, R82xx.CHECK_ADDR) == R82xx.CHECK_VAL -> "R820T"
                rtl.i2cReadReg(R82xx.R828D_I2C_ADDR, R82xx.CHECK_ADDR) == R82xx.CHECK_VAL -> "R828D"
                else -> null
            }
            DebugLog.i("RTL-SDR", "USB strings '$maker' / '$product' -> model $model, tuner $chip")
            if (chip == null) {
                DebugLog.w("RTL-SDR", "No R820T / R828D tuner answered (E4000, FC0012, FC0013 and FC2580 are not supported)")
                // E4000, FC0012, FC0013 and FC2580 sticks are not supported.
                rtl.setI2cRepeater(false)
                conn.releaseInterface(iface); conn.close()
                return null
            }
            val r828d = chip == "R828D"
            val tunerXtal = if (r828d && model != R82xx.Model.BlogV4) R82xx.R828D_XTAL else rtl.rtlXtal
            // Low-IF mode for the R82xx: zero-IF off, in-phase ADC input only, 3.57 MHz IF, spectrum inversion.
            rtl.demodWrite(1, 0xb1, 0x1a, 1)
            rtl.demodWrite(0, 0x08, 0x4d, 1)
            rtl.setIfFreq(R82xx.IF_FREQ)
            rtl.demodWrite(1, 0x15, 0x01, 1)
            val tuner = R82xx(rtl, r828d, tunerXtal, model)
            val rc = tuner.init()
            rtl.setI2cRepeater(false)
            DebugLog.i("RTL-SDR", "Tuner init: $rc (xtal $tunerXtal Hz, PLL ${if (tuner.locked) "locked" else "not locked"})")
            if (rc < 0) { conn.releaseInterface(iface); conn.close(); return null }
            val label = when (model) {
                R82xx.Model.BlogV4 -> "Blog V4"
                R82xx.Model.BlogV4L -> "Blog V4L"
                else -> chip
            }
            return RtlSdr(conn, iface, ep, rtl, tuner, model, label)
        }
    }
}
