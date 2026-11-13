package com.allnetworktools.ui.pages.sdr

import com.allnetworktools.data.sdr.AutoGain
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import android.os.Looper
import android.os.Handler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.allnetworktools.ui.components.SegmentedRow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.Page
import com.allnetworktools.model.Tool
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.InfoList
import com.allnetworktools.ui.components.InfoRow
import com.allnetworktools.ui.components.PillButton
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.ShapeBadge
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.TechChip
import com.allnetworktools.ui.components.cookieShape
import com.allnetworktools.ui.components.fadeUp
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import java.util.Locale
import com.allnetworktools.R
import com.allnetworktools.util.S

internal fun snr(db: Double) = "%+.1f dB".format(Locale.getDefault(), db)

@Composable
fun SdrDashboard(vm: MainViewModel) {
    val roles = AntTheme.net.sdr
    val c = vm.tools.spectrum
    val device by vm.sdrDevice.collectAsStateWithLifecycle()
    val open = { t: Tool -> vm.navigate { it.copy(page = Page.ToolPage(t)) } }
    PageColumn {
        Column(
            Modifier.fadeUp().fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(roles.container)
                .clickable { open(Tool.Spectrum) }.padding(20.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                ShapeBadge(Sym.BarChart, cookieShape(), 64.dp, roles.accent, roles.onAccent, 30.dp, spinMs = 20_000)
                if (c.running) TechChip(S(R.string.listening_3), roles.accent, roles.onAccent)
            }
            Text(S(R.string.spectrum_analyser), Modifier.padding(top = 16.dp), style = gs(24, 30, 500), color = roles.onContainer)
            Text(
                spectrumSubtitle(device),
                Modifier.padding(top = 4.dp).graphicsLayer { alpha = 0.85f }, style = rf(14, 20), color = roles.onContainer,
            )
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
                PillButton(S(R.string.open_2), { open(Tool.Spectrum) }, icon = Sym.PlayArrow, height = 48.dp)
            }
        }
        InfoList(S(R.string.receiver)) {
            InfoRow(S(R.string.hardware), device?.name ?: "—")
            InfoRow("Mode", S(R.string.receive_only_3))
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(
                    S(R.string.the_hackrf_only_receives_while_a) +
                        S(R.string.the_app_never_transmits_decoding_is),
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun Empty(text: String) {
    SectionCard { Text(text, style = rf(13, 18), color = cs.onSurfaceVariant) }
}

@Composable
internal fun ListCard(content: @Composable () -> Unit) {
    SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) { content() }
}

/**
 * The HackRF's gains for one tool: the LNA and VGA values it offers by hand, the +14 dB amplifier when it has one, and
 * the automatic mode, on by default, which moves over the radio's whole range ([AutoGain]) for the tool's [profile].
 */
class SdrGain(
    val lnaSteps: List<Int>, lna: Int, val vgaSteps: List<Int>, vga: Int, amp: Boolean? = null,
    profile: com.allnetworktools.data.sdr.GainProfile = com.allnetworktools.data.sdr.GainProfile.Narrow,
) {
    /** What the tool's signals ask of the automatic gain; a tool whose mode changes (Listen) sets it again. */
    var profile = profile
        set(v) { field = v; loop?.reprofile(v) }
    var auto by mutableStateOf(true)
    var lna by mutableIntStateOf(lna)
    var vga by mutableIntStateOf(vga)
    /** Null when the tool does not offer the amplifier. */
    var amp by mutableStateOf(amp)

    /** Off for a moment while [AmpCheck] measures without it. */
    @Volatile private var probeOff = false

    /** What the radio's amplifier must be: the user's choice, unless found dead or briefly off for a check. */
    val ampOn: Boolean get() = amp == true && !probeOff && !com.allnetworktools.data.sdr.AmpHealth.dead

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var loop: AutoGain? = null
    private val ampCheck = com.allnetworktools.data.sdr.AmpCheck()
    private var onAdjust: () -> Unit = {}

    /** Called when reception starts: from then on, in automatic mode, [onAdjust] pushes each new step to the radio. */
    fun attach(onAdjust: () -> Unit) {
        this.onAdjust = onAdjust
        loop = AutoGain(profile, lna, vga)
    }

    /** Samples as they arrive from the radio, on its thread. */
    fun feed(buf: ByteArray, len: Int) {
        // The amplifier check runs whatever the gain mode, on the HackRF only (the other radios have no such switch).
        val kind = com.allnetworktools.data.sdr.SdrSettings.kind
        if (amp != null && (kind == null || kind == com.allnetworktools.data.sdr.SdrKind.HackRf)) {
            val nowS = System.nanoTime() / 1e9
            when (ampCheck.feed(buf, len, nowS, ampOn, lna, vga)) {
                com.allnetworktools.data.sdr.AmpCheck.Action.ProbeOff -> { probeOff = true; main.post { onAdjust() } }
                com.allnetworktools.data.sdr.AmpCheck.Action.Restore -> { probeOff = false; main.post { onAdjust() } }
                com.allnetworktools.data.sdr.AmpCheck.Action.Dead -> {
                    probeOff = false
                    com.allnetworktools.util.DebugLog.w("SDR", "Amplifier found dead: much less signal with it on than off, ${com.allnetworktools.data.sdr.AmpCheck.STRIKES} times")
                    main.post { onAdjust() }
                }
                com.allnetworktools.data.sdr.AmpCheck.Action.None -> Unit
            }
        }
        if (!auto) return
        val l = loop ?: return
        val step = l.feed(buf, len, System.nanoTime()) ?: return
        main.post {
            if (!auto) return@post
            lna = step.first; vga = step.second
            onAdjust()
        }
    }

    fun setAuto() {
        auto = true
        loop = AutoGain(profile, lna, vga)
    }
}

/**
 * The gains as sliders over the radio's whole range, with one Auto switch for all of them. While Auto runs, the sliders
 * follow the values it picks, in its own colour; moving one goes back to manual.
 */
@Composable
internal fun GainSettings(g: SdrGain, onChange: () -> Unit) {
    val kind = com.allnetworktools.data.sdr.SdrSettings.kind
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val steps = kind?.gainSteps
        if (steps != null) {
            // A radio with its own gain scale: one slider in its own steps instead of the HackRF's LNA / VGA / amplifier.
            NativeGainLine(kind, steps, g, onChange)
        } else {
            AntFilterChip(S(R.string.auto_gain), g.auto, { if (!g.auto) { g.setAuto(); onChange() } else { g.auto = false } })
            GainSlider(S(R.string.lna_gain_db), g.lna, 0, 40, 8, g.auto, onChange) { g.auto = false; g.lna = it }
            GainSlider(S(R.string.vga_gain_db), g.vga, 0, 62, 2, g.auto, onChange) { g.auto = false; g.vga = it }
            g.amp?.let { on ->
                Text(S(R.string.preamplifier), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                if (com.allnetworktools.data.sdr.AmpHealth.dead) {
                    // Found dead this session: kept off, and the user may try it again.
                    Text(S(R.string.sdr_amp_dead), style = rf(12, 16), color = cs.error)
                    androidx.compose.material3.TextButton({ com.allnetworktools.data.sdr.AmpHealth.reset(); onChange() }) { Text(S(R.string.sdr_amp_retry)) }
                } else {
                    AntFilterChip(S(R.string.amp_14_db), on, { g.amp = !on; onChange() })
                }
            }
        }
        if (kind?.biasTee == true) BiasTeeLine()
        ConverterLine()
    }
}

/** An up- or down-converter in front of the radio: what it adds to the frequency, in MHz. */
@Composable
private fun ConverterLine() {
    val st = com.allnetworktools.data.sdr.SdrSettings
    var text by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(converterText(st.converterHz)) }
    Text(S(R.string.sdr_converter), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AntFilterChip(S(R.string.sdr_converter_none), st.converterHz == 0L, { st.converterHz = 0L; text = "0" })
        AntFilterChip("Ham It Up +125 MHz", st.converterHz == 125_000_000L, { st.converterHz = 125_000_000L; text = "125" })
        AntFilterChip("SpyVerter +120 MHz", st.converterHz == 120_000_000L, { st.converterHz = 120_000_000L; text = "120" })
    }
    com.allnetworktools.ui.tools.HostInputField(
        text,
        { v ->
            text = v
            v.trim().replace(',', '.').toDoubleOrNull()?.takeIf { kotlin.math.abs(it) < 100_000.0 }?.let { st.converterHz = Math.round(it * 1e6) }
        },
        S(R.string.sdr_converter_mhz), Sym.Antenna, keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
    )
    Text(S(R.string.sdr_converter_hint), style = rf(12, 16), color = cs.onSurfaceVariant)
}

private fun converterText(hz: Long): String =
    java.math.BigDecimal(hz).divide(java.math.BigDecimal(1_000_000)).stripTrailingZeros().toPlainString()

@Composable
private fun NativeGainLine(kind: com.allnetworktools.data.sdr.SdrKind, steps: List<Int>, g: SdrGain, onChange: () -> Unit) {
    val st = com.allnetworktools.data.sdr.SdrSettings
    AntFilterChip(S(R.string.auto_gain), st.nativeAuto, {
        if (!st.nativeAuto) { st.nativeAuto = true; g.setAuto(); onChange() } else { st.nativeAuto = false; g.auto = false; onChange() }
    })
    val index = steps.indexOf(st.nativeGain).takeIf { it >= 0 } ?: steps.indices.minBy { kotlin.math.abs(steps[it] - st.nativeGain) }
    val label = S(R.string.sdr_gain_of, kind.label)
    GainSliderRow(label, if (st.nativeAuto) S(R.string.auto_gain) else "${steps[index]}" + if (kind.gainUnit.isNotEmpty()) " ${kind.gainUnit}" else "", st.nativeAuto)
    androidx.compose.material3.Slider(
        value = index.toFloat(),
        onValueChange = { v -> st.nativeAuto = false; g.auto = false; st.nativeGain = steps[Math.round(v).coerceIn(0, steps.size - 1)] },
        onValueChangeFinished = onChange,
        valueRange = 0f..(steps.size - 1).toFloat(),
        steps = (steps.size - 2).coerceAtLeast(0),
        colors = sliderColors(st.nativeAuto, ticks = steps.size <= 11),
    )
}

/** Power on the antenna port, for an active antenna or a preamplifier. */
@Composable
private fun BiasTeeLine() {
    val st = com.allnetworktools.data.sdr.SdrSettings
    Text(S(R.string.sdr_bias_tee), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
    AntFilterChip(S(R.string.sdr_bias_tee_chip), st.biasTee, {
        st.biasTee = !st.biasTee
        Thread { st.applyBiasTee() }.start()
    })
    if (st.biasTee) Text(S(R.string.sdr_bias_tee_warning), style = rf(12, 16), color = cs.error)
}

/** A gain's name, and its value on the right (in the automatic colour while Auto picks it). */
@Composable
private fun GainSliderRow(label: String, value: String, auto: Boolean) {
    androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, androidx.compose.ui.Modifier.weight(1f), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
        Text(value, style = rf(14, 18, 700, tnum = true), color = if (auto) cs.tertiary else cs.onSurface)
    }
}

/** Slider colours: the automatic colour while Auto picks the value; no tick marks when there are too many to read. */
@Composable
internal fun sliderColors(auto: Boolean, ticks: Boolean = true): androidx.compose.material3.SliderColors {
    val none = androidx.compose.ui.graphics.Color.Transparent
    return if (auto) androidx.compose.material3.SliderDefaults.colors(
        thumbColor = cs.tertiary, activeTrackColor = cs.tertiary, inactiveTrackColor = cs.tertiaryContainer,
        activeTickColor = if (ticks) cs.onTertiary else none, inactiveTickColor = if (ticks) cs.tertiary else none,
    ) else if (ticks) androidx.compose.material3.SliderDefaults.colors()
    else androidx.compose.material3.SliderDefaults.colors(activeTickColor = none, inactiveTickColor = none)
}

/** One gain stage as a slider from [min] to [max] in steps of [step] dB; the radio is set when the finger lifts. */
@Composable
private fun GainSlider(label: String, value: Int, min: Int, max: Int, step: Int, auto: Boolean, onChange: () -> Unit, onPick: (Int) -> Unit) {
    GainSliderRow(label, "$value dB", auto)
    androidx.compose.material3.Slider(
        value = value.toFloat().coerceIn(min.toFloat(), max.toFloat()),
        onValueChange = { v -> val snapped = min + Math.round((v - min) / step) * step; if (snapped != value || auto) onPick(snapped.coerceIn(min, max)) },
        onValueChangeFinished = onChange,
        valueRange = min.toFloat()..max.toFloat(),
        steps = ((max - min) / step - 1).coerceAtLeast(0),
        colors = sliderColors(auto, ticks = (max - min) / step <= 10),
    )
}

/** The tabs of an SDR tool: its map (when it has one), what it receives, and its settings. */
enum class ToolTab { Map, List, Main, Settings }

/** The tab picked in each tool, kept for the session so coming back to a tool shows the same tab. */
internal object ToolTabMemory {
    val chosen = androidx.compose.runtime.mutableStateMapOf<String, ToolTab>()
}

/**
 * The tab row under a tool's start button, as in the mesh tools: Map, List and Settings for a tool with a map; what it
 * receives and Settings otherwise. Settings holds the frequency, the gains, the bias tee and the explanation.
 */
@Composable
internal fun ToolTabRow(key: String, map: Boolean): ToolTab {
    val tabs = buildList {
        if (map) add(ToolTab.Map to S(R.string.mesh_tab_map))
        add((if (map) ToolTab.List else ToolTab.Main) to S(if (map) R.string.tool_tab_list else R.string.tool_tab_reception))
        add(ToolTab.Settings to S(R.string.tool_tab_settings))
    }
    val current = ToolTabMemory.chosen[key]?.takeIf { t -> tabs.any { it.first == t } } ?: tabs.first().first
    SegmentedRow(tabs, current, { ToolTabMemory.chosen[key] = it }, Modifier.fillMaxWidth())
    return current
}

/** "Spectrum and waterfall, 24 MHz to 1.766 GHz" for the radio plugged in (through its converter); no range without one. */
internal fun spectrumSubtitle(device: com.allnetworktools.data.sdr.SdrDevice?): String =
    device?.let {
        val r = com.allnetworktools.data.sdr.SdrSettings.tunable(it.kind, it.usb?.productName)
        S(R.string.spectrum_and_waterfall_range, hzLabel(r.first), hzLabel(r.last))
    } ?: S(R.string.spectrum_and_waterfall)

/** A frequency in the unit that reads best, without useless decimals: "500 kHz", "24 MHz", "1.766 GHz". */
internal fun hzLabel(hz: Long): String {
    val (div, unit) = when {
        hz >= 1_000_000_000L -> 1_000_000_000L to "GHz"
        hz >= 1_000_000L -> 1_000_000L to "MHz"
        else -> 1_000L to "kHz"
    }
    val n = java.math.BigDecimal(hz).divide(java.math.BigDecimal(div)).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    val sep = java.text.DecimalFormatSymbols.getInstance(java.util.Locale.getDefault()).decimalSeparator
    return "${if (sep == ',') n.replace('.', ',') else n} $unit"
}
