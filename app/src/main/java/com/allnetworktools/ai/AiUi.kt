package com.allnetworktools.ai

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.R
import com.allnetworktools.ui.LocalActions
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.gs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.IndeterminateBar
import com.allnetworktools.util.S
import kotlinx.coroutines.flow.catch

/** Gemini's colours, blue to purple to rose. */
private val GeminiBrushColors = listOf(Color(0xFF4285F4), Color(0xFF9B72CB), Color(0xFFD96570))

/** The Gemini spark: four points with curved sides, in Gemini's gradient. */
@Composable
fun GeminiMark(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val c = w / 2
        val p = Path().apply {
            moveTo(c, 0f)
            quadraticTo(c, c, w, c)
            quadraticTo(c, c, c, w)
            quadraticTo(c, c, 0f, c)
            quadraticTo(c, c, c, 0f)
            close()
        }
        drawPath(p, Brush.linearGradient(GeminiBrushColors, Offset(0f, w), Offset(w, 0f)))
    }
}

/** Whether anything about the AI may show: only once AICore said this phone can run Gemini Nano. */
@Composable
fun rememberAiAvailable(vm: MainViewModel): Boolean {
    val s by vm.ai.state.collectAsStateWithLifecycle()
    return s != AiState.Unavailable && s != AiState.Checking
}

/** The round Gemini button: the AI only ever starts from a tap on it. */
@Composable
fun GeminiButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, background: Color = Color.Transparent) {
    Box(
        modifier.size(48.dp).clip(CircleShape).background(background)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { GeminiMark(22.dp) }
}

/** What Gemini is asked for in a tool. */
enum class AiMode { Explain, Report }

/**
 * Gemini on a tool's results, in a sheet: an explanation, or a report to share. The facts are read and the model run
 * when the sheet opens (that is, on the tap), and again on each change of mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiToolSheet(vm: MainViewModel, tool: String, facts: () -> String, onDismiss: () -> Unit) {
    var mode by remember { mutableStateOf(AiMode.Explain) }
    var attempt by remember { mutableIntStateOf(0) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = cs.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AiHeader(tool)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AiMode.entries.forEach { m ->
                    com.allnetworktools.ui.components.AntFilterChip(
                        S(if (m == AiMode.Explain) R.string.ai_explain else R.string.ai_report), mode == m, { mode = m },
                    )
                }
            }
            val shown = remember(mode, attempt) { facts() }
            if (shown.isBlank()) {
                AiNote(S(R.string.ai_nothing_yet))
            } else {
                val prompt = remember(mode, attempt) { if (mode == AiMode.Explain) Prompts.explain(tool, shown) else Prompts.report(tool, shown) }
                AiAnswer(vm, prompt, key = "$mode/$attempt", shareTitle = tool, onRetry = { attempt++ })
            }
        }
    }
}

@Composable
internal fun AiHeader(subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(cs.surfaceContainerHigh), contentAlignment = Alignment.Center) { GeminiMark(24.dp) }
        Column(Modifier.weight(1f)) {
            Text("Gemini Nano", style = gs(20, 26, 500))
            Text(subtitle, style = rf(13, 18), color = cs.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
internal fun AiNote(text: String) {
    Text(text, Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHigh).padding(14.dp), style = rf(14, 20), color = cs.onSurfaceVariant)
}

/** The model's answer to [prompt], growing as it comes, with copy, share and try again. */
@Composable
internal fun AiAnswer(vm: MainViewModel, prompt: String, key: Any, shareTitle: String, onRetry: () -> Unit, after: @Composable (String) -> Unit = {}) {
    val state by vm.ai.state.collectAsStateWithLifecycle()
    var text by remember(key) { mutableStateOf("") }
    var done by remember(key) { mutableStateOf(false) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    LaunchedEffect(key) {
        vm.ai.answer(prompt)
            .catch { e -> error = e.message ?: e.javaClass.simpleName; com.allnetworktools.util.DebugLog.e("AI", "Answer failed", e) }
            .collect { text += it }
        done = true
    }
    val actions = LocalActions.current
    Column(
        Modifier.fillMaxWidth().heightIn(max = 460.dp).clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainerHigh)
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            error != null -> Text(S(R.string.ai_failed, error!!), style = rf(14, 20), color = cs.error)
            text.isEmpty() -> {
                val s = state
                Text(
                    if (s is AiState.Downloading) S(R.string.ai_downloading, s.bytes / 1_000_000) else S(R.string.ai_thinking),
                    style = rf(14, 20), color = cs.onSurfaceVariant,
                )
                IndeterminateBar()
            }
            else -> AiText(text)
        }
    }
    if (done && error == null && text.isNotBlank()) after(text)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(S(R.string.ai_disclaimer), Modifier.weight(1f), style = rf(11, 15), color = cs.onSurfaceVariant)
        if (done && text.isNotBlank()) {
            com.allnetworktools.ui.components.IconCircleButton(Sym.ContentCopy, { actions.copy("Gemini", text.trim()) }, tint = cs.onSurfaceVariant)
            com.allnetworktools.ui.components.IconCircleButton(Sym.IosShare, { actions.share(shareTitle, text.trim()) }, tint = cs.onSurfaceVariant)
        }
        if (done) com.allnetworktools.ui.components.IconCircleButton(Sym.Refresh, onRetry, tint = cs.onSurfaceVariant)
    }
}

/** The model's light Markdown: headings, "- " or "* " bullets, **bold** dropped, the rest as paragraphs. */
@Composable
internal fun AiText(text: String) {
    text.replace("\r", "").lines().map { it.trimEnd() }.filter { it.isNotBlank() }.forEach { raw ->
        val l = raw.trim().replace("**", "")
        when {
            l.startsWith("#") -> Text(l.trimStart('#').trim(), style = rf(15, 21, 700), color = cs.primary)
            l.startsWith("- ") || l.startsWith("* ") || l.startsWith("• ") -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("•", style = rf(14, 20, 700), color = cs.primary)
                Text(l.substring(2).trim(), Modifier.weight(1f), style = rf(14, 20))
            }
            else -> Text(l, style = rf(14, 20))
        }
    }
}
