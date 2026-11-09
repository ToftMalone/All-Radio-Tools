package com.allnetworktools.data.sdr

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import com.allnetworktools.R
import com.allnetworktools.util.S
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeoutException

/**
 * The real-sample to I/Q conversion of libairspy (BSD-3-Clause, Airspy): the Airspy sends real 12-bit samples at twice the
 * complex rate; DC removal, a translation by fs/4 and a half-band filter make them I/Q. [process] takes 16-bit values
 * already centred and scaled (12 bits shifted left by 4) and leaves `count / 2` I/Q pairs in the same array.
 */
class AirspyIq {
    private val len = HB_KERNEL.size / 2 + 1
    private val kernel = IntArray(len) { HB_KERNEL[it * 2].toInt() }
    private val queue = IntArray(len * SIZE_FACTOR)
    private var firIndex = 0
    private val delayLine = ShortArray(len / 2 + 1)
    private var delayIndex = 0
    private var oldX = 0
    private var oldY = 0
    private var oldE = 0

    fun reset() {
        firIndex = 0; delayIndex = 0; oldX = 0; oldY = 0; oldE = 0
        queue.fill(0); delayLine.fill(0)
    }

    fun process(s: ShortArray, count: Int) {
        removeDc(s, count)
        var i = 0
        while (i < count) {
            s[i] = (-s[i]).toShort()
            s[i + 1] = ((-s[i + 1]) shr 1).toShort()
            s[i + 3] = (s[i + 3].toInt() shr 1).toShort()
            i += 4
        }
        fir(s, count)
        delay(s, count)
    }

    private fun removeDc(s: ShortArray, count: Int) {
        var ox = oldX
        var oy = oldY
        var oe = oldE
        for (i in 0 until count) {
            val x = s[i].toInt()
            val w = (x - ox).toShort().toInt()
            val u = oe + oy * 32100
            val sh = (u shr 15).toShort().toInt()
            val y = (w + sh).toShort().toInt()
            oe = u - (sh shl 15)
            ox = x
            oy = y
            s[i] = y.toShort()
        }
        oldX = ox; oldY = oy; oldE = oe
    }

    /** The half-band filter on the even positions (the I stream). */
    private fun fir(s: ShortArray, count: Int) {
        var fi = firIndex
        var i = 0
        while (i < count) {
            queue[fi] = s[i].toInt()
            var acc = 0
            for (j in 0 until len) acc += kernel[j] * queue[fi + j]
            if (--fi < 0) {
                fi = len * (SIZE_FACTOR - 1)
                System.arraycopy(queue, 0, queue, fi + 1, len - 1)
            }
            s[i] = (acc shr 15).toShort()
            i += 2
        }
        firIndex = fi
    }

    /** The odd positions (the Q stream) are only delayed, to line up with the filtered I stream. */
    private fun delay(s: ShortArray, count: Int) {
        val half = len shr 1
        var idx = delayIndex
        var i = 1
        while (i < count) {
            val res = delayLine[idx]
            delayLine[idx] = s[i]
            s[i] = res
            if (++idx >= half) idx = 0
            i += 2
        }
        delayIndex = idx
    }

    companion object {
        private const val SIZE_FACTOR = 16

        /** libairspy's 47-tap half-band kernel (HB_KERNEL_INT16). */
        val HB_KERNEL = shortArrayOf(
            -33, 0, 56, 0, -100, 0, 166, 0, -259, 0, 389, 0, -571, 0, 829, 0, -1220, 0, 1885, 0, -3353, 0, 10389, 16384,
            10389, 0, -3353, 0, 1885, 0, -1220, 0, 829, 0, -571, 0, 389, 0, -259, 0, 166, 0, -100, 0, 56, 0, -33,
        )
    }
}

/**
 * An Airspy R2 or Airspy Mini, receive only. The radio makes I/Q at 2.5 or 10 MS/s (R2) or 3 or 6 MS/s (Mini); the lowest
 * rate that is high enough is used and resampled to the rate the tool asked for. Ported from libairspy.
 */
class Airspy private constructor(
    private val conn: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val epIn: UsbEndpoint,
    private val rates: IntArray,
) : SdrRadio {
    /** Only the rates a phone can filter in real time are used (2.5 MS/s on an R2, 3 MS/s on a Mini). */
    private val usable = rates.filter { it <= 3_000_000 }
    override val caps = SdrCaps("Airspy", 24_000_000L, 1_750_000_000L, usable.maxOrNull() ?: 2_500_000)

    @Volatile private var running = false
    private var thread: Thread? = null
    private var rateIndex = 0
    private var inRate = 0
    private var resampler: IqResampler? = null
    private var lna = 0
    private var vga = 0
    private var amp = false

    private fun outReq(request: Int, value: Int, index: Int, data: ByteArray? = null): Boolean =
        conn.controlTransfer(TYPE_OUT, request, value, index, data, data?.size ?: 0, TIMEOUT_MS) >= (data?.size ?: 0)

    private fun inReq(request: Int, value: Int, index: Int, length: Int): ByteArray? {
        val b = ByteArray(length)
        val r = conn.controlTransfer(TYPE_IN, request, value, index, b, length, TIMEOUT_MS)
        return if (r < length) null else b
    }

    /** The lowest radio rate at or above [hz] (the next one up gives more samples to resample, for nothing). */
    override fun setSampleRate(hz: Int, filterHz: Int): Boolean {
        val i = rates.indices.filter { rates[it] >= hz && rates[it] in usable }.minByOrNull { rates[it] } ?: return false
        if (!IqResampler.possible(rates[i], hz)) return false
        rateIndex = i
        inRate = rates[i]
        resampler = IqResampler(inRate, hz)
        return true
    }

    override fun setFrequency(hz: Long): Boolean {
        if (hz < caps.minHz || hz > caps.maxHz) return false
        return outReq(REQ_SET_FREQ, 0, 0, byteArrayOf(hz.toByte(), (hz shr 8).toByte(), (hz shr 16).toByte(), (hz shr 24).toByte()))
    }

    /** The HackRF gain scale (up to 116 dB in all) spread over the Airspy's 22 sensitivity-optimised steps. */
    private fun applyGain(): Boolean {
        val total = lna + vga + (if (amp) 14 else 0)
        val step = (total * (GAIN_COUNT - 1) / 116).coerceIn(0, GAIN_COUNT - 1)
        val i = GAIN_COUNT - 1 - step
        return inReq(REQ_SET_MIXER_AGC, 0, 0, 1) != null && inReq(REQ_SET_LNA_AGC, 0, 0, 1) != null &&
            inReq(REQ_SET_VGA_GAIN, 0, SENS_VGA[i], 1) != null &&
            inReq(REQ_SET_MIXER_GAIN, 0, SENS_MIXER[i], 1) != null &&
            inReq(REQ_SET_LNA_GAIN, 0, SENS_LNA[i], 1) != null
    }

    override fun setLnaGain(db: Int): Boolean { lna = db.coerceIn(0, 40); return applyGain() }
    override fun setVgaGain(db: Int): Boolean { vga = db.coerceIn(0, 62); return applyGain() }
    override fun setAmp(on: Boolean): Boolean { amp = on; return applyGain() }
    override fun setAntennaPower(on: Boolean): Boolean = false

    override fun startRx(onSamples: (ByteArray, Int) -> Unit, onError: (String) -> Unit) {
        if (running) return
        val rs = resampler ?: run { onError(S(R.string.sdr_settings_refused)); return }
        if (inReq(REQ_SET_SAMPLERATE, 0, rateIndex, 1) == null) { onError(S(R.string.usb_error)); return }
        if (!outReq(REQ_RECEIVER_MODE, MODE_RX, 0)) { onError(S(R.string.usb_error)); return }
        running = true
        thread = Thread({
            val conv = AirspyIq().also { it.reset() }
            val reqs = List(TRANSFERS) { UsbRequest().apply { initialize(conn, epIn) } }
            val bufs = List(TRANSFERS) { ByteBuffer.allocateDirect(TRANSFER_SIZE).order(ByteOrder.LITTLE_ENDIAN) }
            val raw = ByteArray(TRANSFER_SIZE)
            val real = ShortArray(TRANSFER_SIZE / 2)
            val resampled = ShortArray(TRANSFER_SIZE * (rs.up + rs.down) / rs.down + 64)
            val out = ByteArray(resampled.size)
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
                    b.get(raw, 0, len)
                    b.clear()
                    if (running) r.queue(b)
                    val n = (len / 2) and 3.inv()
                    if (n == 0) continue
                    for (i in 0 until n) {
                        val v = (raw[2 * i].toInt() and 0xff) or ((raw[2 * i + 1].toInt() and 0xff) shl 8)
                        real[i] = ((v and 0xfff) - 2048 shl 4).toShort()
                    }
                    conv.process(real, n)
                    val m = rs.process(real, n, resampled)
                    for (i in 0 until m) out[i] = (resampled[i].toInt() shr 8).toByte()
                    if (m > 0) onSamples(out, m)
                }
            } catch (e: Exception) {
                if (running) onError(e.message ?: S(R.string.usb_error))
            } finally {
                reqs.forEach { runCatching { it.cancel(); it.close() } }
            }
        }, "airspy-rx").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    override fun close() {
        running = false
        runCatching { thread?.join(1500) }
        runCatching { outReq(REQ_RECEIVER_MODE, MODE_OFF, 0) }
        runCatching { conn.releaseInterface(iface) }
        runCatching { conn.close() }
    }

    companion object {
        const val VID = 0x1d50
        const val PID = 0x60a1
        private const val TYPE_OUT = UsbConstants.USB_TYPE_VENDOR or UsbConstants.USB_DIR_OUT
        private const val TYPE_IN = UsbConstants.USB_TYPE_VENDOR or UsbConstants.USB_DIR_IN
        private const val TIMEOUT_MS = 500
        private const val REQ_RECEIVER_MODE = 1
        private const val REQ_SET_SAMPLERATE = 12
        private const val REQ_SET_FREQ = 13
        private const val REQ_SET_LNA_GAIN = 14
        private const val REQ_SET_MIXER_GAIN = 15
        private const val REQ_SET_VGA_GAIN = 16
        private const val REQ_SET_LNA_AGC = 17
        private const val REQ_SET_MIXER_AGC = 18
        private const val REQ_GET_SAMPLERATES = 25
        private const val MODE_OFF = 0
        private const val MODE_RX = 1
        private const val TRANSFERS = 8
        private const val TRANSFER_SIZE = 262144
        private const val GAIN_COUNT = 22

        // libairspy's gain presets for sensitivity, from the highest gain (index 0 of the reversed scale) down.
        private val SENS_VGA = intArrayOf(13, 12, 11, 10, 9, 8, 7, 6, 5, 5, 5, 5, 5, 4, 4, 4, 4, 4, 4, 4, 4, 4)
        private val SENS_MIXER = intArrayOf(12, 12, 12, 12, 11, 10, 10, 9, 9, 8, 7, 4, 4, 4, 3, 2, 2, 1, 0, 0, 0, 0)
        private val SENS_LNA = intArrayOf(14, 14, 14, 14, 14, 14, 14, 14, 14, 13, 12, 12, 9, 9, 8, 7, 6, 5, 3, 2, 1, 0)

        fun isAirspy(d: UsbDevice) = d.vendorId == VID && d.productId == PID

        fun open(manager: UsbManager, device: UsbDevice): Airspy? {
            val iface = (0 until device.interfaceCount).map(device::getInterface).firstOrNull() ?: return null
            val ep = (0 until iface.endpointCount).map(iface::getEndpoint)
                .firstOrNull { it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK } ?: return null
            val conn = manager.openDevice(device) ?: return null
            if (!conn.claimInterface(iface, true)) { conn.close(); return null }
            fun inReq(request: Int, value: Int, index: Int, length: Int): ByteArray? {
                val b = ByteArray(length)
                val r = conn.controlTransfer(TYPE_IN, request, value, index, b, length, TIMEOUT_MS)
                return if (r < length) null else b
            }
            // The rates the firmware offers: first how many, then the list.
            val count = inReq(REQ_GET_SAMPLERATES, 0, 0, 4)?.let(::le32) ?: run { conn.close(); return null }
            if (count !in 1..16) { conn.close(); return null }
            val list = inReq(REQ_GET_SAMPLERATES, 0, count, count * 4) ?: run { conn.close(); return null }
            val rates = IntArray(count) { le32(list, it * 4) }
            return Airspy(conn, iface, ep, rates)
        }

        private fun le32(b: ByteArray, o: Int = 0) =
            (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
    }
}
