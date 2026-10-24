package com.allnetworktools.ui.pages.cell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.R
import com.allnetworktools.data.CellAnomaly
import com.allnetworktools.data.CellWatch
import com.allnetworktools.data.CellWatchHub
import com.allnetworktools.data.anomalyBody
import com.allnetworktools.data.anomalyTitle
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.pages.TopBarAction
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.HeroTile
import com.allnetworktools.ui.tools.ToolEmpty
import com.allnetworktools.util.S
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Watches the serving cell for the signs a fake base station (IMSI catcher) gives away, in a foreground service
 * so it keeps watching with the app closed. Each sign is a hint, never a proof.
 */
@Composable
fun FakeCellsTool(permitted: Boolean, onGrant: () -> Unit, onNotifications: () -> Unit) {
    val context = LocalContext.current
    val state by CellWatchHub.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        CellWatchHub.watching = true
        onDispose { CellWatchHub.watching = false }
    }
    if (state.anomalies.isNotEmpty()) TopBarAction(Sym.Delete) { CellWatchHub.clear() }
    val strong = state.anomalies.count { it.kind.severity >= 2 }

    fun toggle(on: Boolean) {
        when {
            !on -> CellWatchHub.stop(context)
            !permitted -> onGrant()
            else -> { onNotifications(); CellWatchHub.start(context) }
        }
    }

    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(S(R.string.cell_watch), style = rf(22, 28, 500))
                    Text(if (state.running) S(R.string.cell_watch_on) else S(R.string.cell_watch_off), style = rf(14, 20))
                }
                Switch(
                    checked = state.running, onCheckedChange = ::toggle,
                    thumbContent = if (state.running) ({ Symbol(Sym.Check, size = 16.dp, tint = cs.primary) }) else null,
                    colors = SwitchDefaults.colors(checkedTrackColor = cs.primary, checkedThumbColor = cs.onPrimary),
                )
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroTile(strong.toString(), S(R.string.alerts), Modifier.weight(1f))
                HeroTile((state.anomalies.size - strong).toString(), S(R.string.hints), Modifier.weight(1f))
                HeroTile(state.cellsSeen.toString(), S(R.string.cells), Modifier.weight(1f))
            }
            Row(Modifier.padding(top = 12.dp)) {
                when {
                    !state.running -> HeroChip(S(R.string.watch_stopped), AntTheme.net.poor)
                    strong > 0 -> HeroChip(S(R.string.suspicious_cell), cs.error, blink = true)
                    else -> HeroChip(S(R.string.nothing_suspicious), AntTheme.net.good, blink = true)
                }
            }
        }

        if (!state.running && !permitted) {
            ToolEmpty(Sym.CellTower, S(R.string.permissions_needed), S(R.string.cell_watch_permission_body), S(R.string.allow), onGrant)
        }

        state.serving?.let { c ->
            SectionCard {
                Text(S(R.string.serving_cell), style = rf(14, 20, 600), color = AntTheme.accent.accent)
                Text(CellWatch.label(c), Modifier.padding(top = 4.dp), style = rf(15, 20, 500))
                Text(
                    listOfNotNull(c.level?.let { "$it dBm" }, c.pci?.let { "PCI $it" }, S(R.string.neighbours_n, state.neighbours)).joinToString(" · "),
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }

        if (state.anomalies.isEmpty()) {
            if (state.running) ToolEmpty(Sym.Shield, S(R.string.nothing_suspicious), S(R.string.cell_watch_empty_body), null)
        } else {
            val clock = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
            SectionCard(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                state.anomalies.forEachIndexed { i, a ->
                    if (i > 0) Hairline()
                    AnomalyRow(a, clock.format(Date(a.timeMs)))
                }
            }
        }

        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(S(R.string.cell_watch_how_it_works), style = rf(13, 18), color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun severityColor(s: Int): Color = when {
    s >= 3 -> cs.error
    s == 2 -> AntTheme.net.fair
    else -> cs.onSurfaceVariant
}

@Composable
private fun AnomalyRow(a: CellAnomaly, time: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.padding(top = 6.dp).size(10.dp).clip(RoundedCornerShape(5.dp)).background(severityColor(a.kind.severity)))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(anomalyTitle(a.kind), Modifier.weight(1f), style = rf(15, 20, 600), color = if (a.kind.severity >= 3) cs.error else cs.onSurface)
                Text(time, style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant)
            }
            Text(listOf(a.cell, a.detail).distinct().joinToString(" · "), style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant)
            Text(anomalyBody(a.kind), Modifier.padding(top = 2.dp), style = rf(13, 18))
        }
    }
}
