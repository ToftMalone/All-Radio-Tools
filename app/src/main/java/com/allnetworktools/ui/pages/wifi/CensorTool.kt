package com.allnetworktools.ui.pages.wifi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.allnetworktools.R
import com.allnetworktools.data.net.Censor
import com.allnetworktools.data.net.CensorLists
import com.allnetworktools.data.net.CensorLists.Category
import com.allnetworktools.data.net.CensorResult
import com.allnetworktools.data.net.Suite
import com.allnetworktools.data.net.Verdict
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.LeadingIcon
import com.allnetworktools.ui.components.SectionCard
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.pages.TopBarAction
import com.allnetworktools.ui.pages.sdr.ToolTab
import com.allnetworktools.ui.pages.sdr.ToolTabRow
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.HostInputField
import com.allnetworktools.ui.tools.StartButton
import com.allnetworktools.ui.tools.ToolEmpty
import com.allnetworktools.util.Export
import com.allnetworktools.util.S
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The censorship test (in the spirit of OONI Probe) over one network: the Wi-Fi tool and its mobile-data copy each have
 * their own controller, so each network keeps its own results. Results stay in memory for the session.
 */
class CensorController(
    private val scope: CoroutineScope,
    /** What Android tells of each SIM's calls, for Wi-Fi calling and VoLTE. */
    private val sims: () -> List<com.allnetworktools.data.net.SimVoice> = { emptyList() },
) {
    val suites = mutableStateMapOf(
        Suite.Web to true, Suite.Messaging to true, Suite.WifiCalling to true, Suite.Volte to true, Suite.Circumvention to true, Suite.Middlebox to true,
    )
    val categories = mutableStateMapOf<Category, Boolean>().apply { Category.entries.forEach { put(it, it != Category.Adult) } }
    val custom = mutableStateListOf<String>()
    var input by mutableStateOf("")
    var country by mutableStateOf(Locale.getDefault().country.ifEmpty { "FR" })
    var ooni by mutableStateOf<List<Pair<String, String>>>(emptyList())
        private set
    var ooniOn by mutableStateOf(false)
    var ooniLoading by mutableStateOf(false)
        private set
    var ooniFailed by mutableStateOf(false)
        private set

    val results = mutableStateListOf<CensorResult>()
    var running by mutableStateOf(false)
        private set
    var total by mutableIntStateOf(0)
        private set
    var where by mutableStateOf<Pair<String, String>?>(null)
        private set
    private var job: Job? = null

    fun addCustom() {
        val h = input.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').lowercase()
        if (h.isNotEmpty() && '.' in h && h !in custom) custom += h
        input = ""
    }

    fun loadOoni() {
        if (ooniLoading) return
        ooniLoading = true; ooniFailed = false
        scope.launch {
            val l = runCatching { Censor.ooniList(country.trim().take(2)) }.getOrNull()
            ooniLoading = false
            if (l.isNullOrEmpty()) { ooniFailed = true; return@launch }
            ooni = l; ooniOn = true
        }
    }

    fun start() {
        if (running) return
        results.clear()
        running = true
        where = null
        // Sites: one test per host, whatever list it came from.
        val sites = LinkedHashMap<String, String?>()
        if (suites[Suite.Web] == true) {
            CensorLists.sites.forEach { (c, l) -> if (categories[c] == true) l.forEach { sites.putIfAbsent(it, c.name) } }
            custom.forEach { sites.putIfAbsent(it, null) }
            if (ooniOn) ooni.forEach { (u, c) -> sites.putIfAbsent(u.removePrefix("https://").removePrefix("http://").substringBefore('/').lowercase(), c) }
        }
        val tasks = ArrayList<suspend () -> CensorResult>()
        sites.forEach { (h, c) -> tasks += { Censor.site("https://$h/", c) } }
        if (suites[Suite.Messaging] == true) CensorLists.messaging.forEach { a -> tasks += { Censor.app(Suite.Messaging, a) } }
        if (suites[Suite.WifiCalling] == true || suites[Suite.Volte] == true) {
            val l = runCatching(sims).getOrDefault(emptyList())
            for (suite in listOf(Suite.WifiCalling, Suite.Volte)) {
                if (suites[suite] != true) continue
                if (l.isEmpty()) tasks += { CensorResult(suite, S(R.string.cen_no_sim), "", null, Verdict.Inconclusive, emptyList()) }
                l.forEach { sim -> if (suite == Suite.WifiCalling) tasks += { Censor.wifiCalling(sim) } else tasks += { Censor.volte(sim) } }
            }
        }
        if (suites[Suite.Circumvention] == true) CensorLists.circumvention.forEach { a -> tasks += { Censor.app(Suite.Circumvention, a) } }
        if (suites[Suite.Middlebox] == true) tasks += { Censor.headerManipulation() }
        total = tasks.size
        job = scope.launch {
            launch { where = Censor.whereAmI() }
            val gate = Semaphore(PARALLEL)
            tasks.map { t -> async { gate.withPermit { val r = runCatching { t() }.getOrNull(); if (r != null) results += r } } }.awaitAll()
            running = false
        }
    }

    fun stop() {
        job?.cancel()
        running = false
    }

    fun csv(): String = buildString {
        append("suite,name,target,category,verdict,via_encrypted_dns,ms,steps\r\n")
        results.forEach { r ->
            val steps = r.steps.joinToString(" | ") { "${it.what}: ${if (it.ok) "ok" else "fail"} ${it.note}" }.replace("\"", "'")
            append(listOf(r.suite.name, r.name, r.target, r.category ?: "", r.verdict.name, r.viaEncryptedDns, r.ms, "\"$steps\"").joinToString(","))
            append("\r\n")
        }
    }

    companion object {
        private const val PARALLEL = 6
    }
}

private fun verdictText(v: Verdict) = S(
    when (v) {
        Verdict.Ok -> R.string.cen_v_ok; Verdict.Partial -> R.string.cen_v_partial; Verdict.DnsBlocked -> R.string.cen_v_dns
        Verdict.DnsTampered -> R.string.cen_v_dns_tamper; Verdict.TcpBlocked -> R.string.cen_v_tcp; Verdict.SniBlocked -> R.string.cen_v_sni
        Verdict.TlsBlocked -> R.string.cen_v_tls; Verdict.Intercepted -> R.string.cen_v_mitm; Verdict.ProxyDetected -> R.string.cen_v_proxy
        Verdict.Down -> R.string.cen_v_down; Verdict.Inconclusive -> R.string.cen_v_inconclusive
        Verdict.CarrierOff -> R.string.cen_v_carrier_off; Verdict.Fallback -> R.string.cen_v_fallback
    },
)

private fun suiteText(s: Suite) = S(
    when (s) {
        Suite.Web -> R.string.cen_suite_web; Suite.Messaging -> R.string.cen_suite_im; Suite.WifiCalling -> R.string.cen_suite_vowifi; Suite.Volte -> R.string.cen_suite_volte
        Suite.Circumvention -> R.string.cen_suite_circ; Suite.Middlebox -> R.string.cen_suite_mb
    },
)

private fun categoryText(c: Category) = S(
    when (c) {
        Category.News -> R.string.cen_cat_news; Category.StateMedia -> R.string.cen_cat_state; Category.Social -> R.string.cen_cat_social
        Category.Search -> R.string.cen_cat_search; Category.Rights -> R.string.cen_cat_rights; Category.Lgbt -> R.string.cen_cat_lgbt
        Category.Comms -> R.string.cen_cat_comms; Category.Ai -> R.string.cen_cat_ai; Category.FileSharing -> R.string.cen_cat_files
        Category.Gambling -> R.string.cen_cat_gambling; Category.Adult -> R.string.cen_cat_adult; Category.Crypto -> R.string.cen_cat_crypto
    },
)

/** The category as the user knows it: ours by name, OONI's by its code. */
private fun categoryLabel(c: String?): String? = c?.let { code -> Category.entries.firstOrNull { it.name == code }?.let(::categoryText) ?: code }

@Composable
private fun verdictColor(v: Verdict): Color = when {
    v == Verdict.Ok -> AntTheme.net.good
    v.blocked -> AntTheme.net.poor
    v.anomaly || v == Verdict.Inconclusive || v == Verdict.Down -> AntTheme.net.fair
    else -> cs.outline
}

@Composable
fun CensorTool(c: CensorController, networkLabel: String, networkIcon: String) {
    val actions = LocalActions.current
    if (c.results.isNotEmpty() && !c.running) TopBarAction(Sym.Download) {
        val snapshot = c.csv()
        actions.saveFile("censorship-${Export.stamp(System.currentTimeMillis())}.csv", "text/csv") { snapshot.toByteArray() }
    }
    val blocked = c.results.count { it.verdict.blocked }
    val ok = c.results.count { it.verdict == Verdict.Ok }
    val odd = c.results.size - ok - blocked
    PageColumn {
        HeroCard {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("$blocked", style = gs(56, 60, 500, -1.5f, tnum = true), color = if (blocked > 0) AntTheme.net.poor else cs.onSurface)
                Text(S(R.string.cen_blocked_of, c.results.size), Modifier.padding(bottom = 8.dp), style = rf(18, 24, 500))
            }
            Text(S(R.string.cen_summary, ok, blocked, odd), style = rf(14, 20))
            c.where?.let { (ip, cc) -> Text(S(R.string.cen_where, ip, cc), style = rf(12, 16), color = cs.onSurfaceVariant) }
            if (c.running) {
                LinearProgressIndicator(
                    progress = { if (c.total == 0) 0f else c.results.size.toFloat() / c.total },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(4.dp)),
                )
            }
            Row(Modifier.padding(top = 12.dp)) {
                HeroChip(
                    when { c.running -> S(R.string.cen_progress, c.results.size, c.total); else -> networkLabel },
                    if (c.running) AntTheme.net.fair else AntTheme.net.good, blink = c.running,
                )
            }
        }
        if (c.running) StartButton(S(R.string.stop), Sym.Stop) { c.stop() }
        else StartButton(S(R.string.cen_start), Sym.PlayArrow, enabled = c.suites.values.any { it }) { c.start() }
        val tab = ToolTabRow("CensorTool$networkIcon", map = false, mainLabel = S(R.string.cen_results))
        if (tab == ToolTab.Main) Results(c)
        if (tab == ToolTab.Settings) Settings(c)
    }
}

@Composable
private fun Results(c: CensorController) {
    if (c.results.isEmpty()) {
        ToolEmpty(Sym.Public, S(R.string.cen_empty_title), S(R.string.cen_empty_body), null)
        return
    }
    Suite.entries.forEach { suite ->
        val rows = c.results.filter { it.suite == suite }
            .sortedWith(compareBy<CensorResult>({ !it.verdict.blocked }, { !it.verdict.anomaly }, { it.verdict == Verdict.Ok }, { it.name }))
        if (rows.isEmpty()) return@forEach
        Text(suiteText(suite), Modifier.padding(start = 4.dp, top = 8.dp), style = rf(14, 20, 600), color = cs.onSurfaceVariant)
        SectionCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
            rows.forEachIndexed { i, r ->
                if (i > 0) Hairline()
                ResultRow(r)
            }
        }
    }
}

@Composable
private fun ResultRow(r: CensorResult) {
    var open by androidx.compose.runtime.remember { mutableStateOf(false) }
    val color = verdictColor(r.verdict)
    val icon = when (r.suite) { Suite.Web -> Sym.Public; Suite.Messaging -> Sym.Forum; Suite.WifiCalling -> Sym.Call; Suite.Volte -> Sym.CellTower; Suite.Circumvention -> Sym.Key; Suite.Middlebox -> Sym.Router }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open = !open }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LeadingIcon(icon, AntTheme.accent.container, AntTheme.accent.onContainer, 40.dp, RoundedCornerShape(12.dp), 22.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(r.name, Modifier.weight(1f, fill = false), style = rf(15, 20, 600), maxLines = 1)
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Text(verdictText(r.verdict), style = rf(13, 18, 600), color = color, maxLines = 1)
            }
            Text(
                listOfNotNull(categoryLabel(r.category), if (r.viaEncryptedDns) S(R.string.cen_via_doh) else null, "${r.ms} ms").joinToString(" · "),
                style = rf(12, 16, tnum = true), color = cs.onSurfaceVariant,
            )
            if (open) {
                Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    r.steps.forEach { s ->
                        Text("${if (s.ok) "✓" else "✗"} ${s.what} — ${s.note}", style = rf(12, 16, tnum = true), color = if (s.ok) cs.onSurfaceVariant else AntTheme.net.poor)
                    }
                }
            }
        }
    }
}

@Composable
private fun Settings(c: CensorController) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(S(R.string.cen_tests), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Suite.entries.forEach { s -> AntFilterChip(suiteText(s), c.suites[s] == true, { if (!c.running) c.suites[s] = c.suites[s] != true }) }
            }
            if (c.suites[Suite.Web] == true) {
                Text(S(R.string.cen_categories), Modifier.padding(top = 6.dp), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Category.entries.forEach { k -> AntFilterChip(categoryText(k), c.categories[k] == true, { if (!c.running) c.categories[k] = c.categories[k] != true }) }
                }
            }
        }
    }
    if (c.suites[Suite.Web] == true) {
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HostInputField(c.input, { c.input = it }, S(R.string.cen_custom), Sym.Public, onDone = { c.addCustom() })
                if (c.custom.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        c.custom.toList().forEach { h -> AntFilterChip(h, true, { if (!c.running) c.custom.remove(h) }, trailing = "✕") }
                    }
                }
            }
        }
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(S(R.string.cen_ooni), style = rf(13, 18, 600), color = cs.onSurfaceVariant)
                HostInputField(c.country, { c.country = it.uppercase().take(2) }, S(R.string.cen_country), Sym.Language, keyboardType = androidx.compose.ui.text.input.KeyboardType.Text, onDone = { c.loadOoni() })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AntFilterChip(S(R.string.cen_ooni_load, c.country), false, { c.loadOoni() })
                    if (c.ooni.isNotEmpty()) AntFilterChip(S(R.string.cen_ooni_loaded, c.ooni.size), c.ooniOn, { if (!c.running) c.ooniOn = !c.ooniOn })
                }
                if (c.ooniLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (c.ooniFailed) Text(S(R.string.cen_ooni_failed), style = rf(12, 16), color = cs.error)
            }
        }
    }
    SectionCard {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Symbol(Sym.Info, size = 22.dp, tint = AntTheme.accent.accent)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(S(R.string.cen_how), style = rf(13, 18), color = cs.onSurfaceVariant)
                Text(S(R.string.cen_how_vowifi), style = rf(13, 18), color = cs.onSurfaceVariant)
                Text(S(R.string.cen_how_volte), style = rf(13, 18), color = cs.onSurfaceVariant)
            }
        }
    }
}

/**
 * What Android tells of each active SIM's calls: its operator code, the operator's configuration for this phone
 * (VoLTE, Wi-Fi calling), the user's VoLTE switch when readable, and the network the voice service is on. Everything
 * needs the phone permission; what is refused stays unknown.
 */
fun simVoices(ctx: android.content.Context): List<com.allnetworktools.data.net.SimVoice> {
    val sm = ctx.getSystemService(android.telephony.SubscriptionManager::class.java)
    val base = ctx.getSystemService(android.telephony.TelephonyManager::class.java) ?: return emptyList()
    val cfgs = ctx.getSystemService(android.telephony.CarrierConfigManager::class.java)
    fun voice(subId: Int?, op: String, mcc: String, mnc: String): com.allnetworktools.data.net.SimVoice {
        val tm = if (subId != null) base.createForSubscriptionId(subId) else base
        val cfg = runCatching { if (subId != null) cfgs?.getConfigForSubId(subId) else cfgs?.config }.getOrNull()
        fun flag(key: String) = cfg?.takeIf { it.containsKey(key) }?.getBoolean(key)
        val switch = if (subId != null && android.os.Build.VERSION.SDK_INT >= 30) runCatching {
            ctx.getSystemService(android.telephony.ims.ImsManager::class.java)?.getImsMmTelManager(subId)?.isAdvancedCallingSettingEnabled
        }.getOrNull() else null
        return com.allnetworktools.data.net.SimVoice(
            op, mcc, mnc,
            volteAllowed = flag(android.telephony.CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL),
            wfcAllowed = flag(android.telephony.CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL),
            volteSwitch = switch,
            voiceRat = runCatching { tm.voiceNetworkType }.getOrNull(),
        )
    }
    val subs = runCatching { sm?.activeSubscriptionInfoList }.getOrNull().orEmpty()
    val l = subs.mapNotNull { s ->
        val mcc = s.mccString
        val mnc = s.mncString
        if (mcc.isNullOrEmpty() || mnc.isNullOrEmpty()) null
        else voice(s.subscriptionId, s.carrierName?.toString()?.takeIf { it.isNotBlank() } ?: "SIM ${s.simSlotIndex + 1}", mcc, mnc)
    }.distinctBy { it.mcc + it.mnc }
    if (l.isNotEmpty()) return l
    val op = runCatching { base.simOperator }.getOrNull()
    if (op == null || op.length < 5) return emptyList()
    return listOf(voice(null, runCatching { base.simOperatorName }.getOrNull()?.takeIf { it.isNotBlank() } ?: op, op.take(3), op.drop(3)))
}
