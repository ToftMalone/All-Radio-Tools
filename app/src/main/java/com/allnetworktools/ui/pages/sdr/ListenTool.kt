package com.allnetworktools.ui.pages.sdr

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.R
import com.allnetworktools.data.sdr.ListenMode
import com.allnetworktools.data.sdr.ListenReceiver
import com.allnetworktools.data.sdr.SdrRepository
import com.allnetworktools.data.sdr.SpectrumAnalyzer
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.HostInputField
import com.allnetworktools.ui.tools.StartButton
import com.allnetworktools.util.S
import java.util.Locale
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope

/** The groups the stations are sorted in, one tab each. */
enum class ListenBand(val label: Int) {
    Ham(R.string.listen_cat_ham),
    Broadcast(R.string.listen_cat_broadcast),
    AirSea(R.string.listen_cat_air_sea),
    Utility(R.string.listen_cat_utility),
    Space(R.string.listen_cat_space),
}

/** A frequency worth one tap: where it is, how it is modulated, which group it belongs to. */
class ListenPreset(val label: String, val hz: Long, val mode: ListenMode, val band: ListenBand = ListenBand.Utility)

/**
 * Listens to a station: the 2 MHz around the frequency are shown as a spectrum, and the channel picked in it (by tapping
 * or dragging, or by typing the frequency) is demodulated as WFM, NFM, AM or SSB and played. Moving inside the window
 * only moves the digital tuner; the radio is retuned only when the frequency leaves it.
 */
class ListenController(repo: SdrRepository, scope: CoroutineScope, onAcquire: () -> Unit = {}) :
    SdrRxController(repo, scope, onAcquire, SdrGain(listOf(0, 8, 16, 24, 32, 40), 24, listOf(10, 20, 30, 40), 30, amp = false, profile = com.allnetworktools.data.sdr.GainProfile.Strong), "sdr-listen") {
    var frequencyHz by mutableLongStateOf(100_000_000L)
        private set
    var mode by mutableStateOf(ListenMode.Wfm)
        private set
    var frequencyText by mutableStateOf("100.000")
    var volume by mutableFloatStateOf(0.8f)
    /** Below [SQUELCH_MIN] the squelch is off. */
    var squelch by mutableFloatStateOf(SQUELCH_MIN)
    var spectrum by mutableStateOf<FloatArray?>(null)
        private set
    var levelDb by mutableFloatStateOf(-120f)
        private set
    /** Where the radio is tuned (the middle of the 2 MHz window). */
    var centerHz by mutableLongStateOf(99_750_000L)
        private set
    /** The step of the dial; null follows the mode (100 kHz in WFM, 12.5 kHz in NFM…). */
    var stepChoice by mutableStateOf<Long?>(null)
    val stepHz: Long get() = stepChoice ?: autoStepHz
    /** The step "Auto" picks: in AM, 9 kHz on long and medium wave, 5 kHz on shortwave, the mode's step above. */
    val autoStepHz: Long get() = autoStep(mode, frequencyHz)
    /** How much of the window the spectrum shows: 1 for all of it, 16 for an eighth of a megahertz. */
    var zoom by mutableIntStateOf(1)
        private set
    /** The middle of what the spectrum shows, while zoomed in; it follows the channel when the channel leaves it. */
    var viewHz by mutableLongStateOf(100_000_000L)
        private set
    /** The middle of the spectrum frame shown, as an offset from [centerHz]: what the frame was cut around. */
    var frameOffsetHz by mutableLongStateOf(0L)
        private set
    var stationBand by mutableStateOf(ListenBand.Broadcast)

    @Volatile private var wantOffset = OFFSET_HZ.toDouble()
    @Volatile private var wantMode = ListenMode.Wfm
    @Volatile private var wantVolume = 0.8f
    @Volatile private var wantSquelch: Double? = null
    @Volatile private var wantZoom = 1
    @Volatile private var wantView = 0L

    override val sampleRate = ListenReceiver.SAMPLE_RATE
    override fun tuneHz(): Long {
        // The channel sits 250 kHz off the centre, away from the radio's own DC spike.
        centerHz = frequencyHz - OFFSET_HZ
        wantOffset = OFFSET_HZ.toDouble()
        follow(force = true)
        return centerHz
    }

    /** Width of the spectrum shown, in Hz. */
    val viewSpanHz: Long get() = ListenReceiver.SAMPLE_RATE.toLong() / zoom

    /** The middle of the view as an offset from the window's centre, kept inside the window. */
    fun viewOffsetHz(): Long {
        val room = (ListenReceiver.SAMPLE_RATE - viewSpanHz) / 2
        return (viewHz - centerHz).coerceIn(-room, room)
    }

    fun chooseZoom(z: Int) {
        zoom = z.coerceIn(1, MAX_ZOOM)
        wantZoom = zoom
        follow(force = true)
    }

    /** Recentres the view on the channel when it is forced to or when the channel left it. */
    private fun follow(force: Boolean) {
        val half = viewSpanHz / 2
        if (force || kotlin.math.abs(frequencyHz - (centerHz + viewOffsetHz())) > half * 9 / 10) viewHz = frequencyHz
        wantView = viewOffsetHz()
    }

    fun parsedHz(): Long? = frequencyText.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.1..6000.0 }?.let { (it * 1e6).roundToLong() }

    fun typedFrequency() { parsedHz()?.let { tune(it) } }

    fun chooseMode(m: ListenMode) {
        mode = m
        wantMode = m
        gain.profile = if (m == ListenMode.Wfm) com.allnetworktools.data.sdr.GainProfile.Strong else com.allnetworktools.data.sdr.GainProfile.Narrow
    }

    fun setVolumeLevel(v: Float) { volume = v; wantVolume = v }

    fun setSquelchLevel(v: Float) { squelch = v; wantSquelch = if (v <= SQUELCH_MIN) null else v.toDouble() }

    /** Tunes to [hz]: inside the window only the digital tuner moves, outside the radio follows. */
    fun tune(hz: Long) {
        frequencyHz = hz
        frequencyText = mhz(hz)
        if (!running && !starting) { viewHz = hz; return }
        val off = hz - centerHz
        if (kotlin.math.abs(off) <= MAX_OFFSET_HZ) {
            wantOffset = off.toDouble()
            follow(force = false)
        } else {
            centerHz = hz - OFFSET_HZ
            wantOffset = OFFSET_HZ.toDouble()
            follow(force = true)
            retune(centerHz)
        }
    }

    /** A step on the dial, in the chosen step. */
    fun step(direction: Int) = tune(snap(frequencyHz + direction * stepHz))

    private fun snap(hz: Long): Long = ((hz + stepHz / 2) / stepHz) * stepHz

    /** [fraction] is the position along the spectrum shown, 0 on the left edge and 1 on the right one. */
    fun tuneFraction(fraction: Float) {
        val hz = centerHz + viewOffsetHz() + ((fraction.coerceIn(0.01f, 0.99f) - 0.5f) * viewSpanHz).toLong()
        // Never past the edges of the window, where the filters cut.
        val lim = MAX_OFFSET_HZ + 100_000L
        tune(snap(hz.coerceIn(centerHz - lim, centerHz + lim)))
    }

    /** Tunes to [hz] in the mode its band usually uses (from the spectrum's marker). */
    fun listenAt(hz: Long) {
        chooseMode(ListenMode.guess(hz))
        tune(hz)
    }

    fun preset(p: ListenPreset) {
        chooseMode(p.mode)
        tune(p.hz)
    }

    override fun newSink(): SdrSink {
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(ListenReceiver.AUDIO_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(ListenReceiver.AUDIO_RATE * 4 / 2).build()
        track.play()
        val scaled = FloatArray(4096)
        val rx = ListenReceiver { b, n ->
            val v = wantVolume * wantVolume
            for (i in 0 until n) scaled[i] = b[i] * v
            track.write(scaled, 0, n, AudioTrack.WRITE_NON_BLOCKING)
        }
        var analyzer = SpectrumAnalyzer(BINS)
        var lastFrame = 0L
        return object : SdrSink {
            override fun feed(buf: ByteArray, len: Int) {
                rx.mode = wantMode
                rx.offsetHz = wantOffset
                rx.squelchDb = wantSquelch
                rx.feed(buf, len)
                // Zoomed in, a longer FFT keeps BINS points across the narrower view.
                val z = wantZoom
                if (analyzer.size != BINS * z) analyzer = SpectrumAnalyzer(BINS * z)
                analyzer.accumulate(buf, len, 2)
                val now = System.currentTimeMillis()
                if (now - lastFrame >= 100) {
                    lastFrame = now
                    val view = wantView
                    val f = analyzer.frame()?.let { cutView(it, z, view) }
                    val level = rx.levelDb.toFloat()
                    post { if (f != null) { spectrum = f; frameOffsetHz = view }; levelDb = level }
                }
            }

            override fun close() {
                runCatching { track.stop(); track.release() }
                post { spectrum = null }
            }
        }
    }

    private var mic: com.allnetworktools.data.sdr.MicSource? = null

    /** What the current mode transmits as; WFM (broadcast) never. */
    val txMode: com.allnetworktools.data.sdr.TxMode? get() = when (mode) {
        ListenMode.Nfm -> com.allnetworktools.data.sdr.TxMode.Fm
        ListenMode.Am -> com.allnetworktools.data.sdr.TxMode.Am
        ListenMode.Usb -> com.allnetworktools.data.sdr.TxMode.Usb
        ListenMode.Lsb -> com.allnetworktools.data.sdr.TxMode.Lsb
        ListenMode.Wfm -> null
    }

    /** Push to talk: the microphone goes out on the tuned frequency, in the tuned mode, until [releaseTalk]. */
    fun pushToTalk(device: com.allnetworktools.data.sdr.SdrDevice?) {
        if (tx.transmitting) return
        val m = txMode ?: return
        val src = com.allnetworktools.data.sdr.MicSource()
        if (!src.start()) { tx.error = S(R.string.tx_mic_failed); return }
        val (below, above) = when (m) {
            com.allnetworktools.data.sdr.TxMode.Fm -> 6_000 to 6_000
            com.allnetworktools.data.sdr.TxMode.Am -> 3_000 to 3_000
            com.allnetworktools.data.sdr.TxMode.Usb -> 0 to 3_000
            com.allnetworktools.data.sdr.TxMode.Lsb -> 3_000 to 0
        }
        val e = com.allnetworktools.data.sdr.Emission.around(frequencyHz, below, above, com.allnetworktools.data.sdr.TxUse.Amateur)
        val stream = com.allnetworktools.data.sdr.TxStream(com.allnetworktools.data.sdr.AudioModulator(src, m), TX_RATE, TX_SHIFT.toDouble())
        mic = src
        if (!tx.send(device, this, e, TX_RATE, frequencyHz - TX_SHIFT, stream) { src.close() }) { src.close(); mic = null }
    }

    fun releaseTalk() { mic?.close(); mic = null }

    internal fun setForTest(db: FloatArray, level: Float) { spectrum = db; levelDb = level; markRunningForTest() }

    companion object {
        const val BINS = 1024
        /** Transmit rate: 50 times the microphone's 48 kHz. */
        const val TX_RATE = 2_400_000
        /** The signal goes out this far above the tuned frequency of the radio, clear of its carrier leak. */
        const val TX_SHIFT = 300_000L
        const val MAX_ZOOM = 16
        val Zooms = listOf(1, 2, 4, 8, 16)
        val Steps = listOf(100L, 500L, 1_000L, 5_000L, 12_500L, 25_000L, 100_000L)

        fun autoStep(mode: ListenMode, hz: Long): Long = when {
            mode != ListenMode.Am -> mode.stepHz
            hz < 1_710_000L -> 9_000L
            hz < 30_000_000L -> 5_000L
            else -> mode.stepHz
        }

        /** The frequency in MHz down to 100 Hz, as typed in the field. */
        fun mhz(hz: Long): String = "%.4f".format(Locale.US, hz / 1e6)

        /** The [BINS] points of a frame from an FFT [z] times longer, around [viewOffsetHz] from its centre. */
        fun cutView(frame: FloatArray, z: Int, viewOffsetHz: Long): FloatArray {
            if (z == 1) return frame
            val mid = frame.size / 2 + (viewOffsetHz.toDouble() / ListenReceiver.SAMPLE_RATE * frame.size).toInt()
            val from = (mid - BINS / 2).coerceIn(0, frame.size - BINS)
            return frame.copyOfRange(from, from + BINS)
        }

        const val SQUELCH_MIN = -70f
        private const val OFFSET_HZ = 250_000L
        private const val MAX_OFFSET_HZ = 800_000L
    }
}

private fun presets(): List<ListenPreset> {
    val ham = ListenBand.Ham
    val bc = ListenBand.Broadcast
    val air = ListenBand.AirSea
    val ut = ListenBand.Utility
    val sp = ListenBand.Space
    return listOf(
        // Radio amateurs: the voice part of each band, lower sideband below 10 MHz and upper above.
        ListenPreset("160 m", 1_840_000L, ListenMode.Lsb, ham),
        ListenPreset("80 m", 3_700_000L, ListenMode.Lsb, ham),
        ListenPreset("40 m", 7_100_000L, ListenMode.Lsb, ham),
        ListenPreset("20 m", 14_200_000L, ListenMode.Usb, ham),
        ListenPreset("FT8 20 m", 14_074_000L, ListenMode.Usb, ham),
        ListenPreset("17 m", 18_130_000L, ListenMode.Usb, ham),
        ListenPreset("15 m", 21_250_000L, ListenMode.Usb, ham),
        ListenPreset("12 m", 24_950_000L, ListenMode.Usb, ham),
        ListenPreset("10 m", 28_500_000L, ListenMode.Usb, ham),
        ListenPreset("6 m", 50_150_000L, ListenMode.Usb, ham),
        ListenPreset("2 m", 145_500_000L, ListenMode.Nfm, ham),
        ListenPreset("70 cm", 433_500_000L, ListenMode.Nfm, ham),
        // Broadcasting: FM, medium wave and the shortwave broadcast bands, named by their wavelength.
        ListenPreset(S(R.string.fm_radio), 100_000_000L, ListenMode.Wfm, bc),
        ListenPreset(S(R.string.listen_mw), 1_215_000L, ListenMode.Am, bc),
        ListenPreset("90 m", 3_250_000L, ListenMode.Am, bc),
        ListenPreset("75 m", 3_950_000L, ListenMode.Am, bc),
        ListenPreset("60 m", 4_900_000L, ListenMode.Am, bc),
        ListenPreset("49 m", 6_000_000L, ListenMode.Am, bc),
        ListenPreset("41 m", 7_300_000L, ListenMode.Am, bc),
        ListenPreset("31 m", 9_650_000L, ListenMode.Am, bc),
        ListenPreset("25 m", 11_850_000L, ListenMode.Am, bc),
        ListenPreset("22 m", 13_700_000L, ListenMode.Am, bc),
        ListenPreset("19 m", 15_400_000L, ListenMode.Am, bc),
        ListenPreset("16 m", 17_700_000L, ListenMode.Am, bc),
        ListenPreset("13 m", 21_600_000L, ListenMode.Am, bc),
        ListenPreset("11 m", 25_900_000L, ListenMode.Am, bc),
        // Aviation and sea.
        ListenPreset(S(R.string.aviation), 121_500_000L, ListenMode.Am, air),
        ListenPreset("VOLMET", 5_505_000L, ListenMode.Usb, air),
        ListenPreset(S(R.string.listen_marine), 156_800_000L, ListenMode.Nfm, air),
        // Everyday radios and time signals.
        ListenPreset("PMR446", 446_006_250L, ListenMode.Nfm, ut),
        ListenPreset("CB 19", 27_185_000L, ListenMode.Am, ut),
        ListenPreset("LPD 433", 433_075_000L, ListenMode.Nfm, ut),
        ListenPreset("WWV", 10_000_000L, ListenMode.Am, ut),
        ListenPreset("WWV", 15_000_000L, ListenMode.Am, ut),
        // Satellites and the space station.
        ListenPreset("ISS", 145_800_000L, ListenMode.Nfm, sp),
        ListenPreset("ISS APRS", 145_825_000L, ListenMode.Nfm, sp),
        ListenPreset("SO-50", 436_795_000L, ListenMode.Nfm, sp),
        ListenPreset("Meteor-M", 137_900_000L, ListenMode.Wfm, sp),
    )
}

/** Hz as a short label: "500 Hz", "12.5 kHz". */
private fun stepLabel(hz: Long): String =
    if (hz < 1_000) "$hz Hz" else "%s kHz".format(Locale.getDefault(), if (hz % 1_000 == 0L) (hz / 1_000).toString() else "%.1f".format(Locale.getDefault(), hz / 1e3))

@Composable
fun ListenTool(vm: MainViewModel) {
    val c = vm.tools.listen
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    PageColumn {
        HeroCard {
            Text("%.4f MHz".format(Locale.getDefault(), c.frequencyHz / 1e6), style = rf(32, 38, 600, tnum = true))
            Text(c.mode.label, style = rf(14, 20))
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    c.error != null -> HeroChip(c.error!!, AntTheme.net.poor)
                    c.starting -> HeroChip(S(R.string.starting_the_hackrf), AntTheme.net.fair, blink = true)
                    c.running -> HeroChip(S(R.string.listen_level, "%.0f".format(Locale.getDefault(), c.levelDb)), AntTheme.net.good, blink = true)
                    device == null -> HeroChip(S(R.string.no_hackrf_plugged_in), AntTheme.net.poor)
                    else -> HeroChip(S(R.string.ready, device!!.name), AntTheme.net.good)
                }
            }
        }
        val d = device
        if (c.running || c.starting) StartButton(S(R.string.stop), Sym.Stop) { c.stop() }
        else StartButton(S(R.string.listen_start), Sym.Headphones, enabled = d != null && c.parsedHz() != null) { d?.let { c.typedFrequency(); c.start(it) } }
        val tab = ToolTabRow("ListenTool0", map = false)
        if (tab == ToolTab.Main) {
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpectrumPicker(c)
                    Text(S(R.string.listen_zoom), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ListenController.Zooms.forEach { z ->
                            val w = ListenReceiver.SAMPLE_RATE / z
                            AntFilterChip(if (w >= 1_000_000) "%d MHz".format(Locale.getDefault(), w / 1_000_000) else "%d kHz".format(Locale.getDefault(), w / 1_000), c.zoom == z, { c.chooseZoom(z) })
                        }
                    }
                    Text(S(R.string.listen_tap_hint) + " " + S(R.string.listen_pinch_hint), style = rf(12, 16), color = cs.onSurfaceVariant)
                }
            }
        }
        if (tab == ToolTab.Main) {
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(S(R.string.frequency), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DialButton(Sym.Remove) { c.step(-1) }
                        Box(Modifier.weight(1f)) {
                            HostInputField(c.frequencyText, { c.frequencyText = it }, S(R.string.frequency_mhz), Sym.Antenna, keyboardType = KeyboardType.Decimal, onDone = c::typedFrequency)
                        }
                        DialButton(Sym.Add) { c.step(1) }
                    }
                    Text(S(R.string.listen_step), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AntFilterChip(S(R.string.listen_step_auto, stepLabel(c.autoStepHz)), c.stepChoice == null, { c.stepChoice = null })
                        ListenController.Steps.forEach { st -> AntFilterChip(stepLabel(st), c.stepChoice == st, { c.stepChoice = st }) }
                    }
                    Text(S(R.string.listen_mode), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ListenMode.entries.forEach { m -> AntFilterChip(m.label, c.mode == m, { c.chooseMode(m) }) }
                    }
                }
            }
        }
        if (tab == ToolTab.Main) {
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(S(R.string.listen_presets), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                    // One tab per group; stations the radio cannot tune are left out.
                    val range = d?.let { com.allnetworktools.data.sdr.SdrSettings.tunable(it.kind, it.usb?.productName) }
                    val all = presets().filter { range == null || it.hz in range }
                    val bands = ListenBand.entries.filter { b -> all.any { it.band == b } }
                    val band = if (c.stationBand in bands) c.stationBand else bands.firstOrNull()
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(bands.size) { i -> val b = bands[i]; AntFilterChip(S(b.label), b == band, { c.stationBand = b }) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        all.filter { it.band == band }.forEach { p ->
                            AntFilterChip("${p.label} · ${"%.3f".format(Locale.getDefault(), p.hz / 1e6)}", c.frequencyHz == p.hz && c.mode == p.mode, { c.preset(p) })
                        }
                    }
                }
            }
        }
        if (tab == ToolTab.Main) {
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(S(R.string.listen_volume), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                    Slider(c.volume, { c.setVolumeLevel(it) }, valueRange = 0f..1f)
                    Text(
                        if (c.squelch <= ListenController.SQUELCH_MIN) S(R.string.listen_squelch_off) else S(R.string.listen_squelch, "%.0f".format(Locale.getDefault(), c.squelch)),
                        style = rf(13, 18, 600), color = cs.onSurfaceVariant,
                    )
                    Slider(c.squelch, { c.setSquelchLevel(it) }, valueRange = ListenController.SQUELCH_MIN..-5f)
                }
            }
        }
        val gate = rememberLicenceGate()
        if (tab == ToolTab.Main && d?.kind == com.allnetworktools.data.sdr.SdrKind.HackRf) TalkCard(c, d, gate)
        if (tab == ToolTab.Settings && d?.kind == com.allnetworktools.data.sdr.SdrKind.HackRf) TxSettingsCard(gate = gate)
        if (tab == ToolTab.Settings) {
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(S(R.string.gain), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                    GainSettings(c.gain) { c.applyGains() }
                }
            }
        }
        if (tab == ToolTab.Settings) {
            SectionCard {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                    Text(S(R.string.listen_how_it_works) + "\n\n" + S(R.string.listen_tx_note), style = rf(13, 18), color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun DialButton(icon: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).background(cs.surfaceContainerHigh).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Symbol(icon, size = 24.dp, tint = cs.onSurface)
    }
}

/** The spectrum shown (all the window, or part of it when zoomed in), with the channel marked; a tap or a drag tunes, a pinch zooms. */
@Composable
private fun SpectrumPicker(c: ListenController) {
    val db = c.spectrum
    val accent = AntTheme.accent.accent
    val grid = cs.outlineVariant
    val span = c.viewSpanHz.toFloat()
    // Where the channel is in the frame drawn, which was cut around frameOffsetHz.
    val off = c.frequencyHz - c.centerHz - (if (c.running) c.frameOffsetHz else c.viewOffsetHz())
    val bw = c.mode.bandwidthHz
    var pinch by remember { mutableFloatStateOf(1f) }
    Canvas(
        Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHigh)
            .pointerInput(Unit) { detectTapGestures { p -> c.tuneFraction(p.x / size.width) } }
            .pointerInput(Unit) {
                // One finger tunes, two fingers zoom: each doubling of the pinch is one zoom step.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pinch = 1f
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            pinch *= event.calculateZoom()
                            if (pinch > 1.6f) { c.chooseZoom(c.zoom * 2); pinch = 1f }
                            if (pinch < 0.62f) { c.chooseZoom(c.zoom / 2); pinch = 1f }
                            event.changes.forEach { it.consume() }
                        } else if (pressed.size == 1 && pressed[0].positionChanged()) {
                            c.tuneFraction(pressed[0].position.x / size.width)
                            pressed[0].consume()
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        val minDb = -100f
        val maxDb = -10f
        fun y(v: Float) = size.height * (1 - ((v - minDb) / (maxDb - minDb)).coerceIn(0f, 1f))
        for (k in 1..3) drawLine(grid, Offset(0f, size.height * k / 4), Offset(size.width, size.height * k / 4), 1f)
        if (db != null) {
            val path = Path()
            val n = db.size
            path.moveTo(0f, size.height)
            for (i in 0 until n) path.lineTo(size.width * i / (n - 1), y(db[i]))
            path.lineTo(size.width, size.height)
            path.close()
            drawPath(path, accent.copy(alpha = 0.25f))
            val line = Path()
            for (i in 0 until n) { val x = size.width * i / (n - 1); if (i == 0) line.moveTo(x, y(db[i])) else line.lineTo(x, y(db[i])) }
            drawPath(line, accent, style = Stroke(2f))
        }
        // The channel: what the demodulator lets through as a band (on one side of the carrier in USB and LSB), its
        // carrier as a line. Never narrower than a few pixels, so it shows on the full 2 MHz too.
        val x = size.width * (0.5f + off.toFloat() / span)
        val w = (size.width * bw / span).coerceAtLeast(8f)
        val left = when (c.mode) { ListenMode.Usb -> x; ListenMode.Lsb -> x - w; else -> x - w / 2 }
        drawRect(accent.copy(alpha = 0.25f), Offset(left, 0f), Size(w, size.height))
        drawLine(accent, Offset(x, 0f), Offset(x, size.height), 3f)
    }
}

/** Push to talk: hold the button to transmit the microphone on the tuned frequency, in the tuned mode. */
@Composable
private fun TalkCard(c: ListenController, device: com.allnetworktools.data.sdr.SdrDevice, gate: LicenceGate) {
    val vmPerms = com.allnetworktools.ui.LocalActions.current
    val e = com.allnetworktools.data.sdr.Emission.around(c.frequencyHz, 3_000, 3_000, com.allnetworktools.data.sdr.TxUse.Amateur)
    val why = if (c.txMode == null) S(R.string.tx_not_in_wfm) else com.allnetworktools.data.sdr.TxPolicy.refuse(e, ignoreLicence = true)
    val red = cs.error
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(S(R.string.tx_talk_title), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
            val on = c.tx.transmitting
            Box(
                Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(32.dp))
                    .background(if (why != null) cs.surfaceContainerHigh else if (on) red else AntTheme.accent.container)
                    .pointerInput(why) {
                        if (why != null) return@pointerInput
                        detectTapGestures(onPress = {
                            if (!androidx.core.content.ContextCompat.checkSelfPermission(com.allnetworktools.util.Res.context!!, android.Manifest.permission.RECORD_AUDIO).let { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
                                vmPerms.request(com.allnetworktools.data.PermGroup.Microphone)
                                return@detectTapGestures
                            }
                            // No call sign or licence yet: the press opens the dialog instead of transmitting.
                            if (!com.allnetworktools.data.sdr.TxSettings.amateurReady) { gate.run { }; return@detectTapGestures }
                            c.pushToTalk(device)
                            tryAwaitRelease()
                            c.releaseTalk()
                        })
                    },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Symbol(Sym.Mic, size = 24.dp, tint = if (on) cs.onError else if (why != null) cs.onSurfaceVariant else AntTheme.accent.onContainer)
                    Text(
                        S(if (on) R.string.tx_talk_release else R.string.tx_talk_hold),
                        style = rf(16, 22, 600), color = if (on) cs.onError else if (why != null) cs.onSurfaceVariant else AntTheme.accent.onContainer,
                    )
                }
            }
            if (why != null) Text(why, style = rf(12, 16), color = cs.onSurfaceVariant)
            TxStatus(c.tx)
        }
    }
}
