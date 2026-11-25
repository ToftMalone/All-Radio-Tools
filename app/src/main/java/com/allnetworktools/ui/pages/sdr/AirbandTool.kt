package com.allnetworktools.ui.pages.sdr

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.R
import com.allnetworktools.data.sdr.AirChannel
import com.allnetworktools.data.sdr.AirbandScanner
import com.allnetworktools.data.sdr.ListenReceiver
import com.allnetworktools.data.sdr.SdrRepository
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.LeadingIcon
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.StartButton
import com.allnetworktools.util.Cue
import com.allnetworktools.util.P
import com.allnetworktools.util.S
import com.allnetworktools.util.SoundCues
import kotlinx.coroutines.CoroutineScope
import java.util.Locale

/**
 * The automatic airband scanner: a 1.8 MHz window of the 118–137 MHz aviation band, every channel that keys up opened
 * on its own, the strongest one played in AM. Nothing is kept after the session.
 */
class AirbandController(repo: SdrRepository, scope: CoroutineScope, onAcquire: () -> Unit = {}) :
    SdrRxController(repo, scope, onAcquire, SdrGain(listOf(16, 24, 32, 40), 32, listOf(20, 30, 40), 30, amp = true), "sdr-airband") {
    /** The middle of the window scanned. */
    var centerHz by mutableLongStateOf(WINDOWS.first())
    var channels by mutableStateOf<List<AirChannel>>(emptyList())
        private set
    var pinned by mutableStateOf<Long?>(null)
        private set
    var volume by mutableFloatStateOf(0.8f)
    @Volatile private var wantVolume = 0.8f
    @Volatile private var wantPin: Long? = null

    override val sampleRate = 2_000_000
    override fun tuneHz() = centerHz

    fun chooseWindow(hz: Long) {
        if (hz == centerHz) return
        val was = running
        if (was) stop()
        centerHz = hz
        channels = emptyList()
        pinned = null; wantPin = null
    }

    fun setVolumeLevel(v: Float) { volume = v; wantVolume = v }

    /** Only this channel is played (again: back to the strongest one on air). */
    fun pin(hz: Long) {
        val next = if (pinned == hz) null else hz
        pinned = next
        wantPin = next
    }

    override fun newSink(): SdrSink {
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(ListenReceiver.AUDIO_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(ListenReceiver.AUDIO_RATE * 4 / 2).build()
        track.play()
        val scaled = FloatArray(4096)
        val scanner = AirbandScanner(centerHz, sampleRate, { b, n ->
            val v = wantVolume * wantVolume
            for (i in 0 until n) scaled[i] = b[i] * v
            track.write(scaled, 0, n, AudioTrack.WRITE_NON_BLOCKING)
        })
        scanner.onOpen = { SoundCues.play(Cue.Found) }
        var last = 0L
        return object : SdrSink {
            override fun feed(buf: ByteArray, len: Int) {
                scanner.pinned = wantPin
                scanner.feed(buf, len)
                val now = System.currentTimeMillis()
                if (now - last >= 300) { last = now; val snap = scanner.snapshot(); post { channels = snap } }
            }

            override fun close() {
                runCatching { track.stop(); track.release() }
                post { channels = emptyList() }
            }
        }
    }

    internal fun setForTest(list: List<AirChannel>) { channels = list; markRunningForTest() }

    companion object {
        /** Window centres: 1.8 MHz each, together the whole band (121.5 MHz, the distress frequency, in the second). */
        val WINDOWS = listOf(118_900_000L, 120_700_000L, 122_500_000L, 124_300_000L, 126_100_000L, 127_900_000L, 129_700_000L, 131_500_000L, 133_300_000L, 135_100_000L, 136_100_000L)

        /**
         * The channel as pilots and charts write it: a 25 kHz channel exactly, an 8.33 kHz one rounded to the 5 kHz name
         * it is published under (118.00833 MHz is "118.010").
         */
        fun channelName(hz: Long): String = "%.3f".format(Locale.US, Math.round(hz / 5_000.0) * 5_000 / 1e6)
    }
}

@Composable
fun AirbandTool(vm: MainViewModel) {
    val c = vm.tools.airband
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${c.channels.size}", style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(P(R.plurals.p_airband_channels, c.channels.size), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text(
                "%.1f – %.1f MHz".format(Locale.getDefault(), (c.centerHz - AirbandScanner.HALF_SPAN_HZ) / 1e6, (c.centerHz + AirbandScanner.HALF_SPAN_HZ) / 1e6),
                style = rf(14, 20),
            )
            Row(Modifier.padding(top = 12.dp)) {
                val playing = c.channels.firstOrNull { it.playing }
                when {
                    c.error != null -> HeroChip(c.error!!, AntTheme.net.poor)
                    c.starting -> HeroChip(S(R.string.starting_the_hackrf), AntTheme.net.fair, blink = true)
                    c.running && playing != null -> HeroChip(S(R.string.airband_playing, AirbandController.channelName(playing.hz)), AntTheme.net.good, blink = true)
                    c.running -> HeroChip(S(R.string.airband_listening), AntTheme.net.good, blink = true)
                    device == null -> HeroChip(S(R.string.no_hackrf_plugged_in), AntTheme.net.poor)
                    else -> HeroChip(S(R.string.ready, device!!.name), AntTheme.net.good)
                }
            }
        }
        val d = device
        if (c.running || c.starting) StartButton(S(R.string.stop), Sym.Stop) { c.stop() }
        else StartButton(S(R.string.start_receiving), Sym.PlayArrow, enabled = d != null) { d?.let(c::start) }
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(S(R.string.airband_window), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AirbandController.WINDOWS.forEach { w ->
                        AntFilterChip("%.1f".format(Locale.getDefault(), (w - AirbandScanner.HALF_SPAN_HZ) / 1e6) + "–" + "%.1f".format(Locale.getDefault(), (w + AirbandScanner.HALF_SPAN_HZ) / 1e6), c.centerHz == w, { if (!c.starting) c.chooseWindow(w) })
                    }
                }
                Text(S(R.string.listen_volume), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                Slider(c.volume, { c.setVolumeLevel(it) }, valueRange = 0f..1f)
            }
        }
        if (c.channels.isNotEmpty()) {
            SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
                Text(S(R.string.airband_channels_title), style = rf(14, 20, 600))
                Text(S(R.string.airband_pin_hint), style = rf(12, 16), color = cs.onSurfaceVariant)
                c.channels.forEachIndexed { i, ch ->
                    if (i > 0) Hairline()
                    ChannelRow(ch, c.pinned == ch.hz) { c.pin(ch.hz) }
                }
            }
        } else if (c.running) {
            com.allnetworktools.ui.tools.ToolEmpty(Sym.Flight, S(R.string.airband_none_yet), S(R.string.airband_none_yet_body), null)
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(S(R.string.airband_how_it_works), style = rf(13, 18), color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ChannelRow(ch: AirChannel, pinned: Boolean, onClick: () -> Unit) {
    val acc = AntTheme.accent
    val now = System.currentTimeMillis()
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LeadingIcon(
            if (ch.playing) Sym.VolumeUp else if (pinned) Sym.PushPin else Sym.Flight,
            if (ch.keyed) acc.accent else acc.container, if (ch.keyed) acc.onAccent else acc.onContainer, 40.dp, RoundedCornerShape(12.dp), 22.dp,
        )
        Column(Modifier.weight(1f)) {
            Text(AirbandController.channelName(ch.hz) + " MHz", style = rf(15, 20, 600, tnum = true))
            Text(
                listOfNotNull(
                    if (ch.keyed) S(R.string.airband_on_air) else S(R.string.airband_ago, ((now - ch.lastHeardMs) / 1000).coerceAtLeast(0)),
                    S(R.string.airband_talk, ch.talkMs / 1000),
                    "%.0f dBFS".format(Locale.getDefault(), ch.peakDb),
                    if (pinned) S(R.string.airband_pinned) else null,
                ).joinToString(" · "),
                style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant,
            )
        }
    }
}
