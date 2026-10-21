package com.allnetworktools.ui.pages.cell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.Page
import com.allnetworktools.data.CellMeasure
import com.allnetworktools.data.CellRepository
import com.allnetworktools.data.PermGroup
import com.allnetworktools.data.RadioTech
import com.allnetworktools.model.Tool
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.CardHeader
import com.allnetworktools.ui.components.ChartBand
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.InfoList
import com.allnetworktools.ui.components.InfoRow
import com.allnetworktools.ui.components.LiveChart
import com.allnetworktools.ui.components.QualityRing
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.TextAction
import com.allnetworktools.ui.components.TileGrid
import com.allnetworktools.ui.components.fadeUp
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.util.fmt
import com.allnetworktools.util.of
import com.allnetworktools.util.quality
import kotlin.math.ceil
import kotlin.math.floor
import com.allnetworktools.R
import com.allnetworktools.util.S

data class QualitySpec(val key: String, val unit: String, val min: Float, val max: Float, val good: Float, val fair: Float)

val RsrpSpec = QualitySpec("RSRP", "dBm", -140f, -44f, -90f, -105f)
val RsrqSpec = QualitySpec("RSRQ", "dB", -20f, -3f, -10f, -15f)
val SinrSpec = QualitySpec("SINR", "dB", -5f, 30f, 13f, 0f)
val RssiSpec = QualitySpec("RSSI", "dBm", -110f, -50f, -70f, -85f)

@Composable
fun qualityColor(v: Int?, spec: QualitySpec): Color =
    if (v == null) cs.outline else AntTheme.net.of(quality(v.toFloat(), spec.good, spec.fair))

fun groupDigits(v: Long): String = fmt(v)

@Composable
fun CellDashboard(vm: MainViewModel) {
    val ui by vm.cell.collectAsStateWithLifecycle()
    val sims by vm.sims.collectAsStateWithLifecycle()
    val perms by vm.permissions.collectAsStateWithLifecycle()
    val actions = LocalActions.current
    val roles = AntTheme.net.cell
    val settings = AntTheme.settings
    val s = ui.state
    val serving = s?.serving
    PageColumn {
        Column(Modifier.fadeUp().fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(roles.container).padding(20.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(s?.operator ?: S(R.string.carrier), style = gs(28, 34, 500), color = roles.onContainer, maxLines = 1)
                        s?.simSlot?.let {
                            Box(Modifier.height(24.dp).clip(RoundedCornerShape(8.dp)).background(cs.surface).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                                Text("SIM $it", style = rf(12, 16, 600), color = cs.onSurface)
                            }
                        }
                    }
                    Text(
                        "${if (s?.voiceAndData != false) S(R.string.data_calls) else S(R.string.data)} · ${if (s?.roaming == true) S(R.string.roaming) else S(R.string.not_roaming)}",
                        Modifier.padding(top = 2.dp).graphicsLayer { alpha = 0.85f }, style = rf(13, 18), color = roles.onContainer,
                    )
                }
                Symbol(CellRepository.barsIcon(s?.signalLevel ?: 0), size = 28.dp, filled = true, tint = roles.accent)
            }
            Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(
                    Modifier.clip(RoundedCornerShape(24.dp, 24.dp, 24.dp, 8.dp)).background(roles.accent).padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(s?.techBig ?: "—", style = gs(44, 48, 600, -1f), color = roles.onAccent)
                    Text(s?.techLong ?: "", style = rf(12, 16, 600, 0.5f), color = roles.onAccent)
                }
                Column {
                    val band = serving?.band
                    val freq = serving?.downlinkMhz?.let { " · ${fmt(it)} MHz" } ?: ""
                    DetailLine(S(R.string.band), band?.let { it + freq } ?: "—")
                    DetailLine(serving?.arfcnLabel ?: "ARFCN", serving?.arfcn?.let { fmt(it) } ?: "—")
                    val width = serving?.bandwidthKhz ?: s?.servingBandwidthsKhz?.firstOrNull()
                    DetailLine(S(R.string.width), width?.let { "${fmt(it / 1000)} MHz" } ?: "—")
                }
            }
        }
        val rings = listOf(RsrpSpec to serving?.rsrp, RsrqSpec to serving?.rsrq, SinrSpec to serving?.sinr, RssiSpec to serving?.rssi)
        TileGrid(rings) { (spec, v), mod -> QualityTile(spec, v, mod, estimated = spec === RssiSpec && serving?.rssiEstimated == true && v != null) }
        SectionCard {
            val windowMin = (300 * settings.refreshSeconds / 60).let { if (it >= 1) "${fmt(it)} min" else "${fmt(it * 60)} s" }
            CardHeader(S(R.string.rsrp_history)) { Text(windowMin, style = rf(12, 16), color = cs.onSurfaceVariant) }
            val h = ui.history
            val lo = floor(minOf(-120f, (h.minOrNull() ?: -120f) - 5f) / 10f) * 10f
            val hi = ceil(maxOf(-80f, (h.maxOrNull() ?: -80f) + 5f) / 10f) * 10f
            LiveChart(
                h, lo, hi, 10f, roles.accent, Modifier.padding(top = 10.dp), capacity = 300, area = false,
                bands = listOf(ChartBand(hi, -90f, AntTheme.net.good), ChartBand(-90f, -105f, AntTheme.net.fair), ChartBand(-105f, lo, AntTheme.net.poor)),
                morphMs = settings.refreshMillis.toInt(),
            )
        }
        InfoList(
            S(R.string.cell_identity), roles.accent,
            trailing = { TextAction(S(R.string.detail), { vm.navigate { it.copy(page = Page.ToolPage(Tool.CellDetail, "serving")) } }, color = roles.accent) },
        ) {
            if (serving?.cellId == null && !perms.location) {
                Hairline()
                TextAction(S(R.string.allow_location_to_read_the_identifiers), { actions.request(PermGroup.Location) }, color = roles.accent, trailingIcon = null)
            }
            CellIdentityRows(serving)
            InfoRow(S(R.string.roaming_2), if (s?.roaming == true) S(R.string.yes) else S(R.string.no))
        }
        if (sims.isNotEmpty()) {
            SectionCard(padding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 8.dp)) {
                Text(if (sims.size > 1) S(R.string.dual_sim) else "SIM", Modifier.padding(top = 6.dp, bottom = 4.dp), style = rf(14, 20, 600), color = roles.accent)
                sims.forEach { sim ->
                    Hairline()
                    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(if (sim.isDefaultData) roles.accent else cs.surfaceContainerHighest),
                            contentAlignment = Alignment.Center,
                        ) { Text(sim.slot.toString(), style = rf(14, 20, 700), color = if (sim.isDefaultData) roles.onAccent else cs.onSurface) }
                        Column(Modifier.weight(1f)) {
                            Text(sim.operator, style = rf(15, 20, 600), maxLines = 1)
                            val role = (sim.roles.ifEmpty { listOf(S(R.string.idle)) } + listOfNotNull(if (sim.embedded) "eSIM" else null)).joinToString(" · ")
                            Text(role, style = rf(12, 16), color = cs.onSurfaceVariant)
                        }
                        Text(sim.tech, style = rf(13, 18, 600))
                        Symbol(CellRepository.barsIcon(sim.bars), size = 22.dp, filled = true, tint = roles.accent)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailLine(k: String, v: String) {
    Row {
        Text(k, style = rf(13, 20, 600), color = AntTheme.net.cell.onContainer)
        Text(" $v", style = rf(13, 20, tnum = true), color = AntTheme.net.cell.onContainer)
    }
}

@Composable
fun CellIdentityRows(c: CellMeasure?) {
    val nr = c?.tech == RadioTech.NR
    InfoRow(if (nr) "Cell ID (NCI)" else "Cell ID (ECI)", c?.cellId?.let(::groupDigits) ?: "—")
    InfoRow(if (nr) "gNB ID" else "eNB ID", c?.nodeId?.let(::groupDigits) ?: "—")
    InfoRow("PCI", c?.pci?.toString() ?: "—")
    InfoRow("TAC", c?.tac?.let { fmt(it) } ?: "—")
    InfoRow("MCC / MNC", if (c?.mcc != null) "${c.mcc} / ${c.mnc}" else "—")
}

@Composable
private fun QualityTile(spec: QualitySpec, v: Int?, modifier: Modifier, estimated: Boolean = false) {
    val color = qualityColor(v, spec)
    val frac = if (v == null) 0.05f else (v - spec.min) / (spec.max - spec.min)
    Row(
        modifier.clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainerLow).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        QualityRing(frac, color)
        Column {
            Text(if (estimated) S(R.string.estimated, spec.key) else spec.key, style = rf(12, 16, 600), color = cs.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(v?.let { (if (estimated) "≈ " else "") + fmt(it) } ?: "—", style = gs(24, 28, 500, tnum = true))
                Text(spec.unit, Modifier.padding(bottom = 3.dp), style = rf(11, 14), color = cs.onSurfaceVariant)
            }
            Text(v?.let { quality(it.toFloat(), spec.good, spec.fair).label } ?: S(R.string.unavailable), style = rf(12, 16, 700), color = color)
        }
    }
}
