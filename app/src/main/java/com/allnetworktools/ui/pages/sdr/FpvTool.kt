package com.allnetworktools.ui.pages.sdr

import com.allnetworktools.ui.pages.ToolBack
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.data.sdr.AnalogDrone
import com.allnetworktools.data.sdr.AnalogDroneTracker
import com.allnetworktools.data.sdr.AnalogVideoDecoder
import com.allnetworktools.data.sdr.DjiDetection
import com.allnetworktools.data.sdr.DjiTracker
import com.allnetworktools.data.sdr.DroneBands
import com.allnetworktools.data.sdr.DroneIdDetector
import com.allnetworktools.data.sdr.DroneWindow
import com.allnetworktools.data.sdr.FpvChannel
import com.allnetworktools.data.sdr.FpvChannels
import com.allnetworktools.data.sdr.FpvResult
import com.allnetworktools.data.sdr.HackRf
import com.allnetworktools.data.sdr.RcBands
import com.allnetworktools.data.sdr.RcBurstDetector
import com.allnetworktools.data.sdr.RcLink
import com.allnetworktools.data.sdr.RcLinkTracker
import com.allnetworktools.data.sdr.SdrDevice
import com.allnetworktools.data.sdr.SdrRepository
import com.allnetworktools.data.sdr.iqPowerDb
import com.allnetworktools.data.sdr.levelTrend
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.InfoList
import com.allnetworktools.ui.components.InfoRow
import com.allnetworktools.ui.components.LeadingIcon
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.SegmentedRow
import com.allnetworktools.ui.components.Sparkline
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.TechChip
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.BtnKind
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.StartButton
import com.allnetworktools.ui.tools.ToolButton
import com.allnetworktools.ui.tools.ToolButtons
import com.allnetworktools.util.plural
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.allnetworktools.R
import com.allnetworktools.util.S
import com.allnetworktools.util.P

enum class FpvTab(val label: String) { Dji("DJI"), Analog(S(R.string.analog)), Rc(S(R.string.rc_link)) }

/**
 * Drones, with the HackRF only: analogue FPV video (5.8 and 2.4 GHz, a drone is a channel carrying real video sync) and
 * DJI (the DroneID burst). Only drones are shown, never raw signals. Receive only; nothing is recorded.
 */
class FpvController(private val repo: SdrRepository, private val scope: CoroutineScope, private val onAcquire: () -> Unit = {}) {
    var tab by mutableStateOf(FpvTab.Dji)
    val gain = SdrGain(listOf(0, 8, 16, 24, 32, 40), 24, listOf(10, 20, 30, 40), 24, amp = false)
    var lnaGain by gain::lna
    var vgaGain by gain::vga
    var amp: Boolean
        get() = gain.amp == true
        set(v) { gain.amp = v }
    var band by mutableIntStateOf(0) // DroneID: 24, 58 or 0 for both
    var rcBand by mutableIntStateOf(0) // remote controls: 24, 868, 915 or 0 for all
    var analogBand by mutableIntStateOf(58) // analogue scan: 24, 58 or 0 for both

    var running by mutableStateOf(false)
        private set
    var starting by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var usbMBps by mutableStateOf(0.0)
        private set

    // Analogue.
    var drones by mutableStateOf<List<AnalogDrone>>(emptyList())
        private set
    var scanning by mutableStateOf<String?>(null)
        private set
    var sweeps by mutableIntStateOf(0)
        private set
    var watching by mutableStateOf<FpvChannel?>(null)
        private set
    var frame by mutableStateOf<ImageBitmap?>(null)
        private set
    var frameTick by mutableIntStateOf(0)
        private set

    // DJI.
    var dji by mutableStateOf<DjiDetection?>(null)
        private set
    var windowMhz by mutableStateOf<Double?>(null)
        private set
    var digitalSweeps by mutableIntStateOf(0)
        private set

    // Remote controls.
    var rcLinks by mutableStateOf<List<RcLink>>(emptyList())
        private set
    var rcSweeps by mutableIntStateOf(0)
        private set

    private val analog = AnalogDroneTracker()
    private val djiTracker = DjiTracker()
    private val rcTracker = RcLinkTracker()
    private var radio: HackRf? = null
    @Volatile private var dspRunning = false
    @Volatile private var mode: Any = Mode.Scan
    private val usbBytes = java.util.concurrent.atomic.AtomicLong()

    private enum class Mode { Scan, Digital, Rc }
    private class Watch(val channel: FpvChannel)

    /** The HackRF is starting or listening. */
    val active: Boolean get() = running || starting

    /** Starts the analogue scan, the DroneID search or the remote-control search, depending on the tab. */
    fun start(device: SdrDevice) = begin(device, when (tab) { FpvTab.Analog -> Mode.Scan; FpvTab.Dji -> Mode.Digital; FpvTab.Rc -> Mode.Rc })

    /** Shows the picture of [ch]; starts the radio if needed. */
    fun watch(device: SdrDevice, ch: FpvChannel) {
        tab = FpvTab.Analog
        if (running) { mode = Watch(ch); watching = ch; frame = null; return }
        begin(device, Watch(ch))
    }

    fun backToScan() {
        if (!running) return
        watching = null
        mode = Mode.Scan
    }

    private fun begin(device: SdrDevice, m: Any) {
        if (running || starting) return
        onAcquire()
        error = null
        starting = true
        scope.launch {
            try {
                if (!repo.hasPermission(device) && !repo.requestPermission(device)) { error = S(R.string.usb_access_to_the_hackrf_refused); return@launch }
                val r = withContext(Dispatchers.IO) { repo.open(device) } ?: run { error = S(R.string.could_not_open_the_hackrf); return@launch }
                val rate = when (m) { Mode.Digital -> DIGITAL_RATE; Mode.Rc -> RcBands.RATE; else -> ANALOG_RATE }
                val lna = lnaGain
                val vga = vgaGain
                val withAmp = amp
                val ok = withContext(Dispatchers.IO) {
                    r.setSampleRate(rate, HackRf.filterFor(rate)) && r.setLnaGain(lna) && r.setVgaGain(vga) && r.setAmp(withAmp)
                }
                if (!ok) { r.close(); error = S(R.string.the_hackrf_did_not_accept_the); return@launch }
                radio = r
                mode = m
                watching = (m as? Watch)?.channel
                frame = null
                startPipeline(r, rate, m)
                running = true
            } finally {
                starting = false
            }
        }
    }

    /** Buffers from USB with their length and where they start in the stream (samples): when processing falls behind, buffers are dropped and the gap shows. */
    private class Pipe(val free: ArrayBlockingQueue<ByteArray>, val full: ArrayBlockingQueue<Triple<ByteArray, Int, Long>>) {
        fun take(ms: Long): Triple<ByteArray, Int, Long>? = full.poll(ms, TimeUnit.MILLISECONDS)
        fun give(b: ByteArray) { free.offer(b) }

        /** Throws away what arrived while the radio was still retuning. */
        fun flush(ms: Long) {
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end) take(10)?.let { give(it.first) }
        }
    }

    private fun startPipeline(r: HackRf, rate: Int, m: Any) {
        val pipe = Pipe(ArrayBlockingQueue(POOL), ArrayBlockingQueue(POOL))
        repeat(POOL) { pipe.free.add(ByteArray(131072)) }
        usbBytes.set(0)
        dspRunning = true
        Thread({
            try {
                when (m) {
                    Mode.Digital -> digitalLoop(r, pipe)
                    Mode.Rc -> rcLoop(r, pipe)
                    else -> analogLoop(r, pipe, rate)
                }
            } catch (e: Exception) {
                scope.launch { error = e.message ?: S(R.string.processing_error) }
            }
        }, "sdr-drones").start()
        gain.attach { applyGains() }
        r.startRx(
            onSamples = { data, len ->
                gain.feed(data, len)
                val at = usbBytes.getAndAdd(len.toLong()) / 2
                pipe.free.poll()?.let { b -> System.arraycopy(data, 0, b, 0, len); pipe.full.offer(Triple(b, len, at)) }
            },
            onError = { msg -> scope.launch { error = msg; stop() } },
        )
    }

    private fun tune(r: HackRf, mhz: Double, pipe: Pipe) {
        r.setFrequency((mhz * 1e6).toLong())
        pipe.flush(SETTLE_MS)
    }

    private var lastTick = 0L
    private var lastBytes = 0L

    private fun tick() {
        val now = System.currentTimeMillis()
        if (now - lastTick >= 1000) {
            val b = usbBytes.get()
            val mb = (b - lastBytes) / ((now - lastTick) / 1000.0) / 1e6
            lastTick = now; lastBytes = b
            scope.launch { usbMBps = mb }
        }
    }

    /** Publishes the drone list; the tracker is only touched from the processing thread. */
    private fun publish() {
        val list = analog.list()
        scope.launch { drones = list }
    }

    private fun analogLoop(r: HackRf, pipe: Pipe, rate: Int) {
        val pixels = IntArray(PIC_W * PIC_H)
        val bitmaps = arrayOf(createBitmap(PIC_W, PIC_H), createBitmap(PIC_W, PIC_H))
        var flip = 0
        var fieldCount = 0
        fun newDecoder(show: Boolean) = AnalogVideoDecoder(
            rate.toDouble(),
            onLine = { field, line, p ->
                if (show) {
                    val y = 2 * (line - FIRST_LINE) + (field and 1)
                    if (y in 0 until PIC_H) for (x in 0 until PIC_W) { val g = p[x].toInt() and 0xFF; pixels[y * PIC_W + x] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
                }
            },
            onField = {
                if (show && ++fieldCount % 3 == 0) {
                    val bmp = bitmaps[flip]
                    flip = flip xor 1
                    bmp.setPixels(pixels, 0, PIC_W, 0, 0, PIC_W, PIC_H)
                    val img = bmp.asImageBitmap()
                    scope.launch { frame = img; frameTick++ }
                }
            },
        )

        /** Listens to [ch] for [ms] and says what is there. */
        fun measure(ch: FpvChannel, ms: Long, m: Any): FpvResult {
            val dec = newDecoder(false)
            var sum = 0.0
            var n = 0
            val until = System.currentTimeMillis() + ms
            while (dspRunning && mode === m && System.currentTimeMillis() < until) {
                val item = pipe.take(100) ?: continue
                sum += iqPowerDb(item.first, item.second); n++
                dec.feed(item.first, item.second)
                pipe.give(item.first)
            }
            val st = dec.stats
            return FpvResult(ch, if (n > 0) sum / n else -120.0, st.syncQuality, st.hPulses, st.standard, System.currentTimeMillis())
        }

        lastTick = System.currentTimeMillis(); lastBytes = 0L
        var tunedTo: Any? = null
        var decoder = newDecoder(true)
        while (dspRunning) {
            val m = mode
            if (m is Watch) {
                if (tunedTo !== m) {
                    tune(r, m.channel.mhz.toDouble(), pipe)
                    pixels.fill(0xFF000000.toInt())
                    decoder = newDecoder(true)
                    tunedTo = m
                }
                var sum = 0.0
                var n = 0
                val until = System.currentTimeMillis() + 500
                while (dspRunning && mode === m && System.currentTimeMillis() < until) {
                    val item = pipe.take(100) ?: continue
                    sum += iqPowerDb(item.first, item.second); n++
                    decoder.feed(item.first, item.second)
                    pipe.give(item.first)
                }
                if (n > 0) {
                    val st = decoder.stats
                    analog.watched(m.channel, sum / n, st.standard, st.syncQuality, System.currentTimeMillis())
                    publish()
                }
                tick()
            } else {
                tunedTo = null
                var complete = true
                for (ch in FpvChannels.scan(analogBand)) {
                    if (!dspRunning || mode !== m) { complete = false; break }
                    scope.launch { scanning = ch.name }
                    tune(r, ch.mhz.toDouble(), pipe)
                    var res = measure(ch, SCAN_DWELL_MS, m)
                    // A hint of sync: a weak or distant drone. A longer listen settles it.
                    if (res.isCandidate) res = measure(ch, RECHECK_DWELL_MS, m)
                    if (analog.found(res, System.currentTimeMillis())) publish()
                    tick()
                }
                if (complete && dspRunning) {
                    analog.endSweep()
                    publish()
                    scope.launch { scanning = null; sweeps++ }
                }
            }
        }
    }

    private fun digitalLoop(r: HackRf, pipe: Pipe) {
        lastTick = System.currentTimeMillis(); lastBytes = 0L
        fun publishDji() {
            val st = djiTracker.state(System.currentTimeMillis())
            scope.launch { dji = st }
        }
        while (dspRunning) {
            for (centre in DroneBands.windows(band)) {
                if (!dspRunning) return
                scope.launch { windowMhz = centre }
                tune(r, centre, pipe)
                val visit = DroneWindow(centre)
                val det = DroneIdDetector(DIGITAL_RATE.toDouble()) { b ->
                    val now = System.currentTimeMillis()
                    visit.add(b, now, 0.0)
                    djiTracker.add(centre, b, now)
                }
                var until = System.currentTimeMillis() + DIGITAL_DWELL_MS
                var extended = false
                while (dspRunning && System.currentTimeMillis() < until) {
                    val item = pipe.take(100) ?: continue
                    det.feed(item.first, item.second)
                    pipe.give(item.first)
                    // A burst here: stay a little longer to catch the next one at the broadcast's rhythm.
                    if (!extended && visit.count > 0) { extended = true; until += DIGITAL_DWELL_MS }
                    tick()
                }
                if (visit.periodic) djiTracker.periodic(System.currentTimeMillis())
                publishDji()
            }
            scope.launch { digitalSweeps++ }
        }
    }

    private fun rcLoop(r: HackRf, pipe: Pipe) {
        lastTick = System.currentTimeMillis(); lastBytes = 0L
        var dwell = 0
        while (dspRunning) {
            for (w in RcBands.windows(rcBand)) {
                if (!dspRunning) return
                scope.launch { windowMhz = w.centreMhz }
                tune(r, w.centreMhz, pipe)
                val visit = dwell++
                val start = System.currentTimeMillis()
                val det = RcBurstDetector(RcBands.RATE.toDouble(), w.centreMhz * 1e6) { b ->
                    rcTracker.add(w.band, visit, b, System.currentTimeMillis())
                }
                val until = start + RC_DWELL_MS
                while (dspRunning && System.currentTimeMillis() < until) {
                    val item = pipe.take(100) ?: continue
                    det.feed(item.first, item.second, item.third)
                    pipe.give(item.first)
                    tick()
                }
                val links = rcTracker.links(System.currentTimeMillis())
                scope.launch { rcLinks = links }
            }
            scope.launch { rcSweeps++ }
        }
    }

    fun applyGains() {
        val r = radio ?: return
        val lna = lnaGain
        val vga = vgaGain
        val withAmp = amp
        scope.launch(Dispatchers.IO) { r.setLnaGain(lna); r.setVgaGain(vga); r.setAmp(withAmp) }
    }

    fun clear() {
        if (running) return
        analog.clear(); djiTracker.clear(); rcTracker.clear()
        drones = emptyList(); dji = null; rcLinks = emptyList(); sweeps = 0; digitalSweeps = 0; rcSweeps = 0
    }

    fun stop() {
        dspRunning = false
        val r = radio
        radio = null
        running = false
        scanning = null
        windowMhz = null
        watching = null
        if (r != null) scope.launch(Dispatchers.IO) { r.close() }
    }

    internal fun setForTest(
        list: List<AnalogDrone>, running: Boolean, watching: FpvChannel? = null, image: ImageBitmap? = null, dji: DjiDetection? = null,
        rc: List<RcLink> = emptyList(),
    ) {
        drones = list; this.running = running; this.watching = watching; frame = image; this.dji = dji; rcLinks = rc
    }

    companion object {
        const val ANALOG_RATE = 16_000_000
        const val DIGITAL_RATE = 20_000_000
        private const val POOL = 64
        private const val SETTLE_MS = 30L
        private const val SCAN_DWELL_MS = 140L
        private const val RECHECK_DWELL_MS = 450L
        private const val DIGITAL_DWELL_MS = 1200L
        private const val RC_DWELL_MS = 1500L
        const val PIC_W = AnalogVideoDecoder.WIDTH
        const val PIC_H = 576
        private const val FIRST_LINE = 16
    }
}

private fun fmt(v: Double, decimals: Int = 0) = "%.${decimals}f".format(Locale.getDefault(), v)

/** "à l'instant", "il y a 12 s", "il y a 3 min". */
private fun ago(ms: Long, now: Long): String {
    val s = ((now - ms) / 1000).coerceAtLeast(0)
    return when {
        s < 3 -> S(R.string.just_now)
        s < 60 -> S(R.string.s_ago, s)
        else -> S(R.string.min_ago, s / 60)
    }
}

private fun duration(fromMs: Long, toMs: Long): String {
    val s = ((toMs - fromMs) / 1000).coerceAtLeast(0)
    return if (s < 60) "$s s" else S(R.string.min_s, s / 60, "%02d".format(s % 60))
}

private fun trendLabel(levels: List<Double>) = when (levelTrend(levels)) {
    1 -> S(R.string.getting_closer_signal_rising)
    -1 -> S(R.string.moving_away_signal_falling)
    else -> S(R.string.steady)
}

@Composable
fun FpvTool(vm: MainViewModel) {
    val c = vm.tools.fpv
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    val d = device
    val now = System.currentTimeMillis()
    val tab = c.tab
    val count = when (tab) {
        FpvTab.Analog -> c.drones.count { !it.lost }
        FpvTab.Dji -> if (c.dji?.confirmed == true) 1 else 0
        FpvTab.Rc -> c.rcLinks.size
    }
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("$count", style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(
                    if (tab == FpvTab.Rc) P(R.plurals.p_rc_links, count) else P(R.plurals.p_drones_detected, count),
                    Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500),
                )
            }
            Text(
                when (tab) {
                    FpvTab.Analog -> c.scanning?.let { S(R.string.searching_on_channel, it) }
                        ?: if (c.sweeps > 0) "${c.sweeps} ${P(R.plurals.p_sweeps_done, c.sweeps)}"
                        else when (c.analogBand) { 24 -> S(R.string.analog_video_2_4_ghz); 0 -> S(R.string.analog_video_2_4_and_5); else -> S(R.string.analog_video_5_8_ghz) }
                    FpvTab.Dji -> c.windowMhz?.let { S(R.string.droneid_around_mhz, fmt(it, 1)) } ?: S(R.string.dji_droneid_2_4_and_5)
                    FpvTab.Rc -> c.windowMhz?.let { S(R.string.listening_around_mhz, fmt(it)) } ?: "ELRS, Crossfire, FrSky, FlySky, Spektrum…"
                },
                style = rf(14, 20, tnum = true),
            )
            Row(Modifier.padding(top = 12.dp)) {
                when {
                    c.error != null -> HeroChip(c.error!!, AntTheme.net.poor)
                    c.starting -> HeroChip(S(R.string.starting_the_hackrf), AntTheme.net.fair, blink = true)
                    c.active && count > 0 && tab == FpvTab.Rc -> HeroChip(if (count == 1) S(R.string.rc_transmitter_on) else S(R.string.rc_transmitters_on, count), AntTheme.net.good, blink = true)
                    c.active && count > 0 -> HeroChip(if (count == 1) S(R.string.drone_nearby) else S(R.string.drones_nearby, count), AntTheme.net.good, blink = true)
                    tab == FpvTab.Dji && c.dji != null -> HeroChip(S(R.string.droneid_burst_confirming), AntTheme.net.fair, blink = true)
                    c.active -> HeroChip(S(R.string.no_drone_yet), AntTheme.net.fair, blink = true)
                    d == null -> HeroChip(S(R.string.no_hackrf_plugged_in), AntTheme.net.poor)
                    else -> HeroChip(S(R.string.ready, d.name), AntTheme.net.good)
                }
            }
        }
        SegmentedRow(FpvTab.entries.map { it to it.label }, c.tab, { if (it != c.tab && !c.starting) { if (c.running) c.stop(); c.tab = it } }, Modifier.fillMaxWidth(), height = 36.dp)
        if (c.active) StartButton(S(R.string.stop), Sym.Stop) { c.stop() }
        else StartButton(
            when (tab) { FpvTab.Analog -> S(R.string.search_for_drones); FpvTab.Dji -> S(R.string.search_for_a_dji_drone); FpvTab.Rc -> S(R.string.search_for_rc_links) },
            Sym.PlayArrow, enabled = d != null,
        ) { d?.let(c::start) }
        when (tab) {
            FpvTab.Analog -> AnalogPage(c, d, now)
            FpvTab.Dji -> DjiPage(c, now)
            FpvTab.Rc -> RcPage(c, now)
        }
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(S(R.string.band_to_watch), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (tab == FpvTab.Analog) listOf(0 to S(R.string.all_2), 24 to S(R.string.n_2_4_ghz), 58 to S(R.string.n_5_8_ghz)).forEach { (b, l) -> AntFilterChip(l, c.analogBand == b, { if (c.analogBand != b && !c.starting) { if (c.running) c.stop(); c.analogBand = b } }) }
                    else if (tab == FpvTab.Dji) listOf(0 to S(R.string.all_2), 24 to S(R.string.n_2_4_ghz), 58 to S(R.string.n_5_8_ghz)).forEach { (b, l) -> AntFilterChip(l, c.band == b, { if (c.band != b && !c.starting) { if (c.running) c.stop(); c.band = b } }) }
                    else listOf(0 to S(R.string.all_2), 868 to "868 MHz", 915 to "915 MHz", 24 to S(R.string.n_2_4_ghz)).forEach { (b, l) -> AntFilterChip(l, c.rcBand == b, { if (c.rcBand != b && !c.starting) { if (c.running) c.stop(); c.rcBand = b } }) }
                }
                GainSettings(c.gain) { c.applyGains() }
                if (c.running) Text(S(R.string.usb_stream_mb_s_expected).format(Locale.getDefault(), c.usbMBps, if (tab == FpvTab.Analog) 32 else 40), style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant)
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(
                    when (tab) {
                        FpvTab.Analog ->
                            S(R.string.a_drone_is_only_reported_if) +
                                S(R.string.a_weak_video_is_listened_to) +
                                S(R.string.altitude_battery_or_gps_only_appear)
                        FpvTab.Dji ->
                            S(R.string.the_hackrf_listens_on_the_frequencies) +
                                S(R.string.the_droneid_content_is_not_decoded) +
                                S(R.string.dji_video_ocusync_o3_o4_is)
                        FpvTab.Rc ->
                            S(R.string.an_rc_link_is_only_shown) +
                                S(R.string.exact_packet_rate_packet_duration_frequency) +
                                S(R.string.bluetooth_wi_fi_packet_contents_are) +
                                S(R.string.a_radio_switched_on_with_no)
                    },
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

/** Header of a drone: icon, name, and the three answers (a drone is there, is there video, is there telemetry). */
@Composable
private fun DroneHeader(icon: String, title: String, subtitle: String, onAir: Boolean, video: String, videoOk: Boolean, telemetry: Boolean) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LeadingIcon(icon, if (onAir) AntTheme.accent.accent else AntTheme.accent.container, if (onAir) AntTheme.accent.onAccent else AntTheme.accent.onContainer)
                Column(Modifier.weight(1f)) {
                    Text(title, style = rf(16, 22, 600), maxLines = 1)
                    Text(subtitle, style = rf(13, 18), color = cs.onSurfaceVariant, maxLines = 2)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val on = AntTheme.accent
                val offBg = cs.surfaceContainerHighest
                val offFg = cs.onSurfaceVariant
                if (onAir) TechChip(S(R.string.drone_present), on.accent, on.onAccent) else TechChip(S(R.string.signal_lost), offBg, offFg)
                TechChip(video, if (videoOk) on.container else offBg, if (videoOk) on.onContainer else offFg)
                TechChip(if (telemetry) S(R.string.telemetry_received) else S(R.string.no_telemetry), if (telemetry) on.container else offBg, if (telemetry) on.onContainer else offFg)
            }
        }
    }
}

@Composable
private fun LevelChart(levels: List<Double>) {
    if (levels.size < 2) return
    Sparkline(levels.map { it.toFloat() }, (levels.min() - 3).toFloat(), (levels.max() + 3).toFloat(), AntTheme.accent.accent, Modifier.fillMaxWidth().padding(vertical = 8.dp).height(48.dp))
}

@Composable
private fun AnalogPage(c: FpvController, d: SdrDevice?, now: Long) {
    val w = c.watching
    ToolBack(w != null) { c.backToScan() }
    if (c.drones.isEmpty()) {
        Empty(
            if (c.running) S(R.string.no_drone_video_yet_the_sweep)
            else S(R.string.start_the_search_the_band_is),
        )
        return
    }
    c.drones.forEach { dr ->
        val ch = dr.channel
        val chName = if (ch.band == 'G') S(R.string.mhz_2_4_ghz, ch.mhz) else S(R.string.channel_mhz, ch.name, ch.mhz)
        val isWatched = c.running && w != null && kotlin.math.abs(w.mhz - ch.mhz) < 12
        DroneHeader(Sym.Videocam, S(R.string.analog_fpv_drone), chName, !dr.lost, S(R.string.video, dr.standard ?: "analogique"), true, false)
        if (isWatched) {
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val tick = c.frameTick // redraw with each new picture
                    val img = c.frame
                    Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(16.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                        if (img != null && tick >= 0) {
                            Canvas(Modifier.fillMaxWidth().aspectRatio(4f / 3f)) {
                                drawImage(img, IntOffset.Zero, IntSize(FpvController.PIC_W, FpvController.PIC_H), IntOffset.Zero, IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Medium)
                            }
                        } else Text(S(R.string.looking_for_the_picture), style = rf(13, 18), color = Color.White.copy(alpha = 0.7f))
                    }
                    ToolButtons(ToolButton(S(R.string.close_the_video), Sym.Close, BtnKind.OutlineOnSurface) { c.backToScan() })
                }
            }
        } else if (!dr.lost && d != null) {
            ToolButtons(ToolButton(S(R.string.watch_the_video), Sym.Videocam, BtnKind.OutlineOnSurface) { c.watch(d, ch) })
        }
        InfoList(S(R.string.radio_telemetry)) {
            InfoRow("Signal", "${fmt(dr.levelDb)} dBFS")
            InfoRow(S(R.string.trend), trendLabel(dr.levels))
            InfoRow(S(R.string.sync_quality), "${fmt(dr.syncQuality * 100)} %")
            InfoRow(S(R.string.last_seen), ago(dr.lastSeenMs, now))
            InfoRow(S(R.string.present_for), duration(dr.firstSeenMs, dr.lastSeenMs))
            LevelChart(dr.levels)
        }
    }
    if (!c.running) ToolButtons(ToolButton(S(R.string.clear), Sym.Delete, BtnKind.OutlineOnSurface) { c.clear() })
}

@Composable
private fun DjiPage(c: FpvController, now: Long) {
    val dj = c.dji
    if (dj?.confirmed != true) {
        Empty(
            when {
                !c.active -> S(R.string.start_the_search_the_hackrf_listens)
                dj != null -> S(R.string.a_droneid_burst_was_heard_three, dj.bursts)
                else -> S(R.string.no_dji_drone_yet_switch_the, c.digitalSweeps, P(R.plurals.p_sweeps, c.digitalSweeps))
            },
        )
        return
    }
    DroneHeader(
        Sym.Flight, S(R.string.dji_drone), S(R.string.recognised_by_its_droneid, dj.bursts, P(R.plurals.p_bursts, dj.bursts)),
        now - dj.lastSeenMs < 30_000, S(R.string.encrypted_digital_video), false, false,
    )
    InfoList(S(R.string.radio_telemetry)) {
        InfoRow(S(R.string.last_frequency), "${fmt(dj.lastCentreMhz, 1)} MHz")
        InfoRow(S(R.string.frequencies_used), dj.frequencies.joinToString(" · ") { fmt(it, 1) })
        InfoRow(S(R.string.level), S(R.string.db_above_the_noise, fmt(dj.lastLevelDb)))
        InfoRow(S(R.string.trend), trendLabel(dj.levels))
        InfoRow(S(R.string.last_seen), ago(dj.lastSeenMs, now))
        InfoRow(S(R.string.present_for), duration(dj.firstSeenMs, dj.lastSeenMs))
        LevelChart(dj.levels)
    }
    if (!c.active) ToolButtons(ToolButton(S(R.string.clear), Sym.Delete, BtnKind.OutlineOnSurface) { c.clear() })
}

@Composable
private fun RcPage(c: FpvController, now: Long) {
    if (c.rcLinks.isEmpty()) {
        Empty(
            if (c.running) S(R.string.no_rc_link_yet_switch_the, c.rcSweeps, P(R.plurals.p_sweeps, c.rcSweeps))
            else S(R.string.start_the_search_the_hackrf_listens_2),
        )
        return
    }
    c.rcLinks.forEach { l ->
        val onAir = now - l.lastSeenMs < 20_000
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LeadingIcon(Sym.Gamepad, if (onAir) AntTheme.accent.accent else AntTheme.accent.container, if (onAir) AntTheme.accent.onAccent else AntTheme.accent.onContainer)
                    Column(Modifier.weight(1f)) {
                        Text(l.protocol, style = rf(16, 22, 600), maxLines = 2)
                        Text("${l.variant} · ${RcBands.label(l.band)} · ${l.modulation.label}", style = rf(13, 18), color = cs.onSurfaceVariant, maxLines = 2)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val on = AntTheme.accent
                    val offBg = cs.surfaceContainerHighest
                    val offFg = cs.onSurfaceVariant
                    if (onAir) TechChip(S(R.string.active_rc_link), on.accent, on.onAccent) else TechChip(S(R.string.signal_lost), offBg, offFg)
                    TechChip(S(R.string.protocol_identified), on.container, on.onContainer)
                }
            }
        }
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(S(R.string.signature_verified), style = rf(14, 20, 600), color = AntTheme.accent.accent)
                l.checks.forEach { ch ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Symbol(Sym.Check, size = 18.dp, tint = AntTheme.accent.accent)
                        Text(ch, style = rf(13, 18))
                    }
                }
            }
        }
        InfoList(S(R.string.radio_measurements)) {
            InfoRow(S(R.string.rate), S(R.string.packets_s, fmt(l.rateHz)))
            InfoRow(S(R.string.width), "${fmt(l.bandwidthKHz)} kHz")
            InfoRow(S(R.string.packet_duration), if (l.packetUs < 1000) "${fmt(l.packetUs)} µs" else "${fmt(l.packetUs / 1000, 1)} ms")
            InfoRow(S(R.string.frequencies_used), S(R.string.frequency_hopping, l.channels))
            InfoRow(S(R.string.level), S(R.string.db_above_the_noise, fmt(l.levelDb)))
            InfoRow(S(R.string.trend), trendLabel(l.levels))
            InfoRow(S(R.string.packets_received), "${l.bursts}")
            InfoRow(S(R.string.last_seen), ago(l.lastSeenMs, now))
            InfoRow(S(R.string.present_for), duration(l.firstSeenMs, l.lastSeenMs))
            LevelChart(l.levels)
        }
    }
    if (!c.running) ToolButtons(ToolButton(S(R.string.clear), Sym.Delete, BtnKind.OutlineOnSurface) { c.clear() })
}
