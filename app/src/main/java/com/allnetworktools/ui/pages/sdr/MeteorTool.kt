package com.allnetworktools.ui.pages.sdr

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.data.orbit.MeteorPass
import com.allnetworktools.data.orbit.MeteorPredictor
import com.allnetworktools.data.orbit.MeteorSats
import com.allnetworktools.data.orbit.Observer
import com.allnetworktools.data.orbit.WeatherTleRepository
import com.allnetworktools.data.sdr.ChannelImage
import com.allnetworktools.data.sdr.HackRf
import com.allnetworktools.data.sdr.LrptReceiver
import com.allnetworktools.data.sdr.LrptStatus
import com.allnetworktools.data.sdr.SdrDevice
import com.allnetworktools.data.sdr.SdrRepository
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.pages.TopBarAction
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
import com.allnetworktools.util.Export
import com.allnetworktools.util.plural
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.allnetworktools.R
import com.allnetworktools.util.S
import com.allnetworktools.util.P

/** One received channel as a growing bitmap (784 pixels wide, 4 per strip of 8 lines). */
class ChannelView(val apid: Int) {
    var bitmap: Bitmap = createBitmap(ChannelImage.WIDTH, 4 * 128)
        private set
    private var capacity = 128
    var rows by mutableIntStateOf(0) // strips
        private set
    var version by mutableIntStateOf(0)
        private set

    fun put(row: Int, strip: ByteArray) {
        if (row >= capacity) {
            val newCap = maxOf(row + 1, capacity * 2).coerceAtMost(ChannelImage.MAX_STRIPS + 1)
            val bigger = createBitmap(ChannelImage.WIDTH, 4 * newCap)
            AndroidCanvas(bigger).drawBitmap(bitmap, 0f, 0f, null)
            bitmap = bigger
            capacity = newCap
        }
        val px = IntArray(ChannelImage.WIDTH * 4) { val v = strip[it].toInt() and 0xFF; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        bitmap.setPixels(px, 0, ChannelImage.WIDTH, 0, row * 4, ChannelImage.WIDTH, 4)
        if (row + 1 > rows) rows = row + 1
        version++
    }

    val label: String get() = if (apid in 64..69) S(R.string.channel, apid - 63) else "APID $apid"
}

/**
 * Meteor-M weather satellite images (LRPT, 72 ksymbol/s QPSK at 137.1 or 137.9 MHz). The HackRF captures 2.304 MS/s with
 * the channel fs/4 above the tuned frequency.
 */
class MeteorController(
    private val repo: SdrRepository,
    private val tles: WeatherTleRepository,
    private val scope: CoroutineScope,
    private val onAcquire: () -> Unit = {},
) {
    var frequencyMhz by mutableStateOf("137.900")
    val gain = SdrGain(listOf(16, 24, 32, 40), 32, listOf(20, 30, 40, 50), 30, amp = false)
    var lnaGain by gain::lna
    var vgaGain by gain::vga
    var amp: Boolean
        get() = gain.amp == true
        set(v) { gain.amp = v }
    var running by mutableStateOf(false)
        private set
    var starting by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var status by mutableStateOf(LrptStatus())
        private set
    var listeningHz by mutableStateOf<Long?>(null)
        private set
    var startedAtMs by mutableLongStateOf(0L)
        private set

    val channels = mutableStateMapOf<Int, ChannelView>()
    var selectedApid by mutableStateOf<Int?>(null)

    var passes by mutableStateOf<List<MeteorPass>>(emptyList())
        private set
    var passesLoading by mutableStateOf(false)
        private set
    var passesError by mutableStateOf<String?>(null)
        private set

    private var radio: HackRf? = null
    @Volatile private var dspRunning = false
    private val stripQueue = ConcurrentLinkedQueue<Triple<Int, Int, ByteArray>>()

    fun parsedHz(): Long? = frequencyMhz.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in 130.0..140.0 }?.let { (it * 1e6).roundToLong() }

    fun start(device: SdrDevice) {
        if (running || starting) return
        val hz = parsedHz() ?: run { error = S(R.string.invalid_frequency_meteor_m_137_100); return }
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
                listeningHz = hz
                startedAtMs = System.currentTimeMillis()
                status = LrptStatus()
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
        val rx = LrptReceiver { apid, row, strip -> stripQueue.add(Triple(apid, row, strip)) }
        dspRunning = true
        Thread({
            var last = 0L
            while (dspRunning) {
                val item = full.poll(200, TimeUnit.MILLISECONDS)
                if (item != null) {
                    rx.feed(item.first, item.second)
                    free.offer(item.first)
                }
                val now = System.currentTimeMillis()
                if (now - last >= 1000) {
                    last = now
                    val st = rx.status()
                    scope.launch { publish(st) }
                }
            }
        }, "sdr-lrpt").start()
        gain.attach { applyGains() }
        r.startRx(
            onSamples = { data, len -> gain.feed(data, len); free.poll()?.let { b -> System.arraycopy(data, 0, b, 0, len); full.offer(b to len) } },
            onError = { msg -> scope.launch { error = msg; stop() } },
        )
    }

    private fun publish(st: LrptStatus) {
        status = st
        while (true) {
            val (apid, row, strip) = stripQueue.poll() ?: break
            channels.getOrPut(apid) { ChannelView(apid) }.put(row, strip)
            if (selectedApid == null) selectedApid = apid
        }
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
        listeningHz = null
        if (r != null) scope.launch(Dispatchers.IO) { r.close() }
    }

    fun clearImages() {
        channels.clear(); selectedApid = null
    }

    /** Passes of the next 24 hours seen from [obs], from CelesTrak's weather group. */
    fun loadPasses(obs: Observer, force: Boolean = false) {
        if (passesLoading) return
        passesLoading = true
        passesError = null
        scope.launch {
            try {
                val all = tles.load(force)
                val now = System.currentTimeMillis()
                val list = withContext(Dispatchers.Default) {
                    MeteorSats.all.flatMap { sat ->
                        val tle = all.firstOrNull { it.catalog == sat.catalog } ?: return@flatMap emptyList()
                        MeteorPredictor.passes(tle, sat, obs, now - 10 * 60_000L, now + 24 * 3_600_000L)
                    }.filter { it.setMs > now }.sortedBy { it.riseMs }
                }
                passes = list
                if (list.isEmpty() && all.none { t -> MeteorSats.all.any { it.catalog == t.catalog } }) passesError = S(R.string.meteor_m_orbital_elements_not_found)
            } catch (e: Exception) {
                passesError = S(R.string.orbital_elements_unavailable, e.message ?: S(R.string.offline))
            } finally {
                passesLoading = false
            }
        }
    }

    internal fun setForTest(st: LrptStatus, hz: Long, list: List<MeteorPass>, images: Map<Int, List<ByteArray>>) {
        status = st; listeningHz = hz; passes = list; running = true
        for ((apid, strips) in images) { val v = ChannelView(apid); strips.forEachIndexed { i, s -> v.put(i, s) }; channels[apid] = v }
        selectedApid = images.keys.minOrNull()
    }

    companion object {
        private const val SAMPLE_RATE = 2_304_000
        private const val POOL = 32
    }
}

private val timeFmt = SimpleDateFormat("EEE HH:mm", Locale.getDefault())
private val clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun mhz(hz: Double) = "%.3f".format(Locale.getDefault(), hz)

private fun countdown(ms: Long): String {
    val m = (ms / 60_000).coerceAtLeast(0)
    return if (m < 60) "$m min" else "${m / 60} h ${"%02d".format(m % 60)}"
}

@Composable
fun MeteorTool(vm: MainViewModel) {
    val c = vm.tools.meteor
    val actions = LocalActions.current
    val shown = c.channels[c.selectedApid] ?: c.channels.values.minByOrNull { it.apid }
    if (shown != null && shown.rows > 0) TopBarAction(Sym.Download) {
        // The bitmap grows by whole strips; only the part already received is saved.
        val image = Bitmap.createBitmap(shown.bitmap, 0, 0, shown.bitmap.width, minOf(shown.rows * 4, shown.bitmap.height))
        val now = System.currentTimeMillis()
        val label = shown.label.lowercase(Locale.ROOT).replace(' ', '-')
        actions.saveFile("meteor-$label-${Export.stamp(now)}.png", "image/png") {
            java.io.ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
    }
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    val positions by vm.positions.collectAsStateWithLifecycle()
    val loc = positions.fused ?: positions.gnss ?: positions.network
    val st = c.status
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(15_000); now = System.currentTimeMillis() } }
    LaunchedEffect(loc != null) {
        if (loc != null && c.passes.isEmpty() && !c.passesLoading) c.loadPasses(Observer(loc.latitude, loc.longitude, loc.altitude))
    }
    PageColumn {
        HeroCard {
            val head = when {
                !c.running -> "Meteor-M"
                st.locked -> S(R.string.locked_on_the_satellite)
                st.carrier -> S(R.string.signal_detected)
                else -> S(R.string.listening_2)
            }
            Text(head, style = gs(30, 36, 500))
            Text(
                "LRPT 72 kbit/s · ${c.listeningHz?.let { mhz(it / 1e6) } ?: c.frequencyMhz.replace('.', ',')} MHz",
                Modifier.padding(top = 2.dp), style = rf(14, 20),
            )
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    c.error != null -> HeroChip(c.error!!, AntTheme.net.poor)
                    c.starting -> HeroChip(S(R.string.starting_the_hackrf), AntTheme.net.fair, blink = true)
                    c.running && st.locked -> HeroChip("${st.rsOk} ${P(R.plurals.p_valid_frames, st.rsOk)}", AntTheme.net.good, blink = true)
                    c.running && st.carrier -> HeroChip(S(R.string.signal_at_db).format(Locale.getDefault(), st.snrDb), AntTheme.net.fair, blink = true)
                    c.running -> HeroChip(S(R.string.no_satellite_audible), AntTheme.net.fair, blink = true)
                    device == null -> HeroChip(S(R.string.no_hackrf_plugged_in), AntTheme.net.poor)
                    else -> HeroChip(S(R.string.ready, device!!.name), AntTheme.net.good)
                }
            }
        }
        val d = device
        if (c.running || c.starting) StartButton(S(R.string.stop), Sym.Stop) { c.stop() }
        else StartButton(S(R.string.start_receiving), Sym.PlayArrow, enabled = d != null && c.parsedHz() != null) { d?.let(c::start) }

        if (c.channels.isNotEmpty()) ImageCard(c)
        StagesCard(st, c.running)
        PassesCard(c, now, loc?.let { Observer(it.latitude, it.longitude, it.altitude) })

        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(S(R.string.satellite), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MeteorSats.all.forEach { s ->
                        val f = "%.3f".format(Locale.US, s.freqMhz)
                        AntFilterChip("${s.name} · ${mhz(s.freqMhz)}", c.frequencyMhz == f, { if (c.frequencyMhz != f && !c.starting) { if (c.running) c.stop(); c.frequencyMhz = f } })
                    }
                }
                if (!c.running) HostInputField(c.frequencyMhz, { c.frequencyMhz = it }, S(R.string.frequency_mhz), Sym.Stream, keyboardType = KeyboardType.Decimal)
                GainSettings(c.gain) { c.applyGains() }
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(
                    S(R.string.meteor_m_satellites_broadcast_their_images) +
                        S(R.string.above_the_horizon_a_pass_lasts) +
                        S(R.string.a_vhf_antenna_in_the_clear) +
                        S(R.string.this_decoding_is_experimental_if_no),
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ImageCard(c: MeteorController) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val list = c.channels.values.sortedBy { it.apid }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                list.forEach { ch -> AntFilterChip("${ch.label} · ${ch.rows}", c.selectedApid == ch.apid, { c.selectedApid = ch.apid }) }
            }
            val ch = c.channels[c.selectedApid] ?: list.first()
            val v = ch.version // redraw as strips arrive
            val image = remember(ch.bitmap) { ch.bitmap.asImageBitmap() }
            val aspect = ChannelImage.WIDTH.toFloat() / (4 * ch.rows.coerceAtLeast(1))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.Black)) {
                Canvas(Modifier.fillMaxWidth().androidxAspect(aspect.coerceAtLeast(0.2f))) {
                    if (v >= 0) {
                        drawImage(
                            image, IntOffset.Zero, IntSize(ChannelImage.WIDTH, 4 * ch.rows.coerceAtLeast(1)),
                            dstOffset = IntOffset.Zero, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Medium,
                        )
                    }
                }
            }
            Text(
                S(R.string.of_8_lines_half_resolution_the, ch.label, ch.rows, P(R.plurals.p_strips, ch.rows)),
                style = rf(12, 16), color = cs.onSurfaceVariant,
            )
            ToolButtons(ToolButton(S(R.string.clear), Sym.Delete, BtnKind.OutlineOnSurface) { c.clearImages() })
        }
    }
}

private fun Modifier.androidxAspect(ratio: Float): Modifier = this.then(Modifier.aspectRatio(ratio))

@Composable
private fun StagesCard(st: LrptStatus, running: Boolean) {
    SectionCard(padding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)) {
        val apids = st.apids.filterKeys { it in 64..69 }
        val strips = st.strips.values.sum()
        val rows = listOf(
            Stage(
                S(R.string.qpsk_signal), st.carrier,
                if (st.carrier) S(R.string.offset_khz_signal_to_noise_ratio).format(Locale.getDefault(), st.cfoHz / 1000, st.snrDb)
                else if (running) S(R.string.waiting_for_a_72_ksymbol_s).format(Locale.getDefault(), st.lineRatio, st.symbolLineRatio)
                else S(R.string.stopped_2),
            ),
            Stage(
                S(R.string.frame_synchronisation), st.locked,
                if (st.locked) S(R.string.marker_found_phase, st.hypothesis) else if (st.carrier) S(R.string.looking_for_the_frame_marker) else "—",
            ),
            Stage(
                S(R.string.error_correction), st.rsOk > 0,
                if (st.frames > 0) S(R.string.bytes_corrected, st.rsOk, P(R.plurals.p_valid_frames, st.rsOk), st.rsFail, P(R.plurals.p_lost, st.rsFail), st.corrected) +
                    (st.basis?.let { " · $it" } ?: "")
                else "—",
            ),
            Stage(
                S(R.string.packets_2), st.packets > 0,
                if (st.packets > 0) "${st.packets} · " + (if (apids.isEmpty()) S(R.string.no_image_packet_yet) else apids.keys.sorted().joinToString { S(R.string.channel_2, it - 63) }) else "—",
            ),
            Stage(
                S(R.string.images), strips > 0,
                if (st.segmentsOk + st.segmentsBad > 0) S(R.string.segments, st.segmentsOk, strips, P(R.plurals.p_strips, strips)) +
                    (if (st.segmentsBad > 0) S(R.string.unreadable, st.segmentsBad) else "") else "—",
            ),
        )
        rows.forEachIndexed { i, s ->
            if (i > 0) Hairline()
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(if (s.done) Sym.CheckCircle else Sym.RadioUnchecked, size = 22.dp, filled = s.done, tint = if (s.done) AntTheme.net.good else cs.outline)
                Column(Modifier.weight(1f)) {
                    Text(s.title, style = rf(14, 20, 600))
                    Text(s.detail, style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

private class Stage(val title: String, val done: Boolean, val detail: String)

@Composable
private fun PassesCard(c: MeteorController, now: Long, obs: Observer?) {
    SectionCard(padding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(S(R.string.next_passes), Modifier.weight(1f), style = rf(14, 20, 600))
            if (obs != null) Text(
                if (c.passesLoading) S(R.string.computing) else S(R.string.refresh), style = rf(13, 18, 600), color = AntTheme.accent.accent,
                modifier = Modifier.clickableNoRipple { c.loadPasses(obs, force = true) },
            )
        }
        when {
            obs == null -> Text(S(R.string.the_phone_s_location_is_needed), Modifier.padding(bottom = 8.dp), style = rf(13, 18), color = cs.onSurfaceVariant)
            c.passesError != null -> Text(c.passesError!!, Modifier.padding(bottom = 8.dp), style = rf(13, 18), color = cs.error)
            c.passes.isEmpty() -> Text(if (c.passesLoading) S(R.string.downloading_orbital_elements) else S(R.string.no_good_pass_above_15_elevation), Modifier.padding(bottom = 8.dp), style = rf(13, 18), color = cs.onSurfaceVariant)
            else -> c.passes.take(6).forEachIndexed { i, p ->
                if (i > 0) Hairline()
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Symbol(Sym.SatelliteAlt, size = 22.dp, tint = if (p.maxElevation >= 40) AntTheme.net.good else if (p.maxElevation >= 25) AntTheme.net.fair else cs.onSurfaceVariant)
                    Column(Modifier.weight(1f)) {
                        Text("${p.sat.name} · ${mhz(p.sat.freqMhz)} MHz", style = rf(14, 20, 600))
                        Text(
                            "${timeFmt.format(Date(p.riseMs))} → ${clockFmt.format(Date(p.setMs))} · ${((p.setMs - p.riseMs) / 60_000.0).roundToInt()} min · max ${p.maxElevation.roundToInt()}°",
                            style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant,
                        )
                    }
                    Text(if (now in p.riseMs..p.setMs) S(R.string.in_progress) else S(R.string.in_text) + countdown(p.riseMs - now), style = rf(12, 16, 600, tnum = true), color = if (now in p.riseMs..p.setMs) AntTheme.net.good else cs.onSurfaceVariant)
                }
            }
        }
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.then(Modifier.clickable(onClick = onClick))
