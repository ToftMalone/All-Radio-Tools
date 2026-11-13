package com.allnetworktools.ui.pages.sdr

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.allnetworktools.R
import com.allnetworktools.data.sdr.Emission
import com.allnetworktools.data.sdr.HackRf
import com.allnetworktools.data.sdr.SdrDevice
import com.allnetworktools.data.sdr.SdrRadio
import com.allnetworktools.data.sdr.SdrRepository
import com.allnetworktools.data.sdr.TxPolicy
import com.allnetworktools.data.sdr.TxSettings
import com.allnetworktools.data.sdr.TxStream
import com.allnetworktools.util.DebugLog
import com.allnetworktools.util.S
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A receiving tool that lends its radio for a transmission: reception pauses, then picks up where it was. */
interface RxHost {
    val activeRadio: SdrRadio?
    fun pauseRx()
    fun resumeRx()
}

/**
 * Sends one transmission at a time: checks the licence and the band, borrows the tool's radio (or opens it when the
 * tool is not receiving), transmits [TxStream] until it ends or [stop], then gives the radio back.
 */
class SdrTransmitter(private val repo: SdrRepository, private val scope: CoroutineScope) {
    var transmitting by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    @Volatile private var stopAsked = false

    /** Starts sending; false (with [error] set) when it may not. [onEnd] runs on the main thread afterwards. */
    fun send(device: SdrDevice?, host: RxHost?, emission: Emission, rate: Int, loHz: Long, stream: TxStream, onEnd: () -> Unit = {}): Boolean {
        if (transmitting) return false
        TxPolicy.refuse(emission)?.let { error = it; return false }
        val shared = host?.activeRadio
        if (shared == null && device == null) { error = S(R.string.no_hackrf_plugged_in); return false }
        error = null
        stopAsked = false
        transmitting = true
        scope.launch {
            var r: SdrRadio? = shared
            try {
                if (r == null) {
                    val d = device!!
                    if (!repo.hasPermission(d) && !repo.requestPermission(d)) { error = S(R.string.usb_access_to_the_hackrf_refused); return@launch }
                    r = withContext(Dispatchers.IO) { repo.open(d) } ?: run { error = S(R.string.could_not_open_the_hackrf); return@launch }
                }
                val radio = r!!
                if (!radio.canTransmit) { error = S(R.string.tx_radio_cannot); return@launch }
                val started = withContext(Dispatchers.IO) {
                    if (shared != null) host.pauseRx()
                    TxPolicy.declared = emission
                    radio.setSampleRate(rate, HackRf.filterFor(rate)) && radio.setFrequency(loHz) &&
                        radio.setTxGain(TxSettings.txGain) && radio.setAmp(TxSettings.txAmp) &&
                        radio.startTx(stream::fill) { msg -> error = msg; stopAsked = true }
                }
                if (!started) { if (error == null) error = S(R.string.tx_refused); return@launch }
                while (!stopAsked && !stream.finished) delay(20)
            } catch (e: Exception) {
                DebugLog.e("SDR", "Transmission failed", e)
                error = e.message
            } finally {
                val radio = r
                withContext(Dispatchers.IO) {
                    runCatching { radio?.stopStreaming() }
                    TxPolicy.declared = null
                    if (radio != null) {
                        if (shared != null) runCatching { host.resumeRx() } else runCatching { radio.close() }
                    }
                }
                transmitting = false
                onEnd()
            }
        }
        return true
    }

    fun stop() { stopAsked = true }
}
