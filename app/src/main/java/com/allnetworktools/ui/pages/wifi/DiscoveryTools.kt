package com.allnetworktools.ui.pages.wifi

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.allnetworktools.data.net.Whois
import com.allnetworktools.data.net.WhoisResult
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.InfoList
import com.allnetworktools.ui.components.InfoRow
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.pages.TopBarAction
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.HostInputField
import com.allnetworktools.ui.tools.IndeterminateBar
import com.allnetworktools.ui.tools.Phase
import com.allnetworktools.ui.tools.StartButton
import com.allnetworktools.ui.tools.ToolEmpty
import com.allnetworktools.ui.tools.ToolError
import com.allnetworktools.ui.tools.mono
import com.allnetworktools.util.plural
import java.io.IOException
import java.net.UnknownHostException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.allnetworktools.R
import com.allnetworktools.util.S
import com.allnetworktools.util.P

// ---- Whois ----------------------------------------------------------------------------------------

class WhoisController(private val scope: CoroutineScope) {
    var query by mutableStateOf("")
    var phase by mutableStateOf(Phase.Idle)
    var result by mutableStateOf<WhoisResult?>(null)
    var error by mutableStateOf<String?>(null)
    var showRaw by mutableStateOf(false)
    val recent = Recents("google.com", "wikipedia.org", "1.1.1.1")
    private var job: Job? = null

    fun start(q: String = query) {
        query = q.trim()
        if (query.isEmpty()) return
        job?.cancel()
        phase = Phase.Running
        result = null; error = null; showRaw = false
        job = scope.launch {
            try {
                val r = Whois.lookup(query)
                recent.push(query)
                result = r
                phase = if (r.text.isBlank() || r.text.contains("No match", true) || r.text.contains("NOT FOUND", true)) Phase.Empty else Phase.Results
            } catch (e: UnknownHostException) {
                error = S(R.string.whois_server_unreachable_check_the_internet); phase = Phase.Error
            } catch (e: IOException) {
                error = e.message ?: S(R.string.the_whois_server_did_not_answer); phase = Phase.Error
            }
        }
    }
}

@Composable
fun WhoisTool(c: WhoisController) {
    val actions = LocalActions.current
    val haptics = AntTheme.haptics
    val acc = AntTheme.accent
    LaunchedEffect(c.phase) { if (c.phase == Phase.Results) haptics.confirm() }
    val r = c.result
    if (r != null && c.phase == Phase.Results) TopBarAction(Sym.IosShare) { actions.share("Whois ${r.query}", r.hops.joinToString("\n\n") { "# ${it.server}\n${it.text}" }) }
    val start = { haptics.confirm(); c.start() }
    PageColumn {
        if (c.phase == Phase.Error) ToolError(Sym.CloudOff, S(R.string.query_failed), c.error ?: "", S(R.string.retry)) { c.start() }
        HostInputField(c.query, { c.query = it }, S(R.string.domain_or_ip_address), Sym.TravelExplore, c.recent.items, onDone = start)
        if (c.phase != Phase.Running) StartButton(S(R.string.look_up), Sym.Search, enabled = c.query.isNotBlank(), onClick = start)
        if (c.phase == Phase.Running) {
            SectionCard { IndeterminateBar(); Text(S(R.string.querying_whois_iana_org_then_the), Modifier.padding(top = 12.dp), style = rf(14, 20), color = cs.onSurfaceVariant) }
        }
        if (c.phase == Phase.Empty) {
            ToolEmpty(Sym.SearchOff, S(R.string.no_record), S(R.string.the_registry_does_not_know_the, c.query), null)
        }
        if (r != null && c.phase == Phase.Results) {
            HeroCard {
                Text(r.query, style = gs(26, 32, 500), maxLines = 2)
                Text(
                    if (r.isIp) listOfNotNull(r.netName, r.network).joinToString(" · ").ifEmpty { S(R.string.ip_address) }
                    else r.registrar ?: S(R.string.registrar_not_disclosed),
                    Modifier.padding(top = 2.dp), style = rf(14, 20),
                )
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HeroChip(r.hops.last().server, acc.accent)
                    r.country?.let { HeroChip(it.uppercase(), cs.outline) }
                }
            }
            InfoList(if (r.isIp) S(R.string.network) else S(R.string.domain)) {
                val rows = if (r.isIp) listOf(
                    S(R.string.range) to r.network, S(R.string.network_name) to r.netName, S(R.string.organisation) to (r.registrant ?: r.description),
                    S(R.string.country) to r.country, S(R.string.origin_as) to r.asn, S(R.string.updated) to r.updated?.let(::shortDate),
                ) else listOf(
                    "Registrar" to r.registrar, S(R.string.registrant) to r.registrant, S(R.string.created) to r.created?.let(::shortDate),
                    S(R.string.expires) to r.expires?.let(::shortDate), S(R.string.updated_2) to r.updated?.let(::shortDate),
                    S(R.string.dns_servers) to r.nameServers.joinToString("\n").ifEmpty { null }, S(R.string.status) to r.status.joinToString("\n").ifEmpty { null },
                    "DNSSEC" to r.dnssec,
                )
                rows.filter { it.second != null }.forEach { (k, v) -> InfoRow(k, v!!) { actions.copy(k, v) } }
                if (rows.all { it.second == null }) Text(S(R.string.answer_without_standard_fields_see_the), Modifier.padding(vertical = 12.dp), style = rf(14, 20), color = cs.onSurfaceVariant)
            }
            SectionCard(padding = PaddingValues(16.dp)) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { c.showRaw = !c.showRaw }, verticalAlignment = Alignment.CenterVertically) {
                    Text(S(R.string.raw_answer, r.hops.size, P(R.plurals.p_servers, r.hops.size)), Modifier.weight(1f), style = rf(15, 20, 600))
                    Symbol(if (c.showRaw) Sym.UnfoldLess else Sym.ChevronRight, size = 22.dp, tint = cs.onSurfaceVariant)
                }
                AnimatedVisibility(c.showRaw) {
                    Column(Modifier.padding(top = 10.dp)) {
                        r.hops.forEach { h ->
                            Text("# ${h.server}", Modifier.padding(top = 8.dp), style = mono(11, 16, 700), color = acc.accent)
                            Text(
                                h.text.lines().filter { it.isNotBlank() && !it.startsWith("%") && !it.startsWith(">>>") && !it.startsWith("#") }.take(120).joinToString("\n"),
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHigh).padding(10.dp),
                                style = mono(10.5f, 15),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "2027-08-13T04:00:00Z" → "13/08/2027"; other formats are returned unchanged. */
fun shortDate(s: String): String {
    val m = Regex("""^(\d{4})-(\d{2})-(\d{2})""").find(s) ?: Regex("""^(\d{4})(\d{2})(\d{2})$""").find(s) ?: return s
    val (y, mo, d) = m.destructured
    return "$d/$mo/$y"
}
