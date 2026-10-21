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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.allnetworktools.data.CellMeasure
import com.allnetworktools.data.CellState
import com.allnetworktools.data.RadioTech
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.BlinkDot
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Sparkline
import com.allnetworktools.ui.components.TileGrid
import com.allnetworktools.ui.components.rise
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.pages.TopBarAction
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.ToolEmpty
import com.allnetworktools.util.fmt
import kotlinx.coroutines.delay
import com.allnetworktools.R
import com.allnetworktools.util.S

/** Finds the cell the page is about: "serving" follows handovers, a key pins one physical cell. */
fun findCell(state: CellState?, arg: String?): Pair<CellMeasure, Boolean>? {
    val s = state ?: return null
    val serving = s.serving
    if (arg == null || arg == "serving") return serving?.let { it to true }
    if (serving?.key == arg) return serving to true
    return s.neighbors.firstOrNull { it.key == arg }?.let { it to false }
}

private fun bandDuplex(m: CellMeasure): String? {
    val b = m.band ?: return null
    val n = b.drop(1).toIntOrNull() ?: return b
    val tdd = if (m.tech == RadioTech.NR) n in listOf(34, 38, 39, 40, 41, 46, 47, 48, 50, 51, 53, 77, 78, 79, 90) || n >= 257
    else n in 33..53
    return "$b · ${if (tdd) "TDD" else "FDD"}"
}

@Composable
fun CellDetailTool(state: CellState?, arg: String?, onNeighbors: () -> Unit) {
    val actions = LocalActions.current
    val acc = AntTheme.accent
    val found = findCell(state, arg)
    val history = remember(arg) { mutableStateListOf<Float>() }
    var lastSeen by remember(arg) { mutableLongStateOf(System.currentTimeMillis()) }
    var lost by remember(arg) { mutableStateOf(false) }
    LaunchedEffect(found?.first) {
        val m = found?.first
        if (m != null) {
            lastSeen = System.currentTimeMillis(); lost = false
            m.level?.let { history.add(it.toFloat()); while (history.size > 40) history.removeAt(0) }
        }
    }
    LaunchedEffect(found == null) {
        while (found == null) {
            if (System.currentTimeMillis() - lastSeen > 8_000) lost = true
            delay(1000)
        }
    }
    val m = found?.first
    val serving = found?.second == true
    if (m != null) {
        TopBarAction(Sym.ContentCopy) { actions.copy(S(R.string.cell_2), report(m, state)) }
    }
    PageColumn {
        if (m == null) {
            if (lost || history.isEmpty()) {
                ToolEmpty(
                    Sym.CellNoData, S(R.string.cell_lost),
                    S(R.string.this_cell_is_no_longer_detected),
                    S(R.string.see_the_neighbour_cells), onNeighbors,
                )
            }
            return@PageColumn
        }
        val nr = m.tech == RadioTech.NR
        HeroCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(
                    Modifier.clip(RoundedCornerShape(22.dp, 22.dp, 22.dp, 8.dp)).background(if (nr) acc.accent else cs.surface).padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        when (m.tech) { RadioTech.NR -> "5G"; RadioTech.LTE -> "4G"; RadioTech.WCDMA -> "3G"; RadioTech.GSM -> "2G" },
                        style = gs(26, 30, 600), color = if (nr) acc.onAccent else cs.onSurface,
                    )
                    Text(if (nr) "NR" else m.tech.short, style = rf(11, 14, 600), color = if (nr) acc.onAccent else cs.onSurfaceVariant)
                }
                Column(Modifier.weight(1f)) {
                    Text(m.title(), style = gs(22, 28, 500), maxLines = 1)
                    m.arfcnText()?.let { Text(it, style = rf(13, 18, tnum = true)) }
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (serving) BlinkDot(AntTheme.net.good, 8.dp) else Box(Modifier.size(8.dp).clip(CircleShape).background(cs.outline))
                        Text(if (serving) S(R.string.serving_cell) else S(R.string.neighbour_cell), style = rf(12, 16, 600))
                    }
                }
            }
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(m.level?.let(::fmt) ?: "—", style = gs(56, 60, 500, -1.5f, tnum = true))
                Text("dBm ${if (m.rsrp != null) if (nr) "SS-RSRP" else "RSRP" else "RSSI"}", Modifier.padding(bottom = 8.dp), style = rf(16, 22, 500))
            }
            Sparkline(history.toList(), -120f, -70f, acc.accent, Modifier.padding(top = 8.dp).fillMaxWidth().height(52.dp), areaAlpha = 0f)
        }
        val net = AntTheme.net
        data class Tile(val k: String, val v: String, val q: String, val c: androidx.compose.ui.graphics.Color)
        val muted = cs.onSurfaceVariant
        fun q(v: Int?, spec: QualitySpec): Pair<String, androidx.compose.ui.graphics.Color> = when {
            v == null -> S(R.string.not_measured) to muted
            v >= spec.good -> S(R.string.good) to net.good
            v >= spec.fair -> S(R.string.fair) to net.fair
            else -> S(R.string.weak) to net.poor
        }
        val tiles = buildList {
            q(m.rsrq, RsrqSpec).let { (t, c) -> add(Tile(if (nr) "SS-RSRQ" else "RSRQ", m.rsrq?.let { "${fmt(it)} dB" } ?: "—", t, c)) }
            q(m.sinr, SinrSpec).let { (t, c) -> add(Tile(if (nr) "SS-SINR" else "RSSNR", m.sinr?.let { "${fmt(it)} dB" } ?: "—", t, c)) }
            q(m.rssi, RssiSpec).let { (t, c) -> add(Tile(if (m.rssiEstimated) S(R.string.rssi_estimated) else "RSSI", m.rssi?.let { "${if (m.rssiEstimated) "≈ " else ""}${fmt(it)} dBm" } ?: "—", t, c)) }
            val taNote = when {
                m.timingAdvance == null -> S(R.string.not_measured)
                nr -> "≈ ${fmt(m.timingAdvance * 150)} m"
                else -> "≈ ${fmt(m.timingAdvance * 78)} m"
            }
            add(Tile("Timing advance", m.timingAdvance?.let { if (nr) "$it µs" else "$it" } ?: "—", taNote, muted))
            add(Tile("CQI", m.cqi?.toString() ?: "—", m.cqi?.let { if (it >= 10) "64QAM" else if (it >= 7) "16QAM" else "QPSK" } ?: S(R.string.not_measured), muted))
            q(m.rsrp, RsrpSpec).let { (t, c) -> add(Tile(S(R.string.quality), t, S(R.string.from, if (m.rsrp != null) "RSRP" else S(R.string.the_level)), c)) }
        }
        TileGrid(tiles) { t, mod ->
            Column(mod.clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainerLow).padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(t.k, style = rf(12, 16), color = cs.onSurfaceVariant)
                Text(t.v, style = gs(20, 26, 500, tnum = true), maxLines = 1)
                Text(t.q, style = rf(11, 14, 700), color = t.c)
            }
        }
        val op = state?.operator.takeIf { serving }
        val ids = if (nr) listOf(
            "NCI" to m.cellId?.let(::fmt), "gNB ID" to m.nodeId?.let(::fmt), S(R.string.sector) to m.sector?.toString(),
            "PCI" to m.pci?.toString(), "TAC" to m.tac?.let(::fmt), "MCC / MNC" to mccMnc(m, op),
        ) else listOf(
            "CI" to m.cellId?.let(::fmt), "${if (m.tech == RadioTech.LTE) "eNB" else "RNC"} ID" to m.nodeId?.let(::fmt),
            if (m.tech == RadioTech.LTE) "PCI" to m.pci?.toString() else "PSC" to m.pci?.toString(),
            (if (m.tech == RadioTech.LTE) "TAC" else "LAC") to m.tac?.let(::fmt), "MCC / MNC" to mccMnc(m, op),
        )
        val radio = listOf(
            S(R.string.band) to bandDuplex(m),
            (m.arfcnLabel ?: "ARFCN") to m.arfcn?.let(::fmt),
            S(R.string.dl_frequency) to m.downlinkMhz?.let { "${fmt(it, 2)} MHz" },
            S(R.string.width) to m.bandwidthKhz?.let { "${fmt(it / 1000)} MHz" },
        )
        Group(S(R.string.identifiers), ids, 0)
        Group("Radio", radio, 1)
    }
}

private fun mccMnc(m: CellMeasure, operator: String?): String? =
    if (m.mcc != null && m.mnc != null) listOfNotNull("${m.mcc} / ${m.mnc}", operator).joinToString(" · ") else null

@Composable
private fun Group(title: String, rows: List<Pair<String, String?>>, index: Int) {
    SectionCard(Modifier.rise(index), padding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
        Text(title, Modifier.padding(bottom = 4.dp), style = rf(14, 20, 600), color = AntTheme.accent.accent)
        rows.forEach { (k, v) ->
            Hairline()
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(k, Modifier.weight(1f), style = rf(14, 20), color = cs.onSurfaceVariant)
                Text(
                    v ?: S(R.string.not_provided_by_the_modem),
                    style = if (v == null) rf(12, 16).copy(fontStyle = FontStyle.Italic) else rf(14, 20, 500, tnum = true),
                    color = if (v == null) cs.onSurfaceVariant else cs.onSurface, textAlign = TextAlign.End,
                )
            }
        }
    }
}

private fun report(m: CellMeasure, s: CellState?): String = buildString {
    appendLine("${m.tech.short} ${m.title()} ${m.arfcnText().orEmpty()}")
    s?.operator?.let { appendLine(S(R.string.carrier_2, it)) }
    listOf("Cell ID" to m.cellId, "Node ID" to m.nodeId, "TAC" to m.tac, "MCC" to m.mcc, "MNC" to m.mnc, "RSRP" to m.rsrp, "RSRQ" to m.rsrq, "SINR" to m.sinr, "RSSI" to m.rssi, "TA" to m.timingAdvance)
        .filter { it.second != null }.forEach { (k, v) -> appendLine("$k : $v") }
}
