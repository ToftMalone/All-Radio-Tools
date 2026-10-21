package com.allnetworktools.ui.pages.gnss

import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.data.CompassCheck
import com.allnetworktools.data.GnssIntegrity
import com.allnetworktools.data.GnssRaw
import com.allnetworktools.data.IntegrityReport
import com.allnetworktools.data.IntegritySample
import com.allnetworktools.data.RawEpoch
import com.allnetworktools.data.Rinex
import com.allnetworktools.data.Satellite
import com.allnetworktools.data.SkyMask
import com.allnetworktools.data.Verdict
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.InfoList
import com.allnetworktools.ui.components.InfoRow
import com.allnetworktools.ui.components.LiveChart
import com.allnetworktools.ui.components.MetricTile
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.TileGrid
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
import com.allnetworktools.ui.tools.ToolButton
import com.allnetworktools.ui.tools.ToolButtons
import com.allnetworktools.util.fmt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import com.allnetworktools.R
import com.allnetworktools.util.S

@Composable
private fun verdictColor(v: Verdict) = when (v) {
    Verdict.Ok -> AntTheme.net.good
    Verdict.Warn -> AntTheme.net.fair
    Verdict.Alert -> cs.error
    Verdict.Unknown -> cs.outline
}

/** Phone clock minus GNSS UTC is the negative of this: GNSS time of the fix minus the phone's time at that instant. */
private fun gnssMinusPhoneMs(l: Location): Double {
    val ageMs = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1e6
    return l.time - (System.currentTimeMillis() - ageMs)
}

// ---- 1. Jamming and spoofing ---------------------------------------------------------------------

class IntegrityController {
    val engine = GnssIntegrity()
    var report by mutableStateOf<IntegrityReport?>(null)
    val cn0 = mutableStateListOf<Float>()
    var agc = emptyList<com.allnetworktools.data.RawAgc>()

    fun reset() { engine.reset(); report = null; cn0.clear() }

    fun feed(s: IntegritySample) {
        val r = engine.feed(s)
        report = r
        cn0 += (r.cn0 ?: 0.0).toFloat()
        while (cn0.size > 120) cn0.removeAt(0)
    }
}

@Composable
fun IntegrityTool(vm: MainViewModel) {
    val c = vm.tools.integrity
    val gnss by vm.gnss.collectAsStateWithLifecycle()
    val positions by vm.positions.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.gnssRaw.collect { c.agc = it.agc } }
    LaunchedEffect(gnss) {
        val l = gnss.location
        c.feed(
            IntegritySample(
                System.currentTimeMillis(), gnss.satellites.filter { it.elevation > 0f }, c.agc,
                l?.latitude, l?.longitude, l?.altitude, l?.let { it.elapsedRealtimeNanos / 1_000_000 }, l?.isMock == true,
                l?.let(::gnssMinusPhoneMs), positions.network?.latitude, positions.network?.longitude, positions.network?.accuracy,
            ),
        )
    }
    TopBarAction(Sym.RestartAlt) { c.reset() }
    val r = c.report
    PageColumn {
        HeroCard {
            val jam = r?.jamming ?: Verdict.Unknown
            val spoof = r?.spoofing ?: Verdict.Unknown
            val worst = maxOf(jam, spoof)
            Text(
                when {
                    r == null -> S(R.string.analysing)
                    worst == Verdict.Alert && jam == Verdict.Alert -> S(R.string.jamming_likely)
                    worst == Verdict.Alert -> S(R.string.spoofing_possible)
                    worst == Verdict.Warn -> S(R.string.anomaly_to_watch)
                    worst == Verdict.Ok -> S(R.string.no_anomaly)
                    else -> S(R.string.waiting_for_satellites)
                },
                style = gs(30, 36, 500),
            )
            Text(S(R.string.the_gnss_receiver_is_watched_continuously), Modifier.padding(top = 4.dp), style = rf(14, 20))
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroChip(S(R.string.jamming) + label(jam), verdictColor(jam))
                HeroChip(S(R.string.spoofing) + label(spoof), verdictColor(spoof))
            }
        }
        if (c.cn0.size > 1) {
            SectionCard {
                Text(S(R.string.strength_of_the_4_best_satellites), style = rf(14, 20, 600), color = AntTheme.accent.accent)
                LiveChart(c.cn0.toList(), 0f, 55f, 10f, AntTheme.accent.accent, Modifier.padding(top = 8.dp), capacity = 120, height = 120.dp)
                Text(S(R.string.db_hz_over_the_last_2), style = rf(12, 16), color = cs.onSurfaceVariant)
            }
        }
        InfoList(S(R.string.clues)) {
            (r?.checks ?: emptyList()).forEach { k ->
                HorizontalDivider(color = cs.outlineVariant, thickness = 1.dp)
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(verdictColor(k.verdict)))
                    Column(Modifier.weight(1f)) {
                        Text(k.title, style = rf(14, 20, 600))
                        Text(k.detail, style = rf(12, 16), color = cs.onSurfaceVariant)
                    }
                }
            }
        }
        Text(
            S(R.string.jamming_a_transmitter_drowns_the_satellites) +
                S(R.string.spoofing_fake_signals_fake_another_position) +
                S(R.string.an_impossible_jump_or_a_disagreement),
            Modifier.padding(horizontal = 4.dp), style = rf(12, 16), color = cs.onSurfaceVariant,
        )
    }
}

private fun label(v: Verdict) = when (v) { Verdict.Ok -> "non"; Verdict.Warn -> "suspect"; Verdict.Alert -> "probable"; Verdict.Unknown -> "—" }

// ---- 3. Raw measurements and dual frequency --------------------------------------------------------

class RawController {
    var last by mutableStateOf<RawEpoch?>(null)
    var epochs by mutableIntStateOf(0)
    var recording by mutableStateOf(false)
    val recorded = ArrayList<RawEpoch>()
    var recordedCount by mutableIntStateOf(0)

    fun feed(e: RawEpoch) {
        last = e
        epochs++
        if (recording && recorded.size < 7200) { recorded += e; recordedCount = recorded.size }
    }

    fun start() { recorded.clear(); recordedCount = 0; recording = true }
    fun stop() { recording = false }
}

@Composable
fun RawTool(vm: MainViewModel) {
    val c = vm.tools.raw
    val actions = LocalActions.current
    val caps = remember { vm.gnssCapabilities() }
    LaunchedEffect(Unit) { vm.gnssRaw.collect { c.feed(it) } }
    var waited by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (waited < 10) { kotlinx.coroutines.delay(1000); waited++ } }
    val e = c.last
    val bands = e?.measurements?.groupBy { GnssRaw.band(it.constellation, it.carrierHz) }.orEmpty()
    val dual = bands.keys.any { it.isLower }
    PageColumn {
        HeroCard {
            Text(
                when {
                    e == null && waited >= 10 -> S(R.string.no_raw_measurements)
                    e == null -> S(R.string.waiting_for_measurements)
                    dual -> S(R.string.dual_frequency)
                    else -> S(R.string.single_frequency)
                },
                style = gs(30, 36, 500),
            )
            Text(
                when {
                    e == null && waited >= 10 -> S(R.string.this_phone_does_not_give_raw)
                    e == null -> S(R.string.go_outside_the_chip_publishes_its)
                    else -> bands.keys.sortedBy { it.ordinal }.joinToString(" + ") { it.label } + " · ${e.measurements.distinctBy { it.constellation to it.svid }.size} satellites"
                },
                Modifier.padding(top = 4.dp), style = rf(14, 20),
            )
            if (e != null) {
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeroChip(if (dual) "L1 + L5 captés" else "L1 seulement", if (dual) AntTheme.net.good else AntTheme.net.fair)
                    HeroChip("${c.epochs} ${com.allnetworktools.util.plural(c.epochs, "époque", "époques")}", AntTheme.accent.accent)
                }
            }
        }
        if (bands.isNotEmpty()) {
            TileGrid(bands.entries.sortedBy { it.key.ordinal }.map { it }) { (b, ms), mod ->
                MetricTile(b.label, ms.size.toString(), "sat.", if (b.isLower) Sym.Bolt else Sym.SatelliteAlt, mod)
            }
        }
        if (e != null) SignalTable(e)
        InfoList(S(R.string.chip_capabilities)) {
            fun yes(v: Boolean?) = when (v) { true -> "Oui"; false -> "Non"; null -> S(R.string.unknown) }
            InfoRow(S(R.string.raw_measurements), yes(caps?.measurements ?: (e != null)))
            InfoRow(S(R.string.navigation_messages), yes(caps?.navigationMessages))
            InfoRow(S(R.string.multi_band_tracking), yes(caps?.multibandTracking))
            InfoRow(S(R.string.measurement_corrections), yes(caps?.measurementCorrections))
            InfoRow(S(R.string.antenna_info_phase_centre), yes(caps?.antennaInfo))
            InfoRow(S(R.string.satellite_position_pvt), yes(caps?.satellitePvt))
            InfoRow(S(R.string.carrier_phase_adr), if (e == null) "—" else yes(e.measurements.any { it.adrState and GnssRaw.ADR_STATE_VALID != 0 }))
        }
        SectionCard {
            Text(S(R.string.rinex_recording), style = rf(14, 20, 600), color = AntTheme.accent.accent)
            Text(
                if (c.recording) S(R.string.epochs_in_memory_min_s, c.recordedCount, c.recordedCount / 60, c.recordedCount % 60)
                else S(R.string.pseudoranges_phase_doppler_and_c_n0),
                Modifier.padding(top = 4.dp, bottom = 10.dp), style = rf(13, 18), color = cs.onSurfaceVariant,
            )
            if (c.recording) {
                ToolButtons(ToolButton(S(R.string.stop), Sym.Stop, BtnKind.Fill) { c.stop() })
            } else {
                ToolButtons(ToolButton(S(R.string.record), Sym.PlayArrow, BtnKind.Outline) { c.start() })
            }
            if (!c.recording && c.recordedCount > 0) {
                ToolButtons(
                    ToolButton(S(R.string.export_epochs, c.recordedCount), Sym.Download, BtnKind.Fill) {
                        val epochs = c.recorded.toList()
                        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(Date())
                        actions.saveFile("android_$stamp.obs", "text/plain") { Rinex.format(epochs).toByteArray() }
                    },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SignalTable(e: RawEpoch) {
    val bySat = e.measurements.groupBy { it.constellation to it.svid }.entries.sortedWith(compareBy({ it.key.first.ordinal }, { it.key.second }))
    InfoList(S(R.string.signals_per_satellite)) {
        bySat.forEach { (key, ms) ->
            HorizontalDivider(color = cs.outlineVariant, thickness = 1.dp)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(key.first.prefix + key.second.toString().padStart(2, '0'), Modifier.size(width = 44.dp, height = 20.dp), style = rf(13, 18, 700, tnum = true))
                Column(Modifier.weight(1f)) {
                    ms.sortedBy { GnssRaw.band(it.constellation, it.carrierHz).ordinal }.forEach { m ->
                        val b = GnssRaw.band(m.constellation, m.carrierHz)
                        val flags = listOfNotNull(
                            if (GnssRaw.timeKnown(m)) null else S(R.string.time_not_decoded),
                            if (m.multipath == 1) "multitrajet" else null,
                            if (m.adrState and GnssRaw.ADR_STATE_VALID != 0) "phase" else null,
                        )
                        Text(
                            "${b.label} · ${fmt(m.cn0.toFloat(), 0)} dB-Hz" + (GnssRaw.pseudorangeM(e.clock, m)?.let { " · ${fmt((it / 1000).toFloat(), 0)} km" } ?: "") +
                                if (flags.isEmpty()) "" else " · " + flags.joinToString(", "),
                            style = rf(12, 16, tnum = true), color = if (b.isLower) AntTheme.accent.accent else cs.onSurface,
                        )
                    }
                }
            }
        }
    }
}

// ---- 4. Compass against GNSS course ----------------------------------------------------------------

class CompassController {
    val check = CompassCheck()
    var heading by mutableFloatStateOf(Float.NaN)
    var count by mutableIntStateOf(0)
}

@Composable
fun CompassCheckTool(vm: MainViewModel) {
    val c = vm.tools.compassCheck
    val context = LocalContext.current
    val gnss by vm.gnss.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        val sm = context.getSystemService(SensorManager::class.java)
        val rot = FloatArray(9)
        val o = FloatArray(3)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                SensorManager.getOrientation(rot, o)
                c.heading = ((Math.toDegrees(o[0].toDouble()) + 360) % 360).toFloat()
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
        }
        sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_UI) }
        onDispose { sm?.unregisterListener(l) }
    }
    val loc = gnss.location
    val declination = loc?.let { GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), it.altitude.toFloat(), System.currentTimeMillis()).declination }
    val compassTrue = if (c.heading.isNaN()) null else (c.heading + (declination ?: 0f) + 360) % 360
    val moving = loc != null && loc.hasBearing() && loc.hasSpeed() && loc.speed >= 1.2f
    LaunchedEffect(loc) {
        if (loc != null && moving && compassTrue != null) {
            if (c.check.feed(loc.bearing, loc.speed, if (loc.hasBearingAccuracy()) loc.bearingAccuracyDegrees else null, compassTrue) != null) c.count++
        }
    }
    TopBarAction(Sym.RestartAlt) { c.check.reset(); c.count = 0 }
    val off = remember(c.count) { c.check.offset() }
    PageColumn {
        HeroCard {
            Text(
                when {
                    off == null -> S(R.string.walk_straight_ahead)
                    abs(off.first) <= 5 -> S(R.string.compass_reliable)
                    abs(off.first) <= 15 -> S(R.string.compass_fair)
                    else -> S(R.string.compass_needs_recalibrating)
                },
                style = gs(30, 36, 500),
            )
            Text(
                if (off == null) S(R.string.phone_pointing_in_the_walking_direction)
                else S(R.string.mean_offset_spread_over_measurements, fmt(off.first, 1), fmt(off.second, 0), c.count) +
                    if (abs(off.first) > 15) S(R.string.draw_figure_eights_in_the_air) else "",
                Modifier.padding(top = 4.dp), style = rf(14, 20),
            )
        }
        SectionCard {
            CompassDial(compassTrue, if (moving) loc.bearing else null, Modifier.fillMaxWidth().aspectRatio(1f).padding(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Legend(AntTheme.net.wifi.accent, S(R.string.compass))
                Legend(AntTheme.net.gnss.accent, S(R.string.gnss_course))
            }
        }
        TileGrid(
            listOf(
                Triple(Sym.Explore, S(R.string.compass_true), compassTrue?.let { fmt(it, 0) } ?: "—") to "°",
                Triple(Sym.Navigation, S(R.string.gnss_course), if (moving) fmt(loc.bearing, 0) else "—") to "°",
                Triple(Sym.Speed, S(R.string.speed), loc?.takeIf { it.hasSpeed() }?.let { fmt(it.speed * 3.6f, 1) } ?: "—") to "km/h",
                Triple(Sym.SwapHoriz, S(R.string.declination), declination?.let { fmt(it, 1) } ?: "—") to "°",
            ),
        ) { (t, u), mod -> MetricTile(t.second, t.third, u, t.first, mod) }
        Text(
            S(R.string.the_compass_shows_magnetic_north_the) +
                S(R.string.which_is_true_and_depends_on),
            Modifier.padding(horizontal = 4.dp), style = rf(12, 16), color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(text, style = rf(12, 16, 600), color = cs.onSurfaceVariant)
    }
}

@Composable
private fun CompassDial(compass: Float?, gnss: Float?, modifier: Modifier) {
    val ring = cs.outlineVariant
    val ink = cs.onSurfaceVariant
    val a = AntTheme.net.wifi.accent
    val g = AntTheme.net.gnss.accent
    val hub = cs.surface
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            val ctr = Offset(size.width / 2, size.height / 2)
            drawCircle(ring, r, ctr, style = Stroke(2.dp.toPx()))
            for (d in 0 until 360 step 10) {
                val rad = Math.toRadians(d - 90.0)
                val len = if (d % 90 == 0) 16.dp.toPx() else if (d % 30 == 0) 10.dp.toPx() else 5.dp.toPx()
                drawLine(ink, Offset(ctr.x + (r - len) * cos(rad).toFloat(), ctr.y + (r - len) * sin(rad).toFloat()), Offset(ctr.x + r * cos(rad).toFloat(), ctr.y + r * sin(rad).toFloat()), 1.5f.dp.toPx())
            }
            fun needle(deg: Float, color: Color, w: Float) = rotate(deg, ctr) {
                val p = Path().apply {
                    moveTo(ctr.x, ctr.y - r * 0.82f); lineTo(ctr.x - w, ctr.y); lineTo(ctr.x, ctr.y + r * 0.2f); lineTo(ctr.x + w, ctr.y); close()
                }
                drawPath(p, color)
            }
            gnss?.let { needle(it, g, 10.dp.toPx()) }
            compass?.let { needle(it, a, 6.dp.toPx()) }
            drawCircle(hub, 6.dp.toPx(), ctr)
        }
        listOf("N" to Alignment.TopCenter, "E" to Alignment.CenterEnd, "S" to Alignment.BottomCenter, "O" to Alignment.CenterStart).forEach { (t, al) ->
            Text(t, Modifier.align(al).padding(22.dp), style = rf(14, 18, 700), color = if (t == "N") cs.error else ink)
        }
    }
}

// ---- 5. Atomic clock -------------------------------------------------------------------------------

@Composable
fun GnssClockTool(vm: MainViewModel) {
    val gnss by vm.gnss.collectAsStateWithLifecycle()
    var raw by remember { mutableStateOf<RawEpoch?>(null) }
    LaunchedEffect(Unit) { vm.gnssRaw.collect { raw = it } }
    var nowElapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
    LaunchedEffect(Unit) { while (true) withFrameMillis { nowElapsed = SystemClock.elapsedRealtimeNanos() } }
    // Reference: the chip's own clock when it gives its bias, else the last fix's time.
    val rawUtc = raw?.let { e -> GnssRaw.utcMillis(e.clock)?.let { it to (e.clock.elapsedRealtimeNanos ?: e.receivedAtElapsedNanos) } }
    val fix = gnss.location
    val ref = rawUtc ?: fix?.let { it.time.toDouble() to it.elapsedRealtimeNanos }
    val utcNow = ref?.let { (t, el) -> t + (nowElapsed - el) / 1e6 }
    val phoneOffset = utcNow?.let { System.currentTimeMillis() - it }
    PageColumn {
        HeroCard {
            Text(if (rawUtc != null) S(R.string.satellite_clock) else if (fix != null) S(R.string.time_of_the_last_fix) else S(R.string.waiting_for_satellites_2), style = rf(14, 20, 600))
            val utcFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val local = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            Text(utcNow?.let { utcFmt.format(Date(it.toLong())) } ?: "--:--:--.---", Modifier.padding(top = 4.dp), style = gs(46, 52, 500, -1f, tnum = true))
            Text(utcNow?.let { S(R.string.utc_local_time, local.format(Date(it.toLong()))) } ?: "UTC", style = rf(14, 20))
            if (phoneOffset != null) {
                Row(Modifier.padding(top = 12.dp)) {
                    HeroChip(
                        when {
                            abs(phoneOffset) < 50 -> S(R.string.phone_on_time_ms, fmt(phoneOffset.toFloat(), 0))
                            phoneOffset > 0 -> S(R.string.phone_ahead_by, offsetText(phoneOffset))
                            else -> S(R.string.phone_behind_by, offsetText(-phoneOffset))
                        },
                        if (abs(phoneOffset) < 500) AntTheme.net.good else AntTheme.net.fair,
                    )
                }
            }
        }
        val e = raw
        InfoList(S(R.string.gps_time)) {
            val gpsNs = e?.let { GnssRaw.gpsNanos(it.clock) }
            InfoRow(S(R.string.gps_week), gpsNs?.let { (it / GnssRaw.WEEK_NS).toString() } ?: "—")
            InfoRow(S(R.string.second_of_the_week), gpsNs?.let { fmt(((it % GnssRaw.WEEK_NS) / 1e9).toFloat(), 3) } ?: "—")
            InfoRow(S(R.string.leap_seconds), e?.let { "${GnssRaw.leap(it.clock)} s" + if (it.clock.leapSecond == null) S(R.string.default_value) else "" } ?: "18 s")
            InfoRow("Source", if (rawUtc != null) S(R.string.gnss_clock_raw_measurements) else if (fix != null) S(R.string.fix_time_a_few_ms) else "—")
        }
        if (e != null) {
            InfoList(S(R.string.phone_oscillator)) {
                InfoRow(S(R.string.time_uncertainty), e.clock.biasUncertaintyNanos?.let { "${fmt(it.toFloat(), 0)} ns" } ?: "—")
                InfoRow(S(R.string.crystal_drift), e.clock.driftNanosPerSecond?.let { "${fmt((it / 1000).toFloat(), 3)} ppm (${fmt(it.toFloat(), 0)} ns/s)" } ?: "—")
                InfoRow(S(R.string.hardware_discontinuities), e.clock.hardwareDiscontinuityCount.toString())
            }
        }
        Text(
            S(R.string.every_satellite_carries_atomic_clocks_the) +
                S(R.string.the_display_depends_on_the_screen),
            Modifier.padding(horizontal = 4.dp), style = rf(12, 16), color = cs.onSurfaceVariant,
        )
    }
}

private fun offsetText(ms: Double) = if (ms >= 1000) "${fmt((ms / 1000).toFloat(), 2)} s" else "${fmt(ms.toFloat(), 0)} ms"

// ---- 6. Sky mask -----------------------------------------------------------------------------------

class SkyMaskController {
    val mask = SkyMask()
    var version by mutableIntStateOf(0)
    var lastFeedMs = 0L
}

@Composable
fun SkyMaskTool(vm: MainViewModel) {
    val c = vm.tools.skyMask
    val gnss by vm.gnss.collectAsStateWithLifecycle()
    LaunchedEffect(gnss.satellites) {
        val now = System.currentTimeMillis()
        if (now - c.lastFeedMs >= 4000 && gnss.satellites.isNotEmpty()) {
            c.lastFeedMs = now
            c.mask.feed(gnss.satellites.filter { it.elevation > 0f })
            c.version++
        }
    }
    TopBarAction(Sym.RestartAlt) { c.mask.reset(); c.version++ }
    val open = remember(c.version) { c.mask.openSky() }
    val clear = remember(c.version) { c.mask.clearDirections() }
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(open?.let { fmt(it * 100, 0) } ?: "—", style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(S(R.string.open_sky), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            val dirs = listOf("N", "NNE", "ENE", "E", "ESE", "SSE", "S", "SSO", "OSO", "O", "ONO", "NNO")
            Text(
                if (clear.isEmpty()) S(R.string.stay_still_outside_for_a_few)
                else S(R.string.open_sky_towards, clear.joinToString(", ") { dirs[it] }),
                Modifier.padding(top = 4.dp), style = rf(14, 20),
            )
        }
        SectionCard {
            MaskPlot(c.mask, c.version, gnss.satellites.filter { it.elevation > 0f }, Modifier.fillMaxWidth().aspectRatio(1f).padding(8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Legend(AntTheme.net.good, S(R.string.open_3))
                Legend(AntTheme.net.fair, S(R.string.partial))
                Legend(cs.error, S(R.string.blocked))
                Legend(cs.surfaceContainerHighest, S(R.string.not_seen_yet))
            }
        }
        InfoList("Observations") {
            InfoRow(S(R.string.satellite_measurements), c.mask.observations.toString())
            InfoRow(S(R.string.visible_satellites), gnss.satellites.count { it.elevation > 0f }.toString())
            InfoRow(S(R.string.received), gnss.satellites.count { it.cn0 > 0f }.toString())
        }
        Text(
            S(R.string.android_knows_where_every_satellite_is) +
                S(R.string.building_tree_roof_in_its_direction),
            Modifier.padding(horizontal = 4.dp), style = rf(12, 16), color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun MaskPlot(mask: SkyMask, version: Int, sats: List<Satellite>, modifier: Modifier) {
    val good = AntTheme.net.good
    val fair = AntTheme.net.fair
    val bad = cs.error
    val none = cs.surfaceContainerHighest
    val line = cs.outlineVariant
    val ink = cs.onSurface
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            if (version < 0) return@Canvas // redraw when the mask changes
            val r = size.minDimension / 2
            val ctr = Offset(size.width / 2, size.height / 2)
            // Rings: 10–30°, 30–60°, 60–90° elevation, outer to inner.
            val bounds = floatArrayOf(10f, 30f, 60f, 90f)
            fun rad(el: Float) = r * (90f - el) / 80f
            for (ring in 0..2) {
                for (s in 0 until 12) {
                    val share = mask.blockedShare(ring, s)
                    val color = when {
                        share == null -> none
                        share < 0.34f -> good
                        share < 0.67f -> fair
                        else -> bad
                    }
                    val outer = rad(bounds[ring])
                    drawArc(color.copy(alpha = if (share == null) 1f else 0.75f), s * 30f - 90f, 30f, true, Offset(ctr.x - outer, ctr.y - outer), Size(outer * 2, outer * 2))
                }
            }
            for (k in bounds) drawCircle(line, rad(k), ctr, style = Stroke(1.dp.toPx()))
            for (s in 0 until 12) {
                val a = Math.toRadians(s * 30.0 - 90)
                drawLine(line, ctr, Offset(ctr.x + r * cos(a).toFloat(), ctr.y + r * sin(a).toFloat()), 1.dp.toPx())
            }
            sats.forEach { s ->
                val a = Math.toRadians(s.azimuth - 90.0)
                val d = rad(s.elevation.coerceAtLeast(0f)).coerceAtMost(r)
                val p = Offset(ctr.x + d * cos(a).toFloat(), ctr.y + d * sin(a).toFloat())
                drawCircle(Color.White, 5.dp.toPx(), p)
                drawCircle(if (s.cn0 > 0) ink else line, 3.5f.dp.toPx(), p)
            }
        }
        listOf("N" to Alignment.TopCenter, "E" to Alignment.CenterEnd, "S" to Alignment.BottomCenter, "O" to Alignment.CenterStart).forEach { (t, al) ->
            Text(t, Modifier.align(al), style = rf(12, 16, 700), color = ink, textAlign = TextAlign.Center)
        }
    }
}

