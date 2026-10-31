package com.allnetworktools.ui.pages.nfc

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.Page
import com.allnetworktools.R
import com.allnetworktools.data.nfc.CardApp
import com.allnetworktools.data.nfc.ChipFamily
import com.allnetworktools.data.nfc.DumpNote
import com.allnetworktools.data.nfc.EmvCard
import com.allnetworktools.data.nfc.Ndef
import com.allnetworktools.data.nfc.NdefContent
import com.allnetworktools.data.nfc.NfcChips
import com.allnetworktools.data.nfc.TagScan
import com.allnetworktools.data.nfc.WifiAuth
import com.allnetworktools.data.nfc.WriteResult
import com.allnetworktools.model.Tool
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.AntFilterChip
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.InfoList
import com.allnetworktools.ui.components.InfoRow
import com.allnetworktools.ui.components.LevelBar
import com.allnetworktools.ui.components.PillButton
import com.allnetworktools.ui.components.PulseRing
import com.allnetworktools.ui.components.SectionTitle
import com.allnetworktools.ui.components.SegmentedRow
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.groupShape
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HeroCard
import com.allnetworktools.ui.tools.HeroChip
import com.allnetworktools.ui.tools.HeroTile
import com.allnetworktools.ui.tools.HostInputField
import com.allnetworktools.ui.tools.mono
import com.allnetworktools.util.P
import com.allnetworktools.util.S
import java.util.Locale

/** Keeps the controller on [mode] while the page is shown. */
@Composable
private fun Listen(c: NfcController, mode: NfcMode) {
    DisposableEffect(mode) {
        c.open(mode)
        onDispose { c.close(mode) }
    }
}

/** "Hold a tag to the back of the phone", with the NFC symbol pulsing. */
@Composable
private fun HoldTag(title: String, text: String, working: Boolean = false) {
    val acc = AntTheme.accent
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(acc.container).padding(vertical = 28.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
            PulseRing(acc.accent, 110.dp, periodMs = if (working) 700 else 2000)
            Box(Modifier.size(76.dp).clip(CircleShape).background(acc.accent), contentAlignment = Alignment.Center) {
                Symbol(Sym.Nfc, size = 40.dp, tint = acc.onAccent)
            }
        }
        Text(title, Modifier.padding(top = 14.dp), style = gs(20, 26, 500), color = acc.onContainer)
        Text(text, Modifier.padding(top = 4.dp), style = rf(14, 20), color = acc.onContainer.copy(alpha = 0.8f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun ResultBanner(r: WriteResult?, okText: String) {
    r ?: return
    val net = AntTheme.net
    val (icon, text, color) = when (r) {
        WriteResult.Ok -> Triple(Sym.CheckCircle, okText, net.good)
        WriteResult.TooBig -> Triple(Sym.Error, S(R.string.nfc_res_too_big), net.poor)
        WriteResult.ReadOnly -> Triple(Sym.Lock, S(R.string.nfc_res_read_only), net.poor)
        WriteResult.NotNdef -> Triple(Sym.Block, S(R.string.nfc_res_not_ndef), net.poor)
        WriteResult.Lost -> Triple(Sym.Warning, S(R.string.nfc_res_lost), net.fair)
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(color.copy(alpha = 0.14f)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Symbol(icon, size = 22.dp, filled = true, tint = color)
        Text(text, style = rf(14, 20, 600), color = cs.onSurface)
    }
}

private fun familyIcon(f: ChipFamily) = when (f) {
    ChipFamily.Ntag, ChipFamily.Ultralight -> Sym.Sell
    ChipFamily.Classic, ChipFamily.Plus -> Sym.Key
    ChipFamily.Desfire, ChipFamily.Iso14443_4 -> Sym.CreditCard
    ChipFamily.Felica -> Sym.Train
    ChipFamily.Iso15693 -> Sym.Badge
    ChipFamily.Unknown -> Sym.Nfc
}

private fun appLabel(a: CardApp) = when (a) {
    CardApp.Payment -> S(R.string.nfc_app_payment)
    CardApp.Transport -> S(R.string.nfc_app_transport)
    CardApp.Passport -> S(R.string.nfc_app_passport)
}

private fun bytes(n: Int) = S(R.string.nfc_bytes, n)

// ---- dashboard ------------------------------------------------------------------------------

@Composable
fun NfcDashboard(vm: MainViewModel) {
    val c = vm.tools.nfc
    val caps = remember { vm.nfcCaps() }
    val net = AntTheme.net
    fun yes(b: Boolean) = if (b) S(R.string.nfc_yes) else S(R.string.nfc_no)
    PageColumn {
        HeroCard {
            Text("NFC", style = gs(44, 52, 500, -1f))
            Text(S(R.string.nfc_dash_text), style = rf(14, 20))
            Row(Modifier.padding(top = 12.dp)) { HeroChip(S(R.string.nfc_on), net.good) }
        }
        InfoList(S(R.string.nfc_phone_caps)) {
            InfoRow(S(R.string.nfc_cap_reader), S(R.string.nfc_yes))
            InfoRow(S(R.string.nfc_cap_hce), yes(caps.hce))
            InfoRow("HCE-F (FeliCa)", yes(caps.hceF))
            InfoRow(S(R.string.nfc_cap_se), listOfNotNull("SIM".takeIf { caps.offHostUicc }, "eSE".takeIf { caps.offHostEse }).joinToString(" + ").ifEmpty { S(R.string.nfc_no) })
            InfoRow(S(R.string.nfc_cap_secure), if (!caps.secureNfcSupported) S(R.string.nfc_not_supported) else yes(caps.secureNfc == true))
            caps.observeMode?.let { InfoRow(S(R.string.nfc_cap_observe), yes(it)) }
        }
        val history = c.history
        SectionTitle(S(R.string.nfc_session_tags))
        if (history.isEmpty()) {
            Text(S(R.string.nfc_session_empty), style = rf(14, 20), color = cs.onSurfaceVariant)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                history.forEachIndexed { i, s -> TagLine(s, groupShape(i, history.size)) { vm.navigate { it.copy(page = Page.ToolPage(Tool.NfcRead)) } } }
            }
        }
    }
}

@Composable
private fun TagLine(s: TagScan, shape: androidx.compose.ui.graphics.Shape, onClick: () -> Unit) {
    val acc = AntTheme.accent
    Row(
        Modifier.fillMaxWidth().clip(shape).background(cs.surfaceContainerLow).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(acc.container), contentAlignment = Alignment.Center) {
            Symbol(familyIcon(s.chip.family), size = 22.dp, tint = acc.onContainer)
        }
        Column(Modifier.weight(1f)) {
            Text(s.chip.name, style = rf(15, 20, 600), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.uidHex.ifEmpty { "—" }, style = mono(12, 17), color = cs.onSurfaceVariant, maxLines = 1)
        }
        s.ndef?.contents?.firstOrNull()?.let { Symbol(contentIcon(it), size = 18.dp, tint = cs.onSurfaceVariant) }
    }
}

// ---- reader ---------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NfcReadTool(vm: MainViewModel) {
    val c = vm.tools.nfc
    Listen(c, NfcMode.Read)
    val actions = LocalActions.current
    PageColumn {
        val s = c.last
        if (s == null) {
            HoldTag(S(R.string.nfc_hold_title), S(R.string.nfc_hold_read), c.working)
            return@PageColumn
        }
        HeroCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(AntTheme.accent.accent), contentAlignment = Alignment.Center) {
                    Symbol(familyIcon(s.chip.family), size = 28.dp, tint = AntTheme.accent.onAccent)
                }
                Column(Modifier.weight(1f)) {
                    Text(s.chip.name, style = gs(24, 30, 500), maxLines = 2)
                    Text(s.manufacturer ?: S(R.string.nfc_maker_unknown), style = rf(14, 20))
                }
            }
            FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                s.app?.let { HeroChip(appLabel(it), AntTheme.net.good) }
                s.ndef?.let { HeroChip(if (it.writable) S(R.string.nfc_writable) else S(R.string.nfc_locked), if (it.writable) AntTheme.net.good else AntTheme.net.fair) }
                if (s.formatable) HeroChip(S(R.string.nfc_blank), AntTheme.net.good)
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.chip.totalBytes?.let { HeroTile(bytes(it), S(R.string.nfc_memory_total), Modifier.weight(1f)) }
                (s.ndef?.maxSize ?: s.chip.userBytes)?.let { HeroTile(bytes(it), S(R.string.nfc_memory_free), Modifier.weight(1f)) }
            }
        }
        if (s.app == CardApp.Payment) {
            PillButton(S(R.string.nfc_bank), { vm.navigate { it.copy(page = Page.ToolPage(Tool.NfcBank)) } }, icon = Sym.CreditCard, height = 48.dp)
        }
        s.ndef?.let { n ->
            InfoList(S(R.string.nfc_content), trailing = { Text("${n.used} / ${bytes(n.maxSize)}", style = rf(12, 16), color = cs.onSurfaceVariant) }) {
                if (n.contents.isEmpty()) InfoRow(n.type, S(R.string.nfc_empty))
                n.contents.forEach { ContentRow(it) }
            }
        }
        InfoList(S(R.string.nfc_identity)) {
            InfoRow("UID", s.uidHex.ifEmpty { "—" }) { actions.copy("UID", s.uidHex) }
            if (s.randomUid) InfoRow(S(R.string.nfc_uid_kind), S(R.string.nfc_uid_random))
            s.atqa?.let { InfoRow("ATQA", it) }
            s.sak?.let { InfoRow("SAK", it) }
            s.historical?.let { InfoRow(S(R.string.nfc_historical), it) }
            s.felicaSystem?.let { InfoRow(S(R.string.nfc_felica_system), it) }
            s.chip.units?.let { u -> InfoRow(S(R.string.nfc_layout), "$u × ${bytes(s.chip.unitBytes ?: 0)}") }
            s.ndef?.let { InfoRow(S(R.string.nfc_ndef_type), it.type) }
            InfoRow(S(R.string.nfc_techs), s.techs.joinToString(", "))
        }
        val others = c.history.drop(1)
        if (others.isNotEmpty()) {
            SectionTitle(S(R.string.nfc_session_tags))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                others.forEachIndexed { i, t -> TagLine(t, groupShape(i, others.size)) {} }
            }
        }
        Text(S(R.string.nfc_hold_next), style = rf(13, 18), color = cs.onSurfaceVariant)
    }
}

private fun contentIcon(c: NdefContent) = when (c) {
    is NdefContent.Url -> Sym.Link
    is NdefContent.Text -> Sym.TextFields
    is NdefContent.Wifi -> Sym.Wifi
    is NdefContent.Contact -> Sym.Contacts
    is NdefContent.Phone -> Sym.Call
    is NdefContent.Sms -> Sym.Sms
    is NdefContent.Email -> Sym.Mail
    is NdefContent.Geo -> Sym.LocationOn
    is NdefContent.App -> Sym.Apps
    is NdefContent.Mime, is NdefContent.Other -> Sym.DataObject
}

@Composable
private fun ContentRow(c: NdefContent) {
    val actions = LocalActions.current
    val (title, value) = when (c) {
        is NdefContent.Url -> S(R.string.nfc_t_url) to c.uri
        is NdefContent.Text -> S(R.string.nfc_t_text) to c.text
        is NdefContent.Wifi -> "Wi-Fi" to listOf(c.ssid, authLabel(c.auth), c.password.takeIf { it.isNotEmpty() }?.let { S(R.string.nfc_password_is, it) }).filterNotNull().joinToString("\n")
        is NdefContent.Contact -> S(R.string.nfc_t_contact) to listOf(c.name, c.org, c.phone, c.email).filter { it.isNotEmpty() }.joinToString("\n")
        is NdefContent.Phone -> S(R.string.nfc_t_phone) to c.number
        is NdefContent.Sms -> "SMS" to (c.number + if (c.body.isNotEmpty()) "\n" + c.body else "")
        is NdefContent.Email -> S(R.string.nfc_t_email) to listOf(c.to, c.subject, c.body).filter { it.isNotEmpty() }.joinToString("\n")
        is NdefContent.Geo -> S(R.string.nfc_t_geo) to "%.6f, %.6f".format(Locale.US, c.lat, c.lon)
        is NdefContent.App -> S(R.string.nfc_t_app) to c.pkg
        is NdefContent.Mime -> c.type to (c.preview ?: bytes(c.size))
        is NdefContent.Other -> S(R.string.nfc_t_other) to "${c.type.ifEmpty { "TNF ${c.tnf}" }} · ${bytes(c.size)}"
    }
    Hairline()
    Row(
        Modifier.fillMaxWidth().clickable {
            when (c) {
                is NdefContent.Url -> actions.openUrl(c.uri)
                is NdefContent.Geo -> actions.openUrl("geo:${c.lat},${c.lon}")
                else -> actions.copy(title, value)
            }
        }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Symbol(contentIcon(c), size = 22.dp, tint = AntTheme.accent.accent)
        Column(Modifier.weight(1f)) {
            Text(title, style = rf(12, 16), color = cs.onSurfaceVariant)
            Text(value, style = rf(14, 20, 500), maxLines = 6, overflow = TextOverflow.Ellipsis)
        }
        Symbol(if (c is NdefContent.Url || c is NdefContent.Geo) Sym.OpenInNew else Sym.ContentCopy, size = 18.dp, tint = cs.onSurfaceVariant)
    }
}

private fun authLabel(a: WifiAuth) = when (a) {
    WifiAuth.Open -> S(R.string.nfc_wifi_open)
    WifiAuth.Wpa2 -> "WPA2"
    WifiAuth.Wpa3 -> "WPA3"
}

// ---- writer ---------------------------------------------------------------------------------

private enum class Kind { Url, Text, Wifi, Contact, Phone, Sms, Email, Geo, App }

private fun kindLabel(k: Kind) = when (k) {
    Kind.Url -> S(R.string.nfc_t_url)
    Kind.Text -> S(R.string.nfc_t_text)
    Kind.Wifi -> "Wi-Fi"
    Kind.Contact -> S(R.string.nfc_t_contact)
    Kind.Phone -> S(R.string.nfc_t_phone)
    Kind.Sms -> "SMS"
    Kind.Email -> S(R.string.nfc_t_email)
    Kind.Geo -> S(R.string.nfc_t_geo)
    Kind.App -> S(R.string.nfc_t_app)
}

@Composable
fun NfcWriteTool(vm: MainViewModel) {
    val c = vm.tools.nfc
    Listen(c, NfcMode.Write)
    val conn = vm.wifi.collectAsStateWithLifecycle().value.connection
    val positions by vm.positions.collectAsStateWithLifecycle()
    var kind by remember { mutableStateOf(Kind.Url) }
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    var d by remember { mutableStateOf("") }
    var e by remember { mutableStateOf("") }
    var auth by remember { mutableStateOf(WifiAuth.Wpa2) }
    fun reset(k: Kind) {
        kind = k; a = ""; b = ""; d = ""; e = ""
        when (k) {
            Kind.Url -> a = "https://"
            Kind.Wifi -> a = conn?.ssid.orEmpty()
            Kind.Geo -> (positions.gnss ?: positions.fused ?: positions.network)?.let { a = "%.6f".format(Locale.US, it.latitude); b = "%.6f".format(Locale.US, it.longitude) }
            Kind.App -> a = "com.allnetworktools"
            else -> Unit
        }
        c.pending = null
    }
    val content: NdefContent? = when (kind) {
        Kind.Url -> a.takeIf { it.length > 8 || (it.isNotBlank() && !it.startsWith("https://")) }?.let { NdefContent.Url(it.trim()) }
        Kind.Text -> a.takeIf { it.isNotBlank() }?.let { NdefContent.Text(it, Locale.getDefault().language) }
        Kind.Wifi -> a.takeIf { it.isNotBlank() && (auth == WifiAuth.Open || b.length >= 8) }?.let { NdefContent.Wifi(it, b, auth) }
        Kind.Contact -> a.takeIf { it.isNotBlank() }?.let { NdefContent.Contact(it.trim(), b.trim(), d.trim(), e.trim()) }
        Kind.Phone -> a.takeIf { it.count(Char::isDigit) >= 3 }?.let { NdefContent.Phone(it) }
        Kind.Sms -> a.takeIf { it.count(Char::isDigit) >= 3 }?.let { NdefContent.Sms(it, b) }
        Kind.Email -> a.takeIf { '@' in it }?.let { NdefContent.Email(it.trim(), b, d) }
        Kind.Geo -> { val la = a.replace(',', '.').toDoubleOrNull(); val lo = b.replace(',', '.').toDoubleOrNull(); if (la != null && lo != null && la in -90.0..90.0 && lo in -180.0..180.0) NdefContent.Geo(la, lo) else null }
        Kind.App -> a.takeIf { it.contains('.') }?.let { NdefContent.App(it.trim()) }
    }
    val message = remember(content) { content?.let { runCatching { Ndef.build(it) }.getOrNull() } }
    PageColumn {
        val armed = c.pending
        if (armed != null) {
            HoldTag(S(R.string.nfc_hold_title), S(R.string.nfc_hold_write), c.working)
            ResultBanner(c.writeResult, P(R.plurals.p_nfc_written, c.written, c.written))
            PillButton(S(R.string.nfc_change_content), { c.pending = null; c.resetWrites() }, icon = Sym.Edit, height = 48.dp, outlined = true)
            return@PageColumn
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Kind.entries.forEach { k -> AntFilterChip(kindLabel(k), k == kind, { reset(k) }) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (kind) {
                Kind.Url -> HostInputField(a, { a = it }, S(R.string.nfc_t_url), Sym.Link)
                Kind.Text -> HostInputField(a, { a = it }, S(R.string.nfc_t_text), Sym.TextFields, keyboardType = KeyboardType.Text)
                Kind.Wifi -> {
                    HostInputField(a, { a = it }, S(R.string.nfc_f_ssid), Sym.Wifi, keyboardType = KeyboardType.Text)
                    SegmentedRow(listOf(WifiAuth.Wpa2 to "WPA2", WifiAuth.Wpa3 to "WPA3", WifiAuth.Open to S(R.string.nfc_wifi_open)), auth, { auth = it }, Modifier.fillMaxWidth())
                    if (auth != WifiAuth.Open) HostInputField(b, { b = it }, S(R.string.nfc_f_password), Sym.Key, keyboardType = KeyboardType.Password)
                }
                Kind.Contact -> {
                    HostInputField(a, { a = it }, S(R.string.nfc_f_name), Sym.Contacts, keyboardType = KeyboardType.Text)
                    HostInputField(b, { b = it }, S(R.string.nfc_t_phone), Sym.Call, keyboardType = KeyboardType.Phone)
                    HostInputField(d, { d = it }, S(R.string.nfc_t_email), Sym.Mail, keyboardType = KeyboardType.Email)
                    HostInputField(e, { e = it }, S(R.string.nfc_f_org), Sym.Badge, keyboardType = KeyboardType.Text)
                }
                Kind.Phone -> HostInputField(a, { a = it }, S(R.string.nfc_t_phone), Sym.Call, keyboardType = KeyboardType.Phone)
                Kind.Sms -> {
                    HostInputField(a, { a = it }, S(R.string.nfc_t_phone), Sym.Sms, keyboardType = KeyboardType.Phone)
                    HostInputField(b, { b = it }, S(R.string.nfc_f_message), Sym.TextFields, keyboardType = KeyboardType.Text)
                }
                Kind.Email -> {
                    HostInputField(a, { a = it }, S(R.string.nfc_f_to), Sym.Mail, keyboardType = KeyboardType.Email)
                    HostInputField(b, { b = it }, S(R.string.nfc_f_subject), Sym.TextFields, keyboardType = KeyboardType.Text)
                    HostInputField(d, { d = it }, S(R.string.nfc_f_message), Sym.TextFields, keyboardType = KeyboardType.Text)
                }
                Kind.Geo -> {
                    HostInputField(a, { a = it }, S(R.string.nfc_f_lat), Sym.LocationOn, keyboardType = KeyboardType.Decimal)
                    HostInputField(b, { b = it }, S(R.string.nfc_f_lon), Sym.LocationOn, keyboardType = KeyboardType.Decimal)
                }
                Kind.App -> HostInputField(a, { a = it }, S(R.string.nfc_f_package), Sym.Apps, keyboardType = KeyboardType.Text)
            }
        }
        Text(
            when (kind) {
                Kind.Wifi -> S(R.string.nfc_help_wifi)
                Kind.App -> S(R.string.nfc_help_app)
                else -> message?.let { S(R.string.nfc_size_needed, Ndef.sizeOnTag(it)) } ?: S(R.string.nfc_fill_fields)
            },
            style = rf(13, 18), color = cs.onSurfaceVariant,
        )
        PillButton(S(R.string.nfc_ready_to_write), { c.resetWrites(); c.pending = message }, icon = Sym.Nfc, enabled = message != null)
    }
}

// ---- copy -----------------------------------------------------------------------------------

@Composable
fun NfcCopyTool(vm: MainViewModel) {
    val c = vm.tools.nfc
    Listen(c, NfcMode.Copy)
    PageColumn {
        val src = c.copySource
        if (src == null) {
            HoldTag(S(R.string.nfc_copy_step1), S(R.string.nfc_copy_step1_text), c.working)
            if (c.writeResult == WriteResult.NotNdef) ResultBanner(WriteResult.NotNdef, "")
            Text(S(R.string.nfc_copy_note), style = rf(13, 18), color = cs.onSurfaceVariant)
            return@PageColumn
        }
        InfoList(S(R.string.nfc_copy_source, src.chip.name)) {
            src.ndef?.contents?.forEach { ContentRow(it) }
        }
        HoldTag(S(R.string.nfc_copy_step2), S(R.string.nfc_copy_step2_text, src.message?.byteArrayLength ?: 0), c.working)
        ResultBanner(c.writeResult, P(R.plurals.p_nfc_copies, c.written, c.written))
        PillButton(S(R.string.nfc_copy_other), { c.resetCopy(); c.resetWrites() }, icon = Sym.RestartAlt, height = 48.dp, outlined = true)
    }
}

// ---- erase / lock ---------------------------------------------------------------------------

@Composable
fun NfcEraseTool(vm: MainViewModel) {
    val c = vm.tools.nfc
    var lock by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    Listen(c, if (lock) NfcMode.Lock else NfcMode.Erase)
    PageColumn {
        SegmentedRow(listOf(false to S(R.string.nfc_erase), true to S(R.string.nfc_lock)), lock, { lock = it; c.resetWrites(); c.lockArmed = false }, Modifier.fillMaxWidth(), icons = mapOf(false to Sym.Delete, true to Sym.Lock))
        if (!lock) {
            HoldTag(S(R.string.nfc_hold_title), S(R.string.nfc_hold_erase), c.working)
            ResultBanner(c.writeResult, S(R.string.nfc_erased))
        } else if (!c.lockArmed) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(AntTheme.net.poor.copy(alpha = 0.12f)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Symbol(Sym.Warning, size = 24.dp, filled = true, tint = AntTheme.net.poor)
                    Text(S(R.string.nfc_lock_title), style = gs(20, 26, 500))
                }
                Text(S(R.string.nfc_lock_text), style = rf(14, 20))
                PillButton(S(R.string.nfc_lock_arm), { confirm = true }, icon = Sym.Lock, bg = AntTheme.net.poor, fg = Color.White, height = 48.dp)
            }
            ResultBanner(c.writeResult, S(R.string.nfc_locked_done))
        } else {
            HoldTag(S(R.string.nfc_hold_title), S(R.string.nfc_hold_lock), c.working)
            PillButton(S(R.string.cancel), { c.lockArmed = false }, height = 44.dp, outlined = true)
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(S(R.string.nfc_lock_title)) },
            text = { Text(S(R.string.nfc_lock_confirm), style = rf(14, 20)) },
            confirmButton = { TextButton({ c.lockArmed = true; c.resetWrites(); confirm = false }) { Text(S(R.string.nfc_lock), color = AntTheme.net.poor) } },
            dismissButton = { TextButton({ confirm = false }) { Text(S(R.string.cancel)) } },
        )
    }
}

// ---- bank card ------------------------------------------------------------------------------

@Composable
fun NfcBankTool(vm: MainViewModel) {
    val c = vm.tools.nfc
    Listen(c, NfcMode.Bank)
    PageColumn {
        val card = c.card
        if (card == null) {
            HoldTag(S(R.string.nfc_hold_card), S(R.string.nfc_hold_bank), c.working)
        } else {
            CardFace(card)
            InfoList(S(R.string.nfc_card_info)) {
                InfoRow(S(R.string.nfc_card_network), card.scheme)
                card.label?.let { InfoRow(S(R.string.nfc_card_app), it) }
                if (card.applications.size > 1) InfoRow(S(R.string.nfc_card_apps), card.applications.joinToString(", "))
                card.expiry?.let { InfoRow(S(R.string.nfc_card_expiry), it) }
                card.effective?.let { InfoRow(S(R.string.nfc_card_effective), it) }
                card.country?.let { InfoRow(S(R.string.nfc_card_country), it) }
                card.currency?.let { InfoRow(S(R.string.nfc_card_currency), it) }
                card.holder?.let { InfoRow(S(R.string.nfc_card_holder), it) }
                InfoRow("AID", card.aid)
            }
            SectionTitle(S(R.string.nfc_card_log))
            if (card.transactions.isEmpty()) {
                Text(if (card.logSupported) S(R.string.nfc_card_log_empty) else S(R.string.nfc_card_log_none), style = rf(14, 20), color = cs.onSurfaceVariant)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    card.transactions.forEachIndexed { i, t ->
                        Row(
                            Modifier.fillMaxWidth().clip(groupShape(i, card.transactions.size)).background(cs.surfaceContainerLow).padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(t.merchant ?: listOfNotNull(t.date, t.time).joinToString(" "), style = rf(15, 20, 600))
                                Text(listOfNotNull(t.date.takeIf { t.merchant != null }, t.time.takeIf { t.merchant != null }, t.country).joinToString(" · "), style = rf(12, 16), color = cs.onSurfaceVariant)
                            }
                            t.amount?.let { Text("%.2f %s".format(Locale.getDefault(), it / 100.0, t.currency.orEmpty()), style = gs(16, 22, 600, tnum = true)) }
                        }
                    }
                }
            }
            Text(S(R.string.nfc_card_privacy), style = rf(13, 18), color = cs.onSurfaceVariant)
        }
        if (c.cardFailed && card == null) Text(S(R.string.nfc_card_failed), style = rf(14, 20), color = AntTheme.net.poor)
    }
}

@Composable
private fun CardFace(card: EmvCard) {
    val acc = AntTheme.accent
    Column(
        Modifier.fillMaxWidth().aspectRatio(1.586f).clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(acc.accent, acc.accent.copy(alpha = 0.65f), cs.inverseSurface)))
            .padding(22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(card.label ?: card.scheme, Modifier.weight(1f), style = rf(16, 22, 600), color = Color.White, maxLines = 1)
            Symbol(Sym.Contactless, size = 28.dp, tint = Color.White)
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(width = 46.dp, height = 34.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFE6C36A)))
        Text("•••• •••• •••• " + (card.last4 ?: "••••"), Modifier.padding(top = 14.dp), style = mono(20, 26, 600), color = Color.White)
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
            Text(card.expiry?.let { "EXP $it" }.orEmpty(), Modifier.weight(1f), style = mono(13, 18), color = Color.White.copy(alpha = 0.9f))
            Text(card.scheme, style = gs(20, 24, 700), color = Color.White)
        }
    }
}

// ---- memory ---------------------------------------------------------------------------------

@Composable
fun NfcDumpTool(vm: MainViewModel) {
    val c = vm.tools.nfc
    Listen(c, NfcMode.Dump)
    val actions = LocalActions.current
    PageColumn {
        val d = c.dump
        if (d == null) {
            HoldTag(S(R.string.nfc_hold_title), S(R.string.nfc_hold_dump), c.working)
            Text(S(R.string.nfc_dump_note), style = rf(13, 18), color = cs.onSurfaceVariant)
            return@PageColumn
        }
        HeroCard {
            Text(d.chip.name, style = gs(24, 30, 500))
            Text(
                when {
                    d.unsupported -> S(R.string.nfc_dump_unsupported)
                    d.sectors > 0 -> S(R.string.nfc_dump_sectors, d.keysFound, d.sectors)
                    else -> P(R.plurals.p_nfc_units, d.rows.size, d.rows.size, d.unit)
                },
                style = rf(14, 20),
            )
        }
        if (d.rows.isNotEmpty()) {
            val text = remember(d) { d.rows.joinToString("\n") { r -> "%03d %s".format(r.address, r.bytes?.let { NfcChips.hex(it, " ") } ?: "??") } }
            PillButton(S(R.string.nfc_dump_copy), { actions.copy("dump", text) }, icon = Sym.ContentCopy, height = 44.dp, outlined = true)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainerLow).padding(12.dp)) {
                var lastSector = -1
                d.rows.forEach { r ->
                    if (r.sector != null && r.sector != lastSector) {
                        lastSector = r.sector
                        Text(S(R.string.nfc_sector, r.sector), Modifier.padding(top = if (r.sector == 0) 0.dp else 8.dp, bottom = 2.dp), style = rf(12, 16, 700), color = AntTheme.accent.accent)
                    }
                    DumpLine(r.address, r.bytes, r.note)
                }
            }
        }
        Text(S(R.string.nfc_hold_next), style = rf(13, 18), color = cs.onSurfaceVariant)
    }
}

@Composable
private fun DumpLine(address: Int, bytes: ByteArray?, note: DumpNote?) {
    val noteText = when (note) {
        DumpNote.Uid -> "UID"
        DumpNote.Lock -> S(R.string.nfc_n_lock)
        DumpNote.Cc -> "CC"
        DumpNote.Config -> "Config"
        DumpNote.Trailer -> S(R.string.nfc_n_keys)
        DumpNote.Manufacturer -> S(R.string.nfc_n_maker)
        DumpNote.Protected -> S(R.string.nfc_n_protected)
        null -> null
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("%03d".format(address), Modifier.width(34.dp), style = mono(11, 16), color = cs.onSurfaceVariant)
        Text(bytes?.let { NfcChips.hex(it, " ") } ?: "?? ".repeat(4).trim(), Modifier.weight(1f), style = mono(11, 16), color = if (bytes == null) cs.onSurfaceVariant else cs.onSurface)
        if (noteText != null) Text(noteText, style = rf(10, 14, 600), color = AntTheme.accent.accent, maxLines = 1)
    }
}

// ---- antenna --------------------------------------------------------------------------------

@Composable
fun NfcAntennaTool(vm: MainViewModel) {
    val c = vm.tools.nfc
    Listen(c, NfcMode.Antenna)
    val s = c.antenna
    val net = AntTheme.net
    val color = when {
        !s.present -> cs.outline
        s.quality >= 0.9f -> net.good
        s.quality >= 0.6f -> net.fair
        else -> net.poor
    }
    val fill by animateFloatAsState(if (s.present) s.quality else 0f, label = "q")
    PageColumn {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(AntTheme.accent.container).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(150.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                if (s.present) PulseRing(color, 140.dp, periodMs = 900)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (s.present) "${s.rate}" else "—", style = gs(44, 50, 500, tnum = true), color = AntTheme.accent.onContainer)
                    Text(S(R.string.nfc_per_second), style = rf(12, 16), color = AntTheme.accent.onContainer)
                }
            }
            Text(if (s.present) S(R.string.nfc_tag_in_field) else S(R.string.nfc_tag_away), style = gs(20, 26, 500), color = AntTheme.accent.onContainer)
            LevelBar(fill, color, Modifier.fillMaxWidth(), height = 12.dp)
            Text(S(R.string.nfc_quality, (fill * 100).toInt()), style = rf(13, 18), color = AntTheme.accent.onContainer)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HeroTile("${s.contacts}", S(R.string.nfc_contacts), Modifier.weight(1f))
            HeroTile("${s.best}/s", S(R.string.nfc_best), Modifier.weight(1f))
            HeroTile("${s.drops}", S(R.string.nfc_drops), Modifier.weight(1f))
        }
        InfoList(S(R.string.nfc_antenna_how)) {
            Text(S(R.string.nfc_antenna_help), Modifier.padding(vertical = 8.dp), style = rf(14, 20))
        }
        if (s.tries > 0) {
            InfoList(S(R.string.nfc_last_contact)) {
                InfoRow(S(R.string.nfc_held), "%.1f s".format(Locale.getDefault(), s.heldMs / 1000.0))
                InfoRow(S(R.string.nfc_exchanges), "${s.ok} / ${s.tries}")
            }
        }
    }
}
