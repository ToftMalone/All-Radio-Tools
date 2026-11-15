package com.allnetworktools.data.sdr

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbRequest
import java.util.concurrent.TimeoutException

/**
 * Ends a stream's USB requests so the next stream on the same connection starts clean. A cancelled request still
 * completes on the connection: closed before that, its completion waits there, and the next stream's
 * [UsbDeviceConnection.requestWait] picks it up with no endpoint (a NullPointerException in Android, the transmit
 * error of a radio that was receiving). So the requests are cancelled, their completions collected, then closed.
 */
internal fun UsbDeviceConnection.retire(reqs: List<UsbRequest>) {
    reqs.forEach { runCatching { it.cancel() } }
    var left = reqs.size + 2
    while (left-- > 0) {
        val r = try { requestWait(150) } catch (_: TimeoutException) { break } catch (_: Exception) { continue }
        if (r == null) break
    }
    reqs.forEach { runCatching { it.close() } }
}

/**
 * [UsbDeviceConnection.requestWait] that passes over a completion left by an earlier stream (one Android can no
 * longer match to its endpoint) instead of failing on it.
 */
internal fun UsbDeviceConnection.waitOwn(timeoutMs: Long): UsbRequest? {
    var stale = 0
    while (true) {
        try {
            return requestWait(timeoutMs)
        } catch (e: NullPointerException) {
            if (++stale > 16) throw e
        }
    }
}
