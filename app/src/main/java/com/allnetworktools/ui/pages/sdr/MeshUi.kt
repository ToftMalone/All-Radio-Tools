package com.allnetworktools.ui.pages.sdr

import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.R
import com.allnetworktools.data.sdr.AdsbTracker
import com.allnetworktools.data.sdr.MeshBand
import com.allnetworktools.data.sdr.MeshMessage
import com.allnetworktools.data.sdr.MeshNode
import com.allnetworktools.data.sdr.MeshPacketRow
import com.allnetworktools.data.sdr.MeshRadio
import com.allnetworktools.data.sdr.MeshSnapshot
import com.allnetworktools.data.sdr.MeshTracker
import com.allnetworktools.data.sdr.SdrRepository
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.IconCircleButton
import com.allnetworktools.ui.components.LeadingIcon
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.SegmentedRow
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.pages.TopBarAction
import com.allnetworktools.ui.pages.gnss.TouchMapView
import com.allnetworktools.ui.pages.gnss.configureOsm
import com.allnetworktools.ui.pages.gnss.darkTilesFilter
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.StartButton
import com.allnetworktools.ui.tools.ToolButton
import com.allnetworktools.ui.tools.ToolButtons
import com.allnetworktools.ui.tools.BtnKind
import com.allnetworktools.ui.tools.ToolEmpty
import com.allnetworktools.util.Export
import com.allnetworktools.util.P
import com.allnetworktools.util.S
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

enum class MeshTab { Nodes, Map, Messages, Packets, Settings }

/**
 * What the Meshtastic and MeshCore tools share: one tuning, a [MeshRadio] on the band's channels and a tracker kept for
 * the whole session (stopping and starting again keeps what was heard; clearing starts over).
 */
abstract class MeshController(repo: SdrRepository, scope: CoroutineScope, onAcquire: () -> Unit, threadName: String) :
    SdrRxController(repo, scope, onAcquire, SdrGain(listOf(16, 24, 32, 40), 32, listOf(20, 30, 40), 30, amp = true), threadName) {
    var snapshot by mutableStateOf(MeshSnapshot.Empty)
        private set
    var tab by mutableStateOf(MeshTab.Nodes)
    var selected by mutableStateOf<String?>(null)

    abstract val band: MeshBand
    protected abstract val tracker: MeshTracker

    override val sampleRate = MeshRadio.SAMPLE_RATE
    override fun tuneHz() = band.loHz

    override fun newSink(): SdrSink {
        val t = tracker
        val radio = MeshRadio(band) { f -> synchronized(t) { t.update(f, System.currentTimeMillis()) } }
        return object : SdrSink {
            override fun feed(buf: ByteArray, len: Int) = radio.feed(buf, len)
            override fun close() = radio.close()
            override fun tick(nowMs: Long) {
                val s = synchronized(t) { t.snapshot(nowMs) }
                post { snapshot = s }
            }
        }
    }

    /** Publishes the tracker's state now (after a change of channels or a clear). */
    fun refresh() {
        val t = tracker
        snapshot = synchronized(t) { t.snapshot(System.currentTimeMillis()) }
    }

    fun clear() {
        val t = tracker
        synchronized(t) { t.clear() }
        selected = null
        refresh()
    }

    internal fun setForTest(s: MeshSnapshot) { snapshot = s; markRunningForTest() }

    fun csv(): String = buildString {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        fun q(s: String?) = s?.let { if (it.any { c -> c == ',' || c == '"' || c == '\n' }) "\"" + it.replace("\"", "\"\"") + "\"" else it } ?: ""
        append("heure_utc,de,vers,type,detail,chiffre,frequence_mhz,sf,bande_khz,snr_db,sauts,octets\r\n")
        snapshot.packets.asReversed().forEach { p ->
            append(
                listOf(
                    fmt.format(Date(p.timeMs)), q(p.from), q(p.to), p.kind, q(p.detail), if (p.encrypted) "1" else "0",
                    "%.4f".format(Locale.ROOT, p.hz / 1e6), p.sf.toString(), "%.1f".format(Locale.ROOT, p.bwHz / 1e3), "%.1f".format(Locale.ROOT, p.snrDb),
                    p.hops?.toString() ?: "", p.bytes.toString(),
                ).joinToString(","),
            )
            append("\r\n")
        }
    }
}

/** The screen both mesh tools use; [settings] adds the tool's own choices (band, channels) above the gains. */
@Composable
fun MeshScreen(vm: MainViewModel, c: MeshController, fileName: String, emptyIcon: String, emptyTitle: String, emptyBody: String, info: String, canStart: () -> Boolean = { true }, txPage: (@Composable () -> Unit)? = null, settings: @Composable () -> Unit) {
    val actions = LocalActions.current
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    val positions by vm.positions.collectAsStateWithLifecycle()
    val loc = positions.fused ?: positions.gnss ?: positions.network
    val me = loc?.let { it.latitude to it.longitude }
    val s = c.snapshot
    if (s.packets.isNotEmpty()) TopBarAction(Sym.Download) {
        val snapshot = c.csv()
        actions.saveFile("$fileName-${Export.stamp(System.currentTimeMillis())}.csv", "text/csv") { snapshot.toByteArray() }
    }
    PageColumn {
        // A tool that also transmits: reception and transmission are two pages.
        if (txPage != null && RxTxRow(fileName) == RxTx.Tx) { txPage(); return@PageColumn }
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${s.nodes.size}", style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(P(R.plurals.p_mesh_nodes, s.nodes.size), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text(
                P(R.plurals.p_mesh_messages, s.messages.size, s.messages.size) + " · " + S(R.string.mesh_packets_line, s.total, s.readable),
                style = rf(14, 20, tnum = true),
            )
            Row(Modifier.padding(top = 12.dp)) {
                when {
                    c.error != null -> HeroChip(c.error!!, AntTheme.net.poor)
                    c.starting -> HeroChip(S(R.string.starting_the_hackrf), AntTheme.net.fair, blink = true)
                    c.running -> HeroChip(S(R.string.mesh_listening, "%.3f".format(Locale.getDefault(), c.band.centreHz / 1e6)), AntTheme.net.good, blink = true)
                    device == null -> HeroChip(S(R.string.no_hackrf_plugged_in), AntTheme.net.poor)
                    else -> HeroChip(S(R.string.ready, device!!.name), AntTheme.net.good)
                }
            }
        }
        val d = device
        if (c.running || c.starting) StartButton(S(R.string.stop), Sym.Stop) { c.stop() }
        else StartButton(S(R.string.start_receiving), Sym.PlayArrow, enabled = d != null && canStart()) { d?.let(c::start) }
        SegmentedRow(
            listOf(MeshTab.Nodes to S(R.string.mesh_tab_nodes), MeshTab.Map to S(R.string.mesh_tab_map), MeshTab.Messages to S(R.string.mesh_tab_messages), MeshTab.Packets to S(R.string.mesh_tab_packets), MeshTab.Settings to "⚙️"),
            c.tab, { c.tab = it }, Modifier.fillMaxWidth(),
        )
        val now = System.currentTimeMillis()
        when (c.tab) {
            MeshTab.Nodes -> if (s.nodes.isEmpty()) {
                if (c.running) ToolEmpty(emptyIcon, emptyTitle, emptyBody, null)
            } else SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
                s.nodes.forEachIndexed { i, n ->
                    if (i > 0) Hairline()
                    NodeRow(n, me, now, n.key == c.selected) { c.selected = if (c.selected == n.key) null else n.key }
                }
            }
            MeshTab.Map -> {
                val located = s.nodes.filter { it.lat != null && it.lon != null }
                MeshMap(c, located, me, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)))
                if (located.isEmpty()) Text(S(R.string.mesh_no_positions), Modifier.padding(horizontal = 4.dp), style = rf(13, 18), color = cs.onSurfaceVariant)
                c.selected?.let { k -> s.nodes.firstOrNull { it.key == k } }?.let { n ->
                    SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) { NodeRow(n, me, now, true) { c.selected = null } }
                }
            }
            MeshTab.Messages -> if (s.messages.isEmpty()) {
                Text(S(R.string.mesh_no_messages), Modifier.padding(horizontal = 4.dp), style = rf(13, 18), color = cs.onSurfaceVariant)
            } else SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
                s.messages.forEachIndexed { i, m ->
                    if (i > 0) Hairline()
                    MessageRow(m, now)
                }
            }
            MeshTab.Settings -> Unit
            MeshTab.Packets -> if (s.packets.isEmpty()) {
                Text(S(R.string.mesh_no_packets), Modifier.padding(horizontal = 4.dp), style = rf(13, 18), color = cs.onSurfaceVariant)
            } else SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
                s.packets.take(100).forEachIndexed { i, p ->
                    if (i > 0) Hairline()
                    PacketRow(p, now)
                }
            }
        }
        if (s.total > 0 && !c.running && c.tab != MeshTab.Settings) ToolButtons(ToolButton(S(R.string.mesh_clear), Sym.Delete, BtnKind.OutlineOnSurface) { c.clear() })
        if (c.tab == MeshTab.Settings) {
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    settings()
                    GainSettings(c.gain) { c.applyGains() }
                }
            }
            SectionCard {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                    Text(info + (if (s.badCrc > 0) "\n\n" + S(R.string.mesh_bad_crc, s.badCrc) else ""), style = rf(13, 18), color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

private fun ago(now: Long, t: Long): String {
    val s = ((now - t) / 1000).coerceAtLeast(0)
    return when {
        s <= 2 -> S(R.string.just_now)
        s < 60 -> S(R.string.s_ago, s.toString())
        else -> S(R.string.min_ago, (s / 60).toString())
    }
}

private fun hopsText(h: Int?) = when (h) {
    null -> null
    0 -> S(R.string.mesh_direct)
    else -> P(R.plurals.p_mesh_hops, h, h)
}

@Composable
private fun NodeRow(n: MeshNode, me: Pair<Double, Double>?, now: Long, selected: Boolean, onClick: () -> Unit) {
    val acc = AntTheme.accent
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val icon = when (n.role) {
            "ROUTER", "ROUTER_LATE", "REPEATER", "ROUTER_CLIENT" -> Sym.Router
            "TRACKER", "TAK_TRACKER", "LOST_AND_FOUND" -> Sym.LocationOn
            "SENSOR" -> Sym.Sensors
            "ROOM" -> Sym.Forum
            else -> if (n.encryptedOnly) Sym.Lock else Sym.Smartphone
        }
        LeadingIcon(icon, if (selected) acc.accent else acc.container, if (selected) acc.onAccent else acc.onContainer, 40.dp, RoundedCornerShape(12.dp), 22.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(n.title, Modifier.weight(1f), style = rf(15, 20, 600), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(ago(now, n.lastMs), style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant)
            }
            Text(
                listOfNotNull(
                    n.shortName?.takeIf { n.longName != null }, n.id.takeIf { n.longName != null || n.shortName != null }, n.hardware, n.role,
                    if (n.verified == true) S(R.string.mesh_signed) else null,
                ).joinToString(" · ").ifEmpty { if (n.encryptedOnly) S(R.string.mesh_encrypted_only) else n.id },
                style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    n.snrDb?.let { "SNR " + snr(it) }, hopsText(n.hops), n.battery?.let { if (it > 100) S(R.string.mesh_powered) else "🔋 $it %" },
                    n.voltage?.takeIf { it > 0 }?.let { "%.2f V".format(Locale.getDefault(), it) },
                    if (me != null && n.lat != null && n.lon != null) "%.1f km".format(Locale.getDefault(), AdsbTracker.haversineKm(me.first, me.second, n.lat, n.lon)) else null,
                    "×${n.packets}",
                ).joinToString(" · "),
                style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant,
            )
            if (selected) {
                val more = listOfNotNull(
                    n.lat?.let { "%.5f, %.5f".format(Locale.ROOT, n.lat, n.lon) + (n.altitudeM?.let { a -> " · $a m" } ?: "") },
                    n.temperature?.let { "%.1f °C".format(Locale.getDefault(), it) + (n.humidity?.let { h -> " · %.0f %%".format(Locale.getDefault(), h) } ?: "") + (n.pressure?.let { p -> " · %.0f hPa".format(Locale.getDefault(), p) } ?: "") },
                    n.channelUtil?.let { S(R.string.mesh_channel_util, "%.1f".format(Locale.getDefault(), it), "%.1f".format(Locale.getDefault(), n.airUtilTx ?: 0f)) },
                    n.uptimeS?.let { S(R.string.mesh_uptime, it / 3600, (it / 60) % 60) },
                    if (n.viaMqtt) S(R.string.mesh_via_mqtt) else null,
                    n.key.takeIf { it.length > 16 }?.let { "🔑 $it" },
                )
                more.forEach { Text(it, Modifier.padding(top = 4.dp), style = rf(13, 18, tnum = true)) }
            }
        }
    }
}

@Composable
private fun MessageRow(m: MeshMessage, now: Long) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(m.fromName + (m.toName?.let { " → $it" } ?: ""), Modifier.weight(1f), style = rf(14, 20, 600), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(ago(now, m.timeMs), style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant)
        }
        Text(m.text, style = rf(15, 21))
        Text(
            listOfNotNull(m.channel, m.snrDb?.let { "SNR " + snr(it) }, hopsText(m.hops), if (m.copies > 1) S(R.string.mesh_copies, m.copies) else null).joinToString(" · "),
            style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun PacketRow(p: MeshPacketRow, now: Long) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Symbol(if (p.encrypted) Sym.Lock else Sym.LockOpen, size = 18.dp, tint = if (p.encrypted) cs.onSurfaceVariant else AntTheme.accent.accent)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${p.kind} · ${p.from}" + (p.to?.let { " → $it" } ?: ""), Modifier.weight(1f), style = rf(13, 18, 600), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(ago(now, p.timeMs), style = rf(11, 16, tnum = true), color = cs.onSurfaceVariant)
            }
            p.detail?.let { Text(it, style = rf(13, 18), maxLines = 3, overflow = TextOverflow.Ellipsis) }
            Text(
                listOfNotNull(
                    "%.3f MHz".format(Locale.getDefault(), p.hz / 1e6), "SF${p.sf}/${(p.bwHz / 1000).let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(Locale.getDefault(), it) }} kHz",
                    "SNR " + snr(p.snrDb), hopsText(p.hops), "${p.bytes} o",
                ).joinToString(" · "),
                style = rf(11, 16, tnum = true), color = cs.onSurfaceVariant,
            )
        }
    }
}

/** Nodes as labelled dots; the selected one in red. */
private class MeshOverlay(private val density: Float) : Overlay() {
    var nodes: List<MeshNode> = emptyList()
    var selected: String? = null
    var me: GeoPoint? = null
    var accent = 0
    var onTap: (String?) -> Unit = {}
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11 * density; isFakeBoldText = true }
    private val pt = android.graphics.Point()

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = map.projection
        me?.let {
            proj.toPixels(it, pt)
            paint.style = Paint.Style.FILL
            paint.color = 0xFFFFFFFF.toInt(); c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 8 * density, paint)
            paint.color = 0xFF1E88E5.toInt(); c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 5 * density, paint)
        }
        nodes.forEach { n ->
            proj.toPixels(GeoPoint(n.lat!!, n.lon!!), pt)
            val sel = n.key == selected
            val r = (if (sel) 8 else 6) * density
            paint.style = Paint.Style.FILL
            paint.color = 0xFFFFFFFF.toInt(); c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), r + 2 * density, paint)
            paint.color = if (sel) 0xFFE53935.toInt() else accent; c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), r, paint)
            val label = n.shortName ?: n.title
            text.color = 0xFFFFFFFF.toInt(); text.style = Paint.Style.STROKE; text.strokeWidth = 3 * density
            c.drawText(label, pt.x + r + 3 * density, pt.y - 2 * density, text)
            text.style = Paint.Style.FILL; text.color = 0xFF202124.toInt()
            c.drawText(label, pt.x + r + 3 * density, pt.y - 2 * density, text)
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, map: MapView): Boolean {
        val proj = map.projection
        fun dist(n: MeshNode): Float {
            proj.toPixels(GeoPoint(n.lat!!, n.lon!!), pt)
            return (pt.x - e.x) * (pt.x - e.x) + (pt.y - e.y) * (pt.y - e.y)
        }
        val hit = nodes.minByOrNull(::dist)?.takeIf { dist(it) < (28 * density) * (28 * density) }
        onTap(hit?.key)
        return hit != null
    }
}

@Composable
private fun MeshMap(c: MeshController, nodes: List<MeshNode>, me: Pair<Double, Double>?, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dark = com.allnetworktools.ui.theme.isDarkTheme(AntTheme.settings)
    val density = LocalDensity.current.density
    val overlay = remember { MeshOverlay(density) }
    val accent = AntTheme.accent.accent.toArgb()
    var centred by remember { mutableStateOf(false) }
    val map = remember {
        configureOsm(context)
        TouchMapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 3.0
            maxZoomLevel = 17.0
            controller.setZoom(9.0)
            controller.setCenter(GeoPoint(46.6, 2.4))
        }
    }
    LaunchedEffect(me, nodes.isNotEmpty()) {
        if (centred) return@LaunchedEffect
        val target = me ?: nodes.firstOrNull()?.let { it.lat!! to it.lon!! } ?: return@LaunchedEffect
        map.controller.setCenter(GeoPoint(target.first, target.second))
        centred = true
    }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        map.onResume()
        onDispose { lifecycle.removeObserver(obs); map.onPause(); map.onDetach() }
    }
    Box(modifier) {
        AndroidView(
            factory = { map },
            modifier = Modifier.fillMaxSize(),
            update = { m ->
                overlay.nodes = nodes
                overlay.selected = c.selected
                overlay.me = me?.let { GeoPoint(it.first, it.second) }
                overlay.accent = accent
                overlay.onTap = { c.selected = it }
                if (overlay !in m.overlays) m.overlays += overlay
                m.overlayManager.tilesOverlay.setColorFilter(if (dark) darkTilesFilter else null)
                m.invalidate()
            },
        )
        Column(Modifier.align(Alignment.TopEnd).padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val bg = cs.surface.copy(alpha = 0.92f)
            IconCircleButton(Sym.Add, { map.controller.zoomIn() }, size = 36.dp, bg = bg, tint = cs.onSurface)
            IconCircleButton(Sym.Remove, { map.controller.zoomOut() }, size = 36.dp, bg = bg, tint = cs.onSurface)
            if (me != null) IconCircleButton(Sym.MyLocation, { map.controller.animateTo(GeoPoint(me.first, me.second), 10.0, 600L) }, size = 36.dp, bg = bg, tint = AntTheme.accent.accent)
        }
        Text(
            S(R.string.openstreetmap_contributors),
            Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(cs.surface.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 2.dp),
            style = rf(10, 12), color = cs.onSurfaceVariant,
        )
    }
}

/** A row of choices that stops reception when the choice changes the tuning. */
@Composable
internal fun BandChips(options: List<Pair<String, String>>, selected: String, enabled: Boolean, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (k, label) -> com.allnetworktools.ui.components.AntFilterChip(label, selected == k, { if (enabled && selected != k) onPick(k) }) }
    }
}
