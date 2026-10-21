package com.allnetworktools.ui.pages.sdr

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.data.sdr.Decimator
import com.allnetworktools.data.sdr.FloatDecimator
import com.allnetworktools.data.sdr.FmReceiver
import com.allnetworktools.data.sdr.HackRf
import com.allnetworktools.data.sdr.Rds
import com.allnetworktools.data.sdr.RdsInfo
import com.allnetworktools.data.sdr.SdrDevice
import com.allnetworktools.data.sdr.SdrRepository
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.BtnKind
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.HostInputField
import com.allnetworktools.ui.tools.StartButton
import com.allnetworktools.ui.tools.ToolButton
import com.allnetworktools.ui.tools.ToolButtons
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.allnetworktools.R
import com.allnetworktools.util.S

/**
 * Broadcast FM (87,5–108 MHz) with RDS. The HackRF runs at 1,9 MS/s, an integer multiple of the RDS bit rate, tuned
 * 475 kHz below the station so the HackRF's DC spike stays out of the channel.
 */
class FmController(private val repo: SdrRepository, private val scope: CoroutineScope, private val onAcquire: () -> Unit = {}) {
    var frequencyMhz by mutableStateOf("100.000")
    var lnaGain by mutableIntStateOf(32)
    var vgaGain by mutableIntStateOf(30)
    var amp by mutableStateOf(false)
    var sound by mutableStateOf(true)
    var running by mutableStateOf(false)
        private set
    var starting by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var rds by mutableStateOf(RdsInfo())
        private set
    var levelDb by mutableDoubleStateOf(-100.0)
        private set
    var tunedHz by mutableStateOf<Long?>(null)
        private set

    private var radio: HackRf? = null
    private var track: AudioTrack? = null
    @Volatile private var dspRunning = false
    @Volatile private var soundOn = true
    @Volatile private var resetRequested = false

    fun parsedHz(): Long? {
        val mhz = frequencyMhz.trim().replace(',', '.').toDoubleOrNull() ?: return null
        return if (mhz in 64.0..110.0) (mhz * 1e6).roundToLong() else null
    }

    /** Moves the tuning by [deltaMhz], live if the radio is running. */
    fun step(deltaMhz: Double) {
        val now = (parsedHz() ?: 100_000_000L) / 1e6
        tune(((now + deltaMhz) * 10).roundToLong() / 10.0)
    }

    fun tune(mhz: Double) {
        val m = mhz.coerceIn(87.5, 108.0)
        frequencyMhz = "%.3f".format(Locale.US, m)
        retune()
    }

    fun retune() {
        val hz = parsedHz() ?: return
        val r = radio ?: return
        tunedHz = hz
        resetRequested = true
        rds = RdsInfo()
        scope.launch(Dispatchers.IO) { r.setFrequency(hz - SAMPLE_RATE / 4) }
    }

    fun toggleSound() { sound = !sound; soundOn = sound }

    fun start(device: SdrDevice) {
        if (running || starting) return
        val hz = parsedHz() ?: run { error = S(R.string.invalid_frequency_87_5_to_108); return }
        onAcquire()
        error = null
        starting = true
        scope.launch {
            try {
                if (!repo.hasPermission(device) && !repo.requestPermission(device)) { error = S(R.string.usb_access_to_the_hackrf_refused); return@launch }
                val r = withContext(Dispatchers.IO) { repo.open(device) } ?: run { error = S(R.string.could_not_open_the_hackrf); return@launch }
                val lna = lnaGain
                val vga = vgaGain
                val withAmp = amp
                val ok = withContext(Dispatchers.IO) {
                    r.setSampleRate(SAMPLE_RATE, HackRf.filterFor(SAMPLE_RATE)) && r.setFrequency(hz - SAMPLE_RATE / 4) &&
                        r.setLnaGain(lna) && r.setVgaGain(vga) && r.setAmp(withAmp)
                }
                if (!ok) { r.close(); error = S(R.string.the_hackrf_did_not_accept_the); return@launch }
                radio = r
                tunedHz = hz
                rds = RdsInfo()
                soundOn = sound
                startPipeline(r)
                running = true
            } finally {
                starting = false
            }
        }
    }

    private fun startPipeline(r: HackRf) {
        val free = ArrayBlockingQueue<ByteArray>(POOL)
        val full = ArrayBlockingQueue<Pair<ByteArray, Int>>(POOL)
        repeat(POOL) { free.add(ByteArray(131072)) }
        val at = runCatching {
            val minBuf = AudioTrack.getMinBufferSize(FmReceiver.AUDIO_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(FmReceiver.AUDIO_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minBuf, 32768))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build().also { it.play() }
        }.getOrNull()
        track = at
        val fm = FmReceiver(
            onAudio = { s, n -> if (soundOn) at?.write(s, 0, n, AudioTrack.WRITE_NON_BLOCKING) },
            onRds = { info -> scope.launch { rds = info } },
        )
        val d1 = Decimator(4, 0.09, taps = 48)
        val d2 = FloatDecimator(2, 0.21, 48)
        val aRe = FloatArray(32768)
        val aIm = FloatArray(32768)
        val bRe = FloatArray(16384)
        val bIm = FloatArray(16384)
        dspRunning = true
        Thread({
            var last = 0L
            while (dspRunning) {
                val item = full.poll(200, TimeUnit.MILLISECONDS)
                if (resetRequested) { resetRequested = false; fm.reset() }
                if (item != null) {
                    val k = d1.process(item.first, item.second, aRe, aIm, 0)
                    free.offer(item.first)
                    val m = d2.process(aRe, aIm, k, bRe, bIm)
                    fm.feed(bRe, bIm, m)
                }
                val now = System.currentTimeMillis()
                if (now - last >= 500) {
                    last = now
                    val db = fm.levelDb()
                    scope.launch { levelDb = db }
                }
            }
        }, "sdr-fm").start()
        r.startRx(
            onSamples = { data, len -> free.poll()?.let { b -> System.arraycopy(data, 0, b, 0, len); full.offer(b to len) } },
            onError = { msg -> scope.launch { error = msg; stop() } },
        )
    }

    fun applyGains() {
        val r = radio ?: return
        val lna = lnaGain
        val vga = vgaGain
        val withAmp = amp
        scope.launch(Dispatchers.IO) { r.setLnaGain(lna); r.setVgaGain(vga); r.setAmp(withAmp) }
    }

    fun stop() {
        dspRunning = false
        val r = radio
        radio = null
        running = false
        tunedHz = null
        track?.let { runCatching { it.stop(); it.release() } }
        track = null
        if (r != null) scope.launch(Dispatchers.IO) { r.close() }
    }

    internal fun setForTest(info: RdsInfo, mhz: Double, level: Double) {
        frequencyMhz = "%.3f".format(Locale.US, mhz); rds = info; levelDb = level; tunedHz = (mhz * 1e6).roundToLong(); running = true
    }

    companion object {
        /** 1 600 samples per RDS bit. */
        private const val SAMPLE_RATE = 1_900_000
        private const val POOL = 32
    }
}

private fun mhz(hz: Long) = "%.1f".format(Locale.getDefault(), hz / 1e6)

@Composable
fun FmTool(vm: MainViewModel) {
    val c = vm.tools.fm
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    val info = c.rds
    val shown = c.tunedHz ?: c.parsedHz()
    PageColumn {
        HeroCard {
            Text(info.ps ?: if (c.running) S(R.string.searching_for_rds) else S(R.string.fm_radio), style = gs(40, 46, 500, -0.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(shown?.let { "${mhz(it)} MHz" }, info.pty?.takeIf { it > 0 }?.let { Rds.PTY.getOrNull(it) }, info.pi?.let { "PI %04X".format(it) }).joinToString(" · "),
                Modifier.padding(top = 2.dp), style = rf(14, 20, tnum = true),
            )
            if (info.radioText != null) Text(info.radioText, Modifier.padding(top = 8.dp), style = rf(15, 21, 500), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    c.error != null -> HeroChip(c.error!!, AntTheme.net.poor)
                    c.starting -> HeroChip(S(R.string.starting_the_hackrf), AntTheme.net.fair, blink = true)
                    c.running -> {
                        HeroChip("Signal %.0f dBFS".format(Locale.getDefault(), c.levelDb), if (c.levelDb > -45) AntTheme.net.good else AntTheme.net.fair, blink = true)
                        if (info.tp) HeroChip("TP", AntTheme.net.good)
                        if (info.ta) HeroChip("TA", AntTheme.net.fair)
                    }
                    device == null -> HeroChip(S(R.string.no_hackrf_plugged_in), AntTheme.net.poor)
                    else -> HeroChip(S(R.string.ready, device!!.name), AntTheme.net.good)
                }
            }
        }
        val d = device
        if (c.running || c.starting) StartButton(S(R.string.stop), Sym.Stop) { c.stop() }
        else StartButton(S(R.string.listen), Sym.PlayArrow, enabled = d != null && c.parsedHz() != null) { d?.let(c::start) }
        ToolButtons(
            ToolButton("− 0,1", Sym.ChevronLeft, BtnKind.OutlineOnSurface) { c.step(-0.1) },
            ToolButton("+ 0,1", Sym.ChevronRight, BtnKind.OutlineOnSurface) { c.step(0.1) },
        )
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HostInputField(c.frequencyMhz, { c.frequencyMhz = it }, S(R.string.frequency_mhz), Sym.Stream, keyboardType = KeyboardType.Decimal) { c.retune() }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AntFilterChip(if (c.sound) S(R.string.sound_on) else S(R.string.sound_off), c.sound, { c.toggleSound() })
                    AntFilterChip(S(R.string.amp_14_db), c.amp, { c.amp = !c.amp; c.applyGains() })
                }
                GainSettings(
                    c.lnaGain, listOf(0, 8, 16, 24, 32, 40), { c.lnaGain = it; c.applyGains() },
                    c.vgaGain, listOf(10, 20, 30, 40), { c.vgaGain = it; c.applyGains() },
                )
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(
                    S(R.string.public_fm_radio_in_mono_50) +
                        S(R.string.if_the_signal_is_strong_enough) +
                        S(R.string.receive_only),
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}
