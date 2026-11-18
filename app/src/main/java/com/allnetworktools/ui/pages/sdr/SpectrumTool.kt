package com.allnetworktools.ui.pages.sdr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.ui.components.AntFilterChip
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
import com.allnetworktools.ui.tools.HostInputField
import com.allnetworktools.ui.tools.StartButton
import com.allnetworktools.ui.tools.BtnKind
import com.allnetworktools.ui.tools.ToolButton
import com.allnetworktools.ui.tools.ToolButtons
import java.util.Locale
import com.allnetworktools.R
import com.allnetworktools.util.S

/** The widest width the radio offers that is not wider than [wanted], or its narrowest one. */
internal fun fitSpan(wanted: Int, spans: List<Int>?): Int =
    if (spans.isNullOrEmpty()) wanted else spans.filter { it <= wanted }.maxOrNull() ?: spans.min()

internal fun mhzLabel(hz: Double) = "%.3f".format(Locale.getDefault(), hz / 1e6)

/** The level under the marker: the highest of the points around it, so a narrow signal is not missed. */
internal fun markerLevel(db: FloatArray, at: Float): Float {
    val i = Math.round(at * (db.size - 1))
    return (maxOf(0, i - 1)..minOf(db.size - 1, i + 1)).maxOf { db[it] }
}

/** A step in Hz as "2.5 kHz" or "100 kHz". */
internal fun markerStepLabel(hz: Long): String = if (hz >= 1_000_000L) "${widthLabel(hz.toDouble())} MHz" else "${java.math.BigDecimal(hz / 1000.0).setScale(1, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()} kHz"

/** A width in MHz without useless decimals: "10", "2.5", "0.125". */
internal fun widthLabel(hz: Double): String {
    val mhz = hz / 1e6
    return java.math.BigDecimal(mhz).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().let {
        if (Locale.getDefault().let { l -> java.text.DecimalFormatSymbols.getInstance(l).decimalSeparator } == ',') it.replace('.', ',') else it
    }
}

/** Two fingers zoom in or out one step each time the pinch doubles or halves; one finger is left alone. */
internal fun Modifier.pinchZoom(zoom: Int, onZoom: (Int) -> Unit): Modifier = pointerInput(zoom) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var pinch = 1f
        do {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } >= 2) {
                pinch *= event.calculateZoom()
                if (pinch > 1.6f) { onZoom(zoom * 2); pinch = 1f }
                if (pinch < 0.62f) { onZoom(zoom / 2); pinch = 1f }
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

@Composable
fun SpectrumTool(vm: MainViewModel) {
    val c = vm.tools.spectrum
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    // The width follows the radio plugged in: an RTL-SDR shows at most 3 MHz.
    androidx.compose.runtime.LaunchedEffect(device) { device?.let { if (!c.running && !c.starting) c.spanMhz = fitSpan(c.spanMhz, it.spectrumSpans) } }
    val db = c.spectrum
    val center = if (c.running) c.tunedHz.toDouble() else (c.parsedCenterHz() ?: 0L).toDouble()
    val span = c.spanMhz * 1e6 / c.zoom
    val peakIdx = db?.let { a -> a.indices.maxBy { a[it] } }
    val peakHz = peakIdx?.let { center - span / 2 + (it + 0.5) * span / db.size }
    PageColumn {
        HeroCard {
            Text("${mhzLabel(center)} MHz", style = gs(30, 36, 500, tnum = true))
            Text(S(R.string.width_mhz_from_to_mhz, widthLabel(span), mhzLabel(center - span / 2), mhzLabel(center + span / 2)), Modifier.padding(top = 2.dp), style = rf(14, 20))
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    c.error != null -> HeroChip(c.error!!, AntTheme.net.poor)
                    c.starting -> HeroChip(S(R.string.starting_the_hackrf), AntTheme.net.fair, blink = true)
                    c.running && peakHz != null && db != null -> HeroChip(S(R.string.peak_mhz_dbfs, mhzLabel(peakHz)).format(Locale.getDefault(), db[peakIdx!!]), AntTheme.net.good, blink = true)
                    device == null -> HeroChip(S(R.string.no_hackrf_plugged_in), AntTheme.net.poor)
                    else -> HeroChip(S(R.string.ready, device!!.name), AntTheme.net.good)
                }
            }
        }
        // The marker, as a place along the spectrum shown (null when off it).
        val marker = c.markerHz
        val markerAt = marker?.let { ((it - (center - span / 2)) / span).toFloat() }?.takeIf { it in 0f..1f }
        Column(Modifier.pinchZoom(c.zoom, c::chooseZoom), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SpectrumView(db, c.hold, peakIdx, markerAt) { f -> c.markerHz = (center - span / 2 + f * span).toLong() }
            WaterfallView(c)
        }
        if (marker != null) {
            val level = if (db != null && markerAt != null) markerLevel(db, markerAt) else null
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        S(R.string.spectrum_marker, mhzLabel(marker.toDouble())) + (level?.let { " · " + "%.0f dBFS".format(Locale.getDefault(), it) } ?: ""),
                        style = rf(15, 20, 600, tnum = true),
                    )
                    SignalGuess(vm, c, db, markerAt, marker, center, span)
                    ToolButtons(
                        ToolButton("− ${markerStepLabel(c.markerStepHz())}", Sym.ChevronLeft, BtnKind.OutlineOnSurface) { c.nudgeMarker(-1, center.toLong()) },
                        ToolButton("+ ${markerStepLabel(c.markerStepHz())}", Sym.ChevronRight, BtnKind.OutlineOnSurface) { c.nudgeMarker(1, center.toLong()) },
                    )
                    ToolButtons(
                        ToolButton(S(R.string.spectrum_listen_here), Sym.Headphones) {
                            // The radio goes to the Listen tool, tuned on the marker in the mode of its band.
                            if (c.running || c.starting) c.stop()
                            vm.tools.listen.listenAt(marker)
                            vm.navigate { it.copy(page = com.allnetworktools.Page.ToolPage(com.allnetworktools.model.Tool.Listen)) }
                        },
                        ToolButton(S(R.string.spectrum_marker_clear), Sym.Close, BtnKind.OutlineOnSurface) { c.markerHz = null },
                    )
                }
            }
        } else if (db != null) {
            Text(S(R.string.spectrum_marker_hint), Modifier.padding(horizontal = 8.dp), style = rf(12, 16), color = cs.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SpectrumController.Zooms.forEach { z -> AntFilterChip(widthLabel(c.spanMhz * 1e6 / z) + " MHz", c.zoom == z, { c.chooseZoom(z) }) }
        }
        ToolButtons(
            ToolButton("− ${widthLabel(span / 2)} MHz", Sym.ChevronLeft, BtnKind.OutlineOnSurface) { c.retune((center - span / 2).toLong()) },
            ToolButton("+ ${widthLabel(span / 2)} MHz", Sym.ChevronRight, BtnKind.OutlineOnSurface) { c.retune((center + span / 2).toLong()) },
        )
        if (c.running || c.starting) StartButton(S(R.string.stop), Sym.Stop) { c.stop() }
        else StartButton(S(R.string.start_the_analysis), Sym.PlayArrow, enabled = device != null && c.parsedCenterHz() != null) { device?.let(c::start) }
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HostInputField(c.centerMhz, { c.centerMhz = it }, S(R.string.centre_frequency_mhz), Sym.Antenna, keyboardType = KeyboardType.Decimal) {
                    c.parsedCenterHz()?.let(c::retune)
                }
                Text(S(R.string.bands), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpectrumPreset.entries.forEach { p ->
                        AntFilterChip(p.label, false, {
                            if (!c.running) c.spanMhz = fitSpan(p.spanMhz, device?.spectrumSpans)
                            c.retune((p.centerMhz * 1e6).toLong())
                        })
                    }
                }
                Text(S(R.string.displayed_width_at_next_start), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (device?.spectrumSpans ?: listOf(2, 5, 10, 20)).forEach { s -> AntFilterChip("$s MHz", c.spanMhz == s, { if (c.spanMhz != s && !c.starting) { if (c.running) c.stop(); c.spanMhz = s } }) }
                }
                GainSettings(c.gain) { c.applyGains() }
                Text(S(R.string.display_2), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                AntFilterChip(S(R.string.peak_hold), c.peakHold, { c.peakHold = !c.peakHold })
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(
                    S(R.string.receive_only_the_level_is_in) +
                        S(R.string.the_line_in_the_middle_is),
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun SpectrumView(db: FloatArray?, hold: FloatArray?, peakIdx: Int?, markerAt: Float? = null, onTap: ((Float) -> Unit)? = null) {
    val line = AntTheme.accent.accent
    val grid = cs.outlineVariant
    val holdColor = AntTheme.net.fair
    val markerColor = cs.tertiary
    Box(Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainerHigh)) {
        // The handler is read again at every gesture: it holds the centre and the width shown now, not those of the first frame.
        val current by androidx.compose.runtime.rememberUpdatedState(onTap)
        val tap = if (onTap != null) Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                fun at(x: Float) = (x / size.width).coerceIn(0f, 1f)
                var moved = false
                var scrolling = false
                while (true) {
                    val event = awaitPointerEvent()
                    // Two fingers are a pinch (the zoom), not a marker.
                    if (event.changes.count { it.pressed } > 1) { scrolling = true; break }
                    val ch = event.changes.firstOrNull() ?: break
                    if (!ch.pressed) break
                    val dx = ch.position.x - down.position.x
                    val dy = ch.position.y - down.position.y
                    if (!moved && kotlin.math.abs(dy) > viewConfiguration.touchSlop && kotlin.math.abs(dy) > kotlin.math.abs(dx)) { scrolling = true; break }
                    if (kotlin.math.abs(dx) > viewConfiguration.touchSlop) moved = true
                    // One finger sliding along the spectrum moves the marker, as the dial of the Listen tool.
                    if (moved) { current?.invoke(at(ch.position.x)); ch.consume() }
                }
                if (!moved && !scrolling) current?.invoke(at(down.position.x))
            }
        } else Modifier
        Canvas(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 12.dp).then(tap)) {
            val minDb = -110f
            val maxDb = -10f
            fun y(v: Float) = size.height * (1 - ((v - minDb) / (maxDb - minDb)).coerceIn(0f, 1f))
            for (g in -100..-20 step 20) drawLine(grid, Offset(0f, y(g.toFloat())), Offset(size.width, y(g.toFloat())), 1f)
            drawLine(grid, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 1f)
            fun path(a: FloatArray) = Path().apply {
                a.forEachIndexed { i, v ->
                    val x = size.width * i / (a.size - 1)
                    if (i == 0) moveTo(x, y(v)) else lineTo(x, y(v))
                }
            }
            hold?.let { drawPath(path(it), holdColor.copy(alpha = 0.7f), style = Stroke(1.5f)) }
            if (db != null) {
                drawPath(path(db), line, style = Stroke(2f))
                peakIdx?.let { drawCircle(line, 5f, Offset(size.width * it / (db.size - 1), y(db[it]))) }
            }
            markerAt?.let { m ->
                val x = size.width * m
                drawLine(markerColor, Offset(x, 0f), Offset(x, size.height), 3f)
                if (db != null) drawCircle(markerColor, 7f, Offset(x, y(markerLevel(db, m))))
            }
        }
        if (db == null) Text(S(R.string.the_spectrum_shows_here_during_the), Modifier.align(Alignment.Center), style = rf(13, 18), color = cs.onSurfaceVariant)
    }
}

@Composable
private fun WaterfallView(c: SpectrumController) {
    val frames = c.frames // recompose on each new row
    val image = remember { c.waterfall.asImageBitmap() }
    Box(Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF0B1026))) {
        Canvas(Modifier.fillMaxSize()) {
            if (frames >= 0) {
                drawImage(
                    image, IntOffset.Zero, IntSize(SpectrumController.WIDTH, SpectrumController.HEIGHT),
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Low,
                )
            }
        }
        Text(S(R.string.waterfall_newest_at_the_top), Modifier.align(Alignment.BottomStart).padding(10.dp), style = rf(11, 14), color = Color.White.copy(alpha = 0.7f))
    }
}

/** Names and descriptions of [SignalId.Kinds], by key. */
private val SignalTexts: Map<String, Pair<Int, Int>> = mapOf(
    "fm" to (R.string.sig_name_fm to R.string.sig_fm),
    "dab" to (R.string.sig_name_dab to R.string.sig_dab),
    "dvbt" to (R.string.sig_name_dvbt to R.string.sig_dvbt),
    "sw" to (R.string.sig_name_sw to R.string.sig_sw),
    "air" to (R.string.sig_name_air to R.string.sig_air),
    "acars" to (R.string.sig_name_acars to R.string.sig_acars),
    "vdl2" to (R.string.sig_name_vdl2 to R.string.sig_vdl2),
    "adsb" to (R.string.sig_name_adsb to R.string.sig_adsb),
    "modes_up" to (R.string.sig_name_modes_up to R.string.sig_modes_up),
    "milair" to (R.string.sig_name_milair to R.string.sig_milair),
    "flarm" to (R.string.sig_name_flarm to R.string.sig_flarm),
    "marine" to (R.string.sig_name_marine to R.string.sig_marine),
    "dsc" to (R.string.sig_name_dsc to R.string.sig_dsc),
    "ais" to (R.string.sig_name_ais to R.string.sig_ais),
    "sarsat" to (R.string.sig_name_sarsat to R.string.sig_sarsat),
    "meteor" to (R.string.sig_name_meteor to R.string.sig_meteor),
    "wxsat" to (R.string.sig_name_wxsat to R.string.sig_wxsat),
    "sonde" to (R.string.sig_name_sonde to R.string.sig_sonde),
    "gnss" to (R.string.sig_name_gnss to R.string.sig_gnss),
    "iridium" to (R.string.sig_name_iridium to R.string.sig_iridium),
    "inmarsat" to (R.string.sig_name_inmarsat to R.string.sig_inmarsat),
    "amsat" to (R.string.sig_name_amsat to R.string.sig_amsat),
    "ham_hf" to (R.string.sig_name_ham_hf to R.string.sig_ham_hf),
    "ft8" to (R.string.sig_name_ft8 to R.string.sig_ft8),
    "cb" to (R.string.sig_name_cb to R.string.sig_cb),
    "ham2m" to (R.string.sig_name_ham2m to R.string.sig_ham2m),
    "aprs" to (R.string.sig_name_aprs to R.string.sig_aprs),
    "ham70" to (R.string.sig_name_ham70 to R.string.sig_ham70),
    "lora_aprs" to (R.string.sig_name_lora_aprs to R.string.sig_lora_aprs),
    "ham23" to (R.string.sig_name_ham23 to R.string.sig_ham23),
    "pmr446" to (R.string.sig_name_pmr446 to R.string.sig_pmr446),
    "ism433" to (R.string.sig_name_ism433 to R.string.sig_ism433),
    "tpms" to (R.string.sig_name_tpms to R.string.sig_tpms),
    "srd868" to (R.string.sig_name_srd868 to R.string.sig_srd868),
    "audio863" to (R.string.sig_name_audio863 to R.string.sig_audio863),
    "lorawan" to (R.string.sig_name_lorawan to R.string.sig_lorawan),
    "sigfox" to (R.string.sig_name_sigfox to R.string.sig_sigfox),
    "meshtastic" to (R.string.sig_name_meshtastic to R.string.sig_meshtastic),
    "meshcore" to (R.string.sig_name_meshcore to R.string.sig_meshcore),
    "zwave" to (R.string.sig_name_zwave to R.string.sig_zwave),
    "wmbus" to (R.string.sig_name_wmbus to R.string.sig_wmbus),
    "pocsag" to (R.string.sig_name_pocsag to R.string.sig_pocsag),
    "ism915" to (R.string.sig_name_ism915 to R.string.sig_ism915),
    "dect" to (R.string.sig_name_dect to R.string.sig_dect),
    "wifi24" to (R.string.sig_name_wifi24 to R.string.sig_wifi24),
    "ble" to (R.string.sig_name_ble to R.string.sig_ble),
    "zigbee" to (R.string.sig_name_zigbee to R.string.sig_zigbee),
    "ant" to (R.string.sig_name_ant to R.string.sig_ant),
    "wifi5" to (R.string.sig_name_wifi5 to R.string.sig_wifi5),
    "fpv" to (R.string.sig_name_fpv to R.string.sig_fpv),
    "gsmr" to (R.string.sig_name_gsmr to R.string.sig_gsmr),
    "dl900" to (R.string.sig_name_dl900 to R.string.sig_dl900),
    "dl800" to (R.string.sig_name_dl800 to R.string.sig_dl800),
    "dl700" to (R.string.sig_name_dl700 to R.string.sig_dl700),
    "dl1800" to (R.string.sig_name_dl1800 to R.string.sig_dl1800),
    "dl2100" to (R.string.sig_name_dl2100 to R.string.sig_dl2100),
    "dl2600" to (R.string.sig_name_dl2600 to R.string.sig_dl2600),
    "nr3500" to (R.string.sig_name_nr3500 to R.string.sig_nr3500),
    "ul" to (R.string.sig_name_ul to R.string.sig_ul),
    "tetra" to (R.string.sig_name_tetra to R.string.sig_tetra),
    "pmse" to (R.string.sig_name_pmse to R.string.sig_pmse)
)

/** What the signal under the marker probably is, with a way to the tool that decodes it. */
@Composable
private fun SignalGuess(vm: MainViewModel, c: SpectrumController, db: FloatArray?, markerAt: Float?, marker: Long, center: Double, span: Double) {
    val id = com.allnetworktools.data.sdr.SignalId
    val m = remember(db, markerAt, marker, center, span) {
        if (db != null && markerAt != null) id.measure(db, markerAt, center, span)
        else com.allnetworktools.data.sdr.SignalId.Measure(marker, 0f, null, span / 1024, false)
    }
    val guesses = remember(m) { id.identify(m) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            when {
                !m.present -> S(R.string.sig_no_signal)
                m.bwHz != null && m.truncated -> S(R.string.sig_width_min, id.widthLabel(m.bwHz))
                m.bwHz != null -> S(R.string.sig_width, id.widthLabel(m.bwHz))
                else -> S(R.string.sig_too_narrow, id.widthLabel(m.resolutionHz * 3))
            },
            style = rf(12, 16), color = cs.onSurfaceVariant,
        )
        if (guesses.isEmpty()) {
            Text(S(R.string.sig_unknown), style = rf(13, 18))
            return@Column
        }
        Text(S(R.string.sig_title), style = rf(13, 18, 600), color = AntTheme.accent.accent)
        guesses.forEach { g ->
            val t = SignalTexts[g.kind.key]
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(t?.let { S(it.first) } ?: g.kind.name, style = rf(14, 20, 600))
                    t?.let { Text(S(it.second), style = rf(12, 16), color = cs.onSurfaceVariant) }
                }
                Text("%.0f %%".format(Locale.getDefault(), g.confidence * 100), style = rf(13, 18, 600, tnum = true))
            }
        }
        // The decoder of the likeliest kind, when the app has one (the Listen tool has its own button above).
        val best = guesses.first()
        val tool = best.kind.tool
        if (tool != null && tool != com.allnetworktools.model.Tool.Listen) {
            ToolButtons(
                ToolButton(S(R.string.sig_open, tool.title), Sym.OpenInNew, BtnKind.Outline) {
                    if (c.running || c.starting) c.stop()
                    vm.navigate { it.copy(page = com.allnetworktools.Page.ToolPage(tool)) }
                },
            )
        }
        Text(S(R.string.sig_note), style = rf(11, 14), color = cs.onSurfaceVariant)
    }
}
