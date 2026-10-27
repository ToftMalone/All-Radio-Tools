package com.allnetworktools.ui.pages.wardrive

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.R
import com.allnetworktools.data.Exclusions
import com.allnetworktools.data.Wardrive
import com.allnetworktools.data.WardriveSettings
import com.allnetworktools.data.WigleClient
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.Hairline
import com.allnetworktools.ui.components.PillButton
import com.allnetworktools.ui.components.SectionTitle
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.pages.PageColumn
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.HostInputField
import com.allnetworktools.ui.tools.mono
import com.allnetworktools.util.P
import com.allnetworktools.util.S
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The WiGLE.net account, the addresses left out, the database. */
@Composable
fun WardrivingSettingsTool(@Suppress("UNUSED_PARAMETER") vm: MainViewModel) {
    val context = LocalContext.current
    val repo = remember { Wardrive.settings(context) }
    val s = repo.settings.collectAsStateWithLifecycle(null).value ?: return
    val st by Wardrive.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { Wardrive.load(context) }
    PageColumn {
        AccountCard(s)
        ExclusionsCard(s)
        DatabaseCard(st.db.wifi, st.db.ble, st.pending)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainerLow).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Symbol(Sym.Info, size = 20.dp, tint = AntTheme.accent.accent)
                Text(S(R.string.wardriving_throttle_title), style = rf(15, 20, 600))
            }
            Text(S(R.string.wardriving_throttle_text), style = rf(13, 18), color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainerLow).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun AccountCard(s: WardriveSettings) {
    val context = LocalContext.current
    val actions = LocalActions.current
    val scope = rememberCoroutineScope()
    val repo = Wardrive.settings(context)
    SectionTitle(S(R.string.wigle_account))
    Card {
        if (s.connected) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(AntTheme.accent.container), contentAlignment = Alignment.Center) {
                    Symbol(Sym.CheckCircle, size = 22.dp, filled = true, tint = AntTheme.accent.onContainer)
                }
                Column(Modifier.weight(1f)) {
                    Text(S(R.string.wigle_connected_as, s.user.ifEmpty { s.apiName }), style = rf(15, 20, 600))
                    Text("WiGLE.net", style = rf(12, 16), color = cs.onSurfaceVariant)
                }
            }
            PillButton(S(R.string.wigle_disconnect), { scope.launch { repo.disconnect() } }, height = 40.dp, outlined = true)
        } else {
            var name by remember { mutableStateOf("") }
            var token by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            Text(S(R.string.wigle_help), style = rf(13, 18), color = cs.onSurfaceVariant)
            PillButton(S(R.string.wigle_open_account), { actions.openUrl("https://wigle.net/account") }, icon = Sym.OpenInNew, height = 40.dp, outlined = true)
            HostInputField(name, { name = it.trim() }, S(R.string.wigle_api_name), icon = Sym.Badge, keyboardType = KeyboardType.Ascii)
            HostInputField(token, { token = it.trim() }, S(R.string.wigle_api_token), icon = Sym.Key, keyboardType = KeyboardType.Password)
            error?.let { Text(S(R.string.wigle_failed, it), style = rf(13, 18), color = AntTheme.net.poor) }
            PillButton(
                if (busy) S(R.string.wigle_checking) else S(R.string.wigle_connect),
                {
                    busy = true; error = null
                    scope.launch {
                        when (val r = withContext(Dispatchers.IO) { WigleClient.profile(name, token) }) {
                            is WigleClient.Result.Ok -> repo.setAccount(name, token, r.value)
                            is WigleClient.Result.Failed -> error = r.message
                        }
                        busy = false
                    }
                },
                icon = Sym.Link, height = 48.dp, enabled = !busy && name.isNotEmpty() && token.isNotEmpty(),
            )
        }
    }
}

@Composable
private fun ExclusionsCard(s: WardriveSettings) {
    val context = LocalContext.current
    var entry by remember { mutableStateOf("") }
    val valid = Exclusions.pretty(entry)
    fun add() {
        valid ?: return
        Wardrive.applyExclusions(context, s.exclusions + valid)
        entry = ""
    }
    SectionTitle(S(R.string.wardriving_exclusions))
    Card {
        Text(S(R.string.wardriving_exclusions_help), style = rf(13, 18), color = cs.onSurfaceVariant)
        HostInputField(entry, { entry = it }, S(R.string.wardriving_exclusion_field), icon = Sym.Block, keyboardType = KeyboardType.Ascii, onDone = ::add)
        if (entry.isNotBlank() && valid == null) Text(S(R.string.wardriving_exclusion_invalid), style = rf(12, 16), color = AntTheme.net.poor)
        PillButton(S(R.string.wardriving_add), ::add, icon = Sym.Add, height = 40.dp, enabled = valid != null)
        if (s.exclusions.isEmpty()) {
            Text(S(R.string.wardriving_no_exclusion), style = rf(13, 18), color = cs.onSurfaceVariant)
        } else {
            Column {
                s.exclusions.sorted().forEachIndexed { i, e ->
                    if (i > 0) Hairline()
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        val vendor = if (e.length == 8) Wardrive.oui(context).vendor(e) else null
                        Column(Modifier.weight(1f)) {
                            Text(e, style = mono(14))
                            Text(
                                if (e.length == 8) listOfNotNull("OUI", vendor).joinToString(" · ") else "MAC",
                                style = rf(12, 16), color = cs.onSurfaceVariant,
                            )
                        }
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).clickable { Wardrive.applyExclusions(context, s.exclusions - e) },
                            contentAlignment = Alignment.Center,
                        ) { Symbol(Sym.Delete, size = 20.dp, tint = cs.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DatabaseCard(wifi: Long, ble: Long, pending: Long) {
    val context = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    SectionTitle(S(R.string.wardriving_database))
    Card {
        Text(S(R.string.wardriving_db_counts, wifi.toInt(), ble.toInt()), style = rf(15, 20, 600))
        Text(P(R.plurals.p_wardriving_pending, pending.toInt(), pending.toInt()), style = rf(13, 18), color = cs.onSurfaceVariant)
        PillButton(S(R.string.wardriving_clear), { confirm = true }, icon = Sym.Delete, height = 40.dp, outlined = true, bg = AntTheme.net.poor)
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(S(R.string.wardriving_clear)) },
            text = { Text(S(R.string.wardriving_clear_confirm), style = rf(14, 20)) },
            confirmButton = { TextButton({ Wardrive.clearDatabase(context); confirm = false }) { Text(S(R.string.wardriving_clear), color = AntTheme.net.poor) } },
            dismissButton = { TextButton({ confirm = false }) { Text(S(R.string.cancel)) } },
        )
    }
}
