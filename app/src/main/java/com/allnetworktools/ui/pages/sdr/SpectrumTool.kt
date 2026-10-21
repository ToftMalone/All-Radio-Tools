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

internal fun mhzLabel(hz: Double) = "%.3f".format(Locale.getDefault(), hz / 1e6)

@Composable
fun SpectrumTool(vm: MainViewModel) {
    val c = vm.tools.spectrum
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    val db = c.spectrum
    val center = if (c.running) c.tunedHz.toDouble() else (c.parsedCenterHz() ?: 0L).toDouble()
    val span = c.spanMhz * 1e6
    val peakIdx = db?.let { a -> a.indices.maxBy { a[it] } }
    val peakHz = peakIdx?.let { center - span / 2 + (it + 0.5) * span / db.size }
    PageColumn {
        HeroCard {
            Text("${mhzLabel(center)} MHz", style = gs(30, 36, 500, tnum = true))
            Text(S(R.string.width_mhz_from_to_mhz, c.spanMhz, mhzLabel(center - span / 2), mhzLabel(center + span / 2)), Modifier.padding(top = 2.dp), style = rf(14, 20))
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
        SpectrumView(db, c.hold, peakIdx)
        WaterfallView(c)
        ToolButtons(
            ToolButton("− ${c.spanMhz / 2} MHz", Sym.ChevronLeft, BtnKind.OutlineOnSurface) { c.retune((center - span / 2).toLong()) },
            ToolButton("+ ${c.spanMhz / 2} MHz", Sym.ChevronRight, BtnKind.OutlineOnSurface) { c.retune((center + span / 2).toLong()) },
        )
        if (c.running || c.starting) StartButton(S(R.string.stop), Sym.Stop) { c.stop() }
        else StartButton(S(R.string.start_the_analysis), Sym.PlayArrow, enabled = device != null && c.parsedCenterHz() != null) { device?.let(c::start) }
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HostInputField(c.centerMhz, { c.centerMhz = it }, S(R.string.centre_frequency_mhz), Sym.Stream, keyboardType = KeyboardType.Decimal) {
                    c.parsedCenterHz()?.let(c::retune)
                }
                Text(S(R.string.bands), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpectrumPreset.entries.forEach { p ->
                        AntFilterChip(p.label, false, {
                            if (!c.running) c.spanMhz = p.spanMhz
                            c.retune((p.centerMhz * 1e6).toLong())
                        })
                    }
                }
                Text(S(R.string.displayed_width_at_next_start), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2, 5, 10, 20).forEach { s -> AntFilterChip("$s MHz", c.spanMhz == s, { if (c.spanMhz != s && !c.starting) { if (c.running) c.stop(); c.spanMhz = s } }) }
                }
                GainSettings(
                    c.lnaGain, listOf(0, 8, 16, 24, 32, 40), { c.lnaGain = it; c.applyGains() },
                    c.vgaGain, listOf(10, 20, 30, 40), { c.vgaGain = it; c.applyGains() },
                    c.amp, { c.amp = !c.amp; c.applyGains() },
                )
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
internal fun SpectrumView(db: FloatArray?, hold: FloatArray?, peakIdx: Int?) {
    val line = AntTheme.accent.accent
    val grid = cs.outlineVariant
    val holdColor = AntTheme.net.fair
    Box(Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainerHigh)) {
        Canvas(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 12.dp)) {
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
