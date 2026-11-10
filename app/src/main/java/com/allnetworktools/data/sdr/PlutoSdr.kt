package com.allnetworktools.data.sdr

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.allnetworktools.R
import com.allnetworktools.util.S

/**
 * The text protocol of iiod (libiio, LGPL-2.1+) over some byte pipe: commands end in "\r\n" and are answered by an
 * integer line (a length, or a negative errno), followed by the payload when there is one. The pipe delivers the bytes in
 * whatever pieces the USB transfers come in; this class puts them back together.
 */
class IiodClient(private val read: (ByteArray, Int) -> Int, private val write: (ByteArray, Int) -> Int) {
    private var buf = ByteArray(1 shl 16)
    private var start = 0
    private var end = 0

    private fun fill(): Boolean {
        if (start == end) { start = 0; end = 0 }
        if (end + 16384 > buf.size) {
            if (start > 0) { System.arraycopy(buf, start, buf, 0, end - start); end -= start; start = 0 }
            if (end + 16384 > buf.size) buf = buf.copyOf(buf.size * 2)
        }
        val tmp = ByteArray(16384)
        val n = read(tmp, tmp.size)
        if (n <= 0) return false
        System.arraycopy(tmp, 0, buf, end, n)
        end += n
        return true
    }

    private fun send(bytes: ByteArray): Boolean {
        var off = 0
        while (off < bytes.size) {
            val chunk = bytes.copyOfRange(off, minOf(bytes.size, off + 16384))
            val n = write(chunk, chunk.size)
            if (n <= 0) return false
            off += n
        }
        return true
    }

    /** The next integer line, skipping blank lines; null when the pipe fails. */
    private fun readInteger(): Int? {
        while (true) {
            var i = start
            while (i < end && (buf[i] == '\n'.code.toByte() || buf[i] == '\r'.code.toByte())) i++
            start = i
            var j = start
            while (j < end && buf[j] != '\n'.code.toByte()) j++
            if (j < end) {
                val s = String(buf, start, j - start, Charsets.US_ASCII).trim()
                start = j + 1
                return s.toIntOrNull()
            }
            if (!fill()) return null
        }
    }

    /** Exactly [n] bytes of payload. */
    fun readBytes(n: Int, dst: ByteArray, off: Int): Boolean {
        var got = 0
        while (got < n) {
            if (start == end && !fill()) return false
            val c = minOf(n - got, end - start)
            System.arraycopy(buf, start, dst, off + got, c)
            start += c
            got += c
        }
        return true
    }

    /** Sends [command] and returns the integer answer, or null when the pipe fails. */
    fun exec(command: String): Int? = if (send((command + "\r\n").toByteArray())) readInteger() else null

    /** `WRITE <device> [INPUT|OUTPUT <channel>] <attr> <len>` then the value; true when the device accepted it. */
    fun writeAttr(device: String, channelKind: String?, channel: String?, attr: String, value: String): Boolean {
        val v = value.toByteArray()
        val head = if (channel != null) "WRITE $device $channelKind $channel $attr ${v.size}\r\n" else "WRITE $device $attr ${v.size}\r\n"
        if (!send(head.toByteArray()) || !send(v)) return false
        return (readInteger() ?: -1) >= 0
    }

    /** The XML description of the context (`PRINT`). */
    fun print(): String? {
        val n = exec("PRINT") ?: return null
        if (n <= 0) return null
        val b = ByteArray(n)
        return if (readBytes(n, b, 0)) String(b, Charsets.UTF_8) else null
    }

    /** `OPEN <device> <samples> <mask>`; true when the buffer is open. */
    fun open(device: String, samples: Int, mask: Int): Boolean =
        exec("OPEN $device $samples ${"%08x".format(mask)}") == 0

    fun close(device: String) { exec("CLOSE $device") }

    /**
     * `READBUF <device> <bytes>`: the device answers with chunks, each preceded by its length, and the channel mask
     * once. Fills [dst] with [len] bytes; false on error.
     */
    fun readBuffer(device: String, len: Int, dst: ByteArray): Boolean {
        if (!send("READBUF $device $len\r\n".toByteArray())) return false
        var got = 0
        var maskRead = false
        while (got < len) {
            val toRead = readInteger() ?: return false
            if (toRead < 0) return false
            if (toRead == 0) break
            if (!maskRead) {
                // 8 hex digits for the mask of up to 32 channels, and a newline.
                val m = ByteArray(9)
                if (!readBytes(9, m, 0)) return false
                maskRead = true
            }
            if (!readBytes(minOf(toRead, len - got), dst, got)) return false
            got += minOf(toRead, len - got)
        }
        return got == len
    }

    companion object {
        /** Device ids and names from the XML of `PRINT`. */
        fun devices(xml: String): Map<String, String> =
            Regex("""<device id="([^"]+)" name="([^"]+)"""").findAll(xml).associate { it.groupValues[2] to it.groupValues[1] }
    }
}

/**
 * An ADALM-Pluto (PlutoSDR, AD9363), receive only, reached through its USB "IIO" interface with the iiod text protocol:
 * no network is needed. The radio samples at 2.1 MS/s or more, so the tool's rate is made by resampling a multiple of it.
 */
class PlutoSdr private constructor(
    private val conn: UsbDeviceConnection,
    private val iface: UsbInterface,
    epIn: UsbEndpoint,
    epOut: UsbEndpoint,
    private val phy: String,
    private val rx: String,
) : SdrRadio {
    override val caps = SdrCaps("PlutoSDR", 70_000_000L, 6_000_000_000L, 5_000_000)

    private val lock = Any()
    private val iiod = IiodClient(
        read = { b, n -> conn.bulkTransfer(epIn, b, n, READ_TIMEOUT_MS) },
        write = { b, n -> conn.bulkTransfer(epOut, b, n, 2000) },
    )
    @Volatile private var running = false
    private var thread: Thread? = null
    private var resampler: IqResampler? = null
    private var lna = 0
    private var vga = 0
    private var amp = false

    private fun attr(channel: String, kind: String, name: String, value: String) =
        synchronized(lock) { iiod.writeAttr(phy, kind, channel, name, value) }

    override fun setSampleRate(hz: Int, filterHz: Int): Boolean {
        // The lowest multiple of the tool's rate that the AD9363 can make.
        var k = 1
        while (hz * k < 2_100_000) k++
        val radioRate = hz * k
        if (radioRate > 5_000_000 || !IqResampler.possible(radioRate, hz)) return false
        resampler = IqResampler(radioRate, hz)
        return attr("voltage0", "INPUT", "sampling_frequency", radioRate.toString()) &&
            attr("voltage0", "INPUT", "rf_bandwidth", minOf(radioRate, maxOf(hz, 200_000)).toString())
    }

    override fun setFrequency(hz: Long): Boolean = hz in caps.minHz..caps.maxHz && attr("altvoltage0", "OUTPUT", "frequency", hz.toString())

    /** The HackRF gain scale (up to 116 dB) spread over the AD9363's 0 to 73 dB; manual gain. */
    private fun applyGain(): Boolean {
        val total = lna + vga + (if (amp) 14 else 0)
        val db = (total * 73 / 116).coerceIn(0, 73)
        return attr("voltage0", "INPUT", "gain_control_mode", "manual") && attr("voltage0", "INPUT", "hardwaregain", db.toString())
    }

    /** [value] in dB, 0 to 73, manual. */
    override fun setNativeGain(value: Int): Boolean =
        attr("voltage0", "INPUT", "gain_control_mode", "manual") && attr("voltage0", "INPUT", "hardwaregain", value.coerceIn(0, 73).toString())

    override fun setLnaGain(db: Int): Boolean { lna = db.coerceIn(0, 40); return applyGain() }
    override fun setVgaGain(db: Int): Boolean { vga = db.coerceIn(0, 62); return applyGain() }
    override fun setAmp(on: Boolean): Boolean { amp = on; return applyGain() }
    override fun setAntennaPower(on: Boolean): Boolean = false

    override fun startRx(onSamples: (ByteArray, Int) -> Unit, onError: (String) -> Unit) {
        if (running) return
        val rs = resampler ?: run { onError(S(R.string.sdr_settings_refused)); return }
        val opened = synchronized(lock) { iiod.open(rx, SAMPLES, 0x3) }
        if (!opened) { onError(S(R.string.usb_error)); return }
        running = true
        thread = Thread({
            val raw = ByteArray(SAMPLES * 4)
            val shorts = ShortArray(SAMPLES * 2)
            val resampled = ShortArray(SAMPLES * 2 * (rs.up + rs.down) / rs.down + 64)
            val out = ByteArray(resampled.size)
            try {
                while (running) {
                    val ok = synchronized(lock) { iiod.readBuffer(rx, raw.size, raw) }
                    if (!ok) { if (running) onError(S(R.string.usb_link_lost)); break }
                    for (i in shorts.indices) shorts[i] = ((raw[2 * i].toInt() and 0xff) or (raw[2 * i + 1].toInt() shl 8)).toShort()
                    val m = rs.process(shorts, shorts.size, resampled)
                    // 12-bit samples in 16 bits: 4 bits down to the signed 8-bit range of the other radios.
                    for (i in 0 until m) out[i] = (resampled[i].toInt() shr 4).coerceIn(-128, 127).toByte()
                    if (m > 0) onSamples(out, m)
                }
            } catch (e: Exception) {
                if (running) onError(e.message ?: S(R.string.usb_error))
            }
        }, "pluto-rx").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    override fun close() {
        running = false
        runCatching { thread?.join(3000) }
        runCatching { synchronized(lock) { iiod.close(rx) } }
        runCatching { conn.controlTransfer(TYPE_PIPE, CMD_CLOSE_PIPE, 0, iface.id, null, 0, 1000) }
        runCatching { conn.releaseInterface(iface) }
        runCatching { conn.close() }
    }

    companion object {
        const val VID = 0x0456
        private const val SAMPLES = 32768
        private const val READ_TIMEOUT_MS = 5000
        private const val TYPE_PIPE = UsbConstants.USB_TYPE_VENDOR or 0x01 // recipient: interface
        private const val CMD_RESET_PIPES = 0
        private const val CMD_OPEN_PIPE = 1
        private const val CMD_CLOSE_PIPE = 2

        private fun iioInterface(d: UsbDevice): UsbInterface? =
            (0 until d.interfaceCount).map(d::getInterface).firstOrNull { it.name == "IIO" }

        fun isPluto(d: UsbDevice) = d.vendorId == VID && iioInterface(d) != null

        fun open(manager: UsbManager, device: UsbDevice): PlutoSdr? {
            val iface = iioInterface(device) ?: return null
            // The endpoints come in pairs, input then output: the first pair is pipe 0.
            val eps = (0 until iface.endpointCount).map(iface::getEndpoint)
            if (eps.size < 2 || eps[0].direction != UsbConstants.USB_DIR_IN || eps[1].direction != UsbConstants.USB_DIR_OUT) return null
            val conn = manager.openDevice(device) ?: return null
            if (!conn.claimInterface(iface, true)) { conn.close(); return null }
            fun fail(): PlutoSdr? { conn.releaseInterface(iface); conn.close(); return null }
            if (conn.controlTransfer(TYPE_PIPE, CMD_RESET_PIPES, 0, iface.id, null, 0, 1000) < 0) return fail()
            if (conn.controlTransfer(TYPE_PIPE, CMD_OPEN_PIPE, 0, iface.id, null, 0, 1000) < 0) return fail()
            val epIn = eps[0]
            val epOut = eps[1]
            val c = IiodClient({ b, n -> conn.bulkTransfer(epIn, b, n, READ_TIMEOUT_MS) }, { b, n -> conn.bulkTransfer(epOut, b, n, 2000) })
            c.exec("TIMEOUT 10000")
            val devs = c.print()?.let(IiodClient::devices) ?: return fail()
            val phy = devs["ad9361-phy"] ?: return fail()
            val rx = devs["cf-ad9361-lpc"] ?: return fail()
            // The probing client has read the context; the radio's own client starts from a clean stream.
            return PlutoSdr(conn, iface, epIn, epOut, phy, rx)
        }
    }
}
