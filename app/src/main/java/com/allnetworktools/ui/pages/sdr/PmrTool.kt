package com.allnetworktools.ui.pages.sdr

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.R
import com.allnetworktools.data.sdr.Dmr
import com.allnetworktools.data.sdr.Dpmr
import com.allnetworktools.data.sdr.Pmr446
import com.allnetworktools.data.sdr.Pmr446Receiver
import com.allnetworktools.data.sdr.PmrEvent
import com.allnetworktools.data.sdr.SdrRepository
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.LeadingIcon
import com.allnetworktools.ui.components.LevelBar
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
import com.allnetworktools.util.S
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope

/** One PMR446 channel as last seen: level over the band's floor, what it carried, its CTCSS tone. */
data class PmrChannelState(val ch: Int, val hz: Long, val overDb: Double, val lastMs: Long, val kind: String?, val ctcss: Double?)

/** A digital call or its end, as the signalling gave it. */
data class PmrCall(val atMs: Long, val hz: Long, val dmr: Boolean, val cc: Int?, val group: Boolean, val src: Int?, val dst: Int?, val slot: Int, val emergency: Boolean, val encrypted: Boolean, val end: Boolean)

/**
 * The PMR446 band at once: activity on the 16 analogue channels, CTCSS tones, DMR446 and dPMR446 call signalling with
 * their ids, and the audio of one analogue channel on demand. Kept in memory for the session only.
 */
class PmrController(repo: SdrRepository, scope: CoroutineScope, onAcquire: () -> Unit = {}) :
    SdrRxController(repo, scope, onAcquire, SdrGain(listOf(16, 24, 32, 40), 32, listOf(20, 30, 40), 30, amp = true), "sdr-pmr") {
    var channels by mutableStateOf(List(16) { PmrChannelState(it + 1, Pmr446.analogueHz(it + 1), 0.0, 0L, null, null) })
        private set
    var calls by mutableStateOf<List<PmrCall>>(emptyList())
        private set
    /** The channel being listened to, or null. */
    var listening by mutableStateOf<Int?>(null)
        private set
    @Volatile private var wantListen: Int? = null

    override val sampleRate = Pmr446Receiver.SAMPLE_RATE
    override fun tuneHz() = Pmr446.CENTRE_HZ - sampleRate / 4

    fun listen(ch: Int?) {
        val next = if (listening == ch) null else ch
        listening = next
        wantListen = next
    }

    override fun newSink(): SdrSink {
        val log = ArrayDeque<PmrCall>()
        val kind = HashMap<Int, String>()
        val last = HashMap<Int, Long>()
        var changed = false
        fun chOf(hz: Long) = ((hz - 446_006_250L + 6_250) / 12_500).toInt() + 1
        val rx = Pmr446Receiver { e ->
            val now = System.currentTimeMillis()
            val call: PmrCall? = when (e) {
                is PmrEvent.Dmr -> when (val b = e.burst) {
                    is Dmr.Burst.Header -> PmrCall(now, e.hz, true, b.cc, b.lc.group, b.lc.src, b.lc.dst, e.slot, b.lc.emergency, b.lc.privacy, false)
                    is Dmr.Burst.Terminator -> PmrCall(now, e.hz, true, b.cc, b.lc.group, b.lc.src, b.lc.dst, e.slot, b.lc.emergency, b.lc.privacy, true)
                    is Dmr.Burst.Control -> PmrCall(now, e.hz, true, b.cc, false, b.csbk.src, b.csbk.dst, e.slot, false, false, false)
                    is Dmr.Burst.Other -> null
                }
                is PmrEvent.DmrVoice -> null
                is PmrEvent.DpmrHeader -> PmrCall(now, e.hz, false, e.cc, e.header.callType != 0, e.header.own, e.header.called, 0, e.header.emergency, false, false)
                is PmrEvent.DpmrEnd -> PmrCall(now, e.hz, false, null, true, e.own, null, 0, false, false, true)
            }
            val ch = chOf(e.hz)
            kind[ch] = if (e is PmrEvent.Dmr || e is PmrEvent.DmrVoice) "DMR" else "dPMR"
            last[ch] = now
            if (call != null) {
                // A header or terminator is sent several times: one line per call and end.
                val prev = log.firstOrNull()
                if (prev == null || prev.src != call.src || prev.dst != call.dst || prev.end != call.end || prev.hz != call.hz || now - prev.atMs > 5_000) {
                    log.addFirst(call); changed = true
                    while (log.size > 300) log.removeLast()
                }
            }
        }
        var audio: AudioTrack? = null
        var audioCh: Int? = null
        var hp = 0f
        var hpIn = 0f
        var lp = 0f
        val pcm = FloatArray(1024)
        return object : SdrSink {
            override fun feed(buf: ByteArray, len: Int) {
                val want = wantListen
                if (want != audioCh) {
                    rx.chains.values.forEach { it.audio = null }
                    audio?.let { it.stop(); it.release() }
                    audio = null
                    audioCh = want
                    if (want != null) {
                        val track = AudioTrack.Builder()
                            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(25_000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(25_000).build()
                        track.play()
                        audio = track
                        rx.ensure(Pmr446.analogueHz(want), false).audio = { x, n ->
                            // 300 Hz high-pass (the CTCSS tone out), de-emphasis, then ±3 kHz of deviation to full scale.
                            for (i in 0 until n) {
                                hp = 0.93f * (hp + x[i] - hpIn); hpIn = x[i]
                                lp += 0.45f * (hp - lp)
                                pcm[i] = (lp / 3000f).coerceIn(-1f, 1f)
                            }
                            audio?.write(pcm, 0, n, AudioTrack.WRITE_NON_BLOCKING)
                        }
                    }
                }
                rx.feed(buf, len)
            }

            override fun tick(nowMs: Long) {
                val floor = rx.slotDb.sorted()[8]
                val list = (1..16).map { ch ->
                    val over = maxOf(rx.slotDb[2 * ch - 2], rx.slotDb[2 * ch - 1]) - floor
                    if (over > 10) last[ch] = nowMs
                    val chain = rx.chains[Pmr446.analogueHz(ch)]
                    val tone = chain?.ctcss()
                    if (tone != null && kind[ch] == null) kind[ch] = "FM"
                    if (chain == null && nowMs - (last[ch] ?: 0L) > 30_000) kind.remove(ch)
                    PmrChannelState(ch, Pmr446.analogueHz(ch), over, last[ch] ?: 0L, kind[ch] ?: if (over > 10) "FM" else null, tone)
                }
                val c = if (changed) { changed = false; log.toList() } else null
                post { channels = list; if (c != null) calls = c }
            }

            override fun close() {
                audio?.let { it.stop(); it.release() }
                audio = null
                wantListen = null
                post { listening = null }
            }
        }
    }

    internal fun setForTest(ch: List<PmrChannelState>, log: List<PmrCall>) { channels = ch; calls = log; markRunningForTest() }
}

@Composable
fun PmrTool(vm: MainViewModel) {
    val c = vm.tools.pmr
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()
    val active = c.channels.count { it.overDb > 10 }
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("$active", style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(S(R.string.pmr_active), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text(S(R.string.pmr_heard, c.channels.count { it.lastMs > 0 }, c.calls.size), style = rf(14, 20))
            Row(Modifier.padding(top = 12.dp)) {
                when {
                    c.error != null -> HeroChip(c.error!!, AntTheme.net.poor)
                    c.starting -> HeroChip(S(R.string.starting_the_hackrf), AntTheme.net.fair, blink = true)
                    c.running -> HeroChip(c.listening?.let { S(R.string.pmr_listening_to, it) } ?: S(R.string.pmr_listening), AntTheme.net.good, blink = true)
                    device == null -> HeroChip(S(R.string.no_hackrf_plugged_in), AntTheme.net.poor)
                    else -> HeroChip(S(R.string.ready, device!!.name), AntTheme.net.good)
                }
            }
        }
        val d = device
        if (c.running || c.starting) StartButton(S(R.string.stop), Sym.Stop) { c.stop() }
        else StartButton(S(R.string.start_receiving), Sym.PlayArrow, enabled = d != null) { d?.let(c::start) }
        SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) {
            Text(S(R.string.pmr_tap_to_listen), Modifier.padding(bottom = 6.dp), style = rf(12, 16), color = cs.onSurfaceVariant)
            c.channels.forEachIndexed { i, ch ->
                if (i > 0) Hairline()
                ChannelRow(ch, c.listening == ch.ch, c.running, now) { c.listen(ch.ch) }
            }
        }
        if (c.calls.isNotEmpty()) {
            SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
                c.calls.take(100).forEachIndexed { i, call ->
                    if (i > 0) Hairline()
                    CallRow(call)
                }
            }
        }
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GainSettings(c.gain) { c.applyGains() }
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(S(R.string.pmr_how_it_works), style = rf(13, 18), color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ChannelRow(s: PmrChannelState, listening: Boolean, running: Boolean, now: Long, onTap: () -> Unit) {
    val acc = AntTheme.accent
    val on = s.overDb > 10
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = running, onClick = onTap).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(if (on) acc.accent else acc.container),
            contentAlignment = Alignment.Center,
        ) { Text("${s.ch}", style = rf(14, 18, 600, tnum = true), color = if (on) acc.onAccent else acc.onContainer) }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("%.5f".format(Locale.getDefault(), s.hz / 1e6), style = rf(13, 18, 500, tnum = true))
                s.kind?.let { Text(it, style = rf(12, 16, 600), color = acc.accent) }
                s.ctcss?.let { tone ->
                    val code = Pmr446.ctcss.indexOfFirst { it == tone } + 1
                    Text("CTCSS %.1f Hz (%d)".format(Locale.getDefault(), tone, code), style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant)
                }
            }
            LevelBar((s.overDb.toFloat() / 40f).coerceIn(0f, 1f), if (on) acc.accent else cs.outlineVariant, Modifier.fillMaxWidth().padding(top = 4.dp), height = 6.dp)
        }
        Column(Modifier.width(64.dp), horizontalAlignment = Alignment.End) {
            if (listening) Symbol(Sym.VolumeUp, size = 20.dp, tint = acc.accent)
            else if (s.lastMs > 0) {
                val ago = ((now - s.lastMs) / 1000).coerceAtLeast(0)
                Text(if (ago <= 2) S(R.string.just_now) else S(R.string.s_ago, ago.toString()), style = rf(11, 14, tnum = true), color = cs.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun CallRow(c: PmrCall) {
    val acc = AntTheme.accent
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LeadingIcon(if (c.end) Sym.Stop else Sym.Mic, if (c.emergency) cs.errorContainer else acc.container, if (c.emergency) cs.onErrorContainer else acc.onContainer, 40.dp, RoundedCornerShape(12.dp), 22.dp)
        Column(Modifier.weight(1f)) {
            val id = { v: Int? -> v?.let { if (c.dmr) "$it" else Dpmr.idText(it) } ?: "?" }
            val what = when {
                c.end -> S(R.string.pmr_end_of_call)
                c.group -> S(R.string.pmr_group_call, id(c.dst))
                else -> S(R.string.pmr_private_call, id(c.dst))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${if (c.dmr) "DMR" else "dPMR"} · $what", Modifier.weight(1f), style = rf(15, 20, 600), maxLines = 1)
                Text(DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(c.atMs)), style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant)
            }
            Text(
                listOfNotNull(
                    c.src?.let { S(R.string.pmr_from, id(it)) },
                    "%.5f MHz".format(Locale.getDefault(), c.hz / 1e6),
                    c.cc?.let { "CC $it" },
                    if (c.slot > 0) "TS ${c.slot}" else null,
                    if (c.emergency) S(R.string.pmr_emergency) else null,
                    if (c.encrypted) S(R.string.pmr_encrypted) else null,
                ).joinToString(" · "),
                style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant,
            )
        }
    }
}
