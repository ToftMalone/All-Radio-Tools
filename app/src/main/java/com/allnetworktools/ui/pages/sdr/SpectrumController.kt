package com.allnetworktools.ui.pages.sdr

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.allnetworktools.data.sdr.HackRf
import com.allnetworktools.data.sdr.SdrDevice
import com.allnetworktools.data.sdr.SdrRepository
import com.allnetworktools.data.sdr.SpectrumAnalyzer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.allnetworktools.R
import com.allnetworktools.util.S

/** A one-tap starting point on the band plan. */
enum class SpectrumPreset(val label: String, val centerMhz: Double, val spanMhz: Int) {
    Fm(S(R.string.fm_radio), 98.0, 20),
    Air(S(R.string.aviation), 127.5, 20),
    Ham2m(S(R.string.n_2_m_amateur_radio), 145.0, 5),
    Meteor("Meteor-M 137 MHz", 137.5, 2),
    Aprs("APRS 144,800", 144.8, 2),
    Marine(S(R.string.marine_ais), 162.0, 2),
    Sondes(S(R.string.weather_sondes), 403.0, 5),
    Ism433("433 MHz", 433.92, 2),
    Ism868("868 MHz", 868.3, 2),
    Adsb("ADS-B 1090", 1090.0, 2),
    Wifi(S(R.string.wi_fi_2_4_ghz), 2442.0, 20),
}

/**
 * Live spectrum and waterfall from the HackRF. Only a few FFTs per USB transfer are computed: the display needs
 * an average, not every sample, which keeps wide spans cheap on a phone.
 */
class SpectrumController(private val repo: SdrRepository, private val scope: CoroutineScope, private val onAcquire: () -> Unit = {}) {
    var centerMhz by mutableStateOf("98.000")
    var spanMhz by mutableIntStateOf(10)
    val gain = SdrGain(listOf(0, 8, 16, 24, 32, 40), 24, listOf(10, 20, 30, 40), 20, amp = false)
    var lnaGain by gain::lna
    var vgaGain by gain::vga
    var amp: Boolean
        get() = gain.amp == true
        set(v) { gain.amp = v }
    var peakHold by mutableStateOf(false)

    var running by mutableStateOf(false)
        private set
    var starting by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var tunedHz by mutableLongStateOf(0L)
        private set
    var spectrum by mutableStateOf<FloatArray?>(null)
        private set
    var hold by mutableStateOf<FloatArray?>(null)
        private set
    var frames by mutableIntStateOf(0)
        private set

    val waterfall: Bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).apply { eraseColor(COLD) }
    private val pixels = IntArray(WIDTH * HEIGHT) { COLD }

    private var radio: HackRf? = null
    private var dsp: Thread? = null
    @Volatile private var dspRunning = false

    fun parsedCenterHz(): Long? = centerMhz.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in 1.0..6000.0 }?.let { (it * 1e6).toLong() }

    fun start(device: SdrDevice) {
        if (running || starting) return
        val hz = parsedCenterHz() ?: run { error = S(R.string.invalid_frequency_1_to_6000_mhz); return }
        onAcquire()
        error = null
        starting = true
        scope.launch {
            try {
                if (!repo.hasPermission(device) && !repo.requestPermission(device)) { error = S(R.string.usb_access_to_the_hackrf_refused); return@launch }
                val r = withContext(Dispatchers.IO) { repo.open(device) } ?: run { error = S(R.string.could_not_open_the_hackrf); return@launch }
                val rate = spanMhz * 1_000_000
                val lna = lnaGain
                val vga = vgaGain
                val withAmp = amp
                val ok = withContext(Dispatchers.IO) {
                    r.setSampleRate(rate, HackRf.filterFor(rate)) && r.setFrequency(hz) && r.setLnaGain(lna) && r.setVgaGain(vga) && r.setAmp(withAmp)
                }
                if (!ok) { r.close(); error = S(R.string.the_hackrf_did_not_accept_the); return@launch }
                radio = r
                tunedHz = hz
                hold = null
                startPipeline(r)
                running = true
            } finally {
                starting = false
            }
        }
    }

    /** Changes the centre frequency without restarting the stream. */
    fun retune(hz: Long) {
        val r = radio ?: run { centerMhz = "%.3f".format(java.util.Locale.US, hz / 1e6); return }
        centerMhz = "%.3f".format(java.util.Locale.US, hz / 1e6)
        hold = null
        scope.launch {
            if (withContext(Dispatchers.IO) { r.setFrequency(hz) }) tunedHz = hz else error = S(R.string.frequency_refused_by_the_hackrf)
        }
    }

    fun applyGains() {
        val r = radio ?: return
        val lna = lnaGain
        val vga = vgaGain
        val withAmp = amp
        scope.launch(Dispatchers.IO) { r.setLnaGain(lna); r.setVgaGain(vga); r.setAmp(withAmp) }
    }

    private fun startPipeline(r: HackRf) {
        val free = ArrayBlockingQueue<ByteArray>(POOL)
        val full = ArrayBlockingQueue<Pair<ByteArray, Int>>(POOL)
        repeat(POOL) { free.add(ByteArray(131072)) }
        val analyzer = SpectrumAnalyzer(FFT)
        dspRunning = true
        dsp = Thread({
            var last = System.nanoTime()
            while (dspRunning) {
                val (buf, len) = full.poll(200, TimeUnit.MILLISECONDS) ?: continue
                analyzer.accumulate(buf, len)
                free.offer(buf)
                val now = System.nanoTime()
                if (now - last >= FRAME_NS) {
                    last = now
                    analyzer.frame()?.let(::publish)
                }
            }
        }, "sdr-spectrum").apply { start() }
        gain.attach { applyGains() }
        r.startRx(
            onSamples = { data, len ->
                gain.feed(data, len)
                // Spare buffers only: when the phone is busy, skipping transfers just thins the average.
                free.poll()?.let { b -> System.arraycopy(data, 0, b, 0, len); full.offer(b to len) }
            },
            onError = { msg -> scope.launch { error = msg; stop() } },
        )
    }

    /** Runs on the DSP thread: waterfall row first, then the new frame for Compose. */
    private fun publish(db: FloatArray) {
        val sorted = db.sortedArray()
        val floor = sorted[sorted.size / 2] - 4f
        val top = floor + 55f
        System.arraycopy(pixels, 0, pixels, WIDTH, WIDTH * (HEIGHT - 1))
        val per = db.size / WIDTH
        for (x in 0 until WIDTH) {
            var m = -200f
            for (k in 0 until per) m = maxOf(m, db[x * per + k])
            pixels[x] = color(((m - floor) / (top - floor)).coerceIn(0f, 1f))
        }
        val snapshot = pixels.copyOf()
        scope.launch {
            waterfall.setPixels(snapshot, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
            spectrum = db
            hold = if (peakHold) hold?.let { h -> FloatArray(db.size) { maxOf(h[it], db[it]) } } ?: db else null
            frames++
        }
    }

    fun stop() {
        dspRunning = false
        val r = radio
        radio = null
        running = false
        if (r != null) scope.launch(Dispatchers.IO) { r.close() }
    }

    internal fun setForTest(db: FloatArray, centerHz: Long) {
        repeat(HEIGHT) { publish(FloatArray(db.size) { i -> db[i] + ((it * 7 + i) % 5) - 2f }) }
        spectrum = db
        tunedHz = centerHz
        running = true
    }

    companion object {
        const val FFT = 1024
        const val WIDTH = 512
        const val HEIGHT = 160
        private const val POOL = 16
        private const val FRAME_NS = 66_000_000L
        private const val COLD = 0xFF0B1026.toInt()

        /** Dark blue → blue → cyan → yellow → red. */
        fun color(t: Float): Int {
            val stops = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
            val rgb = arrayOf(intArrayOf(11, 16, 38), intArrayOf(30, 60, 170), intArrayOf(0, 190, 220), intArrayOf(250, 220, 40), intArrayOf(230, 40, 30))
            var i = 0
            while (i < 3 && t > stops[i + 1]) i++
            val u = ((t - stops[i]) / (stops[i + 1] - stops[i])).coerceIn(0f, 1f)
            val r = (rgb[i][0] + (rgb[i + 1][0] - rgb[i][0]) * u).toInt()
            val g = (rgb[i][1] + (rgb[i + 1][1] - rgb[i][1]) * u).toInt()
            val b = (rgb[i][2] + (rgb[i + 1][2] - rgb[i][2]) * u).toInt()
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
