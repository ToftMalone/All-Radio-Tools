package com.allnetworktools.data.sdr

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import com.allnetworktools.R
import com.allnetworktools.util.S

/** A HackRF-family board on the USB bus. */
data class SdrDevice(val name: String, val usb: UsbDevice?)

/**
 * HackRF USB protocol (greatscottgadgets/hackrf, host/libhackrf/src/hackrf.c): vendor control requests to
 * configure the radio, then interleaved signed 8-bit I/Q on bulk endpoint 0x81 while in receive mode.
 * Receive only: the transmit mode is never requested.
 */
class HackRf private constructor(
    private val conn: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val epIn: UsbEndpoint,
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    private fun out(request: Int, value: Int, index: Int, data: ByteArray? = null): Boolean =
        conn.controlTransfer(TYPE_OUT, request, value, index, data, data?.size ?: 0, TIMEOUT_MS) >= (data?.size ?: 0)

    private fun inp(request: Int, value: Int, index: Int, length: Int): ByteArray? {
        val b = ByteArray(length)
        val r = conn.controlTransfer(TYPE_IN, request, value, index, b, length, TIMEOUT_MS)
        return if (r < 0) null else b.copyOf(r)
    }

    private fun le32(vararg v: Long) = ByteArray(4 * v.size) { i -> (v[i / 4] shr (8 * (i % 4))).toByte() }

    fun boardId(): Int? = inp(REQ_BOARD_ID_READ, 0, 0, 1)?.firstOrNull()?.toInt()?.and(0xFF)

    fun version(): String? = inp(REQ_VERSION_STRING_READ, 0, 0, 255)?.toString(Charsets.US_ASCII)?.trim { it <= ' ' }

    fun setFrequency(hz: Long): Boolean = out(REQ_SET_FREQ, 0, 0, le32(hz / 1_000_000, hz % 1_000_000))

    /** Sample rate with divider 1, then the narrowest baseband filter (1.75 MHz) that passes it at 2 MS/s. */
    fun setSampleRate(hz: Int, filterHz: Int): Boolean =
        out(REQ_SAMPLE_RATE_SET, 0, 0, le32(hz.toLong(), 1)) && out(REQ_BASEBAND_FILTER_BANDWIDTH_SET, filterHz and 0xFFFF, filterHz ushr 16)

    /** LNA (IF) gain, 0–40 dB in 8 dB steps. */
    fun setLnaGain(db: Int): Boolean = inp(REQ_SET_LNA_GAIN, 0, db.coerceIn(0, 40) and 0x07.inv(), 1)?.firstOrNull()?.toInt() != 0

    /** VGA (baseband) gain, 0–62 dB in 2 dB steps. */
    fun setVgaGain(db: Int): Boolean = inp(REQ_SET_VGA_GAIN, 0, db.coerceIn(0, 62) and 0x01.inv(), 1)?.firstOrNull()?.toInt() != 0

    /** The 14 dB RF front-end amplifier. */
    fun setAmp(on: Boolean): Boolean = out(REQ_AMP_ENABLE, if (on) 1 else 0, 0)

    /** 3.3 V on the antenna port (bias tee), to power an active antenna such as a GNSS one. */
    fun setAntennaPower(on: Boolean): Boolean = out(REQ_ANTENNA_ENABLE, if (on) 1 else 0, 0)

    /**
     * Starts receiving: [onSamples] gets each USB transfer on a dedicated thread. [onError] fires once if the
     * board stops answering (unplugged).
     */
    fun startRx(onSamples: (ByteArray, Int) -> Unit, onError: (String) -> Unit) {
        if (running) return
        if (!out(REQ_SET_TRANSCEIVER_MODE, MODE_RECEIVE, 0)) { onError(S(R.string.the_hackrf_refuses_receive_mode)); return }
        running = true
        thread = Thread({
            val reqs = List(TRANSFERS) { UsbRequest().apply { initialize(conn, epIn) } }
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
                    val i = r.clientData as Int
                    val b = bufs[i]
                    val len = b.position()
                    b.flip()
                    b.get(copy, 0, len)
                    b.clear()
                    if (running) r.queue(b)
                    if (len > 0) onSamples(copy, len)
                }
            } catch (e: Exception) {
                if (running) onError(e.message ?: S(R.string.usb_error))
            } finally {
                reqs.forEach { runCatching { it.cancel(); it.close() } }
            }
        }, "hackrf-rx").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    fun close() {
        running = false
        runCatching { thread?.join(1500) }
        runCatching { out(REQ_SET_TRANSCEIVER_MODE, MODE_OFF, 0) }
        runCatching { setAntennaPower(false) }
        runCatching { conn.releaseInterface(iface) }
        runCatching { conn.close() }
    }

    companion object {
        const val VID = 0x1d50
        private val PIDS = mapOf(0x6089 to "HackRF One", 0x604b to "HackRF Jawbreaker", 0xcc15 to "rad1o")

        private const val TYPE_OUT = UsbConstants.USB_TYPE_VENDOR or UsbConstants.USB_DIR_OUT // recipient: device
        private const val TYPE_IN = UsbConstants.USB_TYPE_VENDOR or UsbConstants.USB_DIR_IN
        private const val TIMEOUT_MS = 500
        private const val REQ_SET_TRANSCEIVER_MODE = 1
        private const val REQ_SAMPLE_RATE_SET = 6
        private const val REQ_BASEBAND_FILTER_BANDWIDTH_SET = 7
        private const val REQ_BOARD_ID_READ = 14
        private const val REQ_VERSION_STRING_READ = 15
        private const val REQ_SET_FREQ = 16
        private const val REQ_AMP_ENABLE = 17
        private const val REQ_SET_LNA_GAIN = 19
        private const val REQ_SET_VGA_GAIN = 20
        private const val REQ_ANTENNA_ENABLE = 23
        private const val MODE_OFF = 0
        private const val MODE_RECEIVE = 1
        private const val TRANSFERS = 4
        private const val TRANSFER_SIZE = 131072

        private val FILTERS = intArrayOf(
            1_750_000, 2_500_000, 3_500_000, 5_000_000, 5_500_000, 6_000_000, 7_000_000, 8_000_000, 9_000_000,
            10_000_000, 12_000_000, 14_000_000, 15_000_000, 20_000_000, 24_000_000, 28_000_000,
        )

        /** libhackrf's rule: the widest MAX2837 baseband filter no wider than 75 % of the sample rate. */
        fun filterFor(sampleRateHz: Int): Int = FILTERS.lastOrNull { it <= sampleRateHz * 3L / 4 } ?: FILTERS.first()

        fun isHackRf(d: UsbDevice) = d.vendorId == VID && d.productId in PIDS

        fun name(d: UsbDevice) = PIDS[d.productId] ?: "HackRF"

        /** Board ids returned by BOARD_ID_READ. */
        fun boardName(id: Int?): String? = when (id) {
            0 -> "Jellybean"; 1 -> "Jawbreaker"; 2 -> "HackRF One (r1–r5)"; 3 -> "rad1o"; 4 -> "HackRF One (r9)"; 5 -> "HackRF Pro (Praline)"
            else -> null
        }

        fun open(manager: UsbManager, device: UsbDevice): HackRf? {
            val iface = (0 until device.interfaceCount).map(device::getInterface).firstOrNull() ?: return null
            val ep = (0 until iface.endpointCount).map(iface::getEndpoint)
                .firstOrNull { it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK } ?: return null
            val conn = manager.openDevice(device) ?: return null
            if (!conn.claimInterface(iface, true)) { conn.close(); return null }
            return HackRf(conn, iface, ep)
        }
    }
}

/** Finds HackRF boards, follows plug/unplug and asks for USB access. */
open class SdrRepository(private val context: Context) {
    private val usb: UsbManager? = context.getSystemService(UsbManager::class.java)

    private fun find(): SdrDevice? = usb?.deviceList?.values?.firstOrNull(HackRf::isHackRf)?.let { SdrDevice(HackRf.name(it), it) }

    /** The plugged-in HackRF, or null. */
    open val device: Flow<SdrDevice?> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { trySend(find()) }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        // Attach broadcasts are not guaranteed on every Android build: poll as a fallback.
        val poll = launch { while (true) { trySend(find()); delay(2000) } }
        awaitClose {
            poll.cancel()
            runCatching { context.unregisterReceiver(receiver) }
        }
    }.distinctUntilChanged { a, b -> a?.usb?.deviceName == b?.usb?.deviceName }

    open fun hasPermission(d: SdrDevice): Boolean = d.usb != null && usb?.hasPermission(d.usb) == true

    /** Shows Android's "allow access to the HackRF" dialog; true when granted. */
    open suspend fun requestPermission(d: SdrDevice): Boolean = suspendCancellableCoroutine { cont ->
        val dev = d.usb ?: return@suspendCancellableCoroutine cont.resume(false)
        val m = usb ?: return@suspendCancellableCoroutine cont.resume(false)
        val action = context.packageName + ".USB_PERMISSION"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                runCatching { context.unregisterReceiver(this) }
                if (cont.isActive) cont.resume(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        val pi = PendingIntent.getBroadcast(context, 0, Intent(action).setPackage(context.packageName), PendingIntent.FLAG_MUTABLE)
        m.requestPermission(dev, pi)
        cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
    }

    open fun open(d: SdrDevice): HackRf? = d.usb?.let { dev -> usb?.let { HackRf.open(it, dev) } }
}
