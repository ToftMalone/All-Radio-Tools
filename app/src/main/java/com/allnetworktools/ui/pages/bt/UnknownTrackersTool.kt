package com.allnetworktools.ui.pages.bt

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import com.allnetworktools.ui.components.AntFilterChip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.allnetworktools.data.BleDevice
import com.allnetworktools.data.FollowAssessment
import com.allnetworktools.data.FollowLevel
import com.allnetworktools.data.PositionSet
import com.allnetworktools.data.Sighting
import com.allnetworktools.data.TrackerDetect
import com.allnetworktools.data.TrackerKind
import com.allnetworktools.data.TrackerNet
import com.allnetworktools.data.probeFindMy
import com.allnetworktools.data.TrackerSignal
import com.allnetworktools.ui.components.PillButton
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.TechChip
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.pages.TopBarAction
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.ToolEmpty
import com.allnetworktools.util.fmt
import com.allnetworktools.util.plural
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.allnetworktools.R
import com.allnetworktools.util.S
import com.allnetworktools.util.P

/** A tag seen during the session, with where and when. */
class TrackerCandidate(val address: String, signal: TrackerSignal) {
    val sightings = mutableListOf<Sighting>()
    var signal by mutableStateOf(signal)
    var device by mutableStateOf<BleDevice?>(null)
    /** Every address this tag used during the session (SmartTags change it every ~7 min). */
    val addresses = mutableSetOf(address)
    var lastLinkKey: String? = signal.linkKey
    var lastHeardMs = 0L
    /** When the tag was first heard: the list keeps this order so rows never jump around. */
    var firstHeardMs = 0L
    var sound by mutableStateOf<SoundUi>(SoundUi.Idle)
    /** Name read over GATT from a Find My accessory, and the kind it reveals (an AirTag hides as an accessory). */
    var gattName by mutableStateOf<String?>(null)
    var gattKind by mutableStateOf<TrackerKind?>(null)
    /** Where the GATT identification stands, shown on the details page. */
    var probeState by mutableStateOf<String?>(null)
    var probeTries = 0
    var lastProbeMs = 0L
    val kind: TrackerKind get() = gattKind ?: signal.kind
    /** True once a connection read what the tag tells about itself. */
    var probed = false
    val title: String get() = device?.name ?: gattName ?: kind.label
    var assessment by mutableStateOf(FollowAssessment(FollowLevel.Nearby, 0, null, 0, 0))
}

/**
 * Records every tracker tag heard while the tool is open, with the phone's position, and flags the
 * ones that stay with you over time and distance.
 */
sealed interface SoundUi {
    data object Idle : SoundUi
    data object Connecting : SoundUi
    data class Playing(val via: String) : SoundUi
    data class Message(val text: String, val error: Boolean) : SoundUi
}

class UnknownTrackersController(private val context: android.content.Context?, private val scope: kotlinx.coroutines.CoroutineScope?) {
    private val sound by lazy { context?.let { com.allnetworktools.data.TagSound(it) } }

    /** Rings the tag through DULT / Find My, the public non-owner protocols. */
    fun ring(t: TrackerCandidate) {
        val s = sound ?: return
        val address = t.device?.address ?: return
        if (t.sound == SoundUi.Connecting) return
        // One connection at a time: the ring goes first.
        probeJob?.cancel()
        candidates.values.forEach { if (it !== t && it.sound is SoundUi.Playing) it.sound = SoundUi.Idle }
        t.sound = SoundUi.Connecting
        scope?.launch {
            t.sound = when (val r = s.play(address)) {
                is com.allnetworktools.data.SoundResult.Playing -> SoundUi.Playing(r.protocol.label)
                is com.allnetworktools.data.SoundResult.Refused -> SoundUi.Message(r.message, true)
                is com.allnetworktools.data.SoundResult.Failed -> SoundUi.Message(r.message, true)
            }
            if (t.sound is SoundUi.Playing) {
                // Tags stop by themselves after 5 to 30 s.
                kotlinx.coroutines.delay(30_000)
                if (t.sound is SoundUi.Playing) { s.stop(); t.sound = SoundUi.Idle }
            }
        }
    }

    fun stopSound(t: TrackerCandidate) {
        scope?.launch { sound?.stop(); t.sound = SoundUi.Idle }
    }

    val candidates = mutableStateMapOf<String, TrackerCandidate>()

    /** Kind of device the list is limited to, or null for all of them. */
    var kindFilter by mutableStateOf<TrackerKind?>(null)
    var sessionStart by mutableStateOf(System.currentTimeMillis())
        private set
    var travelledM by mutableStateOf(0.0)
        private set
    private var lastFix: Pair<Double, Double>? = null

    fun reset() {
        scope?.launch { sound?.stop() }
        candidates.clear()
        sessionStart = System.currentTimeMillis()
        travelledM = 0.0
        lastFix = null
    }

    fun feed(devices: List<BleDevice>, pos: PositionSet, now: Long = System.currentTimeMillis()) {
        val loc = pos.fused ?: pos.gnss ?: pos.network
        val lat = loc?.latitude
        val lon = loc?.longitude
        if (lat != null && lon != null) {
            val prev = lastFix
            if (prev == null) {
                lastFix = lat to lon
            } else {
                // Ignore the jitter of a still phone.
                val d = TrackerDetect.distanceM(prev.first, prev.second, lat, lon)
                if (d > 20) {
                    travelledM += d
                    lastFix = lat to lon
                }
            }
        }
        devices.forEach { d ->
            val sig = TrackerDetect.classify(d.ads) ?: return@forEach
            val c = candidates.values.firstOrNull { d.address in it.addresses }
                ?: sig.linkKey?.let { k -> candidates.values.firstOrNull { it.lastLinkKey == k && it.signal.net == sig.net && now - it.lastHeardMs < 20 * 60_000L } }
                    ?.also { it.addresses += d.address }
                ?: TrackerCandidate(d.address, sig).also { it.firstHeardMs = now; candidates[d.address] = it }
            c.device = d
            c.signal = sig
            sig.linkKey?.let { c.lastLinkKey = it }
            c.lastHeardMs = now
            val last = c.sightings.lastOrNull()
            // One sighting every 15 s is plenty to follow a walk and keeps memory bounded.
            if (last == null || now - last.timeMs >= 15_000L) {
                c.sightings += Sighting(now, lat, lon, d.rssi)
                if (c.sightings.size > 2000) c.sightings.removeAt(0)
            }
            c.assessment = TrackerDetect.assess(sig, c.sightings)
            // A rotating identifier changes with the epoch while the address stays: remember both.
            if (sig.linkKey != null) c.lastLinkKey = sig.linkKey
        }
        probeNext(now)
    }

    private var probeJob: kotlinx.coroutines.Job? = null

    /**
     * Connects to one Find My accessory at a time to read its name, as AirGuard does: the only way to know an
     * accessory is in fact an AirTag. Up to three tries a minute apart, never while a tag is ringing.
     */
    private fun probeNext(now: Long) {
        if (probeJob?.isActive == true || candidates.values.any { it.sound != SoundUi.Idle && it.sound !is SoundUi.Message }) return
        val t = candidates.values.filter {
            it.signal.net == TrackerNet.AppleFindMy && it.signal.kind != TrackerKind.AirTag && it.signal.kind != TrackerKind.AirPods &&
                !it.probed && it.probeTries < 3 && now - it.lastProbeMs > 60_000L &&
                it.device != null && now - it.lastHeardMs < 10_000L
        }.minByOrNull { it.firstHeardMs } ?: return
        probe(t, now)
    }

    /** Identifies one tag now, from the details page. */
    fun identify(t: TrackerCandidate) {
        if (t.sound == SoundUi.Connecting || t.sound is SoundUi.Playing) return
        probeJob?.cancel()
        t.probeTries = 0
        probe(t, System.currentTimeMillis())
    }

    private fun probe(t: TrackerCandidate, now: Long) {
        val ctx = context ?: return
        val address = t.device?.address ?: return
        t.probeTries++
        t.lastProbeMs = now
        t.probeState = S(R.string.connecting_to_the_tag)
        probeJob = scope?.launch {
            val r = probeFindMy(ctx, address)
            if (r.error != null) {
                t.probeState = S(R.string.failed_2, r.error) + if (t.probeTries < 3) S(R.string.retrying_in_1_min) else ""
                return@launch
            }
            t.probed = true
            // Prefer the model the accessory gives ("AirPods Pro"), then its name; "left"/"right" name one AirPod.
            t.gattName = r.findMyModel ?: r.model ?: r.name?.takeUnless { it == "left" || it == "right" }
            t.gattKind = TrackerDetect.kindFromProbe(r.name, r.airTagService, r.model, r.findMyModel)
            t.probeState = S(R.string.connected_name_model, r.name ?: "—", r.findMyModel ?: r.model ?: "—") +
                (r.findMyMaker?.let { S(R.string.manufacturer_3, it) } ?: "") + S(R.string.services, r.services) +
                if (r.airTagService) S(R.string.airtag_service_present) else ""
        }
    }

    internal fun setForTest(list: List<TrackerCandidate>, start: Long, travelled: Double) {
        candidates.clear()
        list.forEach { if (it.firstHeardMs == 0L) it.firstHeardMs = it.sightings.firstOrNull()?.timeMs ?: start; candidates[it.address] = it }
        sessionStart = start
        travelledM = travelled
    }
}

@Composable
private fun levelColor(l: FollowLevel): Color = when (l) {
    FollowLevel.Following -> cs.error
    FollowLevel.Watch -> AntTheme.net.fair
    FollowLevel.Nearby -> AntTheme.accent.accent
    FollowLevel.WithOwner -> AntTheme.net.good
}

private fun minutes(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60} h ${"%02d".format(m % 60)}" else if (m >= 1) "$m min" else "< 1 min"
}

private fun dist(m: Double) = if (m >= 1000) "${fmt(m / 1000, 1)} km" else "${fmt(m)} m"

@Composable
fun UnknownTrackersTool(
    c: UnknownTrackersController,
    devices: List<BleDevice>,
    positions: PositionSet,
    onOpen: (String) -> Unit,
) {
    LaunchedEffect(devices) { c.feed(devices, positions) }
    TopBarAction(Sym.RestartAlt) { c.reset() }
    // Detection only runs while the tool is open; the app keeps the screen on when "Écran toujours allumé" is set.
    val awake = AntTheme.settings.keepAwake
    var now by androidx.compose.runtime.remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    // In the order they were first heard: a new tag goes to the end and the others stay where they are.
    val list = c.candidates.values.sortedWith(compareBy<TrackerCandidate>({ it.firstHeardMs }, { it.address }))
    val following = list.count { it.assessment.level == FollowLevel.Following }
    val watch = list.count { it.assessment.level == FollowLevel.Watch }
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(following.toString(), style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(P(R.plurals.p_trackers_are_following_you, following), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text(
                S(R.string.to_watch_scanning_since_travelled, list.size, P(R.plurals.p_trackers_detected, list.size), watch, minutes(now - c.sessionStart), dist(c.travelledM)),
                style = rf(14, 20),
            )
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroChip(S(R.string.bluetooth_listening_in_progress), AntTheme.net.good, blink = true)
                if (awake) HeroChip(S(R.string.screen_kept_on), AntTheme.accent.accent)
            }
        }
        if (list.isEmpty()) {
            ToolEmpty(
                Sym.GppMaybe, S(R.string.no_tracker_nearby),
                S(R.string.the_tool_listens_for_airtags_smarttags) +
                    S(R.string.a_tracker_that_stays_with_you),
                null,
            )
        }
        // Filter by type of device: only the kinds heard so far, with how many of each.
        val counts = list.groupingBy { it.kind }.eachCount()
        val kinds = TrackerKind.entries.filter { it in counts }
        val filter = c.kindFilter
        if (list.isNotEmpty()) {
            val roles = AntTheme.accent
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AntFilterChip(S(R.string.all), filter == null, { c.kindFilter = null }, roles.container, roles.onContainer, trailing = list.size.toString())
                kinds.forEach { k ->
                    AntFilterChip(k.label, filter == k, { c.kindFilter = k }, roles.container, roles.onContainer, trailing = counts[k].toString())
                }
                if (filter != null && filter !in kinds) AntFilterChip(filter.label, true, { c.kindFilter = null }, roles.container, roles.onContainer, trailing = "0")
            }
        }
        val shown = if (filter == null) list else list.filter { it.kind == filter }
        // Just the tags: name and status. Everything else is one tap away.
        if (list.isNotEmpty() && shown.isEmpty()) {
            SectionCard { Text(S(R.string.no_device_of_this_kind_yet), style = rf(14, 20), color = cs.onSurfaceVariant) }
        }
        if (shown.isNotEmpty()) {
            SectionCard(padding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
                shown.forEachIndexed { i, t ->
                    if (i > 0) com.allnetworktools.ui.components.Hairline()
                    TrackerRow(t) { onOpen(t.address) }
                }
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(
                    S(R.string.an_airtag_a_google_tag_or) +
                        S(R.string.a_separated_tag_that_stays_with) +
                        S(R.string.make_it_ring_uses_the_public) +
                        S(R.string.android_also_offers_in_settings_safety),
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}


private val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())

@Composable
private fun TrackerRow(t: TrackerCandidate, onClick: () -> Unit) {
    val col = levelColor(t.assessment.level)
    val acc = AntTheme.accent
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TrackerBadge(t.kind, acc.container, 44.dp, RoundedCornerShape(14.dp), 36.dp)
        Column(Modifier.weight(1f)) {
            Text(t.title, style = rf(16, 22, 600), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(
                t.assessment.level.label + (if (t.firstHeardMs > 0) S(R.string.seen_at, clock.format(java.util.Date(t.firstHeardMs))) else ""),
                style = rf(13, 18, 500), color = col, maxLines = 1,
            )
        }
        SignalBars(t.device?.rssi)
        Symbol(Sym.ChevronRight, size = 22.dp, tint = cs.onSurfaceVariant)
    }
}

/** Four bars, like a phone's signal: −95 dBm and below none, −60 dBm and above all four. */
@Composable
private fun SignalBars(rssi: Int?) {
    val lit = when {
        rssi == null -> 0
        rssi >= -60 -> 4
        rssi >= -72 -> 3
        rssi >= -84 -> 2
        rssi >= -95 -> 1
        else -> 0
    }
    val on = AntTheme.accent.accent
    val off = cs.outlineVariant
    Row(Modifier.height(18.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 0 until 4) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(width = 4.dp, height = (6 + 4 * i).dp).clip(RoundedCornerShape(1.dp))
                    .background(if (i < lit) on else off),
            )
        }
    }
}

/** Everything about one tag on one page: status and actions up top, then signal, identity and advertisement. */
@Composable
fun TrackerDetailTool(
    c: UnknownTrackersController,
    key: String?,
    devices: List<BleDevice>,
    positions: PositionSet,
    onLocate: (BleDevice) -> Unit,
) {
    // Keep following the tags while the details are open.
    LaunchedEffect(devices) { c.feed(devices, positions) }
    var now by androidx.compose.runtime.remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val t = key?.let { k -> c.candidates[k] ?: c.candidates.values.firstOrNull { k in it.addresses } }
    PageColumn {
        if (t == null) {
            ToolEmpty(Sym.GppMaybe, S(R.string.tracker_not_found), S(R.string.it_has_not_been_heard_since), null)
            return@PageColumn
        }
        TrackerHero(t, now, onLocate, c::ring, c::stopSound)
        if (t.signal.net == TrackerNet.AppleFindMy) ProbeCard(t) { c.identify(t) }
        SignalCard(t)
        IdentityCard(t.device)
        AdvertCard(t.device)
        FramesCard(t.device)
    }
}

@Composable
private fun TrackerHero(t: TrackerCandidate, now: Long, onLocate: (BleDevice) -> Unit, onRing: (TrackerCandidate) -> Unit, onStopSound: (TrackerCandidate) -> Unit) {
    val a = t.assessment
    val col = levelColor(a.level)
    val acc = AntTheme.accent
    HeroCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TrackerBadge(t.kind, col, 56.dp, RoundedCornerShape(20.dp), 46.dp)
            Column(Modifier.weight(1f)) {
                Text(t.title, style = gs(22, 28, 500), maxLines = 2)
                Text(t.signal.net.label, style = rf(13, 18), maxLines = 1)
            }
            if (t.device != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(fmt(t.device!!.rssi), style = gs(22, 26, 500, tnum = true))
                    Text("dBm", style = rf(11, 14))
                }
            }
        }
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HeroChip(a.level.label, col, blink = a.level == FollowLevel.Following)
            HeroChip(
                t.signal.state ?: when (t.signal.separated) {
                    true -> S(R.string.separated_from_its_owner)
                    false -> S(R.string.owner_nearby)
                    null -> S(R.string.state_not_advertised)
                },
                cs.outline,
            )
        }
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            @Composable
            fun stat(v: String, k: String, m: Modifier) = Column(m.background(cs.surface.copy(alpha = 0.7f), RoundedCornerShape(14.dp)).padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(v, style = rf(15, 20, 600, tnum = true), maxLines = 1)
                Text(k, style = rf(11, 14), color = cs.onSurfaceVariant, maxLines = 1)
            }
            stat(minutes(a.durationMs), S(R.string.with_you), Modifier.weight(1f))
            stat(a.spreadM?.let { dist(it) } ?: "—", "distance", Modifier.weight(1f))
            stat("${a.places}", P(R.plurals.p_places, a.places), Modifier.weight(1f))
            stat("${a.sightings}", P(R.plurals.p_readings, a.sightings), Modifier.weight(1f))
        }
        val lastSeen = t.sightings.lastOrNull()?.timeMs
        Text(
            (t.device?.address ?: t.address) + (if (t.addresses.size > 1) S(R.string.successive_addresses, t.addresses.size) else "") +
                (lastSeen?.let { S(R.string.heard_s_ago, ((now - it) / 1000).coerceAtLeast(0)) } ?: ""),
            Modifier.padding(top = 10.dp), style = rf(12, 16, tnum = true),
        )
        val snd = t.sound
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(
                S(R.string.hot_cold), { t.device?.let(onLocate) }, Modifier.weight(1f), icon = Sym.MyLocation, height = 48.dp,
                bg = acc.accent, fg = acc.onAccent, enabled = t.device != null,
            )
            PillButton(
                when (snd) {
                    SoundUi.Connecting -> S(R.string.connecting)
                    is SoundUi.Playing -> S(R.string.stop)
                    else -> S(R.string.make_it_ring)
                },
                { if (snd is SoundUi.Playing) onStopSound(t) else onRing(t) },
                Modifier.weight(1f), icon = Sym.VolumeUp, height = 48.dp, bg = cs.surface, fg = acc.accent,
                enabled = t.device != null && snd != SoundUi.Connecting,
            )
        }
        when (snd) {
            is SoundUi.Playing -> Text(S(R.string.ringing_requested_through_the, snd.via), Modifier.padding(top = 8.dp), style = rf(12, 16, 600), color = AntTheme.net.good)
            is SoundUi.Message -> Text(snd.text, Modifier.padding(top = 8.dp), style = rf(12, 16), color = if (snd.error) cs.error else cs.onSurfaceVariant)
            else -> Unit
        }
    }
}

/** How the tag was identified: the advertisement's status byte, then what it said once connected. */
@Composable
private fun ProbeCard(t: TrackerCandidate, onIdentify: () -> Unit) {
    SectionCard {
        Text(S(R.string.identification), style = rf(14, 20, 600), color = AntTheme.accent.accent)
        Text(
            S(R.string.find_my_status_byte) + (t.signal.appleStatus?.let { "0x%02X".format(it) } ?: "—") + " → " + t.signal.kind.label,
            Modifier.padding(top = 8.dp), style = rf(13, 18, tnum = true),
        )
        Text(
            t.probeState ?: S(R.string.not_connected_to_the_tag_yet),
            Modifier.padding(top = 4.dp), style = rf(13, 18), color = cs.onSurfaceVariant,
        )
        PillButton(
            S(R.string.identify), onIdentify, Modifier.fillMaxWidth().padding(top = 12.dp), icon = Sym.Search, height = 44.dp,
            bg = cs.surface, fg = AntTheme.accent.accent, enabled = t.device != null && t.probeState != "Connexion au tag…",
        )
    }
}

/** RSSI over the session: a tag whose signal stays steady while you move is one you carry. */
@Composable
private fun SignalCard(t: TrackerCandidate) {
    val pts = t.sightings.takeLast(60).map { it.rssi.toFloat() }
    SectionCard {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(S(R.string.signal), Modifier.weight(1f), style = rf(14, 20, 600), color = AntTheme.accent.accent)
            if (pts.isNotEmpty()) Text("min ${fmt(pts.min().toInt())} · max ${fmt(pts.max().toInt())} dBm", style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant)
        }
        if (pts.size >= 2) {
            com.allnetworktools.ui.components.Sparkline(
                pts, -100f, -30f, levelColor(t.assessment.level),
                Modifier.fillMaxWidth().padding(top = 10.dp).height(72.dp),
            )
            Text(S(R.string.one_reading_every_15_s_while), Modifier.padding(top = 6.dp), style = rf(12, 16), color = cs.onSurfaceVariant)
        } else {
            Text(S(R.string.the_curve_appears_from_the_second), Modifier.padding(top = 8.dp), style = rf(13, 18), color = cs.onSurfaceVariant)
        }
    }
}
