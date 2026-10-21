package com.allnetworktools.ui.pages.sdr

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
 * The HackRF's two gain stages, each on its own line so the values never mix, then the +14 dB amplifier when the tool
 * offers it. Calls [onChange] after every tap so the controller can push the new gains to the radio.
 */
@Composable
internal fun GainSettings(
    lna: Int, lnaSteps: List<Int>, onLna: (Int) -> Unit,
    vga: Int, vgaSteps: List<Int>, onVga: (Int) -> Unit,
    amp: Boolean? = null, onAmp: () -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GainLine(S(R.string.lna_gain_db), lna, lnaSteps, onLna)
        GainLine(S(R.string.vga_gain_db), vga, vgaSteps, onVga)
        if (amp != null) {
            Text(S(R.string.preamplifier), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
            AntFilterChip(S(R.string.amp_14_db), amp, onAmp)
        }
    }
}

@Composable
private fun GainLine(label: String, value: Int, steps: List<Int>, onPick: (Int) -> Unit) {
    Text(label, style = rf(13, 18, 600), color = cs.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.forEach { g -> AntFilterChip("$g", value == g, { onPick(g) }) }
    }
}
