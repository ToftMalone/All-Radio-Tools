package com.allnetworktools.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.allnetworktools.BuildConfig
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.ui.components.PillButton
import com.allnetworktools.ui.components.Symbol
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.ui.theme.cs
import com.allnetworktools.ui.theme.rf
import com.allnetworktools.ui.tools.ProgressBar
import com.allnetworktools.update.UpdateState
import kotlinx.coroutines.launch
import com.allnetworktools.R
import com.allnetworktools.util.S

/** Asks whether to install a newer version, with what changes in it. */
@Composable
fun UpdateDialog(vm: MainViewModel) {
    val state by vm.updater.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val s = state as? UpdateState.Available ?: return
    AlertDialog(
        onDismissRequest = { vm.updater.postpone() },
        icon = { Symbol(Sym.Update, size = 28.dp, tint = cs.primary) },
        title = { Text(S(R.string.version_available, s.info.version)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    S(R.string.you_have_version, BuildConfig.VERSION_NAME) + (if (s.info.sizeBytes > 0) S(R.string.mb_download_without_leaving_the_app).format(java.util.Locale.getDefault(), s.info.sizeBytes / 1e6) else ""),
                    style = rf(14, 20), color = cs.onSurfaceVariant,
                )
                Text(S(R.string.what_s_new), style = rf(14, 20, 600))
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHigh)
                        .verticalScroll(rememberScrollState()).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val items = changelogItems(s.info.notes)
                    if (items.isEmpty()) Text(S(R.string.no_notes_for_this_version), style = rf(13, 18), color = cs.onSurfaceVariant)
                    items.forEach { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("•", style = rf(13, 18, 700), color = cs.primary)
                            Text(line, style = rf(13, 18))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ scope.launch { vm.updater.downloadAndInstall(s.info) } }) { Text(S(R.string.install)) } },
        dismissButton = { TextButton({ vm.updater.postpone() }) { Text(S(R.string.later)) } },
    )
}

/** The release notes as a list: one entry per Markdown bullet, or per paragraph when there are none. */
internal fun changelogItems(notes: String): List<String> {
    val lines = notes.replace("\r", "").lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    val out = ArrayList<String>()
    for (l in lines) {
        val bullet = l.startsWith("- ") || l.startsWith("* ") || l.startsWith("• ")
        val text = (if (bullet) l.substring(2) else l).replace("**", "").replace("`", "").trim()
        if (text.isEmpty()) continue
        if (!bullet && out.isNotEmpty() && lines.any { it.startsWith("- ") || it.startsWith("* ") }) out[out.size - 1] = out.last() + " " + text else out += text
    }
    return out
}

/** Progress of an app update: download, permission to install, result. Hidden when nothing is going on. */
@Composable
fun UpdateBanner(vm: MainViewModel, modifier: Modifier = Modifier) {
    val state by vm.updater.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Back from the "install unknown apps" screen: try again.
    DisposableEffect(lifecycle, state) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME && state is UpdateState.NeedsPermission && vm.updater.canInstall()) scope.launch { vm.updater.install() }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val visible = when (state) {
        is UpdateState.Downloading, is UpdateState.NeedsPermission, is UpdateState.Installing, is UpdateState.Failed -> true
        else -> false
    }
    AnimatedVisibility(visible, modifier, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth()
                .clip(RoundedCornerShape(24.dp)).background(cs.inverseSurface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val fg = cs.inverseOnSurface
            when (val s = state) {
                is UpdateState.Downloading -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Symbol(Sym.Download, size = 24.dp, tint = fg)
                        Column(Modifier.weight(1f)) {
                            Text(S(R.string.updating_to, s.info.version), style = rf(15, 20, 600), color = fg)
                            Text(S(R.string.downloading, (s.progress * 100).toInt()), style = rf(12, 16), color = fg)
                        }
                    }
                    ProgressBar(s.progress, track = fg.copy(alpha = 0.25f))
                }
                is UpdateState.NeedsPermission -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Symbol(Sym.Shield, size = 24.dp, tint = fg)
                        Column(Modifier.weight(1f)) {
                            Text(S(R.string.allow_installation), style = rf(15, 20, 600), color = fg)
                            Text(S(R.string.version_downloaded_android_asks_to_allow, s.info.version), style = rf(12, 16), color = fg)
                        }
                    }
                    PillButton(S(R.string.open_the_setting), { vm.updater.openInstallPermissionSettings() }, Modifier.fillMaxWidth(), height = 44.dp, bg = cs.inversePrimary, fg = cs.onSurface)
                }
                is UpdateState.Installing -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Symbol(Sym.Update, size = 24.dp, tint = fg)
                        Column(Modifier.weight(1f)) {
                            Text(S(R.string.installing_version, s.info.version), style = rf(15, 20, 600), color = fg)
                            Text(S(R.string.confirm_in_the_android_window_if), style = rf(12, 16), color = fg)
                        }
                    }
                }
                is UpdateState.Failed -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Symbol(Sym.Error, size = 24.dp, tint = fg)
                        Text(s.message, Modifier.weight(1f), style = rf(13, 18), color = fg)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (s.info != null) PillButton(S(R.string.retry), { scope.launch { vm.updater.downloadAndInstall(s.info) } }, Modifier.weight(1f), height = 40.dp, bg = cs.inversePrimary, fg = cs.onSurface)
                        PillButton(S(R.string.close), { vm.updater.dismiss() }, Modifier.weight(1f), height = 40.dp, outlined = true, bg = fg)
                    }
                }
                else -> Unit
            }
        }
    }
}
