package com.allnetworktools.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.allnetworktools.MainViewModel
import com.allnetworktools.R
import com.allnetworktools.model.Network
import com.allnetworktools.model.Tool
import com.allnetworktools.ui.components.IconCircleButton
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.pages.ToolHit
import com.allnetworktools.ui.pages.allToolHits
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.util.S

/**
 * What the phone already knows, for the assistant: the connections as last read and the results the tools still
 * hold. Read when a question is asked; nothing is started to answer it.
 */
fun phoneFacts(vm: MainViewModel): String = buildString {
    vm.wifi.value.connection?.let { c ->
        appendLine("Wi-Fi: connected to ${c.ssid ?: "a hidden network"}, signal ${c.rssi} dBm, ${c.frequency} MHz" +
            (c.linkTx?.let { ", link $it Mbit/s" } ?: "") + (c.security?.let { ", security $it" } ?: ""))
    }
    vm.cell.value.state?.let { s ->
        s.serving?.let { appendLine("Mobile network:\n" + clipFacts(com.allnetworktools.ui.pages.cell.report(it, s), 900)) }
    }
    for (tool in listOf(Tool.Speed, Tool.CellSpeed, Tool.Ping, Tool.Audit, Tool.Mitm, Tool.Censor, Tool.UnknownTrackers)) {
        val f = toolFacts(tool, vm)?.invoke().orEmpty()
        if (f.isNotBlank()) appendLine("Last results of the tool \"${tool.title}\":\n" + clipFacts(f, 700))
    }
}

/** The tools named in an answer, so they can be opened from it. */
internal fun toolsNamed(answer: String, all: List<ToolHit>): List<ToolHit> {
    val low = answer.lowercase()
    return all.filter { it.title.length >= 3 && low.contains(it.title.lowercase()) }
        .sortedByDescending { it.title.length }.distinctBy { it.tool }.take(3)
}

/**
 * The diagnostic assistant of the home screen: a question about the phone's networks, answered by Gemini Nano from
 * what the phone has measured, with the tools it names one tap away. Opened only from the Gemini button.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiAssistant(vm: MainViewModel, onOpenTool: (Network, Tool) -> Unit, onDismiss: () -> Unit) {
    val all = remember { allToolHits() }
    var draft by rememberSaveable { mutableStateOf("") }
    var asked by rememberSaveable { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val ask = { q: String -> if (q.isNotBlank()) { asked = q.trim(); attempt++ } }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(
            Modifier.fillMaxSize().background(cs.surface)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.navigationBars).union(WindowInsets.ime))
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { AiHeader(S(R.string.ai_assistant_sub)) }
                IconCircleButton(Sym.Close, onDismiss, tint = cs.onSurfaceVariant)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val q = asked
                if (q == null) {
                    Text(S(R.string.ai_assistant_hint), style = rf(14, 20), color = cs.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        S(R.string.ai_assistant_examples).split('|').forEach { ex ->
                            Text(
                                ex, Modifier.clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHigh).clickable { draft = ex; ask(ex) }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                style = rf(14, 20), color = cs.onSurface,
                            )
                        }
                    }
                } else {
                    Text(q, Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(AntTheme.accent.container).padding(14.dp), style = rf(15, 21, 600), color = AntTheme.accent.onContainer)
                    val prompt = remember(q, attempt) { Prompts.assistant(q, phoneFacts(vm), all.map { it.title }.distinct()) }
                    AiAnswer(vm, prompt, key = "$q/$attempt", shareTitle = q, onRetry = { attempt++ }) { answer ->
                        val named = remember(answer) { toolsNamed(answer, all) }
                        if (named.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            named.forEach { h ->
                                val roles = AntTheme.net[h.network]
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(roles.container)
                                        .clickable { onOpenTool(h.network, h.tool) }.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Symbol(h.icon, size = 22.dp, tint = roles.onContainer)
                                    Text(S(R.string.ai_open_tool, h.title), Modifier.weight(1f), style = rf(14, 20, 600), color = roles.onContainer)
                                    Symbol(Sym.ChevronRight, size = 20.dp, tint = roles.onContainer, description = null)
                                }
                            }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp).height(56.dp).clip(RoundedCornerShape(28.dp)).background(cs.surfaceContainerHigh).padding(start = 18.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (draft.isEmpty()) Text(S(R.string.ai_assistant_field), style = rf(16, 24), color = cs.onSurfaceVariant, maxLines = 1)
                    BasicTextField(
                        draft, { draft = it }, Modifier.fillMaxWidth().semantics { contentDescription = S(R.string.ai_assistant_field) },
                        textStyle = rf(16, 24).copy(color = cs.onSurface), singleLine = true,
                        cursorBrush = SolidColor(AntTheme.accent.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { ask(draft) }),
                    )
                }
                Box(
                    Modifier.padding(4.dp).height(48.dp).clip(RoundedCornerShape(24.dp)).background(if (draft.isBlank()) cs.surfaceContainerHighest else AntTheme.accent.accent)
                        .clickable(enabled = draft.isNotBlank()) { ask(draft) }.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { Symbol(Sym.ArrowUpward, size = 22.dp, tint = if (draft.isBlank()) cs.onSurfaceVariant else AntTheme.accent.onAccent, description = S(R.string.ai_send)) }
            }
        }
    }
}
