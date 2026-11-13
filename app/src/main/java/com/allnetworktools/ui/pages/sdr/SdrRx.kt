package com.allnetworktools.ui.pages.sdr

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.allnetworktools.R
import com.allnetworktools.data.sdr.HackRf
import com.allnetworktools.data.sdr.SdrDevice
import com.allnetworktools.data.sdr.SdrRadio
import com.allnetworktools.data.sdr.SdrRepository
import com.allnetworktools.util.S
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a tool does with the samples, on its DSP thread: [feed] for each buffer, [tick] about once a second, [close] at the end. */
interface SdrSink {
    fun feed(buf: ByteArray, len: Int)
    fun tick(nowMs: Long) {}
    /** Once reception has stopped, on the DSP thread. */
    fun close() {}
}

/**
 * The part every receive-only HackRF tool shares: opening the radio, tuning, gains (automatic by default), the buffer
 * pool between the USB thread and a DSP thread, and stopping. A tool gives its rate, where to tune and a fresh [SdrSink]
 * per start, built on the main thread.
 */
abstract class SdrRxController(
    private val repo: SdrRepository,
    protected val scope: CoroutineScope,
    private val onAcquire: () -> Unit,
    val gain: SdrGain,
    private val threadName: String,
) : RxHost {
    var running by mutableStateOf(false)
        private set
    var starting by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var radio: SdrRadio? = null
    @Volatile private var dspRunning = false
    @Volatile private var tunedHz = 0L
    private var rxSamples: ((ByteArray, Int) -> Unit)? = null
    private var rxError: ((String) -> Unit)? = null

    /** Transmissions from this tool, with its radio lent while receiving. */
    val tx by lazy { SdrTransmitter(repo, scope) }

    override val activeRadio: SdrRadio? get() = radio

    override fun pauseRx() { radio?.stopStreaming() }

    /** Back to receiving after a transmission: the rate, frequency and gains are set again. */
    override fun resumeRx() {
        val r = radio ?: return
        val rate = sampleRate
        r.setSampleRate(rate, HackRf.filterFor(rate))
        r.setFrequency(tunedHz)
        r.setLnaGain(gain.lna); r.setVgaGain(gain.vga); r.setAmp(gain.amp == true)
        val s = rxSamples ?: return
        val e = rxError ?: return
        r.startRx(s, e)
    }

    protected abstract val sampleRate: Int
    protected abstract fun tuneHz(): Long
    protected abstract fun newSink(): SdrSink

    fun start(device: SdrDevice) {
        if (running || starting) return
        onAcquire()
        error = null
        starting = true
        scope.launch {
            try {
                if (!repo.hasPermission(device) && !repo.requestPermission(device)) { error = S(R.string.usb_access_to_the_hackrf_refused); return@launch }
                val r = withContext(Dispatchers.IO) { repo.open(device) } ?: run { error = S(R.string.could_not_open_the_hackrf); return@launch }
                val lna = gain.lna
                val vga = gain.vga
                val withAmp = gain.amp == true
                val rate = sampleRate
                val hz = tuneHz()
                tunedHz = hz
                val ok = withContext(Dispatchers.IO) {
                    r.setSampleRate(rate, HackRf.filterFor(rate)) && r.setFrequency(hz) &&
                        r.setLnaGain(lna) && r.setVgaGain(vga) && r.setAmp(withAmp)
                }
                if (!ok) { r.close(); error = S(R.string.sdr_settings_refused); return@launch }
                radio = r
                startPipeline(r, newSink())
                running = true
            } finally {
                starting = false
            }
        }
    }

    private fun startPipeline(r: SdrRadio, sink: SdrSink) {
        val free = ArrayBlockingQueue<ByteArray>(POOL)
        val full = ArrayBlockingQueue<Pair<ByteArray, Int>>(POOL)
        repeat(POOL) { free.add(ByteArray(131072)) }
        dspRunning = true
        Thread({
            var last = 0L
            while (dspRunning) {
                val item = full.poll(200, TimeUnit.MILLISECONDS)
                if (item != null) {
                    sink.feed(item.first, item.second)
                    free.offer(item.first)
                }
                val now = System.currentTimeMillis()
                if (now - last >= 1000) { last = now; sink.tick(now) }
            }
            sink.close()
        }, threadName).start()
        gain.attach { applyGains() }
        val samples: (ByteArray, Int) -> Unit = { data, len -> gain.feed(data, len); free.poll()?.let { b -> System.arraycopy(data, 0, b, 0, len); full.offer(b to len) } }
        val failed: (String) -> Unit = { msg -> scope.launch { error = msg; stop() } }
        rxSamples = samples
        rxError = failed
        r.startRx(samples, failed)
    }

    fun applyGains() {
        val r = radio ?: return
        val lna = gain.lna
        val vga = gain.vga
        val withAmp = gain.amp == true
        scope.launch(Dispatchers.IO) { r.setLnaGain(lna); r.setVgaGain(vga); r.setAmp(withAmp) }
    }

    /** Retunes while running, for tools that hop between channels. */
    protected fun retune(hz: Long) {
        tunedHz = hz
        val r = radio ?: return
        scope.launch(Dispatchers.IO) { r.setFrequency(hz) }
    }

    fun stop() {
        if (tx.transmitting) tx.stop()
        dspRunning = false
        val r = radio
        radio = null
        running = false
        if (r != null) scope.launch(Dispatchers.IO) { r.close() }
    }

    /** Publishes DSP results on the main thread. */
    protected fun post(block: () -> Unit) { scope.launch { block() } }

    internal fun markRunningForTest() { running = true }

    companion object {
        private const val POOL = 32
    }
}
