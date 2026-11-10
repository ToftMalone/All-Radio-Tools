package com.allnetworktools.ui.pages.sdr

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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

/** A frequency worth one tap: where it is, how it is modulated. */
class ListenPreset(val label: String, val hz: Long, val mode: ListenMode)

/**
 * Listens to a station: the 2 MHz around the frequency are shown as a spectrum, and the channel picked in it (by tapping
 * or dragging, or by typing the frequency) is demodulated as WFM, NFM, AM or SSB and played. Moving inside the window
 * only moves the digital tuner; the radio is retuned only when the frequency leaves it.
 */
class ListenController(repo: SdrRepository, scope: CoroutineScope, onAcquire: () -> Unit = {}) :
    SdrRxController(repo, scope, onAcquire, SdrGain(listOf(0, 8, 16, 24, 32, 40), 24, listOf(10, 20, 30, 40), 30, amp = false), "sdr-listen") {
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
    /** Where the radio is tuned (the middle of the spectrum shown). */
    var centerHz by mutableLongStateOf(99_750_000L)
        private set

    @Volatile private var wantOffset = OFFSET_HZ.toDouble()
    @Volatile private var wantMode = ListenMode.Wfm
    @Volatile private var wantVolume = 0.8f
    @Volatile private var wantSquelch: Double? = null

    override val sampleRate = ListenReceiver.SAMPLE_RATE
    override fun tuneHz(): Long {
        // The channel sits 250 kHz off the centre, away from the radio's own DC spike.
        centerHz = frequencyHz - OFFSET_HZ
        wantOffset = OFFSET_HZ.toDouble()
        return centerHz
    }

    fun parsedHz(): Long? = frequencyText.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.5..6000.0 }?.let { (it * 1e6).roundToLong() }

    fun typedFrequency() { parsedHz()?.let { tune(it) } }

    fun chooseMode(m: ListenMode) {
        mode = m
        wantMode = m
    }

    fun setVolumeLevel(v: Float) { volume = v; wantVolume = v }

    fun setSquelchLevel(v: Float) { squelch = v; wantSquelch = if (v <= SQUELCH_MIN) null else v.toDouble() }

    /** Tunes to [hz]: inside the window only the digital tuner moves, outside the radio follows. */
    fun tune(hz: Long) {
        frequencyHz = hz
        frequencyText = "%.3f".format(Locale.US, hz / 1e6)
        if (!running && !starting) return
        val off = hz - centerHz
        if (kotlin.math.abs(off) <= MAX_OFFSET_HZ) {
            wantOffset = off.toDouble()
        } else {
            centerHz = hz - OFFSET_HZ
            wantOffset = OFFSET_HZ.toDouble()
            retune(centerHz)
        }
    }

    /** A step on the dial, in the mode's channel width. */
    fun step(direction: Int) = tune(snap(frequencyHz + direction * mode.stepHz))

    private fun snap(hz: Long): Long = ((hz + mode.stepHz / 2) / mode.stepHz) * mode.stepHz

    /** [fraction] is the position along the spectrum, 0 on the left edge and 1 on the right one. */
    fun tuneFraction(fraction: Float) {
        val hz = centerHz + ((fraction.coerceIn(0.02f, 0.98f) - 0.5f) * ListenReceiver.SAMPLE_RATE).toLong()
        tune(snap(hz))
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
        val analyzer = SpectrumAnalyzer(1024)
        var lastFrame = 0L
        return object : SdrSink {
            override fun feed(buf: ByteArray, len: Int) {
                rx.mode = wantMode
                rx.offsetHz = wantOffset
                rx.squelchDb = wantSquelch
                rx.feed(buf, len)
                analyzer.accumulate(buf, len, 2)
                val now = System.currentTimeMillis()
                if (now - lastFrame >= 100) {
                    lastFrame = now
                    val f = analyzer.frame()
                    val level = rx.levelDb.toFloat()
                    post { if (f != null) spectrum = f; levelDb = level }
                }
            }

            override fun close() {
                runCatching { track.stop(); track.release() }
                post { spectrum = null }
            }
        }
    }

    internal fun setForTest(db: FloatArray, level: Float) { spectrum = db; levelDb = level; markRunningForTest() }

    companion object {
        const val SQUELCH_MIN = -70f
        private const val OFFSET_HZ = 250_000L
        private const val MAX_OFFSET_HZ = 800_000L
    }
}

private fun presets() = listOf(
    ListenPreset(S(R.string.fm_radio), 100_000_000L, ListenMode.Wfm),
    ListenPreset(S(R.string.aviation), 121_500_000L, ListenMode.Am),
    ListenPreset(S(R.string.listen_marine), 156_800_000L, ListenMode.Nfm),
    ListenPreset("PMR446", 446_006_250L, ListenMode.Nfm),
    ListenPreset(S(R.string.n_2_m_amateur_radio), 145_500_000L, ListenMode.Nfm),
    ListenPreset("20 m", 14_200_000L, ListenMode.Usb),
    ListenPreset("40 m", 7_100_000L, ListenMode.Lsb),
)

@Composable
fun ListenTool(vm: MainViewModel) {
    val c = vm.tools.listen
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    PageColumn {
        HeroCard {
            Text("%.3f MHz".format(Locale.getDefault(), c.frequencyHz / 1e6), style = rf(32, 38, 600, tnum = true))
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

        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SpectrumPicker(c)
                Text(S(R.string.listen_tap_hint), style = rf(12, 16), color = cs.onSurfaceVariant)
            }
        }
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(S(R.string.frequency), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DialButton(Sym.Remove) { c.step(-1) }
                    Box(Modifier.weight(1f)) {
                        HostInputField(c.frequencyText, { c.frequencyText = it }, S(R.string.frequency_mhz), Sym.Stream, keyboardType = KeyboardType.Decimal, onDone = c::typedFrequency)
                    }
                    DialButton(Sym.Add) { c.step(1) }
                }
                Text(S(R.string.listen_mode), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ListenMode.entries.forEach { m -> AntFilterChip(m.label, c.mode == m, { c.chooseMode(m) }) }
                }
                Text(S(R.string.listen_presets), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets().forEach { p -> AntFilterChip("${p.label} · ${"%.3f".format(Locale.getDefault(), p.hz / 1e6)}", c.frequencyHz == p.hz && c.mode == p.mode, { c.preset(p) }) }
                }
            }
        }
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
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(S(R.string.gain), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                GainSettings(c.gain) { c.applyGains() }
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(S(R.string.listen_how_it_works), style = rf(13, 18), color = cs.onSurfaceVariant)
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

/** The spectrum of the window, with the tuned channel marked; a tap or a drag tunes. */
@Composable
private fun SpectrumPicker(c: ListenController) {
    val db = c.spectrum
    val accent = AntTheme.accent.accent
    val grid = cs.outlineVariant
    val off = c.frequencyHz - c.centerHz
    val bw = c.mode.bandwidthHz
    Canvas(
        Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHigh)
            .pointerInput(Unit) { detectTapGestures { p -> c.tuneFraction(p.x / size.width) } }
            .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> c.tuneFraction(change.position.x / size.width) } },
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
        // The channel: its width as a band, its middle as a line.
        val x = size.width * (0.5f + off.toFloat() / ListenReceiver.SAMPLE_RATE)
        val w = size.width * bw / ListenReceiver.SAMPLE_RATE
        drawRect(accent.copy(alpha = 0.18f), Offset(x - w / 2, 0f), Size(w.coerceAtLeast(3f), size.height))
        drawLine(accent, Offset(x, 0f), Offset(x, size.height), 3f)
    }
}
