package com.allnetworktools.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.allnetworktools.R
import com.allnetworktools.model.Network
import com.allnetworktools.model.Tool
import com.allnetworktools.ui.components.IconCircleButton
import com.allnetworktools.ui.components.LeadingIcon
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.components.groupShape
import com.allnetworktools.ui.components.rise
import com.allnetworktools.ui.pages.allToolHits
import com.allnetworktools.ui.pages.searchTools
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.util.S

/**
 * Every tool of the app, found by typing part of its name, of what it does or of its radio: with more than eighty
 * tools under six radios, the quickest way to one whose radio is not obvious.
 */
@Composable
fun ToolSearch(onPick: (Network, Tool) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ToolSearchContent(onPick, onDismiss)
    }
}

/** The search screen itself, inside its full-screen window. */
@Composable
internal fun ToolSearchContent(onPick: (Network, Tool) -> Unit, onDismiss: () -> Unit, initialQuery: String = "") {
    val all = remember { allToolHits() }
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    val hits = remember(query) { searchTools(all, query) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    run {
        Column(
            Modifier.fillMaxSize().background(cs.surface)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.navigationBars).union(WindowInsets.ime)),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
                    .height(56.dp).clip(RoundedCornerShape(28.dp)).background(cs.surfaceContainerHigh),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconCircleButton(Sym.ArrowBack, onDismiss, tint = cs.onSurface, label = S(R.string.a11y_back))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(S(R.string.search_tools), style = rf(16, 24), color = cs.onSurfaceVariant, maxLines = 1)
                    BasicTextField(
                        query, { query = it },
                        Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = S(R.string.search_tools) },
                        textStyle = rf(16, 24).copy(color = cs.onSurface),
                        singleLine = true,
                        cursorBrush = SolidColor(AntTheme.accent.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { hits.firstOrNull()?.let { onPick(it.network, it.tool) } }),
                    )
                }
                if (query.isNotEmpty()) IconCircleButton(Sym.Close, { query = "" }, tint = cs.onSurfaceVariant, label = S(R.string.a11y_clear))
            }
            when {
                query.isBlank() -> Hint(Sym.Search, S(R.string.search_tools_hint, all.size))
                hits.isEmpty() -> Hint(Sym.SearchOff, S(R.string.search_no_tool, query.trim()))
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(hits, key = { _, h -> "${h.network}/${h.tool}" }) { i, h ->
                        val roles = AntTheme.net[h.network]
                        Row(
                            Modifier.rise(i, 25).fillMaxWidth().clip(groupShape(i, hits.size)).background(cs.surfaceContainerLow)
                                .clickable { onPick(h.network, h.tool) }.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            LeadingIcon(h.icon, roles.container, roles.onContainer, 44.dp, RoundedCornerShape(14.dp), 24.dp)
                            Column(Modifier.weight(1f)) {
                                Text(h.title, style = rf(16, 22, 600), color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(h.subtitle, style = rf(13, 18), color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Box(Modifier.clip(CircleShape).background(roles.container).padding(horizontal = 10.dp, vertical = 4.dp)) {
                                Text(h.network.label, style = rf(11, 14, 600), color = roles.onContainer, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(icon: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(cs.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Symbol(icon, size = 32.dp, tint = cs.onSurfaceVariant)
        }
        Text(text, Modifier.padding(top = 16.dp), style = rf(14, 20), color = cs.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
