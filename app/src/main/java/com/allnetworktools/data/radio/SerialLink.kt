package com.allnetworktools.data.radio

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.ByteArrayOutputStream
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

/** A byte pipe to a radio: a USB programming cable in the app, a simulated radio in the tests. */
interface SerialLink : java.io.Closeable {
    fun write(data: ByteArray)

    /** Up to [count] bytes, waiting at most [timeoutMs] in total; fewer (possibly none) when the radio goes quiet. */
    fun read(count: Int, timeoutMs: Int): ByteArray

    /** Drops whatever the radio sent that nobody read yet. */
    fun purge()
}

/** A programming cable on the USB bus: the chip inside it, and the radio-side name of that chip. */
data class RadioCable(val name: String, val usb: UsbDevice?, internal val driver: UsbSerialDriver? = null)

/** [SerialLink] over a USB-serial chip (FTDI, CH340, CP210x, PL2303, CDC-ACM). */
class UsbSerialLink(private val port: UsbSerialPort) : SerialLink {
    private val pending = ArrayDeque<Byte>()
    private val buf = ByteArray(512)

    override fun write(data: ByteArray) {
        // One short chunk at a time keeps cheap bridges from overrunning the radio's slow UART.
        var o = 0
        while (o < data.size) {
            val n = minOf(CHUNK, data.size - o)
            port.write(data.copyOfRange(o, o + n), WRITE_TIMEOUT_MS)
            o += n
        }
    }

    override fun read(count: Int, timeoutMs: Int): ByteArray {
        val out = ByteArrayOutputStream(count)
        val end = System.nanoTime() + timeoutMs * 1_000_000L
        while (out.size() < count) {
            while (pending.isNotEmpty() && out.size() < count) out.write(pending.removeFirst().toInt())
            if (out.size() >= count) break
            val left = ((end - System.nanoTime()) / 1_000_000L).toInt()
            if (left <= 0) break
            val n = port.read(buf, minOf(left, SLICE_MS))
            for (i in 0 until n) pending.addLast(buf[i])
        }
        return out.toByteArray()
    }

    override fun purge() {
        pending.clear()
        runCatching { port.purgeHwBuffers(true, true) }
        // Whatever the chip had already queued.
        runCatching { while (port.read(buf, 20) > 0) Unit }
    }

    override fun close() {
        runCatching { port.close() }
    }

    private companion object {
        const val CHUNK = 64
        const val WRITE_TIMEOUT_MS = 2000
        const val SLICE_MS = 100
    }
}

/** Finds programming cables, follows plug/unplug and asks for USB access. */
open class RadioCableRepository(private val context: Context) {
    private val usb: UsbManager? = context.getSystemService(UsbManager::class.java)

    private fun find(): RadioCable? = usb?.let { m ->
        UsbSerialProber.getDefaultProber().findAllDrivers(m).firstOrNull()?.let { RadioCable(chipName(it.device), it.device, it) }
    }

    /** The plugged-in programming cable, or null. */
    open val cable: Flow<RadioCable?> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { trySend(find()) }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        val poll = launch { while (true) { trySend(find()); delay(2000) } }
        awaitClose {
            poll.cancel()
            runCatching { context.unregisterReceiver(receiver) }
        }
    }.distinctUntilChanged { a, b -> a?.usb?.deviceName == b?.usb?.deviceName }

    open fun hasPermission(c: RadioCable): Boolean = c.usb != null && usb?.hasPermission(c.usb) == true

    /** Shows Android's "allow access to the USB device" dialog; true when granted. */
    open suspend fun requestPermission(c: RadioCable): Boolean = suspendCancellableCoroutine { cont ->
        val dev = c.usb ?: return@suspendCancellableCoroutine cont.resume(false)
        val m = usb ?: return@suspendCancellableCoroutine cont.resume(false)
        val action = context.packageName + ".CABLE_PERMISSION"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                runCatching { context.unregisterReceiver(this) }
                if (cont.isActive) cont.resume(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        val pi = PendingIntent.getBroadcast(context, 1, Intent(action).setPackage(context.packageName), PendingIntent.FLAG_MUTABLE)
        m.requestPermission(dev, pi)
        cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
    }

    /** Opens the cable at [baud] 8N1; null when Android refuses or the chip does not answer. */
    open fun open(c: RadioCable, baud: Int): SerialLink? {
        val m = usb ?: return null
        val driver = c.driver ?: return null
        val conn = m.openDevice(driver.device) ?: return null
        val port = driver.ports.firstOrNull() ?: return null
        return try {
            port.open(conn)
            port.setParameters(baud, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            UsbSerialLink(port).also { it.purge() }
        } catch (e: Exception) {
            runCatching { port.close() }
            null
        }
    }

    private fun chipName(d: UsbDevice) = when (d.vendorId) {
        0x0403 -> "FTDI"
        0x067b -> "Prolific PL2303"
        0x10c4 -> "Silicon Labs CP210x"
        0x1a86 -> "WCH CH340"
        else -> d.productName ?: S(R.string.usb_serial)
    }
}
