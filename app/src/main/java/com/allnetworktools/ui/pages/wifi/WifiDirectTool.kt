package com.allnetworktools.ui.pages.wifi

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.data.P2pPeer
import com.allnetworktools.data.P2pStatus
import com.allnetworktools.data.WifiDirect
import com.allnetworktools.data.WifiDirectRepository
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.TechChip
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.ToolError
import com.allnetworktools.util.plural
import kotlinx.coroutines.flow.catch
import com.allnetworktools.R
import com.allnetworktools.util.S
import com.allnetworktools.util.P

/** Peers list while the screen is open; null until the first answer. Discovery stops when the screen leaves. */
@Composable
fun WifiDirectTool(repo: WifiDirectRepository, permitted: Boolean, permissionText: String, onFix: () -> Unit) {
    PageColumn {
        if (!repo.supported) {
            ToolError(Sym.WifiOff, S(R.string.wi_fi_direct_unavailable), S(R.string.this_phone_does_not_support_wi), "OK") {}
            return@PageColumn
        }
        if (!permitted) {
            ToolError(Sym.Lock, S(R.string.permission_needed), permissionText, S(R.string.allow), onFix)
            return@PageColumn
        }
        val flow = remember { repo.peers().catch { emit(emptyList()) } }
        val peers: List<P2pPeer>? by flow.collectAsStateWithLifecycle(initialValue = null)
        val list = peers.orEmpty()
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(list.size.toString(), style = gs(56, 60, 500, -1.5f, tnum = true))
                Text(P(R.plurals.p_devices, list.size), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text(
                S(R.string.printers_tvs_projectors_and_phones_that),
                style = rf(14, 20),
            )
            Row(Modifier.padding(top = 12.dp)) { HeroChip(S(R.string.wi_fi_direct_search_active), AntTheme.net.good, blink = true) }
        }
        if (peers != null && list.isEmpty()) {
            SectionCard {
                Text(S(R.string.no_device_yet), style = rf(16, 22, 600))
                Text(
                    S(R.string.devices_only_appear_if_their_wi) +
                        S(R.string.or_wi_fi_direct_settings_of),
                    Modifier.padding(top = 4.dp), style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
        if (list.isNotEmpty()) {
            SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
                Text(S(R.string.nearby), Modifier.padding(bottom = 4.dp), style = rf(14, 20, 600), color = AntTheme.accent.accent)
                list.forEach { p ->
                    Hairline()
                    PeerRow(p)
                }
            }
        }
        SectionCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
                Text(
                    S(R.string.while_searching_your_phone_is_also) +
                        S(R.string.no_connection_is_made_mac_addresses),
                    style = rf(13, 18), color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PeerRow(p: P2pPeer) {
    val cat = WifiDirect.category(p.deviceType)
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(AntTheme.accent.container), contentAlignment = Alignment.Center) {
            Symbol(cat?.icon ?: Sym.WifiTethering, size = 22.dp, filled = true, tint = AntTheme.accent.onContainer)
        }
        Column(Modifier.weight(1f)) {
            Text(p.name, style = rf(15, 20, 600), maxLines = 1)
            Text(
                listOfNotNull(cat?.label, p.address, if (p.groupOwner) S(R.string.group_owner) else null).joinToString(" · "),
                style = rf(12, 16), color = cs.onSurfaceVariant, maxLines = 2,
            )
        }
        val n = AntTheme.net
        val (bg, fg) = when (p.status) {
            P2pStatus.Connected -> n.good to cs.surface
            P2pStatus.Available -> AntTheme.accent.container to AntTheme.accent.onContainer
            P2pStatus.Failed -> n.poor to cs.surface
            else -> cs.surfaceContainerHighest to cs.onSurfaceVariant
        }
        TechChip(p.status.label, bg, fg)
    }
}
