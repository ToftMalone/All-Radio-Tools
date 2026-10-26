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
                S(R.string.spectrum_and_waterfall_1_mhz_to_2),
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
 * The HackRF's gains for one tool: the LNA and VGA values it offers, the +14 dB amplifier when it has one, and the
 * automatic mode, on by default, which picks among those same values from the level of the samples.
 */
class SdrGain(val lnaSteps: List<Int>, lna: Int, val vgaSteps: List<Int>, vga: Int, amp: Boolean? = null) {
    var auto by mutableStateOf(true)
    var lna by mutableIntStateOf(lna)
    var vga by mutableIntStateOf(vga)
    /** Null when the tool does not offer the amplifier. */
    var amp by mutableStateOf(amp)

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var loop: AutoGain? = null
    private var onAdjust: () -> Unit = {}

    /** Called when reception starts: from then on, in automatic mode, [onAdjust] pushes each new step to the radio. */
    fun attach(onAdjust: () -> Unit) {
        this.onAdjust = onAdjust
        loop = AutoGain(lnaSteps, vgaSteps, lna, vga)
    }

    /** Samples as they arrive from the radio, on its thread. */
    fun feed(buf: ByteArray, len: Int) {
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
        loop = AutoGain(lnaSteps, vgaSteps, lna, vga)
    }
}

/**
 * Each gain on its own line: Auto first, then the values. Auto is shared, picking it on one line sets both; while it
 * runs, the value in use is lit in its own colour. Picking a value goes back to manual for both.
 */
@Composable
internal fun GainSettings(g: SdrGain, onChange: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GainLine(S(R.string.lna_gain_db), g, g.lna, g.lnaSteps, onChange) { g.lna = it }
        GainLine(S(R.string.vga_gain_db), g, g.vga, g.vgaSteps, onChange) { g.vga = it }
        g.amp?.let { on ->
            Text(S(R.string.preamplifier), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
            AntFilterChip(S(R.string.amp_14_db), on, { g.amp = !on; onChange() })
        }
    }
}

@Composable
private fun GainLine(label: String, g: SdrGain, value: Int, steps: List<Int>, onChange: () -> Unit, onPick: (Int) -> Unit) {
    Text(label, style = rf(13, 18, 600), color = cs.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AntFilterChip(S(R.string.auto_gain), g.auto, { if (!g.auto) { g.setAuto(); onChange() } })
        steps.forEach { v ->
            if (g.auto) {
                // The value automatic mode is using right now, in the tertiary colour.
                AntFilterChip("$v", value == v, { g.auto = false; onPick(v); onChange() }, color = cs.tertiaryContainer, onColor = cs.onTertiaryContainer, leadingDot = if (value == v) cs.tertiary else null)
            } else {
                AntFilterChip("$v", value == v, { onPick(v); onChange() })
            }
        }
    }
}
