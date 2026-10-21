package com.allnetworktools.ui.pages.bt

import android.content.Context
import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.allnetworktools.data.BleDevice
import com.allnetworktools.data.ringDevice
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.ChartBand
import com.allnetworktools.ui.components.LeadingIcon
import com.allnetworktools.ui.components.Legend
import com.allnetworktools.ui.components.LiveChart
import com.allnetworktools.ui.components.PillButton
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.ShapeBadge
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.cookieShape
import com.allnetworktools.ui.components.fadeUp
import com.allnetworktools.ui.components.groupShape
import com.allnetworktools.ui.components.rise
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.Phase
import com.allnetworktools.ui.tools.ToolEmpty
import com.allnetworktools.util.fmt
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.allnetworktools.R
import com.allnetworktools.util.S

/** Log-distance path loss with the usual BLE defaults (1 m reference −59 dBm, exponent 2.2). */
const val TxAt1m = -59f
const val PathLossN = 2.2f

fun rssiToMeters(rssi: Float): Float = 10f.pow((TxAt1m - rssi) / (10f * PathLossN))

/** 0 = cold (−85 dBm or less) → 1 = right next to it (−50 dBm). */
fun proximity(rssi: Float): Float = ((rssi + 85f) / 35f).coerceIn(0f, 1f)

enum class Heat(val word: String) { Cold(S(R.string.cold)), Warm(S(R.string.warm)), Hot(S(R.string.hot)), Found(S(R.string.found)) }

fun heatOf(rssi: Float) = when {
    rssi > -50 -> Heat.Found
    rssi > -55 -> Heat.Hot
    rssi > -70 -> Heat.Warm
    else -> Heat.Cold
}

class TrackerController(private val context: Context, private val scope: CoroutineScope) {
    var target by mutableStateOf<BleDevice?>(null)
    var phase by mutableStateOf(Phase.Idle)
    var rssi by mutableFloatStateOf(-100f)
    var trend by mutableIntStateOf(0)
    var ringing by mutableStateOf(false)
    val history = mutableStateListOf<Float>()
    private var lastPacket = 0L
    private var lastHeard = 0L

    /** When each device was first heard, so the list to pick from keeps the order of discovery and never jumps around. */
    private val discovered = HashMap<String, Long>()

    fun inDiscoveryOrder(list: List<BleDevice>, now: Long = System.currentTimeMillis()): List<BleDevice> {
        list.forEach { discovered.getOrPut(it.address) { now } }
        return list.sortedWith(compareBy<BleDevice>({ discovered[it.address] }, { it.address }))
    }
    private var p = 4f

    /** Compass heading of the phone (degrees), fed by the screen; null without a compass. */
    var heading by mutableStateOf<Float?>(null)
    var sweep by mutableStateOf<com.allnetworktools.data.DirectionSweep?>(null)
        private set
    /** Bumped on every sample so the sweep's progress redraws. */
    var sweepTick by mutableIntStateOf(0)
        private set
    var direction by mutableStateOf<com.allnetworktools.data.DirectionEstimate?>(null)
        private set

    fun startSweep() {
        sweep = com.allnetworktools.data.DirectionSweep()
        direction = null
        sweepTick = 0
    }

    fun cancelSweep() {
        sweep = null
    }

    fun follow(d: BleDevice) {
        target = d
        history.clear()
        sweep = null
        direction = null
        rssi = d.rssi.toFloat(); p = 4f; trend = 0
        lastPacket = d.lastSeen; lastHeard = SystemClock.elapsedRealtime()
        phase = Phase.Running
    }

    fun stop() {
        phase = Phase.Idle
        target = null
        sweep = null
        direction = null
    }

    /** Called with each ~1 s scan snapshot: scalar Kalman filter on RSSI, 60 s history, trend over 5 s. */
    fun feed(devices: List<BleDevice>) {
        val t = target ?: return
        if (phase != Phase.Running && phase != Phase.Empty) return
        val d = devices.firstOrNull { it.address == t.address }
        val now = SystemClock.elapsedRealtime()
        if (d != null && d.lastSeen != lastPacket) {
            lastPacket = d.lastSeen; lastHeard = now
            target = d
            val h = heading
            val sw = sweep
            if (h != null && sw != null) {
                // Raw readings: the filter would smear the peak over the turn.
                sw.add(h, d.rssi.toFloat())
                sweepTick++
                if (sw.complete) {
                    direction = sw.estimate()
                    sweep = null
                }
            }
            p += 0.8f
            val k = p / (p + 6f)
            rssi += k * (d.rssi - rssi)
            p *= 1 - k
            phase = Phase.Running
        } else if (now - lastHeard > 10_000) {
            phase = Phase.Empty
            return
        }
        history.add(rssi)
        while (history.size > 60) history.removeAt(0)
        if (history.size >= 10) {
            val recent = history.takeLast(5).average()
            val before = history.subList(history.size - 10, history.size - 5).average()
            trend = when {
                recent - before > 2 -> 1
                before - recent > 2 -> -1
                else -> 0
            }
        }
    }

    fun ring(onResult: (Boolean) -> Unit) {
        val t = target ?: return
        if (ringing) return
        ringing = true
        scope.launch {
            val ok = ringDevice(context, t.address)
            ringing = false
            onResult(ok)
        }
    }
}

@Composable
fun TrackerTool(c: TrackerController, heard: List<BleDevice>) {
    val nearby = androidx.compose.runtime.remember(heard) { c.inDiscoveryOrder(heard) }
    val haptics = AntTheme.haptics
    val actions = LocalActions.current
    LaunchedEffect(nearby) { c.feed(nearby) }
    val heat = heatOf(c.rssi)
    LaunchedEffect(heat, c.phase) {
        if (c.phase != Phase.Running) return@LaunchedEffect
        if (heat == Heat.Found) haptics.confirm() else haptics.tick()
    }
    PageColumn {
        when (c.phase) {
            Phase.Idle, Phase.Error, Phase.Results -> PickDevice(nearby) { haptics.confirm(); c.follow(it) }
            Phase.Running -> {
                HotColdCard(c)
                DirectionCard(c)
                HistoryCard(c)
            }
            Phase.Empty -> {
                ToolEmpty(
                    Sym.SearchOff, S(R.string.signal_lost),
                    S(R.string.has_not_transmitted_for_10_s, c.target?.displayName ?: S(R.string.the_device)),
                    S(R.string.choose_another_device),
                ) { c.stop() }
            }
        }
        if (c.phase == Phase.Running) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(
                    if (c.ringing) S(R.string.ringing) else S(R.string.make_it_ring),
                    {
                        haptics.confirm()
                        c.ring { ok -> actions.toast(if (ok) S(R.string.alert_sent_to, c.target?.displayName) else S(R.string.this_device_does_not_offer_immediate)) }
                    },
                    Modifier.weight(1f), icon = Sym.VolumeUp, height = 48.dp, enabled = !c.ringing && c.target?.connectable == true,
                )
                PillButton(S(R.string.stop), { haptics.segment(); c.stop() }, Modifier.weight(1f), icon = Sym.Stop, height = 48.dp, outlined = true, bg = AntTheme.accent.accent)
            }
        }
    }
}

@Composable
private fun PickDevice(nearby: List<BleDevice>, onFollow: (BleDevice) -> Unit) {
    val acc = AntTheme.accent
    SectionCard(Modifier.fadeUp(), color = acc.container, shape = RoundedCornerShape(32.dp), padding = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ShapeBadge(Sym.LocationSearching, cookieShape(), 64.dp, acc.accent, acc.onAccent, 30.dp, spinMs = 20_000)
            Column(Modifier.weight(1f)) {
                Text(S(R.string.choose_a_device_to_find), style = gs(20, 26, 500), color = acc.onContainer)
                Text(
                    S(R.string.move_around_the_screen_gets_hotter),
                    Modifier.padding(top = 4.dp), style = rf(13, 18), color = acc.onContainer,
                )
            }
        }
    }
    Text(S(R.string.nearby_3, nearby.size), Modifier.padding(start = 4.dp, top = 8.dp), style = rf(14, 20, 600), color = acc.accent)
    if (nearby.isEmpty()) {
        SectionCard {
            Text(S(R.string.searching_for_ble_devices_check_that), style = rf(14, 20), color = cs.onSurfaceVariant)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        nearby.take(40).forEachIndexed { i, d ->
            Row(
                Modifier.rise(i).fillMaxWidth().clip(groupShape(i, minOf(nearby.size, 40))).background(cs.surfaceContainerLow)
                    .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LeadingIcon(d.icon, acc.container, acc.onContainer, 40.dp, CircleShape, 22.dp)
                Column(Modifier.weight(1f)) {
                    Text(d.displayName, style = rf(15, 20, 600), maxLines = 1)
                    Text("${d.rssi} dBm · ≈ ${fmt(rssiToMeters(d.rssi.toFloat()), 1)} m", style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant)
                }
                PillButton(S(R.string.follow), { onFollow(d) }, height = 36.dp, bg = acc.accent, fg = acc.onAccent)
            }
        }
    }
}

@Composable
private fun HotColdCard(c: TrackerController) {
    val net = AntTheme.net
    val prox = proximity(c.rssi)
    val heat = heatOf(c.rssi)
    fun mix(cold: Color, warm: Color, hot: Color) = if (prox < 0.5f) lerp(cold, warm, prox * 2) else lerp(warm, hot, (prox - 0.5f) * 2)
    val bg by animateColorAsState(mix(net.wifi.container, net.gnss.container, cs.errorContainer), tween(600), label = "heatBg")
    val fg by animateColorAsState(mix(net.wifi.onContainer, net.gnss.onContainer, cs.onErrorContainer), tween(600), label = "heatFg")
    val core by animateColorAsState(mix(net.wifi.accent, net.gnss.accent, cs.error), tween(600), label = "heatCore")
    val onCore by animateColorAsState(mix(net.wifi.onAccent, net.gnss.onAccent, cs.onError), tween(600), label = "heatOnCore")
    val blob by animateDpAsState((120 + prox * 150).dp.coerceAtMost(260.dp), tween(800), label = "blob")
    val periodMs = ((2.4f - prox * 1.8f) * 5).toInt() * 200
    val pulse = rememberInfiniteTransition(label = "pulse")
    val wave by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(periodMs, easing = LinearEasing)), label = "wave")
    val d = c.target
    Column(
        Modifier.fadeUp().fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(bg).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Symbol(d?.icon ?: Sym.Bluetooth, size = 22.dp, filled = true, tint = fg)
            Text(d?.displayName ?: "", Modifier.weight(1f), style = rf(16, 22, 600), color = fg, maxLines = 1)
            val (icon, text) = when (c.trend) {
                1 -> Sym.TrendingUp to S(R.string.getting_closer)
                -1 -> Sym.TrendingDown to S(R.string.moving_away)
                else -> Sym.TrendingFlat to S(R.string.steady_distance)
            }
            Row(
                Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).background(cs.surface).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Symbol(icon, size = 16.dp, tint = core)
                Text(text, style = rf(12, 16, 600), color = cs.onSurface, maxLines = 1)
            }
        }
        Box(Modifier.padding(top = 12.dp).size(292.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(292.dp)) {
                val r = size.minDimension / 2
                listOf(0.35f, 0.55f, 0.75f, 0.97f).forEach { f -> drawCircle(fg.copy(alpha = 0.18f), r * f, style = Stroke(1.5f.dp.toPx())) }
                val inner = blob.toPx() / 2
                val pr = inner + (r - inner) * wave
                drawCircle(core.copy(alpha = 0.35f * (1 - wave)), pr, style = Stroke(6.dp.toPx() * (1 - wave) + 1f))
            }
            Box(Modifier.size(blob).clip(CircleShape).background(core), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (heat == Heat.Found) Symbol(Sym.Celebration, size = 32.dp, filled = true, tint = onCore)
                    Text(heat.word, style = gs(if (heat == Heat.Found) 30 else 36, 40, 600), color = onCore, textAlign = TextAlign.Center)
                }
            }
        }
        Text(
            "≈ ${fmt(rssiToMeters(c.rssi), 1)} m · ${fmt(c.rssi)} dBm",
            Modifier.padding(top = 12.dp), style = gs(22, 28, 500, tnum = true), color = fg,
        )
        Row(Modifier.padding(top = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("${fmt(c.rssi)} dBm" to S(R.string.filtered_rssi), "${fmt(TxAt1m)} dBm" to "Tx @ 1 m", "n = 2,2" to S(R.string.model)).forEach { (v, k) ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(cs.surface).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(v, style = gs(16, 22, 600, tnum = true), color = cs.onSurface, maxLines = 1)
                    Text(k, style = rf(11, 14), color = cs.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(c: TrackerController) {
    val net = AntTheme.net
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(S(R.string.rssi_history_60_s), Modifier.weight(1f), style = rf(16, 22, 600))
            Legend(listOf("chaud" to cs.error, S(R.string.warm_2) to net.gnss.accent, "froid" to net.wifi.accent))
        }
        LiveChart(
            c.history.toList(), -100f, -30f, 20f, AntTheme.accent.accent, Modifier.padding(top = 12.dp), capacity = 60,
            bands = listOf(ChartBand(-55f, -30f, cs.error), ChartBand(-70f, -55f, net.gnss.accent), ChartBand(-100f, -70f, net.wifi.accent)),
        )
        Text(
            S(R.string.distance_estimated_with_the_log_distance),
            Modifier.padding(top = 4.dp), style = rf(12, 16), color = cs.onSurfaceVariant,
        )
    }
}

/** Compass heading from the rotation vector, for a phone held flat; null when the sensor is missing. */
@Composable
private fun rememberHeading(): Pair<Boolean, Float?> {
    val context = androidx.compose.ui.platform.LocalContext.current
    var heading by androidx.compose.runtime.remember { mutableStateOf<Float?>(null) }
    var available by androidx.compose.runtime.remember { mutableStateOf(true) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val sm = context.getSystemService(android.hardware.SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)
            ?: sm?.getDefaultSensor(android.hardware.Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        val rot = FloatArray(9)
        val ori = FloatArray(3)
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                android.hardware.SensorManager.getRotationMatrixFromVector(rot, e.values)
                android.hardware.SensorManager.getOrientation(rot, ori)
                val deg = ((Math.toDegrees(ori[0].toDouble()).toFloat() % 360f) + 360f) % 360f
                val prev = heading
                // Light smoothing across the 359° → 0° wrap.
                heading = if (prev == null) deg else {
                    val d = ((deg - prev + 540f) % 360f) - 180f
                    ((prev + 0.3f * d) % 360f + 360f) % 360f
                }
            }

            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) = Unit
        }
        available = sensor != null
        if (sensor != null) sm.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_UI)
        onDispose { sm?.unregisterListener(listener) }
    }
    return available to heading
}

private fun relativeWords(rel: Float): String {
    val r = ((rel % 360f) + 360f) % 360f
    return when {
        r < 22.5f || r >= 337.5f -> S(R.string.straight_ahead)
        r < 67.5f -> S(R.string.ahead_to_the_right)
        r < 112.5f -> S(R.string.to_your_right)
        r < 157.5f -> S(R.string.behind_to_the_right)
        r < 202.5f -> S(R.string.behind_you)
        r < 247.5f -> S(R.string.behind_to_the_left)
        r < 292.5f -> S(R.string.to_your_left)
        else -> S(R.string.ahead_to_the_left)
    }
}

@Composable
private fun DirectionCard(c: TrackerController) {
    val (hasCompass, heading) = rememberHeading()
    LaunchedEffect(heading) { c.heading = heading }
    val acc = AntTheme.accent
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Symbol(Sym.Navigation, size = 22.dp, filled = true, tint = acc.accent)
            Text(S(R.string.direction_of_the_device), Modifier.weight(1f), style = rf(16, 22, 600))
        }
        val sweep = c.sweep
        val dir = c.direction
        when {
            !hasCompass -> Text(
                S(R.string.this_phone_has_no_compass_the),
                Modifier.padding(top = 8.dp), style = rf(13, 18), color = cs.onSurfaceVariant,
            )
            heading == null -> Text(
                S(R.string.reading_the_compass),
                Modifier.padding(top = 8.dp), style = rf(13, 18), color = cs.onSurfaceVariant,
            )
            sweep != null -> {
                @Suppress("UNUSED_VARIABLE") val tick = c.sweepTick
                Box(Modifier.padding(top = 12.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val done = acc.accent
                    val todo = cs.surfaceContainerHighest
                    val needle = cs.onSurface
                    Canvas(Modifier.size(200.dp)) {
                        val sw = 18.dp.toPx()
                        val tl = androidx.compose.ui.geometry.Offset(sw / 2, sw / 2)
                        val sz = androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw)
                        for (i in 0 until sweep.sectors) {
                            // Sector 0 starts at north; canvas angles start at 3 o'clock.
                            drawArc(if (sweep.covered(i)) done else todo, i * 30f - 90f + 1.5f, 27f, false, tl, sz, style = Stroke(sw))
                        }
                        val a = Math.toRadians((heading - 90f).toDouble())
                        val r = size.minDimension / 2 - sw * 1.6f
                        drawLine(needle, center, center + androidx.compose.ui.geometry.Offset((r * kotlin.math.cos(a)).toFloat(), (r * kotlin.math.sin(a)).toFloat()), 4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    }
                    Text("${sweep.coveredCount} / ${sweep.sectors}", style = gs(22, 28, 600, tnum = true))
                }
                Text(
                    S(R.string.hold_the_phone_flat_in_front) +
                        S(R.string.your_body_blocks_the_signal_from),
                    Modifier.padding(top = 10.dp), style = rf(13, 18), color = cs.onSurfaceVariant,
                )
                PillButton(S(R.string.cancel), { c.cancelSweep() }, Modifier.fillMaxWidth().padding(top = 10.dp), height = 44.dp, outlined = true, bg = acc.accent)
            }
            dir != null -> {
                val rel = dir.bearing - heading
                val angle by androidx.compose.animation.core.animateFloatAsState(
                    ((rel % 360f) + 360f) % 360f, tween(300), label = "arrow",
                )
                Box(Modifier.padding(top = 12.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(180.dp).clip(CircleShape).background(acc.container), contentAlignment = Alignment.Center) {
                        Symbol(Sym.Navigation, Modifier.graphicsRotation(angle), size = 110.dp, filled = true, tint = acc.accent)
                    }
                }
                Text(relativeWords(rel), Modifier.fillMaxWidth().padding(top = 12.dp), style = gs(24, 30, 500), textAlign = TextAlign.Center)
                Text(
                    S(R.string.heading_confidence_db_more_than_behind, fmt(dir.bearing), dir.confidence, fmt(dir.marginDb, 1)),
                    Modifier.fillMaxWidth().padding(top = 2.dp), style = rf(13, 18, tnum = true), color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
                )
                if (dir.confidence == "faible") {
                    Text(
                        S(R.string.signal_almost_the_same_in_every),
                        Modifier.padding(top = 8.dp), style = rf(12, 16), color = cs.error,
                    )
                }
                PillButton(S(R.string.measure_again), { c.startSweep() }, Modifier.fillMaxWidth().padding(top = 12.dp), icon = Sym.ThreeSixty, height = 44.dp, outlined = true, bg = acc.accent)
            }
            else -> {
                Text(
                    S(R.string.bluetooth_does_not_give_a_direction),
                    Modifier.padding(top = 8.dp), style = rf(13, 18), color = cs.onSurfaceVariant,
                )
                PillButton(S(R.string.find_the_direction), { c.startSweep() }, Modifier.fillMaxWidth().padding(top = 12.dp), icon = Sym.ThreeSixty, height = 48.dp)
            }
        }
    }
}

private fun Modifier.graphicsRotation(deg: Float) = this.then(Modifier.rotate(deg))
